package io.github.huang404yan.platypus.core.theme

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 配色的几条硬约束：默认值怎么挑、认不出来的字段怎么兜、色卡里的对比度够不够。
 *
 * 对比度那几条不是「看起来差不多」的断言 —— 用 WCAG 的相对亮度公式算，
 * 因为「橙色按钮上的字看不清」这种问题只有数字能提前拦住。
 */
class LauncherThemeTest {

    @Test
    fun 孵化版默认是特工蓝橙而正式版默认是自然原色() {
        assertEquals(LauncherTheme.PERRY, LauncherTheme.AUTO.resolve(isPreview = true))
        assertEquals(LauncherTheme.NATURAL, LauncherTheme.AUTO.resolve(isPreview = false))
    }

    @Test
    fun 手动选过的配色不再看版本号() {
        // 用户在孵化版里选了自然原色，就一直是自然原色；反过来也一样。
        assertEquals(LauncherTheme.NATURAL, LauncherTheme.NATURAL.resolve(isPreview = true))
        assertEquals(LauncherTheme.PERRY, LauncherTheme.PERRY.resolve(isPreview = false))
    }

    @Test
    fun 配色id能从设置文件里读回来() {
        LauncherTheme.entries.forEach { theme ->
            assertEquals(theme, launcherThemeOf(theme.id))
        }
    }

    @Test
    fun 设置里的配色字符串大小写与空白都不影响() {
        assertEquals(LauncherTheme.NATURAL, launcherThemeOf("  Natural  "))
        assertEquals(LauncherTheme.PERRY, launcherThemeOf("PERRY\n"))
    }

    @Test
    fun 认不出来的配色一律当跟随版本() {
        // 字段可能是手改的，也可能来自以后版本里已经删掉的主题名。
        assertEquals(LauncherTheme.AUTO, launcherThemeOf(""))
        assertEquals(LauncherTheme.AUTO, launcherThemeOf("solarized"))
    }

    @Test
    fun 只有自然原色是深色底() {
        assertTrue(LauncherTheme.NATURAL.isDark)
        assertFalse(LauncherTheme.PERRY.isDark)
        // AUTO 走到 isDark 说明调用方忘了先 resolve，给个安全的答案而不是崩。
        assertFalse(LauncherTheme.AUTO.isDark)
    }

    @Test
    fun 跟随版本取色时退回正式版那套() {
        assertEquals(paletteOf(LauncherTheme.NATURAL), paletteOf(LauncherTheme.AUTO))
    }

    @Test
    fun 两套配色确实不一样() {
        assertNotEquals(paletteOf(LauncherTheme.NATURAL).background, paletteOf(LauncherTheme.PERRY).background)
        assertNotEquals(paletteOf(LauncherTheme.NATURAL).primary, paletteOf(LauncherTheme.PERRY).primary)
    }

    @Test
    fun 每套配色的色值都是不透明的() {
        // 色卡里写的是 0xFFRRGGBB；少写一位会变成半透明，界面上看着像没生效。
        listOf(LauncherTheme.NATURAL, LauncherTheme.PERRY).forEach { theme ->
            allColorsOf(theme).forEach { value ->
                assertTrue(
                    value in 0xFF000000L..0xFFFFFFFFL,
                    theme.id + " 里有不透明的色值：" + value.toString(16),
                )
            }
        }
    }

    @Test
    fun 正文压在自己底色上够清楚() {
        listOf(LauncherTheme.NATURAL, LauncherTheme.PERRY).forEach { theme ->
            val colors = paletteOf(theme)
            assertTrue(
                contrast(colors.onSurface, colors.surface) >= 4.5,
                theme.id + " 的正文对比度只有 " + contrast(colors.onSurface, colors.surface),
            )
            assertTrue(
                contrast(colors.onSurfaceVariant, colors.surface) >= 4.5,
                theme.id + " 的次要文字对比度只有 " + contrast(colors.onSurfaceVariant, colors.surface),
            )
        }
    }

    @Test
    fun 强调色上的字也够清楚() {
        // 白字压 #FF8C00 只有 2.2:1，所以 onPrimary 用的是深色。
        listOf(LauncherTheme.NATURAL, LauncherTheme.PERRY).forEach { theme ->
            val colors = paletteOf(theme)
            assertTrue(
                contrast(colors.onPrimary, colors.primary) >= 4.5,
                theme.id + " 的主按钮文字对比度只有 " + contrast(colors.onPrimary, colors.primary),
            )
        }
    }

    @Test
    fun 卡片与底色能分出层次但不会太跳() {
        listOf(LauncherTheme.NATURAL, LauncherTheme.PERRY).forEach { theme ->
            val colors = paletteOf(theme)
            val ratio = contrast(colors.surfaceVariant, colors.background)
            // 1.05 以下基本看不出是两块，3 以上会像两个不相干的界面。
            assertTrue(ratio in 1.05..3.0, theme.id + " 的卡片与底色对比度是 " + ratio)
        }
    }

    // ---- 工具 ----

    private fun allColorsOf(theme: LauncherTheme): List<Long> {
        val c = paletteOf(theme)
        return listOf(
            c.background, c.surface, c.surfaceVariant, c.onBackground, c.onSurface, c.onSurfaceVariant,
            c.primary, c.onPrimary, c.primaryContainer, c.onPrimaryContainer,
            c.secondary, c.onSecondary, c.secondaryContainer, c.onSecondaryContainer,
            c.tertiary, c.onTertiary, c.tertiaryContainer, c.onTertiaryContainer,
            c.error, c.onError, c.errorContainer, c.onErrorContainer, c.outline, c.outlineVariant,
        )
    }

    /** WCAG 2.x 的对比度公式。两色顺序无关。 */
    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(color: Long): Double {
        fun channel(value: Long): Double {
            val c = (value and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
