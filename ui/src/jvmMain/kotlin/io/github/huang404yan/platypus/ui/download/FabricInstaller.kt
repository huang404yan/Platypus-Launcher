package io.github.huang404yan.platypus.ui.download

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.loader.FabricFiles
import io.github.huang404yan.platypus.core.loader.FabricLibraries
import io.github.huang404yan.platypus.core.loader.FabricLoaderVersion
import io.github.huang404yan.platypus.core.loader.FabricMeta
import io.github.huang404yan.platypus.core.loader.FabricParse
import io.github.huang404yan.platypus.core.loader.parseFabricLoaderVersions
import io.github.huang404yan.platypus.core.loader.parseFabricProfile
import io.github.huang404yan.platypus.core.version.DownloadPlanner
import io.github.huang404yan.platypus.core.version.VersionJson
import io.github.huang404yan.platypus.core.version.parseVersionJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * Fabric 安装：拉元数据 → 写版本 json → 规划并下载 libraries。
 *
 * 不负责原版文件（客户端 jar / assets）与 Java——那些由 [OneClickInstaller] 串起来，
 * 因为 Fabric 的 profile json 是 inheritsFrom 原版的，缺了父版本根本起不来。
 * Forge 以后也走这条缝：换一个 LoaderInstaller 实现，注册实例那一步完全一样。
 */
object FabricInstaller {

    /** 只拉 loader 版本列表，给 UI 选版本用。 */
    suspend fun listLoaders(gameVersion: String): List<FabricLoaderVersion> = withContext(Dispatchers.IO) {
        val text = VanillaInstaller.fetchText(listOf(FabricMeta.loaderListUrl(gameVersion)))
        when (val parsed = parseFabricLoaderVersions(text)) {
            is FabricParse.Ok -> parsed.value
            is FabricParse.Failure -> throw IOException(parsed.reason)
        }
    }

    /**
     * 装 Fabric 到 gameRoot。
     *
     * @param vanillaVersion 原版版本号（1.20.1）；它也必须是 profile 的 inheritsFrom。
     * @param loaderVersion 具体 loader 版本（0.16.14）；传空则自动挑最新 stable。
     */
    suspend fun install(
        gameRoot: File,
        vanillaVersion: String,
        loaderVersion: String? = null,
        parallelism: Int = HttpDownloader.DEFAULT_PARALLELISM,
        onProgress: (FabricProgress) -> Unit = {},
    ): FabricInstallResult = withContext(Dispatchers.IO) {
        val started = Instant.now()
        var step = FabricStep.LOADER_LIST

        try {
            val chosen = if (!loaderVersion.isNullOrBlank()) {
                loaderVersion
            } else {
                onProgress(FabricProgress(FabricStep.LOADER_LIST, "查询可用 loader 版本…"))
                val versions = listLoaders(vanillaVersion)
                val latest = versions.firstOrNull { it.stable } ?: versions.first()
                latest.version
            }

            step = FabricStep.PROFILE
            onProgress(FabricProgress(FabricStep.PROFILE, "拉取 Fabric $chosen 的 profile json…"))
            val profileText = VanillaInstaller.fetchText(
                listOf(FabricMeta.profileUrl(vanillaVersion, chosen)),
            )
            val profile = when (val parsed = parseFabricProfile(profileText, vanillaVersion, chosen)) {
                is FabricParse.Ok -> parsed.value
                is FabricParse.Failure -> throw IOException(parsed.reason)
            }

            step = FabricStep.WRITE_JSON
            val versionId = FabricMeta.versionId(vanillaVersion, chosen)
            onProgress(FabricProgress(step, "写入版本 json：$versionId"))
            val jsonFile = FabricFiles.writeProfile(gameRoot, versionId, profile.raw)

            step = FabricStep.LIBRARIES
            val librariesDir = DownloadPlanner.librariesDir(gameRoot)
            val items = FabricLibraries.plan(
                version = profile.versionJson,
                librariesDir = librariesDir,
            )
            val totalBytes = items.sumOf { if (it.size > 0) it.size else 0L }
            onProgress(
                FabricProgress(
                    step = FabricStep.LIBRARIES,
                    detail = "下载 ${items.size} 个 Fabric 库",
                    done = 0,
                    total = totalBytes,
                ),
            )

            // 目录先串行建好，避免 16 个并发线程同时 mkdirs 打架（HttpDownloader 里也防了一手）。
            items.mapNotNull { File(it.target).parentFile }.distinct().forEach { it.mkdirs() }

            val report = HttpDownloader(parallelism).download(items) { p ->
                onProgress(
                    FabricProgress(
                        step = FabricStep.LIBRARIES,
                        detail = "$chosen 库 ${p.completedFiles}/${p.totalFiles}",
                        done = p.completedBytes,
                        total = p.totalBytes,
                    ),
                )
            }
            if (!report.ok) {
                val first = report.failures.first()
                throw IOException(
                    "${report.failures.size} 个 Fabric 库下载失败，第一个是 " +
                        "${File(first.item.target).name}：${first.reason}",
                )
            }

            FabricInstallResult(
                versionId = versionId,
                jsonFile = jsonFile,
                loaderVersion = chosen,
                versionJson = profile.versionJson,
                downloadReport = report,
                downloadedBytes = items.sumOf { if (it.size > 0) it.size else 0L },
                elapsedMs = Duration.between(started, Instant.now()).toMillis(),
            )
        } catch (e: Exception) {
            if (e is FabricInstallException) throw e
            throw FabricInstallException(step, e.message ?: e::class.simpleName ?: "未知错误", e)
        }
    }

