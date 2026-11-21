package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 卡片上那颗「钉住」的星。
 *
 * 与桌面端 ui/PinMark.kt 是同一颗星、同一套理由，但这是安卓那一份的独立实现：
 * ui 模块只有 jvmMain，桌面的 Composable 在手机上用不了。
 *
 * 为什么不写 ★ / ☆ 字符：字体回退不可控，换一台设备可能变成豆腐块，而这颗星是
 * 卡片上唯一表示「我钉过它」的东西，缺字形等于功能消失。空心用描边而不是淡色实心，
 * 淡色在亮色卡片上几乎看不见。
 *
 * 桌面上有 TooltipArea 说「收藏（排到最前面）」，手机没有悬停，改成给无障碍朗读
 * 一句同样的话 —— 屏幕上则靠星本身在实心与空心之间切换来表达。
 */
@Composable
internal fun PinStar(
    pinned: Boolean,
    enabled: Boolean = true,
    size: Dp = 24.dp,
    onClick: () -> Unit,
) {
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val color = when {
        !enabled -> idle.copy(alpha = 0.28f)
        pinned -> active
        else -> idle
    }
    val label = if (pinned) "取消收藏" else "收藏，排到最前面"
    Canvas(
        modifier = Modifier
            .size(size)
            // 点击热区比图形略大：18dp 的星在手机上是很难点中的目标，
            // 24dp 的框配上 0.46 的半径刚好够指尖。
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        drawStar(color = color, filled = pinned)
    }
}

/** 正五角星。外接半径 0.46 边长的原因见 [PinStar] 的注释。 */
private fun DrawScope.drawStar(color: Color, filled: Boolean) {
    val side = min(size.width, size.height)
    val outer = side * 0.46f
    // 标准正五角星的内半径是 0.382，这个尺寸下太细，看起来像一根针。
    val inner = outer * 0.44f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val path = Path()
    for (index in 0 until 10) {
        val radius = if (index % 2 == 0) outer else inner
        // 从正上方起画，星尖朝上；每 36 度一个点，十个点交替内外。
        val angle = (-90.0 + index * 36.0) * PI / 180.0
        val x = cx + (radius * cos(angle)).toFloat()
        val y = cy + (radius * sin(angle)).toFloat()
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    if (filled) {
        drawPath(path, color)
    } else {
        drawPath(path, color, style = Stroke(width = (side * 0.09f).coerceAtLeast(1.4f)))
    }
}
