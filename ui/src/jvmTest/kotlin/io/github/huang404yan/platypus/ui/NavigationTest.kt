package io.github.huang404yan.platypus.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationTest {

    @Test
    fun `下载页的二级项顺序与原版游戏在前`() {
        val subs = PAGE_SUB_TABS.getValue(Page.DOWNLOAD)
        assertEquals(DOWNLOAD_TAB_VANILLA, subs.first().id)
        assertTrue(subs.any { it.id == DOWNLOAD_TAB_MODS })
        assertTrue(subs.any { it.id == DOWNLOAD_TAB_DATAPACKS })
        assertTrue(subs.any { it.id == DOWNLOAD_TAB_SERVER })
    }

    @Test
    fun `每个二级项的 id 在同一个页面里不重复`() {
        PAGE_SUB_TABS.forEach { (page, subs) ->
            val ids = subs.map { it.id }
            assertEquals(ids.size, ids.distinct().size, page.name + " 的二级项 id 重复了")
        }
    }

    @Test
    fun `默认二级项是列表里的第一个`() {
        assertEquals(DOWNLOAD_TAB_VANILLA, defaultSubTab(Page.DOWNLOAD))
        assertEquals("launch", defaultSubTab(Page.SETTINGS))
    }

    @Test
    fun `没有二级项的页面默认值是空串`() {
        assertEquals("", defaultSubTab(Page.LAUNCH))
        assertEquals("", defaultSubTab(Page.ABOUT))
    }

    @Test
    fun `整合包页故意不带刷新按钮`() {
        val sub = PAGE_SUB_TABS.getValue(Page.DOWNLOAD).first { it.id == DOWNLOAD_TAB_MODPACK }
        // 整合包页没有本地清单可刷新，放一个点了没反应的按钮比不放更糟
        assertFalse(sub.refreshable)
    }

    @Test
    fun `服务端页也没有可刷新的清单`() {
        val sub = PAGE_SUB_TABS.getValue(Page.DOWNLOAD).first { it.id == DOWNLOAD_TAB_SERVER }
        assertFalse(sub.refreshable)
    }

    @Test
    fun `设置里有一项 Java`() {
        val subs = PAGE_SUB_TABS.getValue(Page.SETTINGS)
        assertEquals(SETTINGS_TAB_JAVA, subs[1].id, "Java 紧跟在「启动」后面：它决定「用什么跑」，不是「跑成什么样」")
        assertEquals("Java", subs[1].label)
    }

    @Test
    fun `Java 页可以刷新官方清单`() {
        val sub = PAGE_SUB_TABS.getValue(Page.SETTINGS).first { it.id == SETTINGS_TAB_JAVA }
        // 这一页的清单来自远端，用户重装 / 换目录后需要一个手动重读的入口
        assertTrue(sub.refreshable)
    }

    @Test
    fun `界面和其他两页仍然不带刷新按钮`() {
        val settings = PAGE_SUB_TABS.getValue(Page.SETTINGS)
        for (id in listOf(SETTINGS_TAB_LAUNCH, SETTINGS_TAB_UI, SETTINGS_TAB_OTHER)) {
            assertFalse(settings.first { it.id == id }.refreshable, id + " 没有远端清单可刷新")
        }
    }

    @Test
    fun `每个二级项都有中文名`() {
        PAGE_SUB_TABS.values.flatten().forEach {
            assertTrue(it.label.isNotBlank(), it.id + " 没有名字")
        }
    }
}
