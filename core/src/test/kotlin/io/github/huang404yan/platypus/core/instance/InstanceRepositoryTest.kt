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
        loader: LoaderKind = LoaderKind.VANILLA,
        lastPlayedAt: Long = 0L,
    ) = Instance(
        id = id,
        name = name,
        gameVersion = "1.20.1",
        directory = directory,
        memoryMb = memoryMb,
        loader = loader,
        lastPlayedAt = lastPlayedAt,
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

    @Test
    fun foldsRegisteredVanillaThatLoadersInherit() {
        // 先单独下过 1.20.1 并登记，后来又补装 fabric，于是两条都被登记过。
        // 磁盘上 1.20.1 必须留着（fabric 那份 inheritsFrom 指着它），但列表里只该有 fabric。
        val vanillaDir = File("C:/mc/versions/1.20.1").path
        val fabricDir = File("C:/mc/versions/1.20.1-fabric-0.16.9").path
        val registered = listOf(
            instance(id = "1.20.1", name = "1.20.1", directory = vanillaDir),
            instance(id = "1.20.1-fabric-0.16.9", name = "fabric", directory = fabricDir, loader = LoaderKind.FABRIC),
        )

        val merged = mergeInstances(registered, emptyList(), listOf("1.20.1"))
        assertEquals(1, merged.size)
        assertEquals("1.20.1-fabric-0.16.9", merged.first().id)
        assertTrue(merged.first().registered)
    }

    @Test
    fun keepsRegisteredVanillaWhenNothingInheritsIt() {
        // 没传 libraryVersions 时行为与从前一致：登记的原版照常列出。
        val dir = File("C:/mc/versions/1.20.1").path
        val registered = listOf(instance(id = "1.20.1", name = "1.20.1", directory = dir))
        val merged = mergeInstances(registered, emptyList(), emptyList())
        assertEquals(1, merged.size)
        assertEquals("1.20.1", merged.first().id)
    }

    @Test
    fun sortsByLastPlayedWithinRegisteredGroup() {
        // 登记的几条里，最近玩过的排在前面；从没启动过（0）的一律排在有记录之后，
        // 不能因为 0 比时间戳小就当成「很久以前」翻到最后 —— 那就成了按名字排。
        val registered = listOf(
            instance(id = "old", name = "Old", directory = File("C:/mc/versions/old").path, lastPlayedAt = 1_000L),
            instance(id = "never", name = "Never", directory = File("C:/mc/versions/never").path),
            instance(id = "recent", name = "Recent", directory = File("C:/mc/versions/recent").path, lastPlayedAt = 9_000L),
        )
        val merged = mergeInstances(registered, emptyList())
        assertEquals(listOf("recent", "old", "never"), merged.map { it.id })
    }

    @Test
    fun scannedInstancesStayBehindRegisteredEvenIfRecentlyPlayed() {
        // 「登记的在前」是旧行为，不能被 lastPlayedAt 顶掉：别的启动器装的版本
        // 就算刚玩过，也不该插到用户自己建的实例中间。
        val registered = listOf(
            instance(id = "mine", name = "Mine", directory = File("C:/mc/versions/mine").path),
        )
        val scanned = listOf(
            instance(
                id = "theirs",
                name = "Theirs",
                directory = File("C:/mc/versions/theirs").path,
                lastPlayedAt = 9_000_000L,
            ),
        )
        val merged = mergeInstances(registered, scanned)
        assertEquals(listOf("mine", "theirs"), merged.map { it.id })
    }

    @Test
    fun savesAndReloadsLastPlayed() = runBlocking {
        // 字段要能落盘再读回：漏了序列化的话重启之后排序会整体退回按名字。
        val root = tempDir()
        try {
            val repo = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root))
            repo.save(instance(directory = File(root, "versions/1.20.1").path, lastPlayedAt = 12_345L))
            assertEquals(12_345L, repo.find("1.20.1")?.lastPlayedAt)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun doesNotFoldRegisteredLoaderVersion() {
        // 被折叠的只能是纯原版那条：加载器版本即便 id 撞进 libraryVersions 也不能挡。
        val dir = File("C:/mc/versions/1.20.1-forge-47.4.0").path
        val registered = listOf(
            instance(id = "1.20.1-forge-47.4.0", name = "forge", directory = dir, loader = LoaderKind.FORGE),
        )
        val merged = mergeInstances(registered, emptyList(), listOf("1.20.1-forge-47.4.0"))
        assertEquals(1, merged.size)
    }
}
