package io.github.huang404yan.platypus.core.install

import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 预览阶段的「包里有没有模组」判断。
 *
 * 用户报过「导入后 mod 没进去」——他那份包是 PCL 导出的 MCBBS 包，`overrides/mods/` 里零个 jar，
 * 清单里也没写 files。以前界面只显示「0 个文件要下载」，看不出包里到底有没有东西，
 * 只能进游戏才发现没模组。这里锁住「预览就能看出包是空的」这条。
 */
class ModpackPreviewTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "platypus-modpack-preview-" + System.nanoTime()).apply { mkdirs() }

    private fun pack(dir: File, name: String, entries: Map<String, String>): File {
        val archive = File(dir, name)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for ((entryName, text) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return archive
    }

    private val packmeta = """
        {"manifestType":"minecraftModpack","manifestVersion":1,"name":"空包","version":"1.0",
         "addons":[{"id":"game","version":"1.20.1"},{"id":"forge","version":"47.4.28"}]}
    """.trimIndent()

    @Test
    fun `MCBBS 包的 mods 是空的时候 预览就给出警告`() = runBlocking {
        val dir = tempDir()
        try {
            val archive = pack(
                dir,
                "empty.zip",
                mapOf(
                    "mcbbs.packmeta" to packmeta,
                    "overrides/" to "",
                    "overrides/mods/" to "",
                    "overrides/config/a.toml" to "x=1",
                    "overrides/tacz/ak47.zip" to "pk",
                ),
            )
            val preview = ModpackImporter.inspect(archive)
            assertEquals(0, preview.fileCount, "MCBBS 包清单里的 files 恒为空")
            assertEquals(0, preview.content.modJars)
            assertEquals(2, preview.content.totalFiles)
            assertTrue(
                preview.manifest.warnings.any { it.contains("mods/ 是空的") },
                "预览应当明说包里没有模组：" + preview.manifest.warnings,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `带模组的 MCBBS 包不该冒出空包警告`() = runBlocking {
        val dir = tempDir()
        try {
            val archive = pack(
                dir,
                "full.zip",
                mapOf(
                    "mcbbs.packmeta" to packmeta,
                    "overrides/mods/a.jar" to "j",
                    "overrides/mods/b.jar" to "j",
                ),
            )
            val preview = ModpackImporter.inspect(archive)
            assertEquals(2, preview.content.modJars)
            assertTrue(
                preview.manifest.warnings.none { it.contains("mods/ 是空的") },
                "有模组的包不该说 mods 是空的：" + preview.manifest.warnings,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `配置层完全没文件时警告说得更重`() = runBlocking {
        val dir = tempDir()
        try {
            val archive = pack(
                dir,
                "bare.zip",
                mapOf("mcbbs.packmeta" to packmeta),
            )
            val preview = ModpackImporter.inspect(archive)
            assertTrue(preview.content.isEmpty)
            assertTrue(
                preview.manifest.warnings.any { it.contains("没有带任何文件") },
                "配置层空着时应当说清楚：" + preview.manifest.warnings,
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
