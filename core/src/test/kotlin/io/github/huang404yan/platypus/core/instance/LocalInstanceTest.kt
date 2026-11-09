package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「本地已经有这一版，就别再下载」这条分支的回归测试。
 *
 * 这条判断错了后果不小：判宽了会把半截目录登记成实例，用户点启动才发现缺文件；
 * 判严了会让本地已下好的版本白下一遍，几分钟起步。
 */
class LocalInstanceTest {

    private val roots = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
        roots.clear()
    }

    /** 造一个最小可用的版本目录：versions/<id>/<id>.json + <id>.jar。 */
    private fun makeVersion(
        id: String,
        jar: Boolean = true,
        jarBytes: Int = 8,
        inheritsFrom: String? = null,
    ): Pair<File, File> {
        val root = Files.createTempDirectory("platypus-local").toFile()
        roots += root
        val dir = File(File(root, "versions"), id)
        dir.mkdirs()
        val inherit = if (inheritsFrom == null) "" else """"inheritsFrom": "$inheritsFrom","""
        File(dir, "$id.json").writeText(
            """
            {
              "id": "$id",
              $inherit
              "type": "release",
              "mainClass": "net.minecraft.client.main.Main",
              "assets": "5",
              "downloads": { "client": { "sha1": "x", "size": 8, "url": "https://example.invalid/c.jar" } }
            }
            """.trimIndent(),
        )
        if (jar) File(dir, "$id.jar").writeBytes(ByteArray(jarBytes) { 1 })
        return root to dir
    }

    @Test
    fun `本地版本齐全时直接认养，不需要联网`() {
        val (root, dir) = makeVersion("1.20.1")

        val adopted = LocalInstance.adopt(root, "1.20.1")

        assertNotNull(adopted)
        assertEquals("1.20.1", adopted.id)
        assertEquals("1.20.1", adopted.name)
        assertEquals("1.20.1", adopted.gameVersion)
        assertEquals(LoaderKind.VANILLA, adopted.loader)
        assertEquals(dir.absolutePath, adopted.directory)
        assertTrue(adopted.registered, "认养出来的实例必须是已登记状态，否则界面改不了内存")
        assertEquals(Instance.DEFAULT_MEMORY_MB, adopted.memoryMb)
    }

    @Test
    fun `没有 client jar 或者 jar 是空文件时不算数`() {
        val (noJarRoot, _) = makeVersion("1.19.4", jar = false)
        assertNull(LocalInstance.adopt(noJarRoot, "1.19.4"), "缺 jar 应该退回安装流程")

        val (emptyJarRoot, _) = makeVersion("1.19.2", jarBytes = 0)
        assertNull(LocalInstance.adopt(emptyJarRoot, "1.19.2"), "0 字节 jar 是没下完，不能认")
    }

    @Test
    fun `版本 json 不存在时返回 null`() {
        val root = Files.createTempDirectory("platypus-local").toFile()
        roots += root
        File(File(root, "versions"), "1.18.2").mkdirs()

        assertNull(LocalInstance.adopt(root, "1.18.2"))
    }

    @Test
    fun `自定义名字与内存会带上，越界内存夹到允许范围`() {
        val (root, _) = makeVersion("1.20.1")

        val named = LocalInstance.adopt(root, "1.20.1", name = "  我的世界  ", memoryMb = 4096)
        assertNotNull(named)
        assertEquals("我的世界", named.name, "名字要去掉首尾空白")
        assertEquals(4096, named.memoryMb)

        val clamped = LocalInstance.adopt(root, "1.20.1", name = "  ", memoryMb = 999999)
        assertNotNull(clamped)
        assertEquals("1.20.1", clamped.name, "名字只有空白时退回版本 id")
        assertEquals(Instance.MAX_MEMORY_MB, clamped.memoryMb)
    }

    @Test
    fun `加载器版本会认出加载器，gameVersion 取底层原版号`() {
        val (root, _) = makeVersion("1.20.1-forge-47.2.0", inheritsFrom = "1.20.1")

        val adopted = LocalInstance.adopt(root, "1.20.1-forge-47.2.0")

        assertNotNull(adopted)
        assertEquals(LoaderKind.FORGE, adopted.loader)
        assertEquals("47.2.0", adopted.loaderVersion)
        assertEquals("1.20.1", adopted.gameVersion, "gameVersion 是底层原版号，不是加载器版本号")
    }

    @Test
    fun `带空白的版本号会被 trim`() {
        val (root, _) = makeVersion("1.20.1")

        assertNotNull(LocalInstance.adopt(root, "  1.20.1  "))
        assertNull(LocalInstance.adopt(root, "   "), "全空白等于没填版本号")
    }
}
