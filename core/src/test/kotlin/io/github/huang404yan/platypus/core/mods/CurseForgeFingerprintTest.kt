package io.github.huang404yan.platypus.core.mods

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurseForgeFingerprintTest {

    private val root: File = Files.createTempDirectory("platypus-cf-fingerprint").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun fileOf(name: String, text: String): File =
        File(root, name).also { it.writeText(text, Charsets.ISO_8859_1) }

    private fun fingerprintOf(text: String): Long =
        CurseForgeFingerprint.of(text.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `空文件的指纹是初值本身`() {
        assertEquals(1540447798L, fingerprintOf(""))
    }

    @Test
    fun `与参考实现逐字节一致`() {
        assertEquals(626045324L, fingerprintOf("a"))
        assertEquals(1692487918L, fingerprintOf("ab"))
        assertEquals(1621425345L, fingerprintOf("abc"))
        assertEquals(3376380438L, fingerprintOf("abcd"))
        assertEquals(2667173943L, fingerprintOf("test"))
        assertEquals(2824650221L, fingerprintOf("hello world"))
    }

    @Test
    fun `空白字节先被删掉再算`() {
        assertEquals(fingerprintOf("ab"), fingerprintOf("a\tb"))
        assertEquals(fingerprintOf("ab"), fingerprintOf("\r\n a \n b \t"))
        assertEquals(fingerprintOf("ab"), fingerprintOf("a b"))
    }

    @Test
    fun `长度是删掉空白之后的字节数`() {
        val spaced = "a\r\nb\r\n\r\n"
        val compact = "ab"
        assertEquals(compact.toByteArray().size, CurseForgeFingerprint.withoutWhitespace(spaced.toByteArray()).size)
        assertEquals(fingerprintOf(compact), fingerprintOf(spaced))
    }

    @Test
    fun `大文件分块读也和小文件一致`() {
        val big = ByteArray(1000) { index -> ((index * 7 + 3) % 256).toByte() }
        assertEquals(3769720441L, CurseForgeFingerprint.of(CurseForgeFingerprint.withoutWhitespace(big)))
    }

    @Test
    fun `结果永远落在无符号范围里`() {
        val seeds = listOf("", "a", "abcd", "xxxxxxxx", "1234567890")
        for (text in seeds) {
            val value = fingerprintOf(text)
            assertTrue(value >= 0L, text + " 算出了负数：" + value)
            assertTrue(value <= 0xFFFFFFFFL, text + " 超出 32 位：" + value)
        }
    }

    @Test
    fun `文件与字节数组算出来一样`() {
        val text = "first line\nsecond line\r\n\tthird\n"
        val file = fileOf("a.txt", text)
        assertEquals(fingerprintOf(text), CurseForgeFingerprint.of(file))
    }

    @Test
    fun `同样的内容换个文件名指纹不变`() {
        val text = "identical content".repeat(100)
        assertEquals(CurseForgeFingerprint.of(fileOf("a.jar", text)), CurseForgeFingerprint.of(fileOf("b.jar", text)))
    }

    @Test
    fun `只多一个空白不会改变指纹`() {
        val base = "mono: 1.2.3"
        assertEquals(fingerprintOf(base), CurseForgeFingerprint.of(fileOf("x.json", base + "\r\n")))
    }

    @Test
    fun `改一个字符指纹就变`() {
        assertTrue(fingerprintOf("mono:1.2.3") != fingerprintOf("mono:1.2.4"))
    }

    @Test
    fun `二进制内容也能算且不抛`() {
        val bytes = ByteArray(512) { index -> (index % 256).toByte() }
        val value = CurseForgeFingerprint.of(bytes)
        assertTrue(value >= 0L && value <= 0xFFFFFFFFL)
        assertEquals(value, CurseForgeFingerprint.of(fileOf("a.bin", String(bytes, Charsets.ISO_8859_1))))
    }

    @Test
    fun `读不了的文件给出中文原因`() {
        val missing = File(root, "not-there.jar")
        val error = runCatching { CurseForgeFingerprint.of(missing) }.exceptionOrNull()
        assertTrue(error is java.io.IOException, "应该是 IOException，实际是 " + error)
        assertTrue(error!!.message!!.startsWith("读不了 not-there.jar"), error.message!!)
    }
}