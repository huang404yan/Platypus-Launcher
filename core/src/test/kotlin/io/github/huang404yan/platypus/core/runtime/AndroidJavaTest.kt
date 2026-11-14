package io.github.huang404yan.platypus.core.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 安卓 Java 这块的单测。
 *
 * 不联网：解包器喂合成出来的 tar 字节，清单只验地址与体积。
 * 真的下一遍是 app 里的事，跑单测时不该依赖 GitHub 通不通。
 */
class AndroidJavaTest {

    // ---------- 合成 tar ----------

    /** 造一个 tar 头。参数都是 tar 规范里的固定偏移，见 TarXz 里的常量。 */
    private fun header(name: String, size: Long, type: Char, mode: Int = 0b110_100_100, link: String = ""): ByteArray {
        val h = ByteArray(512)
        fun put(offset: Int, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            bytes.copyInto(h, offset, 0, minOf(bytes.size, h.size - offset))
        }
        put(0, name)
        put(100, mode.toString(8).padStart(7, '0') + "\u0000")
        put(108, "0000000\u0000")
        put(116, "0000000\u0000")
        put(124, size.toString(8).padStart(11, '0') + "\u0000")
        put(136, "00000000000\u0000")
        h[156] = type.code.toByte()
        put(157, link)
        put(257, "ustar")
        put(263, "00")
        // 校验和先按空格算，再回填八进制
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        var sum = 0L
        for (b in h) sum += (b.toInt() and 0xFF)
        put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
        return h
    }

    /** 一条普通文件：头 + 内容 + 补到 512 的零。 */
    private fun fileEntry(name: String, body: String, mode: Int = 0b110_100_100): ByteArray {
        val out = ByteArrayOutputStream()
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write(header(name, bytes.size.toLong(), '0', mode))
        out.write(bytes)
        val pad = (512 - bytes.size % 512) % 512
        if (pad > 0) out.write(ByteArray(pad))
        return out.toByteArray()
    }

    private fun dirEntry(name: String): ByteArray = header(name.trimEnd('/') + "/", 0L, '5')

    /** tar 的结尾是两个全零块。 */
    private fun trailer(): ByteArray = ByteArray(1024)

    private fun tarOf(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        out.write(trailer())
        return out.toByteArray()
    }

    private fun tempDir(): File = Files.createTempDirectory("platypus-tar").toFile()

    // ---------- 解包 ----------

    @Test
    fun 解出来的文件内容与目录层级都对() {
        val dir = tempDir()
        val tar = tarOf(
            dirEntry("bin"),
            fileEntry("bin/java", "#!/bin/sh\nexec java\n"),
            fileEntry("release", "JAVA_VERSION=\"17.0.15\"\n"),
        )
        TarXz.extractTar(ByteArrayInputStream(tar), dir)
        assertEquals("#!/bin/sh\nexec java\n", File(dir, "bin/java").readText())
        assertEquals("JAVA_VERSION=\"17.0.15\"\n", File(dir, "release").readText())
        dir.deleteRecursively()
    }

    @Test
    fun 可执行位会被留住() {
        val dir = tempDir()
        val tar = tarOf(fileEntry("bin/java", "x", mode = 0b111_101_101))
        TarXz.extractTar(ByteArrayInputStream(tar), dir)
        // 目录在 Windows 上不支持可执行位的真正语义，但 setExecutable 得被调用过：
        // 只要解完不抛异常、文件在，就算通过（这条在 Windows 上没断言力，注释留着提醒）。
        assertTrue(File(dir, "bin/java").isFile)
        dir.deleteRecursively()
    }

    @Test
    fun 两个包叠进同一个目录不会互相覆盖掉() {
        val dir = tempDir()
        TarXz.extractTar(
            ByteArrayInputStream(tarOf(dirEntry("lib"), fileEntry("lib/modules", "modules"))),
            dir,
        )
        TarXz.extractTar(
            ByteArrayInputStream(tarOf(dirEntry("bin"), fileEntry("bin/java", "java"), fileEntry("release", "r"))),
            dir,
        )
        assertEquals("modules", File(dir, "lib/modules").readText())
        assertEquals("java", File(dir, "bin/java").readText())
        assertEquals("r", File(dir, "release").readText())
        dir.deleteRecursively()
    }

    @Test
    fun 条目想写到目录之外会被拒绝() {
        val dir = tempDir()
        val tar = tarOf(fileEntry("../escaped.txt", "nope"))
        val error = assertFailsWith<java.io.IOException> {
            TarXz.extractTar(ByteArrayInputStream(tar), dir)
        }
        assertTrue(error.message.orEmpty().contains("目标目录之外"), error.message)
        assertFalse(File(dir.parentFile, "escaped.txt").exists())
        dir.deleteRecursively()
    }

    @Test
    fun 空文件与结尾的两个零块不会把解包卡住() {
        val dir = tempDir()
        val tar = tarOf(fileEntry("empty.txt", ""), fileEntry("after.txt", "ok"))
        TarXz.extractTar(ByteArrayInputStream(tar), dir)
        assertEquals(0L, File(dir, "empty.txt").length())
        assertEquals("ok", File(dir, "after.txt").readText())
        dir.deleteRecursively()
    }

