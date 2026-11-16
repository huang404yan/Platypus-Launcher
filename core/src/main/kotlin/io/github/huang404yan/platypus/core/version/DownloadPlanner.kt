package io.github.huang404yan.platypus.core.version

import io.github.huang404yan.platypus.core.Endpoints
import io.github.huang404yan.platypus.core.download.DownloadItem
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 把版本元数据翻译成「要下载哪些文件、放到哪儿」。
 *
 * 这里只做纯计算，不联网、不写盘，方便单测；真正的下载由 [io.github.huang404yan.platypus.core.download.HttpDownloader] 执行。
 * 资源文件（assets/objects）数量成千上万，单独由 [assets] 按索引生成。
 */
object DownloadPlanner {

    /**
     * Mojang 源 → BMCLAPI 镜像前缀。镜像只作为备选，不改变主地址。
     *
     * 前缀表按当前包现算：空壳发布包里 [Endpoints] 的主机是空的，表也就是空的
     * （镜像根本身已经是用户填的那个，不需要再换一层）。
     */
    private fun mirrorPrefixes(): List<Pair<String, String>> {
        val bmcl = Endpoints.bmclapi()
        val pistonData = Endpoints.pistonData()
        val launcher = Endpoints.launcherCdn()
        val libraries = Endpoints.mojangLibraries()
        val resources = Endpoints.resources()
        if (bmcl.isEmpty()) return emptyList()
        return listOf(
            pistonData to bmcl,
            launcher to bmcl,
            libraries to bmcl + "/maven",
            resources to bmcl + "/assets",
        ).filter { (from, to) -> from.isNotEmpty() && to.isNotEmpty() }
    }

    fun versionDir(gameDir: File, versionId: String): File = File(gameDir, "versions/$versionId")

    fun librariesDir(gameDir: File): File = File(gameDir, "libraries")

    fun assetsDir(gameDir: File): File = File(gameDir, "assets")

    /** 原版客户端 jar：`versions/<id>/<id>.jar`。 */
    fun clientJar(version: VersionJson, gameDir: File): DownloadItem? {
        val file = version.clientJar ?: return null
        if (file.url.isBlank() || version.id.isBlank()) return null
        return DownloadItem(
            url = file.url,
            target = File(versionDir(gameDir, version.id), "${version.id}.jar").path,
            sha1 = file.sha1,
            size = file.size,
            mirrors = mirrorOf(file.url),
        )
    }

    /** 资源索引文件：`assets/indexes/<id>.json`。 */
    fun assetIndex(version: VersionJson, gameDir: File): DownloadItem? {
        val index = version.assetIndex ?: return null
        if (index.url.isBlank() || index.id.isBlank()) return null
        return DownloadItem(
            url = index.url,
            target = File(File(assetsDir(gameDir), "indexes"), "${index.id}.json").path,
            sha1 = index.sha1,
            size = index.size,
            mirrors = mirrorOf(index.url),
        )
    }

    /**
     * 当前平台需要安装的库文件。
     *
     * 两类库都要照顾：
     * - 老版本用 `natives` 字段 + `classifiers`；
     * - 1.19 之后把 natives 平铺成带 rules 的 artifact。
     */
    fun libraries(
        version: VersionJson,
        gameDir: File,
        os: OsFamily = OsFamily.current(),
        arch: String = OsFamily.currentArch(),
    ): List<DownloadItem> {
        val root = librariesDir(gameDir)
        return version.libraries
            .filter { it.isAllowed(os, arch) }
            .mapNotNull { library ->
                val downloads = library.downloads ?: return@mapNotNull null
                val name = LibraryName.parse(library.name)
                val classifier = library.nativeClassifier(os)
                val file = if (classifier != null) downloads.classifiers?.get(classifier) else null
                val chosen = file ?: downloads.artifact ?: return@mapNotNull null
                if (chosen.url.isBlank()) return@mapNotNull null
                val relative = chosen.path ?: name?.basePath ?: return@mapNotNull null
                DownloadItem(
                    url = chosen.url,
                    target = File(root, relative).path,
                    sha1 = chosen.sha1,
                    size = chosen.size,
                    mirrors = mirrorOf(chosen.url),
                )
            }
            .distinctBy { it.target }
    }

    /** 资源对象：`assets/objects/<前两位>/<sha1>`，文件名就是 sha1，天然去重。 */
    fun assets(index: AssetIndexFile, gameDir: File): List<DownloadItem> {
        val root = assetsDir(gameDir)
        return index.objects.values
            .filter { it.hash.length >= 2 }
            .distinctBy { it.hash }
            .map { obj ->
                val prefix = obj.hash.substring(0, 2)
                val relative = "$prefix/${obj.hash}"
                // 空壳包里 resources() 是空串，拼出来的 url 也就空 —— HttpDownloader 会把它当成
                // 「没有可用的下载地址」，不会拿半截地址去发请求。
                DownloadItem(
                    url = Endpoints.join(Endpoints.resources(), relative),
                    target = File(root, "objects/$prefix/${obj.hash}").path,
                    sha1 = obj.hash,
                    size = obj.size,
                    mirrors = listOf(Endpoints.join(Endpoints.join(Endpoints.bmclapi(), "/assets"), relative))
                        .filter { it.isNotEmpty() },
                )
            }
    }

    fun mirrorOf(url: String): List<String> {
        if (url.isBlank()) return emptyList()
        val mirror = mirrorPrefixes().firstOrNull { url.startsWith(it.first) }
            ?.let { (from, to) -> to + url.removePrefix(from) }
            ?: return emptyList()
        return listOf(mirror).filter { it.isNotEmpty() && it != url }
    }
}

@Serializable
data class AssetIndexFile(
    val objects: Map<String, AssetObject> = emptyMap(),
)

@Serializable
data class AssetObject(
    val hash: String = "",
    val size: Long = 0L,
)

fun parseAssetIndex(text: String): AssetIndexFile = VersionJsonFormat.decodeFromString(text)
