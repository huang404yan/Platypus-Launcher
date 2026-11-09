package io.github.huang404yan.platypus.core.runtime

/**
 * 一个可用的 Java 运行时。
 *
 * @param path java 可执行文件（Windows 上是 java.exe）的绝对路径
 * @param majorVersion 主版本号：1.8 记作 8，17.0.10 记作 17，25.0.5.0.3 记作 25
 * @param vendor 发行方（java.vendor），探测不到为 null
 * @param arch 架构（os.arch，如 amd64 / x86 / aarch64）
 * @param bits 位数（32 或 64），探测不到为 null
 * @param managed 是否由启动器自己下载托管（ensure() 装出来的）
 */
data class JavaRuntime(
    val path: String,
    val majorVersion: Int,
    val vendor: String? = null,
    val arch: String? = null,
    val bits: Int? = null,
    val managed: Boolean = false,
)

interface JavaRuntimeService {
    suspend fun detect(): List<JavaRuntime>

    /** 缺失时下载；返回可用运行时。 */
    suspend fun ensure(major: Int, onProgress: (Float) -> Unit = {}): JavaRuntime
}

/**
 * 从一组已探测到的运行时里挑主版本正好是 [major] 的那个。
 *
 * 刻意不做「高版本顶替」：1.16.5 这类老版本（尤其带 Forge/Fabric mod 的）在 Java 16+
 * 上会直接崩，静默换一个更高的 Java 比明确报错难排查得多。找不到就返回 null，由调用方
 * 决定是提示用户装 JDK 还是走自动下载。
 */
fun List<JavaRuntime>.findExactMajor(major: Int): JavaRuntime? = firstOrNull { it.majorVersion == major }

/** 本机没有满足要求的 Java，且自动下载运行时还没实现时抛这个。 */
class JavaRuntimeUnavailableException(message: String) : RuntimeException(message)

/**
 * 游戏版本 → 所需 Java 主版本。
 *
 * 规则来自 Mojang 对各版本的运行时要求（最低要求，不是「最高能跑」）：
 * - 1.16.5 及更早（含 1.12.2、1.7.10）→ Java 8
 * - 1.17.x → Java 16
 * - 1.18 – 1.20.4 → Java 17
 * - 1.20.5+ 与 1.21+ → Java 21
 *
 * 认不出版本号时：快照（形如 26w01a）按最新要求 21；其它（a1.2.6、b1.7.3 之类远古 id）按 8。
 */
object GameJavaRules {
    /** 快照按最新要求处理。 */
    const val LATEST_REQUIRED_MAJOR = 21

    fun requiredMajor(gameVersion: String): Int {
        val trimmed = gameVersion.trim()
        val match = GameVersionRegex.find(trimmed)
            ?: return if (SnapshotRegex.matches(trimmed)) LATEST_REQUIRED_MAJOR else 8
        val minor = match.groupValues[2].toIntOrNull() ?: return LATEST_REQUIRED_MAJOR
        val patch = match.groupValues[3].toIntOrNull() ?: 0
        return when {
            minor > 20 -> 21
            minor == 20 && patch >= 5 -> 21
            minor >= 18 -> 17
            minor == 17 -> 16
            else -> 8
        }
    }

    /** 1.20.1、1.21、1.16.5-pre1 这类带 1. 前缀的版本号。 */
    private val GameVersionRegex = Regex("""^(1)\.(\d+)(?:\.(\d+))?""")

    /** 26w01a 这样的快照 id。 */
    private val SnapshotRegex = Regex("""^\d{2}w\d{2}[a-z]$""", RegexOption.IGNORE_CASE)
}
