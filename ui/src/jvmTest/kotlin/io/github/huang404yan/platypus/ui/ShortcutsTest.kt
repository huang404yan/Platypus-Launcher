package io.github.huang404yan.platypus.ui

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortcutsTest {

    private fun press(
        keyCode: Int,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        overlayOpen: Boolean = false,
    ) = intentFor(keyCode, ctrl, shift, alt, overlayOpen)

    @Test
    fun ctrlDigitsMapToTheFiveTabsInOrder() {
        // 顺序必须与顶栏一致：数字跳错页比没有快捷键更让人恼火。
        assertEquals(LauncherIntent.GoPage(Page.LAUNCH), press(KeyEvent.VK_1, ctrl = true))
        assertEquals(LauncherIntent.GoPage(Page.DOWNLOAD), press(KeyEvent.VK_2, ctrl = true))
        assertEquals(LauncherIntent.GoPage(Page.LINK), press(KeyEvent.VK_3, ctrl = true))
        assertEquals(LauncherIntent.GoPage(Page.SETTINGS), press(KeyEvent.VK_4, ctrl = true))
        assertEquals(LauncherIntent.GoPage(Page.MORE), press(KeyEvent.VK_5, ctrl = true))
    }

    @Test
    fun bareDigitsAreNotOurs() {
        // 不带 Ctrl 的数字要能打进搜索框与端口输入框，一个字都不能吞。
        for (code in listOf(KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_5)) {
            assertNull(press(code), "裸数字键不该被抢")
        }
    }

    @Test
    fun extraModifiersOptOut() {
        // Ctrl+Shift+1 在别的软件里往往是别的事；放宽匹配只会把按键从别人手里抢过来。
        assertNull(press(KeyEvent.VK_1, ctrl = true, shift = true))
        assertNull(press(KeyEvent.VK_1, ctrl = true, alt = true))
    }

    @Test
    fun digitsOutsideOneToFiveAreIgnored() {
        assertNull(press(KeyEvent.VK_0, ctrl = true))
        assertNull(press(KeyEvent.VK_6, ctrl = true))
        assertNull(press(KeyEvent.VK_Q, ctrl = true))
    }

    @Test
    fun escapeClosesOverlayOnlyWhenThereIsOne() {
        assertEquals(LauncherIntent.CloseTop, press(KeyEvent.VK_ESCAPE, overlayOpen = true))
        // 没有副页面时必须放过去：下拉框、对话框都靠 Esc 关自己。
        assertNull(press(KeyEvent.VK_ESCAPE, overlayOpen = false))
    }

    @Test
    fun hintsMatchWhatTheKeysActuallyDo() {
        // 设置页那张表是从 SHORTCUT_HINTS 画的，表错了就是文档在骗人。
        assertEquals(Page.entries.size + 1, SHORTCUT_HINTS.size, "五个翻页加一条 Esc")
        Page.entries.forEachIndexed { index, page ->
            val hint = SHORTCUT_HINTS[index]
            assertEquals("Ctrl+" + (index + 1), hint.combo)
            assertEquals("去「" + page.label + "」", hint.what)
            assertEquals(LauncherIntent.GoPage(page), press(KeyEvent.VK_1 + index, ctrl = true))
        }
        assertEquals("Esc", SHORTCUT_HINTS.last().combo)
    }

    @Test
    fun escapeWithModifiersIsNotOurs() {
        assertNull(press(KeyEvent.VK_ESCAPE, ctrl = true, overlayOpen = true))
        assertNull(press(KeyEvent.VK_ESCAPE, shift = true, overlayOpen = true))
    }
}
