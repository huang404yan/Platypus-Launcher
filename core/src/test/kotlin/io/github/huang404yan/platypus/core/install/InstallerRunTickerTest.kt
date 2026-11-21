package io.github.huang404yan.platypus.core.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 安装器日志节流的单测。
 *
 * 时间由一个可变变量冒充：真的 sleep 一秒跑测试既慢又不稳（CI 上偶发超时）。
 */
class InstallerRunTickerTest {

    /** 假时钟：测试自己决定「过了一秒」还是「只过了十毫秒」。 */
    private var now: Long = 1_000_000L

    private fun ticker(intervalMillis: Long = 1000L) = InstallerRunTicker(intervalMillis = intervalMillis) { now }

    @Test
    fun `第一行一定推出去`() {
        val t = ticker()
        val snap = assertNotNull(t.onLine("Copying 1 file"))
        assertEquals(1, snap.lines)
        assertEquals(0, snap.seconds)
        assertEquals("Copying 1 file", snap.lastLine)
    }

    @Test
    fun `同一秒里的行只推第一条`() {
        val t = ticker()
        assertNotNull(t.onLine("a"))
        now += 10L
        assertNull(t.onLine("b"))
        now += 10L
        assertNull(t.onLine("c"))
        // 条数在后台一直数着：下一次推出来时要能报出真实行数
        assertEquals(3, t.lines)
    }

    @Test
    fun `过了一秒就推，且只推这一秒的最后一行`() {
        val t = ticker()
        assertNotNull(t.onLine("first"))
        now += 100L
        for (i in 1..50) {
            now += 15L
            t.onLine("line " + i)
        }
        // 上面这一轮铺满了一秒多一点，中途应该有推送；取最后一次
        now += 1000L
        val snap = assertNotNull(t.onLine("settled"))
        assertEquals(52, snap.lines)
        assertEquals("settled", snap.lastLine)
    }

    @Test
    fun `秒数按从开始算起四舍五入掉小数`() {
        val t = ticker()
        assertNotNull(t.onLine("start"))
        now += 2500L
        val snap = assertNotNull(t.onLine("later"))
        assertEquals(2, snap.seconds)
    }

    @Test
    fun `摘要里同时有行数秒数和最后一行`() {
        val t = ticker()
        val snap = assertNotNull(t.onLine("Loading patches file"))
        val text = snap.summary
        assertTrue(text.contains("已跑 1 行"), text)
        assertTrue(text.contains("已用 0 秒"), text)
        assertTrue(text.endsWith("Loading patches file"), text)
    }

    @Test
    fun `最后一行太长时截断`() {
        val t = ticker()
        val long = "x".repeat(500)
        val snap = assertNotNull(t.onLine(long))
        val kept = snap.summary.substringAfterLast(" · ")
        assertEquals(InstallerRunTicker.Snapshot.LAST_LINE_CHARS, kept.length)
    }

    @Test
    fun `没有行原文时摘要只剩行数与秒数`() {
        val snap = InstallerRunTicker.Snapshot(lines = 29777, seconds = 116, lastLine = "")
        assertEquals("已跑 29777 行 · 已用 116 秒", snap.summary)
    }

    @Test
    fun `三秒里推出来的次数与秒数对得上`() {
        val t = ticker()
        var pushes = 0
        // 每秒 300 行、连跑三秒：三万行那种刷屏的缩小版
        repeat(3) {
            repeat(300) {
                now += 1000L / 300L
                if (t.onLine("spam") != null) pushes += 1
            }
            now += 1000L
        }
        assertEquals(900, t.lines)
        assertTrue(pushes <= 4, "推了 " + pushes + " 次，应当接近每秒一次")
    }
}
