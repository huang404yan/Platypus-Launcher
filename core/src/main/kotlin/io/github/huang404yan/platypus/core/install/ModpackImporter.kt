package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.modpack.ModpackFile
import io.github.huang404yan.platypus.core.modpack.ModpackKind
import io.github.huang404yan.platypus.core.modpack.ModpackLayout
import io.github.huang404yan.platypus.core.modpack.ModpackManifest
import io.github.huang404yan.platypus.core.modpack.ModpackMeta
import io.github.huang404yan.platypus.core.modpack.ModpackOverrides
import io.github.huang404yan.platypus.core.modpack.ModpackParse
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * 整合包导入在界面上要走的步骤。
 *
 * 与 [InstallStep] 分开而不是往那个枚举里塞：装游戏本体那一段是复用
 * [OneClickInstaller] 的（它自己的步骤表已经在下载页用着），而导入还多出
 * 「解析包」「下包内文件」「解 overrides」三段，混进同一个枚举会让下载页的
 * 步骤列表凭空多出三格永远不会亮的灯。
 */
enum class ModpackStep(val label: String) {
    PARSING("解析整合包"),
    INSTALLING_GAME("安装游戏与加载器"),
    DOWNLOADING_FILES("下载整合包文件"),
    VERIFYING("核对包内文件"),
    EXTRACTING("解压 overrides"),
    FINISHING("写入实例设置"),
    DONE("完成"),
}

