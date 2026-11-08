package io.github.huang0yan4.platypus.core.version

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionMetaTest {

    private val manifestJson = """
        {
          "latest": { "release": "1.21.4", "snapshot": "26w01a" },
          "versions": [
            { "id": "1.21.4", "type": "release", "url": "https://example.com/1.21.4.json", "time": "2024-12-03T10:00:00+00:00", "sha1": "abc" },
            { "id": "1.21.4-pre1", "type": "snapshot", "url": "https://example.com/pre1.json", "time": "2024-11-25T10:00:00+00:00" }
          ]
        }
    """.trimIndent()

    private val versionJson = """
        {
          "id": "1.20.1",
          "type": "release",
          "mainClass": "net.minecraft.client.main.Main",
          "assets": "5",
          "assetIndex": { "id": "5", "sha1": "aaaa", "size": 100, "totalSize": 200, "url": "https://example.com/assets.json" },
          "downloads": {
            "client": { "sha1": "bbbb", "size": 12345, "url": "https://example.com/client.jar" }
          },
          "javaVersion": { "component": "java-runtime-gamma", "majorVersion": 17 },
          "libraries": [
            {
              "name": "org.lwjgl:lwjgl:3.3.1",
              "downloads": {
                "artifact": { "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar", "sha1": "cccc", "size": 10, "url": "https://example.com/lwjgl.jar" }
              }
            },
            {
              "name": "org.lwjgl:lwjgl:3.3.1:natives-windows",
              "rules": [ { "action": "allow", "os": { "name": "windows" } } ],
              "downloads": {
                "classifiers": {
                  "natives-windows": { "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar", "sha1": "dddd", "size": 20, "url": "https://example.com/lwjgl-natives.jar" }
                }
              }
            },
            {
              "name": "ca.weblite:java-objc-bridge:1.1",
              "rules": [ { "action": "allow", "os": { "name": "osx" } } ]
            },
            {
              "name": "com.example:arm:1.0",
              "rules": [ { "action": "allow", "os": { "name": "linux", "arch": "aarch64" } } ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses version manifest`() {
        val manifest = parseVersionManifest(manifestJson)
        assertEquals("1.21.4", manifest.latest?.release)
        assertEquals(2, manifest.versions.size)
        assertEquals("1.21.4", manifest.releases().single().id)
        assertEquals("https://example.com/1.21.4.json", manifest.find("1.21.4")?.url)
        assertNull(manifest.find("不存在"))
    }

    @Test
    fun `parses version json with libraries`() {
        val version = parseVersionJson(versionJson)
        assertEquals("1.20.1", version.id)
        assertEquals("net.minecraft.client.main.Main", version.mainClass)
        assertEquals(17, version.requiredJavaMajor)
        assertEquals("https://example.com/client.jar", version.clientJar?.url)
        assertEquals("5", version.assetIndex?.id)
        assertEquals(4, version.libraries.size)
        assertEquals(
            "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar",
            version.libraries.first().downloads?.artifact?.path,
        )
    }

    @Test
    fun `builds maven path from library name`() {
        val lib = LibraryName.parse("net.fabricmc:fabric-loader:0.15.11")
        assertEquals("net/fabricmc/fabric-loader/0.15.11/fabric-loader-0.15.11.jar", lib?.basePath)

        val native = LibraryName.parse("org.lwjgl:lwjgl:3.3.1:natives-windows")
        assertEquals("org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar", native?.basePath)

        assertNull(LibraryName.parse("bad-name"))
    }

    @Test
    fun `library rules depend on os and arch`() {
        val version = parseVersionJson(versionJson)
        val windowsOnly = version.libraries[1]
        val macOnly = version.libraries[2]
        val linuxArmOnly = version.libraries[3]

        assertTrue(windowsOnly.isAllowed(os = OsFamily.WINDOWS, arch = "amd64"))
        assertFalse(windowsOnly.isAllowed(os = OsFamily.LINUX, arch = "x86_64"))
        assertFalse(windowsOnly.isAllowed(os = OsFamily.MACOS, arch = "aarch64"))

        assertTrue(macOnly.isAllowed(os = OsFamily.MACOS, arch = "aarch64"))
        assertFalse(macOnly.isAllowed(os = OsFamily.WINDOWS, arch = "amd64"))

        assertTrue(linuxArmOnly.isAllowed(os = OsFamily.LINUX, arch = "aarch64"))
        assertFalse(linuxArmOnly.isAllowed(os = OsFamily.LINUX, arch = "x86_64"))
    }

    @Test
    fun `empty rules mean allowed`() {
        assertTrue(RuleEvaluator.isAllowed(null, os = OsFamily.LINUX, arch = "x86_64"))
        assertTrue(RuleEvaluator.isAllowed(emptyList(), os = OsFamily.LINUX, arch = "x86_64"))
    }

    @Test
    fun `features must match`() {
        val rules = listOf(
            Rule(action = "allow", features = mapOf("has_custom_resolution" to true)),
        )
        assertFalse(RuleEvaluator.isAllowed(rules, features = emptyMap()))
        assertTrue(RuleEvaluator.isAllowed(rules, features = mapOf("has_custom_resolution" to true)))
    }

    @Test
    fun `reads native classifier for os`() {
        val json = """{ "name": "org.lwjgl:lwjgl:3.3.1", "natives": { "windows": "natives-windows", "linux": "natives-linux" } }"""
        val lib = VersionJsonFormat.decodeFromString<Library>(json)
        assertEquals("natives-windows", lib.nativeClassifier(OsFamily.WINDOWS))
        assertEquals("natives-linux", lib.nativeClassifier(OsFamily.LINUX))
        assertNull(lib.nativeClassifier(OsFamily.MACOS))
    }
}
