package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.instance.LoaderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModrinthMetaTest {

    /** 2026-11-09 实测抓的 /v2/search?query=sodium 响应（截取相关字段）。 */
    private val searchResponse = """
        {
          "hits": [
            {
              "slug": "sodium",
              "title": "Sodium",
              "description": "Modern rendering engine and client-side optimization mod",
              "author": "jellysquid3",
              "downloads": 250402653,
              "project_id": "AANobbMI",
              "categories": ["fabric", "optimization"]
            },
            {
              "slug": "sodium-extra",
              "title": "Sodium Extra",
              "description": "Features that shouldn't be in Sodium.",
              "author": "FlashyReese",
              "downloads": 106107037,
              "project_id": "PtjQE1Ec"
            }
          ],
          "offset": 0,
          "limit": 2,
          "total_hits": 41
        }
    """.trimIndent()

    /** 2026-11-09 实测抓的 /v2/project/sodium/version?game_versions=["1.20.1"]&loaders=["fabric"] 响应。 */
    private val versionsResponse = """
        [
          {
            "id": "OihdIimA",
            "project_id": "AANobbMI",
            "name": "Sodium 0.5.13 for Fabric",
            "version_number": "mc1.20.1-0.5.13-fabric",
            "version_type": "release",
            "game_versions": ["1.20.1"],
            "loaders": ["fabric", "quilt"],
            "dependencies": [],
            "files": [
              {
                "hashes": {"sha1": "bcdbf37d94941e0b52fb21bf51b8a1b74ab4c1c1", "sha512": "aa"},
                "url": "https://cdn.modrinth.com/data/AANobbMI/versions/OihdIimA/sodium-fabric-0.5.13%2Bmc1.20.1.jar",
                "filename": "sodium-fabric-0.5.13+mc1.20.1.jar",
                "primary": true,
                "size": 971552
              },
              {
                "hashes": {"sha1": "0000"},
                "url": "https://cdn.modrinth.com/sources.jar",
                "filename": "sodium-sources.jar",
                "primary": false,
                "size": 100
              }
            ]
          },
          {
            "id": "BetaOne",
            "version_number": "mc1.20.1-0.6.0-beta.1",
            "version_type": "beta",
            "game_versions": ["1.20.1"],
            "dependencies": [
              {"project_id": "P7dR8mSH", "dependency_type": "required"},
              {"project_id": "Ha28R6CL", "dependency_type": "optional"}
            ],
            "files": [
              {
                "hashes": {"sha1": "1111111111111111111111111111111111111111"},
                "url": "https://cdn.modrinth.com/beta.jar",
                "filename": "sodium-0.6.0-beta.1.jar",
                "primary": true,
                "size": 980000
              }
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun `解析搜索结果`() {
        val parsed = ModrinthMeta.parseSearch(searchResponse)
        val hits = assertNotNull(parsed.getOrNull(), "应能解析出 hits")
        assertEquals(2, hits.size)
        assertEquals("sodium", hits[0].slug)
        assertEquals("Sodium", hits[0].title)
        assertEquals(250402653L, hits[0].downloads)
        assertEquals("AANobbMI", hits[0].projectId)
        assertTrue(hits[0].description.contains("optimization"))
    }

    @Test
    fun `下载量走中文单位`() {
        assertEquals("2.5 亿", ModrinthMeta.formatCount(250402653))
        assertEquals("1 亿", ModrinthMeta.formatCount(100_000_000))
        assertEquals("1061 万", ModrinthMeta.formatCount(10610703))
        assertEquals("9999", ModrinthMeta.formatCount(9999))
    }

    @Test
    fun `解析版本列表并只取 primary 文件`() {
        val parsed = ModrinthMeta.parseVersions(versionsResponse, "sodium")
        val versions = assertNotNull(parsed.getOrNull())
        assertEquals(2, versions.size)
        val first = versions[0]
        assertEquals("sodium-fabric-0.5.13+mc1.20.1.jar", first.fileName, "应跳过 sources jar")
        assertEquals(971552L, first.size)
        assertEquals("bcdbf37d94941e0b52fb21bf51b8a1b74ab4c1c1", first.sha1)
        assertEquals("release", first.releaseType)
        assertEquals("sodium", first.projectSlug)
        assertTrue(first.url.startsWith("https://cdn.modrinth.com/"))
    }

    @Test
    fun `版本里的 required 依赖才收进列表`() {
        val versions = assertNotNull(ModrinthMeta.parseVersions(versionsResponse, "sodium").getOrNull())
        assertEquals(emptyList(), versions[0].requiredDependencies, "无依赖")
        assertEquals(listOf("P7dR8mSH"), versions[1].requiredDependencies, "optional 不算")
    }

    @Test
    fun `版本里的 incompatible 依赖单独收，不和 required 混`() {
        // 2026-11-14 实测：Embeddium 的 mc1.20.1 版本就是这么写的
        val sample = """
            [
              {
                "id": "V1",
                "version_number": "0.3.31+mc1.20.1",
                "version_type": "release",
                "game_versions": ["1.20.1"],
                "loaders": ["forge"],
                "dependencies": [
                  {"project_id": "4ZqxOvjD", "dependency_type": "incompatible"},
                  {"project_id": "S1tndFDa", "dependency_type": "incompatible"},
                  {"project_id": "9s6osm5g", "dependency_type": "required"},
                  {"project_id": "Ha28R6CL", "dependency_type": "optional"}
                ],
                "files": [
                  {
                    "hashes": {"sha1": "2222222222222222222222222222222222222222"},
                    "url": "https://cdn.modrinth.com/embeddium.jar",
                    "filename": "embeddium-0.3.31+mc1.20.1.jar",
                    "primary": true,
                    "size": 1000
                  }
                ]
              }
            ]
        """.trimIndent()
        val versions = assertNotNull(ModrinthMeta.parseVersions(sample, "embeddium").getOrNull())
        val file = versions.single()
        assertEquals(listOf("4ZqxOvjD", "S1tndFDa"), file.incompatibleDependencies)
        assertEquals(listOf("9s6osm5g"), file.requiredDependencies, "incompatible 不能被当成前置")
    }

    @Test
    fun `反查结果的依赖字段也解析出来`() {
        // /version_files 的响应与 /project/{slug}/version 同形，带 dependencies
        val sample = """
            {
              "aaa111": {
                "id": "V1",
                "project_id": "sk9rgfiA",
                "version_number": "0.3.31+mc1.20.1",
                "dependencies": [
                  {"project_id": "4ZqxOvjD", "dependency_type": "incompatible"}
                ],
                "files": [
                  {"hashes": {"sha1": "aaa111"}, "url": "https://cdn/x.jar", "filename": "x.jar", "primary": true, "size": 10}
                ]
              }
            }
        """.trimIndent()
        val lookups = assertNotNull(ModrinthMeta.parseVersionFiles(sample).getOrNull())
        val lookup = assertNotNull(lookups["aaa111"])
        assertEquals("sk9rgfiA", lookup.projectId)
        assertEquals(listOf("4ZqxOvjD"), lookup.incompatibleDependencies)
        assertEquals(emptyList(), lookup.requiredDependencies)
    }

    @Test
    fun `挑版本优先正式版`() {
        val versions = assertNotNull(ModrinthMeta.parseVersions(versionsResponse, "sodium").getOrNull())
        assertEquals("mc1.20.1-0.5.13-fabric", ModrinthMeta.pickBest(versions)?.versionNumber)
    }

    @Test
    fun `只有 beta 时退而求其次`() {
        val versions = assertNotNull(ModrinthMeta.parseVersions(versionsResponse, "sodium").getOrNull())
        assertEquals("mc1.20.1-0.6.0-beta.1", ModrinthMeta.pickBest(versions.takeLast(1))?.versionNumber)
    }

    @Test
    fun `空版本列表返回 null`() {
        assertNull(ModrinthMeta.pickBest(emptyList()))
    }

    @Test
    fun `坏响应不崩且给中文原因`() {
        val bad = ModrinthMeta.parseSearch("<html>cloudflare</html>")
        assertNull(bad.getOrNull())
        assertNotNull(bad.reasonOrNull())
        val empty = ModrinthMeta.parseSearch("")
        assertNotNull(empty.reasonOrNull())
        val noHits = ModrinthMeta.parseSearch("""{"offset":0}""")
        assertNotNull(noHits.reasonOrNull())
    }

    @Test
    fun `搜索 URL 带上加载器与游戏版本三层 facet`() {
        val url = ModrinthMeta.searchUrl("sodium", "1.20.1", LoaderKind.FABRIC, limit = 20)
        assertTrue(url.startsWith("https://api.modrinth.com/v2/search?query=sodium"), url)
        assertTrue(url.contains("limit=20"))
        // facets 里三层分别是 project_type / categories / versions，编成 %22 引号
        val facets = java.net.URLDecoder.decode(url.substringAfter("facets="), "UTF-8")
        assertTrue(facets.contains("project_type:mod"), facets)
        assertTrue(facets.contains("categories:fabric"), facets)
        assertTrue(facets.contains("versions:1.20.1"), facets)
        assertTrue(facets.startsWith("[["), "必须是数组的数组，否则变成 OR 关系：" + facets)
    }

    @Test
    fun `原版没有加载器 facet`() {
        val url = ModrinthMeta.searchUrl("x", null, LoaderKind.VANILLA)
        val facets = java.net.URLDecoder.decode(url.substringAfter("facets="), "UTF-8")
        assertTrue(!facets.contains("categories:"), facets)
        assertTrue(!facets.contains("versions:"), facets)
    }

    @Test
    fun `版本 URL 同时带 game_versions 与 loaders`() {
        val url = ModrinthMeta.versionsUrl("sodium", "1.20.1", LoaderKind.FABRIC)
        assertTrue(url.startsWith("https://api.modrinth.com/v2/project/sodium/version?"), url)
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("game_versions=[\"1.20.1\"]"), decoded)
        assertTrue(decoded.contains("loaders=[\"fabric\"]"), decoded)
    }

    @Test
    fun `光影搜索的 project_type facet 换成 shader`() {
        val url = ModrinthMeta.searchUrl("complementary", "1.20.1", LoaderKind.VANILLA, projectType = ModrinthMeta.PROJECT_TYPE_SHADER)
        val facets = java.net.URLDecoder.decode(url.substringAfter("facets="), "UTF-8")
        assertTrue(facets.contains("project_type:shader"), facets)
        // 光影没有 fabric/forge 之分，不能带上加载器 facet，否则一条都搜不到
        assertTrue(!facets.contains("categories:"), facets)
    }

    @Test
    fun `资源包搜索用 resourcepack 类型`() {
        val url = ModrinthMeta.searchUrl("fresh", "1.20.1", LoaderKind.VANILLA, projectType = ModrinthMeta.PROJECT_TYPE_RESOURCEPACK)
        val facets = java.net.URLDecoder.decode(url.substringAfter("facets="), "UTF-8")
        assertTrue(facets.contains("project_type:resourcepack"), facets)
    }

    @Test
    fun `直接给 loaders 列表时不再走加载器枚举`() {
        // 光影在 Modrinth 上标的是 iris / optifine，不是 fabric
        val url = ModrinthMeta.versionsUrl("complementary-reimagined", "1.20.1", LoaderKind.VANILLA, listOf("iris", "optifine"))
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("loaders=[\"iris\",\"optifine\"]"), decoded)
        assertTrue(!decoded.contains("fabric"), decoded)
    }

    @Test
    fun `不传 loaders 时仍按加载器枚举拼`() {
        val url = ModrinthMeta.versionsUrl("sodium", "1.20.1", LoaderKind.FABRIC, null)
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("loaders=[\"fabric\"]"), decoded)
    }

    @Test
    fun `编码把中文与空格都转成百分号`() {
        assertEquals("a%20b", ModrinthMeta.encode("a b"))
        assertEquals("sodium", ModrinthMeta.encode("sodium"))
        // 中文「钠」的 UTF-8 是 E9 92 A0
        assertEquals("%E9%92%A0", ModrinthMeta.encode("钠"))
    }
}
