package io.github.huang404yan.platypus.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 帮助页的结构检查。
 *
 * 这些断言看着琐碎，但它们拦的是手册最容易出的三类问题：
 * ① 章节目录与正文对不上（有一章、点进去是空白/退回第一章）；
 * ② 「去这里」跳到一个不存在的二级项（跳过去落在默认项上，用户还是找不到）；
 * ③ 某章正文空着（结构对了但没内容，比没这一章更误导）。
 *
 * 2026-11-15 起「帮助」不再是顶部一级项，它是「更多」下面的第一项；
 * 章节切换也从左侧栏改成了页内横排 chips。
 */
class HelpPageTest {

    @Test
    fun `每一章都有 id 而且不重复`() {
        val ids = HELP_CHAPTERS.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "章节目录里有重复的 id")
        ids.forEach { assertTrue(it.isNotBlank(), "有空 id") }
    }

    @Test
    fun `章节顺序就是页内 chips 的顺序`() {
        // 页面直接遍历 HELP_CHAPTERS 画 chips，所以顺序即定义；这里钉住入口是第一篇。
        assertEquals(HELP_TAB_START, HELP_CHAPTERS.first().id)
        assertEquals(HELP_CHAPTERS.size, HELP_CHAPTERS.map { it.id }.distinct().size)
    }

    @Test
    fun `按 id 取章节时拿得到对应的那一章`() {
        HELP_CHAPTERS.forEach { chapter ->
            assertEquals(chapter.id, helpChapterOf(chapter.id).id)
        }
    }

    @Test
    fun `id 认不出来时退回第一章而不是抛`() {
        // 章节 id 可能来自本地存下来的旧选择（用户升级前选过一章，之后被删了）
        assertEquals(HELP_CHAPTERS.first().id, helpChapterOf("不存在的章节").id)
        assertEquals(HELP_CHAPTERS.first().id, helpChapterOf("").id)
    }

    @Test
    fun `每一章都有标题导语和至少三个小节`() {
        HELP_CHAPTERS.forEach { chapter ->
            assertTrue(chapter.title.isNotBlank(), chapter.id + " 没有标题")
            assertTrue(chapter.intro.isNotBlank(), chapter.id + " 没有导语")
            assertTrue(chapter.sections.size >= 3, chapter.id + " 的小节太少：" + chapter.sections.size)
            chapter.sections.forEach { section ->
                assertTrue(section.heading.isNotBlank(), chapter.id + " 有一节没标题")
                assertTrue(section.lines.isNotEmpty(), chapter.id + " 的「" + section.heading + "」正文是空的")
                section.lines.forEach { line ->
                    assertTrue(line.isNotBlank(), chapter.id + " 的「" + section.heading + "」里有一条空行")
                }
            }
        }
    }

    @Test
    fun `跳转目标必须是真实存在的页面与二级项`() {
        HELP_CHAPTERS.forEach { chapter ->
            val target = chapter.target ?: return@forEach
            if (target is NavTarget.Tab) {
                assertTrue(target.page in Page.entries, chapter.id + " 跳到了一个不存在的页面")
                val sub = target.sub ?: return@forEach
                val ids = PAGE_SUB_TABS[target.page].orEmpty().map { it.id }
                assertTrue(
                    sub in ids,
                    chapter.id + " 想跳到「" + target.page.label + "」下的 " + sub + "，但那里只有：" + ids.joinToString(","),
                )
            }
        }
    }

    @Test
    fun `帮助页自己不能被某一章指过去`() {
        // 指回自己等于这个按钮点了没反应。
        HELP_CHAPTERS.forEach { chapter ->
            val target = chapter.target
            if (target is NavTarget.Tab) {
                assertTrue(
                    !(target.page == Page.MORE && target.sub == MORE_TAB_HELP),
                    chapter.id + " 指向了帮助页自己",
                )
            }
        }
    }

    @Test
    fun `帮助是「更多」下面的第一项`() {
        val subs = PAGE_SUB_TABS.getValue(Page.MORE)
        assertEquals(MORE_TAB_HELP, subs.first().id)
        assertEquals("帮助", subs.first().label)
        // 顶部五项里没有「帮助」自己
        assertTrue(Page.entries.none { it.name == "HELP" })
    }

    @Test
    fun `存档那一章指向版本选择副页面`() {
        // 存档不在某个顶部页里，它是「版本选择 → 版本设置 → 存档」这条路上的东西。
        val chapter = helpChapterOf(HELP_TAB_SAVES)
        assertEquals(NavTarget.InstanceSelect, chapter.target)
    }

    @Test
    fun `每章的去处都能说成一句话`() {
        HELP_CHAPTERS.forEach { chapter ->
            val target = chapter.target ?: return@forEach
            assertTrue(goLabelOf(target).isNotBlank(), chapter.id + " 的去处没有文案")
        }
    }

    @Test
    fun `章节覆盖了从进游戏到出问题的主要环节`() {
        val ids = HELP_CHAPTERS.map { it.id }.toSet()
        for (expected in listOf(
            HELP_TAB_START,
            HELP_TAB_INSTALL,
            HELP_TAB_ACCOUNT,
            HELP_TAB_LAUNCH,
            HELP_TAB_LINK,
            HELP_TAB_SERVER,
            HELP_TAB_SAVES,
            HELP_TAB_PROBLEM,
        )) {
            assertNotNull(ids.firstOrNull { it == expected }, "缺了 " + expected + " 这一章")
        }
    }
}
