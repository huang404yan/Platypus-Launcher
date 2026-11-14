package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.theme.LauncherTheme
import io.github.huang404yan.platypus.core.theme.paletteOf

/**
 * 把 [LauncherTheme] 的色值套到 Material 3 上。
 *
 * 界面里所有颜色都走 `MaterialTheme.colorScheme.xxx`（全仓库 250 多次引用），
 * 所以换主题只要换这一个 ColorScheme，没有一处页面代码需要知道主题的存在。
 *
 * 没在这里写死的角色（inverseSurface、scrim、surfaceTint 之类）用 M3 的默认值：
 * 它们要么是遮罩、要么是从主色推出来的，跟着 [darkColorScheme] / [lightColorScheme] 走就够了。
 */
@Composable
fun PlatypusTheme(theme: LauncherTheme, content: @Composable () -> Unit) {
    // AUTO 在这里兜底解析：调用方多半已经把设置读出来解析过了，但从没打开过设置页
    // 的用户拿到的就是 AUTO，不能因为它没解析过就画成一套别人没选的色。
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
