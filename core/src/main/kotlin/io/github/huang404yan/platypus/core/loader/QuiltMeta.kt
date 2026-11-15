package io.github.huang404yan.platypus.core.loader

import io.github.huang404yan.platypus.core.version.VersionJson
import io.github.huang404yan.platypus.core.version.VersionJsonFormat
import kotlinx.serialization.Serializable

/**
 * Quilt 官方元数据（meta.quiltmc.org）的解析与版本号命名。
 *
 * 与 [FabricMeta] 的关系：两边的元数据形状几乎一样 —— /v3/versions/loader/<游戏版本> 给 loader 列表，
 * /profile/json 给一份 inheritsFrom 指回原版的版本 json。所以这份文件刻意照着它写，不另起一套概念；
 * 落盘与下载也直接复用 [FabricFiles] 与 [FabricLibraries]，那两个对象处理的是版本 json 与 maven 坐标，
 * 与加载器叫什么名字无关。
 *
 * 真正不一样的地方只有两处，也正是这个对象存在的理由：
 *
 * 1. **元数据里没有 stable 字段。** Quilt 的 loader 项只有 version / build / hashes，谁稳定谁预发布
 *    要靠版本号自己判（见 [isRelease]）。
 * 2. **列表顺序不是版本顺序。** 实测 2026-11 拉 1.20.1 的列表共 310 项，第 1 项是 0.20.0-beta.9，
 *    0.24.0 排在中间，最后一项是 0.31.0-beta.6 —— 它按构建时间排。直接取第一个会拿到一个预发布，
 *    所以必须自己排序再挑（见 [latestGrown]）。
 */
object QuiltMeta {

    const val BASE_URL: String = "https://meta.quiltmc.org"

    /** Quilt 自家 maven。profile json 里每个库都自带 url，这里只是兜底与文档用。 */
    const val QUILT_MAVEN: String = "https://maven.quiltmc.org/repository/release/"

    /** Quilt 生成的客户端入口类，用来判断 profile json 是否像样。 */
    const val MAIN_CLASS: String = "org.quiltmc.loader.impl.launch.knot.KnotClient"

    /** GET 这个地址拿到该游戏版本可用的 loader 列表。 */
    fun loaderListUrl(gameVersion: String): String =
        BASE_URL + "/v3/versions/loader/" + gameVersion.trim()

    /** GET 这个地址拿到可直接启动的完整版本 json。 */
    fun profileUrl(gameVersion: String, loaderVersion: String): String =
        loaderListUrl(gameVersion) + "/" + loaderVersion.trim() + "/profile/json"

    /**
     * 安装后的版本号：<原版号>-quilt-<loader 版本>，与 Fabric 那边的
     * <原版号>-fabric-<loader> 对齐。例：1.20.1-quilt-0.24.0
     */
    fun versionId(gameVersion: String, loaderVersion: String): String =
        gameVersion.trim() + "-quilt-" + loaderVersion.trim()

    /** 展示名，用于实例名。 */
    fun displayName(gameVersion: String, loaderVersion: String): String =
        "Quilt " + loaderVersion.trim() + " / " + gameVersion.trim()

