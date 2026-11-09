package io.github.huang404yan.platypus.core.loader

import io.github.huang404yan.platypus.core.version.VersionJsonFormat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * NeoForge 元数据：版本清单解析、安装器坐标、以及「这个 NeoForge 版本对应哪个 MC」。
 *
 * 这里只做纯逻辑：不联网、不下载、不跑进程。联网与运行安装器在 ui 层的 NeoForgeInstaller 里。
 *
 * 与 Forge 的三处硬差别（决定了这份代码不能直接复用 ForgeMeta）：
 * 1. **源不同**。官方 maven.neoforged.net 在本机连不上（TLS 握手被重置），只能用 BMCLAPI 镜像。
 * 2. **没有 promotions_slim.json 那种「推荐版本表」**。NeoForge 只发 maven-metadata.xml，
 *    里面是一长串版本号（实测 1856 个），没有「哪个是推荐的」这一层。
 * 3. **版本号与 MC 版本不是显式映射**。Forge 的 maven 坐标里带着原版号（1.20.1-47.4.0），
 *    NeoForge 只有 21.1.265 这样的单一版本号，MC 版本得自己推或从 installer jar 里读。
 *
 * 推导规则见 [guessMinecraftVersion] 的注释；最终**一律以 installer jar 内 version.json
 * 的 inheritsFrom 为准**，猜错了会在安装前被 [readInheritsFrom] 拦下来。
 */
object NeoForgeMeta {

    /**
     * 用 BMCLAPI 镜像而不是官方 maven。
     *
     * 实测（2026-11-09）：maven.neoforged.net 的 DNS 正常但 TLS 连接被重置
     * （curl: (35) Recv failure: Connection was reset），加 -4 也一样；
     * 镜像 https://bmclapi2.bangbang93.com/maven/ 正常返回 200。
     */
    const val NEOFORGE_MAVEN: String = "https://bmclapi2.bangbang93.com/maven/"

    /** 版本清单在 maven 里的相对路径。 */
    const val MAVEN_METADATA_PATH: String = "net/neoforged/neoforge/maven-metadata.xml"

    /** 生成的服务端/客户端入口类，用来判断装出来的版本 json 是否像样。 */
    const val MAIN_CLASS: String = "cpw.mods.bootstraplauncher.BootstrapLauncher"

    /** installer jar 根目录的版本描述文件（注意不是 Forge 的 install_profile.json）。 */
    const val INSTALLER_VERSION_JSON: String = "version.json"

    /** installer jar 根目录的安装描述文件：依赖库清单、processor 链、以及要 patch 的坐标。 */
    const val INSTALLER_PROFILE_JSON: String = "install_profile.json"

    /** 官方 maven 前缀，installer 会在 install_profile.json 里把依赖库写成这个前缀。 */
    const val OFFICIAL_MAVEN = "https://maven.neoforged.net/releases/"

    /** Mojang 官方库前缀。 */
    const val MOJANG_LIBRARIES = "https://libraries.minecraft.net/"

    /**
     * 安装器要求目标目录里有 launcher_profiles.json，否则不报错、退出码 0、什么都不装。
     * 与 Forge 用的是同一个文件，内容也一样，直接复用那边的常量，别写两份字面量。
     */
    const val LAUNCHER_PROFILES_JSON: String = ForgeMeta.LAUNCHER_PROFILES_JSON

    /** 版本清单地址。 */
    fun metadataUrl(): String = NEOFORGE_MAVEN + MAVEN_METADATA_PATH

    /**
     * 安装器地址。注意 NeoForge 的 maven 坐标只有一段：net/neoforged/neoforge/<版本>/。
     * 例：https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/21.1.265/neoforge-21.1.265-installer.jar
     */
    fun installerUrl(neoforgeVersion: String): String =
        NEOFORGE_MAVEN + "net/neoforged/neoforge/" + neoforgeVersion + "/neoforge-" + neoforgeVersion + "-installer.jar"

