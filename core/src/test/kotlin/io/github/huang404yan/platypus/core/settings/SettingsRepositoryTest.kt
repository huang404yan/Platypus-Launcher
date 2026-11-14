package io.github.huang404yan.platypus.core.settings

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
}
