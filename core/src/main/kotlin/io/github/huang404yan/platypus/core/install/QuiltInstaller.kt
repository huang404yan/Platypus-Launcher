package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.download.RemoteChecksums
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.loader.FabricFiles
import io.github.huang404yan.platypus.core.loader.FabricLibraries
import io.github.huang404yan.platypus.core.loader.QuiltLoaderVersion
import io.github.huang404yan.platypus.core.loader.QuiltMeta
import io.github.huang404yan.platypus.core.loader.QuiltParse
import io.github.huang404yan.platypus.core.loader.parseQuiltLoaderVersions
import io.github.huang404yan.platypus.core.loader.parseQuiltProfile
import io.github.huang404yan.platypus.core.version.DownloadPlanner
import io.github.huang404yan.platypus.core.version.VersionJson
import io.github.huang404yan.platypus.core.version.parseVersionJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * Quilt 安装：拉元数据 → 写版本 json → 规划并下载 libraries。
 *
 * 与 [FabricInstaller] 是同一个流程的两份实现，而不是一份加上「加载器类型」参数。理由：
 * 两边看着像，差异却都在要命的地方 —— Quilt 的列表没有 stable 字段、顺序也不按版本号排
 * （见 [QuiltMeta] 的类注释），两者的 profile json 校验条件也不一样。合成一个带分支的
 * 函数以后每改一边都得想起另一边，拆开以后各自能独立演进，代价只是几十行重复。
 *
 * 落盘与下载复用 [FabricFiles] 与 [FabricLibraries]：它们是「版本 json 与 maven 坐标」的工具，
 * 与加载器叫什么名字无关。不负责原版文件与 Java —— 那些由 [OneClickInstaller] 串起来，
 * 因为 profile json 是 inheritsFrom 原版的，缺了父版本根本起不来。
 */
object QuiltInstaller {

    /** 只拉 loader 版本列表，给 UI 选版本用。 */
    suspend fun listLoaders(gameVersion: String): List<QuiltLoaderVersion> = withContext(Dispatchers.IO) {
        val text = VanillaInstaller.fetchText(listOf(QuiltMeta.loaderListUrl(gameVersion)))
        when (val parsed = parseQuiltLoaderVersions(text)) {
            is QuiltParse.Ok -> parsed.value
            is QuiltParse.Failure -> throw IOException(parsed.reason)
        }
    }

    /**
     * 装 Quilt 到 gameRoot。
     *
     * @param vanillaVersion 原版版本号（1.20.1）；它也必须是 profile 的 inheritsFrom。
     * @param loaderVersion 具体 loader 版本（0.24.0）；传空则按 [QuiltMeta.latestGrown] 挑一个正式版。
     */
    suspend fun install(
        gameRoot: File,
        vanillaVersion: String,
        loaderVersion: String? = null,
        parallelism: Int = HttpDownloader.DEFAULT_PARALLELISM,
        onProgress: (QuiltProgress) -> Unit = {},
    ): QuiltInstallResult = withContext(Dispatchers.IO) {
        val started = Instant.now()
        var step = QuiltStep.LOADER_LIST

        try {
            val chosen = if (!loaderVersion.isNullOrBlank()) {
                loaderVersion.trim()
            } else {
                onProgress(QuiltProgress(QuiltStep.LOADER_LIST, "查询可用 loader 版本…"))
                val versions = listLoaders(vanillaVersion)
                val latest = QuiltMeta.latestGrown(versions)
                    ?: throw IOException("Quilt 元数据里没有可用于 " + vanillaVersion + " 的 loader 版本")
                latest.version
            }

            step = QuiltStep.PROFILE
            onProgress(QuiltProgress(QuiltStep.PROFILE, "拉取 Quilt " + chosen + " 的 profile json…"))
            val profileText = VanillaInstaller.fetchText(
                listOf(QuiltMeta.profileUrl(vanillaVersion, chosen)),
            )
            val profile = when (val parsed = parseQuiltProfile(profileText, vanillaVersion, chosen)) {
                is QuiltParse.Ok -> parsed.value
                is QuiltParse.Failure -> throw IOException(parsed.reason)
            }

            step = QuiltStep.WRITE_JSON
            val versionId = QuiltMeta.versionId(vanillaVersion, chosen)
            onProgress(QuiltProgress(step, "写入版本 json：" + versionId))
            val jsonFile = FabricFiles.writeProfile(gameRoot, versionId, profile.raw)

            step = QuiltStep.LIBRARIES
            val librariesDir = DownloadPlanner.librariesDir(gameRoot)
            val planned = FabricLibraries.plan(
                version = profile.versionJson,
                librariesDir = librariesDir,
            )
            // Quilt 的 profile 同样有库不带 sha1 与 size（实测 14 个库里一个都没带）。
            // 不补的话：包下坏了发现不了、进度条分母偏小、已下过的也判断不出能跳过。
            // 补不到就按原样继续 —— 少一层保险比不上整个安装失败。
            val checksummed = RemoteChecksums().enrichWithStats(planned)
            val items = checksummed.items
            onProgress(
                QuiltProgress(
                    step = QuiltStep.LIBRARIES,
                    detail = if (checksummed.filled > 0) {
                        "下载 " + items.size + " 个 Quilt 库（补齐 " + checksummed.filled + " 个库的 sha1/体积）"
                    } else {
                        "下载 " + items.size + " 个 Quilt 库"
                    },
                ),
            )

            // 目录先串行建好，避免多个并发线程同时 mkdirs 打架（HttpDownloader 里也防了一手）。
            items.mapNotNull { File(it.target).parentFile }.distinct().forEach { it.mkdirs() }

            val report = HttpDownloader(parallelism).download(items) { p ->
                onProgress(
                    QuiltProgress(
                        step = QuiltStep.LIBRARIES,
                        detail = chosen + " 库 " + p.completedFiles + "/" + p.totalFiles,
                        done = p.completedBytes,
                        total = p.totalBytes,
                    ),
                )
            }
            if (!report.ok) {
                val first = report.failures.first()
                throw IOException(
                    report.failures.size.toString() + " 个 Quilt 库下载失败，第一个是 " +
                        File(first.item.target).name + "：" + first.reason,
                )
            }

            QuiltInstallResult(
                versionId = versionId,
                jsonFile = jsonFile,
                loaderVersion = chosen,
                versionJson = profile.versionJson,
                downloadReport = report,
                downloadedBytes = items.sumOf { if (it.size > 0) it.size else 0L },
                elapsedMs = Duration.between(started, Instant.now()).toMillis(),
                checksumFilled = checksummed.filled,
                checksumMissing = checksummed.stillMissing,
            )
        } catch (e: CancellationException) {
            // 用户点了取消：这不是安装失败，别包成 QuiltInstallException 让界面报红。
            throw e
        } catch (e: Exception) {
            if (e is QuiltInstallException) throw e
            throw QuiltInstallException(step, e.message ?: e::class.simpleName ?: "未知错误", e)
        }
    }

