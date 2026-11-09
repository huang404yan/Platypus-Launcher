package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceRepositoryTest {

    private fun tempDir(): File = Files.createTempDirectory("platypus-repo-test").toFile()

    private fun instance(
        id: String = "1.20.1",
        name: String = "1.20.1",
        directory: String,
        memoryMb: Int = 2048,
    ) = Instance(
        id = id,
        name = name,
        gameVersion = "1.20.1",
        directory = directory,
        memoryMb = memoryMb,
    )

    @Test
    fun saveThenListRoundTrips() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root))
            repo.save(instance(directory = File(root, "versions/1.20.1").path, memoryMb = 4096))

            val all = repo.list()
            assertEquals(1, all.size)
            assertEquals(4096, all.first().memoryMb)
            // 落盘的一定是「已登记」，否则重启后会被当成外部实例
            assertTrue(all.first().registered)
            assertTrue(all.first().createdAt > 0)

            val found = repo.find("1.20.1")
            assertEquals("1.20.1", found?.id)
            assertNull(repo.find("1.21.4"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun savingSameIdUpdatesInsteadOfDuplicating() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root))
            val dir = File(root, "versions/1.20.1").path
            repo.save(instance(directory = dir, memoryMb = 2048))
            repo.save(instance(name = "我的世界", directory = dir, memoryMb = 8192))

            val all = repo.list()
            assertEquals(1, all.size)
            assertEquals(8192, all.first().memoryMb)
            assertEquals("我的世界", all.first().name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clampsMemoryToLegalRange() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root))
            repo.save(instance(id = "low", directory = File(root, "versions/low").path, memoryMb = 64))
            repo.save(instance(id = "high", directory = File(root, "versions/high").path, memoryMb = 1_000_000))

            assertEquals(Instance.MIN_MEMORY_MB, repo.find("low")?.memoryMb)
            assertEquals(Instance.MAX_MEMORY_MB, repo.find("high")?.memoryMb)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removeDropsOnlyGivenId() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root))
            repo.save(instance(id = "a", directory = File(root, "versions/a").path))
            repo.save(instance(id = "b", directory = File(root, "versions/b").path))
            repo.remove("a")
            assertEquals(listOf("b"), repo.list().map { it.id })
            // 删不存在的 id 不抛也不改文件
            repo.remove("nope")
            assertEquals(listOf("b"), repo.list().map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptIndexReadsAsEmptyInsteadOfThrowing() = runBlocking {
        val root = tempDir()
        try {
            val file = JsonInstanceRepository.defaultIndexFile(root)
            file.parentFile.mkdirs()
            file.writeText("{ this is not json", Charsets.UTF_8)
            val repo = JsonInstanceRepository(file)
            assertTrue(repo.list().isEmpty())
            // 坏文件不影响后续写入：save 之后能正常读回
            repo.save(instance(directory = File(root, "versions/1.20.1").path))
            assertEquals(1, repo.list().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mergesRegisteredAndScannedWithoutDuplicates() {
        val dir = File("C:/mc/versions/1.20.1").path
        val registered = listOf(instance(id = "1.20.1", name = "托管", directory = dir, memoryMb = 8192))
        val scanned = listOf(
            instance(id = "1.20.1", name = "1.20.1", directory = dir),
            instance(id = "1.16.5", name = "1.16.5", directory = File("C:/mc/versions/1.16.5").path),
        )

        val merged = mergeInstances(registered, scanned)
        assertEquals(2, merged.size)
        val hosted = merged.first { it.id == "1.20.1" }
        // 登记的优先：用户改过的内存与名字不能被扫描结果盖掉
        assertEquals(8192, hosted.memoryMb)
        assertEquals("托管", hosted.name)
        assertTrue(hosted.registered)
        val external = merged.first { it.id == "1.16.5" }
        assertTrue(!external.registered)
        // 登记的排在前面
        assertTrue(merged.first().registered)
    }
}
