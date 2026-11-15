package io.github.huang404yan.platypus.core.server

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RCON 协议的验证。
 *
 * 为什么要自己起一个假服务端、而不是只测编码函数：RCON 是一份**双端**约定，
 * 长度字段是不是小端、正文末尾那两个 0 有没有算进长度、密码错时回的是哪个请求号 ——
 * 这些只有真收发一遍才知道对不对。真 Minecraft 服务端要下载几十 MB 并启动几十秒，
 * 而假服务端能把同样的字节流造出来，秒级完成。
 */
class RconTest {

    private val root: File = Files.createTempDirectory("platypus-rcon-test").toFile()

    @AfterTest
    fun tearDown() {
        runCatching { root.deleteRecursively() }
    }

    @Test
    fun `登录成功后发命令能拿到回执`() {
        FakeRcon(password = "hunter2", replies = listOf("There are 0 of a max of 20 players online")).use { fake ->
            RconClient(port = fake.port, password = "hunter2").use { client ->
                client.connect()
                val reply = client.command("list")
                assertEquals("There are 0 of a max of 20 players online", reply)
                assertEquals(listOf("list"), fake.received)
            }
        }
    }

    @Test
    fun `密码不对时说明是密码不对`() {
        FakeRcon(password = "right", replies = listOf("x")).use { fake ->
            val error = assertFailsWith<RconException> {
                RconClient(port = fake.port, password = "wrong").connect()
            }
            assertContains(error.message.orEmpty(), "密码不对")
        }
    }

    @Test
    fun `端口没开时给的是连接失败而不是别的`() {
        // 先占一个口再关掉，拿到一个确定没人听的端口号。
        val port = ServerSocket(0).use { it.localPort }
        val error = assertFailsWith<RconException> {
            RconClient(port = port, password = "x", connectTimeoutMillis = 1500).connect()
        }
        assertContains(error.message.orEmpty(), "连不上 RCON")
        assertContains(error.message.orEmpty(), port.toString())
    }

    @Test
    fun `长回执被拆成多包时拼成一份`() {
        FakeRcon(
            password = "pw",
            replies = listOf("第一段很长很长", "第二段也很长"),
        ).use { fake ->
            RconClient(port = fake.port, password = "pw").use { client ->
                client.connect()
                val reply = client.command("help")
                assertContains(reply, "第一段很长很长")
                assertContains(reply, "第二段也很长")
            }
        }
    }

    @Test
    fun `空命令不发出去`() {
        FakeRcon(password = "pw", replies = listOf("不该出现")).use { fake ->
            RconClient(port = fake.port, password = "pw").use { client ->
                client.connect()
                assertEquals("", client.command("   "))
            }
            assertEquals(emptyList(), fake.received)
        }
    }

    @Test
    fun `没连上就发命令会明确报错`() {
        val error = assertFailsWith<RconException> {
            RconClient(port = 25575, password = "x").command("list")
        }
        assertContains(error.message.orEmpty(), "还没连上")
    }

    @Test
    fun `写出去的包长度与小端字节都按协议来`() {
        FakeRcon(password = "pw", replies = listOf("ok")).use { fake ->
            RconClient(port = fake.port, password = "pw").use { client ->
                client.connect()
                client.command("say 你好")
            }
            // 登录包 + 一条命令包，各自校验：长度 = 4 + 4 + 正文字节 + 2。
            val packets = fake.rawPackets
            assertEquals(2, packets.size, "实际收到 " + packets.size + " 个包")
            for (packet in packets) {
                assertEquals(4 + 4 + packet.payload.toByteArray(Charsets.UTF_8).size + 2, packet.length)
            }
            // 「say 你好」的 UTF-8 是 4 + 6 = 10 字节，长度应为 20。
            assertEquals(20, packets[1].length)
            assertEquals(RconPacket.TYPE_LOGIN, packets[0].type)
            assertEquals(RconPacket.TYPE_COMMAND, packets[1].type)
        }
    }

