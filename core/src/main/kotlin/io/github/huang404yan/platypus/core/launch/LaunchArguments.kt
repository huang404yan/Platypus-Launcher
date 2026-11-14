package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.auth.Session
import io.github.huang404yan.platypus.core.version.Library
import io.github.huang404yan.platypus.core.version.LibraryName
import io.github.huang404yan.platypus.core.version.OsFamily
import io.github.huang404yan.platypus.core.version.OsRule
import io.github.huang404yan.platypus.core.version.Rule
import io.github.huang404yan.platypus.core.version.RuleEvaluator
import io.github.huang404yan.platypus.core.version.VersionJson
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 拼装命令行需要的全部「环境事实」。
 *
 * 刻意做成纯数据：给了它，[LaunchArguments.build] 就是纯函数，不读盘、不起进程，
 * 单测里能直接断言生成的参数。真正解压 natives、算 classpath 的动作在桌面实现里。
 */
data class LaunchContext(
    /** 游戏根目录（含 versions/ 与 libraries/ 的那一层），对应 --gameDir 的常见来源。 */
    val gameDir: File,
    val assetsDir: File,
    val librariesDir: File,
    /** natives 已解压到的目录，对应 -Djava.library.path。 */
    val nativesDir: File,
    /** 已按 json 顺序排好的 classpath（libraries + client jar）。 */
    val classpath: List<File>,
    val session: Session,
    /** --version 的值，一般就是版本目录名。 */
    val versionName: String,
    val versionType: String = "release",
    val memoryMb: Int = 2048,
    /** rules 里用到的特性开关；默认全关，所以 --demo、自定义分辨率这类参数不会出现。 */
    val features: Map<String, Boolean> = emptyMap(),
    /**
     * 多人游戏「直接连接」的目标，形如 `192.168.1.10:54321`；空串表示不直接进。
     *
     * 只有在 [features] 里打开 [QUICK_PLAY_MULTIPLAYER_FEATURE] 时这个值才会被
     * 拼进命令行——version json 里那条规则带 features 条件，两处必须同时给。
     */
    val quickPlayMultiplayer: String = "",
    val os: OsFamily = OsFamily.current(),
    val arch: String = OsFamily.currentArch(),
    val launcherName: String = "Platypus Launcher",
    val launcherVersion: String = "0",
    /** 用户额外追加的 JVM 参数，排在 json 参数之后。 */
    val extraJvmArgs: List<String> = emptyList(),
    /** 用户额外追加的游戏参数，排在 json 参数之后。 */
    val extraGameArgs: List<String> = emptyList(),
) {
    /** Windows 用分号分隔 classpath，其它平台用冒号。 */
    val classpathSeparator: String get() = if (os == OsFamily.WINDOWS) ";" else ":"
}

/** 拼好的命令行：[jvmArgs] + 主类 + [gameArgs]。 */
data class LaunchCommand(
    val mainClass: String,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
) {
    /** java 进程的完整参数（不含 java 可执行文件本身）。 */
    fun toArgumentList(): List<String> = jvmArgs + mainClass + gameArgs
}

/** 一条待解压的 natives jar；[exclude] 是要跳过的条目前缀。 */
data class NativeArchive(val jar: File, val exclude: List<String>)

/**
 * 把版本元数据翻译成 java 命令行。
 *
 * 纯计算。顺序照抄官方启动器：
 * 内存参数 → json 的 jvm 参数（其中含 -cp 与 -Djava.library.path）→ 用户追加的 jvm 参数
 * → 主类 → json 的 game 参数 → 用户追加的游戏参数。
 */
object LaunchArguments {

    /** 占位符的两个边界；拆开写是为了绕开 Kotlin 的字符串模板语法。 */
    private const val PLACEHOLDER_OPEN = "$" + "{"
    private const val PLACEHOLDER_CLOSE = "}"

    /**
     * 「直接连接服务器」那条规则依赖的特性名。
     *
     * version json 里 `--quickPlayMultiplayer` 是包在
     * `{"rules":[{"action":"allow","features":{"is_quick_play_multiplayer":true}}]}` 里的，
     * 特性开关不打开这条参数根本不会出现——只填占位符是不够的。
     */
    const val QUICK_PLAY_MULTIPLAYER_FEATURE = "is_quick_play_multiplayer"

    /** 直接连接时要打开的整套特性开关。 */
    fun quickPlayFeatures(): Map<String, Boolean> = mapOf(QUICK_PLAY_MULTIPLAYER_FEATURE to true)

