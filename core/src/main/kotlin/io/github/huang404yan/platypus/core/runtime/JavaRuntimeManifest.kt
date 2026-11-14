package io.github.huang404yan.platypus.core.runtime

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.version.OsFamily
import io.github.huang404yan.platypus.core.version.VersionJsonFormat
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mojang 官方 Java 运行时清单（products/java-runtime/.../all.json）。
 *
 * 顶层结构：平台 → 组件 → 条目数组。每个条目带一个 manifest 地址（该组件在该平台上的
 * 完整文件树清单）和它对应的版本名。整份清单里没有「Java 主版本」字段，组件 id 才是
 * 权威标识（java-runtime-gamma = 17 之类由 Mojang 自己维护）。
 *
 * 该索引文件的 URL 里含 Mojang 自己写死的 hash 段，换版本时地址会变，所以按常量维护。
 */
const val MOJANG_RUNTIME_INDEX_URL =
    "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"

/** 单个运行时组件在某个平台上的一个版本条目。 */
data class JavaRuntimeEntry(
    val component: String,
    val manifestUrl: String,
    val manifestSha1: String? = null,
    val manifestSize: Long = -1L,
    val versionName: String = "",
)

/** 整份运行时索引。 */
data class JavaRuntimeIndex(val platforms: Map<String, List<JavaRuntimeEntry>> = emptyMap()) {
    fun entries(platform: String): List<JavaRuntimeEntry> = platforms[platform].orEmpty()

    fun find(platform: String, component: String): JavaRuntimeEntry? =
        entries(platform).firstOrNull { it.component == component }
}

/**
 * 解析运行时索引。只取「平台 → 组件 → 数组」这一种形状，其它形状（Mojang 偶尔会加
 * gamecore 之类的键）直接跳过，宁可少一个平台也不要因为多一个字段整份解析失败。
 */
fun parseRuntimeIndex(text: String): JavaRuntimeIndex {
    val root = VersionJsonFormat.parseToJsonElement(text) as? JsonObject
        ?: throw IllegalArgumentException("运行时索引不是 JSON 对象")
    val platforms = LinkedHashMap<String, List<JavaRuntimeEntry>>()
    for ((platform, value) in root) {
        val components = value as? JsonObject ?: continue
        val entries = ArrayList<JavaRuntimeEntry>()
        for ((component, list) in components) {
            val array = list as? JsonArray ?: continue
            for (element in array) {
                val entry = element as? JsonObject ?: continue
                val manifest = entry["manifest"] as? JsonObject ?: continue
                val url = manifest.stringAt("url") ?: continue
                entries += JavaRuntimeEntry(
                    component = component,
                    manifestUrl = url,
                    manifestSha1 = manifest.stringAt("sha1"),
                    manifestSize = manifest.longAt("size") ?: -1L,
                    versionName = (entry["version"] as? JsonObject)?.stringAt("name").orEmpty(),
                )
            }
        }
        if (entries.isNotEmpty()) platforms[platform] = entries
    }
    return JavaRuntimeIndex(platforms)
}

/**
 * 当前系统对应的索引平台键。
 *
 * 实测（2026-11-09）windows-x64 下有 alpha/beta/gamma/gamma-snapshot/delta/epsilon/jre-legacy
 * 与 minecraft-java-exe；windows-x86 没有 delta/epsilon，windows-arm64 有 gamma/delta/epsilon。
 */
fun runtimePlatform(os: OsFamily = OsFamily.current(), arch: String = OsFamily.currentArch()): String {
    val normalized = arch.trim().lowercase()
    return when (os) {
        OsFamily.WINDOWS -> when {
            normalized == "x86" || normalized == "i386" || normalized == "i686" -> "windows-x86"
            normalized == "aarch64" || normalized == "arm64" -> "windows-arm64"
            else -> "windows-x64"
        }
        OsFamily.MACOS -> if (normalized == "aarch64" || normalized == "arm64") "mac-os-arm64" else "mac-os"
        OsFamily.LINUX -> if (normalized == "x86" || normalized == "i386" || normalized == "i686") "linux-i386" else "linux"
        OsFamily.UNKNOWN -> "linux"
    }
}

/**
 * 主版本 → Mojang 组件 id。
 *
 * 这是「version json 里没写 component」时的兜底映射，实测自 2026-11-09 的官方索引：
 * jre-legacy=8、alpha=16、beta/gamma=17、delta=21、epsilon=25。
 * Java 9–15 这类中间版本 Mojang 没有对应运行时，返回 null（调用方应明确报错）。
 */
