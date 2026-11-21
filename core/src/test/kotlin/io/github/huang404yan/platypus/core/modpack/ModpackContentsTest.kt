package io.github.huang404yan.platypus.core.modpack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModpackContentsTest {

    @Test
    fun `统计配置层里的文件数与模组 jar 数`() {
        val content = ModpackContents.scan(
            listOf(
                "mcbbs.packmeta",
                "manifest.json",
                "overrides/",
                "overrides/mods/",
                "overrides/mods/a.jar",
                "overrides/mods/b.jar",
                "overrides/mods/c.jar.disabled",
                "overrides/config/x.toml",
                "overrides/saves/world/level.dat",
            ),
        )
        // 目录条目不算文件；三份 jar（含停用那份）都算模组
        assertEquals(5, content.totalFiles)
        assertEquals(3, content.modJars)
        assertEquals("mods", content.dirs.first().name)
        assertEquals(3, content.dirs.first().files)
    }

    @Test
    fun `顶层目录按文件数从多到少、同数按名字`() {
        val content = ModpackContents.scan(
            listOf(
                "overrides/config/a.txt",
                "overrides/config/b.txt",
                "overrides/tacz/c.txt",
                "overrides/aaa/d.txt",
                "overrides/saves/e.txt",
            ),
        )
        // config 2 个最多；其余都是 1 个，按名字排
        assertEquals(listOf("config", "aaa", "saves", "tacz"), content.dirs.map { it.name })
    }

    @Test
    fun `套了顶层文件夹的包也统计得到`() {
        val content = ModpackContents.scan(
            listOf(
                "MyPack/overrides/mods/x.jar",
                "MyPack/overrides/config/y.toml",
            ),
            basePrefix = "MyPack/",
        )
        assertEquals(2, content.totalFiles)
        assertEquals(1, content.modJars)
    }

    @Test
    fun `包里的 mods 是空的时候能看出来`() {
        // 用户报过的那个包：overrides 下一堆东西，但 mods 只有一条目录条目
        val content = ModpackContents.scan(
            listOf(
                "overrides/",
                "overrides/mods/",
                "overrides/tacz/ak47.zip",
                "overrides/config/x.toml",
            ),
        )
        assertEquals(0, content.modJars)
        assertFalse(content.isEmpty)
    }

    @Test
    fun `配置层里一个文件都没有`() {
        val content = ModpackContents.scan(listOf("modrinth.index.json"))
        assertTrue(content.isEmpty)
        assertEquals("一个文件都没有", content.describe())
    }

    @Test
    fun `describe 里带 jar 的目录会标出 jar 数`() {
        val content = ModpackContents.scan(
            listOf("overrides/mods/a.jar", "overrides/config/b.toml"),
        )
        assertEquals("config 1 个文件 · mods 1 个文件（1 个 jar）", content.describe())
    }

    @Test
    fun `describe 超过上限会截断`() {
        val entries = (1..7).map { "overrides/d" + it + "/f.txt" }
        val text = ModpackContents.scan(entries).describe(maxDirs = 3)
        assertTrue(text.endsWith("…还有 4 个目录"), text)
    }

    @Test
    fun `只认 jar 后缀 不把普通文件当模组`() {
        assertTrue(ModpackContents.isModJar("a.jar"))
        assertTrue(ModpackContents.isModJar("A.JAR"))
        assertTrue(ModpackContents.isModJar("a.jar.disabled"))
        assertFalse(ModpackContents.isModJar("a.zip"))
        assertFalse(ModpackContents.isModJar("jar.txt"))
    }

    @Test
    fun `配置层为空时统计全零`() {
        val content = ModpackContents.scan(emptyList())
        assertEquals(0, content.totalFiles)
        assertEquals(0, content.modJars)
        assertTrue(content.dirs.isEmpty())
    }
}
