package io.github.huang404yan.platypus.core.runtime

import java.io.File

/**
 * OpenJDK 运行时目录里那份 `release` 文件的解析。
 *
 * ## 为什么需要它
 *
 * 桌面端判断一个 java 是几版，靠的是「跑一次 `java -XshowSettings:properties -version`
 * 看它自己报什么」（见 [JavaProbeParser]）。安卓上这条路走不通：
 *
 * 1. 应用内只允许存在一个 JVM，起过一次就没法再拿它当命令行工具用；
 * 2. `bin/java` 是给 JNI 加载的 `libjvm.so` 配套的启动器，直接 exec 在
 *    Android 10 以上还会撞上「共享存储不可执行」。
 *
 * 但每份 OpenJDK 构建里都有一个 `release` 文件，内容是 `KEY="VALUE"` 形式的
 * 纯文本，JAVA_VERSION / OS_ARCH / IMPLEMENTOR 都在里面。读它不需要启动任何东西，
 * 也不依赖任何安卓 API，所以在 core 里就能算。
 *
 * ## 格式
 *
 * 每行 `KEY="VALUE"`，值两侧的引号是格式的一部分（OpenJDK 自己的 spec 如此），
 * 少数构建会写成不带引号。两种都认。
 */
object JavaReleaseFile {

    /** 文件名叫 release，就在 JRE 根目录下，和 bin/、lib/ 并排。 */
    const val FILE_NAME = "release"

    /** 一个 JRE 目录里 release 文件的路径。 */
    fun fileOf(javaHome: File): File = File(javaHome, FILE_NAME)

    /** 读并解析；文件不存在或读不动都当空表，不抛。 */
    fun read(javaHome: File): Map<String, String> {
        val file = fileOf(javaHome)
        if (!file.isFile) return emptyMap()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyMap()
        return parse(text)
    }

    /**
     * 解析 release 文本。
     *
     * 只认第一个等号之前是键、之后是值；值两端的双引号会去掉。
     * 空行与 `#` 开头的注释行跳过（OpenJDK 的 release 里没有注释，但手改过的会有）。
     */
    fun parse(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (key.isEmpty() || map.containsKey(key)) continue
            map[key] = unquote(line.substring(eq + 1).trim())
        }
        return map
    }

    /** 从 release 文件里认出主版本号，认不出返回 null。 */
    fun majorOf(release: Map<String, String>): Int? {
        val version = release["JAVA_VERSION"]?.takeIf { it.isNotBlank() } ?: return null
        return JavaProbeParser.parseMajor(version)
    }

    /** 厂商名，例如 "Eclipse Adoptium"、"Azul Systems, Inc."。 */
    fun vendorOf(release: Map<String, String>): String? =
        release["IMPLEMENTOR"]?.takeIf { it.isNotBlank() }
            ?: release["JAVA_VENDOR"]?.takeIf { it.isNotBlank() }

    /** 架构名，例如 aarch64 / arm / x86_64 / i386。 */
    fun archOf(release: Map<String, String>): String? =
        release["OS_ARCH"]?.takeIf { it.isNotBlank() }

    /** 版本全名，例如 17.0.9；界面上一行字用。 */
    fun versionOf(release: Map<String, String>): String? =
        release["JAVA_VERSION"]?.takeIf { it.isNotBlank() }

    /**
     * 架构名 → 位数。
     *
     * 复用 [JavaProbeParser.bits] 的判据（它已经把 x86/i686/arm 这些写法覆盖过一遍），
     * 只是这里没有 `sun.arch.data.model` 那一栏。
     */
    fun bitsOf(release: Map<String, String>): Int? =
        JavaProbeParser.bits(null, archOf(release))

    private fun unquote(value: String): String {
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1)
        }
        return value
    }
}
