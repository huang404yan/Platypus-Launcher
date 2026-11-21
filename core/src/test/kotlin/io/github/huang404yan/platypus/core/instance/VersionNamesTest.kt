package io.github.huang404yan.platypus.core.instance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 判「登记的实例还在不在盘上」。
 *
 * 这一组的核心不是「能挑出没了的」，而是**不能乱挑**：游戏目录暂时读不到时
 * （移动硬盘没插、网盘没挂载、权限被拒）一旦把整份登记当成已删除藏起来，
 * 用户看到的是「我的版本全没了」，而它们其实一条都没少。所以这里一半用例
 * 都在测「不该摘的时候没摘」。
 */
class VersionNamesTest {

    private fun instance(id: String, directory: String = "/root/versions/" + id) = Instance(
        id = id,
        name = id,
        gameVersion = "1.20.1",
        directory = directory,
    )

    private fun names(vararg values: String) = VersionNames(values.map { it.lowercase() }.toSet())

    @Test
    fun keepsInstancesThatStillExist() {
        val list = listOf(instance("1.20.1"), instance("1.20.1-fabric"))
        val result = pruneMissing(list, names("1.20.1", "1.20.1-fabric"))
        assertEquals(2, result.kept.size)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun reportsTheOnesThatAreGone() {
        val list = listOf(instance("1.20.1"), instance("1.20.1-forge"))
        val result = pruneMissing(list, names("1.20.1"))
        assertEquals(listOf("1.20.1"), result.kept.map { it.id })
        assertEquals(listOf("1.20.1-forge"), result.missing.map { it.id })
    }

    @Test
    fun nullNamesChangeNothing() {
        // 这一条是整组里最重要的：读不到 versions/ 等于「不知道」，不等于「都没有」。
        val list = listOf(instance("1.20.1"), instance("1.20.1-forge"))
        val result = pruneMissing(list, null)
        assertEquals(list, result.kept)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun emptyVersionsHidesEveryRegisteredInstance() {
        // versions/ 确实存在但是空的：这时候登记的条目是真的没地方待了，摘掉是对的。
        val list = listOf(instance("1.20.1"))
        val result = pruneMissing(list, names())
        assertTrue(result.kept.isEmpty())
        assertEquals(1, result.missing.size)
    }

    @Test
    fun nameComparisonIgnoresCase() {
        // Windows 与 macOS 上目录名不分大小写，1.20.1-Fabric 与 1.20.1-fabric 是同一个。
        val list = listOf(instance("1.20.1-Fabric"))
        val result = pruneMissing(list, names("1.20.1-fabric"))
        assertEquals(1, result.kept.size)
    }

    @Test
    fun trailingSlashAndQuotesDoNotHideAnExistingInstance() {
        // 手贴路径的人经常带引号、带结尾斜杠，那不该让实例被当成不存在。
        val list = listOf(
            instance("1.20.1", "\"/root/versions/1.20.1\""),
            instance("1.20.1-fabric", "/root/versions/1.20.1-fabric/"),
        )
        val result = pruneMissing(list, names("1.20.1", "1.20.1-fabric"))
        assertEquals(2, result.kept.size)
    }

    @Test
    fun windowsStyleDirectoryResolvesToTheLastSegment() {
        val list = listOf(instance("1.20.1", "D:\\games\\.minecraft\\versions\\1.20.1"))
        val result = pruneMissing(list, names("1.20.1"))
        assertEquals(1, result.kept.size)
    }

    @Test
    fun versionDirNameTakesTheLastSegment() {
        assertEquals("1.20.1", versionDirName("/root/versions/1.20.1"))
        assertEquals("1.20.1", versionDirName("/root/versions/1.20.1/"))
        assertEquals("1.20.1", versionDirName("\"D:\\root\\versions\\1.20.1\""))
    }

    @Test
    fun underReturnsNullWhenVersionsIsAbsent() {
        val dir = kotlin.io.path.createTempDirectory("vn").toFile()
        try {
            assertNull(VersionNames.under(dir))
            // versions/ 建出来但一个子目录都没有：这是「空」而不是「读不到」，两者要分得开。
            File(dir, "versions").mkdirs()
            val empty = VersionNames.under(dir)
            assertTrue(empty != null && empty.names.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun underListsOnlyDirectoriesAndLowercasesThem() {
        val dir = kotlin.io.path.createTempDirectory("vn").toFile()
        try {
            val versions = File(dir, "versions")
            File(versions, "1.20.1").mkdirs()
            File(versions, "1.20.1-Fabric").mkdirs()
            // 版本目录里那个 json 是文件，不该进名单。
            File(File(versions, "1.20.1"), "1.20.1.json").writeText("{}")
            val list = VersionNames.under(dir)
            assertEquals(setOf("1.20.1", "1.20.1-fabric"), list?.names)
        } finally {
            dir.deleteRecursively()
        }
    }

@Test
    fun missingNoticeIsSilentWhenNothingWasPruned() {
        // 没有条目被摘掉就不该多出一行话：那会变成每次打开启动器都挂在列表上方的一句废话。
        assertNull(missingNotice(emptyList()))
    }

    @Test
    fun missingNoticeNamesEveryPrunedInstance() {
        val text = missingNotice(listOf(instance("1.20.1"), instance("1.20.1-forge")))
        assertTrue(text != null)
        assertTrue(text!!.contains("1.20.1"))
        assertTrue(text.contains("1.20.1-forge"))
        // 得说清楚「接回来就会重新出现」，否则用户以为登记被我们删了、得重新配一遍。
        assertTrue(text.contains("重新出现"))
    }

    @Test
    fun scanCarriesTheVersionNamesItAlreadyListed() {
        val dir = kotlin.io.path.createTempDirectory("vn").toFile()
        try {
            val versionDir = File(File(dir, "versions"), "1.20.1")
            versionDir.mkdirs()
            File(versionDir, "1.20.1.json").writeText("{\"id\":\"1.20.1\"}")
            val result = InstanceScanner.scan(dir)
            assertEquals(setOf("1.20.1"), result.versionNames?.names)
            assertEquals(1, result.instances.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
