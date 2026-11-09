package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstanceDetailsTest {
    private fun tempRoot(): File = Files.createTempDirectory("platypus-details-test").toFile()

    private fun makeInstance(root: File, id: String): Instance {
        val versionDir = File(File(root, "versions"), id)
        versionDir.mkdirs()
        val json = """
            {
              "id": "$id",
              "type": "release",
              "mainClass": "net.minecraft.client.main.Main",
              "assets": "5",
              "javaVersion": { "component": "java-runtime-delta", "majorVersion": 21 },
              "libraries": [
                { "name": "a:b:1", "downloads": { "artifact": { "path": "a/b/1/b-1.jar", "size": 10 } } },
                { "name": "c:d:2" }
              ]
            }
        """.trimIndent()
        File(versionDir, "$id.json").writeText(json)
        File(versionDir, "$id.jar").writeBytes(ByteArray(1024) { 1 })
        return Instance(
            id = id,
            name = id,
            gameVersion = id,
            directory = versionDir.absolutePath,
        )
    }

    @Test
    fun `详情里能读出 mainClass 与 Java 版本`() {
        val root = tempRoot()
        try {
            val instance = makeInstance(root, "1.21.1")
            val details = InstanceDetailsLoader.load(instance)
            assertEquals("net.minecraft.client.main.Main", details.mainClass)
            assertEquals(21, details.requiredJavaMajor)
            assertEquals("java-runtime-delta", details.javaComponent)
            assertEquals(2, details.libraryCount)
            assertTrue(details.versionJsonExists)
            assertTrue(details.clientJarExists)
            assertEquals(1024L, details.clientJarBytes)
            assertTrue(details.warnings.isEmpty(), details.warnings.toString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `实例目录就是游戏目录，共享目录指回游戏根`() {
        val root = tempRoot()
        try {
            val instance = makeInstance(root, "1.20.1")
            val details = InstanceDetailsLoader.load(instance)
            assertEquals(File(File(root, "versions"), "1.20.1").absoluteFile, details.layout.gameDir)
            assertEquals(root.absoluteFile, details.gameRoot)
            assertEquals(File(root, "libraries"), details.layout.librariesDir)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `按实例分开的目录会统计件数与体积`() {
        val root = tempRoot()
        try {
            val instance = makeInstance(root, "1.20.1")
            val versionDir = File(instance.directory)
            File(versionDir, "mods").apply { mkdirs() }
            File(versionDir, "mods/sodium.jar").writeBytes(ByteArray(500))
            File(versionDir, "mods/jei.jar").writeBytes(ByteArray(300))
            File(versionDir, "saves/World").apply { mkdirs() }
            File(versionDir, "saves/World/level.dat").writeBytes(ByteArray(120))

            val details = InstanceDetailsLoader.load(instance)
            val mods = details.dirs.first { it.label == "模组" }
            val saves = details.dirs.first { it.label == "存档" }
            val logs = details.dirs.first { it.label == "日志" }

            assertTrue(mods.exists)
            assertEquals(2, mods.fileCount)
            assertEquals(800L, mods.bytes)
            assertEquals(1, saves.fileCount)
            assertEquals(120L, saves.bytes)
            assertFalse(logs.exists)
            assertEquals(920L, details.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `没有版本 json 时给一句能看懂的警告而不是抛异常`() {
        val root = tempRoot()
        try {
            val versionDir = File(File(root, "versions"), "ghost")
            versionDir.mkdirs()
            val instance = Instance(id = "ghost", name = "ghost", gameVersion = "ghost", directory = versionDir.absolutePath)
            val details = InstanceDetailsLoader.load(instance)
            assertFalse(details.versionJsonExists)
            assertFalse(details.clientJarExists)
            assertEquals("", details.mainClass)
            assertTrue(details.warnings.any { it.contains("找不到版本文件") }, details.warnings.toString())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `版本 json 坏了也只是警告`() {
        val root = tempRoot()
        try {
            val versionDir = File(File(root, "versions"), "broken")
            versionDir.mkdirs()
            File(versionDir, "broken.json").writeText("{ 这不是 json")
            val instance = Instance(id = "broken", name = "broken", gameVersion = "broken", directory = versionDir.absolutePath)
            val details = InstanceDetailsLoader.load(instance)
            assertTrue(details.versionJsonExists)
            assertTrue(details.warnings.isNotEmpty())
            assertFalse(details.clientJarExists)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `带双引号的目录字符串也能读`() {
        val root = tempRoot()
        try {
            val instance = makeInstance(root, "1.19.2")
            val quoted = instance.copy(directory = "\"" + instance.directory + "\"")
            val details = InstanceDetailsLoader.load(quoted)
            assertNotNull(details.layout.versionDir)
            assertEquals("1.19.2", details.layout.versionDir.name)
            assertTrue(details.versionJsonExists)
        } finally {
            root.deleteRecursively()
        }
    }
}
