package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceScannerTest {

    private fun tempDir(): File = Files.createTempDirectory("platypus-instance-test").toFile()

    private fun writeVersion(root: File, dirName: String, json: String) {
        val dir = File(root, "versions/$dirName")
        dir.mkdirs()
        File(dir, "$dirName.json").writeText(json, Charsets.UTF_8)
    }

    @Test
    fun detectsVanilla() {
        val (kind, ver) = InstanceScanner.detectLoader("1.21.4")
        assertEquals(LoaderKind.VANILLA, kind)
        assertNull(ver)
    }

    @Test
    fun detectsForge() {
        val (kind, ver) = InstanceScanner.detectLoader("1.20.1-forge-47.2.0")
        assertEquals(LoaderKind.FORGE, kind)
        assertEquals("47.2.0", ver)
    }

    @Test
    fun detectsFabric() {
        val (kind, ver) = InstanceScanner.detectLoader("fabric-loader-0.15.11-1.20.1")
        assertEquals(LoaderKind.FABRIC, kind)
        assertEquals("0.15.11", ver)
    }

    @Test
    fun detectsNeoForge() {
        val (kind, ver) = InstanceScanner.detectLoader("neoforge-20.4.237")
        assertEquals(LoaderKind.NEOFORGE, kind)
        assertEquals("20.4.237", ver)
    }

    @Test
    fun detectsQuilt() {
        val (kind, _) = InstanceScanner.detectLoader("quilt-loader-0.23.1-1.20.1")
        assertEquals(LoaderKind.QUILT, kind)
    }

    @Test
    fun detectsPclStyleForge() {
        // PCL 合并后的版本号形态：1.20.1-Forge_47.4.28（没有 inheritsFrom）
        val (kind, ver) = InstanceScanner.detectLoader("1.20.1-Forge_47.4.28")
        assertEquals(LoaderKind.FORGE, kind)
        assertEquals("47.4.28", ver)
        assertEquals("1.20.1", InstanceScanner.gameVersionFromId("1.20.1-Forge_47.4.28"))
    }

    @Test
    fun extractsGameVersionFromLoaderId() {
        assertEquals("1.20.1", InstanceScanner.gameVersionFromId("fabric-loader-0.15.11-1.20.1"))
        assertEquals("1.21.4", InstanceScanner.gameVersionFromId("1.21.4"))
        assertNull(InstanceScanner.gameVersionFromId("snapshot-abc"))
    }

    @Test
    fun detectsOptiFine() {
        val (kind, _) = InstanceScanner.detectLoader("1.20.1-OptiFine_HD_U_I6")
        assertEquals(LoaderKind.OPTIFINE, kind)
    }

    @Test
    fun rejectsNonGameDirectory() {
        val dir = tempDir()
        try {
            val result = InstanceScanner.scan(dir)
            assertFalse(result.isGameDirectory)
            assertTrue(result.instances.isEmpty())
            assertTrue(result.warnings.isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingDirectory() {
        val missing = File(tempDir(), "not-exist")
        val result = InstanceScanner.scan(missing)
        assertFalse(result.isGameDirectory)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun scansVersionsWithLoaders() {
        val root = tempDir()
        try {
            writeVersion(root, "1.21.4", """{"id":"1.21.4","type":"release"}""")
            writeVersion(
                root,
                "1.20.1-forge-47.2.0",
                """{"id":"1.20.1-forge-47.2.0","inheritsFrom":"1.20.1","type":"release"}""",
            )
            // 只有目录没有 json 的版本应被跳过并记 warning
            File(root, "versions/1.19.2").mkdirs()

            val result = InstanceScanner.scan(root)
            assertTrue(result.isGameDirectory)
            assertEquals(2, result.instances.size)
            assertEquals(1, result.warnings.size)

            val vanilla = result.instances.first { it.id == "1.21.4" }
            assertEquals(LoaderKind.VANILLA, vanilla.loader)
            assertEquals("1.21.4", vanilla.gameVersion)

            val forge = result.instances.first { it.id == "1.20.1-forge-47.2.0" }
            assertEquals(LoaderKind.FORGE, forge.loader)
            assertEquals("47.2.0", forge.loaderVersion)
            assertEquals("1.20.1", forge.gameVersion)
            assertTrue(result.libraryVersions.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun foldsVanillaThatOnlyLoadersInherit() {
        // 装 Forge 时安装器会把原版 1.20.1 目录建出来并让它当父版本。
        // 那条原版不该当成第二个实例列出来，否则用户以为装重了。
        val root = tempDir()
        try {
            writeVersion(root, "1.20.1", """{"id":"1.20.1","type":"release"}""")
            writeVersion(
                root,
                "1.20.1-forge-47.2.0",
                """{"id":"1.20.1-forge-47.2.0","inheritsFrom":"1.20.1","type":"release"}""",
            )

            val result = InstanceScanner.scan(root)
            assertEquals(listOf("1.20.1-forge-47.2.0"), result.instances.map { it.id })
            assertEquals(listOf("1.20.1"), result.libraryVersions)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun foldsInheritedVanillaButKeepsOtherVersions() {
        // 同一个目录里既有被继承的原版、也有没人继承的其他版本：
        // 被继承的那条折叠，其余照常列出来。
        val root = tempDir()
        try {
            writeVersion(root, "1.20.1", """{"id":"1.20.1","type":"release"}""")
            writeVersion(
                root,
                "1.20.1-forge-47.2.0",
                """{"id":"1.20.1-forge-47.2.0","inheritsFrom":"1.20.1","type":"release"}""",
            )
            writeVersion(root, "1.21.4", """{"id":"1.21.4","type":"release"}""")

            val result = InstanceScanner.scan(root)
            assertEquals(listOf("1.20.1-forge-47.2.0", "1.21.4"), result.instances.map { it.id })
            // 1.20.1 仍被继承、且自己是纯原版 → 折叠
            assertEquals(listOf("1.20.1"), result.libraryVersions)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsVanillaWhenNothingInheritsIt() {
        val root = tempDir()
        try {
            writeVersion(root, "1.20.1", """{"id":"1.20.1","type":"release"}""")
            writeVersion(root, "1.21.4", """{"id":"1.21.4","type":"release"}""")

            val result = InstanceScanner.scan(root)
            assertEquals(listOf("1.20.1", "1.21.4"), result.instances.map { it.id })
            assertTrue(result.libraryVersions.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
