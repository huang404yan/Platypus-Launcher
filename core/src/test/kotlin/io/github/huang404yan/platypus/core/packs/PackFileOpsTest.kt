package io.github.huang404yan.platypus.core.packs

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackFileOpsTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    private fun pack(dir: File, name: String): File = File(dir, name).also {
        it.writeText("PK zip payload", Charsets.UTF_8)
    }

    @Test
    fun `停用会加 zip 与 disabled 后缀`() {
        val dir = tempDir("packops-off")
        pack(dir, "Complementary.zip")

        val renamed = PackFileOps.setEnabled(dir, "Complementary.zip", enabled = false)
        assertEquals("Complementary.zip.disabled", renamed.name)
        assertFalse(File(dir, "Complementary.zip").exists())
        assertTrue(PackFileOps.isDisabledName(renamed.name))
    }

    @Test
    fun `启用会剥掉后缀恢复成 zip`() {
        val dir = tempDir("packops-on")
        pack(dir, "Pack.zip.disabled")

        val renamed = PackFileOps.setEnabled(dir, "Pack.zip.disabled", enabled = true)
        assertEquals("Pack.zip", renamed.name)
        assertFalse(PackFileOps.isDisabledName(renamed.name))
    }

    @Test
    fun `disabled 尾巴也能还原成 zip`() {
        // 有些用户手改成了 x.disabled，日志里没见过但在磁盘上很常见
        assertEquals("Pack.zip", PackFileOps.enabledName("Pack.zip.disabled"))
        assertEquals("Pack.zip", PackFileOps.enabledName("Pack.disabled"))
        assertEquals("Pack.zip", PackFileOps.enabledName("Pack.zip"))
        assertEquals("Pack.zip.disabled", PackFileOps.disabledName("Pack.zip"))
        assertEquals("Pack.zip", PackFileOps.enabledName("Pack.zip.bak"))
    }

    @Test
    fun `文件夹形态的包拒绝停用并说明原因`() {
        val dir = tempDir("packops-dir")
        File(dir, "MyPack").mkdirs()

        val error = assertFailsWith<IOException> {
            PackFileOps.setEnabled(dir, "MyPack", enabled = false)
        }
        assertTrue(error.message!!.contains("文件夹形态"))
        // 目录必须原样还在，不能因为拒绝而丢东西
        assertTrue(File(dir, "MyPack").isDirectory)
    }

    @Test
    fun `目标已存在时不覆盖`() {
        val dir = tempDir("packops-dup")
        pack(dir, "Pack.zip")
        pack(dir, "Pack.zip.disabled")

        val error = assertFailsWith<IOException> {
            PackFileOps.setEnabled(dir, "Pack.zip", enabled = false)
        }
        assertTrue(error.message!!.contains("不覆盖"))
        assertTrue(File(dir, "Pack.zip.disabled").exists())
    }

    @Test
    fun `文件不在了要说实话`() {
        val dir = tempDir("packops-missing")
        val error = assertFailsWith<IOException> {
            PackFileOps.setEnabled(dir, "ghost.zip", enabled = false)
        }
        assertTrue(error.message!!.contains("不在了"))
    }

    @Test
    fun `删除真的把文件删掉`() {
        val dir = tempDir("packops-del")
        pack(dir, "gone.zip")

        assertTrue(PackFileOps.delete(dir, "gone.zip"))
        assertFalse(File(dir, "gone.zip").exists())
    }

    @Test
    fun `删除不存在的文件返回 false 而不是抛错`() {
        val dir = tempDir("packops-del2")
        assertFalse(PackFileOps.delete(dir, "ghost.zip"))
    }
}
