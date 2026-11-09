package io.github.huang404yan.platypus.core.loader

import io.github.huang404yan.platypus.core.version.Library
import io.github.huang404yan.platypus.core.version.OsFamily
import io.github.huang404yan.platypus.core.version.VersionJsonFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

class FabricMetaTest {

    /** 实测响应形状（2026-11-09 抓的是 1.20.1）：数组项包了一层 loader 对象。 */
    private val loaderListNested = """
        [
          {
            "loader": { "separator": "+build.", "build": 15, "maven": "net.fabricmc:fabric-loader:0.16.14", "version": "0.16.14", "stable": true },
            "intermediary": { "maven": "net.fabricmc:intermediary:1.20.1", "version": "1.20.1", "stable": true },
            "launcherMeta": { "version": 2, "min_java_version": 8 }
          },
          {
            "loader": { "separator": "+build.", "build": 20, "maven": "net.fabricmc:fabric-loader:0.19.0-beta.3", "version": "0.19.0-beta.3", "stable": false },
            "intermediary": { "maven": "net.fabricmc:intermediary:1.20.1", "version": "1.20.1", "stable": true }
          }
        ]
    """.trimIndent()

    /** 任务书里描述的扁平形状；两种都要吃得下。 */
    private val loaderListFlat = """
        [
          { "separator": "+build.", "build": 15, "maven": "net.fabricmc:fabric-loader:0.16.14", "version": "0.16.14", "stable": true }
        ]
    """.trimIndent()

    /** 真实 profile json 的裁剪版：asm 带 sha1/size，intermediary 与 fabric-loader 什么都不带。 */
    private val profileJson = """
        {
          "id": "fabric-loader-0.16.14-1.20.1",
          "inheritsFrom": "1.20.1",
          "releaseTime": "2026-11-09T02:25:41+0000",
          "time": "2026-11-09T02:25:41+0000",
          "type": "release",
          "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
          "arguments": { "game": [], "jvm": ["-DFabricMcEmu= net.minecraft.client.main.Main "] },
          "libraries": [
            { "name": "org.ow2.asm:asm:9.8", "url": "https://maven.fabricmc.net/", "md5": "f5ad", "sha1": "dc19ecb3f7889b7860697215cae99c0f9b6f6b4b", "size": 126113 },
            { "name": "net.fabricmc:sponge-mixin:0.15.5+mixin.0.8.7", "url": "https://maven.fabricmc.net/", "sha1": "22f9eb729e216a091673a574a5906dc1b9027fb3", "size": 1499194 },
            { "name": "net.fabricmc:intermediary:1.20.1", "url": "https://maven.fabricmc.net/" },
            { "name": "net.fabricmc:fabric-loader:0.16.14", "url": "https://maven.fabricmc.net/" }
          ]
        }
    """.trimIndent()

    // ---- loader 列表 ----

    @Test
    fun parsesNestedLoaderList() {
        val parsed = parseFabricLoaderVersions(loaderListNested)
        val versions = assertNotNull(parsed.getOrNull(), "应当解析成功")
        assertEquals(2, versions.size)
        assertEquals("0.16.14", versions[0].version)
        assertTrue(versions[0].stable)
        assertEquals(15, versions[0].build)
        assertEquals("net.fabricmc:fabric-loader:0.16.14", versions[0].maven)
        assertFalse(versions[1].stable)
    }

    @Test
    fun parsesFlatLoaderList() {
        val versions = assertNotNull(parseFabricLoaderVersions(loaderListFlat).getOrNull())
        assertEquals(1, versions.size)
        assertEquals("0.16.14", versions[0].version)
    }

    @Test
    fun latestStableSkipsNewerUnstable() {
        val versions = assertNotNull(parseFabricLoaderVersions(loaderListNested).getOrNull())
        val latest = assertNotNull(latestStableFabricLoader(versions))
        assertEquals("0.16.14", latest.version)
    }

    @Test
    fun latestStableFallsBackToFirstWhenNoneStable() {
        val versions = listOf(
            FabricLoaderVersion(version = "0.19.0-beta.3", stable = false),
            FabricLoaderVersion(version = "0.16.14", stable = false),
        )
        assertEquals("0.19.0-beta.3", latestStableFabricLoader(versions)?.version)
    }

    @Test
    fun emptyLoaderListGivesReason() {
        val parsed = parseFabricLoaderVersions("[]")
        assertNull(parsed.getOrNull())
        assertEquals("Fabric 元数据里没有任何 loader 版本", parsed.reasonOrNull())
    }