    /**
     * 这个版本 json 认不认 `--quickPlayMultiplayer`。
     *
     * 1.20 起 arguments.game 里才有这条规则，更早的版本 json 根本没有：
     * 传了也不会被拼进命令行，游戏照样停在主菜单。界面必须提前说清楚，
     * 不然用户点完「直接进」只会觉得按钮没反应。
     */
    fun supportsQuickPlay(version: VersionJson): Boolean {
        val needle = PLACEHOLDER_OPEN + "quickPlayMultiplayer" + PLACEHOLDER_CLOSE
        return version.arguments?.game.orEmpty().any { element ->
            when (element) {
                is JsonPrimitive -> element.isString && element.content.contains(needle)
                is JsonObject -> when (val value = element["value"]) {
                    is JsonPrimitive -> value.content.contains(needle)
                    is JsonArray -> value.any { (it as? JsonPrimitive)?.content?.contains(needle) == true }
                    else -> false
                }
                else -> false
            }
        }
    }

    fun build(version: VersionJson, context: LaunchContext): LaunchCommand {
        val classpath = context.classpath.joinToString(context.classpathSeparator) { it.path }
        val variables = placeholders(version, context, classpath)
        val jsonJvm = version.arguments?.jvm.orEmpty()
        val jvm = buildList {
            add("-Xmx" + context.memoryMb + "M")
            addAll(chineseLogArgs())
            if (jsonJvm.isEmpty()) {
                // 1.6 及更早的版本 json 里根本没有 jvm 参数，这两条必须自己补。
                add("-Djava.library.path=" + context.nativesDir.path)
                add("-cp")
                add(classpath)
            } else {
                addAll(substituteAll(expand(jsonJvm, context), variables))
            }
            addAll(context.extraJvmArgs)
        }
        val game = buildList {
            val raw = version.arguments?.game.orEmpty()
            if (raw.isNotEmpty()) {
                addAll(substituteAll(expand(raw, context), variables))
            } else if (!version.minecraftArguments.isNullOrBlank()) {
                // 上古版本只有一行 minecraftArguments 字符串，按空格切开。
                addAll(substituteAll(splitPlainArguments(version.minecraftArguments), variables))
            }
            addAll(context.extraGameArgs)
        }
        return LaunchCommand(mainClass = version.mainClass, jvmArgs = jvm, gameArgs = game)
    }

    /**
     * 让游戏把中文日志按 UTF-8 吐出来的两条参数。
     *
     * 背景：游戏在中文 Windows 上按系统代码页（GBK）往 stdout 写中文，我们按 UTF-8 读，
     * 界面上那几行就变成一串 U+FFFD。读侧改不了这件事——字节在写的那一刻就已经丢了。
     *
     * 为什么要同时传两条：JDK 18 起才有 `stdout.encoding`（`file.encoding` 被改成只读），
     * 而 Java 17 及更早只认 `file.encoding`。两个都传才能跨版本通吃，
     * 不认识的 JDK 会把它当成一个没人读的系统属性，没有副作用。
     *
     * 位置在 -Xmx 之后、json 参数与 [LaunchContext.extraJvmArgs] 之前：
     * JVM 对重复的 -D 是后者覆盖前者，用户想改回 GBK 仍然能用额外参数盖掉。
     */
    fun chineseLogArgs(): List<String> = listOf("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")

    /** 老的 minecraftArguments 用空格分隔；值里不会出现空格，简单切即可。 */
    fun splitPlainArguments(text: String): List<String> =
        text.split(' ').filter { it.isNotBlank() }

    private fun substituteAll(args: List<String>, variables: Map<String, String>): List<String> =
        args.map { substitute(it, variables) }

    /**
     * 展开 json 里的参数数组。
     *
     * 两种形态：纯字符串；或 { "rules": [...], "value": "…" | [...] }。
     * 后者在当前 os / arch / features 下不允许就整段丢掉，允许就展开 value。
     */
    fun expand(raw: List<JsonElement>, context: LaunchContext): List<String> = raw.flatMap { element ->
        when {
            element is JsonPrimitive && element.isString -> listOf(element.content)
            element is JsonObject -> {
                val rulesElement = element["rules"]
                val allowed = if (rulesElement == null) {
                    true
                } else {
                    RuleEvaluator.isAllowed(
                        rules = parseRules(rulesElement),
                        os = context.os,
                        arch = context.arch,
                        features = context.features,
                    )
                }
                if (!allowed) {
                    emptyList()
                } else {
                    when (val value = element["value"]) {
                        is JsonPrimitive -> listOf(value.content)
                        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
                        else -> emptyList()
                    }
                }
            }
            else -> emptyList()
        }
    }

