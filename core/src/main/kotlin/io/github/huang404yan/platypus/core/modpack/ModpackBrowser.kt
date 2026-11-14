package io.github.huang404yan.platypus.core.modpack

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.mods.ModInstaller
import io.github.huang404yan.platypus.core.mods.ModProject
import io.github.huang404yan.platypus.core.mods.ModVersionFile
import io.github.huang404yan.platypus.core.mods.ModrinthMeta
import io.github.huang404yan.platypus.core.mods.getOrNull
import io.github.huang404yan.platypus.core.mods.reasonOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 从 Modrinth 搜整合包，把 .mrpack 下到本地，剩下的交给 [io.github.huang404yan.platypus.core.install.ModpackImporter]。
 *
 * 为什么不让本对象直接装：整合包的「装」等于「解析 mrpack → 装游戏本体与加载器 → 下包内文件 → 解 overrides」，
 * 这一段 [io.github.huang404yan.platypus.core.install.ModpackImporter] 已经实现了，且只认本地文件。
 * 远程多了的只是「把 .mrpack 弄到本地」这一步，所以这里只做搜索 / 查版本 / 下载三件事，
 * 下载完返回一个 File，调用方再走一遍本地导入的老路 —— 两条路的进度、错误、重试都因此只有一份。
 *
 * 与 [io.github.huang404yan.platypus.core.packs.PackInstaller] 的差别：那边装完就完事（一个 zip 丢进目录），
 * 这边下载的 .mrpack 只是「原料」，所以文件名不做改动（包里 index 与 overrides 都靠这个名字认上下文）。
 */
object ModpackBrowser {

    /** 搜索：按关键词 + 游戏版本 + 加载器过滤。加载器为 VANILLA 时不加 categories facet。 */
    suspend fun search(
        query: String,
        gameVersion: String?,
        loader: LoaderKind,
        limit: Int = ModrinthMeta.DEFAULT_SEARCH_LIMIT,
    ): List<ModProject> = withContext(Dispatchers.IO) {
        val text = query.trim()
        // 空关键词也放行：整合包列表本身就是内容（Modrinth 上有一万多个），
        // 用户想「看看有什么」时不该逼他先编一个词。空 query 接口实测返回 19,337 条。
        val url = ModrinthMeta.searchUrl(
            query = text,
            gameVersion = gameVersion,
            loader = loader,
            limit = limit,
            offset = 0,
            projectType = ModrinthMeta.PROJECT_TYPE_MODPACK,
        )
        val body = ModInstaller.fetchText(url)
        val parsed = ModrinthMeta.parseSearch(body)
        parsed.getOrNull() ?: throw IOException("解析 Modrinth 搜索结果失败：" + (parsed.reasonOrNull() ?: "响应格式不对"))
    }

    /**
     * 某个整合包在指定游戏版本 / 加载器下的全部 .mrpack（新的在前）。
     *
     * 只保留 `.mrpack`：同一个项目偶尔会额外挂 source 包或服务器包，那些不是能导入的整合包，
     * 列出来只会让用户点错。没有一个是 .mrpack 时返回空列表，由界面说明原因。
     */
    suspend fun versionsOf(
        slug: String,
        gameVersion: String?,
        loader: LoaderKind,
    ): List<ModVersionFile> = withContext(Dispatchers.IO) {
        val url = ModrinthMeta.versionsUrl(slug, gameVersion, loader)
        val body = ModInstaller.fetchText(url)
        val parsed = ModrinthMeta.parseVersions(body, slug)
        val all = parsed.getOrNull()
            ?: throw IOException("解析 Modrinth 版本列表失败：" + (parsed.reasonOrNull() ?: "响应格式不对"))
        mrpackOnly(all)
    }

    /** 只留 .mrpack。抽成函数是为了能在单测里直接验，不用联网。 */
    internal fun mrpackOnly(versions: List<ModVersionFile>): List<ModVersionFile> =
        versions.filter { it.fileName.endsWith(".mrpack", ignoreCase = true) }

    /** 从 Modrinth 给的文件名里取出可落盘的文件名；不是 .mrpack 就返回 null。 */
    internal fun localFileNameOf(raw: String): String? {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
        return name.takeIf { it.isNotBlank() && it.endsWith(".mrpack", ignoreCase = true) }
    }

    /**
     * 把选中的 .mrpack 下到 [cacheDir]，返回本地文件。
     *
     * 目标已存在且 sha1 对得上时 [HttpDownloader] 会直接跳过，所以「装到一半失败再来一次」
     * 不会把几十 MB 重下一遍。断点续传靠它自己的 `.part`。
     */
    suspend fun download(
        file: ModVersionFile,
        cacheDir: File,
        onProgress: (ModpackDownloadProgress) -> Unit = {},
    ): File {
        val fileName = localFileNameOf(file.fileName)
            ?: throw IOException("Modrinth 给的文件名不是 .mrpack：" + file.fileName)
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
            throw IOException("建不了整合包缓存目录：" + cacheDir.absolutePath)
        }
        val target = File(cacheDir, fileName)
        onProgress(ModpackDownloadProgress("下载整合包", fileName, total = file.size))
        val item = DownloadItem(url = file.url, target = target.absolutePath, sha1 = file.sha1, size = file.size)
        val report: DownloadReport = HttpDownloader().download(listOf(item)) { p ->
            onProgress(
                ModpackDownloadProgress(
                    step = "下载整合包",
                    detail = fileName,
                    total = if (p.totalBytes > 0) p.totalBytes else file.size,
                    done = p.completedBytes,
                ),
            )
        }
        if (!report.ok) {
            throw IOException("下载整合包失败：" + (report.failures.firstOrNull()?.reason ?: "未知原因"))
        }
        return target
    }

    /**
     * 「远程整合包下载到本地」这几十 MB 放哪。
     *
     * 放系统临时目录而不是游戏目录：这些 .mrpack 是导入的原料，装完就该消失，
     * 留在 `versions/` 里会变成用户看不懂的垃圾；放临时目录还能借操作系统的清理策略兜底。
     * 目录名用固定的 `platypus-modpacks`，重装同一个包时能命中旧文件直接跳过下载。
     */
    fun defaultCacheDir(): File =
        File(File(System.getProperty("java.io.tmpdir") ?: ".", "platypus-modpacks"), "packs")
}

/** 远程整合包下载进度。与 [io.github.huang404yan.platypus.core.packs.PackInstallProgress] 同形状，分开只是为了文案。 */
data class ModpackDownloadProgress(
    val step: String,
    val detail: String = "",
    val total: Long = 0L,
    val done: Long = 0L,
) {
    val fraction: Float
        get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}