    @Test
    fun `连上不是 RCON 的端口时会说这个地址不像 RCON`() {
        // 一个只会吐垃圾字节的口子。
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            var accepted: Socket? = null
            val worker = Thread {
                runCatching {
                    accepted = server.accept()
                    accepted?.getOutputStream()?.let { out ->
                        out.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F))
                        out.flush()
                    }
                }
            }
            worker.isDaemon = true
            worker.start()
            val error = assertFailsWith<RconException> {
                RconClient(port = server.localPort, password = "pw", replyTimeoutMillis = 2000).connect()
            }
            assertTrue(
                error.message.orEmpty().contains("包长度不对") || error.message.orEmpty().contains("RCON"),
                "提示不对：" + error.message,
            )
            runCatching { accepted?.close() }
        }
    }

    @Test
    fun `设置从三行配置里读出来`() {
        val view = ServerProperties.parse(
            listOf(
                "enable-rcon=true",
                "rcon.port=25580",
                "rcon.password=abc123",
            ).joinToString("\r\n") + "\r\n",
        )
        val settings = RconClient.settingsOf(view)
        assertTrue(settings.enabled)
        assertEquals(25580, settings.port)
        assertEquals("abc123", settings.password)
        assertTrue(settings.usable)
        assertEquals(null, settings.problem)
    }

    @Test
    fun `没开 RCON 时说清是哪个键没开`() {
        val settings = RconClient.settingsOf(ServerProperties.parse(""))
        assertFalse(settings.enabled)
        assertEquals(RconClient.DEFAULT_PORT, settings.port)
        assertFalse(settings.usable)
        assertContains(settings.problem.orEmpty(), "enable-rcon")
    }

    @Test
    fun `开了却没设密码时说不接受连接`() {
        val view = ServerProperties.parse("enable-rcon=true" + "\r\n")
        val settings = RconClient.settingsOf(view)
        assertFalse(settings.usable)
        assertContains(settings.problem.orEmpty(), "密码")
    }

    @Test
    fun `建议的密码不含容易看错的字符`() {
        val password = RconClient.suggestPassword()
        assertEquals(16, password.length)
        // 只排掉真正会看错的四个：0 与 O、1 与 I。其它数字与字母都留着。
        assertTrue(password.none { it in "01OI" }, "含容易看错的字符：" + password)
        assertTrue(password.all { it.isUpperCase() || it.isDigit() })
    }

    @Test
    fun `配置从目录里读得出来`() {
        File(root, ServerProperties.FILE_NAME).writeText(
            "enable-rcon=true\r\nrcon.port=25590\r\nrcon.password=dirpw\r\n",
            Charsets.UTF_8,
        )
        val settings = RconClient.settingsOf(root)
        assertEquals(25590, settings.port)
        assertEquals("dirpw", settings.password)
    }

    /** 收到的一个包，原样留着供断言。 */
    private data class SeenPacket(val length: Int, val id: Int, val type: Int, val payload: String)

    /**
     * 一个只说 RCON 的假服务端。
     *
     * 线程里同步收发：测试都是一问一答，不需要真的并发。
     */
    private class FakeRcon(private val password: String, private val replies: List<String>) : AutoCloseable {

        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private var client: Socket? = null
        private val thread: Thread

        val port: Int get() = server.localPort
        val received: MutableList<String> = mutableListOf()
        val rawPackets: MutableList<SeenPacket> = mutableListOf()

        init {
            thread = Thread { serve() }
            thread.isDaemon = true
            thread.start()
        }

        private fun serve() {
            runCatching {
                val socket = server.accept()
                client = socket
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                while (!socket.isClosed) {
                    val length = readInt(input)
                    if (length < 10) break
                    val body = ByteArray(length)
                    var filled = 0
                    while (filled < length) {
                        val n = input.read(body, filled, length - filled)
                        if (n < 0) break
                        filled += n
                    }
                    if (filled < length) break
                    val id = intAt(body, 0)
                    val type = intAt(body, 4)
                    val payload = String(body, 8, length - 10, Charsets.UTF_8)
                    rawPackets.add(SeenPacket(length, id, type, payload))
                    when (type) {
                        RconPacket.TYPE_LOGIN -> {
                            if (payload == password) {
                                writePacket(out, id, RconPacket.TYPE_COMMAND, "")
                            } else {
                                writePacket(out, RconPacket.AUTH_FAILED_ID, RconPacket.TYPE_COMMAND, "")
                            }
                        }
                        RconPacket.TYPE_COMMAND -> {
                            received.add(payload)
                            // 把这一轮的回执**全发出去**：真实服务端的长回执就是连着好几个包，
                            // 中间不会停下来等下一个请求。这里照着造。
                            for (reply in replies) {
                                writePacket(out, id, RconPacket.TYPE_RESPONSE, reply)
                            }
                        }
                        else -> break
                    }
                }
            }
        }

        private fun writePacket(out: java.io.OutputStream, id: Int, type: Int, payload: String) {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            writeInt(out, 4 + 4 + bytes.size + 2)
            writeInt(out, id)
            writeInt(out, type)
            out.write(bytes)
            out.write(0)
            out.write(0)
            out.flush()
        }

        private fun writeInt(out: java.io.OutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        private fun readInt(input: java.io.InputStream): Int {
            val b0 = input.read()
            if (b0 < 0) return -1
            val b1 = input.read()
            val b2 = input.read()
            val b3 = input.read()
            if (b1 < 0 || b2 < 0 || b3 < 0) return -1
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        private fun intAt(body: ByteArray, offset: Int): Int {
            val b0 = body[offset].toInt() and 0xFF
            val b1 = body[offset + 1].toInt() and 0xFF
            val b2 = body[offset + 2].toInt() and 0xFF
            val b3 = body[offset + 3].toInt() and 0xFF
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        override fun close() {
            runCatching { client?.close() }
            runCatching { server.close() }
            runCatching { thread.join(1000) }
        }
    }
}