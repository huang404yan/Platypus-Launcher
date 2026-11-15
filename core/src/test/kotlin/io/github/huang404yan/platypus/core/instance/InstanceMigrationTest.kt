package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceMigrationTest {

    private val root: File = Files.createTempDirectory("platypus-migrate-test").toFile()
    private val versionDir: File = File(File(root, "versions"), "1.21.1")

    @AfterTest
    fun tearDown() {
        runCatching { root.deleteRecursively() }
    }

    private fun file(relative: String, text: String = "x") {
        val target = File(if (relative.startsWith("versions/")) root else versionDir, relative)
        target.parentFile?.mkdirs()
        target.writeText(text, Charsets.UTF_8)
    }

    private fun rootFile(relative: String, text: String = "x") {
        val target = File(root, relative)
        target.parentFile?.mkdirs()
        target.writeText(text, Charsets.UTF_8)
    }

    @Test
    fun `只报告有内容的每实例目录`() {
        File(versionDir, "saves").mkdirs() // 空目录不算
        file("mods/a.jar")
        file("saves/world/level.dat")
        val pending = InstanceMigration.pending(versionDir)
        assertTrue(pending.contains("mods"), pending.toString())
        assertTrue(pending.contains("saves"), pending.toString())
        assertFalse(pending.contains("config"), "空目录不该出现在清单里：" + pending)
    }

    @Test
    fun `合并把文件复制到游戏根目录并且原件还在`() {
        file("mods/a.jar", "A")
        file("saves/world/level.dat", "L")
        val result = InstanceMigration.merge(versionDir)
        assertEquals(2, result.entries.size, result.entries.toString())
        assertEquals("A", File(root, "mods/a.jar").readText(Charsets.UTF_8))
        assertEquals("L", File(root, "saves/world/level.dat").readText(Charsets.UTF_8))
        // 复制不移动：原件必须还在，否则用户想开回隔离就没东西了
        assertTrue(File(versionDir, "mods/a.jar").isFile, "原件被搬走了")
        assertTrue(File(versionDir, "saves/world/level.dat").isFile, "原件被搬走了")
    }

    @Test
    fun `同名文件默认不覆盖只记跳过`() {
        file("mods/a.jar", "新的")
        rootFile("mods/a.jar", "旧的")
        val result = InstanceMigration.merge(versionDir)
        assertEquals(0, result.copied, result.entries.toString())
        assertEquals(1, result.skipped, result.entries.toString())
        assertEquals("旧的", File(root, "mods/a.jar").readText(Charsets.UTF_8), "根目录里的同名文件被覆盖了")
    }

    @Test
    fun `开了覆盖才替换同名文件`() {
        file("mods/a.jar", "新的")
        rootFile("mods/a.jar", "旧的")
        val result = InstanceMigration.merge(versionDir, overwrite = true)
        assertEquals(1, result.copied, result.entries.toString())
        assertEquals("新的", File(root, "mods/a.jar").readText(Charsets.UTF_8))
    }

    @Test
    fun `目录里混着同名与不同名时两样都数对`() {
        file("mods/a.jar", "A")
        file("mods/b.jar", "B")
        rootFile("mods/a.jar", "旧 A")
        val result = InstanceMigration.merge(versionDir)
        assertEquals(1, result.copied, result.entries.toString())
        assertEquals(1, result.skipped, result.entries.toString())
        assertTrue(File(root, "mods/b.jar").isFile, "不同名的那个没复制过去")
    }

    @Test
    fun `没有任何东西可合并时结果为空`() {
        File(versionDir, "mods").mkdirs()
        val result = InstanceMigration.merge(versionDir)
        assertFalse(result.hasWork, "空目录不该产生合并条目：" + result.entries)
        assertEquals(0, result.total)
    }

    @Test
    fun `不是 versions 下的目录时拒绝合并并说清原因`() {
        val odd = File(root, "instances/xx")
        odd.mkdirs()
        File(odd, "mods").mkdirs()
        File(odd, "mods/a.jar").writeText("A", Charsets.UTF_8)
        val error = assertFailsWith<java.io.IOException> { InstanceMigration.merge(odd) }
        assertTrue(error.message.orEmpty().contains("versions"), error.message.orEmpty())
    }

    @Test
    fun `合并结果里的目标目录是游戏根目录`() {
        file("mods/a.jar")
        val result = InstanceMigration.merge(versionDir)
        assertEquals(root.canonicalFile, result.toDir.canonicalFile)
        assertEquals(versionDir.canonicalFile, result.fromDir.canonicalFile)
    }
}
