package io.github.huang404yan.platypus.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 探测输出解析：输入是本机 JDK25 与几个常见发行版的真实输出片段。
 */
class JavaProbeParserTest {

    /** 本机 JDK25 的真实输出（节选，行内容原样保留）。 */
    private val jdk25Output = """
        Property settings:
            java.home = D:/ruanjian/kaifa/java/25.0.5.0.3
            java.runtime.version = 25.0.5.0.3+1-LTS-2
            java.specification.version = 25
            java.vendor = Oracle Corporation
            java.version = 25.0.5.0.3
            java.version.date = 2026-10-25
            os.arch = amd64
            sun.arch.data.model = 64

        openjdk version "25.0.5.0.3" 2026-10-25 LTS
        OpenJDK Runtime Environment (build 25.0.5.0.3+1-LTS-2)
    """.trimIndent()

    /** 老 JDK8：版本号是 1.8.0_xxx 形态，java.version 与 specification 都是 1.8。 */
    private val jdk8Output = """
        Property settings:
            java.home = C:/Program Files/Java/jre1.8.0_401
            java.runtime.version = 1.8.0_401-b10
            java.specification.version = 1.8
            java.vendor = Oracle Corporation
            java.version = 1.8.0_401
            os.arch = x86
            sun.arch.data.model = 32

        java version "1.8.0_401"
    """.trimIndent()

    /** Adoptium 的 17：java.vendor 是 Eclipse Adoptium，arch 是 aarch64。 */
    private val adoptium17Output = """
        Property settings:
            java.home = C:/Program Files/Eclipse Adoptium/jdk-17.0.10.7-hotspot
            java.vendor = Eclipse Adoptium
            java.version = 17.0.10
            os.arch = aarch64
            sun.arch.data.model = 64

        openjdk version "17.0.10" 2024-01-16
    """.trimIndent()

    @Test
    fun `解析本机 JDK25 四段版本号只取第一段`() {
        val java = JavaProbeParser.parse(jdk25Output, "D:/ruanjian/kaifa/java/25.0.5.0.3/bin/java.exe")
        assertNotNull(java)
        assertEquals(25, java.majorVersion)
        assertEquals("Oracle Corporation", java.vendor)
        assertEquals("amd64", java.arch)
        assertEquals(64, java.bits)
        assertEquals("D:/ruanjian/kaifa/java/25.0.5.0.3/bin/java.exe", java.path)
        assertEquals(false, java.managed)
    }

    @Test
    fun `JDK8 的 1点8 记作主版本 8 并识别 32 位`() {
        val java = JavaProbeParser.parse(jdk8Output, "C:/Program Files/Java/jre1.8.0_401/bin/java.exe")
        assertNotNull(java)
        assertEquals(8, java.majorVersion)
        assertEquals(32, java.bits)
        assertEquals("x86", java.arch)
    }

    @Test
    fun `aarch64 也能标出 64 位`() {
        val java = JavaProbeParser.parse(adoptium17Output, "C:/jdk-17/bin/java.exe")
        assertNotNull(java)
        assertEquals(17, java.majorVersion)
        assertEquals("Eclipse Adoptium", java.vendor)
        assertEquals(64, java.bits)
    }

    @Test
    fun `只有 java -version 一行时也能解析出版本`() {
        val output = """
            java version "1.7.0_80"
            Java(TM) SE Runtime Environment (build 1.7.0_80-b15)
        """.trimIndent()
        val java = JavaProbeParser.parse(output, "C:/jre7/bin/java.exe")
        assertNotNull(java)
        assertEquals(7, java.majorVersion)
        assertNull(java.vendor)
        assertNull(java.arch)
        assertNull(java.bits)
    }

    @Test
    fun `输出不是 Java 版本信息时返回 null 而不是猜测`() {
        assertNull(JavaProbeParser.parse("", "C:/jdk/bin/java.exe"))
        assertNull(JavaProbeParser.parse("   ", "C:/jdk/bin/java.exe"))
        assertNull(JavaProbeParser.parse("'java' 不是内部或外部命令", "C:/bad/java.exe"))
        assertNull(JavaProbeParser.parse("Error: could not open jvm.cfg", "C:/jdk/bin/java.exe"))
    }

    @Test
    fun `缺 vendor 与 arch 时不写脏值`() {
        val output = """
            Property settings:
                java.version = 21.0.5
        """.trimIndent()
        val java = JavaProbeParser.parse(output, "C:/jdk21/bin/java.exe")
        assertNotNull(java)
        assertEquals(21, java.majorVersion)
        assertNull(java.vendor)
        assertNull(java.arch)
        assertNull(java.bits)
    }

    @Test
    fun `java home 能取出来用于去重`() {
        assertEquals(
            "D:/ruanjian/kaifa/java/25.0.5.0.3",
            JavaProbeParser.javaHome(jdk25Output),
        )
        assertNull(JavaProbeParser.javaHome("nothing here"))
    }

    @Test
    fun `parseMajor 覆盖各种版本号写法`() {
        assertEquals(25, JavaProbeParser.parseMajor("25.0.5.0.3"))
        assertEquals(17, JavaProbeParser.parseMajor("17.0.10"))
        assertEquals(21, JavaProbeParser.parseMajor("21"))
        assertEquals(8, JavaProbeParser.parseMajor("1.8.0_401"))
        assertEquals(7, JavaProbeParser.parseMajor("1.7"))
        assertEquals(25, JavaProbeParser.parseMajor("25.0.5.0.3+1-LTS-2"))
        assertEquals(17, JavaProbeParser.parseMajor("\"17\""))
        assertNull(JavaProbeParser.parseMajor(""))
        assertNull(JavaProbeParser.parseMajor("abc"))
    }

    @Test
    fun `bits 在只有 os arch 时按架构推断`() {
        assertEquals(64, JavaProbeParser.bits(null, "amd64"))
        assertEquals(64, JavaProbeParser.bits(null, "x86_64"))
        assertEquals(64, JavaProbeParser.bits(null, "aarch64"))
        assertEquals(32, JavaProbeParser.bits(null, "x86"))
        assertEquals(32, JavaProbeParser.bits(null, "i386"))
        assertEquals(32, JavaProbeParser.bits(null, "arm"))
        assertNull(JavaProbeParser.bits(null, null))
        assertNull(JavaProbeParser.bits(null, "mips"))
        // sun.arch.data.model 优先于 os.arch
        assertEquals(64, JavaProbeParser.bits("64", "x86"))
    }
}
