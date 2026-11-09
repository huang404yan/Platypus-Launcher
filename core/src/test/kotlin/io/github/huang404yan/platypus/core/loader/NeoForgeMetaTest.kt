package io.github.huang404yan.platypus.core.loader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NeoForge 元数据的纯逻辑测试。
 *
 * 重点在 [NeoForgeMeta.guessMinecraftVersion]：这是唯一一处「靠版本号猜 MC 版本」的启发式，
 * 猜错会让用户白跑几分钟安装器，所以用实测过的 10 个真实版本号逐条钉住。
 * 另外覆盖 maven-metadata.xml 的解析与挑选，以及 installer jar 内 inheritsFrom 的读取。
 */
class NeoForgeMetaTest {

    // ── 版本号 → MC 版本（对实测样本全部成立）────────────────────────────────
    // 样本取自 bmclapi2 的 maven-metadata.xml，inheritsFrom 由安装器 jar 内 version.json 实测取得。

    @Test
    fun `1_20 世代的版本号补 1_ 前缀`() {
        assertEquals("1.20.2", NeoForgeMeta.guessMinecraftVersion("20.2.93"))
        assertEquals("1.20.3", NeoForgeMeta.guessMinecraftVersion("20.3.8-beta"))
        assertEquals("1.20.4", NeoForgeMeta.guessMinecraftVersion("20.4.253"))
    }

    @Test
    fun `1_21 世代去掉末段 0 再补前缀`() {
        assertEquals("1.21.1", NeoForgeMeta.guessMinecraftVersion("21.1.265"))
        assertEquals("1.21.3", NeoForgeMeta.guessMinecraftVersion("21.3.100"))
        assertEquals("1.21.11", NeoForgeMeta.guessMinecraftVersion("21.11.48"))
        // 21.0.167 实测 inheritsFrom = 1.21（不是 1.21.0）：中间的 0 段要去掉
        assertEquals("1.21", NeoForgeMeta.guessMinecraftVersion("21.0.167"))
    }

    @Test
    fun `26 世代不再带 1_ 前缀`() {
        // 实测 inheritsFrom：26.1.2 / 26.2 / 26.3
        assertEquals("26.1.2", NeoForgeMeta.guessMinecraftVersion("26.1.2.126"))
        assertEquals("26.2", NeoForgeMeta.guessMinecraftVersion("26.2.0.93"))
        assertEquals("26.3", NeoForgeMeta.guessMinecraftVersion("26.3.0.101"))
    }

    @Test
    fun `认不出的版本号返回 null 而不是瞎猜`() {
        assertNull(NeoForgeMeta.guessMinecraftVersion(""))
        assertNull(NeoForgeMeta.guessMinecraftVersion("21"))
        assertNull(NeoForgeMeta.guessMinecraftVersion("21.1"))
        assertNull(NeoForgeMeta.guessMinecraftVersion("0.25w14craftmine.1-beta"))
        assertNull(NeoForgeMeta.guessMinecraftVersion("abc.def.ghi"))
    }

    // ── 坐标与命名 ────────────────────────────────────────────────────────────

    @Test
    fun `安装器坐标只有一段版本号`() {
        assertEquals(
            "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/21.1.265/neoforge-21.1.265-installer.jar",
            NeoForgeMeta.installerUrl("21.1.265"),
        )
        assertEquals("neoforge-21.1.265-installer.jar", NeoForgeMeta.installerFileName("21.1.265"))
    }

    @Test
    fun `版本 id 不带原版号前缀`() {
        // 与 Forge 的 1.20.1-forge-47.4.28 不同：installer 自己就按 neoforge-<版本> 建目录
        assertEquals("neoforge-21.1.265", NeoForgeMeta.versionId("21.1.265"))
        assertEquals("NeoForge 21.1.265", NeoForgeMeta.displayName("21.1.265"))
    }

    @Test
    fun `从版本 id 反解 NeoForge 版本`() {
        assertEquals("21.1.265", NeoForgeMeta.loaderVersionFromId("neoforge-21.1.265"))
        assertEquals("20.3.8-beta", NeoForgeMeta.loaderVersionFromId("neoforge-20.3.8-beta"))
        // 不是本启动器的写法就不认，避免把别的目录名当成 NeoForge
        assertNull(NeoForgeMeta.loaderVersionFromId("21.1.265"))
        assertNull(NeoForgeMeta.loaderVersionFromId("neoforge-abc"))
        assertNull(NeoForgeMeta.loaderVersionFromId("1.21.1-neoforge-21.1.265"))
    }

    @Test
    fun `清单地址与安装器目录`() {
        assertEquals(
            "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/maven-metadata.xml",
            NeoForgeMeta.metadataUrl(),
        )
        val root = File("C:/tmp/mc")
        assertEquals(File(root, "neoforge-installers").absolutePath, NeoForgeMeta.installersDir(root).absolutePath)
        // 与 Forge 共用同一段 launcher_profiles.json 字面量，不能写成两份
        assertEquals(ForgeMeta.LAUNCHER_PROFILES_JSON, NeoForgeMeta.LAUNCHER_PROFILES_JSON)
        assertEquals(ForgeMeta.MAIN_CLASS, NeoForgeMeta.MAIN_CLASS)
    }

    // ── maven-metadata.xml 解析 ───────────────────────────────────────────────

