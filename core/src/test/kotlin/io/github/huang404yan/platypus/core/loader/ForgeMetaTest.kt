package io.github.huang404yan.platypus.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File

class ForgeMetaTest {

    /** 实测响应形状（2026-11-09 抓的 promotions_slim.json，4153 字节，截取相关键）。 */
    private val promotions = """
        {
          "homepage": "https://files.minecraftforge.net/",
          "promos": {
            "1.20.1-recommended": "47.4.28",
            "1.20.1-latest": "47.4.29",
            "1.20.4-recommended": "49.2.13",
            "1.20.4-latest": "49.2.13",
            "1.20.6-latest": "50.2.15",
            "1.7.10-latest": "10.13.4.1614"
          }
        }
    """.trimIndent()

    @Test
    fun `推荐与最新都给`() {
        val parsed = parseForgePromotions(promotions, "1.20.1")
        val versions = assertNotNull(parsed.getOrNull(), "应能解析出 1.20.1 的版本")
        assertEquals(2, versions.size)
        assertEquals("47.4.28", versions[0].version)
        assertTrue(versions[0].recommended, "第一条应是 recommended")
        assertEquals("47.4.29", versions[1].version)
        assertFalse(versions[1].recommended, "第二条应是 latest")
    }

    /** 推荐 == 最新时只留一条，避免界面出现两个一模一样的选项。 */
    @Test
    fun `推荐与最新相同时只留一条`() {
        val versions = assertNotNull(parseForgePromotions(promotions, "1.20.4").getOrNull())
        assertEquals(1, versions.size)
        assertEquals("49.2.13", versions[0].version)
        assertTrue(versions[0].recommended)
    }

    /** 新版本刚发布时常常只有 latest，没有 recommended。 */
    @Test
    fun `只有最新没有推荐也能用`() {
        val versions = assertNotNull(parseForgePromotions(promotions, "1.20.6").getOrNull())
        assertEquals(1, versions.size)
        assertEquals("50.2.15", versions[0].version)
        assertFalse(versions[0].recommended)
        assertEquals("最新", versions[0].label)
    }

    @Test
    fun `老版本只认自己的号段`() {
        val versions = assertNotNull(parseForgePromotions(promotions, "1.7.10").getOrNull())
        assertEquals("10.13.4.1614", versions.single().version)
    }

    @Test
    fun `没有这个原版版本时给出中文原因`() {
        val parsed = parseForgePromotions(promotions, "1.0.0")
        assertNull(parsed.getOrNull())
        val reason = assertNotNull(parsed.reasonOrNull())
        assertTrue(reason.contains("1.0.0"), "原因里应带上问的版本号，实际是：$reason")
        assertTrue(reason.contains("没有为"), "原因应说明没有发布，实际是：$reason")
    }

    @Test
    fun `空响应与坏 json 都不崩`() {
        assertNotNull(parseForgePromotions("", "1.20.1").reasonOrNull())
        assertNotNull(parseForgePromotions("{ 不是 json", "1.20.1").reasonOrNull())
        assertNotNull(parseForgePromotions("{\"foo\":1}", "1.20.1").reasonOrNull())
    }

    /** 实测响应形状（2026-11-16 抓的 maven-metadata.xml，213750 字节，截取相关条目）。 */
    private val metadata = """
        <metadata>
          <groupId>net.minecraftforge</groupId>
          <artifactId>forge</artifactId>
          <versioning>
            <latest>26.3-66.1.8</latest>
            <release>26.3-66.1.8</release>
            <versions>
              <version>26.3-66.1.8</version>
              <version>1.21-51.0.33</version>
              <version>1.20.4-49.2.13</version>
              <version>1.20.1-47.4.29</version>
              <version>1.20.1-47.4.28</version>
              <version>1.20.1-47.4.10</version>
              <version>1.19.2-43.3.0</version>
            </versions>
          </versioning>
        </metadata>
    """.trimIndent()

    /** maven 清单混着所有原版版本，只挑出问的那一个。 */
    @Test
    fun `全量清单只留指定原版`() {
        val versions = assertNotNull(parseForgeVersions(metadata, "1.20.1").getOrNull())
        assertEquals(listOf("47.4.29", "47.4.28", "47.4.10"), versions.map { it.version })
        assertTrue(versions.none { it.recommended || it.latest }, "没传 promotions 时谁都不打标签")
    }

