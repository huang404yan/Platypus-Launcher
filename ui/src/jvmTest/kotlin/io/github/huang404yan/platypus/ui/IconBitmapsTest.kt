package io.github.huang404yan.platypus.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 图标缓存的检查。
 *
 * 缓存那层是泛型的，特意没跟 [androidx.compose.ui.graphics.ImageBitmap] 绑死：
 * 真拿位图当测试替身会去初始化 skiko 原生库，而单测类路径里没有它
 * （实测 `NoClassDefFoundError: Could not initialize class org.jetbrains.skia.Image`）。
 * 这里用字符串当替身，测的是命中、失效、坏图不重试、满了淘汰四件事。
 */
class IconBitmapsTest {

    private fun tempFile(name: String = "icon.png"): File {
        val file = File.createTempFile("icon-test-", "-$name")
        file.deleteOnExit()
        file.writeText("占位内容")
        return file
    }

    @Test
    fun `缺文件与空路径都给空`() {
        val cache = IconCache<String>()
        assertNull(cache.get(null) { "不该被调用" })
        assertNull(cache.get(File("这个文件不存在-9f3c.png")) { "不该被调用" })
    }

    @Test
    fun `坏文件不抛异常，只当没有图`() {
        val cache = IconCache<String>()
        val file = tempFile()
        // 关键：不抛。列表里有一条坏图，不该让整页组合失败。
        val result = runCatching { cache.get(file) { throw IllegalStateException("解码炸了") } }
        assertTrue(result.isSuccess, "解码失败必须是 null，不是异常")
        assertNull(result.getOrNull())
    }

    @Test
    fun `同一张图解一次，第二次不再调解码`() {
        val cache = IconCache<String>()
        val file = tempFile()
        var calls = 0
        val decode: (File) -> String = { calls++; "bitmap" }

        val first = cache.get(file, decode)
        val second = cache.get(file, decode)
        assertNotNull(first)
        // 同一个对象 = 命中缓存；重解一次虽然结果一样但白花了时间。
        assertSame(first, second)
        assertEquals(1, calls, "第二次不该再进解码函数")
    }

    @Test
    fun `解失败也记下来，不反复重试`() {
        val cache = IconCache<String>()
        val file = tempFile()
        var calls = 0
        val decode: (File) -> String? = { calls++; null }

        assertNull(cache.get(file, decode))
        assertNull(cache.get(file, decode))
        assertEquals(1, calls, "坏图重试一次就够了，每帧重试比成功还贵")
    }

    @Test
    fun `文件改过之后键会变，不复用旧图`() {
        val cache = IconCache<String>()
        val file = tempFile()
        val before = cache.key(file)
        file.setLastModified(file.lastModified() + 5_000)
        val after = cache.key(file)
        assertTrue(before != after, "换了图必须重解，否则界面永远是旧的那张：$before / $after")
    }

    @Test
    fun `超过上限时挤掉最久没用的`() {
        val cache = IconCache<String>(maxEntries = 2)
        val a = tempFile("a.png")
        val b = tempFile("b.png")
        val c = tempFile("c.png")
        val decode: (File) -> String = { "bitmap" }

        cache.get(a, decode)
        cache.get(b, decode)
        // 碰一下 a，让它成为最近用过的；之后进来的 c 应该挤掉 b。
        cache.get(a, decode)
        cache.get(c, decode)
        assertEquals(2, cache.size)

        // 先验 a 还在：命中就不会 put，也就不会反过来把别的挤掉。
        var aCalls = 0
        cache.get(a) { aCalls++; "bitmap" }
        assertEquals(0, aCalls, "a 刚碰过，应还在")
        // 再验 b 被挤掉。顺序不能反：取 b 会 put 一条新记录，把 a 挤出去。
        var bCalls = 0
        cache.get(b) { bCalls++; "bitmap" }
        assertEquals(1, bCalls, "b 应已被挤掉，重新取要再解一次")
    }
}