/** 一次步骤回调。字段语义与 [StepProgress] 一致，只是换了一套 step 枚举。 */
data class ModpackProgress(
    val step: ModpackStep,
    val detail: String = "",
    val done: Long = 0L,
    val total: Long = 0L,
    /** [total] 是否覆盖了这一步的全部工作（有体积未知的文件时为 false，界面应改显示文件数）。 */
    val totalReliable: Boolean = true,
) {
    val fraction: Float
        get() = if (total <= 0L) 0f else (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

/** 卡在某一步时抛这个：界面才能说清是「解析包」失败还是「下模组」失败。 */
class ModpackImportException(
    val step: ModpackStep,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 只解析、不落盘、不联网的预览结果。
 *
 * 界面上「选完文件先给用户看一眼」用这个：包是什么格式、要装哪个游戏版本、
 * 带哪些加载器、有几个文件要下 —— 用户确认了再点导入。
 */
data class ModpackPreview(
    val archive: File,
    val layout: ModpackLayout,
    val manifest: ModpackManifest,
    /** 建议的实例名（取包名，太长的截断）。 */
    val suggestedName: String,
    /** 包内文件里需要下载的总字节数；有体积未知的文件时为 -1。 */
    val totalDownloadBytes: Long,
) {
    val fileCount: Int get() = manifest.files.size
}

/** 导入完成后的产物。 */
data class ModpackImportOutcome(
    val manifest: ModpackManifest,
    /** 复用的一键装结果（游戏本体 + 加载器 + Java）。 */
    val install: InstallOutcome,
    /** 最终登记下来的实例（可能已经带上额外 JVM 参数）。 */
    val instance: Instance,
    /** 实例目录（`<gameRoot>/versions/<id>`），overrides 就解在这里。 */
    val instanceDir: File,
    val downloadedFiles: Int,
    val downloadedBytes: Long,
    val overrideFiles: Int,
    val elapsedMs: Long,
    /** 汇总后的提示（包自身的警告 + 导入过程中发现的问题）。 */
    val warnings: List<String>,
    /**
     * 导入结束前按清单核对磁盘，发现没落好的文件。
     *
     * 非空就说明这个实例是「半成品」：界面必须说「没装齐」，不能报「导入完成」。
     * 空列表才是真的装齐了。
     */
    val fileIssues: List<ModpackFileIssue> = emptyList(),
) {
    /** 包内文件是否真的全在磁盘上。 */
    val complete: Boolean get() = fileIssues.isEmpty()
}

/**
 * 整合包导入：`.mrpack` / MCBBS v2 包 → 一个能启动的实例。
 *
 * 顺序是：解析包 → 装原版 + 加载器（复用 [OneClickInstaller]）→ 下包内文件 →
 * 按层解 overrides → 把包声明的 JVM 参数写进实例。
 *
 * 为什么先装游戏再下包内文件：包内文件落在实例目录（`versions/<id>/mods/...`），
 * 而实例目录是 [OneClickInstaller] 在注册那一步才由版本号定下来的；先装能一次拿到
 * 确定的路径，也顺带把 Java 补上 —— 加载器安装（Forge/NeoForge 的官方 installer）
 * 本来就要一个能跑的 java 进程。
 *
 * 所有磁盘与网络操作都在 [Dispatchers.IO] 上跑，本类不碰 Compose 状态。
 */
object ModpackImporter {

    /** 元数据文件（index / packmeta / manifest）的体积上限：正常只有几十 KB，超过就是畸形包。 */
    const val MAX_META_BYTES: Long = 8L * 1024 * 1024

    /** 建议实例名的最大长度：列表里放不下太长的名字，超了截断并加省略号。 */
    private const val MAX_NAME_LENGTH = 48

    /** 支持的扩展名，给文件选择器做过滤（zip 其实是 MRPACK 的常见后缀，一并接受）。 */
    val SUPPORTED_EXTENSIONS: List<String> = listOf("mrpack", "zip")

    /**
     * 只读解析：认出格式、读出清单，不做任何写入。
     *
     * 认不出格式或关键字段缺失时抛 [ModpackImportException]，reason 是给人看的一句话。
     */
    suspend fun inspect(archive: File): ModpackPreview = withContext(Dispatchers.IO) {
        readPreview(archive)
    }

    /**
     * 把整合包导入成实例。
     *
     * @param archive 整合包文件（.mrpack / .zip）
     * @param gameDir 游戏根目录（含 versions/、libraries/、assets/），与一键装用的是同一个
     * @param memoryMb 新实例的内存；加载器包默认给得比原版高一点更稳（由调用方决定）
     * @param allowOverwriteUserData 是否允许 overrides 覆盖已存在的存档/截图/日志。
     *   默认 false：导入到一个全新的实例目录时本来就没有这些文件，只有当用户往**已有**实例里
     *   重导同一个包时才会撞上，那种情况宁可跳过并记警告，也不能悄悄盖掉存档。
     */
    suspend fun import(
        archive: File,
        gameDir: File,
        javaRuntimes: JavaRuntimeService,
        instances: InstanceRepository,
        memoryMb: Int = Instance.DEFAULT_MEMORY_MB,
        allowOverwriteUserData: Boolean = false,
        onProgress: (ModpackProgress) -> Unit = {},
    ): ModpackImportOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val warnings = mutableListOf<String>()

        // ① 解析包
        onProgress(ModpackProgress(ModpackStep.PARSING, "读取 " + archive.name))
        val preview = readPreview(archive)
        val manifest = preview.manifest
        warnings += manifest.warnings
        if (manifest.extraGameArgs.isNotEmpty()) {
            // Instance 目前没有「额外游戏参数」字段（只有 extraJvmArgs），
            // 包要求的那几个游戏参数只能先记下来告诉用户，不能假装已经生效。
            warnings += "包要求额外的游戏参数 " + manifest.extraGameArgs.joinToString(" ") +
                "，当前实例模型还不保存游戏参数，需要的话在启动前手动补"
        }

        // ② 装游戏本体 + 加载器（复用一键装，进度原样透传）
        val choice = when (manifest.loader) {
            LoaderKind.FABRIC -> LoaderChoice.Fabric(manifest.loaderVersion)
            LoaderKind.FORGE -> LoaderChoice.Forge(manifest.loaderVersion)
            LoaderKind.NEOFORGE -> LoaderChoice.NeoForge(manifest.loaderVersion)
            // QUILT 在解析阶段就被拒了；OTHER / OPTIFINE 只能装原版，包里的模组大概率跑不起来，
            // 这种情况在解析的 warnings 里已经写过一句。
            else -> LoaderChoice.Vanilla
        }
        onProgress(ModpackProgress(ModpackStep.INSTALLING_GAME, "准备安装 " + manifest.summary))
        val install = try {
            OneClickInstaller.install(
                gameDir = gameDir,
                versionId = manifest.gameVersion,
                javaRuntimes = javaRuntimes,
                instances = instances,
                memoryMb = memoryMb,
                instanceName = manifest.name,
                loader = choice,
            ) { step ->
                if (step.step == InstallStep.IDLE || step.step == InstallStep.DONE) return@install
                val detail = if (step.detail.isBlank()) step.step.label else step.step.label + "：" + step.detail
                onProgress(
                    ModpackProgress(
                        ModpackStep.INSTALLING_GAME,
                        detail,
                        done = step.done,
                        total = step.total,
                        totalReliable = step.totalReliable,
                    ),
                )
            }
        } catch (e: InstallStepException) {
            throw ModpackImportException(
                ModpackStep.INSTALLING_GAME,
                "安装游戏本体失败（" + e.step.label + "）：" + describe(e),
                e,
            )
        }

        val instanceDir = File(install.instance.directory)
        if (!instanceDir.isDirectory && !instanceDir.mkdirs()) {
            throw ModpackImportException(
                ModpackStep.INSTALLING_GAME,
                "无法创建实例目录：" + instanceDir.absolutePath,
            )
        }
        // 顺手把实例该有的子目录建出来，用户打开目录就能看到 mods/、config/ 在哪。
        InstanceLayouts.perInstanceDirs(instanceDir).forEach { runCatching { it.mkdirs() } }

        // ③ 下包内文件（MCBBS 包没有这一段）
        var downloadedFiles = 0
        var downloadedBytes = 0L
        // 核对结果要在 if 外面可见：MCBBS 包没有文件段，天然就是空（没有什么可核的）。
        var fileIssues: List<ModpackFileIssue> = emptyList()
        if (manifest.files.isNotEmpty()) {
            val items = manifest.files.map { f ->
                DownloadItem(
                    url = f.url,
                    target = File(instanceDir, f.path).absolutePath,
                    sha1 = f.sha1,
                    size = f.size,
                    mirrors = f.mirrors,
                )
            }
            // 串行预建目录，规避多线程同时 mkdirs 的竞态（与 VanillaInstaller.download 同因）。
            items.mapNotNull { File(it.target).parentFile }.distinct().forEach { it.mkdirs() }
            val knownTotal = manifest.files.filter { it.size > 0 }.sumOf { it.size }
            val allSized = manifest.files.all { it.size > 0 }
            onProgress(
                ModpackProgress(
                    ModpackStep.DOWNLOADING_FILES,
                    "共 " + items.size + " 个文件",
                    done = 0L,
                    total = knownTotal,
                    totalReliable = allSized,
                ),
            )
            val report = try {
                HttpDownloader().download(items) { snapshot ->
                    downloadedBytes = snapshot.completedBytes
                    onProgress(
                        ModpackProgress(
                            ModpackStep.DOWNLOADING_FILES,
                            "已完成 " + snapshot.completedFiles + " / " + snapshot.totalFiles + " 个文件",
                            done = snapshot.completedBytes,
                            total = snapshot.totalBytes,
                            totalReliable = snapshot.fractionReliable,
                        ),
                    )
                }
            } catch (e: Exception) {
                throw ModpackImportException(
                    ModpackStep.DOWNLOADING_FILES,
                    "下载整合包文件中断：" + describe(e),
                    e,
                )
            }
            if (!report.ok) {
                val first = report.failures.first()
                throw ModpackImportException(
                    ModpackStep.DOWNLOADING_FILES,
                    report.failures.size.toString() + " 个文件下载失败，第一个是 " +
                        File(first.item.target).name + "：" + first.reason,
                )
            }
            downloadedFiles = report.succeeded
            if (downloadedBytes <= 0L) downloadedBytes = knownTotal

            // ③.5 落盘核对：下载器只保证「下完那一刻」是对的，而这一整段可能跨好几分钟、
            // 上百个文件，中途被取消/被杀/被别的程序改动都会让磁盘和清单对不上。
            // 不在这里核一遍，界面就只能凭「下载器没报错」宣布导入完成 —— 那正是
            // 「77 个文件少 1 个还显示导入完成」的来源。核对结果不抛异常，带回给界面：
            // 实例已经注册好了，抛异常会让用户看到一个「失败」的实例却不知道它少了什么。
            fileIssues = verifyPackedFiles(instanceDir, manifest.files, onProgress)
        }

        // ④ 解 overrides：按层顺序覆盖，后面的盖前面的
        var overrideFiles = 0
        if (manifest.overrideDirs.isNotEmpty()) {
            onProgress(ModpackProgress(ModpackStep.EXTRACTING, "解压 " + manifest.overrideDirs.joinToString(" → ")))
            overrideFiles = extractOverrides(
                archive = archive,
                basePrefix = preview.layout.basePrefix,
                manifest = manifest,
                instanceDir = instanceDir,
                allowOverwriteUserData = allowOverwriteUserData,
            ) { skipped -> warnings += skipped }
        }

        // ⑤ 把包声明的 JVM 参数写进实例
        onProgress(ModpackProgress(ModpackStep.FINISHING, "写入实例设置"))
        var instance = install.instance
        val extraJvm = manifest.extraJvmArgs.map { it.trim() }.filter { it.isNotEmpty() }
        if (extraJvm.isNotEmpty()) {
            instance = instance.copy(extraJvmArgs = (instance.extraJvmArgs + extraJvm).distinct())
            try {
                instances.save(instance)
            } catch (e: Exception) {
                warnings += "把整合包的 JVM 参数写进实例失败：" + describe(e)
            }
        }

        onProgress(ModpackProgress(ModpackStep.DONE, "可以启动了", done = 1L, total = 1L))
        ModpackImportOutcome(
            manifest = manifest,
            install = install,
            instance = instance,
            instanceDir = instanceDir,
            downloadedFiles = downloadedFiles,
            downloadedBytes = downloadedBytes,
            overrideFiles = overrideFiles,
            elapsedMs = System.currentTimeMillis() - startedAt,
            warnings = warnings.distinct(),
            fileIssues = fileIssues,
        )
    }

    /**
     * 按清单核对刚下完的文件，返回对不上的那些。
     *
     * 进度用「已核对 X / Y 个文件」上报：文件多的时候这一步本身也要几秒（大小未知的要算 sha1），
     * 界面不能停在「下载 77 / 77」上不动 —— 那看起来像卡住了。
     */
    private suspend fun verifyPackedFiles(
        instanceDir: File,
        files: List<ModpackFile>,
        onProgress: (ModpackProgress) -> Unit,
    ): List<ModpackFileIssue> {
        if (files.isEmpty()) return emptyList()
        onProgress(ModpackProgress(ModpackStep.VERIFYING, "核对 " + files.size + " 个文件", done = 0L, total = files.size.toLong()))
        val issues = ModpackVerifier.verify(instanceDir, files) { done, total ->
            onProgress(
                ModpackProgress(
                    ModpackStep.VERIFYING,
                    "已核对 " + done + " / " + total + " 个文件",
                    done = done.toLong(),
                    total = total.toLong(),
                ),
            )
        }
        return issues
    }

    /**
     * 把 `overrides/<layer>` 下的文件写到实例目录。
     *
     * 两层防护缺一不可：
     * 1. [ModpackOverrides.relativePath] 已经过了一遍 `safeRelativePath`（拦 `..`、盘符、绝对路径）；
     * 2. 写盘前再比一次 canonicalPath，防的是 `..` 藏在基础前缀和层名之间那类绕过。
     */
    private fun extractOverrides(
        archive: File,
        basePrefix: String,
        manifest: ModpackManifest,
        instanceDir: File,
        allowOverwriteUserData: Boolean,
        onWarning: (String) -> Unit,
    ): Int {
        var written = 0
        var skippedUserData = 0
        var skippedEscape = 0
        val rootPath = instanceDir.canonicalPath
        ZipFile(archive).use { zip ->
            // 外层循环是「层」，内层是 entry：层与层之间的覆盖顺序就是这么保证的
            // （后面层的同名文件后写，自然盖掉前面层）。
            for (layer in manifest.overrideDirs) {
                for (entry in zip.entries().asSequence()) {
                    if (entry.isDirectory) continue
                    val relative = ModpackOverrides.relativePath(
                        entryName = entry.name,
                        basePrefix = basePrefix,
                        prefixes = listOf(layer),
                    ) ?: continue
                    val target = File(instanceDir, relative)
                    val targetPath = target.canonicalPath
                    if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
                        skippedEscape++
                        continue
                    }
                    if (ModpackOverrides.touchesUserData(relative) && target.exists() && !allowOverwriteUserData) {
                        skippedUserData++
                        continue
                    }
                    try {
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        written++
                    } catch (e: Exception) {
                        onWarning("解压 " + relative + " 失败：" + describe(e))
                    }
                }
            }
        }
        if (skippedUserData > 0) {
            onWarning(
                skippedUserData.toString() + " 个 overrides 文件会覆盖已有存档/截图/日志，已跳过（" +
                    "需要覆盖请允许后再导入）",
            )
        }
        if (skippedEscape > 0) {
            onWarning(skippedEscape.toString() + " 个 overrides 文件的目标路径越出了实例目录，已丢弃")
        }
        return written
    }

    /** 开 zip、认格式、读标记文件、解析成清单。不写盘。 */
    private fun readPreview(archive: File): ModpackPreview {
        if (!archive.isFile) {
            throw ModpackImportException(ModpackStep.PARSING, "文件不存在：" + archive.absolutePath)
        }
        if (archive.length() <= 0L) {
            throw ModpackImportException(ModpackStep.PARSING, "文件是空的：" + archive.name)
        }
        val entryNames: List<String>
        val layout: ModpackLayout
        val manifest: ModpackManifest
        try {
            ZipFile(archive).use { zip ->
                entryNames = zip.entries().asSequence().map { it.name }.toList()
                if (entryNames.isEmpty()) {
                    throw ModpackImportException(ModpackStep.PARSING, "压缩包里没有任何文件：" + archive.name)
                }
                layout = ModpackMeta.detect(entryNames)
                manifest = when (layout.kind) {
                    ModpackKind.MRPACK -> {
                        val text = readEntry(zip, layout.entry(ModpackMeta.MRPACK_INDEX))
                            ?: throw ModpackImportException(
                                ModpackStep.PARSING,
                                "包里找不到 " + ModpackMeta.MRPACK_INDEX,
                            )
                        when (val parsed = ModpackMeta.parseMrpack(text, archive.nameWithoutExtension)) {
                            is ModpackParse.Ok -> parsed.value
                            is ModpackParse.Failure -> throw ModpackImportException(
                                ModpackStep.PARSING,
                                parsed.reason,
                            )
                        }
                    }
                    ModpackKind.MCBBS_V2 -> {
                        val packmeta = readEntry(zip, layout.entry(ModpackMeta.MCBBS_META))
                        val cfManifest = readEntry(zip, layout.entry(ModpackMeta.MCBBS_MANIFEST))
                        when (val parsed = ModpackMeta.parseMcbbs(packmeta, cfManifest, archive.nameWithoutExtension)) {
                            is ModpackParse.Ok -> parsed.value
                            is ModpackParse.Failure -> throw ModpackImportException(
                                ModpackStep.PARSING,
                                parsed.reason,
                            )
                        }
                    }
                    ModpackKind.UNKNOWN -> throw ModpackImportException(
                        ModpackStep.PARSING,
                        "认不出这个整合包格式（既没有 " + ModpackMeta.MRPACK_INDEX +
                            "，也没有 " + ModpackMeta.MCBBS_META + "）。" +
                            "如果是 Prism/HMCL 的 mmc-pack.json 包，本启动器还没实现。",
                    )
                }
            }
        } catch (e: ModpackImportException) {
            throw e
        } catch (e: Exception) {
            throw ModpackImportException(
                ModpackStep.PARSING,
                "打不开压缩包（不是 zip 或文件损坏）：" + describe(e),
                e,
            )
        }

        val sized = manifest.files.filter { it.size > 0 }.sumOf { it.size }
        val allSized = manifest.files.all { it.size > 0 }
        return ModpackPreview(
            archive = archive,
            layout = layout,
            manifest = manifest,
            suggestedName = suggestName(manifest.name, archive.nameWithoutExtension),
            totalDownloadBytes = if (manifest.files.isEmpty() || allSized) sized else -1L,
        )
    }

    /** 读一个 zip entry 的文本；不存在或过大返回 null。带 UTF-8 BOM 的先剥掉。 */
    private fun readEntry(zip: ZipFile, name: String, limit: Long = MAX_META_BYTES): String? {
        val entry = zip.getEntry(name) ?: return null
        if (entry.isDirectory) return null
        if (entry.size > limit) return null
        val bytes = zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
        return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    }

    /** 实例名：优先包名，空白就退回文件名，过长截断（列表里放不下）。 */
    private fun suggestName(packName: String, fallback: String): String {
        val raw = packName.trim().ifEmpty { fallback.trim() }
        val name = raw.ifEmpty { "未命名整合包" }
        return if (name.length <= MAX_NAME_LENGTH) name else name.take(MAX_NAME_LENGTH - 1) + "…"
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: (error::class.simpleName ?: "未知错误")
}
