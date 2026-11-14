package io.github.huang404yan.platypus.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchKeywordsTest {

    @Test
    fun `中文查询会被认出来`() {
        assertTrue(SearchKeywords.looksCjk("钠"))
        assertTrue(SearchKeywords.looksCjk("机械动力"))
        // 带标点也算纯中文：用户经常会连着感叹号一起打
        assertTrue(SearchKeywords.looksCjk("光影？"))
    }

    @Test
    fun `中英混写不当作中文查询`() {
        // 已经写了英文就直接搜，不能再弹「请用英文」的提示
        assertFalse(SearchKeywords.looksCjk("sodium 钠"))
        assertFalse(SearchKeywords.looksCjk("sodium"))
        assertFalse(SearchKeywords.looksCjk("1.20.1"))
        assertFalse(SearchKeywords.looksCjk(""))
        assertFalse(SearchKeywords.looksCjk("???"))
    }

    @Test
    fun `整串相等时排在最前面`() {
        val hit = SearchKeywords.translate("钠")
        assertEquals("sodium", hit.first())
    }

    @Test
    fun `查询里包含中文叫法时按长度降序给候选`() {
        val hit = SearchKeywords.translate("我想装机械动力")
        assertEquals("create", hit.first())
    }

    @Test
    fun `只打一半时也能给出候选`() {
        // 「机械」既不是任何一条的完整叫法，也不包含任何一条 —— 只能靠「叫法包含查询」
        val hit = SearchKeywords.translate("机械")
        assertTrue(hit.contains("create") || hit.contains("mekanism"))
    }

    @Test
    fun `英文查询不翻译`() {
        assertEquals(emptyList(), SearchKeywords.translate("sodium"))
        assertEquals(emptyList(), SearchKeywords.translate(""))
    }

    @Test
    fun `按页面类型过滤候选`() {
        // 「光影」在光影页能出 iris/complementary 一类，在资源包页不该出现
        val shader = SearchKeywords.translate("光影", SearchTerm.Kind.SHADER)
        assertTrue(shader.isNotEmpty())
        assertTrue(shader.none { it == "faithful" })
        val pack = SearchKeywords.translate("光影", SearchTerm.Kind.RESOURCE_PACK)
        assertTrue(pack.none { it == "iris" })
    }

    @Test
    fun `候选去重且不超过上限`() {
        val hit = SearchKeywords.translate("光影", SearchTerm.Kind.SHADER, limit = 2)
        assertEquals(hit.distinct(), hit)
        assertTrue(hit.size <= 2)
    }

    @Test
    fun `热门词按类型给且不混通用词`() {
        val shader = SearchKeywords.popular(SearchTerm.Kind.SHADER)
        assertTrue(shader.isNotEmpty())
        assertTrue(shader.all { it.kind == SearchTerm.Kind.SHADER })
        assertTrue(SearchKeywords.popular(SearchTerm.Kind.ANY).isEmpty())
    }

    @Test
    fun `词典里没有空词条也不重复中文`() {
        assertTrue(SearchKeywords.all.all { it.zh.isNotBlank() && it.en.isNotBlank() })
        val zh = SearchKeywords.all.map { it.zh }
        assertEquals(zh.size, zh.distinct().size)
    }

    @Test
    fun `提示文案提到 Modrinth 只认英文`() {
        assertTrue(SearchKeywords.modrinthNote.contains("Modrinth"))
        assertTrue(SearchKeywords.modrinthNote.contains("英文"))
    }
}
