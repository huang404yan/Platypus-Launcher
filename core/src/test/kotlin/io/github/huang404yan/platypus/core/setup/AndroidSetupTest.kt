package io.github.huang404yan.platypus.core.setup

import io.github.huang404yan.platypus.core.settings.LauncherSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSetupTest {

    @Test
    fun `没走过引导时提示要走`() {
        assertTrue(AndroidSetup.needed(LauncherSettings()))
    }

    @Test
    fun `走过一次之后不再提示`() {
        val settings = LauncherSettings(androidGuideAt = 1_700_000_000_000L)
        assertFalse(AndroidSetup.needed(settings))
    }

    @Test
    fun `写回的时间戳不能是零`() {
        assertEquals(1L, AndroidSetup.doneAt(0L))
        assertEquals(1L, AndroidSetup.doneAt(-5L))
        assertEquals(1_700_000_000_000L, AndroidSetup.doneAt(1_700_000_000_000L))
    }

    @Test
    fun `一件都没好时说的是第一次用`() {
        val progress = AndroidSetup.progress(emptyList())
        assertEquals(3, progress.items.size)
        assertEquals(0, progress.doneCount)
        assertFalse(progress.allReady)
        assertEquals(SetupItem.entries.toList(), progress.remaining)
        assertEquals("先进去看看", progress.primaryLabel)
        assertTrue(progress.headline.contains("第一次用"))
    }

    @Test
    fun `三件都好了才给开始使用`() {
        val progress = AndroidSetup.progress(SetupItem.entries)
        assertEquals(3, progress.doneCount)
        assertTrue(progress.allReady)
        assertEquals(emptyList(), progress.remaining)
        assertEquals("开始使用", progress.primaryLabel)
    }

    @Test
    fun `差一件时点名差的是哪一件`() {
        val progress = AndroidSetup.progress(listOf(SetupItem.JAVA, SetupItem.UPDATE))
        assertEquals(2, progress.doneCount)
        assertFalse(progress.allReady)
        assertEquals(listOf(SetupItem.GRAPHICS), progress.remaining)
        assertTrue(progress.headline.contains("图形后端"))
        assertEquals("先进去看看", progress.primaryLabel)
    }

    @Test
    fun `重复报的就绪项不会算成四件`() {
        val progress = AndroidSetup.progress(listOf(SetupItem.JAVA, SetupItem.JAVA, SetupItem.JAVA))
        assertEquals(3, progress.items.size)
        assertEquals(1, progress.doneCount)
        assertEquals(listOf(SetupItem.GRAPHICS, SetupItem.UPDATE), progress.remaining)
    }

    @Test
    fun `顺序固定是 Java 图形 更新`() {
        assertEquals(listOf("java", "graphics", "update"), SetupItem.entries.map { it.id })
    }
}