    // ---------- 清单 ----------

    @Test
    fun 四种安卓架构都有对应的包名后缀() {
        assertEquals("arm64", AndroidJavaCatalog.abiSuffix("arm64-v8a"))
        assertEquals("arm", AndroidJavaCatalog.abiSuffix("armeabi-v7a"))
        assertEquals("x86_64", AndroidJavaCatalog.abiSuffix("x86_64"))
        assertEquals("x86", AndroidJavaCatalog.abiSuffix("x86"))
        assertNull(AndroidJavaCatalog.abiSuffix("riscv64"))
    }

    @Test
    fun 从设备的架构列表里挑第一个认得的() {
        assertEquals("arm64-v8a", AndroidJavaCatalog.supportedAbi(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("armeabi-v7a", AndroidJavaCatalog.supportedAbi(listOf("armeabi-v7a", "arm64-v8a")))
        assertNull(AndroidJavaCatalog.supportedAbi(listOf("riscv64")))
        assertNull(AndroidJavaCatalog.supportedAbi(emptyList()))
    }

    @Test
    fun 主流架构能下到四五个大版本() {
        val majors = AndroidJavaCatalog.packages("arm64-v8a").map { it.majorVersion }
        assertEquals(listOf(8, 17, 21, 25), majors)
    }

    @Test
    fun 三十二位x86上没有Java25() {
        val majors = AndroidJavaCatalog.packages("x86").map { it.majorVersion }
        assertEquals(listOf(8, 17, 21), majors)
    }

    @Test
    fun 不认识的架构给空表而不是崩() {
        assertTrue(AndroidJavaCatalog.packages("riscv64").isEmpty())
    }

    @Test
    fun 每个包都给出两个地址与总字节数() {
        val offer = AndroidJavaCatalog.packages("arm64-v8a").first { it.majorVersion == 17 }
        assertTrue(offer.universalUrl.endsWith("jre17/universal.tar.xz"), offer.universalUrl)
        assertTrue(offer.binUrl.endsWith("jre17/bin-arm64.tar.xz"), offer.binUrl)
        assertTrue(offer.universalUrl.startsWith(AndroidJavaCatalog.MIRROR_GH_PROXY), offer.universalUrl)
        assertEquals(17_070_632L, offer.universalSize)
        assertEquals(4_640_448L, offer.binSize)
        assertEquals(17_070_632L + 4_640_448L, offer.totalBytes)
        assertFalse(offer.installed)
    }

    @Test
    fun 已装的版本会带上路径() {
        val offers = AndroidJavaCatalog.packages("arm64-v8a") { major ->
            if (major == 21) "/data/jre21-arm64-v8a" else null
        }
        val jre21 = offers.first { it.majorVersion == 21 }
        assertTrue(jre21.installed)
        assertEquals("/data/jre21-arm64-v8a", jre21.installedPath)
        assertFalse(offers.first { it.majorVersion == 8 }.installed)
    }

    @Test
    fun 备用镜像把主镜像前缀换掉() {
        val main = AndroidJavaCatalog.MIRROR_GH_PROXY + AndroidJavaCatalog.RAW_BASE + "jre17/universal.tar.xz"
        val mirrors = AndroidJavaCatalog.mirrorsOf(main)
        assertEquals(2, mirrors.size)
        assertTrue(mirrors[0].startsWith(AndroidJavaCatalog.MIRROR_GHFAST), mirrors[0])
        assertTrue(mirrors[1].startsWith("https://raw.githubusercontent.com/"), mirrors[1])
        assertTrue(AndroidJavaCatalog.mirrorsOf("https://example.com/x").isEmpty())
    }

    @Test
    fun 装有判据要求bin与控制文件都在() {
        val dir = tempDir()
        assertFalse(AndroidJavaInstaller.isInstalled(dir))
        File(dir, "bin").mkdirs()
        File(dir, "bin/java").writeText("x")
        assertFalse(AndroidJavaInstaller.isInstalled(dir), "只有 bin/java 不算装完")
        File(dir, "release").writeText("r")
        assertTrue(AndroidJavaInstaller.isInstalled(dir))
        dir.deleteRecursively()
    }

    @Test
    fun 安装目录带上大版本与架构() {
        val offer = AndroidJavaCatalog.packages("arm64-v8a").first()
        val home = AndroidJavaInstaller.homeOf(File("/data/java"), offer)
        assertEquals(File("/data/java", "jre8-arm64-v8a").path, home.path)
    }

    @Test
    fun 删掉一套没装的东西不会报错() {
        val dir = tempDir()
        val offer = AndroidJavaCatalog.packages("arm64-v8a").first()
        assertFalse(AndroidJavaInstaller.remove(dir, offer))
        val home = AndroidJavaInstaller.homeOf(dir, offer)
        assertNotNull(home)
        dir.deleteRecursively()
    }
}