    /** 推荐与最新顶到前面，其余按 maven 原顺序（新的在前）跟上。 */
    @Test
    fun `推荐与最新排在最前`() {
        val promotions = listOf(
            ForgeVersion("47.4.28", recommended = true),
            ForgeVersion("47.4.29", latest = true),
        )
        val versions = assertNotNull(parseForgeVersions(metadata, "1.20.1", promotions).getOrNull())
        assertEquals(listOf("47.4.28", "47.4.29", "47.4.10"), versions.map { it.version })
        assertEquals("推荐", versions[0].label)
        assertEquals("最新", versions[1].label)
        assertEquals("", versions[2].label)
    }

    /** promotions 里说是推荐、但 maven 清单里没有这个号时不能凭空造一条出来。 */
    @Test
    fun `推荐版不在清单里就跳过`() {
        val promotions = listOf(ForgeVersion("47.9.9", recommended = true))
        val versions = assertNotNull(parseForgeVersions(metadata, "1.20.1", promotions).getOrNull())
        assertEquals(listOf("47.4.29", "47.4.28", "47.4.10"), versions.map { it.version })
    }

    @Test
    fun `maven 清单没有这个原版时给出中文原因`() {
        val reason = assertNotNull(parseForgeVersions(metadata, "1.0.0").reasonOrNull())
        assertTrue(reason.contains("1.0.0"), "原因里应带上问的版本号，实际是：$reason")
        assertNotNull(parseForgeVersions("", "1.20.1").reasonOrNull())
    }

    /** installer 的 maven 坐标是 <原版号>-<forge 版本> 拼成的单段版本，不是两段。 */
    @Test
    fun `安装器坐标`() {
        assertEquals(
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.0/" +
                "forge-1.20.1-47.4.0-installer.jar",
            ForgeMeta.installerUrl("1.20.1", "47.4.0"),
        )
        assertEquals("forge-1.20.1-47.4.0-installer.jar", ForgeMeta.installerFileName("1.20.1", "47.4.0"))
        assertEquals("1.20.1-47.4.0", ForgeMeta.artifactVersion("1.20.1", "47.4.0"))
    }

    @Test
    fun `版本号与展示名`() {
        assertEquals("1.20.1-forge-47.4.0", ForgeMeta.versionId("1.20.1", "47.4.0"))
        assertEquals("Forge 47.4.0 / 1.20.1", ForgeMeta.displayName("1.20.1", "47.4.0"))
        assertEquals(File("C:/mc/forge-installers"), ForgeMeta.installersDir(File("C:/mc")))
    }

    /** 反解版本号：认得本启动器/官方 installer 的写法，也认得少数第三方启动器的反过来写法。 */
    @Test
    fun `从版本 id 反解 forge 版本`() {
        assertEquals("47.4.0", ForgeMeta.loaderVersionFromId("1.20.1-forge-47.4.0"))
        assertEquals("49.2.13", ForgeMeta.loaderVersionFromId("1.20.4-forge-49.2.13"))
        assertEquals("10.13.4.1614", ForgeMeta.loaderVersionFromId("1.7.10-forge-10.13.4.1614"))
        assertEquals("47.4.0", ForgeMeta.loaderVersionFromId("forge-47.4.0-1.20.1"))
        // 原版号（不带 forge）不能被误认成加载器版本
        assertNull(ForgeMeta.loaderVersionFromId("1.20.1"))
        assertNull(ForgeMeta.loaderVersionFromId("1.20.1-fabric-0.16.14"))
    }

    /** installer 必须先看到 launcher_profiles.json，否则会以退出码 0 假装成功。 */
    @Test
    fun `空启动器档案是合法 json 且含 version`() {
        val text = ForgeMeta.LAUNCHER_PROFILES_JSON
        assertTrue(text.contains("\"profiles\""), "要有 profiles 字段：$text")
        assertTrue(text.contains("\"version\":3"), "installer 会读 version 字段：$text")
    }
}
