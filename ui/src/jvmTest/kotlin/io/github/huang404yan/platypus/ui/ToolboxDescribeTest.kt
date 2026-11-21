package io.github.huang404yan.platypus.ui

import io.github.huang404yan.platypus.core.instance.JunkCleanOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolboxDescribeTest {

    private fun outcome(deleted: Int, freed: Long, failed: Int) =
        JunkCleanOutcome(
            deletedFiles = deleted,
            freedBytes = freed,
            failedFiles = failed,
            failures = if (failed == 0) emptyList() else List(failed) { "C:/game/logs/latest.log" },
        )

    @Test
    fun `全清了就说清了几个释放多少`() {
        val text = describeClean(outcome(deleted = 12, freed = 2_048, failed = 0))
        assertTrue(text.contains("12"), text)
        assertTrue(text.contains("2.0 KB"), text)
        assertTrue(!text.contains("没删成"), text)
    }

    @Test
    fun `一个没删成要提示先关游戏`() {
        val text = describeClean(outcome(deleted = 0, freed = 0, failed = 3))
        assertTrue(text.contains("一个都没删成"), text)
        assertTrue(text.contains("3"), text)
        assertTrue(text.contains("先把游戏关掉"), text)
    }

    @Test
    fun `没有垃圾时不说删了零个`() {
        val text = describeClean(outcome(deleted = 0, freed = 0, failed = 0))
        assertEquals("没有可清理的垃圾。", text)
    }

    @Test
    fun `删了一部分要把两边的数都报出来`() {
        val text = describeClean(outcome(deleted = 9, freed = 1_536, failed = 2))
        assertTrue(text.contains("已清理 9 个"), text)
        assertTrue(text.contains("1.5 KB"), text)
        assertTrue(text.contains("还有 2 个"), text)
    }

    @Test
    fun `结果里不会出现英文单位`() {
        val text = describeClean(outcome(deleted = 1, freed = 5L * 1024 * 1024, failed = 0))
        assertTrue(text.contains("MB"), text)
        assertTrue(!text.contains("bytes"), text)
    }
}