    private val sampleMetadata = """
        <metadata modelVersion="1.1.0">
          <groupId>net.neoforged</groupId>
          <artifactId>neoforge</artifactId>
          <versioning>
            <latest>26.1.2.126</latest>
            <release>26.1.2.126</release>
            <versions>
              <version>20.4.253</version>
              <version>21.0.167</version>
              <version>21.1.265</version>
              <version>21.2.0-beta</version>
              <version>26.3.0.101</version>
              <version>0.25w14craftmine.1-beta</version>
            </versions>
            <lastUpdated>20261109000000</lastUpdated>
          </versioning>
        </metadata>
    """.trimIndent()

    @Test
    fun `解析清单并丢掉认不出 MC 版本的条目`() {
        val parsed = parseNeoForgeMetadata(sampleMetadata)
        val list = assertNotNull(parsed.getOrNull(), parsed.reasonOrNull())
        // 6 条里 0.25w14craftmine.1-beta 认不出，剩 5 条
        assertEquals(5, list.size)
        assertEquals(
            listOf("20.4.253", "21.0.167", "21.1.265", "21.2.0-beta", "26.3.0.101"),
            list.map { it.version },
        )
        assertEquals("1.20.4", list.first().minecraftVersion)
        // beta 标成预发布，正式版标稳定
        assertFalse(list.first { it.version == "21.2.0-beta" }.stable)
        assertTrue(list.first { it.version == "21.1.265" }.stable)
        assertEquals("预发布", list.first { it.version == "21.2.0-beta" }.label)
        assertEquals("稳定", list.first { it.version == "21.1.265" }.label)
    }

    @Test
    fun `空响应与非 maven 内容给出中文原因`() {
        val empty = parseNeoForgeMetadata("")
        val emptyReason = assertNotNull(empty.reasonOrNull())
        assertTrue(emptyReason.contains("空"), emptyReason)

        val html = parseNeoForgeMetadata("<html>404</html>")
        val htmlReason = assertNotNull(html.reasonOrNull())
        assertTrue(htmlReason.contains("maven-metadata"), htmlReason)
    }

    @Test
    fun `清单里一条都认不出时报错而不是空列表`() {
        val text = "<metadata><versioning><versions><version>0.25w14craftmine.1-beta</version>" +
            "</versions></versioning></metadata>"
        val parsed = parseNeoForgeMetadata(text)
        assertNull(parsed.getOrNull())
        assertTrue(assertNotNull(parsed.reasonOrNull()).contains("没有一条"))
    }

    // ── 挑选：稳定优先，取清单里靠后的那个 ────────────────────────────────────

    @Test
    fun `挑版本时优先稳定版`() {
        val list = assertNotNull(parseNeoForgeMetadata(sampleMetadata).getOrNull())
        // 21.1 只有 21.1.265 一条
        assertEquals("21.1.265", pickNeoForgeForMinecraft(list, "1.21.1")?.version)
        // 1.21.2 只有 beta，退而求其次也要能装
        assertEquals("21.2.0-beta", pickNeoForgeForMinecraft(list, "1.21.2")?.version)
        // 没有对应版本就给 null，界面要如实说「不支持」
        assertNull(pickNeoForgeForMinecraft(list, "1.19.2"))
    }

    @Test
    fun `同一 MC 版本有多个稳定版时取清单里最后一个`() {
        val text = """
            <metadata><versioning><versions>
              <version>21.1.100</version>
              <version>21.1.200</version>
              <version>21.1.150</version>
            </versions></versioning></metadata>
        """.trimIndent()
        val list = assertNotNull(parseNeoForgeMetadata(text).getOrNull())
        // maven-metadata 的 <versions> 是按发布先后排的，取最后一条即最新
        assertEquals("21.1.150", pickNeoForgeForMinecraft(list, "1.21.1")?.version)
    }

    // ── installer jar 内 inheritsFrom ─────────────────────────────────────────

    @Test
    fun `从 installer jar 读 inheritsFrom`() {
        val jar = File.createTempFile("neoforge-fake", ".jar")
        try {
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zip.write("Manifest-Version: 1.0\r\n".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(NeoForgeMeta.INSTALLER_VERSION_JSON))
                zip.write("""{"id":"neoforge-21.1.265","inheritsFrom":"1.21.1"}""".toByteArray())
                zip.closeEntry()
            }
            assertEquals("1.21.1", NeoForgeMeta.readInheritsFrom(jar))
        } finally {
            jar.delete()
        }
    }

    @Test
    fun `不是 jar 或没写 inheritsFrom 时返回 null`() {
        val notJar = File.createTempFile("neoforge-broken", ".jar")
        try {
            notJar.writeText("this is not a zip")
            assertNull(NeoForgeMeta.readInheritsFrom(notJar))
        } finally {
            notJar.delete()
        }
        assertNull(NeoForgeMeta.readInheritsFrom(File("C:/tmp/does-not-exist-1234567.jar")))

        val noField = File.createTempFile("neoforge-nofield", ".jar")
        try {
            ZipOutputStream(noField.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(NeoForgeMeta.INSTALLER_VERSION_JSON))
                zip.write("""{"id":"neoforge-x"}""".toByteArray())
                zip.closeEntry()
            }
            assertNull(NeoForgeMeta.readInheritsFrom(noField))
        } finally {
            noField.delete()
        }
    }
}
