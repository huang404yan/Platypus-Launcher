package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpaceAnalyzerTest {

    private fun tempRoot(): File = Files.createTempDirectory("platypus-space-test").toFile()

    private fun write(dir: File, name: String, bytes: Int) {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes) { 1 })
    }

    @Test
    fun `按大小降序列出前几项`() {
        val root = tempRoot()
        try {
            write(File(root, "assets"), "a.bin", 300)
            write(File(root, "libraries"), "b.jar", 500)
            write(File(root, "versions"), "c.jar", 100)

            val report = SpaceAnalyzer.analyze(root)
            assertEquals(listOf("libraries", "assets", "versions"), report.items.map { it.label })
            assertEquals(900L, report.totalBytes)
            assertEquals(0, report.hiddenCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `只列前 N 项，其余记在 hiddenCount 里但计入合计`() {
        val root = tempRoot()
        try {
            write(File(root, "a"), "x", 10)
            write(File(root, "b"), "x", 20)
            write(File(root, "c"), "x", 30)

            val report = SpaceAnalyzer.analyze(root, top = 2)
            assertEquals(listOf("c", "b"), report.items.map { it.label })
            assertEquals(1, report.hiddenCount)
            assertEquals(60L, report.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `根目录里的散装文件单独算一行`() {
        val root = tempRoot()
        try {
            write(File(root, "versions"), "a.jar", 100)
            write(root, "loose.zip", 250)
            write(root, "readme.txt", 50)

            val report = SpaceAnalyzer.analyze(root)
            val loose = report.items.first { it.label == SpaceAnalyzer.LOOSE_LABEL }
            assertEquals(2, loose.files)
            assertEquals(300L, loose.bytes)
            assertEquals(400L, report.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `启动器数据目录在根目录之外时单独列一行`() {
        val root = tempRoot()
        val data = Files.createTempDirectory("platypus-space-data").toFile()
        try {
            write(File(root, "versions"), "a.jar", 100)
            write(File(data, "saves-backup"), "b.zip", 700)

            val report = SpaceAnalyzer.analyze(root, dataDir = data)
            val line = report.items.first { it.label == SpaceAnalyzer.LAUNCHER_DATA_LABEL }
            assertEquals(700L, line.bytes)
            assertEquals(800L, report.totalBytes)
        } finally {
            root.deleteRecursively()
            data.deleteRecursively()
        }
    }

    @Test
    fun `启动器数据目录就在根目录里时不重复算`() {
        val root = tempRoot()
        try {
            val data = File(root, "platypus")
            write(data, "instances.json", 40)

            val report = SpaceAnalyzer.analyze(root, dataDir = data)
            assertTrue(report.items.none { it.label == SpaceAnalyzer.LAUNCHER_DATA_LABEL })
            assertEquals(40L, report.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `空目录不算一项`() {
        val root = tempRoot()
        try {
            File(root, "empty").mkdirs()
            assertTrue(SpaceAnalyzer.analyze(root).isEmpty)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `目录不存在时返回空报告，不抛异常`() {
        val report = SpaceAnalyzer.analyze(File("this-path-should-not-exist-platypus"))
        assertTrue(report.isEmpty)
        assertEquals(0L, report.totalBytes)
        assertFalse(report.truncated)
    }

    @Test
    fun `文件数也一起统计`() {
        val root = tempRoot()
        try {
            write(File(root, "mods"), "a.jar", 10)
            write(File(root, "mods"), "b.jar", 10)
            val report = SpaceAnalyzer.analyze(root)
            assertEquals(2, report.items.first().files)
        } finally {
            root.deleteRecursively()
        }
    }
}