    /** 扫描磁盘上已装的 Quilt 版本，给 UI 做「已安装」提示与重复安装判断。 */
    fun installedLoaders(gameRoot: File, vanillaVersion: String): List<InstalledQuilt> {
        val versionsRoot = File(gameRoot, "versions")
        if (!versionsRoot.isDirectory) return emptyList()
        return (versionsRoot.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val json = File(dir, dir.name + ".json")
                if (!json.isFile) return@mapNotNull null
                val text = runCatching { json.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
                val parsed = runCatching { parseVersionJson(text) }.getOrNull() ?: return@mapNotNull null
                if (parsed.inheritsFrom != vanillaVersion) return@mapNotNull null
                if (!parsed.mainClass.contains("quilt", ignoreCase = true)) return@mapNotNull null
                val loader = QuiltMeta.loaderVersionFromId(dir.name) ?: return@mapNotNull null
                InstalledQuilt(versionId = dir.name, loaderVersion = loader)
            }
            .sortedWith { a, b -> QuiltMeta.compareVersions(a.loaderVersion, b.loaderVersion) }
    }

    /** 建实例：gameVersion 仍是原版号，loader 标 QUILT。 */
    suspend fun register(
        instances: InstanceRepository,
        gameRoot: File,
        versionId: String,
        loaderVersion: String,
        gameVersion: String,
        javaPath: String?,
        memoryMb: Int,
        instanceName: String? = null,
    ): Instance {
        val instance = Instance(
            id = versionId,
            name = instanceName?.takeIf { it.isNotBlank() }
                ?: QuiltMeta.displayName(gameVersion, loaderVersion),
            gameVersion = gameVersion,
            loader = LoaderKind.QUILT,
            loaderVersion = loaderVersion,
            directory = FabricFiles.versionDir(gameRoot, versionId).absolutePath,
            javaPath = javaPath,
            memoryMb = Instance.clampMemoryMb(memoryMb),
            registered = true,
        )
        instances.save(instance)
        return instance
    }
}

/** 下载步骤；失败时抛 [QuiltInstallException]，step 说明卡在哪。 */
enum class QuiltStep(val label: String) {
    LOADER_LIST("查询 Quilt 版本"),
    PROFILE("拉取 Quilt 配置"),
    WRITE_JSON("写入版本 json"),
    LIBRARIES("下载 Quilt 库"),
}

data class QuiltProgress(
    val step: QuiltStep,
    val detail: String = "",
    val done: Long = 0L,
    val total: Long = 0L,
) {
    val fraction: Float
        get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

class QuiltInstallException(
    val step: QuiltStep,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class QuiltInstallResult(
    val versionId: String,
    val jsonFile: File,
    val loaderVersion: String,
    val versionJson: VersionJson,
    val downloadReport: DownloadReport,
    val downloadedBytes: Long,
    val elapsedMs: Long,
    /** 从 maven 的 .sha1 附属文件补上校验信息的库数。 */
    val checksumFilled: Int = 0,
    /** 补完仍缺校验的库数；0 表示每个库都强校验、都能判重跳过。 */
    val checksumMissing: Int = 0,
) {
    val libraryFiles: Int get() = downloadReport.succeeded
}

data class InstalledQuilt(val versionId: String, val loaderVersion: String)
