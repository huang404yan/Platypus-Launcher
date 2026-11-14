package io.github.huang404yan.platypus.core.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 安装器子进程这一层。
 *
 * 盯的是三件真实发生过的事：
 * 1. Forge 一跑三分钟，用户点「取消」之后进程必须真的死掉 —— 否则它继续往 libraries/ 写文件；
 * 2. 「取消」不能被当成「安装失败」，不能包成 ForgeInstallException 让界面报红；
 * 3. 安装器在下载大库时几十秒不打印一行日志，超时照样要生效 —— 把 deadline 判断塞进读日志的
 *    循环里，对不出声的进程等于没设超时。
 */
class InstallerProcessTest {

    private fun tempDir(name: String): File =
        Files.createTempDirectory("platypus-installer-" + name).toFile().also { it.deleteOnExit() }

    private val windows: Boolean
        get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** 很快结束、会打印一行的命令。 */
    private fun quickCommand(): List<String> =
        if (windows) listOf("cmd", "/c", "echo", "hello") else listOf("/bin/sh", "-c", "echo hello")

    /** 至少跑半分钟、**一个字节都不输出**的命令：正是超时逻辑最容易漏掉的那种进程。 */
    private fun silentSlowCommand(): List<String> =
        if (windows) {
            listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 30")
        } else {
            listOf("/bin/sh", "-c", "sleep 30")
        }

    private fun pidFrom(log: File): Long? =
        log.takeIf { it.isFile }?.readText(Charsets.UTF_8)
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("# pid=") }
            ?.removePrefix("# pid=")
            ?.trim()
            ?.toLongOrNull()

    @Test
    fun `跑完把退出码原样返回并落日志`() = runBlocking {
        val dir = tempDir("ok")
        try {
            val log = File(dir, "install.log")
            val lines = mutableListOf<String>()
            val exit = InstallerProcess.run(
                command = quickCommand(),
                workingDir = dir,
                logFile = log,
                timeoutMillis = 60_000L,
            ) { lines += it }
            assertEquals(0, exit)
            assertTrue(lines.any { it.contains("hello") }, "安装器的输出要往界面上推，实际：" + lines)
            val text = log.readText(Charsets.UTF_8)
            assertTrue(text.startsWith("# "), "日志第一行应当是完整命令行，实际：" + text.take(60))
            assertTrue(pidFrom(log) != null, "日志里要留 pid，排障时能直接说是哪个进程")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `退出码非零时不抛异常而是原样返回`() = runBlocking {
        // 退出码的解释交给调用方：Forge 的 0 与非 0 分别对应「装好了」与「看日志」。
        val dir = tempDir("code")
        try {
            val command = if (windows) listOf("cmd", "/c", "exit", "3") else listOf("/bin/sh", "-c", "exit 3")
            val exit = InstallerProcess.run(
                command = command,
                workingDir = dir,
                logFile = File(dir, "install.log"),
                timeoutMillis = 60_000L,
            ) {}
            assertEquals(3, exit)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `不出声的进程也会在超时后被杀掉`() = runBlocking {
        val dir = tempDir("timeout")
        try {
            val log = File(dir, "install.log")
            val started = System.currentTimeMillis()
            val error = runCatching {
                InstallerProcess.run(
                    command = silentSlowCommand(),
                    workingDir = dir,
                    logFile = log,
                    timeoutMillis = 1_200L,
                ) {}
            }.exceptionOrNull()
            val elapsed = System.currentTimeMillis() - started
            assertTrue(error is IOException, "超时应当抛 IOException，实际：" + error)
            assertTrue(
                error.message?.contains("没跑完") == true,
                "报错要说明是超时，实际：" + error.message,
            )
            assertTrue(elapsed < 20_000L, "超时判定要及时生效，实际等了 " + elapsed + " 毫秒")
            val pid = pidFrom(log)
            assertTrue(pid != null, "日志里要留 pid")
            // kill() 里已经 waitFor 过，这里再给系统一点收尸时间
            delay(300)
            assertTrue(
                !ProcessHandle.of(pid).isPresent,
                "超时之后子进程必须真的被杀掉，pid=" + pid,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `取消时进程被杀且以取消的形式抛出`() = runBlocking {
        val dir = tempDir("cancel")
        try {
            val log = File(dir, "install.log")
            val job = launch(Dispatchers.Default) {
                InstallerProcess.run(
                    command = silentSlowCommand(),
                    workingDir = dir,
                    logFile = log,
                    timeoutMillis = 600_000L,
                ) {}
            }
            // 等它起来并把 pid 写进日志，再取消
            var waited = 0
            while (pidFrom(log) == null && waited < 10_000) {
                delay(100)
                waited += 100
            }
            val pid = pidFrom(log)
            assertTrue(pid != null, "进程起来之后应当立刻把 pid 写进日志")
            job.cancel()
            job.join()
            assertTrue(job.isCancelled, "取消之后 job 应当处于取消态")
            delay(300)
            assertTrue(
                !ProcessHandle.of(pid).isPresent,
                "点了「取消」之后安装器进程必须死掉，否则它还在后台写 libraries/，pid=" + pid,
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
