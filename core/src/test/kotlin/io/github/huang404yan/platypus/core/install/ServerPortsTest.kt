package io.github.huang404yan.platypus.core.install

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerPortsTest {

    private val dir: File = Files.createTempDirectory("platypus-ports-test").toFile()

    @AfterTest
    fun tearDown() {
        runCatching { dir.deleteRecursively() }
    }

    private fun writeProperties(text: String) {
        File(dir, ServerPorts.PROPERTIES_NAME).writeText(text, Charsets.UTF_8)
    }

    @Test
    fun `没有 properties 文件时端口退回默认值`() {
        assertEquals(ServerPorts.DEFAULT_PORT, ServerPorts.portOf(dir))
    }

    @Test
    fun `读出 properties 里写明的端口`() {
        writeProperties("server-port=25566\nmotd=hello\n")
        assertEquals(25566, ServerPorts.portOf(dir))
    }

    @Test
    fun `等号两边有空格也认`() {
        writeProperties("server-port = 25570\n")
        assertEquals(25570, ServerPorts.parsePort(ServerPorts.readProperties(dir)))
    }

    @Test
    fun `注释里的 server-port 不算`() {
        writeProperties("#server-port=25566\nserver-port=25567\n")
        assertEquals(25567, ServerPorts.portOf(dir))
    }

    @Test
    fun `端口超范围或不是数字时退回默认值`() {
        writeProperties("server-port=70000\n")
        assertEquals(ServerPorts.DEFAULT_PORT, ServerPorts.portOf(dir))
        writeProperties("server-port=abc\n")
        assertEquals(ServerPorts.DEFAULT_PORT, ServerPorts.portOf(dir))
        writeProperties("server-port=0\n")
        assertEquals(ServerPorts.DEFAULT_PORT, ServerPorts.portOf(dir))
    }

    @Test
    fun `只有键名大于等于号的类似行不会被误认`() {
        assertNull(ServerPorts.parsePort("server-port\n"))
        assertNull(ServerPorts.parsePort("# server-port=25565\n"))
        assertNull(ServerPorts.parsePort("rcon.port=25575\n"))
    }

    @Test
    fun `端口没人用时探针说空闲`() {
        val port = freePort()
        assertTrue(ServerPorts.isFree(port), "刚释放的端口应该是空闲的")
    }

    @Test
    fun `端口被人占住时探针说被占`() {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(freePort()), 1)
            val busy = socket.localPort
            assertFalse(ServerPorts.isFree(busy), "有人在这个端口上听，探针不该说空闲")
        }
    }

    @Test
    fun `占用提示里带上端口号和要改的文件路径`() {
        val text = ServerPorts.occupiedHint(dir, 25565)
        assertTrue(text.contains("25565"), text)
        assertTrue(text.contains("server.properties"), text)
        assertTrue(text.contains("server-port"), text)
    }

    @Test
    fun `日志里四种绑定失败写法都认得出来`() {
        assertTrue(ServerPorts.looksLikeBindFailure("**** FAILED TO BIND TO PORT!"))
        assertTrue(ServerPorts.looksLikeBindFailure("java.net.BindException: Address already in use: bind"))
        assertTrue(ServerPorts.looksLikeBindFailure("Perhaps a server is already running on that port?"))
        assertTrue(ServerPorts.looksLikeBindFailure("Bind failed"))
    }

    @Test
    fun `普通日志不会被当成绑定失败`() {
        assertFalse(ServerPorts.looksLikeBindFailure("Done (12.345s)! For help, type \"help\""))
        assertFalse(ServerPorts.looksLikeBindFailure("[Server thread/INFO]: Starting minecraft server version 1.21.1"))
        assertFalse(ServerPorts.looksLikeBindFailure("Preparing level \"world\""))
    }

    @Test
    fun `事后认领的提示不说谁占着端口`() {
        val text = ServerPorts.bindFailureHint(dir, 25565)
        assertTrue(text.contains("25565"), text)
        assertTrue(text.contains("server-port"), text)
        assertFalse(text.contains("pid"), "事后认领时不该猜是谁占的：" + text)
    }

    /** 让系统挑一个当时空闲的端口再放开，比硬编码 25599 之类稳。 */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
