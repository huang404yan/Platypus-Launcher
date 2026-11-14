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

    /**
     * 带「版本 json 里的 javaVersion.component」的版本。
     *
     * component（java-runtime-gamma 之类）是 Mojang 的权威标识；主版本号只是它的投影，
     * 而且同一个主版本在 Mojang 的清单里可能对应多个组件（beta 与 gamma 实测都是 17）。
     * 默认实现忽略 component，退回按主版本号查找，方便测试替身。
     */
    suspend fun ensureRuntime(
        major: Int,
        component: String?,
        onProgress: (Float) -> Unit = {},
    ): JavaRuntime = ensure(major, onProgress)

    /**
     * 官方清单里这台机器能装的托管运行时，给「下载 Java」那张表用。
     *
     * 与 [ensure] 的区别是「不一定有现成的」：这里列的是「能下什么」，
     * 每一项自带已装标记，界面照着画一张表即可。不支持的后端（安卓端暂时）返回空表。
     */
    suspend fun catalog(): List<JavaRuntimeOffer> = emptyList()

    /**
     * 强制下载一份托管运行时，**不先看本机有没有**。
     *
     * [ensure] 的语义是「能用就行」：本机已经有 Java 21 就直接返回那个，一个字节都不下。
     * 但用户点「下载 Java 21」时想要的是一份启动器自己管的、删得掉的运行时，
     * 所以这里绕开探测，直接走下载。默认实现退回 [ensureRuntime] 方便测试替身。
     */
    suspend fun download(
        major: Int,
        component: String?,
        onProgress: (Float) -> Unit = {},
    ): JavaRuntime = ensureRuntime(major, component, onProgress)
}

/**
 * 官方清单里的一个可下载运行时。
 *
 * @param component Mojang 的组件 id（java-runtime-gamma 之类），下载时原样传回去
 * @param majorVersion 主版本号，界面显示「Java 21」用它
 * @param versionName 官方版本名（21.0.7 / 8u51-cacert462b08），比主版本号更细
 * @param manifestSize 该组件清单文件的大小（不是下下来的体积），只用来排序与判断有没有数据
 * @param installed 这个组件在本地是否已经有一份能用的 java 可执行文件
 * @param installedPath 已装时 java 的绝对路径
 */
data class JavaRuntimeOffer(
    val component: String,
    val majorVersion: Int,
    val versionName: String,
    val manifestSize: Long = -1L,
    val installed: Boolean = false,
    val installedPath: String? = null,
)

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
 * - 26.1 起的年份式版本号（26.1 / 26.3 / 26.4-snapshot-8）→ Java 25
 *
 * 年份式那一支是 2026-11-14 补的：Mojang 从 26.1 起把版本号从 "1.21.11" 改成 "26.1"，没有
 * "1." 前缀，原来会落到 else → Java 8。实测三个版本 json 的 javaVersion 都是
 * {component: java-runtime-epsilon, majorVersion: 25}：
 * - 26.1  → https://piston-meta.mojang.com/v1/packages/389f92510868468e073b7a12f19e70a7bf8684cd/26.1.json
 * - 26.3  → .../9f67e10ed013deeaad20ac3c800652765d493408/26.3.json
 * - 26.4-snapshot-8 → .../46f6dc6a5cefe084af0ce2ecf7f433c1222d4dd1/26.4-snapshot-8.json
 * 而 1.21.11 仍是 java-runtime-delta / 21，所以分界线正好在 26。
 *
 * 周号快照（13w16a、21w19a、24w14a、26w14a 这种）按年份分段，边界来自 2026-11-14 把
 * version_manifest_v2 里 744 个快照的 javaVersion.majorVersion 全拉下来统计的跃迁点：
 * - 13w16a – 21w18a → 8
 * - 21w19a – 22w02a → 16
 * - 22w03a – 24w13a → 17
 * - 24w14a – 25w46a → 21
 * - 26w14a 起 → 25
 * 只有 24w14potato（愚人节快照，实际是 1.20.4 那一代）是例外，官方 json 写 17。
 *
 * 已知偏差一个：1.18-pre1 官方是 16（到 pre2 才抬 17），但 1.x 那一支只按小版本判，
 * 会把整个 1.18 系列都算成 17。744 个快照逐个对照只有这 1 个对不上，且官方 json
 * 都带 javaVersion 字段，正常不会落到这个兜底函数。
 * 实在认不出（a1.2.6、b1.7.3、c0.30_01c 之类远古 id）按 8。
 *
 * 这个函数只是**兜底**：真正权威的是版本 json 里的 javaVersion.majorVersion，
 * 一键装那条路（OneClickInstaller）优先用它，只有版本文档缺该字段时才会落到这里。
 */
