package io.github.huang404yan.platypus.core.install

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 跑安装器（Forge / NeoForge 的官方 jar）的那个子进程。
 *
 * 抽出来是因为它比「起个进程读日志」多三件必须做对的事：
 *
 * 1. **取消要真的杀掉进程**。用户在界面上点「取消」，协程被取消，但 [ProcessBuilder] 起的
 *    java 不会自己退出 —— 它还在后台往 libraries/ 里写文件，用户以为停了、实际没停。
 *    这里挂 Job 的 invokeOnCompletion，协程一结束就 destroyForcibly，连派生的子进程一起杀。
 * 2. **取消不能被当成「安装失败」**。CancellationException 是 Exception 的子类，
 *    调用方那些 `catch (e: Exception)` 会把它包成「运行安装器失败」，界面于是显示红色报错。
 *    这一层在返回前 ensureActive，让取消以取消的形式冒出去。
 * 3. **日志必须落盘**。进程被强杀时，用户唯一能看的就是这份文件。
 *
 * 两个容易写错的地方，这里都已经踩过：
 * - **超时不能只在「读到一行日志」时判断**。安装器有时几十分钟不打印任何东西（在下大库），
 *   把 deadline 检查放在读循环里，等于对没输出的进程永不生效。所以日志由独立的协程去读，
 *   主循环用 waitFor(轮询) 盯进程是否结束、是否过点。
 * - **顺序很要紧**：先 waitFor 到结束，再 join 读日志的协程。反过来会死等一个还没 EOF 的管道。
 *
 * 续跑不需要额外状态：installer 自己见到 libraries/ 里已有且 sha1 相符的库就跳过，
 * 所以「取消 / 断网 / 超时」之后再点一次安装，会从缺的那几个库接着来。
 */
internal object InstallerProcess {

    /** 强杀后等它收尾的时间：太久会让「取消」按钮看起来没反应。 */
    private const val KILL_WAIT_SECONDS = 5L

    /** 盯进程的轮询间隔。要小到点「取消」不觉得卡，又不能小到空转烧 CPU。 */
    private const val POLL_MILLIS = 100L

    /**
     * @param timeoutMillis 从起进程算起的硬超时；到点强杀并抛 IOException。
     *   接的是毫秒而不是分钟，因为单测要能用几百毫秒验证「到点真的被杀了」，等一分钟的测试没人跑。
     * @param logFile 安装器日志；第一行写完整命令行，第二行写 pid，之后是它自己的输出。
     * @param onLine 每读一行回调一次（已 trim，空行不回）。
     * @return 进程退出码。
     * @throws IOException 超时，或启动失败。
     * @throws CancellationException 调用方取消了。
     */
    suspend fun run(
        command: List<String>,
        workingDir: File,
        logFile: File,
        timeoutMillis: Long,
        onLine: (String) -> Unit,
    ): Int = coroutineScope {
        if (command.isEmpty()) throw IOException("没有可执行的命令")
        val builder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
        val process = builder.start()
        // 协程被取消（点「取消」、界面关掉）时立刻杀进程；只靠轮询发现不了取消。
        val guard = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) kill(process)
        }
        logFile.parentFile?.mkdirs()
        val deadline = System.currentTimeMillis() + timeoutMillis

        // 日志由独立协程读：主循环得空出手来盯超时与取消。
        val pump = launch(Dispatchers.IO) {
            logFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                writeLine(writer, "# " + command.joinToString(" "))
                // 进程号也写进日志：装坏了要排查时，用户能直接说「这个 pid 卡住了」，
                // 也方便单测确认「取消之后这个进程真的没了」。
                writeLine(writer, "# pid=" + process.pid())
                // 立刻刷盘：安装器常有几十秒不打印任何东西的时候，不刷的话这两行一直躺在缓冲区里，
                // 用户（和单测）打开日志只看到一个空文件，会以为进程压根没起来。
                writer.flush()
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        writeLine(writer, line)
                        // 刷盘：进程被强杀时日志也得留下来给用户看
                        writer.flush()
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) onLine(trimmed)
                    }
                }
            }
        }

        try {
            while (true) {
                if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) break
                // 取消到达时协程已经是取消态，这里立刻抛出去（guard 已经把进程杀了）。
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() > deadline) {
                    kill(process)
                    throw IOException(
                        "安装器超过 " + describeTimeout(timeoutMillis) + "没跑完，已强制结束；日志在 " +
                            logFile.absolutePath,
                    )
                }
            }
            val exitCode = process.exitValue()
            pump.join()
            // 进程被我们杀掉之后 waitFor 会返回 true，这里必须先把「其实是取消了」抛出去，
            // 否则调用方看到的就是一个莫名其妙的非零退出码 + 一句「安装器退出码 1」。
            currentCoroutineContext().ensureActive()
            exitCode
        } finally {
            guard.dispose()
            if (process.isAlive) kill(process)
            pump.cancel()
        }
    }

    private fun writeLine(writer: java.io.BufferedWriter, line: String) {
        writer.write(line, 0, line.length)
        writer.newLine()
    }

    /** 正常安装是十几分钟，说成「0 分钟」会让人以为没设超时。 */
    private fun describeTimeout(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        return if (minutes >= 1L) {
            " " + minutes + " 分钟"
        } else {
            " " + TimeUnit.MILLISECONDS.toSeconds(millis) + " 秒"
        }
    }

    /** 连它派生的子进程一起杀：只杀父进程的话，installer 拉起的 java 子任务会变成孤儿。 */
    private fun kill(process: Process) {
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS) }
    }
}