    /** 把 rules 数组手工映射成 [Rule]；字段少，不值得为它单开一个序列化模型。 */
    fun parseRules(element: JsonElement): List<Rule> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val action = (obj["action"] as? JsonPrimitive)?.content ?: "allow"
            val osElement = obj["os"] as? JsonObject
            val os = osElement?.let {
                OsRule(
                    name = (it["name"] as? JsonPrimitive)?.content,
                    arch = (it["arch"] as? JsonPrimitive)?.content,
                    version = (it["version"] as? JsonPrimitive)?.content,
                )
            }
            val featuresElement = obj["features"] as? JsonObject
            val features = featuresElement?.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            }
            Rule(action = action, os = os, features = features)
        }
    }

    /** 替换 $KEY；表里没有的键原样保留，方便在日志里一眼看出缺了什么。 */
    fun substitute(text: String, variables: Map<String, String>): String {
        if (!text.contains(PLACEHOLDER_OPEN)) return text
        var result = text
        for ((key, value) in variables) {
            result = result.replace(PLACEHOLDER_OPEN + key + PLACEHOLDER_CLOSE, value)
        }
        return result
    }

    /**
     * 官方启动器认识的占位符。
     *
     * 用不上的（clientid、xuid、user_properties）给空串或 "{}"，老版本会忽略。
     */
    fun placeholders(version: VersionJson, context: LaunchContext, classpath: String): Map<String, String> = buildMap {
        put("auth_player_name", context.session.username)
        put("auth_uuid", context.session.uuid)
        put("auth_access_token", context.session.accessToken)
        put("auth_xuid", "")
        put("auth_session", context.session.accessToken)
        put("clientid", "")
        put("user_type", context.session.userType)
        put("user_properties", "{}")
        put("version_name", context.versionName)
        put("version_type", context.versionType)
        put("game_directory", context.gameDir.path)
        put("game_assets", File(context.assetsDir, "virtual/legacy").path)
        put("assets_root", context.assetsDir.path)
        put("assets_index_name", assetIndexName(version))
        put("natives_directory", context.nativesDir.path)
        put("launcher_name", context.launcherName)
        put("launcher_version", context.launcherVersion)
        put("classpath", classpath)
        put("classpath_separator", context.classpathSeparator)
        put("library_directory", context.librariesDir.path)
        // quickPlay 与分辨率只在对应 feature 打开时出现，默认给固定值，避免一半参数一半占位符。
        put("resolution_width", "854")
        put("resolution_height", "480")
        put("quickPlayPath", "")
        put("quickPlaySingleplayer", "")
        put("quickPlayMultiplayer", context.quickPlayMultiplayer)
        put("quickPlayRealms", "")
    }

    /**
     * 当前平台该进 classpath 的库，按 json 顺序。
     *
     * 老版本（带 natives 字段）的 natives jar 不进 classpath——它们要解压到 java.library.path；
     * 1.19 之后 natives 变成普通 classifier 条目，反而必须进 classpath（LWJGL 3.3 自己抽 dll），
     * 所以这里只按「有没有 natives 字段」区分。
     */
    fun classpathLibraries(version: VersionJson, context: LaunchContext): List<File> =
        version.libraries
            .filter { it.isAllowed(context.os, context.arch, context.features) }
            .filter { it.nativeClassifier(context.os) == null }
            .mapNotNull { library ->
                val relative = library.downloads?.artifact?.path ?: libraryPathOf(library) ?: return@mapNotNull null
                File(context.librariesDir, relative)
            }
            .distinctBy { it.path }

    /** 没有 downloads 字段时退回 maven 坐标推路径（Forge 的部分库会这样）。 */
    private fun libraryPathOf(library: Library): String? = LibraryName.parse(library.name)?.basePath

    /** 当前平台要解压的 natives jar（老版本才有；1.20.1 这类返回空列表）。 */
    fun nativeArchives(version: VersionJson, context: LaunchContext): List<NativeArchive> =
        version.libraries
            .filter { it.isAllowed(context.os, context.arch, context.features) }
            .mapNotNull { library ->
                val classifier = library.nativeClassifier(context.os) ?: return@mapNotNull null
                val file = library.downloads?.classifiers?.get(classifier) ?: return@mapNotNull null
                val relative = file.path
                    ?: LibraryName.parse(library.name)?.copy(classifier = classifier)?.basePath
                    ?: return@mapNotNull null
                NativeArchive(File(context.librariesDir, relative), library.extract?.exclude.orEmpty())
            }

    /** natives 目录：放在游戏根目录的 natives/<版本 id>，多实例互不干扰。 */
    fun nativesDirFor(gameDir: File, versionName: String): File = File(File(gameDir, "natives"), versionName)

    /** 从 json 里取资源索引名；两个字段都可能缺。 */
    fun assetIndexName(version: VersionJson): String =
        version.assetIndex?.id?.takeIf { it.isNotBlank() } ?: version.assets
}
