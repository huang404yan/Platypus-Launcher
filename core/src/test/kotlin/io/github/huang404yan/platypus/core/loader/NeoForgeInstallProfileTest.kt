package io.github.huang404yan.platypus.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File

/**
 * 预铺库的解析与地址换算。
 *
 * 夹具直接抄 neoforge-21.1.265-installer.jar 里 install_profile.json 的真实条目，
 * 但把 sha1 换成假值，免得测试被上游内容牵动。
 */
class NeoForgeInstallProfileTest {

    private val profile = """
        {
          "spec": 1,
          "version": "neoforge-21.1.265",
          "json": "/version.json",
          "libraries": [
            {
              "name": "net.neoforged.installertools:installertools:2.1.2",
              "downloads": {
                "artifact": {
                  "sha1": "1111111111111111111111111111111111111111",
                  "size": 390000,
                  "url": "https://maven.neoforged.net/releases/net/neoforged/installertools/installertools/2.1.2/installertools-2.1.2.jar",
                  "path": "net/neoforged/installertools/installertools/2.1.2/installertools-2.1.2.jar"
                }
              }
            },
            {
              "name": "com.google.code.gson:gson:2.8.9",
              "downloads": {
                "artifact": {
                  "sha1": "2222222222222222222222222222222222222222",
                  "size": 258000,
                  "url": "https://libraries.minecraft.net/com/google/code/gson/gson/2.8.9/gson-2.8.9.jar",
                  "path": "com/google/code/gson/gson/2.8.9/gson-2.8.9.jar"
                }
              }
            },
            {
              "name": "net.neoforged:neoforge:21.1.265:universal",
              "downloads": {
                "artifact": {
                  "sha1": "3333333333333333333333333333333333333333",
                  "size": 1000,
                  "url": "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.265/neoforge-21.1.265-universal.jar",
                  "path": "net/neoforged/neoforge/21.1.265/neoforge-21.1.265-universal.jar"
                }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析 install_profile 的 libraries 清单`() {
        val libs = NeoForgeInstallProfile.parseLibraries(profile)
        assertEquals(3, libs.size)
        assertEquals("net.neoforged.installertools:installertools:2.1.2", libs[0].coord)
        assertEquals(
            "net/neoforged/installertools/installertools/2.1.2/installertools-2.1.2.jar",
            libs[0].path,
        )
        assertEquals(390000L, libs[0].size)
        assertEquals("1111111111111111111111111111111111111111", libs[0].sha1)
    }

    @Test
    fun `没有 artifact 的条目被跳过`() {
        val text = """
            {"libraries":[
              {"name":"a:b:1.0"},
              {"name":"c:d:2.0","downloads":{}},
              {"name":"e:f:3.0","downloads":{"artifact":{"url":"https://x/y.jar"}}}
            ]}
        """.trimIndent()
        val libs = NeoForgeInstallProfile.parseLibraries(text)
        assertEquals(1, libs.size)
        assertEquals("e:f:3.0", libs[0].coord)
    }

    @Test
    fun `缺 path 时按坐标推`() {
        val text = """
            {"libraries":[
              {"name":"org.ow2.asm:asm:9.3","downloads":{"artifact":{"url":"https://x/asm.jar","sha1":"4444444444444444444444444444444444444444","size":120000}}}
            ]}
        """.trimIndent()
        val libs = NeoForgeInstallProfile.parseLibraries(text)
        assertEquals(1, libs.size)
        assertEquals("org/ow2/asm/asm/9.3/asm-9.3.jar", libs[0].path)
    }

    @Test
    fun `sha1 不是四十位就当没有`() {
        val text = """
            {"libraries":[
              {"name":"a:b:1.0","downloads":{"artifact":{"url":"https://x/b.jar","sha1":"deadbeef","size":1}}}
            ]}
        """.trimIndent()
        val libs = NeoForgeInstallProfile.parseLibraries(text)
        assertNull(libs[0].sha1)
    }

    @Test
    fun `缺 size 记成减一`() {
        val text = """
            {"libraries":[
              {"name":"a:b:1.0","downloads":{"artifact":{"url":"https://x/b.jar","sha1":"5555555555555555555555555555555555555555"}}}
            ]}
        """.trimIndent()
        val libs = NeoForgeInstallProfile.parseLibraries(text)
        assertEquals(-1L, libs[0].size)
    }

    @Test
    fun `不是 JSON 时返回空清单`() {
        assertTrue(NeoForgeInstallProfile.parseLibraries("<html>404</html>").isEmpty())
        assertTrue(NeoForgeInstallProfile.parseLibraries("").isEmpty())
        assertTrue(NeoForgeInstallProfile.parseLibraries("{}").isEmpty())
    }

    @Test
    fun `neoforged 的库换 bmclapi 前缀`() {
        val url = "https://maven.neoforged.net/releases/net/neoforged/srgutils/1.0.0/srgutils-1.0.0.jar"
        assertEquals(
            "https://bmclapi2.bangbang93.com/maven/net/neoforged/srgutils/1.0.0/srgutils-1.0.0.jar",
            NeoForgeInstallProfile.downloadUrl(url),
        )
    }

    @Test
    fun `mojang 的库地址原样不动`() {
        val url = "https://libraries.minecraft.net/com/google/code/gson/gson/2.8.9/gson-2.8.9.jar"
        assertEquals(url, NeoForgeInstallProfile.downloadUrl(url))
    }

    @Test
    fun `无关地址原样返回`() {
        assertEquals("https://example.com/a.jar", NeoForgeInstallProfile.downloadUrl("https://example.com/a.jar"))
        assertEquals("", NeoForgeInstallProfile.downloadUrl("  "))
    }

    @Test
    fun `neoforged 库先兜底到落地镜像再回官方`() {
        val lib = InstallerLibrary(
            coord = "a:b:1.0",
            path = "a/b/1.0/b-1.0.jar",
            url = "https://maven.neoforged.net/releases/a/b/1.0/b-1.0.jar",
            sha1 = null,
            size = -1L,
        )
        assertEquals(
            listOf(
                "https://mirror.nyist.edu.cn/bmclapi/a/b/1.0/b-1.0.jar",
                "https://maven.neoforged.net/releases/a/b/1.0/b-1.0.jar",
            ),
            NeoForgeInstallProfile.mirrorUrls(lib),
        )
    }

    @Test
    fun `mojang 库的兜底是 bmclapi`() {
        val lib = InstallerLibrary(
            coord = "c:d:2.0",
            path = "c/d/2.0/d-2.0.jar",
            url = "https://libraries.minecraft.net/c/d/2.0/d-2.0.jar",
            sha1 = null,
            size = -1L,
        )
        assertEquals(
            listOf("https://bmclapi2.bangbang93.com/libraries/c/d/2.0/d-2.0.jar"),
            NeoForgeInstallProfile.mirrorUrls(lib),
        )
    }

    @Test
    fun `落盘位置在 gameRoot 的 libraries 下`() {
        val lib = InstallerLibrary(
            coord = "a:b:1.0",
            path = "a/b/1.0/b-1.0.jar",
            url = "https://x/b.jar",
            sha1 = null,
            size = -1L,
        )
        val target = NeoForgeInstallProfile.targetFile(File("C:/mc"), lib)
        assertEquals(
            File("C:/mc/libraries/a/b/1.0/b-1.0.jar").path,
            target.path,
        )
    }

    @Test
    fun `mavenPath 处理分类器与扩展名`() {
        assertEquals("a/b/1.0/b-1.0.jar", NeoForgeInstallProfile.mavenPath("a:b:1.0"))
        assertEquals("a/b/1.0/b-1.0-sources.jar", NeoForgeInstallProfile.mavenPath("a:b:1.0:sources"))
        assertEquals("a/b/1.0/b-1.0.zip", NeoForgeInstallProfile.mavenPath("a:b:1.0@zip"))
        assertEquals(
            "net/neoforged/neoform/1.21.1-20240808.144430/neoform-1.21.1-20240808.144430.zip",
            NeoForgeInstallProfile.mavenPath("net.neoforged:neoform:1.21.1-20240808.144430@zip"),
        )
        assertNull(NeoForgeInstallProfile.mavenPath("a:b"))
        assertNull(NeoForgeInstallProfile.mavenPath(""))
    }
}
