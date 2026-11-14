package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
import io.github.huang404yan.platypus.core.theme.LauncherTheme
import io.github.huang404yan.platypus.core.theme.launcherThemeOf
import io.github.huang404yan.platypus.core.theme.paletteOf
import java.io.File

/**
 * 把 [LauncherTheme] 的色值套到 Material 3 上（桌面端 `ui/.../Theme.kt` 的同形副本）。
 *
 * 两端各留一份而不是塞进 core：core 是纯 JVM，打包成 Long 就已经是为了不依赖图形库，
 * 再往里塞 Compose 的 ColorScheme 就等于让 core 拖上 Compose。色值本身在 core 的
 * [io.github.huang404yan.platypus.core.theme.ThemeColors] 里，这里只做「Long 转 Color」。
 */
@Composable
internal fun PlatypusTheme(theme: LauncherTheme, content: @Composable () -> Unit) {
    // AUTO 在这里兜底解析：没打开过设置页的用户拿到的就是 AUTO，得按版本挑一套。
    val resolved = theme.resolve(Brand.isPreview)
    val palette = paletteOf(resolved)
    val scheme = if (resolved.isDark) {
        darkColorScheme(
            primary = Color(palette.primary),
            onPrimary = Color(palette.onPrimary),
            primaryContainer = Color(palette.primaryContainer),
            onPrimaryContainer = Color(palette.onPrimaryContainer),
            secondary = Color(palette.secondary),
            onSecondary = Color(palette.onSecondary),
            secondaryContainer = Color(palette.secondaryContainer),
            onSecondaryContainer = Color(palette.onSecondaryContainer),
            tertiary = Color(palette.tertiary),
            onTertiary = Color(palette.onTertiary),
            tertiaryContainer = Color(palette.tertiaryContainer),
            onTertiaryContainer = Color(palette.onTertiaryContainer),
            background = Color(palette.background),
            onBackground = Color(palette.onBackground),
            surface = Color(palette.surface),
            onSurface = Color(palette.onSurface),
            surfaceVariant = Color(palette.surfaceVariant),
            onSurfaceVariant = Color(palette.onSurfaceVariant),
            outline = Color(palette.outline),
            outlineVariant = Color(palette.outlineVariant),
            error = Color(palette.error),
            onError = Color(palette.onError),
            errorContainer = Color(palette.errorContainer),
            onErrorContainer = Color(palette.onErrorContainer),
        )
    } else {
        lightColorScheme(
            primary = Color(palette.primary),
            onPrimary = Color(palette.onPrimary),
            primaryContainer = Color(palette.primaryContainer),
            onPrimaryContainer = Color(palette.onPrimaryContainer),
            secondary = Color(palette.secondary),
            onSecondary = Color(palette.onSecondary),
            secondaryContainer = Color(palette.secondaryContainer),
            onSecondaryContainer = Color(palette.onSecondaryContainer),
            tertiary = Color(palette.tertiary),
            onTertiary = Color(palette.onTertiary),
            tertiaryContainer = Color(palette.tertiaryContainer),
            onTertiaryContainer = Color(palette.onTertiaryContainer),
            background = Color(palette.background),
            onBackground = Color(palette.onBackground),
            surface = Color(palette.surface),
            onSurface = Color(palette.onSurface),
            surfaceVariant = Color(palette.surfaceVariant),
            onSurfaceVariant = Color(palette.onSurfaceVariant),
            outline = Color(palette.outline),
            outlineVariant = Color(palette.outlineVariant),
            error = Color(palette.error),
            onError = Color(palette.onError),
            errorContainer = Color(palette.errorContainer),
            onErrorContainer = Color(palette.onErrorContainer),
        )
    }
    MaterialTheme(colorScheme = scheme) {
        // 最外层必须自己铺一层底色，否则窗口露出的是 Swing 的默认白底：
        // 深色那套的空白处会是刺眼的白，而正文颜色来自 LocalContentColor ——
        // 没有 Surface 提供它时 Compose 退回 Color.Black，于是「白底 + 黑字」
        // 和「显式指定成奶油白的标题」同时出现在一屏上，正是「刺眼又看不清」。
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

/**
 * 启动时读一次用户选的配色。
 *
 * 走两跳：先看默认游戏目录下的 settings.json，如果里面写了别的游戏目录，再去那儿读一次。
 * 安卓这边没有全局的「当前游戏目录」状态（每个页面自己算），而设置文件是跟着游戏目录走的，
 * 所以只能这样找。读不到、读坏了、字段认不出来，一律当 [LauncherTheme.AUTO]。
 */
internal suspend fun readThemePreference(): LauncherTheme {
    val root = runCatching { File(defaultGameRoot()) }.getOrNull() ?: return LauncherTheme.AUTO
    val store = JsonSettingsRepository(JsonSettingsRepository.defaultFile(root))
    val settings = runCatching { store.load() }.getOrNull() ?: return LauncherTheme.AUTO
    val actual = settings.defaultGameRoot.trim()
    if (actual.isNotEmpty() && actual != root.absolutePath) {
        val moved = JsonSettingsRepository(JsonSettingsRepository.defaultFile(File(actual)))
        runCatching { moved.load() }.getOrNull()?.let { return launcherThemeOf(it.theme) }
    }
    return launcherThemeOf(settings.theme)
}
