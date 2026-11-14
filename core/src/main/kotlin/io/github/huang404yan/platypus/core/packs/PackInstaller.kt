package io.github.huang404yan.platypus.core.packs

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
 * 从 Modrinth 找光影包 / 资源包 / 数据包并装进实例对应的目录。
 *
 * 和 [ModInstaller] 的差别只有三处，读代码时别被相似的流程骗了：
 * 1. `project_type` facet 不同（`shader` / `resourcepack` / `datapack`），否则搜出来全是模组；
 * 2. 版本查询的 `loaders` 不同 —— 光影标的是 `iris` / `optifine`，资源包标的是 `minecraft`，
 *    数据包标的是 `datapack`，照模组的 `fabric` 去查会一条都查不到（这是实测算出来的，不是猜的）；
 * 3. 落盘目录是 `shaderpacks/` / `resourcepacks/` / `datapacks/`，文件名允许 `.zip`。
 */
object PackInstaller {

    /**
     * 各类型包在 Modrinth 的版本 `loaders` 取值。
     *
     * 光影写成 `iris` + `optifine` 两个（Modrinth 的数组是 OR）：只写 iris 会漏掉只标了
     * optifine 的老包，而 Iris 本体能读绝大多数 OptiFine 光影。
     */
    private fun loadersOf(kind: PackKind): List<String> = when (kind) {
        PackKind.SHADER -> listOf("iris", "optifine")
        PackKind.RESOURCE_PACK -> listOf("minecraft")
        // 数据包在 Modrinth 上标的是 datapack：实测 Terralith 同时发了 datapack 的 zip
        // 和 fabric/forge 的 jar，用 minecraft 去筛会把 zip 那条漏掉
        PackKind.DATAPACK -> listOf("datapack")
    }

    /** 搜索：按关键词 + 游戏版本过滤，不按加载器过滤（包类没有 fabric/forge 之分）。 */
    suspend fun search(
        query: String,
        gameVersion: String?,
        kind: PackKind,
        limit: Int = ModrinthMeta.DEFAULT_SEARCH_LIMIT,
    ): List<ModProject> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isEmpty()) return@withContext emptyList()
        // 传 VANILLA 让 loaderName 返回 null，就不会被加上 categories:fabric 这种错误的 facet
        val url = ModrinthMeta.searchUrl(
            query = text,
            gameVersion = gameVersion,
            loader = LoaderKind.VANILLA,
            limit = limit,
            offset = 0,
            projectType = kind.projectType,
        )
        val body = ModInstaller.fetchText(url)
        val parsed = ModrinthMeta.parseSearch(body)
        parsed.getOrNull() ?: throw IOException("解析 Modrinth 搜索结果失败：" + (parsed.reasonOrNull() ?: "响应格式不对"))
    }

    /** 某个包在该游戏版本下的所有文件（新的在前）。 */
    suspend fun versionsOf(
        slug: String,
        gameVersion: String?,
        kind: PackKind,
    ): List<ModVersionFile> = withContext(Dispatchers.IO) {
        val url = ModrinthMeta.versionsUrl(slug, gameVersion, LoaderKind.VANILLA, loadersOf(kind))
        val body = ModInstaller.fetchText(url)
        val parsed = ModrinthMeta.parseVersions(body, slug)
        parsed.getOrNull() ?: throw IOException("解析 Modrinth 版本列表失败：" + (parsed.reasonOrNull() ?: "响应格式不对"))
    }

    /** 一步到位：查版本 → 挑最合适的 → 下载进 shaderpacks/ 或 resourcepacks/。 */
    suspend fun install(
        instanceDir: File,
        slug: String,
        gameVersion: String?,
        kind: PackKind,
        onProgress: (PackInstallProgress) -> Unit = {},
    ): PackInstallResult {
        onProgress(PackInstallProgress("查询版本", "查询 " + slug + " 的版本列表"))
        val versions = versionsOf(slug, gameVersion, kind)
        val chosen = ModrinthMeta.pickBest(versions) ?: throw IOException(
            "Modrinth 上 " + slug + " 没有适配 " + (gameVersion ?: "任意版本") + " 的文件",
        )
        return installFile(instanceDir, kind, slug, chosen, onProgress)
    }

    /** 已选中某个具体版本文件时走这里。 */
    suspend fun installFile(
        instanceDir: File,
        kind: PackKind,
        slug: String,
        file: ModVersionFile,
        onProgress: (PackInstallProgress) -> Unit = {},
    ): PackInstallResult {
        val dir = PackScanner.dirOf(kind, instanceDir)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("建不了 " + kind.dirName + " 目录：" + dir.absolutePath)
        }
        // 服务器给的名字最稳：同作者的多个包常靠文件名区分，改名会让光影自己的 include 找不到文件
        val fileName = file.fileName.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isBlank() || !fileName.endsWith(".zip", ignoreCase = true)) {
            throw IOException("Modrinth 给的文件名不可用：" + file.fileName)
        }
        val target = File(dir, fileName)

        onProgress(PackInstallProgress("下载", "下载 " + fileName, file.size))
        val item = DownloadItem(url = file.url, target = target.absolutePath, sha1 = file.sha1, size = file.size)
        val report: DownloadReport = HttpDownloader().download(listOf(item)) { p ->
            onProgress(
                PackInstallProgress(
                    "下载",
                    "下载 " + fileName,
                    total = if (p.totalBytes > 0) p.totalBytes else file.size,
                    done = p.completedBytes,
                ),
            )
        }
        if (!report.ok) {
            throw IOException("下载失败：" + (report.failures.firstOrNull()?.reason ?: "未知原因"))
        }

        onProgress(PackInstallProgress("校验文件", "校验 " + fileName, file.size, file.size))
        val entry = PackScanner.describe(kind, target, enabled = true)
        return PackInstallResult(
            slug = slug,
            file = target,
            entry = entry,
            version = file.versionNumber,
            downloadedBytes = if (target.length() > 0) target.length() else 0L,
        )
    }
}

data class PackInstallProgress(
    val step: String,
    val detail: String,
    val total: Long = 0L,
    val done: Long = 0L,
) {
    val fraction: Float
        get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

data class PackInstallResult(
    val slug: String,
    val file: File,
    val entry: PackEntry,
    val version: String,
    val downloadedBytes: Long,
)
