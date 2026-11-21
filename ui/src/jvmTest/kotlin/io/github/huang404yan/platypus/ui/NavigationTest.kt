package io.github.huang404yan.platypus.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 导航结构的检查。
 *
 * 2026-11-15 改成 PCL 那样的「顶部横排五项 + 左侧一列二级」：
 * 启动 / 下载 / 联机 / 设置 / 更多。实例与版本设置不再是顶部项，
 * 它们是压栈的副页面（[Overlay]），所以这里也顺带钉住「哪几页没有二级项」。
 */
class NavigationTest {

    @Test
    fun `顶部只有五项，顺序是启动下载联机设置更多`() {
        assertEquals(5, Page.entries.size, "顶部项一多就又回到竖排侧栏那种「什么都摆上来」")
        assertEquals(
            listOf("启动", "下载", "联机", "设置", "更多"),
            Page.entries.map { it.label },
        )
    }

    @Test
    fun `实例与版本设置不在顶部，是副页面`() {
        // 玩家心里只有五件事；实例是「挑一个版本」这个动作，进去回来即可。
        assertTrue(Page.entries.none { it.name.contains("INSTANCE") })
        assertTrue(Page.entries.none { it.name.contains("ABOUT") })
        assertTrue(Page.entries.none { it.name.contains("HELP") })
        // 副页面用一个密封接口收口，这里只钉两个实现都能构造出来
        val select: Overlay = Overlay.InstanceSelect
        val setup: Overlay = Overlay.InstanceSetup("1.20.1")
        assertTrue(select is Overlay.InstanceSelect)
        assertTrue(setup is Overlay.InstanceSetup)
    }

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
        assertEquals(SETTINGS_TAB_LAUNCH, defaultSubTab(Page.SETTINGS))
        assertEquals(MORE_TAB_HELP, defaultSubTab(Page.MORE))
    }

    @Test
    fun `启动与联机没有二级项`() {
        // 启动页左栏是身份 + 内存 + 版本，联机页自己用两栏切换，都不需要再挂一列。
        assertEquals("", defaultSubTab(Page.LAUNCH))
        assertEquals("", defaultSubTab(Page.LINK))
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
    fun `设置里没有独立的 Java 页，Java 跟在启动卡后面`() {
        val subs = PAGE_SUB_TABS.getValue(Page.SETTINGS)
        assertTrue(
            subs.none { it.id == SETTINGS_TAB_JAVA },
            "「这个版本用哪个 Java」与「本机装了哪些 Java」是同一件事，拆两页反而要来回切",
        )
        assertEquals(SETTINGS_TAB_LAUNCH, subs.first().id)
        assertEquals("启动", subs.first().label)
    }

    @Test
    fun `设置里有一项账号`() {
        val subs = PAGE_SUB_TABS.getValue(Page.SETTINGS)
        assertEquals(SETTINGS_TAB_ACCOUNT, subs[1].id, "账号紧跟「启动」：它决定「以谁的身份进游戏」")
        assertEquals("账号", subs[1].label)
        // 账号页没有远端清单可刷新，登录状态由页面自己重读
        assertFalse(subs[1].refreshable)
    }

    @Test
    fun `启动页的清单来自远端，所以可以刷新`() {
        val sub = PAGE_SUB_TABS.getValue(Page.SETTINGS).first { it.id == SETTINGS_TAB_LAUNCH }
        assertTrue(sub.refreshable)
    }

    @Test
    fun `更多下面是帮助关于与诊断和百宝箱`() {
        val subs = PAGE_SUB_TABS.getValue(Page.MORE)
        assertEquals(
            listOf(MORE_TAB_HELP, MORE_TAB_ABOUT, MORE_TAB_DIAG, MORE_TAB_TOOLBOX),
            subs.map { it.id },
        )
        assertEquals(listOf("帮助", "关于与鸣谢", "诊断", "百宝箱"), subs.map { it.label })
    }

    @Test
    fun `界面和其他两页仍然不带刷新按钮`() {
        val settings = PAGE_SUB_TABS.getValue(Page.SETTINGS)
        for (id in listOf(SETTINGS_TAB_ACCOUNT, SETTINGS_TAB_UI, SETTINGS_TAB_OTHER)) {
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
