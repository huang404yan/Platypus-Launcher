package io.github.huang404yan.platypus.core.loader

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.version.DownloadPlanner
import io.github.huang404yan.platypus.core.version.Library
import io.github.huang404yan.platypus.core.version.LibraryName
import io.github.huang404yan.platypus.core.version.OsFamily
import io.github.huang404yan.platypus.core.version.VersionJson
import io.github.huang404yan.platypus.core.version.VersionJsonFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Fabric 官方元数据（meta.fabricmc.net）的解析、版本号命名、profile json 落盘与 maven 补 url。
 *
 * 这里只做纯逻辑：不联网、不下载。联网与下载在 ui 层的 FabricLoaderInstaller 里。
 * 解析一律返回 [FabricParse]，失败给中文原因，不抛裸异常——Fabric 改过元数据形状，
 * 用户看到 stack trace 没有任何意义。
 */
object FabricMeta {

    const val BASE_URL: String = "https://meta.fabricmc.net"

    /** Fabric 自家 maven；profile json 里 url 缺省时的默认仓库之一。 */
    const val FABRIC_MAVEN: String = "https://maven.fabricmc.net/"

    /** Mojang 官方库仓库；非 Fabric 组件的兜底仓库。 */
    const val MOJANG_LIBRARIES: String = "https://libraries.minecraft.net/"

    /** Fabric 生成的服务端/客户端入口类，用来判断 profile json 是否像样。 */
    const val MAIN_CLASS: String = "net.fabricmc.loader.impl.launch.knot.KnotClient"

    /** GET 这个地址拿到该游戏版本可用的 loader 列表。 */
    fun loaderListUrl(gameVersion: String): String =
        "$BASE_URL/v2/versions/loader/$gameVersion"

    /** GET 这个地址拿到可直接启动的完整版本 json。 */
    fun profileUrl(gameVersion: String, loaderVersion: String): String =
        "$BASE_URL/v2/versions/loader/$gameVersion/$loaderVersion/profile/json"

    /**
     * 安装后的版本号，与 PCL 习惯一致：<原版号>-fabric-<loader 版本>。
     * 例：1.20.1-fabric-0.16.14
     */
    fun versionId(gameVersion: String, loaderVersion: String): String =
        "$gameVersion-fabric-$loaderVersion"

    /** 展示名，用于实例名。 */
    fun displayName(gameVersion: String, loaderVersion: String): String =
        "Fabric $loaderVersion / $gameVersion"

    /**
     * 从版本 id 里反解 loader 版本，认三种写法：
     *  1.20.1-fabric-0.16.14（本启动器）
     *  fabric-loader-0.16.14-1.20.1（Fabric 官方 profile）
     *  fabric-0.16.14（老的自定义命名）
     */
    fun loaderVersionFromId(versionId: String): String? {
        PatternOwnId.find(versionId)?.groupValues?.get(1)?.let { return it }
        PatternOfficial.find(versionId)?.groupValues?.get(1)?.let { return it }
        return null
    }

    // 注意：官方 profile 的 id 是 fabric-loader-0.16.14-1.20.1，loader 版本号后面还跟着原版号。
    // 所以官方这条不能再允许 [-+] 后缀，否则 \d+\.\d+ 后面的可选段会把 "-1.20.1" 一起吃进去。
    private val PatternOwnId = Regex(""".*-fabric-(\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.\-]+)?)$""")
    private val PatternOfficial = Regex("""fabric[-_]loader[-_](\d+\.\d+(?:\.\d+)?)""")
}

/** 解析结果壳：成功给值，失败给中文原因。 */
sealed interface FabricParse<out T> {
    data class Ok<T>(val value: T) : FabricParse<T>
    data class Failure(val reason: String) : FabricParse<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
    fun reasonOrNull(): String? = (this as? Failure)?.reason
}

/** /v2/versions/loader/<gameVersion> 数组里的一项里的 loader 对象。 */
@Serializable
data class FabricLoaderVersion(
    val version: String = "",
    val stable: Boolean = false,
    val build: Int = -1,
    val maven: String = "",
    val separator: String = "",
)

/** 同一个数组项里的 intermediary 信息（真正启动时由 profile json 里的库覆盖，这里仅留档）。 */
@Serializable
data class FabricIntermediary(
    val version: String = "",
    val maven: String = "",
)

