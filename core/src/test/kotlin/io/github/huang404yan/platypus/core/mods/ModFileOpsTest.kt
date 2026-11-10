package io.github.huang404yan.platypus.core.mods

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModFileOpsTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    /** 造一个能被 ModScanner 认出来的最小 fabric 模组 jar。 */
    private fun fabricJar(modsDir: File, name: String, id: String): File {
        modsDir.mkdirs()
        val file = File(modsDir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            val json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\"}"
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun `停用把 jar 改成 jar_disabled`() {
        val dir = tempDir("platypus-modops-disable")
        fabricJar(dir, "sodium.jar", "sodium")
        val renamed = ModFileOps.setEnabled(dir, "sodium.jar", enabled = false)
        assertEquals("sodium.jar.disabled", renamed.name)
        assertTrue(File(dir, "sodium.jar.disabled").isFile)
        assertFalse(File(dir, "sodium.jar").exists())
    }

    @Test
    fun `启用把 jar_disabled 改回 jar`() {
        val dir = tempDir("platypus-modops-enable")
        File(dir, "sodium.jar.disabled").writeText("x")
        val renamed = ModFileOps.setEnabled(dir, "sodium.jar.disabled", enabled = true)
        assertEquals("sodium.jar", renamed.name)
        assertTrue(File(dir, "sodium.jar").isFile)
    }

    @Test
    fun `启用时 disabled 尾巴要补回 jar 后缀`() {
        val dir = tempDir("platypus-modops-tail")
        File(dir, "jei.disabled").writeText("x")
        val renamed = ModFileOps.setEnabled(dir, "jei.disabled", enabled = true)
        assertEquals("jei.jar", renamed.name)
    }

    @Test
    fun `启用 jar_bak 也回到 jar`() {
        val dir = tempDir("platypus-modops-bak")
        File(dir, "jei.jar.bak").writeText("x")
        assertEquals("jei.jar", ModFileOps.setEnabled(dir, "jei.jar.bak", enabled = true).name)
    }

    @Test
    fun `目标名已存在时拒绝改名而不是覆盖`() {
        val dir = tempDir("platypus-modops-clash")
        File(dir, "sodium.jar.disabled").writeText("a")
        File(dir, "sodium.jar").writeText("b")
        val error = assertFailsWith<IOException> { ModFileOps.setEnabled(dir, "sodium.jar.disabled", enabled = true) }
        assertTrue(error.message!!.contains("不覆盖"))
        assertEquals("b", File(dir, "sodium.jar").readText())
    }

    @Test
    fun `文件不在时说清绝对路径`() {
        val dir = tempDir("platypus-modops-missing")
        val error = assertFailsWith<IOException> { ModFileOps.setEnabled(dir, "nope.jar", enabled = false) }
        assertTrue(error.message!!.contains("文件不在了"))
    }

    @Test
    fun `已经是目标状态时原样返回不报错`() {
        val dir = tempDir("platypus-modops-same")
        File(dir, "sodium.jar.disabled").writeText("x")
        assertEquals("sodium.jar.disabled", ModFileOps.setEnabled(dir, "sodium.jar.disabled", enabled = false).name)
    }

    @Test
    fun `删除返回 true 且文件消失`() {
        val dir = tempDir("platypus-modops-delete")
        val jar = fabricJar(dir, "iris.jar", "iris")
        assertTrue(ModFileOps.delete(dir, "iris.jar"))
        assertFalse(jar.exists())
    }

    @Test
    fun `删不存在的文件返回 false 不抛`() {
        val dir = tempDir("platypus-modops-delete-none")
        assertFalse(ModFileOps.delete(dir, "nope.jar"))
    }

    @Test
    fun `改名规则对非 jar 文件不生效`() {
        val dir = tempDir("platypus-modops-txt")
        File(dir, "readme.txt").writeText("x")
        assertEquals("readme.txt", ModFileOps.disabledName("readme.txt"))
        assertEquals("readme.txt", ModFileOps.setEnabled(dir, "readme.txt", enabled = false).name)
    }

    @Test
    fun `停用再启用之后扫描结果对得上`() {
        // 这一例要核对与 ModScanner 的配合，所以按真实布局摆：<游戏目录>/mods/x.jar
        val gameDir = tempDir("platypus-modops-roundtrip")
        val dir = File(gameDir, ModScanner.MODS_DIR_NAME)
        fabricJar(dir, "sodium.jar", "sodium")
        assertEquals(1, ModScanner.scan(gameDir).mods.size)

        ModFileOps.setEnabled(dir, "sodium.jar", enabled = false)
        val off = ModScanner.scan(gameDir)
        assertEquals(0, off.mods.size)
        assertEquals(1, off.disabled.size)
        assertEquals("sodium", off.disabled.first().modId)

        ModFileOps.setEnabled(dir, "sodium.jar.disabled", enabled = true)
        val on = ModScanner.scan(gameDir)
        assertEquals(1, on.mods.size)
        assertEquals(0, on.disabled.size)
        assertEquals("sodium", on.mods.first().modId)
    }

    @Test
    fun `isDisabledName 认得三种尾巴`() {
        assertTrue(ModFileOps.isDisabledName("a.jar.disabled"))
        assertTrue(ModFileOps.isDisabledName("a.jar.bak"))
        assertTrue(ModFileOps.isDisabledName("a.disabled"))
        assertFalse(ModFileOps.isDisabledName("a.jar"))
        assertFalse(ModFileOps.isDisabledName("a.txt"))
    }
}
