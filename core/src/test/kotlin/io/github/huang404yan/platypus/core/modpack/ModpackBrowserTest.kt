package io.github.huang404yan.platypus.core.modpack

import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.mods.ModVersionFile
import io.github.huang404yan.platypus.core.mods.ModrinthMeta
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 远程整合包「搜索 → 查版本 → 下载」里那些不发请求的部分。
 *
 * 联网那三段（search / versionsOf / download）不发真请求：它们的正确性靠界面实测与
 * 一次性的真网 smoke，单测只锁住「怎么挑文件」「文件名怎么取」这类纯逻辑，
 * 否则测试会因为 Modrinth 抖动而红。
 */
class ModpackBrowserTest {

    private fun file(name: String, version: String = "1.0", type: String = "release") = ModVersionFile(
        projectSlug = "fabulously-optimized",
        versionNumber = version,
        fileName = name,
        url = "https://cdn.modrinth.com/data/1KVo5zza/versions/FLkaba1E/" + name,
        sha1 = "a".repeat(40),
        size = 173_981L,
        releaseType = type,
    )

    @Test
    fun `只留 mrpack，source 包与服务器包都被滤掉`() {
        val versions = listOf(
            file("Fabulously.Optimized-v15.0.0-beta.3.mrpack"),
            file("Fabulously.Optimized-v15.0.0-beta.3-sources.jar"),
            file("Fabulously.Optimized-v15.0.0-beta.3-server.mrpack"),
        )
        val kept = ModpackBrowser.mrpackOnly(versions)
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.fileName.endsWith(".mrpack") })
    }

    @Test
    fun `一个 mrpack 都没有时返回空列表而不是抛`() {
        val kept = ModpackBrowser.mrpackOnly(listOf(file("pack.zip"), file("pack.jar")))
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `mrpack 后缀大小写不敏感`() {
        val kept = ModpackBrowser.mrpackOnly(listOf(file("SomePack-1.0.MRPACK")))
        assertEquals(1, kept.size)
    }

    @Test
    fun `服务器给的文件名带路径时只取文件名`() {
        assertEquals("pack.mrpack", ModpackBrowser.localFileNameOf("packs/pack.mrpack"))
        assertEquals("pack.mrpack", ModpackBrowser.localFileNameOf("packs\\pack.mrpack"))
        assertEquals("pack.mrpack", ModpackBrowser.localFileNameOf("pack.mrpack"))
    }

    @Test
    fun `不是 mrpack 的文件名返回 null，交给调用方报错`() {
        assertNull(ModpackBrowser.localFileNameOf("pack.zip"))
        assertNull(ModpackBrowser.localFileNameOf(""))
        assertNull(ModpackBrowser.localFileNameOf("packs/"))
    }

    @Test
    fun `缓存目录落在临时目录下的 platypus-modpacks`() {
        val dir = ModpackBrowser.defaultCacheDir()
        val expectedRoot = File(System.getProperty("java.io.tmpdir") ?: ".", "platypus-modpacks")
        assertEquals(File(expectedRoot, "packs").absolutePath, dir.absolutePath)
    }

    @Test
    fun `整合包搜索的 facet 是 project_type modpack`() {
        val url = ModrinthMeta.searchUrl(
            query = "cobblemon",
            gameVersion = "1.20.1",
            loader = LoaderKind.FABRIC,
            projectType = ModrinthMeta.PROJECT_TYPE_MODPACK,
        )
        assertTrue(url.contains("project_type%3Amodpack") || url.contains("project_type:modpack"), url)
        // 加载器 facet 仍然要带上：整合包也有 fabric / forge 之分，只是套在 categories 里
        assertTrue(url.contains("categories%3Afabric") || url.contains("categories:fabric"), url)
    }

    @Test
    fun `空关键词也能搜整合包`() {
        val url = ModrinthMeta.searchUrl(
            query = "",
            gameVersion = null,
            loader = LoaderKind.VANILLA,
            projectType = ModrinthMeta.PROJECT_TYPE_MODPACK,
        )
        assertTrue(url.contains("query=&") || url.endsWith("query="), url)
        assertTrue(url.contains("modpack"), url)
    }

    @Test
    fun `进度分数在没有分母时是 0`() {
        assertEquals(0f, ModpackDownloadProgress("下载", "pack.mrpack").fraction)
        assertEquals(0.5f, ModpackDownloadProgress("下载", "pack.mrpack", total = 100, done = 50).fraction)
    }
}
