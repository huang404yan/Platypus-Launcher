package io.github.huang404yan.platypus.ui

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 命令通道不能把界面线程堵住。
 *
 * 为什么要有这一条：RCON 的回执上限是「连接 3 秒 + 回执 5 秒」，而两处「发送」按钮的回调
 * 原本就在 AWT 事件线程上同步等。服务端不回包时实测界面冻住 4705ms —— 用户看到的是
 * 「点了没反应，过一会儿才刷出五个字」。修法是把等待挪进协程（[ServerRuntime.send] 变成
 * suspend），这条测试就盯着那件事：命令照旧要等，但等的时候事件线程必须能跑。
 *
 * 为什么用一个自造的假 RCON：真服务端要下载几十 MB、启动几十秒，而这里要验的不是
 * Minecraft 的行为，是「等待发生在哪个线程」。假 RCON 登录成功后一句回执都不发，
 * 正好把客户端卡在它的回执超时上 —— 慢了就说明又有人把阻塞等回来了。
 */
class ServerCommandThreadingTest {

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @AfterTest
    fun tearDown() {
        ServerRuntime.stop()
        waitUntil(20_000) { !ServerRuntime.state.value.running && !ServerRuntime.isProcessAlive() }
    }

    @Test
    fun `发命令时界面线程不被堵住`() {
        if (!isWindows) return // 假脚本用 cmd 语法，别的平台跳过
        val dir = Files.createTempDirectory("platypus-edt-test").toFile()
        File(dir, "eula.txt").writeText("eula=true\n", Charsets.UTF_8)
        File(dir, "start.bat").writeText(
            listOf(
                "@echo off",
                "chcp 65001 >nul",
                "cd /d %~dp0",
                "echo [BOOT] fake server ready",
                ":loop",
                "set /p line=",
                "if \"%line%\"==\"\" goto loop",
                "echo [ECHO] %line%",
                "if not \"%line%\"==\"stop\" goto loop",
                "echo Minecraft 服务端已停止，按任意键关闭窗口。",
                "pause >nul",
            ).joinToString("\r\n") + "\r\n",
            Charsets.UTF_8,
        )
        SilentRcon("pw123").use { rcon ->
            File(dir, "server.properties").writeText(
                "enable-rcon=true\r\nrcon.port=" + rcon.port + "\r\nrcon.password=pw123\r\n",
                Charsets.UTF_8,
            )
            ServerRuntime.start(dir.absolutePath)
            assertTrue(
                waitUntil(25_000) { ServerRuntime.state.value.channel == "RCON" },
                "没接上 RCON，channel=" + ServerRuntime.state.value.channel,
            )

            // 与产品里的写法一致：界面线程只 launch，等待落在协程里。派发器必须用 Swing，
            // 因为产品的 rememberCoroutineScope() 就落在 AWT 事件线程上；换成 IO 就等于
            // 把等待搬离事件线程，那样即使 send 里又写回 runBlocking 也测不出来。
            val host = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
            val done = CountDownLatch(1)
            try {
                SwingUtilities.invokeLater {
                    host.launch {
                        runCatching { ServerRuntime.send("list") }
                        done.countDown()
                    }
                }
                // 给那条命令一点时间走到「已经开始等回执」的位置，再量事件线程。
                Thread.sleep(300)
                val start = System.nanoTime()
                SwingUtilities.invokeAndWait { }
                val stallMs = (System.nanoTime() - start) / 1_000_000.0
                // 回执超时是 5 秒；只要事件线程没被拖进去，这里就是个位数毫秒。
                assertTrue(stallMs < 500.0, "界面线程被堵了 " + stallMs + " ms，命令的等待又跑回事件线程了")
                // 命令本身仍然要等满超时 —— 证明「没堵」不是因为命令根本没发出去。
                assertTrue(done.await(20, TimeUnit.SECONDS), "命令一直没返回")
            } finally {
                host.cancel()
            }
            ServerRuntime.stop()
            waitUntil(20_000) { !ServerRuntime.state.value.running && !ServerRuntime.isProcessAlive() }
            runCatching { dir.deleteRecursively() }
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    /** 登录能成功，但收到命令后一句回执都不发 —— 让客户端正好卡满它的回执超时。 */
    private class SilentRcon(password: String) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private var socket: Socket? = null
        val port: Int get() = server.localPort
        private val thread: Thread = Thread {
            runCatching {
                val client = server.accept()
                socket = client
                val input: InputStream = client.getInputStream()
                val out: OutputStream = client.getOutputStream()
                val length = readInt(input)
                if (length < 10) return@runCatching
                val body = ByteArray(length)
                var filled = 0
                while (filled < length) {
                    val n = input.read(body, filled, length - filled)
                    if (n < 0) break
                    filled += n
                }
                val id = intAt(body, 0)
                val type = intAt(body, 4)
                val payload = String(body, 8, length - 10, Charsets.UTF_8)
                if (type != 3 || payload != password) {
                    writePacket(out, -1, 2, "")
                    return@runCatching
                }
                writePacket(out, id, 2, "")
                // 之后什么都不回：让客户端在 command() 里等满回执超时。
                while (!client.isClosed) Thread.sleep(200)
            }
        }.apply { isDaemon = true }

        init { thread.start() }

        private fun writePacket(out: OutputStream, id: Int, type: Int, payload: String) {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            writeInt(out, 4 + 4 + bytes.size + 2)
            writeInt(out, id)
            writeInt(out, type)
            out.write(bytes)
            out.write(0)
            out.write(0)
            out.flush()
        }

        private fun writeInt(out: OutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        private fun readInt(input: InputStream): Int {
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
            runCatching { socket?.close() }
            runCatching { server.close() }
            runCatching { thread.interrupt() }
        }
    }
}
