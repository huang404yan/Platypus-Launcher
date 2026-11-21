package io.github.huang404yan.platypus.core.instance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 游戏目录整棵复制：只有「加法」是安全的，所以这些用例全在验它不会毁东西。 */
class GameRootCopierTest {

    private fun tempDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "platypus-copiertest-" + tag + "-" + System.nanoTime()).also {
            it.mkdirs()
        }

    @Test
    fun `整棵树复制过去，目录层级不变`() {
        val root = tempDir("tree")
        val from = File(root, "from").apply { mkdirs() }
        val to = File(root, "to")
        File(from, "versions/1.20.1").mkdirs()
        File(from, "versions/1.20.1/1.20.1.json").writeText("{}")
        File(from, "saves/world/level.dat").apply {
            parentFile.mkdirs()
            writeText("x".repeat(100))
        }

        val result = GameRootCopier.copy(from, to)

        assertTrue(result.ok, result.failures.joinToString())
        assertEquals(2, result.files)
        // '{}' 两个字节 + 100 字节的 level.dat
        assertEquals(102L, result.bytes)
        assertEquals("{}", File(to, "versions/1.20.1/1.20.1.json").readText())
        assertEquals(100, File(to, "saves/world/level.dat").length().toInt())
        // 原件必须还在：复制是加法，不是搬家。
        assertTrue(File(from, "versions/1.20.1/1.20.1.json").exists())
    }

    @Test
    fun `目标里已有同名文件时跳过，不覆盖`() {
        val root = tempDir("skip")
        val from = File(root, "from").apply { mkdirs() }
        val to = File(root, "to").apply { mkdirs() }
        File(from, "settings.json").writeText("新")
        File(to, "settings.json").writeText("旧")

        val result = GameRootCopier.copy(from, to)

        assertEquals(0, result.files)
        assertEquals(1, result.skipped)
        assertEquals("旧", File(to, "settings.json").readText())
    }

    @Test
    fun `源不是目录时直接报错，不会静默成功`() {
        val root = tempDir("notdir")
        val file = File(root, "a.txt").apply { writeText("x") }
        val result = GameRootCopier.copy(file, File(root, "to"))
        assertFalse(result.ok)
        assertTrue(result.failures.single().contains("不是目录"))
    }

    @Test
    fun `源目标相同时拒绝，避免把目录复制进自己`() {
        val root = tempDir("same")
        val result = GameRootCopier.copy(root, root)
        assertFalse(result.ok)
        assertEquals(1, result.failures.size)
    }

    @Test
    fun `目标在源里面时拒绝，否则会无限套娃`() {
        val root = tempDir("nested")
        val result = GameRootCopier.copy(root, File(root, "inner"))
        assertFalse(result.ok)
        assertTrue(result.failures.single().contains("里面"))
    }

    @Test
    fun `空目录也能复制，不算失败`() {
        val root = tempDir("empty")
        val from = File(root, "from").apply { mkdirs() }
        val to = File(root, "to")
        val result = GameRootCopier.copy(from, to)
        assertTrue(result.ok)
        assertEquals(0, result.files)
        assertTrue(to.isDirectory)
    }


    @Test
    fun `早就复制过的目标不会把新文件盖掉，也不重复计数`() {
        val root = tempDir("twice")
        val from = File(root, "from").apply { mkdirs() }
        val to = File(root, "to")
        File(from, "a.txt").writeText("1")
        val first = GameRootCopier.copy(from, to)
        File(from, "b.txt").writeText("22")
        val second = GameRootCopier.copy(from, to)

        assertEquals(1, first.files)
        assertEquals(1, second.files)
        assertEquals(1, second.skipped)
        // 第二次只复制新增的 b.txt（2 字节），a.txt 那 1 字节不再算一遍。
        assertEquals(2L, second.bytes)
        assertEquals("1", File(to, "a.txt").readText())
        assertEquals("22", File(to, "b.txt").readText())
    }

    @Test
    fun `源里的符号链接不会被跟着复制过去`() {
        val root = tempDir("link")
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("x")
        val from = File(root, "from").apply { mkdirs() }
        File(from, "real.txt").writeText("r")
        val link = File(from, "linked")
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        // Windows 上建符号链接要特权（开发者模式也算）。没有特权就跳过这个用例，
        // 而不是让它红着 —— 这里验的是复制逻辑，不是当前账户的权限。
        if (!created) return

        val result = GameRootCopier.copy(from, File(root, "to"))

        assertTrue(result.ok, result.failures.joinToString())
        assertEquals(1, result.files)
        assertFalse(File(root, "to/linked").exists())
        assertEquals(0, File(root, "to").listFiles()!!.count { it.name == "linked" })
    }

}
