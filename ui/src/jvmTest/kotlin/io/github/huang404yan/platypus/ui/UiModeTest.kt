package io.github.huang404yan.platypus.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 界面模式的检查。
 *
 * 这一层最要紧的一条是「简洁模式不丢功能」：凡是完整模式里能点到的二级项，
 * 简洁模式里也必须还在列表里（只是被收进「更多」）。收起与删除是两件事，
 * 删了就切不回来 —— 而用户切模式的动机往往只是「想少看几行」。
 */
class UiModeTest {

    @Test
    fun `默认档是简洁`() {
        // 2026-11-21 用户定的口径：默认简洁，为的是老机器第一屏出来得快。
        // 这一条钉住的是「默认值不许被随手改回去」——改它要连着改用户口径。
        assertEquals(UiMode.SIMPLE, UiMode.DEFAULT)
    }

    @Test
    fun `空串与认不出的值都退默认档`() {
        // 空串是从没选过（或者用户把这一格删了），落到默认档；
        // 认不出的值多半是以后版本写的，退回默认档也比照着乱猜强。
        assertEquals(UiMode.DEFAULT, UiMode.of(""))
        assertEquals(UiMode.DEFAULT, UiMode.of("  "))
        assertEquals(UiMode.DEFAULT, UiMode.of("simple2"))
        assertEquals(UiMode.DEFAULT, UiMode.of("中"))
    }

    @Test
    fun `两种写法都能读出来，大小写与空白不算区别`() {
        assertEquals(UiMode.SIMPLE, UiMode.of("simple"))
        assertEquals(UiMode.SIMPLE, UiMode.of(" SIMPLE "))
        assertEquals(UiMode.FULL, UiMode.of("Full"))
    }

    @Test
    fun `模式 id 能被自己读回来`() {
        UiMode.entries.forEach { assertEquals(it, UiMode.of(it.id)) }
    }

    @Test
    fun `简洁模式不丢任何一个二级项`() {
        Page.entries.forEach { page ->
            val full = UiPolicy.subTabs(page, UiMode.FULL).map { it.id }
            val simple = UiPolicy.subTabs(page, UiMode.SIMPLE).map { it.id }
            // 比集合不比顺序：简洁模式会把后几项挪到「更多」那一组下面，
            // 摊平之后顺序确实变了 —— 要钉住的是「一项都不能少」，不是「顺序不许动」。
            assertEquals(full.size, simple.size, page.name + " 在简洁模式下少项了")
            assertEquals(full.toSet(), simple.toSet(), page.name + " 的二级项对不上")
        }
    }

    @Test
    fun `完整模式只有一个不加标题的组`() {
        val groups = UiPolicy.subTabGroups(Page.SETTINGS, UiMode.FULL)
        assertEquals(1, groups.size)
        assertEquals(null, groups[0].label)
        assertEquals(PAGE_SUB_TABS.getValue(Page.SETTINGS).size, groups[0].tabs.size)
    }

    @Test
    fun `简洁模式把不常用的项收进更多，常用项仍在上面`() {
        val groups = UiPolicy.subTabGroups(Page.DOWNLOAD, UiMode.SIMPLE)
        assertEquals(2, groups.size)
        assertEquals(null, groups[0].label)
        assertEquals("更多", groups[1].label)
        // 原版与模组是「装个什么」的两条主路，必须留在上面
        assertEquals(listOf(DOWNLOAD_TAB_VANILLA, DOWNLOAD_TAB_MODS, DOWNLOAD_TAB_MODPACK, DOWNLOAD_TAB_RESOURCEPACKS), groups[0].tabs.map { it.id })
        assertEquals(listOf(DOWNLOAD_TAB_DATAPACKS, DOWNLOAD_TAB_SHADERS, DOWNLOAD_TAB_SERVER), groups[1].tabs.map { it.id })
    }

