package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.instance.LoaderKind
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModScannerTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    /** 造一个只含指定条目的 jar。 */
    private fun jar(dir: File, name: String, entries: Map<String, String>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((entryName, content) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private val fabricModJson = """
        {
          "schemaVersion": 1,
          "id": "sodium",
          "version": "0.5.13+mc1.20.1",
          "name": "Sodium",
          "description": "Modern rendering engine"
        }
    """.trimIndent()

    private val forgeModsToml = """
        modLoader="javafml"
        loaderVersion="[47,)"
        license="LGPL-3.0"

        [[mods]]
        modId="jei"
        version="15.2.0.27"
        displayName="Just Enough Items"

        [[dependencies.example]]
        modId="forge"
        mandatory=true
    """.trimIndent()

    @Test
    fun `没有 mods 目录时 exists 为 false 且不崩`() {
        val gameDir = tempDir("platypus-mods-none")
        val result = ModScanner.scan(gameDir)
        assertFalse(result.exists, "目录不存在就该是 false")
        assertTrue(result.mods.isEmpty())
        assertEquals(0, result.totalCount)
        assertEquals(File(gameDir, "mods").absolutePath, result.dir.absolutePath)
    }

    @Test
    fun `空 mods 目录 exists 为 true 但没模组`() {
        val gameDir = tempDir("platypus-mods-empty")
        assertTrue(File(gameDir, "mods").mkdirs())
        val result = ModScanner.scan(gameDir)
        assertTrue(result.exists)
        assertEquals(0, result.totalCount)
        assertTrue(result.warnings.isEmpty(), "空目录不该有告警")
    }

    @Test
    fun `Fabric 模组读 fabric 点 mod 点 json`() {
        val gameDir = tempDir("platypus-mods-fabric")
        val modsDir = File(gameDir, "mods").also { it.mkdirs() }
        jar(modsDir, "sodium-fabric-0.5.13+mc1.20.1.jar", mapOf("fabric.mod.json" to fabricModJson))

        val result = ModScanner.scan(gameDir)
        assertEquals(1, result.mods.size)
        val mod = result.mods[0]
        assertEquals("sodium", mod.modId)
        assertEquals("Sodium", mod.displayName)
        assertEquals("0.5.13+mc1.20.1", mod.version)
        assertEquals(LoaderKind.FABRIC, mod.loader)
        assertTrue(mod.enabled)
        assertNull(mod.problem)
        assertEquals("Sodium", mod.title, "有显示名就用显示名")
    }

    @Test
    fun `Forge 模组读 mods 点 toml 且只取第一段`() {
        val gameDir = tempDir("platypus-mods-forge")
        val modsDir = File(gameDir, "mods").also { it.mkdirs() }
        jar(modsDir, "jei-1.20.1-forge-15.2.0.27.jar", mapOf("META-INF/mods.toml" to forgeModsToml))

        val result = ModScanner.scan(gameDir)
        assertEquals(1, result.mods.size)
        val mod = result.mods[0]
        assertEquals("jei", mod.modId)
        assertEquals("Just Enough Items", mod.displayName)
        assertEquals("15.2.0.27", mod.version)
        assertEquals(LoaderKind.FORGE, mod.loader)
    }

    @Test
    fun `NeoForge 优先读 neoforge 点 mods 点 toml`() {
        val gameDir = tempDir("platypus-mods-neo")
        val modsDir = File(gameDir, "mods").also { it.mkdirs() }
        val toml = forgeModsToml.replace("jei", "neoforgeexample")
        jar(modsDir, "neo.jar", mapOf("META-INF/neoforge.mods.toml" to toml))

        val result = ModScanner.scan(gameDir)
        assertEquals(LoaderKind.NEOFORGE, result.mods[0].loader)
    }

    @Test
    fun `停用的 jar 只列不启用`() {
        val gameDir = tempDir("platypus-mods-disabled")
        val modsDir = File(gameDir, "mods").also { it.mkdirs() }
        jar(modsDir, "on.jar", mapOf("fabric.mod.json" to fabricModJson))
        jar(modsDir, "off.jar.disabled", mapOf("fabric.mod.json" to fabricModJson))

        val result = ModScanner.scan(gameDir)
        assertEquals(1, result.mods.size)
        assertEquals(1, result.disabled.size)
        assertFalse(result.disabled[0].enabled)
        assertEquals(2, result.totalCount)
    }

    /** 坏 jar 不能让页面崩：活动模组列表里照样出现，只是带 problem。 */
    @Test
    fun `坏 jar 保留条目并标 problem`() {
        val gameDir = tempDir("platypus-mods-broken")
        val modsDir = File(gameDir, "mods").also { it.mkdirs() }
        File(modsDir, "broken.jar").writeBytes(byteArrayOf(1, 2, 3, 4))

        val result = ModScanner.scan(gameDir)
        assertEquals(1, result.mods.size)
        assertEquals("broken.jar", result.mods[0].fileName)
        assertTrue(result.mods[0].problem != null, "坏 jar 必须有 problem 说明")
        assertEquals("broken.jar", result.mods[0].title, "读不到元数据时退回文件名")
    }

    /** 不是模组但确实是 jar（比如只放了 LICENSE）也不该崩，只是没元数据。 */
    @Test
    fun `没有声明文件的 jar 报读不出`() {
        val gameDir = tempDir("platypus-mods-plain")
        val modsDir = File(gameDir, "mods").also { it.mkdirs() }
        jar(modsDir, "plain.jar", mapOf("LICENSE" to "Apache-2.0"))

        val result = ModScanner.scan(gameDir)
        assertEquals(1, result.mods.size)
        assertTrue(result.mods[0].problem != null)
    }

    @Test
    fun `非 jar 文件不列进模组但给一条告警`() {
        val gameDir = tempDir("platypus-mods-mixed")
        val modsDir = File(gameDir, "mods").also { it.mkdirs() }
        jar(modsDir, "ok.jar", mapOf("fabric.mod.json" to fabricModJson))
        // readme.txt 与 .DS_Store 是 mods 目录里常见的搭车文件，不告警；真正没用的文件才告警。
        File(modsDir, "readme.txt").writeText("说明")
        File(modsDir, ".DS_Store").writeText("x")
        File(modsDir, "setup.exe").writeText("x")

        val result = ModScanner.scan(gameDir)
        assertEquals(1, result.totalCount)
        assertEquals(1, result.warnings.size, "只有 setup.exe 该告警")
        assertTrue(result.warnings[0].contains("setup.exe"))
    }

    @Test
    fun `多个模组按文件名排序`() {
        val gameDir = tempDir("platypus-mods-sort")
        val modsDir = File(gameDir, "mods").also { it.mkdirs() }
        jar(modsDir, "b.jar", mapOf("fabric.mod.json" to fabricModJson))
        jar(modsDir, "a.jar", mapOf("fabric.mod.json" to fabricModJson))

        val result = ModScanner.scan(gameDir)
        assertEquals(listOf("a.jar", "b.jar"), result.mods.map { it.fileName })
    }

    @Test
    fun `modsDir 指向游戏目录下的 mods`() {
        val gameDir = tempDir("platypus-mods-dir")
        assertEquals(File(gameDir, "mods").absolutePath, ModScanner.modsDir(gameDir).absolutePath)
    }
}
