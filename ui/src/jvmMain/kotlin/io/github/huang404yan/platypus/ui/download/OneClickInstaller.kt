package io.github.huang404yan.platypus.ui.download

import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceRepository
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
    REGISTERING("登记实例"),
    DONE("完成"),
}

/** 一次步骤回调：step 是当前步骤，detail 是给人看的一句话，done/total 用于进度条。 */
data class StepProgress(
    val step: InstallStep,
    val detail: String = "",
    val done: Long = 0L,
    val total: Long = 0L,
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
)

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
                onProgress(
                    StepProgress(
                        InstallStep.DOWNLOADING,
                        "已完成 " + snapshot.completedFiles + " / " + snapshot.totalFiles + " 个文件",
                        done = snapshot.completedBytes,
                        total = snapshot.totalBytes,
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

        // ④ 登记实例
        onProgress(StepProgress(InstallStep.REGISTERING, "写入实例登记"))
        val instance = Instance(
            id = plan.versionId,
            name = plan.versionId,
            gameVersion = plan.gameVersion,
            directory = DownloadPlanner.versionDir(gameDir, plan.versionId).absolutePath,
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
            versionId = plan.versionId,
            java = java,
            javaInstalled = javaInstalled,
            downloadReport = report,
            elapsedMs = System.currentTimeMillis() - startedAt,
        )
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: (error::class.simpleName ?: "未知错误")
}
