package io.github.huang404yan.platypus.desktop.runtime

import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.launch.GameHandle
import io.github.huang404yan.platypus.core.launch.GameLanguage
import io.github.huang404yan.platypus.core.launch.GameOptions
import io.github.huang404yan.platypus.core.launch.GameRuntime
import io.github.huang404yan.platypus.core.launch.LaunchDiagnosis
import io.github.huang404yan.platypus.core.launch.LaunchEvent
import io.github.huang404yan.platypus.core.launch.LaunchPlanner
import io.github.huang404yan.platypus.core.launch.LaunchRequest
import io.github.huang404yan.platypus.core.launch.LogLineReader
import io.github.huang404yan.platypus.core.launch.NativesExtractor
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 桌面侧的游戏启动：把版本 json 拼成命令行，起一个 java 进程，把日志逐行喂给调用方。
 *
 * 分工：**「怎么把一次启动翻译成命令行」在 core 的 [LaunchPlanner] 里**——读 json、
 * 合继承链、挑当前平台的库、拼参数、算要解压哪些 natives，全是纯计算，安卓以后能原样复用。
 * 这里只做 core 不许做的那件事：起进程（D5）。
 *
 * 线程模型：launch() 返回时进程已经起来了，读日志在单独的 IO 协程里跑，
 * 退出事件通过 [onEvent] 回调送回 —— 调用方不需要自己轮询。
 */
class DesktopGameRuntime(
    private val launcherName: String = Brand.NAME_EN,
    private val launcherVersion: String = Brand.version,
) : GameRuntime {

    /**
     * 日志泵用的作用域。
     *
     * 不需要在 stop() 里取消：进程一结束 stdout 就 EOF，泵自然收敛；
     * 用独立作用域是为了让回调里的异常不牵连调用方的协程。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun launch(
        request: LaunchRequest,
        onEvent: (LaunchEvent) -> Unit,
    ): GameHandle = withContext(Dispatchers.IO) {
        onEvent(LaunchEvent.Stage("准备版本文件", 0.05f))
        // 版本 json 在哪、继承链怎么合、classpath 里放什么，全由 planner 决定 ——
        // 这一段以前只住在这个类里，安卓要用还得再抄一遍。
        val plan = LaunchPlanner.plan(request, launcherName = launcherName, launcherVersion = launcherVersion)
        plan.notes.forEach { onEvent(LaunchEvent.LogLine(it)) }

        onEvent(LaunchEvent.Stage("整理 classpath", 0.1f))
        val context = plan.context

        onEvent(LaunchEvent.Stage("解压原生库", 0.15f))
        if (plan.nativeArchives.isNotEmpty()) {
            NativesExtractor.extract(plan.nativeArchives, context.nativesDir).warnings
                .forEach { message -> onEvent(LaunchEvent.LogLine(message)) }
        }

        // 语言写在进游戏之前：options.txt 是游戏自己读的，等它起来再改这一局不算数。
        // 设置里「跟随系统」存的是空串，resolve 会把它映射成本机系统语言 —— 所以这里
        // 不能再拿 isNotBlank 挡一道：那样「跟随系统」就成了什么都不做，用户以为没生效。
        val languageDir = plan.layout.gameDir
        when (val written = GameOptions.ensureLang(languageDir, request.gameLanguage)) {
            is GameOptions.LangWrite.Already ->
                onEvent(LaunchEvent.LogLine("游戏语言已经是 " + written.lang + "，没动 options.txt"))
            is GameOptions.LangWrite.Written ->
                onEvent(LaunchEvent.LogLine("游戏语言写成 " + written.lang))
            // 写失败不拦启动：语言没切成顶多是界面还是旧语言，比不让人进游戏强。
            is GameOptions.LangWrite.Failed ->
                onEvent(
                    LaunchEvent.LogLine(
                        "没能把游戏语言写成 " + written.lang + "（" +
                            GameOptions.fileOf(languageDir).path + " 可能没有写权限）",
                    ),
                )
        }

        val command = plan.command
        onEvent(LaunchEvent.Stage("启动游戏", 0.2f))

        val java = File(request.java.path)
        if (!java.isFile) {
            throw IllegalStateException("找不到 Java 可执行文件：" + request.java.path)
        }

        val arguments = mutableListOf(java.path)
        arguments += command.toArgumentList()

        // 把完整命令行落盘：加载器（Forge 之类）启动失败时，缺的参数就在这一行里，
        // 用户把日志发过来就能复现，不必再让他手工拼。accessToken 必须脱敏。
        LaunchPlanner.dumpCommandLine(plan.layout.gameDir, plan.layout.versionDir.name, arguments, request.session)
            ?.let { file -> onEvent(LaunchEvent.LogLine("命令行已写入 " + file.path)) }

        val builder = ProcessBuilder(arguments)
            .directory(context.gameDir)
            // Minecraft 的日志一半在 stdout 一半在 stderr，合流后逐行回调即可。
            .redirectErrorStream(true)
        val process = try {
            builder.start()
        } catch (t: Throwable) {
            throw IllegalStateException(
                "启动 Java 进程失败：" + (t.message ?: t.javaClass.simpleName),
                t,
            )
        }
        // 关掉 stdin：Minecraft 不读标准输入，留着只会让子进程在某些环境下卡住。
        runCatching { process.outputStream.close() }

        val handle = DesktopGameHandle(process)
        onEvent(LaunchEvent.Started(handle.pid ?: -1L))

        scope.launch {
            pump(process, handle) { line -> onEvent(LaunchEvent.LogLine(line)) }
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            handle.finished()
            onEvent(LaunchEvent.Exited(code, LaunchDiagnosis.diagnose(handle.logTail(), code)))
        }
        handle
    }

    /**
     * 逐行读日志，同时留在环形缓冲里供退出归因使用。
     *
     * 走 [LogLineReader] 而不是 `BufferedReader(reader(Charsets.UTF_8))`：游戏在中文 Windows 上
     * 是按 GBK 写 stdout 的，按 UTF-8 解会把每行中文变成 U+FFFD，字节一丢就再也拿不回来。
     * 这里先看字节、再挑编码解（UTF-8 优先，不像 UTF-8 才退回 GBK）。
     */
    private fun pump(
        process: Process,
        handle: DesktopGameHandle,
        onLine: (String) -> Unit,
    ) {
        runCatching {
            LogLineReader(process.inputStream).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    handle.append(line)
                    onLine(line)
                }
            }
        }
    }

}

