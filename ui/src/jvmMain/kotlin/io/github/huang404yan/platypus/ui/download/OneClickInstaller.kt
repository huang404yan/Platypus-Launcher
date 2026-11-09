package io.github.huang404yan.platypus.ui.download

import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.loader.FabricMeta
import io.github.huang404yan.platypus.core.loader.ForgeMeta
import io.github.huang404yan.platypus.core.loader.NeoForgeMeta
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeService
import io.github.huang404yan.platypus.core.runtime.findExactMajor
import io.github.huang404yan.platypus.core.version.DownloadPlanner
import io.github.huang404yan.platypus.core.version.VersionManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 一键装的分步状态；界面按这个顺序显示每一格的结果。 */
enum class InstallStep(val label: String) {
    IDLE("未开始"),
    PLANNING("解析版本元数据"),
    DOWNLOADING("下载游戏文件"),
    JAVA_CHECK("检查 Java 运行时"),
    JAVA_INSTALL("下载 Java 运行时"),
    LOADER_INSTALL("安装加载器"),
    REGISTERING("登记实例"),
    DONE("完成"),
}

/** 一次步骤回调：step 是当前步骤，detail 是给人看的一句话，done/total 用于进度条。 */
data class StepProgress(
    val step: InstallStep,
    val detail: String = "",
    val done: Long = 0L,
    val total: Long = 0L,
    /**
     * [total] 是否覆盖了这一步的全部工作。
     *
     * 下载阶段若有条目体积未知（没有 size 也没有 sha1 可推算），分母就少了那几项，
     * 百分比会虚高、看着「早该下完却停在 99%」。这种时候界面该只显示文件数，不显示进度条。
     */
    val totalReliable: Boolean = true,
) {
    val fraction: Float
        get() = if (total <= 0L) 0f else (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** 卡在某一步时抛这个，界面才能说清是「下载」还是「补 Java」失败。 */
class InstallStepException(
    val step: InstallStep,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 一键装的最终产物。 */
data class InstallOutcome(
    val instance: Instance,
    val versionId: String,
    val java: JavaRuntime,
    /** true 表示这次真的从 Mojang 下了一个运行时，false 表示本机已有精确主版本。 */
    val javaInstalled: Boolean,
    val downloadReport: DownloadReport,
    val elapsedMs: Long,
    /** 装了加载器时的 Fabric 安装结果；原版安装为 null。 */
    val fabric: FabricInstallResult? = null,
    /** 装了加载器时的 Forge 安装结果；原版安装为 null。 */
    val forge: ForgeInstallResult? = null,
    /** 装了加载器时的 NeoForge 安装结果；原版安装为 null。 */
    val neoforge: NeoForgeInstallResult? = null,
)

/**
 * 要顺带装的加载器。
 *
 * 安装顺序（原版 → 补 Java → 加载器 → 登记）对两种加载器都一样：先有原版文件，
 * 加载器才能以 inheritsFrom 叠在它上面。差别只在实现方式：Fabric 是拉一份 profile json
 * 再下几个库；Forge 要跑官方 installer（所以它必须排在补 Java 之后——得有个能用的 java 进程）。
 */
sealed interface LoaderChoice {
    /** 只装原版，不加加载器。 */
    data object Vanilla : LoaderChoice

    /** Fabric；loaderVersion 留空表示装最新的 stable。 */
    data class Fabric(val loaderVersion: String? = null) : LoaderChoice

    /** Forge；forgeVersion 留空表示装 recommended（没有推荐就取 latest）。 */
    data class Forge(val forgeVersion: String? = null) : LoaderChoice

    /**
     * NeoForge；neoforgeVersion 留空表示按游戏版本从清单里挑（优先稳定版）。
     *
     * NeoForge 是 Forge 分家后的新线，安装方式与 Forge 一样跑官方 installer，
     * 但版本号体系完全不同（没有 1.20.1-47.4.0 这种带原版号的坐标），
     * 细节见 [NeoForgeInstaller] 的类注释。
     */
    data class NeoForge(val neoforgeVersion: String? = null) : LoaderChoice
}

/**
 * 「选版本 → 下载 → 补 Java → 建实例」的一条龙。
 *
 * 每一步失败都折算成 [InstallStepException]，step 指向真正卡住的那一步；
 * 下载与解压都在调用方给的后台调度器上跑（本函数本身是 suspend 的，直接写状态即可）。
 */
object OneClickInstaller {

    suspend fun install(
        gameDir: File,
        versionId: String,
        javaRuntimes: JavaRuntimeService,
        instances: InstanceRepository,
        manifest: VersionManifest? = null,
        memoryMb: Int = Instance.DEFAULT_MEMORY_MB,
        /** 自定义实例名；留空或全空白时退回版本号，保证老调用方行为不变。 */
        instanceName: String? = null,
        /** 要装的加载器；null 表示只装原版（老行为）。 */
        loader: LoaderChoice? = null,
        onProgress: (StepProgress) -> Unit = {},
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()

        // ① 解析版本元数据（会把 <game>/versions/<id>/<id>.json 写盘）
        onProgress(StepProgress(InstallStep.PLANNING, "解析 " + versionId + " 的版本元数据"))
        val plan = try {
            VanillaInstaller.plan(gameDir, versionId, manifest)
        } catch (e: Exception) {
            throw InstallStepException(
                InstallStep.PLANNING,
                "拉取版本元数据失败：" + describe(e),
                e,
            )
        }

        // ② 下载客户端 / 库 / 资源
        onProgress(
            StepProgress(
                InstallStep.DOWNLOADING,
                "共 " + plan.totalFiles + " 个文件",
                done = 0L,
                total = plan.totalBytes,
            ),
        )
        val report = try {
            VanillaInstaller.download(plan.items) { snapshot ->
                // 续传过的文件单独说一句，用户才知道不是从头再下一遍。
                val resumed = if (snapshot.resumedFiles > 0) {
                    "，其中 " + snapshot.resumedFiles + " 个接着上次下（省了 " +
                        (snapshot.resumedBytes / 1024L / 1024L) + " MB）"
                } else {
                    ""
                }
                onProgress(
                    StepProgress(
                        InstallStep.DOWNLOADING,
                        "已完成 " + snapshot.completedFiles + " / " + snapshot.totalFiles + " 个文件" + resumed,
                        done = snapshot.completedBytes,
                        total = snapshot.totalBytes,
                        // 有体积未知的文件时分母不全，界面改用文件数而不是百分比
                        totalReliable = snapshot.fractionReliable,
                    ),
                )
            }
        } catch (e: Exception) {
            throw InstallStepException(InstallStep.DOWNLOADING, "下载中断：" + describe(e), e)
        }
        if (!report.ok) {
            val first = report.failures.first()
            throw InstallStepException(
                InstallStep.DOWNLOADING,
                report.failures.size.toString() + " 个文件下载失败，第一个是 " +
                    File(first.item.target).name + "：" + first.reason,
            )
        }

        // ③ Java：先看本机有没有精确主版本，没有就按版本 json 的 javaVersion.component 现下
        val requiredMajor = plan.javaMajorVersion
        onProgress(StepProgress(InstallStep.JAVA_CHECK, "游戏需要 Java " + requiredMajor))
        val local = try {
            javaRuntimes.detect()
        } catch (e: Exception) {
            throw InstallStepException(InstallStep.JAVA_CHECK, "探测本机 Java 失败：" + describe(e), e)
        }
        val existing = local.findExactMajor(requiredMajor)
        val javaInstalled = existing == null
        val java = if (existing != null) {
            existing
        } else {
            onProgress(
                StepProgress(
                    InstallStep.JAVA_INSTALL,
                    "本机没有 Java " + requiredMajor + "，开始下载" +
                        (plan.javaComponent?.let { "（" + it + "）" } ?: ""),
                    total = 100L,
                ),
            )
            try {
                javaRuntimes.ensureRuntime(requiredMajor, plan.javaComponent) { fraction ->
                    onProgress(
                        StepProgress(
                            InstallStep.JAVA_INSTALL,
                            "正在下载 Java " + requiredMajor,
                            done = (fraction * 100f).toLong(),
                            total = 100L,
                        ),
                    )
                }
            } catch (e: Exception) {
                throw InstallStepException(
                    InstallStep.JAVA_INSTALL,
                    "补 Java " + requiredMajor + " 失败：" + describe(e),
                    e,
                )
            }
        }

        // ④ 加载器：原版文件已经就位，Fabric 只是在它上面叠一层 inheritsFrom
        var effectiveVersionId = plan.versionId
        var fabric: FabricInstallResult? = null
        if (loader is LoaderChoice.Fabric) {
            val wanted = loader.loaderVersion?.trim().orEmpty()
            onProgress(
                StepProgress(
                    InstallStep.LOADER_INSTALL,
                    if (wanted.isEmpty()) "查询 Fabric 最新稳定版" else "安装 Fabric " + wanted,
                ),
            )
            try {
                fabric = FabricInstaller.install(
                    gameRoot = gameDir,
                    vanillaVersion = plan.versionId,
                    loaderVersion = wanted.takeIf { it.isNotEmpty() },
                ) { p ->
                    onProgress(
                        StepProgress(
                            InstallStep.LOADER_INSTALL,
                            p.step.label + if (p.detail.isBlank()) "" else "：" + p.detail,
                            done = p.done,
                            total = p.total,
                        ),
                    )
                }
            } catch (e: FabricInstallException) {
                throw InstallStepException(
                    InstallStep.LOADER_INSTALL,
                    "Fabric " + e.step.label + "失败：" + describe(e),
                    e,
                )
            } catch (e: Exception) {
                throw InstallStepException(
                    InstallStep.LOADER_INSTALL,
                    "装 Fabric 失败：" + describe(e),
                    e,
                )
            }
            effectiveVersionId = fabric.versionId
        }
        var forge: ForgeInstallResult? = null
        if (loader is LoaderChoice.Forge) {
            val wanted = loader.forgeVersion?.trim().orEmpty()
            onProgress(
                StepProgress(
                    InstallStep.LOADER_INSTALL,
                    if (wanted.isEmpty()) "查询 Forge 推荐版本" else "安装 Forge " + wanted,
                ),
            )
            try {
                // 用刚确定下来的那个 java 跑安装器：老版本 Forge（1.16 及以前）只认 Java 8，
                // 直接拿本机默认 java 常常跑不起来，而这里拿的正是这个游戏版本要求的精确主版本。
                forge = ForgeInstaller.install(
                    gameRoot = gameDir,
                    vanillaVersion = plan.versionId,
                    javaPath = java.path,
                    forgeVersion = wanted.takeIf { it.isNotEmpty() },
                ) { p ->
                    onProgress(
                        StepProgress(
                            InstallStep.LOADER_INSTALL,
                            p.step.label + if (p.detail.isBlank()) "" else "：" + p.detail,
                            done = p.done,
                            total = p.total,
                        ),
                    )
                }
            } catch (e: ForgeInstallException) {
                throw InstallStepException(
                    InstallStep.LOADER_INSTALL,
                    "Forge " + e.step.label + "失败：" + describe(e),
                    e,
                )
            } catch (e: Exception) {
                throw InstallStepException(
                    InstallStep.LOADER_INSTALL,
                    "装 Forge 失败：" + describe(e),
                    e,
                )
            }
            effectiveVersionId = forge.versionId
        }
        var neoforge: NeoForgeInstallResult? = null
        if (loader is LoaderChoice.NeoForge) {
            val wanted = loader.neoforgeVersion?.trim().orEmpty()
            onProgress(
                StepProgress(
                    InstallStep.LOADER_INSTALL,
                    if (wanted.isEmpty()) "查询 " + plan.gameVersion + " 对应的 NeoForge 版本" else "安装 NeoForge " + wanted,
                ),
            )
            try {
                // 与 Forge 同理，用这个游戏版本要求的精确主版本来跑安装器。
                // NeoForge 的 installer 会在跑之前复核 jar 内 inheritsFrom，装错版本会被拦下。
                neoforge = NeoForgeInstaller.install(
                    gameRoot = gameDir,
                    vanillaVersion = plan.versionId,
                    javaPath = java.path,
                    neoforgeVersion = wanted.takeIf { it.isNotEmpty() },
                ) { p ->
                    onProgress(
                        StepProgress(
                            InstallStep.LOADER_INSTALL,
                            p.step.label + if (p.detail.isBlank()) "" else "：" + p.detail,
                            done = p.done,
                            total = p.total,
                        ),
                    )
                }
            } catch (e: NeoForgeInstallException) {
                throw InstallStepException(
                    InstallStep.LOADER_INSTALL,
                    "NeoForge " + e.step.label + "失败：" + describe(e),
                    e,
                )
            } catch (e: Exception) {
                throw InstallStepException(
                    InstallStep.LOADER_INSTALL,
                    "装 NeoForge 失败：" + describe(e),
                    e,
                )
            }
            effectiveVersionId = neoforge.versionId
        }

        // ⑤ 登记实例
        onProgress(StepProgress(InstallStep.REGISTERING, "写入实例登记"))
        val loaderVersion = fabric?.loaderVersion ?: forge?.forgeVersion ?: neoforge?.neoforgeVersion
        val loaderKind = when {
            fabric != null -> LoaderKind.FABRIC
            forge != null -> LoaderKind.FORGE
            neoforge != null -> LoaderKind.NEOFORGE
            else -> LoaderKind.VANILLA
        }
        val displayName = instanceName?.trim()?.takeIf { it.isNotEmpty() }
            ?: when {
                fabric != null -> FabricMeta.displayName(plan.gameVersion, fabric.loaderVersion)
                forge != null -> ForgeMeta.displayName(plan.gameVersion, forge.forgeVersion)
                // NeoForge 的版本号自身就带着 MC 版本语义，不再重复原版号
                neoforge != null -> NeoForgeMeta.displayName(neoforge.neoforgeVersion)
                else -> plan.versionId
            }
        val instance = Instance(
            id = effectiveVersionId,
            name = displayName,
            gameVersion = plan.gameVersion,
            loader = loaderKind,
            loaderVersion = loaderVersion,
            directory = DownloadPlanner.versionDir(gameDir, effectiveVersionId).absolutePath,
            javaPath = java.path,
            memoryMb = Instance.clampMemoryMb(memoryMb),
            registered = true,
        )
        try {
            instances.save(instance)
        } catch (e: Exception) {
            throw InstallStepException(InstallStep.REGISTERING, "写实例登记失败：" + describe(e), e)
        }

        onProgress(StepProgress(InstallStep.DONE, "可以启动了", done = 1L, total = 1L))
        InstallOutcome(
            instance = instance,
            versionId = effectiveVersionId,
            java = java,
            javaInstalled = javaInstalled,
            downloadReport = report,
            elapsedMs = System.currentTimeMillis() - startedAt,
            fabric = fabric,
            forge = forge,
            neoforge = neoforge,
        )
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: (error::class.simpleName ?: "未知错误")
}
