package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceDuplicatorTest {

    private fun tempDir(): File = Files.createTempDirectory("platypus-dup-test").toFile()

    private fun versionDir(root: File, id: String): File = File(root, "versions/" + id)

    private fun writeVersion(root: File, id: String, json: String): File {
        val dir = versionDir(root, id)
        dir.mkdirs()
        File(dir, id + ".json").writeText(json, Charsets.UTF_8)
        return dir
    }

    @Test
    fun nextFreeIdKeepsBaseWhenFree() {
        val root = tempDir()
        try {
            assertEquals("1.20.1-fabric-0.16.14", InstanceDuplicator.nextFreeId(root, "1.20.1-fabric-0.16.14"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nextFreeIdWalksUpFromTwo() {
        val root = tempDir()
        try {
            versionDir(root, "1.20.1-forge-47.4.28").mkdirs()
            assertEquals("1.20.1-forge-47.4.28_2", InstanceDuplicator.nextFreeId(root, "1.20.1-forge-47.4.28"))

            versionDir(root, "1.20.1-forge-47.4.28_2").mkdirs()
            assertEquals("1.20.1-forge-47.4.28_3", InstanceDuplicator.nextFreeId(root, "1.20.1-forge-47.4.28"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nextFreeIdDoesNotStackSuffixes() {
        val root = tempDir()
        try {
            versionDir(root, "1.21.4").mkdirs()
            versionDir(root, "1.21.4_2").mkdirs()
            // 已经是 _2 形态时不该产出 _2_2
            assertEquals("1.21.4_3", InstanceDuplicator.nextFreeId(root, "1.21.4_2"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun suffixHelpersMatchOnlyTrailingNumber() {
        assertEquals("1.20.1-forge-47.4.28", InstanceDuplicator.stripCopySuffix("1.20.1-forge-47.4.28_2"))
        assertEquals("1.20.1-forge-47.4.28", InstanceDuplicator.stripCopySuffix("1.20.1-forge-47.4.28"))
        assertTrue(InstanceDuplicator.hasCopySuffix("neoforge-20.4.237_3"))
        assertFalse(InstanceDuplicator.hasCopySuffix("neoforge-20.4.237"))
        assertEquals(3, InstanceDuplicator.copyIndex("neoforge-20.4.237_3"))
        assertNull(InstanceDuplicator.copyIndex("neoforge-20.4.237"))
    }

    @Test
    fun canDuplicateNeedsVersionJson() {
        val root = tempDir()
        try {
            val dir = versionDir(root, "1.21.4")
            dir.mkdirs()
            assertFalse(InstanceDuplicator.canDuplicate(root, "1.21.4"))
            File(dir, "1.21.4.json").writeText("""{"id":"1.21.4"}""", Charsets.UTF_8)
            assertTrue(InstanceDuplicator.canDuplicate(root, "1.21.4"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateCopiesOnlyVersionShell() {
        val root = tempDir()
        try {
            val sourceId = "1.20.1-fabric-0.16.14"
            val targetId = "1.20.1-fabric-0.16.14_2"
            val source = writeVersion(
                root,
                sourceId,
                """{"id":"$sourceId","inheritsFrom":"1.20.1","mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient"}""",
            )
            File(source, sourceId + ".jar").writeBytes(ByteArray(2048) { 7 })
            File(source, "options.txt").writeText("lang:zh_cn\n", Charsets.UTF_8)
            File(source, sourceId + "-install.log").writeText("noise", Charsets.UTF_8)
            InstallMark.mark(source)
            File(source, "mods").mkdirs()
            File(source, "mods/example.jar").writeBytes(ByteArray(16))
            File(source, "saves/world/level.dat").mkdirs()

            val target = InstanceDuplicator.duplicate(root, sourceId, targetId)

            assertEquals(versionDir(root, targetId).absolutePath, target.absolutePath)
            // 版本壳跟着改名
            val json = File(target, targetId + ".json")
            val jar = File(target, targetId + ".jar")
            assertTrue(json.isFile)
            assertTrue(jar.isFile)
            assertEquals(2048L, jar.length())
            // json 里的 id 被改写，inheritsFrom 等其余字段原样保留
            val text = json.readText(Charsets.UTF_8)
            assertTrue(text.contains(""""id":"$targetId""""))
            assertTrue(text.contains(""""inheritsFrom":"1.20.1""""))
            assertTrue(text.contains("KnotClient"))
            // 旧的 <源 id>.json/.jar 不该出现在新目录里
            assertFalse(File(target, sourceId + ".json").exists())
            assertFalse(File(target, sourceId + ".jar").exists())
            // 其它顶层文件原样带走
            assertTrue(File(target, "options.txt").isFile)
            // 日志与「正在装」标记不属于版本壳
            assertFalse(File(target, sourceId + "-install.log").exists())
            assertFalse(File(target, InstallMark.FILE_NAME).exists())
            // 实例数据（mods / saves）一个都不带
            assertFalse(File(target, "mods").exists())
            assertFalse(File(target, "saves").exists())
            // 源目录原封不动
            assertTrue(File(source, sourceId + ".json").isFile)
            assertTrue(File(source, "mods/example.jar").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateRefusesOccupiedTarget() {
        val root = tempDir()
        try {
            writeVersion(root, "1.21.4", """{"id":"1.21.4"}""")
            versionDir(root, "1.21.4_2").mkdirs()
            assertFailsWith<IllegalStateException> {
                InstanceDuplicator.duplicate(root, "1.21.4", "1.21.4_2")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateRefusesDirWithoutVersionJson() {
        val root = tempDir()
        try {
            versionDir(root, "1.21.4").mkdirs()
            assertFailsWith<IllegalStateException> {
                InstanceDuplicator.duplicate(root, "1.21.4", "1.21.4_2")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