object GameJavaRules {
    /** 当前最新的 Java 要求（26.x 起的年份式版本）。 */
    const val LATEST_REQUIRED_MAJOR = 25

    /** 认不出版本号时最后的兜底：周号快照按 21（24w14a 那一段就是 21）。 */
    private const val WEEK_SNAPSHOT_MAJOR = 21

    /** 愚人节快照 24w14potato 的周号；它的 Java 要求与同周的 24w14a 不同。 */
    private const val POTATO_WEEK = 2414

    fun requiredMajor(gameVersion: String): Int {
        val trimmed = gameVersion.trim()
        // 26.1 / 26.3 / 26.4-snapshot-8 这类年份式 id：取前两位当年份
        val year = yearOf(trimmed)
        if (year != null && year >= 26) return LATEST_REQUIRED_MAJOR

        val match = GameVersionRegex.find(trimmed)
        if (match != null) {
            val minor = match.groupValues[2].toIntOrNull() ?: return WEEK_SNAPSHOT_MAJOR
            val patch = match.groupValues[3].toIntOrNull() ?: 0
            return when {
                minor > 20 -> 21
                minor == 20 && patch >= 5 -> 21
                minor >= 18 -> 17
                minor == 17 -> 16
                else -> 8
            }
        }

        val week = weekOf(trimmed) ?: return 8
        // 24w14potato 是愚人节快照，官方 json 的 javaVersion 是 17（1.20.4 那一代），
        // 但它的周号与 24w14a（21）相同，不单独判就会错一档。
        if (week == POTATO_WEEK && trimmed.lowercase().endsWith("potato")) return 17
        return when {
            week < 2119 -> 8
            week < 2203 -> 16
            week < 2414 -> 17
            week < 2614 -> 21
            else -> 25
        }
    }

    /** 1.20.1、1.21、1.16.5-pre1 这类带 1. 前缀的版本号。 */
    private val GameVersionRegex = Regex("""^(1)\.(\d+)(?:\.(\d+))?""")

    /**
     * 周号快照开头的「年份 + 周」：13w16a → 2013 年第 16 周。
     *
     * 拼成 YYWW 这样的四位整数方便比大小（21w19a → 2119），比拆成两个字段更省事。
     * 后缀放宽到任意字符，是为了让 23w13a_or_b、24w14potato 这类特殊 id 也能认出来。
     */
    private val WeekVersionRegex = Regex("""^(\d{2})w(\d{2})""", RegexOption.IGNORE_CASE)

    private fun weekOf(id: String): Int? {
        val g = WeekVersionRegex.find(id) ?: return null
        val year = g.groupValues[1].toIntOrNull() ?: return null
        val week = g.groupValues[2].toIntOrNull() ?: return null
        return year * 100 + week
    }

    /**
     * 年份式版本号（26.1 / 26.3 / 26.4-snapshot-8）开头的年份。
     *
     * 只认「两位数字 + 点 + 数字」这种开头，所以：
     * - 1.20.1 不会被误判（点前面只有一位）；
     * - 26w01a 那种周号快照也不会（没有点）。
     */
    private val YearVersionRegex = Regex("""^(\d{2})\.\d""")

    private fun yearOf(id: String): Int? =
        YearVersionRegex.find(id)?.groupValues?.get(1)?.toIntOrNull()
}