    @Test
    fun brokenLoaderListGivesReason() {
        val parsed = parseFabricLoaderVersions("{ not json")
        assertNull(parsed.getOrNull())
        assertTrue(parsed.reasonOrNull()!!.startsWith("Fabric loader 列表解析失败："))
    }

    @Test
    fun blankLoaderListGivesReason() {
        val parsed = parseFabricLoaderVersions("   ")
        assertNull(parsed.getOrNull())
        assertTrue(parsed.reasonOrNull()!!.contains("空响应"))
    }

    @Test
    fun entriesWithoutVersionAreReported() {
        val parsed = parseFabricLoaderVersions("""[{"loader":{"stable":true}},{"intermediary":{"version":"1.20.1"}}]""")
        assertNull(parsed.getOrNull())
        assertEquals("Fabric 元数据里没有可用的 loader 版本（字段缺失）", parsed.reasonOrNull())
    }

    // ---- profile json ----

    @Test
    fun parsesProfile() {
        val profile = assertNotNull(parseFabricProfile(profileJson, "1.20.1", "0.16.14").getOrNull())
        assertEquals(4, profile.versionJson.libraries.size)
        assertEquals(FabricMeta.MAIN_CLASS, profile.mainClass)
        assertEquals("1.20.1", profile.versionJson.inheritsFrom)
        assertEquals("1.20.1", profile.gameVersion)
        assertEquals("0.16.14", profile.loaderVersion)
        assertTrue(profile.raw.startsWith("{"))
    }

    @Test
    fun profileWithoutLibrariesGivesReason() {
        val parsed = parseFabricProfile("""{"id":"x","mainClass":"a.B"}""", "1.20.1", "0.16.14")
        assertNull(parsed.getOrNull())
        assertEquals("Fabric profile json 里没有 libraries 字段", parsed.reasonOrNull())
    }

    @Test
    fun profileWithoutMainClassGivesReason() {
        val parsed = parseFabricProfile("""{"id":"x","libraries":[{"name":"a:b:1"}]}""", "1.20.1", "0.16.14")
        assertNull(parsed.getOrNull())
        assertEquals("Fabric profile json 里没有 mainClass 字段", parsed.reasonOrNull())
    }

    @Test
    fun brokenProfileGivesReason() {
        val parsed = parseFabricProfile("<html>502</html>", "1.20.1", "0.16.14")
        assertNull(parsed.getOrNull())
        assertTrue(parsed.reasonOrNull()!!.startsWith("Fabric profile json 解析失败："))
    }

    // ---- 版本号命名 ----

    @Test
    fun versionIdFollowsPclHabit() {
        assertEquals("1.20.1-fabric-0.16.14", FabricMeta.versionId("1.20.1", "0.16.14"))
        assertEquals("Fabric 0.16.14 / 1.20.1", FabricMeta.displayName("1.20.1", "0.16.14"))
    }

    @Test
    fun loaderVersionFromOurIdAndOfficialId() {
        assertEquals("0.16.14", FabricMeta.loaderVersionFromId("1.20.1-fabric-0.16.14"))
        assertEquals("0.16.14", FabricMeta.loaderVersionFromId("fabric-loader-0.16.14-1.20.1"))
        assertNull(FabricMeta.loaderVersionFromId("1.20.1"))
    }

    // ---- maven 补 url ----

    @Test
    fun fillsUrlFromMavenCoordinateWithDeclaredPrefix() {
        val library = library("""{"name":"org.ow2.asm:asm:9.8","url":"https://maven.fabricmc.net/","sha1":"abc","size":126113}""")
        assertEquals("org/ow2/asm/asm/9.8/asm-9.8.jar", FabricLibraries.jarPath(library))
        assertEquals(
            "https://maven.fabricmc.net/org/ow2/asm/asm/9.8/asm-9.8.jar",
            FabricLibraries.jarUrl(library),
        )
        assertEquals("abc", FabricLibraries.sha1Of(library))
        assertEquals(126113L, FabricLibraries.sizeOf(library))
    }

    @Test
    fun fabricLoaderWithoutSha1StillPlans() {
        val library = library("""{"name":"net.fabricmc:fabric-loader:0.16.14","url":"https://maven.fabricmc.net/"}""")
        assertEquals(
            "net/fabricmc/fabric-loader/0.16.14/fabric-loader-0.16.14.jar",
            FabricLibraries.jarPath(library),
        )
        assertEquals(
            "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.14/fabric-loader-0.16.14.jar",
            FabricLibraries.jarUrl(library),
        )
        assertNull(FabricLibraries.sha1Of(library))
        assertEquals(-1L, FabricLibraries.sizeOf(library))
    }

