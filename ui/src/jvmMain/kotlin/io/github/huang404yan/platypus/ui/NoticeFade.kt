package io.github.huang404yan.platypus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 一行提示出现 / 消失时的过渡。
 *
 * 为什么不直接用 AnimatedVisibility 包住原来的 Text：提示清空的那一刻文字已经没了，
 * 退出动画这几帧画出来的是空行，看起来就是「闪一下消失」。这里自己记一份最后一次的原文，
 * 让它在淡出期间还画得出来。
 *
 * 用法是把原来那行 if 换成：
 * ```
 * NoticeFade(notice) { shown -> Text(shown, ...) }
 * ```
 * 传空串等于不显示，不需要外面再判一次 isNotBlank。
 */
@Composable
internal fun NoticeFade(notice: String, content: @Composable (String) -> Unit) {
    var last by remember { mutableStateOf(notice) }
    LaunchedEffect(notice) { if (notice.isNotBlank()) last = notice }
    AnimatedVisibility(
        visible = notice.isNotBlank(),
        enter = fadeIn(tween(motionMillis(Motion.BASE))),
        exit = fadeOut(tween(motionMillis(Motion.FAST))),
    ) {
        content(last)
    }
}
