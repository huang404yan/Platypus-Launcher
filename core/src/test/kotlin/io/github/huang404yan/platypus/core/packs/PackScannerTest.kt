package io.github.huang404yan.platypus.core.packs

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackScannerTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    /** 造一个只含指定条目的 zip。 */
    private fun zip(dir: File, name: String, entries: Map<String, String>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((entryName, content) in entries) {
                out.putNextEntry(ZipEntry(entryName))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return file
    }

    /** 和真资源包一样的形状：description 带 § 颜色码，pack_format 15。 */
    private val packMcmeta = """
        {
          "pack": {
            "description": "§a■ 1.11.2 BASE§8\n■ By FreshLX",
            "pack_format": 15
          }
        }
    """.trimIndent()

    @Test
    fun `目录不存在时 exists 为 false 且不抛`() {
        val root = tempDir("packs-none")
        val result = PackScanner.scan(PackKind.SHADER, root)
        assertFalse(result.exists)
        assertEquals(0, result.totalCount)
        assertEquals("shaderpacks", result.dir.name)
    }

    @Test
    fun `资源包能读出显示名并去掉颜色码`() {
        val root = tempDir("packs-rp")
        val dir = File(root, "resourcepacks").also { it.mkdirs() }
        zip(dir, "FreshAnimations_v1.11.2.zip", mapOf("pack.mcmeta" to packMcmeta, "pack.png" to "png"))

        val result = PackScanner.scan(PackKind.RESOURCE_PACK, root)
        assertTrue(result.exists)
        assertEquals(1, result.packs.size)
        val entry = result.packs.single()
        assertEquals("FreshAnimations_v1.11.2.zip", entry.fileName)
        // § 颜色码去掉，换行折成空格
        assertEquals("■ 1.11.2 BASE ■ By FreshLX", entry.displayName)
        assertEquals(15, entry.packFormat)
        assertNull(entry.problem)
        assertTrue(entry.enabled)
    }

    @Test
    fun `没有 pack mcmeta 的资源包仍列出但带问题说明`() {
        val root = tempDir("packs-bad")
        val dir = File(root, "resourcepacks").also { it.mkdirs() }
        zip(dir, "handmade.zip", mapOf("assets/minecraft/foo.txt" to "x"))

        val entry = PackScanner.scan(PackKind.RESOURCE_PACK, root).packs.single()
        assertNotNull(entry.problem)
        assertTrue(entry.problem!!.contains("pack.mcmeta"))
        // 没有显示名时退回文件名（去掉 .zip）
        assertEquals("handmade", entry.title)
    }

    @Test
    fun `光影认 shaders 目录而不是 pack mcmeta`() {
        val root = tempDir("packs-shader")
        val dir = File(root, "shaderpacks").also { it.mkdirs() }
        zip(
            dir,
            "ComplementaryReimagined_r5.9.3.zip",
            mapOf(
                "shaders/shader.properties" to "x",
                "shaders/lib/common.glsl" to "//",
                "HOW TO INSTALL.txt" to "put in shaderpacks",
            ),
        )

        val entry = PackScanner.scan(PackKind.SHADER, root).packs.single()
        assertNull(entry.problem)
        assertNull(entry.packFormat)
        assertEquals("ComplementaryReimagined_r5.9.3", entry.title)
    }

    @Test
    fun `光影 zip 没有 shaders 目录时报问题`() {
        val root = tempDir("packs-noshader")
        val dir = File(root, "shaderpacks").also { it.mkdirs() }
        zip(dir, "wrong.zip", mapOf("README.txt" to "this is a resource pack"))

        val entry = PackScanner.scan(PackKind.SHADER, root).packs.single()
        assertTrue(entry.problem!!.contains("shaders/"))
    }

    @Test
    fun `坏的 zip 不抛异常只折成 problem`() {
        val root = tempDir("packs-broken")
        val dir = File(root, "resourcepacks").also { it.mkdirs() }
        val broken = File(dir, "half-downloaded.zip")
        broken.writeText("PK but truncated", Charsets.UTF_8)

        val entry = PackScanner.scan(PackKind.RESOURCE_PACK, root).packs.single()
        assertNotNull(entry.problem)
        assertEquals("half-downloaded.zip", entry.fileName)
    }

    @Test
    fun `停用的包不混进已启用列表`() {
        val root = tempDir("packs-disabled")
        val dir = File(root, "shaderpacks").also { it.mkdirs() }
        zip(dir, "on.zip", mapOf("shaders/a.glsl" to "//"))
        zip(dir, "off.zip.disabled", mapOf("shaders/b.glsl" to "//"))

        val result = PackScanner.scan(PackKind.SHADER, root)
        assertEquals(1, result.packs.size)
        assertEquals(1, result.disabled.size)
        assertEquals("on.zip", result.packs.single().fileName)
        assertFalse(result.disabled.single().enabled)
    }

    @Test
    fun `文件夹形态的包也能读元数据`() {
        val root = tempDir("packs-dir")
        val dir = File(root, "resourcepacks").also { it.mkdirs() }
        val folder = File(dir, "MyPack").also { it.mkdirs() }
        File(folder, "pack.mcmeta").writeText(packMcmeta, Charsets.UTF_8)
        File(folder, "pack.png").writeText("png", Charsets.UTF_8)

        val entry = PackScanner.scan(PackKind.RESOURCE_PACK, root).packs.single()
        assertTrue(entry.isDirectory)
        assertEquals("■ 1.11.2 BASE ■ By FreshLX", entry.displayName)
        assertTrue(entry.sizeBytes > 0)
    }

    @Test
    fun `非包文件被跳过并记一条警告`() {
        val root = tempDir("packs-noise")
        val dir = File(root, "shaderpacks").also { it.mkdirs() }
        File(dir, "说明.txt").writeText("readme", Charsets.UTF_8)
        File(dir, "random.jar").writeText("jar", Charsets.UTF_8)

        val result = PackScanner.scan(PackKind.SHADER, root)
        assertEquals(0, result.packs.size)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings.single().contains("random.jar"))
    }

    @Test
    fun `supported_formats 能顶替缺失的 pack_format`() {
        val root = tempDir("packs-supported")
        val dir = File(root, "resourcepacks").also { it.mkdirs() }
        zip(
            dir,
            "newfmt.zip",
            mapOf(
                "pack.mcmeta" to """
                    {"pack":{"description":"new format","supported_formats":{"min_inclusive":15,"max_inclusive":75}}}
                """.trimIndent(),
            ),
        )

        val entry = PackScanner.scan(PackKind.RESOURCE_PACK, root).packs.single()
        assertEquals(15, entry.packFormat)
        assertEquals("new format", entry.displayName)
    }

    @Test
    fun `dirOf 指向实例目录下的子目录`() {
        val root = tempDir("packs-dirof")
        assertEquals(
            File(root, "shaderpacks").absolutePath,
            PackScanner.dirOf(PackKind.SHADER, root).absolutePath,
        )
        assertEquals(
            File(root, "resourcepacks").absolutePath,
            PackScanner.dirOf(PackKind.RESOURCE_PACK, root).absolutePath,
        )
    }
}