/**
 * 数组项。实测 2026-11 的响应是 {"loader":{...},"intermediary":{...},"launcherMeta":{...}}，
 * 而任务书里描述的是扁平形状 {"version":...,"stable":...}，两种都吃。
 */
@Serializable
data class FabricLoaderEntry(
    val loader: FabricLoaderVersion? = null,
    val intermediary: FabricIntermediary? = null,
    val version: String = "",
    val stable: Boolean = false,
    val build: Int = -1,
    val maven: String = "",
) {
    val resolved: FabricLoaderVersion?
        get() = loader ?: if (version.isNotBlank()) {
            FabricLoaderVersion(version = version, stable = stable, build = build, maven = maven)
        } else {
            null
        }
}

/** 解析后的 profile：原始文本要原样落盘（里面字段比我们的模型多，重新序列化会丢）。 */
data class FabricProfile(
    val versionJson: VersionJson,
    val gameVersion: String,
    val loaderVersion: String,
    val raw: String,
) {
    val mainClass: String get() = versionJson.mainClass.ifBlank { FabricMeta.MAIN_CLASS }
}

/** 解析 loader 版本列表。 */
fun parseFabricLoaderVersions(text: String): FabricParse<List<FabricLoaderVersion>> {
    if (text.isBlank()) return FabricParse.Failure("Fabric 元数据为空：服务器返回了空响应")
    val entries: List<FabricLoaderEntry> = try {
        VersionJsonFormat.decodeFromString(text)
    } catch (e: Exception) {
        return FabricParse.Failure("Fabric loader 列表解析失败：" + describe(e))
    }
    if (entries.isEmpty()) return FabricParse.Failure("Fabric 元数据里没有任何 loader 版本")
    val versions = entries.mapNotNull { it.resolved }.filter { it.version.isNotBlank() }
    if (versions.isEmpty()) return FabricParse.Failure("Fabric 元数据里没有可用的 loader 版本（字段缺失）")
    return FabricParse.Ok(versions)
}

/** 最新稳定版；没有 stable 标记时退化为第一个（Fabric 的列表是新的在前）。 */
fun latestStableFabricLoader(versions: List<FabricLoaderVersion>): FabricLoaderVersion? =
    versions.firstOrNull { it.stable } ?: versions.firstOrNull()

/**
 * 解析 profile json。会校验三个关键字段：id 或 libraries、mainClass。
 * 缺 libraries 说明这个 json 是残的，装上也起不来，直接报原因。
 */
fun parseFabricProfile(
    text: String,
    gameVersion: String,
    loaderVersion: String,
): FabricParse<FabricProfile> {
    if (text.isBlank()) return FabricParse.Failure("Fabric profile json 为空：服务器返回了空响应")
    val json: VersionJson = try {
        VersionJsonFormat.decodeFromString(text)
    } catch (e: Exception) {
        return FabricParse.Failure("Fabric profile json 解析失败：" + describe(e))
    }
    if (json.libraries.isEmpty()) return FabricParse.Failure("Fabric profile json 里没有 libraries 字段")
    if (json.mainClass.isBlank()) return FabricParse.Failure("Fabric profile json 里没有 mainClass 字段")
    return FabricParse.Ok(
        FabricProfile(
            versionJson = json,
            gameVersion = gameVersion,
            loaderVersion = loaderVersion,
            raw = text,
        ),
    )
}

/** profile json 落盘：<gameRoot>/versions/<新版本号>/<新版本号>.json，返回该文件。 */
object FabricFiles {

    fun versionDir(gameRoot: File, versionId: String): File = File(gameRoot, "versions/$versionId")

    fun versionJsonFile(gameRoot: File, versionId: String): File =
        File(versionDir(gameRoot, versionId), "$versionId.json")

    /**
     * 把 profile 原文写到磁盘，只改 id 字段（改成我们的版本号），
     * 其余字段原样保留，inheritsFrom 仍指原版，VersionResolver 靠它找父版本与客户端 jar。
     */
    fun writeProfile(gameRoot: File, versionId: String, profileText: String): File {
        val root = VersionJsonFormat.parseToJsonElement(profileText).jsonObject
        val patched = JsonObject(root.toMutableMap().apply { put("id", JsonPrimitive(versionId)) })
        val target = versionJsonFile(gameRoot, versionId)
        target.parentFile?.mkdirs()
        target.writeText(VersionJsonFormat.encodeToString(JsonObject.serializer(), patched), Charsets.UTF_8)
        return target
    }
}

