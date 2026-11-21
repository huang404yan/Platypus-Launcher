package io.github.huang404yan.platypus.core.launch

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidGraphicsTest {

    private val root: File = Files.createTempDirectory("platypus-android-gfx").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `arm64 上 GL4ES 那套包含渲染库与公共件`() {
        val bundle = assertNotNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.GL4ES, "arm64-v8a"))
        assertEquals("arm64-v8a", bundle.abi)
        assertTrue(bundle.files.any { it.name == "libgl4es_114.so" })
        assertTrue(bundle.files.any { it.name == "liblwjgl.so" })
        assertTrue(bundle.files.any { it.name == "libopenal.so" })
        assertTrue(bundle.totalBytes > 0L)
    }

    @Test
    fun `Zink 会多带上一个 Vulkan 时间线层`() {
        val bundle = assertNotNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.ZINK, "arm64-v8a"))
        assertTrue(bundle.files.any { it.name == "libOSMesa.so" })
        assertTrue(bundle.files.any { it.name == "libVkLayer_khronos_timeline_semaphore.so" })
    }

    @Test
    fun `三十二位 x86 上没有 Zink`() {
        assertNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.ZINK, "x86"))
        assertFalse(AndroidRenderer.ZINK in AndroidGraphicsCatalog.renderersOn("x86"))
        assertTrue(AndroidRenderer.GL4ES in AndroidGraphicsCatalog.renderersOn("x86"))
    }

    @Test
    fun `LTW 没有预编译库，任何时候都返回空`() {
        assertNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.LTW, "arm64-v8a"))
        assertFalse(AndroidRenderer.LTW in AndroidGraphicsCatalog.renderersOn("arm64-v8a"))
    }

    @Test
    fun `认不出的架构返回空而不是猜一个`() {
        assertNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.GL4ES, "riscv64"))
        assertNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.GL4ES, ""))
        assertEquals(emptyList(), AndroidGraphicsCatalog.renderersOn("riscv64"))
    }

    @Test
    fun `Linux 惯用名也能认出来`() {
        assertEquals("arm64-v8a", assertNotNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.GL4ES, "aarch64")).abi)
        assertEquals("x86_64", assertNotNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.GL4ES, "amd64")).abi)
        assertEquals("armeabi-v7a", assertNotNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.GL4ES, "arm")).abi)
    }

    @Test
    fun `下载地址先走镜像再退回原生域`() {
        val bundle = assertNotNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.GL4ES, "arm64-v8a"))
        val file = bundle.files.first { it.name == "liblwjgl.so" }
        val urls = bundle.urlsOf(file)
        assertEquals(3, urls.size)
        assertTrue(urls[0].startsWith("https://gh-proxy.com/"))
        assertTrue(urls[1].startsWith("https://ghfast.top/"))
        assertTrue(urls[2].startsWith(AndroidGraphicsCatalog.RAW_BASE))
        assertTrue(urls.all { it.endsWith("arm64-v8a/liblwjgl.so") })
    }

    @Test
    fun `已经在盘上的文件不再下一次`() {
        val bundle = assertNotNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.GL4ES, "arm64-v8a"))
        val dir = File(root, "natives").also { it.mkdirs() }
        val one = bundle.files.first { !it.optional }
        File(dir, one.name).writeBytes(ByteArray(4))
        val items = bundle.downloadItems(dir)
        assertEquals(bundle.files.size - 1, items.size)
        assertFalse(items.any { it.target.endsWith(one.name) })
    }

    @Test
    fun `装齐了才算装好，缺一个必需件就不算`() {
        val bundle = assertNotNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.GL4ES, "arm64-v8a"))
        val dir = File(root, "natives").also { it.mkdirs() }
        assertFalse(bundle.isInstalled(dir))
        for (file in bundle.files.filter { !it.optional }) {
            File(dir, file.name).writeBytes(ByteArray(4))
        }
        assertTrue(bundle.isInstalled(dir))
        assertTrue(bundle.missingIn(dir).isEmpty())
    }

    @Test
    fun `缺件清单只报必需的那几个`() {
        val bundle = assertNotNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.ZINK, "arm64-v8a"))
        val dir = File(root, "natives").also { it.mkdirs() }
        val missing = bundle.missingIn(dir)
        assertEquals(bundle.files.count { !it.optional }, missing.size)
        assertFalse(missing.any { it.optional })
    }

    @Test
    fun `摘要里带后端名与体积`() {
        val bundle = assertNotNull(AndroidGraphicsCatalog.bundleOf(AndroidRenderer.GL4ES, "arm64-v8a"))
        assertTrue(bundle.summary.contains("arm64-v8a"))
        assertTrue(bundle.summary.contains("MB"))
    }

    @Test
    fun `体积文案不带负数`() {
        assertEquals("未知", megabytes(-1L))
        assertEquals("未知", megabytes(0L))
        assertEquals("1.0 MB", megabytes(1024L * 1024L))
    }
}
