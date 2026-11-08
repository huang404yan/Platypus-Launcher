package io.github.huang404yan.platypus.core.version

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

    private const val ASSET_OBJECT_BASE = "https://resources.download.minecraft.net"
    private const val ASSET_OBJECT_MIRROR = "https://bmclapi2.bangbang93.com/assets"

    /** Mojang 源 → BMCLAPI 镜像前缀。镜像只作为备选，不改变主地址。 */
    private val MIRROR_PREFIXES = listOf(
        "https://piston-data.mojang.com" to "https://bmclapi2.bangbang93.com",
        "https://launcher.mojang.com" to "https://bmclapi2.bangbang93.com",
        "https://libraries.minecraft.net" to "https://bmclapi2.bangbang93.com/maven",
        "https://resources.download.minecraft.net" to "https://bmclapi2.bangbang93.com/assets",
    )

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
                DownloadItem(
                    url = "$ASSET_OBJECT_BASE/$prefix/${obj.hash}",
                    target = File(root, "objects/$prefix/${obj.hash}").path,
                    sha1 = obj.hash,
                    size = obj.size,
                    mirrors = listOf("$ASSET_OBJECT_MIRROR/$prefix/${obj.hash}"),
                )
            }
    }

    fun mirrorOf(url: String): List<String> {
        val mirror = MIRROR_PREFIXES.firstOrNull { url.startsWith(it.first) }
            ?.let { (from, to) -> to + url.removePrefix(from) }
            ?: return emptyList()
        return listOf(mirror).filter { it != url }
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