    @Test
    fun `设置页简洁模式把下载存档与其他收起来`() {
        val groups = UiPolicy.subTabGroups(Page.SETTINGS, UiMode.SIMPLE)
        assertEquals(listOf(SETTINGS_TAB_LAUNCH, SETTINGS_TAB_ACCOUNT, SETTINGS_TAB_UI), groups[0].tabs.map { it.id })
        assertEquals(listOf(SETTINGS_TAB_DOWNLOAD, SETTINGS_TAB_SAVES, SETTINGS_TAB_OTHER), groups[1].tabs.map { it.id })
    }

    @Test
    fun `启动联机更多这三页没有可收的项`() {
        // 启动与联机本来就没有二级项；更多下面三项都是「出了问题才去」，没有主次
        listOf(Page.LAUNCH, Page.LINK, Page.MORE).forEach { page ->
            val groups = UiPolicy.subTabGroups(page, UiMode.SIMPLE)
            assertEquals(1, groups.size, page.name + " 不该分组")
        }
    }

    @Test
    fun `收起来的判定只在简洁模式成立`() {
        assertTrue(UiPolicy.isNested(Page.DOWNLOAD, UiMode.SIMPLE, DOWNLOAD_TAB_SERVER))
        assertFalse(UiPolicy.isNested(Page.DOWNLOAD, UiMode.FULL, DOWNLOAD_TAB_SERVER))
        assertFalse(UiPolicy.isNested(Page.DOWNLOAD, UiMode.SIMPLE, DOWNLOAD_TAB_MODS))
    }

    @Test
    fun `简洁模式的版本设置少高级与导出两节，其余原样`() {
        val full = UiPolicy.setupSections(UiMode.FULL)
        val simple = UiPolicy.setupSections(UiMode.SIMPLE)
        assertEquals(SETUP_SECTIONS.size, full.size)
        assertEquals(full.size - 2, simple.size)
        assertTrue(simple.none { it.first == SETUP_ADV })
        assertTrue(simple.none { it.first == SETUP_EXPORT })
        assertEquals(full.filter { it.first != SETUP_ADV && it.first != SETUP_EXPORT }, simple)
    }

    @Test
    fun `简洁模式把导出收成总览里的一行按钮`() {
        assertTrue(UiPolicy.showsExportEntry(UiMode.SIMPLE))
        assertFalse(UiPolicy.showsExportEntry(UiMode.FULL))
    }

    @Test
    fun `简洁模式藏起游戏目录与 Java、内存细节`() {
        assertTrue(UiPolicy.showsGameDirAndJava(UiMode.FULL))
        assertFalse(UiPolicy.showsGameDirAndJava(UiMode.SIMPLE))
        assertTrue(UiPolicy.showsMemoryDetail(UiMode.FULL))
        assertFalse(UiPolicy.showsMemoryDetail(UiMode.SIMPLE))
    }

    @Test
    fun `模式选择本身永远显示`() {
        // 简洁模式里藏掉这个开关，用户就切不回去了
        assertTrue(UiPolicy.showsUiModeSwitch())
    }

    @Test
    fun `简洁档下动效一律关掉，不看那个勾选框`() {
        // 2026-11-21 用户口径：简洁模式的语义是「性能档」，不只是少显示几项。
        // 所以这里钉住的是单向覆盖：简洁档 + 没勾也是关，简洁档 + 勾了还是关。
        assertTrue(UiPolicy.effectiveReduceMotion(UiMode.SIMPLE, reduceMotion = false))
        assertTrue(UiPolicy.effectiveReduceMotion(UiMode.SIMPLE, reduceMotion = true))
    }

    @Test
    fun `完整档下动效只听那个勾选框`() {
        // 反过来不能被简洁档的规则牵连：完整档默认是开动效的，勾了才关。
        assertFalse(UiPolicy.effectiveReduceMotion(UiMode.FULL, reduceMotion = false))
        assertTrue(UiPolicy.effectiveReduceMotion(UiMode.FULL, reduceMotion = true))
    }
}
