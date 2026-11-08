package io.github.huang404yan.platypus.core.version

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 版本元数据：Mojang 的 version_manifest_v2.json 与单个版本的 <id>.json。
 *
 * 字段全部给了默认值，因为不同加载器（Forge/NeoForge/Fabric）生成的 json 会缺胳膊少腿，
 * 缺字段不应该直接抛异常。
 */
val VersionJsonFormat: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

fun parseVersionManifest(text: String): VersionManifest = VersionJsonFormat.decodeFromString(text)

fun parseVersionJson(text: String): VersionJson = VersionJsonFormat.decodeFromString(text)

@Serializable
data class VersionManifest(
    val latest: LatestVersions? = null,
    val versions: List<ManifestVersion> = emptyList(),
) {
    @Serializable
    data class LatestVersions(
        val release: String = "",
        val snapshot: String = "",
    )

    fun find(id: String): ManifestVersion? = versions.firstOrNull { it.id == id }

    /** 正式版列表，按 json 里的顺序（新的在前）。 */
    fun releases(): List<ManifestVersion> = versions.filter { it.type == "release" }
}

@Serializable
data class ManifestVersion(
    val id: String = "",
    val type: String = "",
    val url: String = "",
    val time: String = "",
    val releaseTime: String = "",
    val sha1: String? = null,
    val complianceLevel: Int = 0,
)

@Serializable
data class VersionJson(
    val id: String = "",
    val inheritsFrom: String? = null,
    val type: String = "",
    val mainClass: String = "",
    val assets: String = "",
    val assetIndex: AssetIndex? = null,
    val downloads: VersionDownloads? = null,
    val libraries: List<Library> = emptyList(),
    val arguments: Arguments? = null,
    val minecraftArguments: String? = null,
    val javaVersion: JavaVersion? = null,
    val releaseTime: String = "",
    val time: String = "",
) {
    /** 原版客户端 jar 的下载信息；Forge 之类的继承型版本可能没有。 */
    val clientJar: DownloadFile? get() = downloads?.client

    /** 需要的 Java 大版本；老版本没有 javaVersion 字段，按规则推断。 */
    val requiredJavaMajor: Int
        get() = javaVersion?.majorVersion ?: if (assets.startsWith("pre-1.6")) 8 else 8
}

@Serializable
data class AssetIndex(
    val id: String = "",
    val sha1: String? = null,
    val size: Long = -1L,
    val totalSize: Long = -1L,
    val url: String = "",
)

@Serializable
data class VersionDownloads(
    val client: DownloadFile? = null,
    val server: DownloadFile? = null,
    val client_mappings: DownloadFile? = null,
    val server_mappings: DownloadFile? = null,
)

@Serializable
data class DownloadFile(
    val sha1: String? = null,
    val size: Long = -1L,
    val url: String = "",
    val path: String? = null,
)

@Serializable
data class JavaVersion(val component: String = "", val majorVersion: Int = 8)

@Serializable
data class Arguments(
    val game: List<JsonElement> = emptyList(),
    val jvm: List<JsonElement> = emptyList(),
)

@Serializable
data class Library(
    val name: String = "",
    val downloads: LibraryDownloads? = null,
    val rules: List<Rule>? = null,
    val natives: JsonElement? = null,
    val extract: Extract? = null,
) {
    /** 该库在当前平台是否应该被安装。 */
    fun isAllowed(
        os: OsFamily = OsFamily.current(),
        arch: String = OsFamily.currentArch(),
        features: Map<String, Boolean> = emptyMap(),
    ): Boolean = RuleEvaluator.isAllowed(rules, os, arch, features)

    /** natives 里当前平台对应的 classifier，例如 windows → natives-windows。 */
    fun nativeClassifier(os: OsFamily = OsFamily.current()): String? {
        val element = natives ?: return null
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
        return obj[os.id]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }
}

@Serializable
data class LibraryDownloads(
    val artifact: DownloadFile? = null,
    val classifiers: Map<String, DownloadFile>? = null,
)

@Serializable
data class Extract(val exclude: List<String> = emptyList())

@Serializable
data class Rule(
    val action: String = "allow",
    val os: OsRule? = null,
    val features: Map<String, Boolean>? = null,
)

@Serializable
data class OsRule(
    val name: String? = null,
    val arch: String? = null,
    val version: String? = null,
)

/** maven 坐标 group:artifact:version[:classifier]，转成仓库内路径。 */
data class LibraryName(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String? = null,
) {
    val basePath: String
        get() = buildString {
            append(group.replace('.', '/'))
            append('/')
            append(artifact)
            append('/')
            append(version)
            append('/')
            append(artifact)
            append('-')
            append(version)
            if (!classifier.isNullOrBlank()) {
                append('-')
                append(classifier)
            }
            append(".jar")
        }

    companion object {
        fun parse(name: String): LibraryName? {
            val parts = name.split(':', limit = 4)
            if (parts.size < 3) return null
            return LibraryName(
                group = parts[0],
                artifact = parts[1],
                version = parts[2],
                classifier = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
            )
        }
    }
}

enum class OsFamily(val id: String) {
    WINDOWS("windows"),
    MACOS("osx"),
    LINUX("linux"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun current(): OsFamily {
            val name = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                name.contains("win") -> WINDOWS
                name.contains("mac") || name.contains("darwin") -> MACOS
                name.contains("linux") -> LINUX
                else -> UNKNOWN
            }
        }

        fun currentArch(): String = System.getProperty("os.arch").orEmpty()
    }
}

/**
 * rules 求值，语义照抄 Mojang 的规范：
 * rules 为空 → 允许；否则先视为不允许，逐条匹配，最后匹配的那条决定结果。
 */
object RuleEvaluator {

    fun isAllowed(
        rules: List<Rule>?,
        os: OsFamily = OsFamily.current(),
        arch: String = OsFamily.currentArch(),
        features: Map<String, Boolean> = emptyMap(),
    ): Boolean {
        if (rules.isNullOrEmpty()) return true
        var matched = false
        var allowed = false
        for (rule in rules) {
            if (!matches(rule, os, arch, features)) continue
            matched = true
            allowed = rule.action.equals("allow", ignoreCase = true)
        }
        return matched && allowed
    }

    private fun matches(rule: Rule, os: OsFamily, arch: String, features: Map<String, Boolean>): Boolean {
        rule.os?.let { osRule ->
            val osName = osRule.name
            if (!osName.isNullOrBlank() && osName != os.id) return false
            val ruleArch = osRule.arch
            if (!ruleArch.isNullOrBlank() && !archMatches(ruleArch, arch)) return false
        }
        rule.features?.forEach { (key, expected) ->
            val actual = features[key] ?: false
            if (actual != expected) return false
        }
        return true
    }

    private fun archMatches(expected: String, actual: String): Boolean {
        val a = actual.lowercase()
        return when (expected.lowercase()) {
            "x86" -> a == "x86" || a == "i386" || a == "i686"
            "x86_64", "amd64", "x86-64" -> a == "x86_64" || a == "amd64"
            "arm64", "aarch64" -> a == "aarch64" || a == "arm64"
            else -> expected.equals(actual, ignoreCase = true)
        }
    }
}