    /**
     * 从版本 id 里反解 loader 版本，认三种写法：
     *  1.20.1-quilt-0.24.0（本启动器）
     *  quilt-loader-0.24.0-1.20.1（Quilt 官方 profile 的 id）
     *  quilt-0.24.0（老的自定义命名）
     *
     * 官方那条排在前面：quilt-loader-0.24.0-1.20.1 里没有 "-quilt-" 这一段，反过来用宽松正则
     * 又能匹配上一大串，所以先匹配更严格的那条。
     */
    fun loaderVersionFromId(versionId: String): String? {
        PatternOfficial.find(versionId)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        PatternOwn.find(versionId)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    // 官方 profile 的 id 是 quilt-loader-0.24.0-1.20.1，loader 版本号后面还跟着原版号，
    // 所以这里只吃到 0.24.0 或 0.20.0-beta.9 为止，不能把 -1.20.1 一起吞进去。
    // 预发布后缀只认这几个关键词。曾经写成一串 [0-9A-Za-z.\-]，结果把官方 id 尾巴上的
    // -1.20.1（原版号）也吞进来了，反解出 "0.24.0-1.20.1" 这种不存在的 loader 版本。
    private val PreTag = "(?:-(?:beta|alpha|rc|pre|snapshot)[0-9A-Za-z.\\-]*)?"
    private val PatternOfficial = Regex("quilt[-_]loader[-_](\\d+\\.\\d+(?:\\.\\d+)?" + PreTag + ")")
    private val PatternOwn = Regex(".*-quilt-(\\d+\\.\\d+(?:\\.\\d+)?" + PreTag + ")$")

    /** 预发布后缀；Quilt 的版本号形如 0.24.0 与 0.20.0-beta.9。 */
    private val PreReleaseMarks = listOf("-beta", "-alpha", "-rc", "-pre", "-snapshot")

    /** 这个 loader 版本是不是正式版（不含 beta / alpha / rc / pre / snapshot 标记）。 */
    fun isRelease(version: String): Boolean {
        val lower = version.trim().lowercase()
        if (lower.isEmpty()) return false
        return PreReleaseMarks.none { lower.contains(it) }
    }

    /** 三段数字加可选预发布后缀解析出来的结果。 */
    private class ParsedVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val pre: String,
    )

    /**
     * 按版本号比较两个 loader 版本，返回负数表示 a 更旧，0 表示相等。
     *
     * 之所以不用列表顺序：Quilt 的列表按构建时间排，不是按版本号排（见类注释）。
     * 解析不出三段数字的（理论上不该有）退化成字符串比较，保证不会抛异常。
     */
    fun compareVersions(a: String, b: String): Int {
        val left = parseVersion(a)
        val right = parseVersion(b)
        if (left == null || right == null) return a.compareTo(b)
        if (left.major != right.major) return left.major.compareTo(right.major)
        if (left.minor != right.minor) return left.minor.compareTo(right.minor)
        if (left.patch != right.patch) return left.patch.compareTo(right.patch)
        // 主版本相同：预发布排在正式版前面（0.24.0-beta.1 早于 0.24.0）。
        if (left.pre.isEmpty() && right.pre.isEmpty()) return 0
        if (left.pre.isEmpty()) return 1
        if (right.pre.isEmpty()) return -1
        return left.pre.compareTo(right.pre)
    }

    private fun parseVersion(raw: String): ParsedVersion? {
        val match = VersionPattern.find(raw.trim()) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: return null
        return ParsedVersion(major, minor, patch, match.groupValues[4])
    }

