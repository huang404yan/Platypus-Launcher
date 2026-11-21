package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「收藏 / 置顶」那颗星。
 *
 * 为什么自己画而不是写 ★ / ☆ 两个字符：字体回退不可控 —— 一台机器上凑巧有
 * Segoe UI Symbol，换一台可能就是豆腐块，而这颗星是卡片上唯一表示「我钉过它」
 * 的东西，缺字形等于功能消失。星形本身只是十条边，画出来比挑字体稳。
 *
 * 实心 = 已收藏，空心 = 未收藏。空心用描边而不是「更淡的实心」：淡色在浅色主题下
 * 几乎看不见，会让用户以为那一格是空白、不是按钮。
 *
 * @param pinned 当前是否已收藏。
 * @param enabled 为 false 时不可点，也不再画悬停提示（例如正在扫描 / 删除）。
 * @param size 边长，卡片头部用 18dp。
 * @param onClick 点一下切换收藏。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PinMark(
    pinned: Boolean,
    enabled: Boolean = true,
    size: Dp = 18.dp,
    onClick: () -> Unit,
) {
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val color = when {
        !enabled -> idle.copy(alpha = 0.28f)
        pinned -> active
        else -> idle
    }
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                tonalElevation = 0.dp,
            ) {
                Text(
                    if (pinned) "取消收藏" else "收藏（排到最前面）",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
        delayMillis = 520,
    ) {
        Canvas(
            Modifier
                .size(size)
                .clickable(enabled = enabled, onClick = onClick),
        ) {
            drawStar(color = color, filled = pinned)
        }
    }
}

/**
 * 在画布中央画一颗五角星。
 *
 * 内半径取外半径的 0.44：0.382 是标准正五角星，但在 18dp 这种尺寸下尖角会细到
 * 看不清，稍微加粗一点反而更像「星」而不是「爆炸形」。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStar(color: Color, filled: Boolean) {
    val side = this.size.minDimension
    if (side <= 0f) return
    val center = Offset(this.size.width / 2f, this.size.height / 2f)
    val outer = side * 0.46f
    val inner = outer * 0.44f
    val path = Path()
    repeat(10) { index ->
        val radius = if (index % 2 == 0) outer else inner
        val angle = (-90.0 + index * 36.0) * PI / 180.0
        val point = Offset(
            center.x + (radius * cos(angle)).toFloat(),
            center.y + (radius * sin(angle)).toFloat(),
        )
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    if (filled) {
        drawPath(path, color = color)
    } else {
        // 线宽跟着尺寸走：固定 1dp 在 18dp 的星上会把空心挤没，放大成 24dp 又太细。
        drawPath(path, color = color, style = Stroke(width = (side * 0.09f).coerceAtLeast(1f)))
    }
}
