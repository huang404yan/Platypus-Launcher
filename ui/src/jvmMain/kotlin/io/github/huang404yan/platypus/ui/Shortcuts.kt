package io.github.huang404yan.platypus.ui

import java.awt.event.KeyEvent

/**
 * 一次按键想干的事。
 *
 * 只描述「能做的事」，不认识具体是哪个键 —— 键位映射集中在 [intentFor] 里，
 * 想换键只改那一处，不用在界面代码里翻 KeyEvent 常量。
 */
internal sealed interface LauncherIntent {

    /** 切到某个一级页。 */
    data class GoPage(val page: Page) : LauncherIntent

    /** 关掉最上面那一层副页面（版本选择 / 版本设置）。 */
    data object CloseTop : LauncherIntent
}

/**
 * 把一次按键翻译成意图，翻不出来就返回 null（表示「这键不归我管，让别的控件处理」）。
 *
 * 两条口径：
 *
 * 1. Esc 只在自己有事可做的时候才吞。没有副页面时 Esc 必须放过去 ——
 *    下拉框、对话框都要用 Esc 关自己，抢了会让那些地方关不掉。
 * 2. 组合键一律要求不带 Shift / Alt。Ctrl+Shift+1 在别的软件里往往是别的事，
 *    而我们也确实没有那个功能；放宽匹配只会把按键从别人手里抢过来。
 *
 * 为什么用 AWT 的 KeyEvent 常量而不是 Compose 的 Key：这里的入口是 AWT 的
 * 键盘分发器（见 KeyboardShortcutHook），拿到的就是 AWT 事件；换算一遍反而多一层
 * 出错的可能。安卓端没有 AWT，所以这个文件在 jvmMain。
 */
internal fun intentFor(
    keyCode: Int,
    ctrlDown: Boolean,
    shiftDown: Boolean,
    altDown: Boolean,
    overlayOpen: Boolean,
): LauncherIntent? {
    if (keyCode == KeyEvent.VK_ESCAPE) {
        // 没副页面时返回 null：让 Esc 去关它该关的东西。
        val clean = !ctrlDown && !shiftDown && !altDown
        return if (clean && overlayOpen) LauncherIntent.CloseTop else null
    }
    if (!ctrlDown || shiftDown || altDown) return null
    // 数字键 1..5 正对着顶栏那五项的顺序。
    val page = when (keyCode) {
        KeyEvent.VK_1 -> Page.LAUNCH
        KeyEvent.VK_2 -> Page.DOWNLOAD
        KeyEvent.VK_3 -> Page.LINK
        KeyEvent.VK_4 -> Page.SETTINGS
        KeyEvent.VK_5 -> Page.MORE
        else -> null
    }
    return page?.let { LauncherIntent.GoPage(it) }
}

/**
 * 某个一级页的快捷键写法（Ctrl+1 … Ctrl+5）。
 *
 * 顶栏悬停提示与设置页那张表都从这里取，避免「改了一个地方、另一个地方还写着旧键位」。
 * 用 [Page.ordinal] 而不是另存一张映射：顺序就是顶栏顺序，加一页自动顺延。
 */
internal fun pageShortcut(page: Page): String = "Ctrl+" + (page.ordinal + 1)

/** 一条快捷键说明：组合键怎么读 + 它干什么。 */
internal data class ShortcutHint(val combo: String, val what: String)

/**
 * 设置页「界面」里那张快捷键表。
 *
 * 从 [Page.entries] 生成而不是手写五行：顶栏加一页时这张表跟着长，
 * 不会出现「表里还写着 Ctrl+5 是更多、实际顺序已经变了」这种谎。
 * 组合键的写法固定成 `Ctrl+数字`，与 [intentFor] 里那五个分支一一对应。
 */
internal val SHORTCUT_HINTS: List<ShortcutHint> = buildList {
    Page.entries.forEach { page ->
        add(ShortcutHint(pageShortcut(page), "去「" + page.label + "」"))
    }
    add(ShortcutHint("Esc", "关掉压在最上面的那层（版本选择 / 版本设置）"))
}
