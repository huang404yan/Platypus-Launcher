package io.github.huang404yan.platypus.core.loader

import io.github.huang404yan.platypus.core.version.VersionJsonFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.zip.ZipFile

/**
 * installer 要用的一个依赖库。
 *
 * @param coord maven 坐标原文（group:artifact:version[:classifier][@ext]），只用于报错时说清是哪一个。
 * @param path 相对 libraries/ 的路径，例如 net/neoforged/srgutils/1.0.0/srgutils-1.0.0.jar。
 * @param url 官方清单里写的地址（多为 maven.neoforged.net，本机连不上）。
 * @param sha1 40 位十六进制摘要；清单里缺失为 null。
 * @param size 字节数；未知为 -1。
 */
data class InstallerLibrary(
    val coord: String,
    val path: String,
    val url: String,
    val sha1: String?,
    val size: Long,
)

/**
 * 读 NeoForge installer jar 里的依赖库清单，供「预铺库」使用。
 *
 * **为什么需要它**：installer 跑 --installClient 时会自己按 install_profile.json 里写死的
 * https://maven.neoforged.net/releases/... 逐个下库，而这台机器上那个域名 TLS 被重置，
 * 每个库等 5 秒超时后继续下一个，最后装出一堆缺库的残局。
 *
 * 但 installer 的 [net.minecraftforge.installer.Downloader] 在下之前会先看本地：
 * 目标文件存在且 sha1 相符就打印 "File ... exists. Checksum valid." 直接跳过。
 * 所以我们只要提前把库按 <gameRoot>/libraries/<path> 铺好，installer 就一个网络请求都不发。
 *
 * 落盘路径的依据是 Artifact.getLocalPath(dir) = new File(dir, path.replace('/', sep))，
 * 而 dir 是 <gameRoot>（ClientInstall 传给 downloadLibraries 的是 libraries 目录的父级）。
 *
 * 本对象只做解析与地址换算，不联网、不写盘（除了读 jar），方便单测。
 */
object NeoForgeInstallProfile {

    /** BMCLAPI 的 Mojang 库镜像前缀（实测对 libraries.minecraft.net 的路径可用，会 302 到别的镜像）。 */
    private const val MOJANG_MIRROR_PREFIX = "https://bmclapi2.bangbang93.com/libraries/"

    /**
     * BMCLAPI 的备用直连镜像。
     *
     * bmclapi2.bangbang93.com 自己不存文件，一律 302 到各地高校镜像（实测绝大多数落到
     * mirror.nyist.edu.cn）。并发下载时偶尔会有几个连接卡在「等 302 之后的连接」上直接超时，
     * 所以把落地镜像的直连地址也作为兜底：它的路径规则就是 /bmclapi/<原路径>，实测 200。
     */
    private const val BMCLAPI_DIRECT_MIRROR = "https://mirror.nyist.edu.cn/bmclapi/"

    /**
     * 解析 install_profile.json 里的 libraries[]。
     *
     * 解析不出来（不是 JSON、没有 libraries 字段）返回空列表——调用方要当成「无法预铺」处理，
     * 直接照常跑 installer，而不是在这里抛错把安装堵死。
     *
     * 只认 downloads.artifact：classifiers（如 sources、mappings）不是运行时需要的，
     * installer 也不会去下它们。
     */
    fun parseLibraries(profileJson: String): List<InstallerLibrary> {
        val root = runCatching { VersionJsonFormat.parseToJsonElement(profileJson) }
            .getOrNull() as? JsonObject ?: return emptyList()
        return parseLibraryArray(root["libraries"] as? JsonArray)
    }