object JavaRuntimeComponents {
    const val JRE_LEGACY = "jre-legacy"
    const val ALPHA = "java-runtime-alpha"
    const val BETA = "java-runtime-beta"
    const val GAMMA = "java-runtime-gamma"
    const val GAMMA_SNAPSHOT = "java-runtime-gamma-snapshot"
    const val DELTA = "java-runtime-delta"
    const val EPSILON = "java-runtime-epsilon"

    fun componentForMajor(major: Int): String? = when {
        major <= 8 -> JRE_LEGACY
        major == 16 -> ALPHA
        major == 17 -> GAMMA
        major == 21 -> DELTA
        major >= 25 -> EPSILON
        else -> null
    }

    /**
     * 组件 id 反过来推主版本号，界面列表要按它显示「Java 21」。
     *
     * 只认上面这张表里的六个组件：清单里还有 gamma-snapshot（旧快照用的 17）与
     * minecraft-java-exe（不是完整的 Java，只是给启动器自己用的 exe），后者必须排除，
     * 否则列表里会多一行点了装不上的东西。
     */
    fun majorOfComponent(component: String): Int? = when (component) {
        JRE_LEGACY -> 8
        ALPHA -> 16
        BETA, GAMMA -> 17
        GAMMA_SNAPSHOT -> 17
        DELTA -> 21
        EPSILON -> 25
        else -> null
    }

    /**
     * 从官方清单的 version.name 推主版本号。
     *
     * 实测三种写法（2026-11-14 的 windows-x64）：
     * - `21.0.7` / `25.0.1` / `17.0.15` —— 点分隔的现代版本号，取第一段
     * - `16.0.1.9.1` —— 同一个 16 的旧写法，第一段仍是 16
     * - `8u51-cacert462b08` —— jre-legacy 的老写法，主版本在 `u` 前面
     *
     * 认不出来（比如 `14` 那种非 Java 版本号）返回 null。
     */
    fun majorOfVersionName(name: String): Int? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val head = trimmed.substringBefore('.').substringBefore('u').substringBefore('+')
        return head.toIntOrNull()
    }
}

/** 一份可执行的运行时安装规划：要建哪些目录、下哪些文件、最后 java 在哪。 */
data class JavaRuntimePlan(
    val component: String,
    val platform: String,
    val runtimeDir: String,
    val javaExecutable: String,
    val directories: List<String> = emptyList(),
    val files: List<DownloadItem> = emptyList(),
) {
    val totalBytes: Long get() = files.sumOf { if (it.size > 0) it.size else 0L }
    val totalFiles: Int get() = files.size
}

/** 运行时目录下 java 可执行文件的相对位置（Windows 是 bin/java.exe）。 */
fun javaExecutableInRuntime(runtimeDir: File, platform: String = runtimePlatform()): File =
    if (platform.startsWith("windows")) File(File(runtimeDir, "bin"), "java.exe") else File(File(runtimeDir, "bin"), "java")

/**
 * 把某个组件的 manifest.json 转成下载规划。
 *
 * 实测（2026-11-09，gamma 组件 486 项）：每个条目要么是 {"type":"directory"}，要么是
 * {"type":"file","downloads":{"raw":{...},"lzma":{...}},"executable":bool}。**不是所有 file
 * 都有 lzma**（实测踩过 KeyError），所以这里只认 raw：raw 是原样文件，下下来即成品，不需要解压。
 */
fun planRuntimeManifest(
    manifestText: String,
    component: String,
    runtimeDir: File,
    platform: String = runtimePlatform(),
): JavaRuntimePlan {
    val root = VersionJsonFormat.parseToJsonElement(manifestText) as? JsonObject
        ?: throw IllegalArgumentException("运行时 manifest 不是 JSON 对象")
    val filesNode = root["files"] as? JsonObject
        ?: throw IllegalArgumentException("运行时 manifest 缺少 files 字段")
    val directories = ArrayList<String>(filesNode.size)
    val items = ArrayList<DownloadItem>(filesNode.size)
    var executablePath: String? = null
    for ((relative, node) in filesNode) {
        val entry = node as? JsonObject ?: continue
        val target = File(runtimeDir, relative.replace('/', File.separatorChar))
        when (entry.stringAt("type")) {
            "directory" -> directories += target.path
            "file" -> {
                val raw = (entry["downloads"] as? JsonObject)?.get("raw") as? JsonObject ?: continue
                val url = raw.stringAt("url") ?: continue
                items += DownloadItem(
                    url = url,
                    target = target.path,
                    sha1 = raw.stringAt("sha1"),
                    size = raw.longAt("size") ?: -1L,
                )
                if (entry.booleanAt("executable") == true) executablePath = target.path
            }
        }
    }
    val expected = javaExecutableInRuntime(runtimeDir, platform).path
    val javaPath = items.firstOrNull { it.target == expected }?.target
        ?: executablePath
        ?: expected
    return JavaRuntimePlan(
        component = component,
        platform = platform,
        runtimeDir = runtimeDir.path,
        javaExecutable = javaPath,
        directories = directories,
        files = items,
    )
}