    /** 落盘文件名，与官方 installer 下载下来的名字一致。 */
    fun installerFileName(neoforgeVersion: String): String = "neoforge-" + neoforgeVersion + "-installer.jar"

    /**
     * 安装后的版本号。
     *
     * 与 Forge 不同，这里**不加原版号前缀**：installer jar 内 version.json 的 id 就是
     * 「neoforge-<版本>」，installer 按这个 id 建目录，跟它对齐才不会出现「装完了找不到」。
     * 例：neoforge-21.1.265
     */
    fun versionId(neoforgeVersion: String): String = "neoforge-" + neoforgeVersion

    /** 展示名，用于实例名。 */
    fun displayName(neoforgeVersion: String): String = "NeoForge " + neoforgeVersion

    /** 安装器落盘的目录：<gameRoot>/neoforge-installers/（installer jar 与运行日志都放这儿）。 */
    fun installersDir(gameRoot: File): File = File(gameRoot, "neoforge-installers")

    /**
     * 从版本 id 反解 NeoForge 版本，只认本启动器 / installer 自己产出的写法：neoforge-21.1.265。
     * 版本段必须以数字起头，避免把 "neoforge" 本身当成版本。
     */
    fun loaderVersionFromId(versionId: String): String? =
        PatternOwnId.find(versionId)?.groupValues?.get(1)

    private val PatternOwnId = Regex("""^neoforge-(\d[0-9A-Za-z.\-]*)$""")

