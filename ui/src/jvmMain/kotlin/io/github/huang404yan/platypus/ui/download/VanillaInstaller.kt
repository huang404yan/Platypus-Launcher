package io.github.huang404yan.platypus.ui.download

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadProgress
import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.version.DownloadPlanner
import io.github.huang404yan.platypus.core.version.VersionManifest
import io.github.huang404yan.platypus.core.version.parseAssetIndex
import io.github.huang404yan.platypus.core.version.parseVersionJson
import io.github.huang404yan.platypus.core.version.parseVersionManifest
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把「版本号 + 目标目录」翻译成一次真实的原版（vanilla）安装。
 *
 * 只做两件事：从 Mojang 拉版本元数据（BMCLAPI 镜像兜底），交给 core 的
 * [DownloadPlanner] 生成 [DownloadItem] 清单；真正的字节搬运由
 * [io.github.huang404yan.platypus.core.download.HttpDownloader] 完成。
 *
 * 所有函数都是阻塞的，调用方必须放在 Dispatchers.IO 上执行。
 */
object VanillaInstaller {

    const val MANIFEST_URL: String = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    const val MANIFEST_MIRROR_URL: String = "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"

    private const val BMCL = "https://bmclapi2.bangbang93.com"
    private const val PISTON_META = "https://piston-meta.mojang.com"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val USER_AGENT =
        "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"

    /** 一次安装要下载的全部文件，已经去重。 */
    data class InstallPlan(
        val versionId: String,
        val versionJsonPath: String,
        val totalBytes: Long,
        val items: List<DownloadItem>,
        /** 版本 json 里的游戏版本号（一个版本目录只对应一个游戏版本）。 */
        val gameVersion: String = "",
        /** 版本 json 的 javaVersion.majorVersion，缺省时按 GameJavaRules 推。 */
        val javaMajorVersion: Int = 8,
        /** 版本 json 的 javaVersion.component，例如 java-runtime-gamma；缺省为 null。 */
        val javaComponent: String? = null,
    ) {
        val totalFiles: Int get() = items.size
    }

    /** 版本清单（version_manifest_v2.json）。官方源失败时用 BMCLAPI 镜像。 */
    fun loadManifest(): VersionManifest = parseVersionManifest(
        fetchText(listOf(MANIFEST_URL, MANIFEST_MIRROR_URL)),
    )

    /**
     * 生成安装清单。
     *
     * 副作用只有一件事：把解析通过的版本 json 写进 versions 目录下同名 json——
     * 这是启动器识别实例的依据，不能只留在内存里。
     */
    fun plan(gameDir: File, versionId: String, manifest: VersionManifest? = null): InstallPlan {
        val requested = versionId.trim()
        require(requested.isNotEmpty()) { "版本号不能为空" }

        val jsonText = fetchText(versionJsonUrls(requested, manifest))
        val version = parseVersionJson(jsonText)
        val resolvedId = version.id.ifBlank { requested }

        val jsonFile = File(DownloadPlanner.versionDir(gameDir, resolvedId), resolvedId + ".json")
        jsonFile.parentFile?.mkdirs()
        jsonFile.writeText(jsonText)

        val items = mutableListOf<DownloadItem>()
        DownloadPlanner.clientJar(version, gameDir)?.let(items::add)
        DownloadPlanner.assetIndex(version, gameDir)?.let(items::add)
        items += DownloadPlanner.libraries(version, gameDir)

        val index = version.assetIndex
        if (index != null && index.url.isNotBlank()) {
            val indexText = fetchText(listOf(index.url) + mirrorsFor(index.url))
            items += DownloadPlanner.assets(parseAssetIndex(indexText), gameDir)
        }

        val unique = items.distinctBy { it.target }
        val totalBytes = unique.sumOf { if (it.size > 0) it.size else 0L }
        // javaVersion 在新版本 json 里都有；老版本可能整个缺失，用游戏版本号兜底推一次。
        val declaredJava = version.javaVersion
        return InstallPlan(
            versionId = resolvedId,
            versionJsonPath = jsonFile.path,
            totalBytes = totalBytes,
            items = unique,
            gameVersion = resolvedId,
            javaMajorVersion = declaredJava?.majorVersion?.takeIf { it > 0 }
                ?: GameJavaRules.requiredMajor(resolvedId),
            javaComponent = declaredJava?.component?.takeIf { it.isNotBlank() },
        )
    }

    /** 并发下载清单里的所有文件；失败项由调用方决定是否重试。 */
    suspend fun download(
        items: List<DownloadItem>,
        parallelism: Int = HttpDownloader.DEFAULT_PARALLELISM,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadReport {
        // 先把所有目标目录建好：HttpDownloader 内部是「检查不存在 → mkdirs」，多线程同时命中同一个
        // 新目录时后到的那个 mkdirs 会返回 false，被误报成「无法创建目录」（1.20.1 实测 1/3641 命中）。
        // 这里串行预建目录，规避该竞态。
        items.mapNotNull { File(it.target).parentFile }.distinct().forEach { it.mkdirs() }
        return HttpDownloader(parallelism = parallelism).download(items, onProgress)
    }

    private fun versionJsonUrls(id: String, manifest: VersionManifest?): List<String> {
        val urls = mutableListOf<String>()
        val entry = manifest?.find(id)
        if (entry != null && entry.url.isNotBlank()) {
            urls += entry.url
            urls += mirrorsFor(entry.url)
        }
        // 手填的版本号可能不在清单里（或被过滤掉了），BMCLAPI 的单版本接口能兜住。
        urls += BMCL + "/version/" + id + "/json"
        return urls.filter { it.isNotBlank() }.distinct()
    }

    /** Mojang 源 → BMCLAPI 镜像。只作为备选，不改变主地址。 */
    fun mirrorsFor(url: String): List<String> = when {
        url.startsWith(PISTON_META) -> listOf(BMCL + url.removePrefix(PISTON_META))
        else -> DownloadPlanner.mirrorOf(url)
    }

    private fun fetchText(url: String): String = fetchText(listOf(url))

    /** 依次尝试每个地址，全部失败时抛 [IOException] 并列出试过的地址。 */
    fun fetchText(urls: List<String>): String {
        val tried = mutableListOf<String>()
        for (url in urls.distinct()) {
            if (url.isBlank()) continue
            tried += url
            val text = tryFetch(url)
            if (text != null) return text
        }
        throw IOException(
            "拉取失败（已尝试 " + tried.size + " 个地址）：" + tried.joinToString("，"),
        )
    }

    private fun tryFetch(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            @Suppress("DEPRECATION")
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
