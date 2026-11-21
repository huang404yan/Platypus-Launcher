package io.github.huang404yan.platypus.core.settings

import io.github.huang404yan.platypus.core.download.DownloadPreference
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRepositoryTest {

    private fun tempDir(): File = Files.createTempDirectory("platypus-settings-test").toFile()

    @Test
    fun missingFileFallsBackToDefaults() = runBlocking {
        val dir = tempDir()
        try {
            val repo = JsonSettingsRepository(JsonSettingsRepository.defaultFile(dir))
            val settings = repo.load()
            assertEquals(LauncherSettings.DEFAULT_OFFLINE_USERNAME, settings.offlineUsername)
            assertEquals("", settings.defaultGameRoot)
            assertEquals(2048, settings.defaultMemoryMb)
            assertEquals(0L, settings.updatedAt)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun saveThenLoadRoundTrips() = runBlocking {
        val dir = tempDir()
        try {
            val repo = JsonSettingsRepository(JsonSettingsRepository.defaultFile(dir))
            val saved = repo.save(
                LauncherSettings(
                    offlineUsername = "Steve",
                    defaultGameRoot = """D:\Games\.minecraft""",
                    defaultMemoryMb = 8192,
                ),
            )
            assertTrue(saved.updatedAt > 0)
            assertEquals("Steve", saved.offlineUsername)

            val reloaded = JsonSettingsRepository(JsonSettingsRepository.defaultFile(dir)).load()
            assertEquals("Steve", reloaded.offlineUsername)
            // 原样往返：只去首尾空白与引号，不换分隔符（后面要直接 File(path) 用）
            assertEquals("D:\\Games\\.minecraft", reloaded.defaultGameRoot)
            assertEquals(8192, reloaded.defaultMemoryMb)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun saveNormalizesOutOfRangeValues() = runBlocking {
        val dir = tempDir()
        try {
            val repo = JsonSettingsRepository(JsonSettingsRepository.defaultFile(dir))
            val saved = repo.save(
                LauncherSettings(offlineUsername = "   ", defaultGameRoot = "  ", defaultMemoryMb = 1),
            )
            assertEquals(LauncherSettings.DEFAULT_OFFLINE_USERNAME, saved.offlineUsername)
            assertEquals("", saved.defaultGameRoot)
            assertEquals(512, saved.defaultMemoryMb)

            val huge = repo.save(LauncherSettings(offlineUsername = "Alex", defaultMemoryMb = 999_999))
            assertEquals(32768, huge.defaultMemoryMb)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptedFileFallsBackToDefaultsInsteadOfThrowing() = runBlocking {
        val dir = tempDir()
        try {
            val file = JsonSettingsRepository.defaultFile(dir)
            file.parentFile.mkdirs()
            file.writeText("{ 这不是 json", Charsets.UTF_8)
            val settings = JsonSettingsRepository(file).load()
            assertEquals(LauncherSettings.DEFAULT_OFFLINE_USERNAME, settings.offlineUsername)
            assertEquals(2048, settings.defaultMemoryMb)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unknownFieldsAreIgnored() = runBlocking {
        val dir = tempDir()
        try {
            val file = JsonSettingsRepository.defaultFile(dir)
            file.parentFile.mkdirs()
            file.writeText(
                """{"offlineUsername":"Notch","futureField":{"a":1},"defaultMemoryMb":4096}""",
                Charsets.UTF_8,
            )
            val settings = JsonSettingsRepository(file).load()
            assertEquals("Notch", settings.offlineUsername)
            assertEquals(4096, settings.defaultMemoryMb)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clientIdRoundTripsAndIsTrimmed() = runBlocking {
        val dir = tempDir()
        try {
            val repo = JsonSettingsRepository(JsonSettingsRepository.defaultFile(dir))
            // 用户是从 Azure 门户复制的，前后很容易带空格或引号。
            val saved = repo.save(
                LauncherSettings(microsoftClientId = "  \"12345678-1234-1234-1234-123456789abc\" "),
            )
            assertEquals("12345678-1234-1234-1234-123456789abc", saved.microsoftClientId)

            val reloaded = JsonSettingsRepository(JsonSettingsRepository.defaultFile(dir)).load()
            assertEquals("12345678-1234-1234-1234-123456789abc", reloaded.microsoftClientId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oldSettingsFileWithoutClientIdStillLoads() = runBlocking {
        val dir = tempDir()
        try {
            val file = JsonSettingsRepository.defaultFile(dir)
            file.parentFile.mkdirs()
            // preview.9 之前的文件没有这个字段，读出来必须是空串而不是抛异常。
            file.writeText("""{"offlineUsername":"Notch","defaultMemoryMb":4096}""", Charsets.UTF_8)
            val settings = JsonSettingsRepository(file).load()
            assertEquals("", settings.microsoftClientId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `下载连接数存进设置也不会越界`() = runBlocking {
        val dir = tempDir()
        try {
            val repo = JsonSettingsRepository(JsonSettingsRepository.defaultFile(dir))
            // 手改设置文件能写进任意数；读回来必须已经被夹到合法区间。
            val saved = repo.save(LauncherSettings(downloadConnections = 99999))
            assertEquals(DownloadPreference.MAX_CONNECTIONS, saved.downloadConnections)
            assertEquals(DownloadPreference.MAX_CONNECTIONS, repo.load().downloadConnections)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun defaultFileLivesNextToInstancesIndex() {
        val dir = tempDir()
        try {
            val settings = JsonSettingsRepository.defaultFile(dir)
            val instances = io.github.huang404yan.platypus.core.instance.JsonInstanceRepository.defaultIndexFile(dir)
            assertEquals(instances.parentFile, settings.parentFile)
            assertEquals("settings.json", settings.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun saveKeepsUserFilledCredentialsWhenIncomingIsBlank() = runBlocking {
        val dir = tempDir()
        try {
            val file = JsonSettingsRepository.defaultFile(dir)
            val repo = JsonSettingsRepository(file)
            repo.save(
                LauncherSettings(
                    microsoftClientId = "12345678-1234-1234-1234-123456789abc",
                    serverBase = "https://mirror.example.com/bmclapi",
                    curseForgeApiKey = "cf-key",
                ),
            )
            // 模拟「别的调用点拿着一份新建的设置写盘」：三格都是空串。
            val saved = repo.save(LauncherSettings(offlineUsername = "Alex"))
            assertEquals("12345678-1234-1234-1234-123456789abc", saved.microsoftClientId)
            assertEquals("https://mirror.example.com/bmclapi", saved.serverBase)
            assertEquals("cf-key", saved.curseForgeApiKey)
            assertEquals("Alex", saved.offlineUsername)

            val reloaded = JsonSettingsRepository(file).load()
            assertEquals("12345678-1234-1234-1234-123456789abc", reloaded.microsoftClientId)
            assertEquals("https://mirror.example.com/bmclapi", reloaded.serverBase)
            assertEquals("cf-key", reloaded.curseForgeApiKey)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun saveWithKeepUserFilledFalseReallyClearsThem() = runBlocking {
        val dir = tempDir()
        try {
            val file = JsonSettingsRepository.defaultFile(dir)
            val repo = JsonSettingsRepository(file)
            repo.save(
                LauncherSettings(
                    microsoftClientId = "12345678-1234-1234-1234-123456789abc",
                    serverBase = "https://mirror.example.com/bmclapi",
                    curseForgeApiKey = "cf-key",
                ),
            )
            // 用户在界面上亲手清空：这时候必须真的写成空。
            val saved = repo.save(LauncherSettings(), keepUserFilled = false)
            assertEquals("", saved.microsoftClientId)
            assertEquals("", saved.serverBase)
            assertEquals("", saved.curseForgeApiKey)
            assertEquals("", JsonSettingsRepository(file).load().microsoftClientId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun saveSalvagesCredentialsFromADamagedFile() = runBlocking {
        val dir = tempDir()
        try {
            val file = JsonSettingsRepository.defaultFile(dir)
            file.parentFile.mkdirs()
            // 用户手改坏了一处（这里大括号没闭合），但三格其实都还在。
            // 注意不能只少一个逗号：解析器是宽松模式，那种程度它照样吃得下。
            file.writeText(
                """{"microsoftClientId":"12345678-1234-1234-1234-123456789abc","serverBase":"https://mirror.example.com/bmclapi","curseForgeApiKey":""",
                Charsets.UTF_8,
            )
            val saved = JsonSettingsRepository(file).save(LauncherSettings(offlineUsername = "Alex"))
            assertEquals("12345678-1234-1234-1234-123456789abc", saved.microsoftClientId)
            assertEquals("https://mirror.example.com/bmclapi", saved.serverBase)
            // 损坏原文要留档，用户能手工找回别的字段。
            val broken = file.parentFile.listFiles().orEmpty().filter { it.name.startsWith("settings.json.broken-") }
            assertEquals(1, broken.size)
            assertTrue(broken[0].readText(Charsets.UTF_8).contains("mirror.example.com"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun saveKeepsThePreviousVersionAsBak() = runBlocking {
        val dir = tempDir()
        try {
            val file = JsonSettingsRepository.defaultFile(dir)
            val repo = JsonSettingsRepository(file)
            repo.save(LauncherSettings(offlineUsername = "Steve"))
            repo.save(LauncherSettings(offlineUsername = "Alex"))
            val bak = File(file.absolutePath + ".bak")
            assertTrue(bak.isFile)
            assertTrue(bak.readText(Charsets.UTF_8).contains("Steve"))

            // 内容没变的重复落盘不该刷 .bak（updatedAt 变了也不算内容变）。
            bak.setLastModified(1_000L)
            repo.save(LauncherSettings(offlineUsername = "Alex"))
            assertEquals(1_000L, bak.lastModified())
        } finally {
            dir.deleteRecursively()
        }
    }
}