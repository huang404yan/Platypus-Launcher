package io.github.huang0yan4.platypus.core.version

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadPlannerTest {

    private val gameDir = File("C:/games/.minecraft")

    private val version = parseVersionJson(
        """
        {
          "id": "1.20.1",
          "assets": "5",
          "assetIndex": { "id": "5", "sha1": "index-sha1", "size": 42, "url": "https://piston-data.mojang.com/v1/objects/idx/assets-5.json" },
          "downloads": {
            "client": { "sha1": "client-sha1", "size": 1000, "url": "https://piston-data.mojang.com/v1/objects/c/client.jar" }
          },
          "libraries": [
            {
              "name": "org.lwjgl:lwjgl:3.3.1",
              "downloads": {
                "artifact": { "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar", "sha1": "a1", "size": 10, "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar" }
              }
            },
            {
              "name": "org.lwjgl:lwjgl:3.3.1",
              "natives": { "windows": "natives-windows", "linux": "natives-linux" },
              "downloads": {
                "classifiers": {
                  "natives-windows": { "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar", "sha1": "w1", "size": 11, "url": "https://libraries.minecraft.net/w.jar" },
                  "natives-linux": { "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar", "sha1": "l1", "size": 12, "url": "https://libraries.minecraft.net/l.jar" }
                }
              }
            },
            {
              "name": "com.example:windows-only:1.0",
              "rules": [ { "action": "allow", "os": { "name": "windows" } } ],
              "downloads": {
                "artifact": { "path": "com/example/windows-only/1.0/windows-only-1.0.jar", "sha1": "x1", "size": 13, "url": "https://libraries.minecraft.net/x.jar" }
              }
            }
          ]
        }
        """.trimIndent(),
    )

    private val assetIndex = parseAssetIndex(
        """
        {
          "objects": {
            "icons/icon_16x16.png": { "hash": "abcdef0123456789", "size": 100 },
            "icons/icon_32x32.png": { "hash": "1234567890abcdef", "size": 200 }
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `plans client jar`() {
        val item = assertNotNull(DownloadPlanner.clientJar(version, gameDir))
        assertEquals("https://piston-data.mojang.com/v1/objects/c/client.jar", item.url)
        assertTrue(item.target.endsWith("versions\\1.20.1\\1.20.1.jar") || item.target.endsWith("versions/1.20.1/1.20.1.jar"), item.target)
        assertEquals("client-sha1", item.sha1)
        assertEquals(listOf("https://bmclapi2.bangbang93.com/v1/objects/c/client.jar"), item.mirrors)
    }

    @Test
    fun `plans asset index`() {
        val item = assertNotNull(DownloadPlanner.assetIndex(version, gameDir))
        assertTrue(item.target.endsWith("assets\\indexes\\5.json") || item.target.endsWith("assets/indexes/5.json"), item.target)
        assertEquals("index-sha1", item.sha1)
    }

    @Test
    fun `plans libraries for windows with natives`() {
        val items = DownloadPlanner.libraries(version, gameDir, os = OsFamily.WINDOWS, arch = "amd64")
        assertEquals(3, items.size)
        assertTrue(items.any { it.target.endsWith("lwjgl-3.3.1.jar") })
        assertTrue(items.any { it.target.endsWith("lwjgl-3.3.1-natives-windows.jar") })
        assertTrue(items.any { it.target.endsWith("windows-only-1.0.jar") })
        assertTrue(items.none { it.target.endsWith("natives-linux.jar") })
    }

    @Test
    fun `plans libraries for linux without windows-only`() {
        val items = DownloadPlanner.libraries(version, gameDir, os = OsFamily.LINUX, arch = "x86_64")
        assertEquals(2, items.size)
        assertTrue(items.any { it.target.endsWith("lwjgl-3.3.1-natives-linux.jar") })
        assertTrue(items.none { it.target.endsWith("windows-only-1.0.jar") })
    }

    @Test
    fun `plans asset objects by hash prefix`() {
        val items = DownloadPlanner.assets(assetIndex, gameDir)
        assertEquals(2, items.size)
        val first = items.first { it.sha1 == "abcdef0123456789" }
        assertEquals("https://resources.download.minecraft.net/ab/abcdef0123456789", first.url)
        assertTrue(first.target.endsWith("assets\\objects\\ab\\abcdef0123456789") || first.target.endsWith("assets/objects/ab/abcdef0123456789"), first.target)
        assertEquals(listOf("https://bmclapi2.bangbang93.com/assets/ab/abcdef0123456789"), first.mirrors)
    }

    @Test
    fun `unmirrored url has no mirror`() {
        assertEquals(emptyList(), DownloadPlanner.mirrorOf("https://example.com/foo.jar"))
        assertNull(DownloadPlanner.clientJar(parseVersionJson("""{ "id": "x" }"""), gameDir))
    }
}
