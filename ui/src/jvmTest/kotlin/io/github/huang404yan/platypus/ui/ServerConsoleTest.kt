package io.github.huang404yan.platypus.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
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
        runCatching { dir.deleteRecursively() }
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
