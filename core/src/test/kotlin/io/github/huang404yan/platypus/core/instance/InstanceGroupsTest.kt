package io.github.huang404yan.platypus.core.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstanceGroupsTest {

    private fun instance(
        id: String,
        group: String = "",
        registered: Boolean = true,
        pinned: Boolean = false,
        lastPlayedAt: Long = 0L,
    ): Instance = Instance(
        id = id,
        name = id,
        gameVersion = "1.20.1",
        loader = LoaderKind.VANILLA,
        directory = "/root/versions/" + id,
        registered = registered,
        pinned = pinned,
        lastPlayedAt = lastPlayedAt,
        group = group,
    )

    @Test
    fun `组名首尾空白和全角空格都被收掉`() {
        assertEquals("Mod 战斗", normalizeGroupName("  Mod\u3000战斗 "))
        assertEquals("联机", normalizeGroupName("联机"))
        assertEquals("", normalizeGroupName("   "))
    }

    @Test
    fun `连续空白折成一个 超长截断`() {
        assertEquals("a b", normalizeGroupName("a    b"))
        val long = "一".repeat(40)
        assertEquals(MAX_GROUP_NAME_LENGTH, normalizeGroupName(long).length)
    }

    @Test
    fun `分组时顺手把实例登记下来`() {
        val scanned = instance("a", registered = false)
        val grouped = withGroup(scanned, "  Mod  ")
        assertEquals("Mod", grouped.group)
        assertTrue(grouped.registered, "给扫描到的实例分组后，它必须进 instances.json，否则下次分组就丢了")
    }

    @Test
    fun `组名归一化后落到实例上`() {
        assertEquals("", withGroup(instance("a", group = "Mod"), "   ").group)
    }

    @Test
    fun `按组切段 组名排序 未分组在最后`() {
        val list = listOf(
            instance("a", group = "联机"),
            instance("b"),
            instance("c", group = "Mod"),
            instance("d"),
            instance("e", group = "Mod"),
        )
        val sections = sectionize(list)
        assertEquals(listOf("Mod", "联机", ""), sections.map { it.name })
        assertEquals(listOf("c", "e"), sections[0].instances.map { it.id })
        assertEquals(listOf("a"), sections[1].instances.map { it.id })
        assertEquals(listOf("b", "d"), sections[2].instances.map { it.id })
    }

    @Test
    fun `段内顺序原样保留 收藏仍在最前`() {
        val list = listOf(instance("p", group = "Mod", pinned = true), instance("q", group = "Mod"))
        assertEquals(listOf("p", "q"), sectionize(list).single().instances.map { it.id })
    }

    @Test
    fun `全都没分组时只有一段`() {
        val sections = sectionize(listOf(instance("a"), instance("b")))
        assertEquals(1, sections.size)
        assertTrue(sections.single().ungrouped)
        assertEquals(2, sections.single().instances.size)
    }

    @Test
    fun `全都有组时没有空的那一段`() {
        val sections = sectionize(listOf(instance("a", group = "Mod")))
        assertEquals(1, sections.size)
        assertEquals("Mod", sections.single().name)
    }

    @Test
    fun `空列表切出零段`() {
        assertEquals(0, sectionize(emptyList()).size)
    }

    @Test
    fun `组名列表去重排序 不含空串`() {
        val list = listOf(
            instance("a", group = "联机"),
            instance("b"),
            instance("c", group = "Mod"),
            instance("d", group = " 联机 "),
        )
        assertEquals(listOf("Mod", "联机"), groupNames(list))
    }

    @Test
    fun `实例字段的组名也走归一化后才归段`() {
        // 手改 instances.json 打出多余空格的人不该多出一个看起来同名的空组。
        val list = listOf(instance("a", group = "  Mod "), instance("b", group = "Mod"))
        val sections = sectionize(list)
        assertEquals(1, sections.size)
        assertEquals(listOf("a", "b"), sections.single().instances.map { it.id })
    }
}
