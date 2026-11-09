package io.github.huang404yan.platypus.core.loader

import io.github.huang404yan.platypus.core.version.VersionJsonFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Forge 官方元数据的解析、版本号命名与安装器坐标。
 *
 * 这里只做纯逻辑：不联网、不下载、不跑进程。联网与运行安装器在 ui 层的 ForgeInstaller 里。
 *
 * 与 Fabric 的差别（决定了实现方式）：Fabric 只要拉一个 profile json 再下几个库就能起，
 * Forge 的版本 json 里有一堆 `-DignoreList`、`-p`（module path）、`--add-opens`，
 * 而且客户端 jar 要经过 jarsplitter → ForgeAutoRenamingTool → binarypatcher 三个
 * processor 处理过才能用。手写这套 processor 链既要逐条实现又要跟 Forge 的版本演进而变，
 * 所以这里走 Forge 官方 installer：它的 `--installClient` 会把整套东西按官方行为装好。
 */
object ForgeMeta {

    /** Forge 自家 maven：安装器与所有 Forge 库都在这儿。 */
    const val FORGE_MAVEN: String = "https://maven.minecraftforge.net/"

    /**
     * 版本推荐清单（4 KB 左右的小 json）。结构：
     * {"homepage":..., "promos":{"<原版号>-recommended":"<forge 版本>","<原版号>-latest":"..."}}
     */
    const val PROMOTIONS_URL: String = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"

    /** Forge 生成的服务端/客户端入口类，用来判断装出来的版本 json 是否像样。 */
    const val MAIN_CLASS: String = "cpw.mods.bootstraplauncher.BootstrapLauncher"

    /**
     * Forge 安装器要求目标目录里已有 launcher_profiles.json，否则直接报
     * 「There is no minecraft launcher profile in ..., you need to run the launcher first!」
     * 并**以退出码 0 结束**（看起来像成功，实际什么都没装）。所以装之前要自己铺一个空档案。
     */
    const val LAUNCHER_PROFILES_JSON: String = """{"profiles":{},"settings":{},"version":3}"""

    /** installer 在 maven 里的版本段是 <原版号>-<forge 版本> 拼在一起的单段。 */
    fun artifactVersion(gameVersion: String, forgeVersion: String): String =
        "$gameVersion-$forgeVersion"

    /** 例：https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-installer.jar */
    fun installerUrl(gameVersion: String, forgeVersion: String): String {
        val artifact = artifactVersion(gameVersion, forgeVersion)
        return FORGE_MAVEN + "net/minecraftforge/forge/" + artifact + "/forge-" + artifact + "-installer.jar"
    }

    /** 落盘文件名，与官方 installer 下载下来的名字一致。 */
    fun installerFileName(gameVersion: String, forgeVersion: String): String =
        "forge-" + artifactVersion(gameVersion, forgeVersion) + "-installer.jar"

    /**
     * 安装后的版本号，与 PCL / 官方 installer 一致：<原版号>-forge-<forge 版本>。
     * 例：1.20.1-forge-47.4.0
     */
    fun versionId(gameVersion: String, forgeVersion: String): String =
        "$gameVersion-forge-$forgeVersion"

    /** 展示名，用于实例名。 */
    fun displayName(gameVersion: String, forgeVersion: String): String =
        "Forge $forgeVersion / $gameVersion"

    /** 安装器落盘的目录：<gameRoot>/forge-installers/（installer jar 与运行日志都放这儿）。 */
    fun installersDir(gameRoot: File): File = File(gameRoot, "forge-installers")

    /**
     * 从版本 id 里反解 forge 版本，认两种写法：
     *  1.20.1-forge-47.4.0（本启动器 / 官方 installer）
     *  forge-47.4.0-1.20.1（少数第三方启动器）
     * 版本段必须以数字起头，避免把 "minecraftforge" 之类的词尾当成版本。
     */
    fun loaderVersionFromId(versionId: String): String? {
        PatternOwnId.find(versionId)?.groupValues?.get(1)?.let { return it }
        PatternThirdParty.find(versionId)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private val PatternOwnId = Regex(""".*-forge-(\d[0-9A-Za-z.\-]*)$""")
    private val PatternThirdParty = Regex("""^forge-(\d[0-9A-Za-z.\-]*?)-\d+\.\d+(\.\d+)?""")
}

/** 解析结果壳：成功给值，失败给中文原因（与 FabricParse 同形，但别把两个加载器的错误混在一起）。 */
sealed interface ForgeParse<out T> {
    data class Ok<T>(val value: T) : ForgeParse<T>
    data class Failure(val reason: String) : ForgeParse<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
    fun reasonOrNull(): String? = (this as? Failure)?.reason
}

/** 一个可选的 Forge 版本；promotions 只给两个频道：recommended（推荐）与 latest（最新）。 */
data class ForgeVersion(
    val version: String,
    val recommended: Boolean,
) {
    val label: String get() = if (recommended) "推荐" else "最新"
}

/**
 * 解析 promotions_slim.json，取某个原版版本对应的 Forge 版本。
 *
 * 返回顺序：推荐在前、最新在后（两者相同时只留一条），界面按这个顺序铺选项。
 * 只有 latest 没有 recommended 的版本（新版本刚发时常见）也要能用，所以缺哪个都不报错，
 * 两个都缺才算这个原版版本没有 Forge。
 */
fun parseForgePromotions(text: String, gameVersion: String): ForgeParse<List<ForgeVersion>> {
    if (text.isBlank()) return ForgeParse.Failure("Forge 版本清单为空：服务器返回了空响应")
    val promos: JsonObject? = try {
        val root = VersionJsonFormat.parseToJsonElement(text).jsonObject
        root["promos"]?.jsonObject
    } catch (e: Exception) {
        return ForgeParse.Failure("Forge 版本清单解析失败：" + describeForge(e))
    }
    if (promos == null) return ForgeParse.Failure("Forge 版本清单里没有 promos 字段")

    fun pick(channel: String): String? =
        (promos[gameVersion + "-" + channel] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    val recommended = pick("recommended")
    val latest = pick("latest")
    if (recommended == null && latest == null) {
        return ForgeParse.Failure(
            "Forge 没有为 " + gameVersion + " 发布版本（清单里没有 " + gameVersion + "-recommended / -latest）",
        )
    }
    val versions = buildList {
        if (recommended != null) add(ForgeVersion(recommended, recommended = true))
        if (latest != null && latest != recommended) add(ForgeVersion(latest, recommended = false))
    }
    return ForgeParse.Ok(versions)
}

private fun describeForge(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "未知错误"