    private val VersionPattern = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(.+))?$""")

    /**
     * 挑一个默认安装的 loader 版本：正式版里最高的那个；一个正式版都没有时退回整体最高的。
     *
     * 全部是 beta 的情况确实存在（新游戏版本的早期只有预发布），那时候退回最高版本比报错有用 ——
     * 用户想装的本来就是这个游戏版本，硬拦下来只会让他没得选。
     */
    fun latestGrown(versions: List<QuiltLoaderVersion>): QuiltLoaderVersion? {
        val clean = versions.filter { it.version.isNotBlank() }
        if (clean.isEmpty()) return null
        val releases = clean.filter { isRelease(it.version) }
        val pool = if (releases.isEmpty()) clean else releases
        return pool.reduceOrNull { best, next -> if (compareVersions(next.version, best.version) > 0) next else best }
    }
}

/** 解析结果壳；与 [FabricParse] 同形，只是名字不同，免得调用方以为拿到的是 Fabric 的东西。 */
sealed interface QuiltParse<out T> {
    data class Ok<T>(val value: T) : QuiltParse<T>
    data class Failure(val reason: String) : QuiltParse<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
    fun reasonOrNull(): String? = (this as? Failure)?.reason
}

/** /v3/versions/loader/<游戏版本> 数组里一项的 loader 对象。 */
@Serializable
data class QuiltLoaderVersion(
    val version: String = "",
    val build: Int = -1,
    val maven: String = "",
    val separator: String = "",
) {
    val label: String get() = if (version.isBlank()) "未知版本" else version
    val isRelease: Boolean get() = QuiltMeta.isRelease(version)
}

/**
 * 数组项。实测 2026-11 的形状是 {"loader":{...},"hashed":{...},"intermediary":{...},"launcherMeta":{...}}，
 * 这里只取 loader 那一层；其余字段不解析（VersionJsonFormat 已经 ignoreUnknownKeys）。
 */
@Serializable
data class QuiltLoaderEntry(
    val loader: QuiltLoaderVersion? = null,
    val version: String = "",
    val build: Int = -1,
    val maven: String = "",
) {
    val resolved: QuiltLoaderVersion?
        get() = loader ?: if (version.isNotBlank()) {
            QuiltLoaderVersion(version = version, build = build, maven = maven)
        } else {
            null
        }
}

/** 解析后的 profile：原始文本要原样落盘（里面字段比我们的模型多，重新序列化会丢）。 */
data class QuiltProfile(
    val versionJson: VersionJson,
    val gameVersion: String,
    val loaderVersion: String,
    val raw: String,
) {
    val mainClass: String get() = versionJson.mainClass.ifBlank { QuiltMeta.MAIN_CLASS }
}

/** 解析 loader 版本列表。 */
fun parseQuiltLoaderVersions(text: String): QuiltParse<List<QuiltLoaderVersion>> {
    if (text.isBlank()) return QuiltParse.Failure("Quilt 元数据为空：服务器返回了空响应")
    val entries: List<QuiltLoaderEntry> = try {
        VersionJsonFormat.decodeFromString(text)
    } catch (e: Exception) {
        return QuiltParse.Failure("Quilt loader 列表解析失败：" + quiltDescribe(e))
    }
    if (entries.isEmpty()) return QuiltParse.Failure("Quilt 元数据里没有任何 loader 版本")
    val versions = entries.mapNotNull { it.resolved }.filter { it.version.isNotBlank() }
    if (versions.isEmpty()) return QuiltParse.Failure("Quilt 元数据里没有可用的 loader 版本（字段缺失）")
    return QuiltParse.Ok(versions)
}

/**
 * 解析 profile json。会校验 libraries、mainClass 两个关键字段，外加 mainClass 里要不含 quilt：
 * 缺 libraries 说明这份 json 是残的，装上也起不来，直接报原因而不是留给启动时炸。
 */
fun parseQuiltProfile(
    text: String,
    gameVersion: String,
    loaderVersion: String,
): QuiltParse<QuiltProfile> {
    if (text.isBlank()) return QuiltParse.Failure("Quilt profile json 为空：服务器返回了空响应")
    val json: VersionJson = try {
        VersionJsonFormat.decodeFromString(text)
    } catch (e: Exception) {
        return QuiltParse.Failure("Quilt profile json 解析失败：" + quiltDescribe(e))
    }
    if (json.libraries.isEmpty()) return QuiltParse.Failure("Quilt profile json 里没有 libraries 字段")
    if (json.mainClass.isBlank()) return QuiltParse.Failure("Quilt profile json 里没有 mainClass 字段")
    if (!json.mainClass.contains("quilt", ignoreCase = true)) {
        return QuiltParse.Failure("Quilt profile json 的 mainClass 不像 Quilt：" + json.mainClass)
    }
    return QuiltParse.Ok(
        QuiltProfile(
            versionJson = json,
            gameVersion = gameVersion,
            loaderVersion = loaderVersion,
            raw = text,
        ),
    )
}

private fun quiltDescribe(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "未知错误"
