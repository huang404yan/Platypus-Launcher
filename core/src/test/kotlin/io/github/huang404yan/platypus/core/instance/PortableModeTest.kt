package io.github.huang404yan.platypus.core.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

/**
 * 便携模式的四条判定：标记文件、环境变量、拿不到启动器目录、目录建不出来。
 *
 * 全都是纯函数 + 临时目录，不碰用户真实目录 —— 这类「数据放哪儿」的代码一旦写错，
 * 用户看到的是「设置保存了但下次打开又回默认值」，比崩溃更难查。
 */
class PortableModeTest {

    private fun tempDir(): File = Files.createTempDirectory("platypus-portable").toFile()

    @Test
    fun `没有标记文件也没环境变量时不算便携`() {
        val launcher = tempDir()
        try {
            assertFalse(PortableMode.isEnabled(launcher, env = null))
            assertNull(PortableMode.dataDir(launcher, env = null), "没开就不该给出数据目录")
        } finally {
            launcher.deleteRecursively()
        }
    }

    @Test
    fun `有 portable txt 就算便携并把 data 建出来`() {
        val launcher = tempDir()
        try {
            PortableMode.markerIn(launcher).writeText("")
            assertTrue(PortableMode.isEnabled(launcher, env = null))
            val data = PortableMode.dataDir(launcher, env = null)
            assertEquals(File(launcher, "data").absolutePath, data?.absolutePath)
            assertTrue(data!!.isDirectory, "数据目录要真的建出来，否则设置写不下去")
        } finally {
            launcher.deleteRecursively()
        }
    }

    @Test
    fun `环境变量的四种真值写法都算打开`() {
        listOf("1", "true", "TRUE", " yes ", "On").forEach {
            assertTrue(PortableMode.isTruthy(it), "「" + it + "」应当算打开")
        }
    }

    @Test
    fun `环境变量的关闭写法与空值都不算`() {
        listOf(null, "", "  ", "0", "false", "no", "off", "随便什么").forEach {
            assertFalse(PortableMode.isTruthy(it), "「" + it + "」不该算打开")
        }
    }

    @Test
    fun `只有环境变量也能开便携`() {
        val launcher = tempDir()
        try {
            assertTrue(PortableMode.isEnabled(launcher, env = "1"))
            assertEquals(File(launcher, "data").absolutePath, PortableMode.dataDir(launcher, env = "1")?.absolutePath)
        } finally {
            launcher.deleteRecursively()
        }
    }

    @Test
    fun `拿不到启动器目录时一律不算便携`() {
        // 环境变量开着也不行：没有启动器目录就没有地方放 data/，此时说「已便携」
        // 等于骗用户，设置最后还是会写回用户目录。
        assertFalse(PortableMode.isEnabled(null, env = "1"))
        assertNull(PortableMode.dataDir(null, env = "1"))
    }

    @Test
    fun `data 目录建不出来时退回常规位置`() {
        val launcher = tempDir()
        try {
            // 用一个普通文件冒充启动器目录：File(文件, "data").mkdirs() 必然失败。
            val fake = File(launcher, "not-a-dir")
            fake.writeText("x")
            assertTrue(PortableMode.isEnabled(fake, env = "1"), "环境变量照样说明用户想开")
            assertNull(PortableMode.dataDir(fake, env = "1"), "建不出来就得返回 null 让调用方退回")
        } finally {
            launcher.deleteRecursively()
        }
    }

    @Test
    fun `数据目录名固定是 data 且标记文件名固定`() {
        assertEquals("portable.txt", PortableMode.MARKER_FILE)
        assertEquals("PLATYPUS_PORTABLE", PortableMode.ENV_NAME)
        assertEquals("data", PortableMode.DATA_DIR_NAME)
        val launcher = File("C:/mc")
        assertEquals(File("C:/mc/portable.txt").path, PortableMode.markerIn(launcher).path)
        assertEquals(File("C:/mc/data").path, PortableMode.dataDirIn(launcher).path)
    }
}
