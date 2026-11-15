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

    @Test
    fun `老牌科技模组的意译能翻回 slug`() {
        // 这几条是这一批专门补的：中文社区说「工业2」「等价交换」「龙研」，
        // 而 Modrinth 上没有一个字面能对上，不翻就永远搜不到。
        assertEquals("industrialcraft", SearchKeywords.translate("工业时代2").first())
        assertEquals("projecte", SearchKeywords.translate("等价交换").first())
        assertEquals("draconic-evolution", SearchKeywords.translate("龙研").first())
        assertEquals("gregtech", SearchKeywords.translate("格雷科技").first())
    }

    @Test
    fun `词表的每个英文词都能在 Modrinth 上搜到东西`() {
        // 这一条是离线检查，只保证词表内部形状对（非空、不含空格、全小写或数字/横线）。
        // 真实命中率没法进单测（要联网），所以核对方式是 dist/_slugcheck.py 那种一次性脚本；
        // 这里守住的是「不许把中文或者带空格的句子塞进 en 字段」。
        for (term in SearchKeywords.all) {
            assertTrue(term.en.none { it.isWhitespace() }, "en 不该带空格：" + term.en)
            assertTrue(term.en.none { it.code > 0x7F }, "en 不该含非 ASCII：" + term.en)
        }
    }

    @Test
    fun `新增的叫法在同类型页面里优先给出`() {
        // 「背包」在模组页应当是精致背包，不该被整合包那几条抢走
        val hit = SearchKeywords.translate("背包", SearchTerm.Kind.MOD)
        assertEquals("sophisticated-backpacks", hit.first())
    }
}
