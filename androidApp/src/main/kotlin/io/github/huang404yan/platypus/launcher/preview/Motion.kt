package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * 动效的三个时长，这一端只在这里定（桌面 ui/Motion.kt 是同一套数字）。
 *
 * 为什么只留三档：动效一旦按处各写一个数字，同一个「出现」在不同页有不同快慢，
 * 用户会以为是卡顿。三档够覆盖所有情形 —— 小事（颜色、按下）走 FAST，
 * 常规（换页、换二级项、列表增删）走 BASE，整页入栈走 SLOW。
 *
 * 手机上还有一个额外约束：低端机每帧的时间预算比桌面紧得多，
 * 所以这里不出现超过 300ms 的动画，长一点的那种「仪式感」在手机上会直接变成等待。
 */
internal object Motion {
    /** 按下、选中变色这类「跟着手指走」的变化。 */
    const val FAST = 120

    /** 换页、换二级项、列表增删。超过 200ms 就会觉得每一步都在等。 */
    const val BASE = 180

    /** 二级项整块换掉。它承载的是「换了一层」，稍慢一点才看得出方向。 */
    const val SLOW = 240

    /** 减速曲线：进场用它，起点快、收尾稳，看起来像「滑到位」而不是「弹出来」。 */
    val decelerate: Easing = LinearOutSlowInEasing

    /** 标准曲线：进出都合用的对称曲线。 */
    val standard: Easing = FastOutSlowInEasing
}

/**
 * 「少动一点」的开关。
 *
 * true 时所有时长压到 1ms：动画接口照用，只是瞬间到位。这样各处代码不必分支 ——
 * 分支一多，总有一处忘了判断，而那处偏偏是最惹眼的那个。
 *
 * 对手机来说这个开关比桌面更有用：省电模式、老机型、晕动症都会希望界面别晃。
 * 值与桌面共用 settings.json 里的 reduceMotion 字段，两边来回拷目录时读的是同一个。
 */
internal val LocalReduceMotion = compositionLocalOf { false }

/** 按当前开关折算后的时长：正常时是 [millis]，关掉动效时是 1ms（不是 0，0 会跳过动画回调）。 */
@Composable
internal fun motionMillis(millis: Int): Int = if (LocalReduceMotion.current) 1 else millis

/**
 * 各处动画统一用这个取 spec，别自己写 tween(数字)。
 *
 * 泛型是为了同时服务 animateFloatAsState / animateDpAsState / animateColorAsState /
 * animateIntOffsetAsState 这几种不同的值类型 —— 它们的 spec 只差不带类型的 TweenSpec<T>。
 */
@Composable
internal fun <T> motionTween(
    millis: Int = Motion.BASE,
    easing: Easing = Motion.standard,
): TweenSpec<T> = tween(durationMillis = motionMillis(millis), easing = easing)
