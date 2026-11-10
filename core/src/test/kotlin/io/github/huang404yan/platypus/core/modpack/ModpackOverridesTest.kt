package io.github.huang404yan.platypus.core.modpack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModpackOverridesTest {

    @Test
    fun `overrides 下的路径剥掉前缀`() {
        assertEquals(
            "config/a.toml",
            ModpackOverrides.relativePath("overrides/config/a.toml"),
        )
    }

    @Test
    fun `client-overrides 与 overrides 都能剥`() {
        val layers = listOf("overrides", "client-overrides")
        assertEquals("config/x.txt", ModpackOverrides.relativePath("overrides/config/x.txt", "", layers))
        assertEquals("config/x.txt", ModpackOverrides.relativePath("client-overrides/config/x.txt", "", layers))
    }

    @Test
    fun `不在任何层里的 entry 返回 null`() {
        assertNull(ModpackOverrides.relativePath("modrinth.index.json"))
        assertNull(ModpackOverrides.relativePath("readme.txt"))
        // 前缀相近但不是同一层：overrides2/ 不该被当成 overrides/
        assertNull(ModpackOverrides.relativePath("overrides2/config/a.txt"))
    }

    @Test
    fun `套了顶层文件夹的包也能剥对`() {
        assertEquals(
            "config/a.toml",
            ModpackOverrides.relativePath(
                entryName = "MyPack/overrides/config/a.toml",
                basePrefix = "MyPack/",
            ),
        )
        // 前缀对不上时不该硬剥
        assertNull(
            ModpackOverrides.relativePath(
                entryName = "OtherPack/overrides/config/a.toml",
                basePrefix = "MyPack/",
            ),
        )
    }

    @Test
    fun `层目录自身那条 entry 剥出空串时不返回`() {
        // "overrides/" 是目录条目，落到盘上没有任何意义
        assertNull(ModpackOverrides.relativePath("overrides/"))
        assertNull(ModpackOverrides.relativePath("overrides"))
    }

    @Test
    fun `层里夹带路径穿越仍然被拦`() {
        // 先剥层、再做安全校验的顺序不能反：剥之前 .. 可能藏在层名前面
        assertNull(ModpackOverrides.relativePath("overrides/../../evil.dll"))
        assertNull(ModpackOverrides.relativePath("../overrides/a.txt"))
    }

    @Test
    fun `反斜杠写的 entry 也能剥`() {
        assertEquals(
            "config/a.toml",
            ModpackOverrides.relativePath("overrides\\config\\a.toml"),
        )
    }

    @Test
    fun `层顺序原样取自清单`() {
        val manifest = ModpackManifest(
            kind = ModpackKind.MRPACK,
            name = "n",
            versionId = "",
            gameVersion = "1.20.1",
            overrideDirs = listOf("overrides", "client-overrides"),
        )
        // client-overrides 在后 = 最后覆盖，与官方文档一致
        assertEquals(listOf("overrides", "client-overrides"), ModpackOverrides.layersFor(manifest))
    }

    @Test
    fun `存档与设置算用户数据`() {
        assertTrue(ModpackOverrides.touchesUserData("saves/世界/level.dat"))
        assertTrue(ModpackOverrides.touchesUserData("options.txt"))
        assertTrue(ModpackOverrides.touchesUserData("servers.dat"))
        // 模组与配置不算：它们就是包要改的地方
        assertTrue(!ModpackOverrides.touchesUserData("mods/a.jar"))
        assertTrue(!ModpackOverrides.touchesUserData("config/a.toml"))
    }
}
