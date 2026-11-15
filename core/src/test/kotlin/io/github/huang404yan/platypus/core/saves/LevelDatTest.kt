package io.github.huang404yan.platypus.core.saves

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `level.dat` 读取。
 *
 * 测试自己造 NBT 字节：只为了测试引一个 NBT writer 库不划算，而这份 writer 只有二十行 ——
 * 顺带把「我们读的是哪种格式」变成了可执行的规格。
 */
class LevelDatTest {
    private val root: File = Files.createTempDirectory("platypus-level-test").toFile()

    @AfterTest
    fun tearDown() {
        runCatching { root.deleteRecursively() }
    }

    @Test
    fun `读出世界名、游戏模式与最后游玩时间`() {
        val dir = File(root, "world")
        dir.mkdirs()
        write(dir) {
            putString("LevelName", "生存存档")
            putLong("LastPlayed", 1_700_000_000_000L)
            putInt("GameType", 1)
            putByte("hardcore", 1)
            putByte("allowCommands", 0)
            putString("generatorName", "default")
            child("Version") { putString("Name", "1.20.1") }
        }
        val info = LevelDat.read(dir)
        assertEquals("生存存档", info?.levelName)
        assertEquals(1_700_000_000_000L, info?.lastPlayed)
        assertEquals(1, info?.gameType)
        assertTrue(info!!.hardcore)
        assertFalse(info.allowCommands)
        assertEquals("1.20.1", info.versionName)
        assertEquals("default", info.generator)
    }

    @Test
    fun `没有元数据文件时返回 null 而不是抛`() {
        val dir = File(root, "empty")
        dir.mkdirs()
        assertNull(LevelDat.read(dir))
    }

    @Test
    fun `文件被写坏时返回 null`() {
        val dir = File(root, "broken")
        dir.mkdirs()
        File(dir, LevelDat.FILE_NAME).writeBytes(byteArrayOf(10, 0, 0, 1, 2, 3))
        assertNull(LevelDat.read(dir))
    }

    @Test
    fun `未压缩的裸 NBT 也认`() {
        val dir = File(root, "plain")
        dir.mkdirs()
        File(dir, LevelDat.FILE_NAME).writeBytes(nbtBytes { putString("LevelName", "裸的") })
        assertEquals("裸的", LevelDat.read(dir)?.levelName)
    }

    @Test
    fun `缺字段时各项退默认值而世界名还读得出来`() {
        val dir = File(root, "thin")
        dir.mkdirs()
        write(dir) { putString("LevelName", "只有名字") }
        val info = LevelDat.read(dir)
        assertEquals("只有名字", info?.levelName)
        assertNull(info?.gameType)
        assertEquals(0L, info?.lastPlayed)
        assertFalse(info!!.hardcore)
    }

    // ---- 造 NBT：根是 compound，里面按标签类型逐个写 ----

    private class Compound(private val data: DataOutputStream) {
        fun putString(name: String, value: String) {
            data.writeByte(8)
            data.writeUTF(name)
            data.writeUTF(value)
        }

        fun putInt(name: String, value: Int) {
            data.writeByte(3)
            data.writeUTF(name)
            data.writeInt(value)
        }

        fun putLong(name: String, value: Long) {
            data.writeByte(4)
            data.writeUTF(name)
            data.writeLong(value)
        }

        fun putByte(name: String, value: Int) {
            data.writeByte(1)
            data.writeUTF(name)
            data.writeByte(value)
        }

        /** 嵌套 compound：子标签写完后补一个 TAG_END。 */
        fun child(name: String, block: Compound.() -> Unit) {
            data.writeByte(10)
            data.writeUTF(name)
            Compound(data).block()
            data.writeByte(0)
        }
    }

    /** 原版把所有东西放在根的 Data 下，读的时候也只认 Data，所以造数据时要包这一层。 */
    private fun nbtBytes(block: Compound.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(10)
        data.writeUTF("")
        data.writeByte(10)
        data.writeUTF("Data")
        Compound(data).block()
        data.writeByte(0) // Data 结束
        data.writeByte(0) // 根结束
        data.flush()
        return out.toByteArray()
    }

    private fun write(dir: File, block: Compound.() -> Unit) {
        GZIPOutputStream(File(dir, LevelDat.FILE_NAME).outputStream()).use { it.write(nbtBytes(block)) }
    }
}
