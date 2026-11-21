package io.github.huang404yan.platypus.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Notices] 是进程级的单例，测试之间要清干净，否则前一个用例留下的条目会把后一个的
 * 断言带偏 —— 这种串味在「按顺序」这类断言上最容易被误判成逻辑写错了。
 */
class NoticesTest {

    @AfterTest
    fun clean() {
        Notices.clear()
    }

    @Test
    fun pushKeepsOrderAndUniqueness() {
        val first = Notices.push("装好了：1.20.1", "用时 3 秒。")
        val second = Notices.push("装好了：1.20.1", "用时 3 秒。")
        // 文案一模一样也必须能分开：界面靠 id 记「哪条已经淡出过」。
        assertTrue(first.id != second.id, "两条相同的提示拿到了同一个 id")
        assertEquals(listOf(first.id, second.id), Notices.items.value.map { it.id })
    }

    @Test
    fun dismissRemovesOnlyTheGivenOne() {
        val a = Notices.push("a", "")
        val b = Notices.push("b", "")
        Notices.dismiss(a.id)
        assertEquals(listOf(b.id), Notices.items.value.map { it.id })
    }

    @Test
    fun dismissUnknownIdIsNoOp() {
        val a = Notices.push("a", "")
        Notices.dismiss(9_999L)
        assertEquals(listOf(a.id), Notices.items.value.map { it.id })
    }

    @Test
    fun gotoIsCarriedThrough() {
        // 装完的提示要能点进启动页；没有去处的（比如只是「保存成功」）不该带 goto。
        val jump = Notices.push("装好了", "", goto = Page.LAUNCH)
        val plain = Notices.push("保存成功", "")
        assertEquals(Page.LAUNCH, jump.goto)
        assertEquals(null, plain.goto)
    }

    @Test
    fun clearDropsEverything() {
        Notices.push("a", "")
        Notices.push("b", "")
        Notices.clear()
        assertTrue(Notices.items.value.isEmpty())
    }
}