    /**
     * 从 installer jar 里读它真正针对的 MC 版本。
     *
     * version.json 就在 jar 根目录，字段 inheritsFrom 是目标原版版本。读不到（文件缺失、
     * 不是 jar、没有该字段）返回 null，调用方要当成「无法确认」处理，不能当成匹配。
     */
    fun readInheritsFrom(installerJar: File): String? {
        if (!installerJar.isFile || installerJar.length() <= 0L) return null
        return runCatching {
            ZipFile(installerJar).use { zip ->
                val entry = zip.getEntry(INSTALLER_VERSION_JSON) ?: return@use null
                val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                (VersionJsonFormat.parseToJsonElement(text).jsonObject["inheritsFrom"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /**
     * 从 NeoForge 版本号推 MC 版本。
     *
     * 规则（对实测的 10 个样本全部成立）：去掉最后一段构建号；若剩下最后一段是 0 也去掉；
     * 首段是 20 / 21 时补上 "1." 前缀（Minecraft 在 1.21 之后去掉了 1. 前缀）。
     *
     *   21.1.265   → 21.1   → 1.21.1
     *   21.0.167   → 21.0   → 21    → 1.21
     *   20.4.253   → 20.4   → 1.20.4
     *   20.3.8-beta→ 20.3   → 1.20.3
     *   26.3.0.101 → 26.3.0 → 26.3
     *   26.1.2.126 → 26.1.2
     *
     * 这只是**启发式**：版本号里没有权威信息，同前缀也可能对不上。所以安装流程里拿到
     * installer jar 后一定用 [readInheritsFrom] 复核，不一致就中止。
     * 认不出的（例如 "0.25w14craftmine.1-beta"）返回 null。
     */
    fun guessMinecraftVersion(neoforgeVersion: String): String? {
        val raw = neoforgeVersion.trim()
        if (raw.isEmpty()) return null
        val parts = raw.split('.').map { it.trim() }
        // 至少得有「主.次.构建」三段才谈得上去掉构建号
        if (parts.size < 3) return null
        if (parts.any { it.isEmpty() }) return null
        val head = parts[0].toIntOrNull() ?: return null
        // 首段必须是正数：清单里还挂着 "0.25w14craftmine.1-beta" 这类早期试作品，
        // 它们长得像三段版本号，推出来会是 "0.25w14craftmine" 这种不存在的 MC 版本。
        if (head < 1) return null
        val minecraftParts = parts.dropLast(1).toMutableList()
        if (minecraftParts.size >= 2 && minecraftParts.last() == "0") minecraftParts.removeAt(minecraftParts.size - 1)
        // 20.x / 21.x 属于 1.x 世代；26.x 起 Mojang 改了版本号写法，不再带 1. 前缀
        val prefix = if (head in 1..21) "1." else ""
        return prefix + minecraftParts.joinToString(".")
    }
}

/** 解析结果壳（与 ForgeParse / FabricParse 同形，但别把不同加载器的错误混在一起）。 */
sealed interface NeoForgeParse<out T> {
    data class Ok<T>(val value: T) : NeoForgeParse<T>
    data class Failure(val reason: String) : NeoForgeParse<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value
    fun reasonOrNull(): String? = (this as? Failure)?.reason
}

/**
 * 一个可选的 NeoForge 版本。
 *
 * @param minecraftVersion 按 [NeoForgeMeta.guessMinecraftVersion] 推出来的目标原版号，
 *   只用于筛选与展示；真正安装前会用 jar 内 inheritsFrom 复核。
 * @param stable 版本号里没带 -beta / -alpha / -pre 之类的预发布标记。
 */
data class NeoForgeVersion(
    val version: String,
    val minecraftVersion: String,
    val stable: Boolean,
) {
    val label: String get() = if (stable) "稳定" else "预发布"
}

/**
 * 解析 maven-metadata.xml 里的版本清单。
 *
 * 结构很简单（<metadata><versioning><versions><version>…</version></versions></versioning></metadata>），
 * 用正则扫 <version> 就够了，不值得为它引一个 XML 解析器。
 * <versions> 里的顺序是升序，靠后的更新；[pickNeoForgeForMinecraft] 依赖这个顺序取最后一个。
 *
 * 认不出 MC 版本的条目（例如 "0.25w14craftmine.1-beta"）会被丢掉——它们没法安全安装。
 */
fun parseNeoForgeMetadata(text: String): NeoForgeParse<List<NeoForgeVersion>> {
    if (text.isBlank()) return NeoForgeParse.Failure("NeoForge 版本清单为空：服务器返回了空响应")
    if (!text.contains("<metadata")) {
        return NeoForgeParse.Failure("NeoForge 版本清单不像 maven-metadata.xml（开头是：" + text.take(60) + "）")
    }
    val versions = VersionTagRegex.findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .mapNotNull { raw ->
            val mc = NeoForgeMeta.guessMinecraftVersion(raw) ?: return@mapNotNull null
            NeoForgeVersion(version = raw, minecraftVersion = mc, stable = !PrereleaseRegex.containsMatchIn(raw))
        }
        .toList()
    if (versions.isEmpty()) {
        return NeoForgeParse.Failure("NeoForge 版本清单里没有一条能认出对应的 Minecraft 版本")
    }
    return NeoForgeParse.Ok(versions)
}

/** 预发布标记：beta / alpha / pre / rc / snapshot。 */
private val PrereleaseRegex = Regex("""-(beta|alpha|pre|rc|snapshot)""", RegexOption.IGNORE_CASE)

private val VersionTagRegex = Regex("""<version>([^<]+)</version>""")

/**
 * 从清单里挑一个能装在 [minecraftVersion] 上的 NeoForge 版本。
 *
 * 优先稳定版；同稳定度取清单里靠后的（metadata 是升序）。一个都没有返回 null —— 
 * 说明这个 MC 版本还没被 NeoForge 支持，界面要如实说「不支持」而不是随便装一个。
 */
fun pickNeoForgeForMinecraft(versions: List<NeoForgeVersion>, minecraftVersion: String): NeoForgeVersion? {
    val candidates = versions.filter { it.minecraftVersion == minecraftVersion }
    if (candidates.isEmpty()) return null
    return candidates.lastOrNull { it.stable } ?: candidates.last()
}
