package io.github.huang404yan.platypus.core.server

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerPropertiesTest {

    private val dir: File = Files.createTempDirectory("platypus-props-test").toFile()

    @AfterTest
    fun tearDown() {
        runCatching { dir.deleteRecursively() }
    }

    private fun write(text: String) {
        File(dir, ServerProperties.FILE_NAME).writeText(text, Charsets.UTF_8)
    }

    @Test
    fun `没有文件时读到的是空的，取默认值`() {
        val view = ServerProperties.read(dir)
        assertTrue(view.lines.isEmpty())
        assertEquals("25565", view.string("server-port", "25565"))
        assertFalse(view.contains("server-port"))
        assertFalse(ServerProperties.exists(dir))
    }

    @Test
    fun `注释、空行与顺序都原样留着`() {
        write("# 这是注释\r\n\r\nmotd=你好\r\n# server-port=25566\r\nmax-players=8\r\n")
        val view = ServerProperties.read(dir)
        assertEquals(5, view.lines.size)
        assertEquals("# 这是注释", view.lines[0].raw)
        assertNull(view.lines[0].key)
        assertEquals("", view.lines[1].raw)
        assertNull(view.lines[3].key)
        // 被注释掉的旧值不算这个键的值
        assertFalse(view.contains("server-port"))
        assertEquals("8", view.string("max-players"))
    }

    @Test
    fun `改一个已有键时其他行一个字节都不动`() {
        write("# 头\r\nmotd=你好\r\nmax-players=8\r\npvp=true\r\n")
        val before = ServerProperties.read(dir)
        val after = before.withValue("max-players", "20")
        assertEquals(4, after.lines.size)
        assertEquals("# 头", after.lines[0].raw)
        assertEquals("motd=你好", after.lines[1].raw)
        assertEquals("max-players=20", after.lines[2].raw)
        assertEquals("pvp=true", after.lines[3].raw)
    }

    @Test
    fun `改没有的键时追加到末尾并写回磁盘`() {
        write("motd=你好\r\n")
        val view = ServerProperties.read(dir).withValue("server-port", "25567")
        ServerProperties.write(dir, view)
        val reread = ServerProperties.read(dir)
        assertEquals("25567", reread.string("server-port"))
        assertEquals("motd=你好", reread.lines[0].raw)
        assertEquals("server-port=25567", reread.lines[1].raw)
    }

    @Test
    fun `值里的换行换成空格，免得写出两个键`() {
        assertEquals("第一行 第二行", ServerProperties.sanitizeValue("motd", "第一行\n第二行"))
        val view = ServerProperties.read(dir).withValue("motd", "a\r\nb")
        assertEquals("motd=a b", view.lines[0].raw)
    }

    @Test
    fun `值里带等号不会把键切坏`() {
        write("motd=1+1=2\r\n")
        val view = ServerProperties.read(dir)
        assertEquals("motd", view.lines[0].key)
        assertEquals("1+1=2", view.lines[0].value)
    }

    @Test
    fun `数字字段的范围校验`() {
        val port = ServerProperties.FIELDS.first { it.key == "server-port" }
        assertNull(ServerProperties.problemOf(port, "25565"))
        assertEquals("要填整数。", ServerProperties.problemOf(port, "abc"))
        assertNotNull(ServerProperties.problemOf(port, "70000"))
        assertNotNull(ServerProperties.problemOf(port, "0"))
    }

    @Test
    fun `选择字段只认列出来的值`() {
        val difficulty = ServerProperties.FIELDS.first { it.key == "difficulty" }
        assertNull(ServerProperties.problemOf(difficulty, "hard"))
        assertNull(ServerProperties.problemOf(difficulty, "HARD"))
        assertNotNull(ServerProperties.problemOf(difficulty, "nightmare"))
    }

    @Test
    fun `开关字段只认 true 与 false`() {
        val pvp = ServerProperties.FIELDS.first { it.key == "pvp" }
        assertNull(ServerProperties.problemOf(pvp, "true"))
        assertNull(ServerProperties.problemOf(pvp, "False"))
        assertNotNull(ServerProperties.problemOf(pvp, "yes"))
    }

    @Test
    fun `世界文件夹名挡掉路径分隔符与非法字符`() {
        assertTrue(ServerProperties.isSafeWorldName("world"))
        assertTrue(ServerProperties.isSafeWorldName("我的世界2"))
        assertFalse(ServerProperties.isSafeWorldName("../逃出去"))
        assertFalse(ServerProperties.isSafeWorldName("a/b"))
        assertFalse(ServerProperties.isSafeWorldName("a\\b"))
        assertFalse(ServerProperties.isSafeWorldName("a:b"))
        assertFalse(ServerProperties.isSafeWorldName("  "))
    }

    @Test
    fun `写文件用 CRLF 且不留末尾空行`() {
        write("motd=a\r\npvp=true\r\n")
        val view = ServerProperties.read(dir)
        ServerProperties.write(dir, view)
        val text = File(dir, ServerProperties.FILE_NAME).readText(Charsets.UTF_8)
        assertEquals("motd=a\r\npvp=true\r\n", text)
    }

    @Test
    fun `字段表里的默认值与原版一致`() {
        assertEquals("25565", ServerProperties.defaults()["server-port"])
        assertEquals("20", ServerProperties.defaults()["max-players"])
        assertEquals("true", ServerProperties.defaults()["online-mode"])
        assertEquals("survival", ServerProperties.defaults()["gamemode"])
    }

    @Test
    fun `批量改时一次写完`() {
        write("pvp=true\r\n")
        val view = ServerProperties.read(dir).withValues(mapOf("pvp" to "false", "max-players" to "5"))
        assertEquals("false", view.string("pvp"))
        assertEquals("5", view.string("max-players"))
    }

    @Test
    fun `读不动或坏文件不抛异常`() {
        // 目录放在该放文件的位置上：读取一定失败。
        File(dir, ServerProperties.FILE_NAME).mkdirs()
        val view = ServerProperties.read(dir)
        assertTrue(view.lines.isEmpty())
    }
}