/**
 * 一个已经在跑的游戏进程。
 *
 * [stop] 先礼后兵：给 [graceMillis] 让游戏自己保存退出，超时再强杀；
 * 返回值表示「调用后进程确实不在了」，不是「杀成功了吗」——已经自己退出的进程也算 true。
 */
internal class DesktopGameHandle(private val process: Process) : GameHandle {

    /** 只留最近若干行日志用于退出归因，长时间游戏不会把内存吃掉。 */
    private val lines = Collections.synchronizedList(ArrayDeque<String>(LOG_CAPACITY))

    @Volatile
    private var exited = false

    override val pid: Long? = runCatching { process.pid() }.getOrNull()

    override fun isAlive(): Boolean = !exited && process.isAlive

    override suspend fun stop(graceMillis: Long): Boolean = withContext(Dispatchers.IO) {
        if (!process.isAlive) {
            exited = true
            return@withContext true
        }
        process.destroy()
        val graceful = runCatching {
            process.waitFor(graceMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!graceful && process.isAlive) {
            process.destroyForcibly()
            runCatching { process.waitFor(3, TimeUnit.SECONDS) }
        }
        val gone = runCatching { !process.isAlive }.getOrDefault(true)
        exited = true
        gone
    }

    fun append(line: String) {
        synchronized(lines) {
            if (lines.size >= LOG_CAPACITY) lines.removeFirst()
            lines.addLast(line)
        }
    }

    fun logTail(): List<String> = synchronized(lines) { lines.toList() }

    fun finished() {
        exited = true
    }

    private companion object {
        const val LOG_CAPACITY = 500
    }
}