/**
 * 从 maven 坐标 / 库自带 url 生成下载地址与落地路径。
 *
 * 规则（Fabric profile json 与 Mojang 老版本 json 都要吃得下）：
 *   1. 有 downloads.artifact 就照它（Mojang 风格）；
 *   2. 否则 name 当 maven 坐标 g:a:v[:classifier]，路径 = group 点换斜杠 + /a/v/a-v[-classifier].jar；
 *      name 里没 classifier 但 natives 映射到当前平台时，补上 classifier；
 *   3. 地址 = 库自带 url 拼上路径；url 为空时按 group 选仓库：
 *      net.fabricmc / org.quiltmc 走 Fabric maven，其余走 Mojang libraries。
 */
object FabricLibraries {

    /** 相对 libraries 根目录的路径；解析不出返回 null。 */
    fun jarPath(library: Library, os: OsFamily = OsFamily.current()): String? {
        val fromDownloads = library.downloads?.artifact
        if (fromDownloads != null) {
            val path = fromDownloads.path
            if (!path.isNullOrBlank()) return path
        }
        val name = LibraryName.parse(library.name) ?: return null
        val classifier = name.classifier ?: library.nativeClassifier(os)
        return LibraryName(
            group = name.group,
            artifact = name.artifact,
            version = name.version,
            classifier = classifier,
        ).basePath
    }

    /** 完整下载地址；解析不出返回 null。 */
    fun jarUrl(library: Library, os: OsFamily = OsFamily.current()): String? {
        val path = jarPath(library, os) ?: return null

        val fromDownloads = library.downloads?.artifact?.url
        if (!fromDownloads.isNullOrBlank()) return fromDownloads

        val declared = library.url?.trim()
        if (!declared.isNullOrBlank()) {
            if (declared.endsWith(".jar", ignoreCase = true)) return declared
            return declared.trimEnd('/') + "/" + path
        }

        val group = LibraryName.parse(library.name)?.group.orEmpty()
        val base = when {
            group.startsWith("net.fabricmc") || group.startsWith("org.quiltmc") -> FabricMeta.FABRIC_MAVEN
            else -> FabricMeta.MOJANG_LIBRARIES
        }
        return base + path
    }

    /** 该库在当前平台是否要装（rules 过滤），以及最终 jar 的 sha1/size。 */
    fun sha1Of(library: Library): String? =
        library.sha1?.takeIf { it.isNotBlank() } ?: library.downloads?.artifact?.sha1

    fun sizeOf(library: Library): Long {
        val flat = library.size
        if (flat > 0) return flat
        val nested = library.downloads?.artifact?.size ?: -1L
        return if (nested > 0) nested else -1L
    }

    /**
     * 把 profile 里的 libraries 规划成下载项。target 落在 <librariesDir>/<maven 路径>，
     * 与 LaunchArguments.classpathLibraries 推出的路径一致，启动时才找得到。
     */
    fun plan(
        version: VersionJson,
        librariesDir: File,
        os: OsFamily = OsFamily.current(),
        arch: String = OsFamily.currentArch(),
        features: Map<String, Boolean> = emptyMap(),
    ): List<DownloadItem> {
        val items = version.libraries.mapNotNull { library ->
            if (!library.isAllowed(os, arch, features)) return@mapNotNull null
            val path = jarPath(library, os) ?: return@mapNotNull null
            val url = jarUrl(library, os) ?: return@mapNotNull null
            val target = File(librariesDir, path).absolutePath
            DownloadItem(
                url = url,
                target = target,
                sha1 = sha1Of(library),
                size = sizeOf(library),
                // Mojang 的库走 BMCLAPI 镜像；Fabric maven 没有镜像，mirrorOf 返回空表。
                mirrors = DownloadPlanner.mirrorOf(url),
            )
        }
        return items.distinctBy { it.target }
    }
}

private fun describe(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "未知错误"
