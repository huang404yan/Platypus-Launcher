package io.github.huang404yan.platypus.core.launch

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 日志编码兜底的回归测试。
 *
 * 这一批用例存在的理由很具体：界面上那串「??????」（U+FFFD）不是渲染问题，是解码时
 * 就把字节丢了。所以断言一律看**解出来的字符串**，不看有没有抛异常。
 */
class LogLineDecoderTest {

    private val gbk = java.nio.charset.Charset.forName("GBK")

    private fun lines(vararg text: String): ByteArray =
        text.joinToString("").toByteArray(Charsets.UTF_8)

    private fun readAll(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        LogLineReader(ByteArrayInputStream(bytes)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                result += line
            }
        }
        return result
    }

    @Test
    fun `纯 ASCII 原样解出`() {
        assertEquals("[12:00:00] [Render thread/INFO]: Done", LogLineDecoder.decode("[12:00:00] [Render thread/INFO]: Done".toByteArray()))
    }

    @Test
    fun `UTF-8 中文不会被当成 GBK`() {
        val bytes = "已启动 1.20.1".toByteArray(Charsets.UTF_8)
        assertEquals("已启动 1.20.1", LogLineDecoder.decode(bytes))
        assertFalse(LogLineDecoder.decode(bytes).contains('\uFFFD'))
    }

    @Test
    fun `GBK 中文按 GBK 兜底解开而不是变成替换符`() {
        val text = "[Render thread/INFO]: OpenAL initialized"
        val bytes = text.toByteArray(gbk)
        assertEquals(text, LogLineDecoder.decode(bytes))
        assertFalse(LogLineDecoder.decode(bytes).contains('\uFFFD'))
    }

    @Test
    fun `看起来像 GBK 的字节能被认出来`() {
        assertTrue(LogLineDecoder.looksLikeGbk("声音已初始化".toByteArray(gbk)))
        assertFalse(LogLineDecoder.looksLikeGbk("声音已初始化".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `两种编码都解不开时退回宽松 UTF-8 不抛异常`() {
        // 0xFF 在任何 UTF-8 / GBK 里都不是合法起始字节
        val bytes = byteArrayOf(0x41, 0xFF.toByte(), 0x42)
        val decoded = LogLineDecoder.decode(bytes)
        assertTrue(decoded.startsWith("A"))
        assertTrue(decoded.endsWith("B"))
    }

    @Test
    fun `按行切开并去掉行尾回车`() {
        val bytes = "第一行\r\n第二行\n第三行".toByteArray(Charsets.UTF_8)
        assertEquals(listOf("第一行", "第二行", "第三行"), readAll(bytes))
    }

    @Test
    fun `流结尾没有换行的最后一行也要交出来`() {
        assertEquals(listOf("a", "b"), readAll("a\nb".toByteArray()))
    }

    @Test
    fun `空流返回 null 而不是空行`() {
        LogLineReader(ByteArrayInputStream(ByteArray(0))).use { reader ->
            assertNull(reader.readLine())
        }
    }

    @Test
    fun `整行都是 GBK 的中文日志被完整读回`() {
        val text = "错误：找不到资源包"
        val bytes = (text + "\r\n").toByteArray(gbk)
        assertEquals(listOf(text), readAll(bytes))
    }

    @Test
    fun `超长的一行会被截断但不会把读取卡死`() {
        val bytes = (("x".repeat(70_000)) + "\nnext").toByteArray()
        val out = readAll(bytes)
        assertEquals(2, out.size)
        assertEquals(64 * 1024, out[0].length)
        assertEquals("next", out[1])
    }

    @Test
    fun `恰好 4096 字节的边界也能切开`() {
        // 读缓冲是 8192 字节，两行合计正好跨过缓冲边界
        val first = "a".repeat(4090)
        val second = "b".repeat(100)
        assertEquals(listOf(first, second), readAll((first + "\n" + second).toByteArray()))
    }
}
