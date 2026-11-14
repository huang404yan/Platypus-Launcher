package io.github.huang404yan.platypus.core.theme

/**
 * 启动器的配色方案。
 *
 * 两套，按「60% 背景 + 30% 模块 + 10% 强调」定的，不是随手挑的色：
 * - [NATURAL] 自然原色（深色）：深潭青底 + 泥土棕卡片 + 鸭嘴橙强调。正式版默认。
 * - [PERRY] 特工蓝橙（浅色）：灰白底 + 泰瑞蓝绿模块 + 亮橙强调。孵化版默认。
 *
 * 为什么只有两套、且都只取「色」：正经工具软件的配色差一档就够用，
 * 做成可选调色盘会让人纠结十分钟然后随便选一个，还拖慢所有页面的验收。
 *
 * [AUTO] 不是第三套颜色，是「还没选过」：按版本号决定用哪套 —— 孵化版走
 * 亮一点的蓝橙（一眼能和正式版区分开），正式版走护眼的深色。用户一旦手动
 * 选过，就记进设置文件，下次不再看版本号。
 *
 * 色值以 ARGB 打包成 [Long]，这样这一层不依赖 Compose 或 Android 的任何图形库，
 * 桌面端与安卓端各自映射到自己的 ColorScheme 即可。
 */
enum class LauncherTheme(val id: String, val label: String) {
    AUTO("auto", "跟随版本"),
    NATURAL("natural", "自然原色"),
    PERRY("perry", "特工蓝橙"),
    ;

    /**
     * 把 [AUTO] 解析成具体的一套。
     *
     * 只有 [AUTO] 会用到 [isPreview]；显式选过的那两套原样返回 —— 用户在
     * 孵化版里选了「自然原色」，就该一直是自然原色，不能因为版本号是 preview 又跳回去。
     */
    fun resolve(isPreview: Boolean): LauncherTheme = when (this) {
        AUTO -> if (isPreview) PERRY else NATURAL
        NATURAL, PERRY -> this
    }

    /** 这一套是不是深色底（决定 Material 3 用 darkColorScheme 还是 lightColorScheme 打底）。 */
    val isDark: Boolean
        get() = when (this) {
            NATURAL -> true
            PERRY -> false
            // AUTO 走到这里说明没先 resolve，按正式版默认值给个安全的答案。
            AUTO -> false
        }
}

/**
 * 从设置文件里的字符串还原枚举。
 *
 * 认不出来的一律当 [LauncherTheme.AUTO]：字段可能是手改的、也可能来自以后版本
 * 里已经不存在的主题名，退到「按版本自动」总比让整个程序起不来好。
 */
fun launcherThemeOf(raw: String): LauncherTheme {
    val id = raw.trim().lowercase()
    return LauncherTheme.entries.firstOrNull { it.id == id } ?: LauncherTheme.AUTO
}

/** 一套配色里我们真正用到的那些语义色。字段名与 Material 3 的 ColorScheme 对齐。 */
data class ThemeColors(
    val background: Long,
    val surface: Long,
    val surfaceVariant: Long,
    val onBackground: Long,
    val onSurface: Long,
    val onSurfaceVariant: Long,
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val error: Long,
    val onError: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,
    val outline: Long,
    val outlineVariant: Long,
)

/**
 * 自然原色：深潭青底、泥土棕卡片、鸭嘴橙强调。
 *
 * 文字用奶油白而不是纯白 —— 纯白在深色底上盯久了发刺眼，奶油白把对比度压到
 * 90% 左右，夜里打游戏更舒服。
 *
 * 橙色上的字用深棕而不是白：白字压在 #FF8C00 上对比度只有 2.2:1，达不到可读标准；
 * 深棕底反着来有 8:1。
 */
private val NATURAL_PALETTE = ThemeColors(
    background = 0xFF1A2B2C,
    surface = 0xFF213638,
    surfaceVariant = 0xFF3E2723,
    onBackground = 0xFFFDF5E6,
    onSurface = 0xFFFDF5E6,
    onSurfaceVariant = 0xFFC7B5A5,
    primary = 0xFFFF8C00,
    onPrimary = 0xFF2A1600,
    primaryContainer = 0xFF5C3A12,
    onPrimaryContainer = 0xFFFFDCBE,
    secondary = 0xFF7FB8B0,
    onSecondary = 0xFF10332F,
    secondaryContainer = 0xFF2E4A47,
    onSecondaryContainer = 0xFFCBE9E4,
    tertiary = 0xFFC9A227,
    onTertiary = 0xFF2E2400,
    tertiaryContainer = 0xFF4A3C13,
    onTertiaryContainer = 0xFFF0E0A8,
    error = 0xFFFFB4AB,
    onError = 0xFF690005,
    errorContainer = 0xFF93000A,
    onErrorContainer = 0xFFFFDAD6,
    outline = 0xFF9A8A7A,
    outlineVariant = 0xFF55443C,
)

/**
 * 特工蓝橙：灰白底、泰瑞蓝绿模块、亮橙强调。
 *
 * 侧栏与卡片用的是蓝绿兑淡下来的 surfaceVariant 而不是饱和的 #00A896 本身：
 * 30% 模块色的意思是「占三成面积的那一层」，一整条侧栏刷成饱和蓝绿会盖过橙色，
 * 10% 强调就不再是强调了。饱和蓝绿留在 secondary 上给次要按钮用。
 */
private val PERRY_PALETTE = ThemeColors(
    background = 0xFFF4F7F6,
    surface = 0xFFFFFFFF,
    surfaceVariant = 0xFFDDEFEC,
    onBackground = 0xFF2C3E50,
    onSurface = 0xFF2C3E50,
    onSurfaceVariant = 0xFF4A6B68,
    primary = 0xFFFF7F00,
    onPrimary = 0xFF3A1D00,
    primaryContainer = 0xFFFFDDBB,
    onPrimaryContainer = 0xFF2E1500,
    secondary = 0xFF00A896,
    onSecondary = 0xFF00332E,
    secondaryContainer = 0xFFB2E8E0,
    onSecondaryContainer = 0xFF00504A,
    tertiary = 0xFF4A6572,
    onTertiary = 0xFFFFFFFF,
    tertiaryContainer = 0xFFCFE6F0,
    onTertiaryContainer = 0xFF23343D,
    error = 0xFFB3261E,
    onError = 0xFFFFFFFF,
    errorContainer = 0xFFF9DEDC,
    onErrorContainer = 0xFF410E0B,
    outline = 0xFF6F8B88,
    outlineVariant = 0xFFB9D6D2,
)

/** 取一套配色的色值。传 [LauncherTheme.AUTO] 会拿到正式版那套（调用方应先用 [LauncherTheme.resolve]）。 */
fun paletteOf(theme: LauncherTheme): ThemeColors = when (theme) {
    // 常量名必须带 _PALETTE：裸 NATURAL 在 when 分支里会被解析成那个顶层 val 而不是枚举常量，
    // 报 "'when' expression must be exhaustive" 外加一串 "Incompatible types LauncherTheme and ThemeColors"。
    LauncherTheme.NATURAL -> NATURAL_PALETTE
    LauncherTheme.PERRY -> PERRY_PALETTE
    LauncherTheme.AUTO -> NATURAL_PALETTE
}
