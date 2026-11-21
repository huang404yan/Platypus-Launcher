package io.github.huang404yan.platypus.core.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 实例搜索与分组。
 *
 * 搜索最怕的是「明明打对了却搜不到」，所以每条用例都盯着一个具体的输入习惯：
 * 多段输入、全角空格、按目录名找、大小写。
 */
class InstanceCatalogTest {

    private fun instance(
        id: String,
        name: String = id,
        gameVersion: String = "1.20.1",
        loader: LoaderKind = LoaderKind.VANILLA,
        loaderVersion: String? = null,
        dir: String = "/root/versions/" + id,
        registered: Boolean = true,
    ) = Instance(
        id = id,
        name = name,
        gameVersion = gameVersion,
        loader = loader,
        loaderVersion = loaderVersion,
        directory = dir,
        registered = registered,
    )

    private val fabric = instance(
        id = "1.20.1-fabric",
        name = "粘液科技",
        gameVersion = "1.20.1",
        loader = LoaderKind.FABRIC,
        loaderVersion = "0.15.11",
    )
    private val vanilla = instance(id = "1.20.1", name = "原版生存", gameVersion = "1.20.1")
    private val forge = instance(
        id = "1.19.2-forge",
        name = "老存档",
        gameVersion = "1.19.2",
        loader = LoaderKind.FORGE,
        loaderVersion = "43.2.0",
    )
    private val all = listOf(fabric, vanilla, forge)

    @Test
    fun `空查询原样返回全部`() {
        assertEquals(all, InstanceCatalog.search(all, ""))
        assertEquals(all, InstanceCatalog.search(all, "   "))
    }

    @Test
    fun `按名字的一部分就能找到`() {
        assertEquals(listOf(fabric), InstanceCatalog.search(all, "粘液"))
    }

    @Test
    fun `多个词之间是且 不是整串匹配`() {
        assertEquals(listOf(fabric), InstanceCatalog.search(all, "1.20.1 fabric"))
        assertEquals(emptyList(), InstanceCatalog.search(all, "1.20.1 forge"))
    }

    @Test
    fun `大小写不影响结果`() {
        assertEquals(listOf(fabric), InstanceCatalog.search(all, "FABRIC"))
        assertEquals(listOf(forge), InstanceCatalog.search(all, "Forge"))
    }

    @Test
    fun `全角空格也当分隔符`() {
        assertEquals(listOf(fabric), InstanceCatalog.search(all, "1.20.1\u3000fabric"))
    }

    @Test
    fun `能按目录名搜到 目录和名字不一样时也认得`() {
        val renamed = instance(id = "abc", name = "我的整合", dir = "D:/mc/versions/abc")
        assertEquals(listOf(renamed), InstanceCatalog.search(listOf(renamed), "D:/mc"))
        assertEquals(listOf(renamed), InstanceCatalog.search(listOf(renamed), "abc"))
    }

    @Test
    fun `加载器版本号也能搜`() {
        assertEquals(listOf(fabric), InstanceCatalog.search(all, "0.15.11"))
    }

    @Test
    fun `分组按是否登记过 顺序保持不变`() {
        val groups = InstanceCatalog.partition(
            listOf(vanilla, instance(id = "ext", registered = false), fabric),
        )
        assertEquals(listOf("1.20.1", "1.20.1-fabric"), groups.registered.map { it.id })
        assertEquals(listOf("ext"), groups.scanned.map { it.id })
        assertEquals(3, groups.total)
    }

    @Test
    fun `两组都空时 isEmpty 为真`() {
        val groups = InstanceCatalog.partition(emptyList())
        assertTrue(groups.isEmpty)
        assertEquals(0, groups.total)
    }
}
