package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.version.VersionDownloads
import io.github.huang404yan.platypus.core.version.VersionJson
import io.github.huang404yan.platypus.core.version.parseVersionJson
import java.io.File

/**
 * 一个可以拿来启动的版本：json 已经沿 inheritsFrom 链合并过，客户端 jar 也定位好了。
 *
 * [id] 是「真正要启动的那个版本」的 id（子版本），不是被继承的父版本。
 */
data class ResolvedVersion(
    val id: String,
    val version: VersionJson,
    /** 客户端 jar；找不到时是 null，启动前应当报错而不是硬起一个空 classpath。 */
    val clientJar: File?,
    /** 合并链上出现过的问题（缺父版本 json、缺 client jar 之类），给界面提示用。 */
    val warnings: List<String> = emptyList(),
)

/**
 * 读取并合并 versions/<id>/<id>.json。
 *
 * 只读、不联网。合并规则照抄官方启动器：子版本显式给出的字段优先，列表字段（libraries）
 * 与参数（arguments）是「父在前、子在后」的拼接，所以子版本可以再追加自己的参数。
 * 这一步与平台无关，Android 端以后也能直接用。
 */
object VersionResolver {

    /** 版本目录：gameRoot/versions/<id>。 */
    fun versionDir(gameRoot: File, versionId: String): File = File(File(gameRoot, "versions"), versionId)

    fun jsonFile(gameRoot: File, versionId: String): File = File(versionDir(gameRoot, versionId), versionId + ".json")

    fun jarFile(gameRoot: File, versionId: String): File = File(versionDir(gameRoot, versionId), versionId + ".jar")

    /**
     * 读一个版本（沿 inheritsFrom 往上最多 [maxDepth] 层）。
     *
     * 返回 null 只表示「本版本的 json 不存在或解析失败」；父版本缺失会记进 warnings 但不算失败。
     */
    fun load(gameRoot: File, versionId: String, maxDepth: Int = 4): ResolvedVersion? {
        val own = read(gameRoot, versionId) ?: return null
        val warnings = mutableListOf<String>()
        var merged = own
        var current = own
        var depth = 0
        while (depth < maxDepth) {
            val parentId = current.inheritsFrom?.takeIf { it.isNotBlank() } ?: break
            val parent = read(gameRoot, parentId)
            if (parent == null) {
                warnings += "父版本 $parentId 的 json 不存在，按不完整版本继续"
                break
            }
            merged = merge(merged, parent)
            current = parent
            depth++
        }
        val clientJar = locateClientJar(gameRoot, merged.id, warnings)
        return ResolvedVersion(id = merged.id.ifBlank { versionId }, version = merged, clientJar = clientJar, warnings = warnings)
    }

    /**
     * 把 [child] 与它的祖先依次合并（[ancestors] 由近到远，直接父版本在最前）。
     *
     * 与 [load] 的区别：这里不碰磁盘，调用方已经自己读好了 json。合并方向固定为
     * 「父在前、子在后」，所以子版本可以继续追加参数、覆盖同名库。
     */
    fun resolve(child: VersionJson, ancestors: List<VersionJson>): VersionJson =
        ancestors.fold(child) { acc, parent -> merge(acc, parent) }

    private fun read(gameRoot: File, versionId: String): VersionJson? {
        val file = jsonFile(gameRoot, versionId)
        if (!file.isFile) return null
        return runCatching { parseVersionJson(file.readText()) }.getOrNull()
    }

    /** 客户端 jar：先找自己的，再沿继承链找父版本的（Forge 的 client jar 常常直接复用原版）。 */
    private fun locateClientJar(gameRoot: File, versionId: String, warnings: MutableList<String>): File? {
        val own = jarFile(gameRoot, versionId)
        if (own.isFile) return own
        val parent = read(gameRoot, versionId)?.inheritsFrom?.takeIf { it.isNotBlank() } ?: return null
        val inherited = jarFile(gameRoot, parent)
        if (inherited.isFile) {
            warnings += "本版本没有 client jar，使用父版本 $parent 的 jar"
            return inherited
        }
        warnings += "找不到 client jar：versions/$versionId/$versionId.jar"
        return null
    }

    /**
     * 子版本 [child] 覆盖父版本 [parent]。
     *
     * 列表字段顺序固定为「父在前、子在后」，与官方启动器一致；这样父版本的 jvm 参数
     * （-cp、-Djava.library.path 之类）先出现，子版本（Forge）追加的 -p/--add-opens 在后。
     */
    fun merge(child: VersionJson, parent: VersionJson): VersionJson {
        val childArgs = child.arguments
        val parentArgs = parent.arguments
        return child.copy(
            id = child.id.ifBlank { parent.id },
            inheritsFrom = null,
            type = child.type.ifBlank { parent.type },
            mainClass = child.mainClass.ifBlank { parent.mainClass },
            assets = child.assets.ifBlank { parent.assets },
            assetIndex = child.assetIndex ?: parent.assetIndex,
            downloads = mergeDownloads(child.downloads, parent.downloads),
            libraries = mergeLibraries(parent.libraries, child.libraries),
            arguments = if (childArgs == null && parentArgs == null) null else io.github.huang404yan.platypus.core.version.Arguments(
                game = (parentArgs?.game.orEmpty() + childArgs?.game.orEmpty()),
                jvm = (parentArgs?.jvm.orEmpty() + childArgs?.jvm.orEmpty()),
            ),
            minecraftArguments = child.minecraftArguments ?: parent.minecraftArguments,
            javaVersion = child.javaVersion ?: parent.javaVersion,
            releaseTime = child.releaseTime.ifBlank { parent.releaseTime },
            time = child.time.ifBlank { parent.time },
        )
    }

    private fun mergeDownloads(child: VersionDownloads?, parent: VersionDownloads?): VersionDownloads? {
        if (child == null) return parent
        if (parent == null) return child
        return child.copy(
            client = child.client ?: parent.client,
            server = child.server ?: parent.server,
            client_mappings = child.client_mappings ?: parent.client_mappings,
            server_mappings = child.server_mappings ?: parent.server_mappings,
        )
    }

    /** 父版本库在前、子版本库在后；同名（含 classifier）时保留子版本的那条。 */
    internal fun mergeLibraries(parent: List<io.github.huang404yan.platypus.core.version.Library>, child: List<io.github.huang404yan.platypus.core.version.Library>): List<io.github.huang404yan.platypus.core.version.Library> {
        val seen = LinkedHashMap<String, io.github.huang404yan.platypus.core.version.Library>()
        for (library in parent) seen[library.name] = library
        for (library in child) seen[library.name] = library
        return seen.values.toList()
    }
}
