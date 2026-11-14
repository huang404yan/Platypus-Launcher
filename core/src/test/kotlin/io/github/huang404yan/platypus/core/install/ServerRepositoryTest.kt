package io.github.huang404yan.platypus.core.install

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRepositoryTest {

    private fun tempDir(): File = Files.createTempDirectory("platypus-server-repo").toFile()

    private fun entry(
        id: String = "server-a",
        name: String = "mc-server",
        kind: ServerKind = ServerKind.PAPER,
        gameVersion: String = "1.20.1",
        loaderVersion: String? = "196",
        directory: String,
        memoryMb: Int = 2048,
        createdAt: Long = 0L,
    ) = ServerEntry(
        id = id,
        name = name,
        kind = kind,
        gameVersion = gameVersion,
        loaderVersion = loaderVersion,
        directory = directory,
        memoryMb = memoryMb,
        createdAt = createdAt,
    )

    @Test
    fun 存一条能原样读回来() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonServerRepository(JsonServerRepository.defaultIndexFile(root))
            val dir = File(root, "mc-server").absolutePath
            repo.save(entry(directory = dir, memoryMb = 4096))

            val all = repo.list()
            assertEquals(1, all.size)
            val saved = all.first()
            assertEquals("server-a", saved.id)
            assertEquals(ServerKind.PAPER, saved.kind)
            assertEquals("1.20.1", saved.gameVersion)
            assertEquals("196", saved.loaderVersion)
            assertEquals(4096, saved.memoryMb)
            assertEquals(dir, saved.directory)
            // 没给创建时间也要补上一个，否则按时间排序会乱
            assertTrue(saved.createdAt > 0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun 同一个目录再存一次不会长出第二条() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonServerRepository(JsonServerRepository.defaultIndexFile(root))
            val dir = File(root, "mc-server").absolutePath
            repo.save(entry(id = "server-a", directory = dir, memoryMb = 2048))
            // 重装同一个服务端会拿到新 id，但目录一样 —— 列表里只该有一条
            repo.save(entry(id = "server-b", directory = dir, memoryMb = 8192))

            val all = repo.list()
            assertEquals(1, all.size)
            assertEquals(8192, all.first().memoryMb)
            assertEquals("server-b", all.first().id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun 目录写法不同也认作同一个() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonServerRepository(JsonServerRepository.defaultIndexFile(root))
            repo.save(entry(id = "a", directory = "D:\\mc-server"))
            // 反斜杠换正斜杠、大小写不同、多一层引号，都还是同一个目录
            repo.save(entry(id = "b", directory = "\"d:/MC-Server\""))

            assertEquals(1, repo.list().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun 内存被夹到合法范围() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonServerRepository(JsonServerRepository.defaultIndexFile(root))
            repo.save(entry(id = "low", directory = File(root, "low").absolutePath, memoryMb = 64))
            repo.save(entry(id = "high", directory = File(root, "high").absolutePath, memoryMb = 1_000_000))

            assertEquals(512, repo.list().first { it.id == "low" }.memoryMb)
            assertEquals(32768, repo.list().first { it.id == "high" }.memoryMb)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun 删除只影响指定的那条() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonServerRepository(JsonServerRepository.defaultIndexFile(root))
            repo.save(entry(id = "a", directory = File(root, "a").absolutePath))
            repo.save(entry(id = "b", directory = File(root, "b").absolutePath))

            repo.remove("a")
            assertEquals(listOf("b"), repo.list().map { it.id })
            // 删不存在的 id 不抛异常，也不改文件
            repo.remove("nope")
            assertEquals(listOf("b"), repo.list().map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun 索引文件坏了当没有而不是崩掉() = runBlocking {
        val root = tempDir()
        try {
            val file = JsonServerRepository.defaultIndexFile(root)
            file.parentFile.mkdirs()
            file.writeText("{ 这不是 json", Charsets.UTF_8)
            val repo = JsonServerRepository(file)

            assertTrue(repo.list().isEmpty())
            // 坏文件不该把后续登记也堵死：存一条之后能正常读回
            repo.save(entry(directory = File(root, "mc-server").absolutePath))
            assertEquals(1, repo.list().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun 空的id与空目录挡在写盘之前() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonServerRepository(JsonServerRepository.defaultIndexFile(root))
            assertFailsWith<IllegalArgumentException> { repo.save(entry(id = "", directory = "D:/x")) }
            assertFailsWith<IllegalArgumentException> { repo.save(entry(directory = "  ")) }
            assertTrue(repo.list().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun 默认索引跟实例索引放在一起() {
        val root = File("C:/mc")
        assertEquals(
            File(root, "platypus/servers.json").path,
            JsonServerRepository.defaultIndexFile(root).path,
        )
    }

    @Test
    fun 标题写成加载器加版本() {
        assertEquals(
            "Paper 1.20.1 196",
            entry(kind = ServerKind.PAPER, directory = "D:/x").title,
        )
        assertEquals(
            "原版 1.20.1",
            entry(kind = ServerKind.VANILLA, loaderVersion = null, directory = "D:/x").title,
        )
        assertEquals(
            "Forge 1.20.1 47.4.0",
            entry(kind = ServerKind.FORGE, loaderVersion = "47.4.0", directory = "D:/x").title,
        )
    }

    @Test
    fun 安装结果能直接变成登记条目() {
        val dir = File("D:/servers/paper-1.20.1")
        val result = ServerInstaller.ServerInstallResult(
            versionId = "1.20.1",
            dir = dir,
            jar = File(dir, "server.jar"),
            eulaFile = File(dir, "eula.txt"),
            startScript = File(dir, "start.bat"),
            downloadedBytes = 43_230_582L,
            javaMajorVersion = 17,
            kind = ServerKind.PAPER,
            loaderVersion = "196",
        )

        val entry = serverEntryOf(result, memoryMb = 4096, javaPath = "C:/jdk17/bin/java.exe")
        assertEquals("1.20.1", entry.gameVersion)
        assertEquals(ServerKind.PAPER, entry.kind)
        assertEquals("196", entry.loaderVersion)
        assertEquals(dir.absolutePath, entry.directory)
        assertEquals("paper-1.20.1", entry.name)
        assertEquals(4096, entry.memoryMb)
        assertEquals("C:/jdk17/bin/java.exe", entry.javaPath)
        assertTrue(entry.id.startsWith("server-"))
    }

    @Test
    fun 列表按创建时间排() = runBlocking {
        val root = tempDir()
        try {
            val repo = JsonServerRepository(JsonServerRepository.defaultIndexFile(root))
            // 故意先存晚的那个
            repo.save(entry(id = "late", directory = File(root, "late").absolutePath, createdAt = 2_000L))
            repo.save(entry(id = "early", directory = File(root, "early").absolutePath, createdAt = 1_000L))

            assertEquals(listOf("early", "late"), repo.list().map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }
}
