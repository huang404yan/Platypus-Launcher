package io.github.huang404yan.platypus.core.mods

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModUpdateCheckerTest {

    private fun tempDir(name: String): File =
        Files.createTempDirectory("platypus-update-" + name).toFile().also { it.deleteOnExit() }

    private fun write(dir: File, name: String, text: String): File {
        val f = File(dir, name)
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    @Test
    fun `空文件 sha1 是已知常量`() {
        val dir = tempDir("sha1-empty")
        val empty = write(dir, "empty.jar", "")
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", ModUpdateChecker.sha1Of(empty))
    }

    @Test
    fun `abc 的 sha1 是已知常量`() {
        val dir = tempDir("sha1-abc")
        val f = write(dir, "abc.jar", "abc")
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", ModUpdateChecker.sha1Of(f))
    }

    @Test
    fun `hex 每字节补足两位`() {
        assertEquals("00010f10ff", ModUpdateChecker.hex(byteArrayOf(0, 1, 15, 16, -1)))
    }

    @Test
    fun `读不存在的文件说清是哪个`() {
        val dir = tempDir("sha1-missing")
        val e = assertFailsWith<java.io.IOException> {
            ModUpdateChecker.sha1Of(File(dir, "nope.jar"))
        }
        assertTrue(e.message.orEmpty().contains("nope.jar"), e.message.orEmpty())
    }

    @Test
    fun `请求体里每个哈希各自带引号`() {
        val body = ModUpdateChecker.versionFilesBody(listOf("aaa", "bbb"))
        assertEquals("""{"hashes":["aaa","bbb"],"algorithm":"sha1"}""", body)
    }

    @Test
    fun `单个哈希的请求体不会被粘在一起`() {
        assertEquals(
            """{"hashes":["abc"],"algorithm":"sha1"}""",
            ModUpdateChecker.versionFilesBody(listOf("abc")),
        )
    }

    @Test
    fun `请求体可以被 JSON 解析回同样的哈希列表`() {
        val body = ModUpdateChecker.versionFilesBody(listOf("a1", "b2", "c3"))
        val parsed = kotlinx.serialization.json.Json
            .parseToJsonElement(body)
            .let { it as kotlinx.serialization.json.JsonObject }
        val hashes = (parsed["hashes"] as kotlinx.serialization.json.JsonArray).map {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        }
        assertEquals(listOf("a1", "b2", "c3"), hashes)
    }

    @Test
    fun `按一百条一批切开`() {
        val items = (1..250).toList()
        val batches = ModUpdateChecker.batches(items)
        assertEquals(3, batches.size)
        assertEquals(100, batches[0].size)
        assertEquals(100, batches[1].size)
        assertEquals(50, batches[2].size)
        assertEquals(250, batches.sumOf { it.size })
    }

    @Test
    fun `空列表切出来是零批`() {
        assertTrue(ModUpdateChecker.batches(emptyList<Int>()).isEmpty())
    }

    @Test
    fun `版本号字符串相同才算最新`() {
        assertTrue(ModUpdateResult.compareVersions("0.5.11", "0.5.11"))
        assertTrue(ModUpdateResult.compareVersions(" 0.5.11 ", "0.5.11"))
        assertTrue(ModUpdateResult.compareVersions("1.0-BETA", "1.0-beta"))
        assertTrue(!ModUpdateResult.compareVersions("0.5.11", "0.5.12"))
        // 写法不同就当成不同版本：宁可多提示一次更新，也不静默漏掉
        assertTrue(!ModUpdateResult.compareVersions("1.2", "1.2.0"))
    }

    private fun lookup(version: String) = ModVersionLookup(
        sha1 = "a".repeat(40),
        projectId = "proj1",
        versionNumber = version,
        fileName = "sodium-1.0.jar",
    )

    private fun latest(version: String) = ModVersionFile(
        projectSlug = "sodium",
        versionNumber = version,
        fileName = "sodium-" + version + ".jar",
        url = "https://example.invalid/sodium.jar",
        sha1 = null,
        size = 1L,
    )

    @Test
    fun `Modrinth 不认识的文件归到 NOT_ON_MODRINTH`() {
        val result = ModUpdateResult("mymod.jar", "b".repeat(40))
        assertEquals(ModUpdateStatus.NOT_ON_MODRINTH, result.status)
        assertEquals("mymod.jar", result.title)
        assertEquals("", result.currentVersion)
        assertNull(result.latestVersion)
    }

    @Test
    fun `认得出但没有适配版本归到 NO_COMPATIBLE_VERSION`() {
        val result = ModUpdateResult("sodium.jar", "c".repeat(40), lookup = lookup("0.5.11"))
        assertEquals(ModUpdateStatus.NO_COMPATIBLE_VERSION, result.status)
        assertEquals("0.5.11", result.currentVersion)
        assertNull(result.latestVersion)
    }

    @Test
    fun `版本相同是 UP_TO_DATE`() {
        val result = ModUpdateResult(
            "sodium.jar", "d".repeat(40),
            lookup = lookup("0.5.11"),
            latest = latest("0.5.11"),
        )
        assertEquals(ModUpdateStatus.UP_TO_DATE, result.status)
    }

    @Test
    fun `版本更高是 OUTDATED 且带新旧两个号`() {
        val result = ModUpdateResult(
            "sodium.jar", "e".repeat(40),
            lookup = lookup("0.5.11"),
            latest = latest("0.6.0"),
            project = ModProject("sodium", "Sodium", "渲染优化", "jellysquid3", 250_000_000L, "proj1"),
        )
        assertEquals(ModUpdateStatus.OUTDATED, result.status)
        assertEquals("Sodium", result.title)
        assertEquals("0.5.11", result.currentVersion)
        assertEquals("0.6.0", result.latestVersion)
    }

    @Test
    fun `项目标题为空时退回文件名`() {
        val result = ModUpdateResult(
            "sodium.jar", "f".repeat(40),
            lookup = lookup("0.5.11"),
            latest = latest("0.5.11"),
            project = ModProject("sodium", "   ", "", null, 0L, "proj1"),
        )
        assertEquals("sodium.jar", result.title)
    }
}
