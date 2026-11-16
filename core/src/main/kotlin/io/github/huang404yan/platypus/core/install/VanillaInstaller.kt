package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.Endpoints
import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadProgress
import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.download.DownloadPreference
import io.github.huang404yan.platypus.core.download.DownloadSources
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

    // 这几个原来是 const val，现在全部走 [Endpoints]：空壳发布包里它们会是空串，
    // 于是「拉清单」这条路在这个包上根本发不出请求（而不是把地址留在 jar 里改一行就绕过去）。
    val MANIFEST_URL: String get() = Endpoints.join(Endpoints.pistonMeta(), "/mc/game/version_manifest_v2.json")
    val MANIFEST_MIRROR_URL: String get() = Endpoints.join(Endpoints.bmclapi(), "/mc/game/version_manifest_v2.json")

    private val BMCL: String get() = Endpoints.bmclapi()
    private val PISTON_META: String get() = Endpoints.pistonMeta()
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
    fun loadManifest(): VersionManifest {
        // 空壳包里两个地址会归并成同一个（都从用户填的镜像根拼），先去重再试。
        val urls = listOf(MANIFEST_URL, MANIFEST_MIRROR_URL).filter { it.isNotBlank() }.distinct()
        return parseVersionManifest(fetchText(urls))
    }

    /**
     * 生成安装清单。
     *
     * 副作用只有一件事：把解析通过的版本 json 写进 versions 目录下同名 json——
     * 这是启动器识别实例的依据，不能只留在内存里。
     */
    fun plan(gameDir: File, versionId: String, manifest: VersionManifest? = null): InstallPlan {
        val requested = versionId.trim()
        require(requested.isNotEmpty()) { "版本号不能为空" }

        val jsonText = loadVersionJson(requested, manifest)
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

    /**
     * 拉某个版本的 json 原文（Mojang 主源，BMCLAPI 兜底）。
     *
     * 单独开这个口子是给 [ServerInstaller] 用的：服务端只要版本 json 里的
     * `downloads.server`，不需要客户端那一整套文件清单，用 [plan] 会白写
     * `versions/<id>/<id>.json` 到游戏目录里 —— 那不是用户装服务端的地方。
     */
    fun loadVersionJson(versionId: String, manifest: VersionManifest? = null): String {
        val requested = versionId.trim()
        require(requested.isNotEmpty()) { "版本号不能为空" }
        return fetchText(versionJsonUrls(requested, manifest))
    }

    private fun versionJsonUrls(id: String, manifest: VersionManifest?): List<String> {
        val urls = mutableListOf<String>()
        val entry = manifest?.find(id)
        if (entry != null && entry.url.isNotBlank()) {
            urls += entry.url
            urls += mirrorsFor(entry.url)
        }
        // 手填的版本号可能不在清单里（或被过滤掉了），BMCLAPI 的单版本接口能兜住。
        if (BMCL.isNotEmpty()) urls += BMCL + "/version/" + id + "/json"
        return urls.filter { it.isNotBlank() }.distinct()
    }

    /** Mojang 源 → BMCLAPI 镜像。只作为备选，不改变主地址。 */
    fun mirrorsFor(url: String): List<String> {
        // 空壳包里 PISTON_META / BMCL 都是空串，不能拿 startsWith 去比（空前缀恒成立，
        // 会把任何地址都当成 Mojang 源的）。这时只走 DownloadPlanner 那条前缀表。
        val pistonMeta = PISTON_META
        if (pistonMeta.isNotEmpty() && url.startsWith(pistonMeta)) {
            val mirror = Endpoints.use(pistonMeta)
            if (mirror.isNotEmpty()) return listOf(mirror + url.removePrefix(pistonMeta))
        }
        return DownloadPlanner.mirrorOf(url)
    }

    private fun fetchText(url: String): String = fetchText(listOf(url))

    /**
     * 依次尝试每个地址，全部失败时抛 [IOException] 并列出试过的地址。
     *
     * 顺序受下载源偏好影响（见 [DownloadSources.orderAll]）：调用方写死的是「官方源在前」，
     * 用户选了「优先国内镜像」时这里把镜像提到前面。文本接口（版本清单、安装器清单、
     * 构建列表）同样会被墙住，只给文件下载做偏好、清单还走官方源是说不通的。
     */
    fun fetchText(urls: List<String>): String {
        // 空壳包还没填镜像根：这里一个请求都不该发，直接给用户一句能看懂的话。
        if (!Endpoints.serviceReady()) throw IOException(Endpoints.SHELL_HINT)
        val tried = mutableListOf<String>()
        for (url in DownloadSources.orderAll(urls, DownloadPreference.source)) {
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