/**
 * 下载规划是否可用：清单里一个 raw 文件都没有，说明 Mojang 的清单结构变了（或该组件不存在），
 * 不能当成「已经下好了」。返回给人看的原因，null 表示没问题。
 */
fun runtimePlanProblem(component: String, plan: JavaRuntimePlan): String? =
    if (plan.files.isEmpty()) "$component 的文件清单是空的，清单结构可能变了。" else null

/**
 * 下载完成后回验 java 可执行文件是否真的落地。
 *
 *  java 在运行时目录里的位置是推断出来的（预期路径 → executable 标记 → 预期路径兜底），
 * 所以「下完了」不等于「能启动」：文件不在就是清单结构变了或下载被截断。
 * 返回给人看的原因，null 表示没问题。
 */
fun runtimeJavaProblem(executable: File): String? =
    if (executable.isFile) null else "下完了，但没找到 java 可执行文件：${executable.path}"

private fun JsonObject.stringAt(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.longAt(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

private fun JsonObject.booleanAt(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/**
 * 把运行时索引摊平成「这台机器能装哪几个 Java」的列表。
 *
 * 三条过滤，都是实测踩出来的：
 * - **只留这张表里认得的六个组件**：清单里还有 `gamma-snapshot`（旧快照用的 17）与
 *   `minecraft-java-exe`（14 / 12，不是 Java，是启动器自己要用的 exe，装下来只有一个
 *   几百字节的清单、根本不是 JRE）。不筛的话列表里会多出点了装不上的行。
 * - **同一个主版本只留一行**：beta 与 gamma 实测都是 17（beta 是旧的一支），
 *   留 gamma 那一支 —— 新版游戏 json 里写的就是 gamma。
 * - **按主版本升序**：8 / 16 / 17 / 21 / 25，用户从上往下看是「从老到新」。
 *
 * @param componentOf 组件 → 主版本号；测试里可以塞一个假的映射表
 * @param installedOf 组件 → 本地已有的 java 路径（没有就返回 null）
 */
fun buildRuntimeOffers(
    index: JavaRuntimeIndex,
    platform: String,
    installedOf: (String) -> String? = { null },
): List<JavaRuntimeOffer> {
    // 同一主版本里优先取的组件：gamma 胜过 beta，其余一一对应。
    val preferred = LinkedHashMap<Int, JavaRuntimeEntry>()
    for (entry in index.entries(platform)) {
        val major = JavaRuntimeComponents.majorOfComponent(entry.component) ?: continue
        if (entry.component == JavaRuntimeComponents.GAMMA_SNAPSHOT) continue
        val existing = preferred[major]
        if (existing == null || rank(entry.component) < rank(existing.component)) {
            preferred[major] = entry
        }
    }
    return preferred.entries
        .sortedBy { it.key }
        .map { (major, entry) ->
            val path = installedOf(entry.component)
            JavaRuntimeOffer(
                component = entry.component,
                majorVersion = major,
                versionName = entry.versionName,
                manifestSize = entry.manifestSize,
                installed = path != null,
                installedPath = path,
            )
        }
}

/** 同主版本时哪个组件更该被推荐：数字越小越优先（gamma 与 beta 都是 17，要 gamma）。 */
private fun rank(component: String): Int = when (component) {
    JavaRuntimeComponents.GAMMA -> 0
    JavaRuntimeComponents.BETA -> 1
    JavaRuntimeComponents.ALPHA -> 2
    JavaRuntimeComponents.DELTA -> 3
    JavaRuntimeComponents.EPSILON -> 4
    JavaRuntimeComponents.JRE_LEGACY -> 5
    else -> 9
}
