package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.instance.LoaderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 从 Modrinth 找一个模组并装进 `<游戏目录>/mods`。
 *
 * 为什么不做完整的依赖求解：Modrinth 的 required dependencies 只给了 project id，
 * 要再查一次 /v2/projects/<id> 才知道它是什么、该选哪个版本。这里先把「单模组安装」
 * 做扎实（选版本 → 下载 → sha1 校验 → 落到 mods/），把缺依赖如实报给用户，
 * 由用户在界面上补——比猜错版本装一堆不兼容的库好。
 */
object ModInstaller {

    /** 搜索：按关键词 + 游戏版本 + 加载器过滤。加载器为 VANILLA 时不加 categories 过滤。 */
    suspend fun search(
        query: String,
        gameVersion: String?,
        loader: LoaderKind,
        limit: Int = ModrinthMeta.DEFAULT_SEARCH_LIMIT,
    ): List<ModProject> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isEmpty()) return@withContext emptyList()
        val url = ModrinthMeta.searchUrl(text, gameVersion, loader, limit, offset = 0)
        val body = ModInstaller.fetchText(url)
        val parsed = ModrinthMeta.parseSearch(body)
        parsed.getOrNull() ?: throw IOException("解析 Modrinth 搜索结果失败：" + (parsed.reasonOrNull() ?: "响应格式不对"))
    }

    /** 某个模组在该游戏版本/加载器下的所有版本文件（新的在前）。 */
    suspend fun versionsOf(
        slug: String,
        gameVersion: String?,
        loader: LoaderKind,
    ): List<ModVersionFile> = withContext(Dispatchers.IO) {
        val url = ModrinthMeta.versionsUrl(slug, gameVersion, loader)
        val body = ModInstaller.fetchText(url)
        val parsed = ModrinthMeta.parseVersions(body, slug)
        parsed.getOrNull() ?: throw IOException("解析 Modrinth 版本列表失败：" + (parsed.reasonOrNull() ?: "响应格式不对"))
    }

    /**
     * 一步到位：查版本 → 挑最合适的 → 下载进 mods/。
     *
     * 目标目录不存在会先建；同名文件已存在且 sha1 与体积对得上会被 HttpDownloader 直接跳过，
     * 所以重复点安装是安全的。
     */
    suspend fun install(
        gameDir: File,
        slug: String,
        gameVersion: String?,
        loader: LoaderKind,
        onProgress: (ModInstallProgress) -> Unit = {},
    ): ModInstallResult {
        onProgress(ModInstallProgress(ModInstallStep.VERSIONS, "查询 " + slug + " 的版本列表"))
        val versions = versionsOf(slug, gameVersion, loader)
        val chosen = ModrinthMeta.pickBest(versions) ?: throw IOException(
            "Modrinth 上 " + slug + " 没有适配 " +
                (gameVersion ?: "任意版本") + " / " + ModrinthMeta.loaderName(loader) + " 的文件",
        )
        return installFile(gameDir, slug, chosen, onProgress)
    }

    /** 已选中某个具体版本文件时走这里（界面允许用户手动挑版本）。 */
    suspend fun installFile(
        gameDir: File,
        slug: String,
        file: ModVersionFile,
        onProgress: (ModInstallProgress) -> Unit = {},
    ): ModInstallResult {
        val modsDir = ModScanner.modsDir(gameDir)
        if (!modsDir.isDirectory && !modsDir.mkdirs()) {
            throw IOException("建不了 mods 目录：" + modsDir.absolutePath)
        }
        // 文件名可能是 "sodium-fabric-0.5.13+mc1.20.1.jar"，直接拿服务器给的名字最稳
        // —— 有些模组用文件名区分构建变体，改名会导致它自己找不到资源。
        val fileName = file.fileName.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isBlank() || !fileName.endsWith(".jar")) {
            throw IOException("Modrinth 给的文件名不可用：" + file.fileName)
        }
        val target = File(modsDir, fileName)

        onProgress(ModInstallProgress(ModInstallStep.DOWNLOAD, "下载 " + fileName, file.size))
        val item = DownloadItem(
            url = file.url,
            target = target.absolutePath,
            sha1 = file.sha1,
            size = file.size,
        )
        val report: DownloadReport = HttpDownloader().download(listOf(item)) { p ->
            onProgress(
                ModInstallProgress(
                    ModInstallStep.DOWNLOAD,
                    "下载 " + fileName,
                    total = if (p.totalBytes > 0) p.totalBytes else file.size,
                    done = p.completedBytes,
                ),
            )
        }
        if (!report.ok) {
            val reason = report.failures.firstOrNull()?.reason ?: "未知原因"
            throw IOException("下载失败：" + reason)
        }

        onProgress(ModInstallProgress(ModInstallStep.VERIFY, "校验 " + fileName, file.size, file.size))
        val entry: ModEntry = ModScanner.describe(target, enabled = true)
        return ModInstallResult(
            slug = slug,
            file = target,
            entry = entry,
            version = file.versionNumber,
            downloadedBytes = if (target.length() > 0) target.length() else 0L,
            requiredDependencies = file.requiredDependencies,
        )
    }

    /**
     * POST 一小段 JSON 并读回文本。
     *
     * 只有 /version_files 用它：Modrinth 的反查接口是 POST，且请求体就是一串 sha1，
     * 用不到完整的序列化框架。响应体大小有限（只回命中的那些），30 秒读超时够。
     */
    internal fun postJson(url: String, body: String): String {
        val connection = try {
            @Suppress("DEPRECATION")
            java.net.URL(url).openConnection() as java.net.HttpURLConnection
        } catch (e: Exception) {
            throw IOException("连不上 " + url + "：" + (e.message ?: e::class.simpleName))
        }
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", ModrinthMeta.DEFAULT_USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Modrinth 返回 HTTP " + code + "：" + url)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("读 " + url + " 失败：" + (e.message ?: e::class.simpleName))
        } finally {
            connection.disconnect()
        }
    }

    internal fun fetchText(url: String): String {
        val connection = try {
            @Suppress("DEPRECATION")
            java.net.URL(url).openConnection() as java.net.HttpURLConnection
        } catch (e: Exception) {
            throw IOException("连不上 " + url + "：" + (e.message ?: e::class.simpleName))
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", ModrinthMeta.DEFAULT_USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Modrinth 返回 HTTP " + code + "：" + url)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("读 " + url + " 失败：" + (e.message ?: e::class.simpleName))
        } finally {
            connection.disconnect()
        }
    }
}

enum class ModInstallStep(val label: String) {
    VERSIONS("查询版本"),
    DOWNLOAD("下载模组"),
    VERIFY("校验文件"),
}

data class ModInstallProgress(
    val step: ModInstallStep,
    val detail: String,
    val total: Long = 0L,
    val done: Long = 0L,
) {
    val fraction: Float
        get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

data class ModInstallResult(
    val slug: String,
    val file: File,
    val entry: ModEntry,
    val version: String,
    val downloadedBytes: Long,
    val requiredDependencies: List<String> = emptyList(),
)