    @Test
    fun missingUrlFallsBackByGroup() {
        val fabric = library("""{"name":"net.fabricmc:tiny-mappings:0.1"}""")
        assertEquals(
            "https://maven.fabricmc.net/net/fabricmc/tiny-mappings/0.1/tiny-mappings-0.1.jar",
            FabricLibraries.jarUrl(fabric),
        )
        val mojang = library("""{"name":"com.mojang:brigadier:1.0.18"}""")
        assertEquals(
            "https://libraries.minecraft.net/com/mojang/brigadier/1.0.18/brigadier-1.0.18.jar",
            FabricLibraries.jarUrl(mojang),
        )
    }

    @Test
    fun classifierInNameIsKept() {
        val library = library("""{"name":"org.lwjgl:lwjgl:3.3.1:natives-windows","url":"https://libraries.minecraft.net/"}""")
        assertEquals(
            "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar",
            FabricLibraries.jarPath(library),
        )
        assertEquals(
            "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar",
            FabricLibraries.jarUrl(library),
        )
    }

    @Test
    fun nativesMappingSuppliesClassifier() {
        val library = library(
            """{"name":"org.lwjgl:lwjgl:3.3.1","natives":{"windows":"natives-windows","linux":"natives-linux"},"url":"https://libraries.minecraft.net/"}""",
        )
        assertEquals(
            "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar",
            FabricLibraries.jarPath(library, OsFamily.WINDOWS),
        )
        assertEquals(
            "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar",
            FabricLibraries.jarPath(library, OsFamily.LINUX),
        )
    }

    @Test
    fun mojangStyleDownloadsStillWin() {
        val library = library(
            """
            {"name":"org.lwjgl:lwjgl:3.3.1","downloads":{"artifact":{
              "path":"org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar","sha1":"cccc","size":10,
              "url":"https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar"}}}
            """.trimIndent(),
        )
        assertEquals("https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar", FabricLibraries.jarUrl(library))
        assertEquals("cccc", FabricLibraries.sha1Of(library))
    }

    @Test
    fun planSkipsDisallowedLibrariesAndDedupesTargets() {
        val version = VersionJsonFormat.decodeFromString<io.github.huang404yan.platypus.core.version.VersionJson>(
            """
            {"libraries":[
              {"name":"org.ow2.asm:asm:9.8","url":"https://maven.fabricmc.net/"},
              {"name":"org.ow2.asm:asm:9.8","url":"https://maven.fabricmc.net/"},
              {"name":"com.example:only-mac:1.0","rules":[{"action":"allow","os":{"name":"osx"}}]},
              {"name":"broken-name"}
            ]}
            """.trimIndent(),
        )
        val root = Files.createTempDirectory("fabric-plan").toFile()
        try {
            val items = FabricLibraries.plan(version, File(root, "libraries"), OsFamily.WINDOWS, "amd64")
            assertEquals(1, items.size)
            assertEquals(
                File(root, "libraries/org/ow2/asm/asm/9.8/asm-9.8.jar").absolutePath,
                items[0].target,
            )
            assertEquals("https://maven.fabricmc.net/org/ow2/asm/asm/9.8/asm-9.8.jar", items[0].url)
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- profile 落盘 ----

    @Test
    fun writeProfileRewritesIdKeepsInheritsFrom() {
        val root = Files.createTempDirectory("fabric-write").toFile()
        try {
            val versionId = FabricMeta.versionId("1.20.1", "0.16.14")
            val file = FabricFiles.writeProfile(root, versionId, profileJson)
            assertEquals(File(root, "versions/1.20.1-fabric-0.16.14/1.20.1-fabric-0.16.14.json"), file)
            val written = VersionJsonFormat.decodeFromString<io.github.huang404yan.platypus.core.version.VersionJson>(
                file.readText(Charsets.UTF_8),
            )
            assertEquals("1.20.1-fabric-0.16.14", written.id)
            assertEquals("1.20.1", written.inheritsFrom)
            assertEquals(FabricMeta.MAIN_CLASS, written.mainClass)
            assertEquals(4, written.libraries.size)
            // arguments 必须原样保留，KnotClient 靠 -DFabricMcEmu 启动
            assertEquals(1, written.arguments?.jvm?.size)
            assertEquals(
                "fabric-loader-0.16.14-1.20.1",
                VersionJsonFormat.decodeFromString<io.github.huang404yan.platypus.core.version.VersionJson>(profileJson).id,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun library(json: String): Library =
        VersionJsonFormat.decodeFromString(Library.serializer(), json)
}
