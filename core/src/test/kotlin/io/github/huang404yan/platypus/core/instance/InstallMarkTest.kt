package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallMarkTest {

    private fun tempDir(): File = Files.createTempDirectory("platypus-install-mark-test").toFile()

    @Test
    fun markCreatesDirectoryAndFile() {
        val root = tempDir()
        try {
            val dir = File(root, "versions/1.21.4")
            assertFalse(InstallMark.isMarked(dir))
            InstallMark.mark(dir)
            assertTrue(dir.isDirectory)
            assertTrue(InstallMark.isMarked(dir))
            assertTrue(InstallMark.fileIn(dir).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearRemovesMarkAndIsIdempotent() {
        val root = tempDir()
        try {
            val dir = File(root, "versions/1.21.4")
            InstallMark.mark(dir)
            InstallMark.clear(dir)
            assertFalse(InstallMark.isMarked(dir))
            // 再撤一次不该抛异常
            InstallMark.clear(dir)
            assertFalse(InstallMark.isMarked(dir))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleMarkIsTreatedAsAbsent() {
        val root = tempDir()
        try {
            val dir = File(root, "versions/1.21.4")
            InstallMark.mark(dir)
            val file = InstallMark.fileIn(dir)
            assertTrue(file.setLastModified(System.currentTimeMillis() - InstallMark.STALE_MILLIS - 60_000L))
            // 强杀留下的残留：不能因为这个目录就永远不显示。
            assertFalse(InstallMark.isMarked(dir))
            assertFalse(file.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
