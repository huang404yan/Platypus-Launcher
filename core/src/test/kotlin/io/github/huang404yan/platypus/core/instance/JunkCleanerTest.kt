package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JunkCleanerTest {

    private fun tempRoot(): File = Files.createTempDirectory("platypus-junk-test").toFile()

    private fun instanceIn(root: File, id: String, isolated: Boolean = true): Instance {
        val versionDir = File(File(root, "versions"), id)
        versionDir.mkdirs()
        return Instance(
            id = id,
            name = "测试 " + id,
            gameVersion = id,
            directory = versionDir.absolutePath,
            isolated = isolated,
            isolatedFollowsDefault = false,
        )
    }

    private fun write(dir: File, name: String, bytes: Int): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes) { 1 })
        return file
    }

    @Test
    fun `隔离时日志长在版本目录里，扫得到`() {
        val root = tempRoot()
        try {
            val instance = instanceIn(root, "1.21.1")
            val versionDir = File(instance.directory)
            write(File(versionDir, "logs"), "latest.log", 100)
            write(File(versionDir, "logs"), "debug.log", 50)
            write(File(versionDir, "crash-reports"), "crash-1.txt", 20)

            val report = JunkCleaner.scan(instance)
            assertEquals(3, report.totalFiles)
            assertEquals(170L, report.totalBytes)
            assertFalse(report.isEmpty)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `不隔离时日志长在游戏根目录，也扫得到`() {
        val root = tempRoot()
        try {
            val instance = instanceIn(root, "1.21.1", isolated = false)
            write(File(root, "logs"), "latest.log", 100)

            val report = JunkCleaner.scan(instance)
            assertEquals(1, report.totalFiles)
            assertEquals(100L, report.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `隔离时同一批文件不会被算两遍`() {
        val root = tempRoot()
        try {
            // gameDir 与 versionDir 是同一个目录（默认隔离），只该统计一次。
            val instance = instanceIn(root, "1.21.1")
            write(File(instance.directory, "logs"), "latest.log", 100)

            val report = JunkCleaner.scan(instance)
            assertEquals(1, report.totalFiles)
            assertEquals(100L, report.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `clean 删掉文件但保留 logs 目录本身`() {
        val root = tempRoot()
        try {
            val instance = instanceIn(root, "1.21.1")
            val versionDir = File(instance.directory)
            val logs = File(versionDir, "logs")
            write(logs, "latest.log", 100)
            write(File(logs, "2024"), "old.log", 30)
            // 非垃圾文件必须留着
            write(File(versionDir, "saves"), "level.dat", 40)
            write(File(versionDir, "mods"), "a.jar", 70)

            val report = JunkCleaner.scan(instance)
            val outcome = JunkCleaner.clean(report)

            assertEquals(2, outcome.deletedFiles)
            assertEquals(130L, outcome.freedBytes)
            assertTrue(outcome.ok)
            assertTrue(logs.isDirectory, "logs 目录本身要留着")
            assertFalse(File(logs, "latest.log").exists())
            assertTrue(File(File(versionDir, "saves"), "level.dat").exists())
            assertTrue(File(File(versionDir, "mods"), "a.jar").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `mixout 是文件时直接删掉`() {
        val root = tempRoot()
        try {
            val instance = instanceIn(root, "1.21.1")
            val versionDir = File(instance.directory)
            write(versionDir, ".mixin.out", 12)

            val report = JunkCleaner.scan(instance)
            assertEquals(1, report.totalFiles)
            val outcome = JunkCleaner.clean(report)
            assertEquals(1, outcome.deletedFiles)
            assertEquals(12L, outcome.freedBytes)
            assertFalse(File(versionDir, ".mixin.out").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `没有垃圾时扫描是空的，清理什么都不做`() {
        val root = tempRoot()
        try {
            val instance = instanceIn(root, "1.21.1")
            write(File(instance.directory, "mods"), "a.jar", 10)

            val report = JunkCleaner.scan(instance)
            assertTrue(report.isEmpty)
            assertEquals(0L, report.totalBytes)

            val outcome = JunkCleaner.clean(report)
            assertEquals(0, outcome.deletedFiles)
            assertEquals(0L, outcome.freedBytes)
            assertTrue(outcome.ok)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `扫两次结果一样，清理之后变成空的`() {
        val root = tempRoot()
        try {
            val instance = instanceIn(root, "1.21.1")
            write(File(instance.directory, "crash-reports"), "crash-1.txt", 8)

            val first = JunkCleaner.scan(instance)
            val second = JunkCleaner.scan(instance)
            assertEquals(first.totalFiles, second.totalFiles)

            JunkCleaner.clean(first)
            assertTrue(JunkCleaner.scan(instance).isEmpty)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `不存在的目录也留在清单里，只是 0 个文件`() {
        val root = tempRoot()
        try {
            val instance = instanceIn(root, "1.21.1")
            val report = JunkCleaner.scan(instance)
            assertTrue(report.targets.isNotEmpty())
            assertTrue(report.targets.none { it.exists })
            assertTrue(report.isEmpty)
        } finally {
            root.deleteRecursively()
        }
    }
}
