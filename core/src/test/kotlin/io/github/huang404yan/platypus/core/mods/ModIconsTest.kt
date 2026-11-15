package io.github.huang404yan.platypus.core.mods

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 图标缓存的行为测试。全部在临时目录里跑，不联网 —— 网络那条路（[ModIcons.fetch] 的下载分支）
 * 只在缓存未命中时才走，这里刻意先写缓存文件，验的就是「命中就不再发请求」。
 */
class ModIconsTest {

    private val work = File(
        File(System.getProperty("java.io.tmpdir") ?: "."),
        "platypus-modicons-test-" + System.nanoTime(),
    )

    private val cache get() = File(work, "cache")

    @AfterTest
    fun cleanUp() {
        work.deleteRecursively()
    }

    /** 造一个只有 icon.png 的最小 jar；[iconPath] 为 null 表示这个 jar 里一张图都没有。 */
    private fun jarWith(name: String, iconPath: String?, modId: String? = "demo"): File {
        val jar = File(work, name)
        jar.parentFile.mkdirs()
        ZipOutputStream(jar.outputStream()).use { zip ->
            if (modId != null) {
                val meta = """{"schemaVersion":1,"id":"demo","version":"1.0"}"""
                zip.putNextEntry(ZipEntry("fabric.mod.json"))
                zip.write(meta.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (iconPath != null) {
                zip.putNextEntry(ZipEntry(iconPath))
                zip.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                zip.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `按 modId 找到 assets 下的 icon`() {
        val jar = jarWith("a.jar", "assets/demo/icon.png")
        val icon = ModIcons.fromJar(jar, "demo", cache)
        assertNotNull(icon)
        assertTrue(icon.isFile)
        assertEquals(4L, icon.length())
    }

    @Test
    fun `根目录下的 icon 也能认出来`() {
        val jar = jarWith("b.jar", "icon.png", modId = null)
        assertNotNull(ModIcons.fromJar(jar, null, cache))
    }

    @Test
    fun `一张图都没有时返回 null 而不是抛异常`() {
        val jar = jarWith("c.jar", iconPath = null, modId = "demo")
        assertNull(ModIcons.fromJar(jar, "demo", cache))
    }

    @Test
    fun `不存在路径名里的 icon 会兜底扫到`() {
        // 有些模组把图放在 assets/<命名空间>/textures/gui/ 这种更深的位置
        val jar = jarWith("d.jar", "assets/other/textures/gui/icon.png")
        assertNotNull(ModIcons.fromJar(jar, "demo", cache))
    }

    @Test
    fun `第二次取同一个 jar 命中磁盘缓存不再解压`() {
        val jar = jarWith("e.jar", "assets/demo/icon.png")
        val first = assertNotNull(ModIcons.fromJar(jar, "demo", cache))
        val stamp = first.lastModified()
        val second = assertNotNull(ModIcons.fromJar(jar, "demo", cache))
        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(stamp, second.lastModified())
    }

    @Test
    fun `网络图标命中缓存时不会真的去联网`() {
        // 地址是假的：只要走了下载分支就一定失败，返回 null。
        // 先按 URL 把缓存文件摆好，fetch 应该直接返回它 —— 这就是「命中不联网」的证明。
        val url = "https://example.invalid/icon.png"
        val cached = ModIcons.cachedFileForUrl(cache, url)
        cached.parentFile.mkdirs()
        cached.writeBytes(byteArrayOf(1, 2, 3))
        val hit = ModIcons.fetch(url, cache)
        assertNotNull(hit)
        assertEquals(cached.absolutePath, hit.absolutePath)
    }

    @Test
    fun `空地址直接返回 null`() {
        assertNull(ModIcons.fetch("", cache))
    }

    @Test
    fun `缓存目录不存在时会自己建出来`() {
        val target = File(work, "nested/deep/icons")
        assertTrue(!target.isDirectory)
        val jar = jarWith("f.jar", "assets/demo/icon.png")
        assertNotNull(ModIcons.fromJar(jar, "demo", target))
        assertTrue(target.isDirectory)
    }
}
