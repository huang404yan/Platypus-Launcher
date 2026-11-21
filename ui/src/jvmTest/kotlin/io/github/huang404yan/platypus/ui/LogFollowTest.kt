package io.github.huang404yan.platypus.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「日志要不要跟着最新一行滚」的判定。
 *
 * 为什么值得单独测：这一条错了的表现是「用户往上翻日志，一有新行就被拽回底部」，
 * 或者反过来「日志刷了但界面不动」。两种都在真机上一眼能看出来，
 * 却没有任何自动化手段能在 CI 里复现 —— 除非把判定本身抽成纯函数。
 */
class LogFollowTest {

    @Test
    fun `停在底部时新内容进来继续跟`() {
        // 新行追加会把 canScrollForward 由 false 变 true，但那不是用户在滚，
        // 只看 canScrollForward 会在这里误判成「用户翻上去了」，之后再也不跟。
        assertTrue(LogFollow.next(current = true, scrolling = false, wasScrolling = false, canScrollForward = true))
    }

    @Test
    fun `用户往上滚立刻停止跟随`() {
        assertFalse(LogFollow.next(current = true, scrolling = true, wasScrolling = false, canScrollForward = true))
    }

    @Test
    fun `滚动画停在上方时保持不跟`() {
        assertFalse(LogFollow.next(current = true, scrolling = false, wasScrolling = true, canScrollForward = true))
    }

    @Test
    fun `滚回底部后恢复跟随`() {
        // 滚动中且已贴底：先不定，等手松开
        val during = LogFollow.next(current = false, scrolling = true, wasScrolling = true, canScrollForward = false)
        assertFalse(during, "手动滚到底的过程中不该提前宣告恢复")
        assertTrue(LogFollow.next(current = during, scrolling = false, wasScrolling = true, canScrollForward = false))
    }

    @Test
    fun `内容不够一屏时一直是贴底`() {
        assertTrue(LogFollow.next(current = true, scrolling = false, wasScrolling = false, canScrollForward = false))
    }

    @Test
    fun `不跟随时新行不会把它拉回跟随`() {
        assertFalse(LogFollow.next(current = false, scrolling = false, wasScrolling = false, canScrollForward = true))
    }

    @Test
    fun `走一遍完整流程`() {
        // 起始：跟随中，内容还没溢出
        var following = true
        var wasScrolling = false
        fun step(scrolling: Boolean, canScrollForward: Boolean) {
            following = LogFollow.next(following, scrolling, wasScrolling, canScrollForward)
            wasScrolling = scrolling
        }

        // 日志刷满一屏：仍跟随
        step(scrolling = false, canScrollForward = true)
        assertTrue(following, "第一屏刷满后就不跟了")

        // 用户往上翻
        step(scrolling = true, canScrollForward = true)
        assertFalse(following, "往上翻之后还在跟，界面上表现出「一滚就被拽回去」")
        step(scrolling = false, canScrollForward = true)
        assertFalse(following)

        // 其间又来了几十行：不许把他拽走
        repeat(50) { step(scrolling = false, canScrollForward = true) }
        assertFalse(following, "新日志把用户从历史位置拽回了底部")

        // 用户自己滚回底部
        step(scrolling = true, canScrollForward = false)
        step(scrolling = false, canScrollForward = false)
        assertTrue(following, "滚回底部后没有恢复跟随，之后的新日志都不会自动出现")
    }
}