    /** 扫描磁盘上已装的 Fabric 版本，给 UI 做「已安装」提示与重复安装判断。 */
    fun installedLoaders(gameRoot: File, vanillaVersion: String): List<InstalledFabric> {
        val versionsRoot = File(gameRoot, "versions")
        if (!versionsRoot.isDirectory) return emptyList()
        return (versionsRoot.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val json = File(dir, "${dir.name}.json")
                if (!json.isFile) return@mapNotNull null
                val text = runCatching { json.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
                val parsed = runCatching { parseVersionJson(text) }.getOrNull() ?: return@mapNotNull null
                if (parsed.inheritsFrom != vanillaVersion) return@mapNotNull null
                val main = parsed.mainClass
                if (!main.contains("fabricmc")) return@mapNotNull null
                val loader = FabricMeta.loaderVersionFromId(dir.name) ?: return@mapNotNull null
                InstalledFabric(versionId = dir.name, loaderVersion = loader)
            }
            .sortedBy { it.loaderVersion }
    }

    /** 建实例：gameVersion 仍是原版号，loader 标 FABRIC。 */
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
                ?: FabricMeta.displayName(gameVersion, loaderVersion),
            gameVersion = gameVersion,
            loader = LoaderKind.FABRIC,
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

/** 下载步骤；失败时抛 [FabricInstallException]，step 说明卡在哪。 */
enum class FabricStep(val label: String) {
    LOADER_LIST("查询 Fabric 版本"),
    PROFILE("拉取 Fabric 配置"),
    WRITE_JSON("写入版本 json"),
    LIBRARIES("下载 Fabric 库"),
}

data class FabricProgress(
    val step: FabricStep,
    val detail: String = "",
    val done: Long = 0L,
    val total: Long = 0L,
) {
    val fraction: Float
        get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

class FabricInstallException(
    val step: FabricStep,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class FabricInstallResult(
    val versionId: String,
    val jsonFile: File,
    val loaderVersion: String,
    val versionJson: VersionJson,
    val downloadReport: DownloadReport,
    val downloadedBytes: Long,
    val elapsedMs: Long,
) {
    val libraryFiles: Int get() = downloadReport.succeeded
}

data class InstalledFabric(val versionId: String, val loaderVersion: String)