    private fun parseLibraryArray(array: JsonArray?): List<InstallerLibrary> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val coord = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val artifact = (obj["downloads"] as? JsonObject)?.get("artifact") as? JsonObject
                ?: return@mapNotNull null
            val url = (artifact["url"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // path 缺失时按坐标推：实测 70 条全都写了 path，但别指望下一个版本也写
            val path = (artifact["path"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: mavenPath(coord) ?: return@mapNotNull null
            val sha1 = (artifact["sha1"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.length == 40 }
            val size = (artifact["size"] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull() ?: -1L
            InstallerLibrary(coord = coord, path = path, url = url, sha1 = sha1, size = size)
        }
    }

    /**
     * 从 installer jar 里读出所有运行时需要的库，按 path 去重。
     *
     * 两批来源：
     * 1. install_profile.json 的 libraries[]（processor 链用的工具，installertools / srgutils / asm 等）；
     * 2. 它 "json" 字段指向的 version.json 的 libraries[]（BootstrapLauncher、fancymodloader 等）。
     *
     * 实测 21.1.265 里第一批 70 条、第二批 47 条且 47 条全是 70 条的子集；别的版本不保证，
     * 所以两批都读、按 path 去重（保留先出现的那条）。
     */
    fun libraries(installerJar: File): List<InstallerLibrary> {
        if (!installerJar.isFile || installerJar.length() <= 0L) return emptyList()
        return runCatching {
            ZipFile(installerJar).use { zip ->
                val profileEntry = zip.getEntry(NeoForgeMeta.INSTALLER_PROFILE_JSON)
                    ?: return@use emptyList()
                val profileText = zip.getInputStream(profileEntry)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                val fromProfile = parseLibraries(profileText)

                val versionPath = runCatching {
                    val root = VersionJsonFormat.parseToJsonElement(profileText) as? JsonObject
                    ((root?.get("json") as? JsonPrimitive)?.contentOrNull)?.trim()
                        ?.removePrefix("/")
                        ?.takeIf { it.isNotEmpty() }
                }.getOrNull() ?: NeoForgeMeta.INSTALLER_VERSION_JSON

                val fromVersion = runCatching {
                    val entry = zip.getEntry(versionPath) ?: return@runCatching emptyList<InstallerLibrary>()
                    val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    parseLibraries(text)
                }.getOrDefault(emptyList())

                (fromProfile + fromVersion).distinctBy { it.path }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 把官方地址换成本机能连上的镜像。
     *
     * - `https://maven.neoforged.net/releases/...` → `https://bmclapi2.bangbang93.com/maven/...`
     *   （实测 51 条全部 200/206；官方域名本机 TLS 被重置）
     * - `https://libraries.minecraft.net/...` → 原样（本机直连正常，实测 19 条都 200）
     * - 其它地址原样返回
     */
    fun downloadUrl(original: String): String {
        val url = original.trim()
        if (url.startsWith(NeoForgeMeta.OFFICIAL_MAVEN)) {
            return NeoForgeMeta.NEOFORGE_MAVEN + url.removePrefix(NeoForgeMeta.OFFICIAL_MAVEN)
        }
        return url
    }

    /**
     * 主地址失败后的兜底地址。
     *
     * neoforged 的库兜底回官方地址（本机不通，但换台机器就可能通，不能写死成「只有镜像」）；
     * Mojang 的库兜底到 BMCLAPI 的 /libraries/ 前缀（实测 302 后可用）。
     */
    fun mirrorUrls(library: InstallerLibrary): List<String> {
        val official = library.url.trim()
        return when {
            official.startsWith(NeoForgeMeta.OFFICIAL_MAVEN) -> {
                val tail = official.removePrefix(NeoForgeMeta.OFFICIAL_MAVEN)
                // 先试落地镜像的直连（快，且绕开一次 302），最后才是本机基本不通的官方地址
                listOf(BMCLAPI_DIRECT_MIRROR + tail, official)
            }
            official.startsWith(NeoForgeMeta.MOJANG_LIBRARIES) ->
                listOf(MOJANG_MIRROR_PREFIX + official.removePrefix(NeoForgeMeta.MOJANG_LIBRARIES))
            else -> emptyList()
        }
    }

    /** 库应该落到的位置：<gameRoot>/libraries/<path>。 */
    fun targetFile(gameRoot: File, library: InstallerLibrary): File =
        File(File(gameRoot, "libraries"), library.path.replace('/', File.separatorChar))

    /**
     * 按 maven 坐标推相对路径：group:artifact:version[:classifier][@ext]。
     * 认不出（少于三段）返回 null。
     */
    fun mavenPath(coord: String): String? {
        var body = coord.trim()
        if (body.isEmpty()) return null
        var ext = "jar"
        val at = body.indexOf('@')
        if (at > 0 && at < body.length - 1) {
            ext = body.substring(at + 1)
            body = body.substring(0, at)
        }
        val parts = body.split(':')
        if (parts.size < 3) return null
        val group = parts[0].trim()
        val artifact = parts[1].trim()
        val version = parts[2].trim()
        if (group.isEmpty() || artifact.isEmpty() || version.isEmpty()) return null
        val classifier = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
        val fileName = buildString {
            append(artifact)
            append('-')
            append(version)
            if (classifier != null) {
                append('-')
                append(classifier)
            }
            append('.')
            append(ext)
        }
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName
    }
}
