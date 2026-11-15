package io.github.huang404yan.platypus.ui

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 服务端运行时的自动化验证。
 *
 * 为什么不靠点界面：Compose 自绘的控件不暴露 UIA 树，模拟点击既不可靠也不可复现。
 * 这里直接把 [ServerRuntime] 当成被测对象 —— 它才是「能不能开服」的全部逻辑，
 * 界面只是把它的状态画出来。
 *
 * 用一个假的 start.bat 代替真服务端：真 jar 要下载几十 MB、启动几十秒，
 * 而这里要验的是**管道**（stdout 读得到、stdin 写得进、进程树杀得干净），
 * 假脚本能把这四件事全部覆盖，且秒级完成。
 */
class ServerConsoleTest {

    private val dir: File = Files.createTempDirectory("platypus-server-test").toFile()

    @AfterTest
    fun tearDown() {
        ServerRuntime.stop()
        // stop() 是异步的：它发出 stop 命令后由另一个协程等着进程退出。不等它落定就删目录、
        // 让下一条测试开始，会出现「上一条的进程还活着 → start() 在 running 时直接 return →
        // 这一条什么都没发生」的假失败（实测过一次：整跑挂、单跑过）。
        waitUntil(20_000) { !ServerRuntime.state.value.running && !ServerRuntime.isProcessAlive() }
        runCatching { dir.deleteRecursively() }
    }

    @BeforeTest
    fun waitIdle() {
        // 双保险：单例的运行状态可能被上一条或别的测试类留着。
        if (ServerRuntime.state.value.running || ServerRuntime.state.value.starting) {
            ServerRuntime.stop()
            waitUntil(20_000) { !ServerRuntime.state.value.running && !ServerRuntime.isProcessAlive() }
        }
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Test
    fun `起脚本、读日志、发命令、停止`() {
        if (!isWindows) return // 假脚本用 cmd 语法，别的平台跳过
        writeEula()
        writeScript(
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
            ),
        )

        ServerRuntime.start(dir.absolutePath)
        assertTrue(waitUntil(8000) { ServerRuntime.state.value.running }, "进程没有进入 running")
        assertTrue(
            waitUntil(8000) { ServerRuntime.state.value.lines.any { it.contains("[BOOT]") } },
            "没读到启动输出，实际日志：" + ServerRuntime.state.value.lines,
        )

        ServerRuntime.send("list")
        assertTrue(
            waitUntil(8000) { ServerRuntime.state.value.lines.any { it.contains("[ECHO] list") } },
            "命令没有被服务端读到，实际日志：" + ServerRuntime.state.value.lines,
        )

        ServerRuntime.stop()
        // stop() 先写 stop，脚本回显「停止」那句后 pause；pump 读到那句会补回车放行，
        // 所以这里能等到进程真的退出 —— 这条同时验证了 pause 不会被挂住。
        assertTrue(waitUntil(15000) { !ServerRuntime.state.value.running }, "停止后状态没有回落")
        assertFalse(ServerRuntime.isProcessAlive(), "进程还在")
    }

    @Test
    fun `没有 eula 时拒绝启动并说明原因`() {
        writeScript(listOf("@echo off", "echo should-not-run"))
        ServerRuntime.start(dir.absolutePath)
        // 拒绝是同步发生的，不需要等
        val state = ServerRuntime.state.value
        assertFalse(state.running, "没有 eula 却启动了")
        assertTrue(state.notice.contains("EULA"), "没有给出 EULA 提示，实际：" + state.notice)
    }

    @Test
    fun `目录里没有脚本时给出可操作的提示`() {
        writeEula()
        ServerRuntime.start(dir.absolutePath)
        val state = ServerRuntime.state.value
        assertFalse(state.running, "没有脚本却启动了")
        assertTrue(state.notice.contains("开服脚本"), "提示不对，实际：" + state.notice)
    }

    @Test
    fun `端口已被占用时不开进程并说清怎么改`() {
        if (!isWindows) return
        writeEula()
        writeScript(listOf("@echo off", "echo should-not-run"))
        // 真占一个端口：比起硬编码 25599，这样不会和机器上别的东西撞车。
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(0), 1)
            File(dir, "server.properties").writeText("server-port=" + socket.localPort + "\n", Charsets.UTF_8)
            ServerRuntime.start(dir.absolutePath)
            // 预检在后台线程里做（要跑 netstat 查占用者），所以要等它出结论。
            assertTrue(
                waitUntil(8000) { ServerRuntime.state.value.notice.isNotBlank() },
                "没有给出任何提示",
            )
            val state = ServerRuntime.state.value
            assertFalse(state.running, "端口被占还照样起进程了")
            assertFalse(state.starting, "预检完还停在「正在启动」")
            assertTrue(state.notice.contains(socket.localPort.toString()), "提示里没写端口号：" + state.notice)
            assertTrue(state.notice.contains("server-port"), "提示里没说改哪个键：" + state.notice)
            assertFalse(
                state.lines.any { it.contains("should-not-run") },
                "脚本被执行了，说明预检没挡住：" + state.lines,
            )
        }
    }

    @Test
    fun `日志里出现绑定失败时会给出提示且不被后续日志擦掉`() {
        if (!isWindows) return
        writeEula()
        writeScript(
            listOf(
                "@echo off",
                "chcp 65001 >nul",
                "echo **** FAILED TO BIND TO PORT!",
                "echo java.net.BindException: Address already in use: bind",
                "echo [Server] 后面还有几行普通日志",
                "echo [Server] 又一行普通日志",
                "exit /b 1",
            ),
        )
        ServerRuntime.start(dir.absolutePath)
        // 关键点：说明是在日志中间认出来的，后面还会继续刷行；那些行不许把说明顶掉。
        assertTrue(
            waitUntil(8000) { ServerRuntime.state.value.lines.any { it.contains("又一行普通日志") } },
            "脚本没跑完，实际日志：" + ServerRuntime.state.value.lines,
        )
        val notice = ServerRuntime.state.value.notice
        assertTrue(notice.contains("server-port"), "说明被后面的日志擦掉了，实际：" + notice)
    }

    private fun writeEula() {
        File(dir, "eula.txt").writeText("eula=true\n", Charsets.UTF_8)
    }

    private fun writeScript(lines: List<String>) {
        File(dir, "start.bat").writeText(lines.joinToString("\r\n") + "\r\n", Charsets.UTF_8)
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
