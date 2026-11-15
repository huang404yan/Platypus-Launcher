package io.github.huang404yan.platypus.core.server

import io.github.huang404yan.platypus.core.auth.OfflineProfile
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerPlayersTest {

    private val dir: File = Files.createTempDirectory("platypus-players-test").toFile()

    @AfterTest
    fun tearDown() {
        runCatching { dir.deleteRecursively() }
    }

    @Test
    fun `没有名单文件时是空表而不是报错`() {
        assertTrue(ServerPlayers.whitelist(dir).isEmpty())
        assertTrue(ServerPlayers.ops(dir).isEmpty())
    }

    @Test
    fun `写进去的白名单能读回来`() {
        val player = ServerPlayer(uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5", name = "Notch")
        ServerPlayers.addWhitelist(dir, player)
        val read = ServerPlayers.whitelist(dir)
        assertEquals(1, read.size)
        assertEquals("Notch", read[0].name)
        assertEquals("069a79f444e94726a5befca90e38aaf5", read[0].key)
    }

    @Test
    fun `同一个人加两次不会重复`() {
        val player = ServerPlayer(uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5", name = "Notch")
        ServerPlayers.addWhitelist(dir, player)
        ServerPlayers.addWhitelist(dir, player)
        assertEquals(1, ServerPlayers.whitelist(dir).size)
    }

    @Test
    fun `UUID 带不带横线都认得出是同一个人`() {
        ServerPlayers.addWhitelist(dir, ServerPlayer(uuid = "069a79f444e94726a5befca90e38aaf5", name = "Notch"))
        ServerPlayers.addWhitelist(dir, ServerPlayer(uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5", name = "Notch"))
        assertEquals(1, ServerPlayers.whitelist(dir).size)
    }

    @Test
    fun `删白名单只删那一个人`() {
        val a = ServerPlayer(uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5", name = "Notch")
        val b = ServerPlayer(uuid = OfflineProfile.uuidFor("xiaoming"), name = "xiaoming")
        ServerPlayers.addWhitelist(dir, a)
        ServerPlayers.addWhitelist(dir, b)
        ServerPlayers.removeWhitelist(dir, a.key)
        val left = ServerPlayers.whitelist(dir)
        assertEquals(1, left.size)
        assertEquals("xiaoming", left[0].name)
    }

    @Test
    fun `OP 能记等级，再加一次是改等级不是加一条`() {
        val player = ServerPlayer(uuid = OfflineProfile.uuidFor("xiaoming"), name = "xiaoming", level = 2)
        ServerPlayers.addOp(dir, player)
        ServerPlayers.addOp(dir, player.copy(level = 4))
        val read = ServerPlayers.ops(dir)
        assertEquals(1, read.size)
        assertEquals(4, read[0].level)
    }

    @Test
    fun `OP 等级超范围会被夹回 1 到 4`() {
        ServerPlayers.addOp(dir, ServerPlayer(uuid = OfflineProfile.uuidFor("aaa"), name = "aaa", level = 9))
        assertEquals(4, ServerPlayers.ops(dir)[0].level)
        ServerPlayers.addOp(dir, ServerPlayer(uuid = OfflineProfile.uuidFor("bbb"), name = "bbb", level = 0))
        assertEquals(1, ServerPlayers.ops(dir).first { it.name == "bbb" }.level)
    }

    @Test
    fun `空名单写出来是空数组不是空文件`() {
        ServerPlayers.writeWhitelist(dir, emptyList())
        val text = File(dir, ServerPlayers.WHITELIST_NAME).readText(Charsets.UTF_8).trim()
        assertEquals("[]", text)
    }

    @Test
    fun `名字不合法时直接说清楚`() {
        assertFailsWith<IllegalArgumentException> { ServerPlayers.lookup("") }
        assertFailsWith<IllegalArgumentException> { ServerPlayers.lookup("has space") }
        assertFailsWith<IllegalArgumentException> { ServerPlayers.lookup("a".repeat(17)) }
    }

    @Test
    fun `离线 UUID 与启动器自己算的是同一个`() {
        val offline = OfflineProfile.uuidFor("TestPlayer")
        assertEquals(32, offline.length)
        assertEquals(offline, ServerPlayers.dashUuid(ServerPlayers.dashUuid(offline)).replace("-", ""))
    }

    @Test
    fun `32 位转带横线的标准写法`() {
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", ServerPlayers.dashUuid("069a79f444e94726a5befca90e38aaf5"))
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", ServerPlayers.dashUuid("069a79f4-44e9-4726-a5be-fca90e38aaf5"))
    }

    @Test
    fun `权限等级有中文说明`() {
        assertTrue(ServerPlayers.levelLabel(4).startsWith("4"))
        assertTrue(ServerPlayers.levelLabel(9).startsWith("4"))
        assertTrue(ServerPlayers.levelLabel(0).startsWith("1"))
    }
}
