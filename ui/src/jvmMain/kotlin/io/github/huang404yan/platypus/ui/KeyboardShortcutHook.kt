package io.github.huang404yan.platypus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.KeyEventDispatcher

/**
 * 窗口级的按键入口。
 *
 * 为什么不用 Compose 的 onPreviewKeyEvent：那要挂在 Window 上，而「按 Ctrl+2 想去下载页」
 * 这件事在 App 这一层就已经决定了（页面状态在这里）。走 AWT 的全局分发器，App 自己就能装，
 * 宿主窗口那边一行都不用改；将来安卓端换成 BackHandler 之类的写法，这一层也不受影响。
 *
 * 分发器在 EDT 上被调用，和 Compose 的组合在同一线程，直接回调是安全的。
 *
 * [enabled] 传 false 时整条链路让路：对话框开着的时候，Esc 该关对话框而不是关副页面，
 * 数字键也不该在用户对着对话框做选择时把底下的页面换掉。
 */
@Composable
internal fun KeyboardShortcutHook(
    overlayOpen: Boolean,
    enabled: Boolean = true,
    onIntent: (LauncherIntent) -> Unit,
) {
    // 用 rememberUpdatedState 而不是把这两个值放进 DisposableEffect 的 key：
    // 放进 key 会导致每切一次页就注销重装一次分发器，白白抖一下键盘焦点。
    val currentOverlay by rememberUpdatedState(overlayOpen)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnIntent by rememberUpdatedState(onIntent)

    DisposableEffect(Unit) {
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            if (!currentEnabled) return@KeyEventDispatcher false
            val intent = intentFor(
                keyCode = event.keyCode,
                ctrlDown = event.isControlDown,
                shiftDown = event.isShiftDown,
                altDown = event.isAltDown,
                overlayOpen = currentOverlay,
            ) ?: return@KeyEventDispatcher false
            currentOnIntent(intent)
            // 吞掉：不吞的话 Ctrl+2 会继续传下去，落到某个输入框里变成奇怪的字符。
            true
        }
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        manager.addKeyEventDispatcher(dispatcher)
        onDispose { manager.removeKeyEventDispatcher(dispatcher) }
    }
}
