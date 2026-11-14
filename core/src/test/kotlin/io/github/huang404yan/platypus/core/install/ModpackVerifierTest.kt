package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.mods.ModUpdateChecker
import io.github.huang404yan.platypus.core.modpack.ModpackFile
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 导入结束前的落盘核对。
 *
 * 这组用例守的是「界面上那句『导入完成』必须对应磁盘真的齐了」：清单里的每个文件
 * 只有三种可接受的状态 —— 存在且大小对、大小未知但 sha1 对、或者被如实记成一条 issue。
 */
class ModpackVerifierTest {

    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("platypus-verify-").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun 文件齐全且大小对得上时没有问题() = runBlocking {
        write("mods/a.jar", "alpha")
        write("mods/b.jar", "beta-beta")

        val issues = ModpackVerifier.verify(
            root,
            listOf(file("mods/a.jar", size = 5L), file("mods/b.jar", size = 9L)),
        )

        assertTrue(issues.isEmpty(), "不应有问题：$issues")
    }

    @Test
    fun 文件根本没落盘时报文件不存在() = runBlocking {
        val issues = ModpackVerifier.verify(root, listOf(file("mods/missing.jar", size = 10L)))

        assertEquals(1, issues.size, "应恰好一条：$issues")
        assertEquals("mods/missing.jar", issues.first().path)
        assertEquals("missing.jar", issues.first().fileName)
        assertTrue(issues.first().reason.contains("不存在"), "原因应说清没落盘：${issues.first().reason}")
    }

    @Test
    fun 只剩_part半成品时说明下到一半并带数字() = runBlocking {
        // 真实的 Cobblemon 场景：目标 jar 不在，同名 .part 停在 51 MB。
        val part = File(root, "mods/big.jar.part")
        part.parentFile.mkdirs()
        part.writeBytes(ByteArray(3 * 1024))

        val issues = ModpackVerifier.verify(root, listOf(file("mods/big.jar", size = 140L * 1024 * 1024)))

        assertEquals(1, issues.size, "应恰好一条：$issues")
        val reason = issues.first().reason
        assertTrue(reason.contains("一半"), "应说明只下到一半：$reason")
        assertTrue(reason.contains("3.0 KB"), "应带上半成品当前大小：$reason")
        assertTrue(reason.contains("140.0 MB"), "应带上期望大小：$reason")
    }

    @Test
    fun 大小不符时按大小报错而不是算sha1() = runBlocking {
        write("mods/short.jar", "12345")

        val issues = ModpackVerifier.verify(root, listOf(file("mods/short.jar", size = 123L)))

        assertEquals(1, issues.size, "应恰好一条：$issues")
        assertTrue(issues.first().reason.contains("大小不符"), "应报大小不符：${issues.first().reason}")
        assertTrue(issues.first().reason.contains("123 B"), "应带上期望值：${issues.first().reason}")
        assertTrue(issues.first().reason.contains("5 B"), "应带上实际值：${issues.first().reason}")
    }

    @Test
    fun 清单没写大小时退回算sha1且对得上就不报错() = runBlocking {
        val bytes = "gamma-content".toByteArray()
        write("mods/g.jar", bytes)

        val issues = ModpackVerifier.verify(
            root,
            listOf(file("mods/g.jar", size = -1L, sha1 = sha1(bytes))),
        )

        assertTrue(issues.isEmpty(), "sha1 对得上就应放过：$issues")
    }

    @Test
    fun 清单没写大小时sha1不符要报内容不一致() = runBlocking {
        write("mods/g.jar", "tampered")

        val issues = ModpackVerifier.verify(
            root,
            listOf(file("mods/g.jar", size = -1L, sha1 = "0".repeat(40))),
        )

        assertEquals(1, issues.size, "应恰好一条：$issues")
        assertTrue(issues.first().reason.contains("sha1"), "应说清是摘要不符：${issues.first().reason}")
    }

    @Test
    fun 清单既没大小也没sha1时只能放过() = runBlocking {
        // 没有可比对的证据时不能凭空报错：宁可放过，也不要让用户看到无法解释的红字。
        write("mods/x.jar", "whatever")

        val issues = ModpackVerifier.verify(root, listOf(file("mods/x.jar", size = -1L, sha1 = null)))

        assertTrue(issues.isEmpty(), "没有证据时不应报错：$issues")
    }

    @Test
    fun 多个问题时全部收集而不是只报第一个() = runBlocking {
        write("mods/ok.jar", "ok")

        val issues = ModpackVerifier.verify(
            root,
            listOf(
                file("mods/ok.jar", size = 2L),
                file("mods/a.jar", size = 10L),
                file("mods/b.jar", size = 10L),
            ),
        )

        assertEquals(2, issues.size, "应把两个问题都收上来：$issues")
    }

    @Test
    fun 核对进度会报到总数且最后一个不越界() = runBlocking {
        write("mods/a.jar", "a")
        write("mods/b.jar", "b")
        val calls = mutableListOf<Pair<Int, Int>>()

        ModpackVerifier.verify(
            root,
            listOf(file("mods/a.jar", size = 1L), file("mods/b.jar", size = 1L)),
        ) { done, total -> calls += done to total }

        assertTrue(calls.isNotEmpty(), "应当有进度回调")
        assertTrue(calls.all { it.second == 2 }, "总数应恒为 2：$calls")
        assertEquals(2, calls.last().first, "最后一次应是全部核对完：$calls")
    }

    @Test
    fun 一句话汇总里带文件名与续传提示() {
        val issues = listOf(
            ModpackFileIssue("mods/Cobblemon-fabric-1.8.4+1.21.1.jar", "只下到一半（51.6 MB / 140.0 MB），还没落到最终位置"),
        )

        val text = ModpackVerifier.summarize(issues)

        assertTrue(text.contains("1 个不对"), "应报数量：$text")
        assertTrue(text.contains("Cobblemon-fabric-1.8.4+1.21.1.jar"), "应带文件名：$text")
        assertTrue(text.contains("续传"), "半成品要告诉用户会被续上：$text")
    }

    @Test
    fun 汇总超过上限时折叠成等N个() {
        val issues = (1..8).map { ModpackFileIssue("mods/m" + it + ".jar", "文件不存在") }

        val text = ModpackVerifier.summarize(issues)

        assertTrue(text.contains("8 个不对"), "应报总数：$text")
        assertTrue(text.contains("等 8 个"), "超过 5 个后应折叠：$text")
        assertTrue(!text.contains("m6.jar"), "折叠后不应再列第 6 个：$text")
        assertTrue(!text.contains("续传"), "没有半成品时不该提续传：$text")
    }

    @Test
    fun 没有问题时不产出提示() {
        assertEquals("", ModpackVerifier.summarize(emptyList()))
    }

    private fun file(path: String, size: Long, sha1: String? = null) =
        ModpackFile(path = path, url = "https://example.invalid/" + path, sha1 = sha1, size = size)

    private fun write(relative: String, text: String) = write(relative, text.toByteArray())

    private fun write(relative: String, bytes: ByteArray) {
        val target = File(root, relative)
        val parent = target.parentFile
        assertNotNull(parent)
        parent.mkdirs()
        target.writeBytes(bytes)
    }

    private fun sha1(bytes: ByteArray): String {
        write("__sha1_probe.bin", bytes)
        val digest = ModUpdateChecker.sha1Of(File(root, "__sha1_probe.bin"))
        File(root, "__sha1_probe.bin").delete()
        return digest
    }
}
