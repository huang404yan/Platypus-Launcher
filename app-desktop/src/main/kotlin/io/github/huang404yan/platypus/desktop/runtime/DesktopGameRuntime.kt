package io.github.huang404yan.platypus.desktop.runtime

import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.launch.GameHandle
import io.github.huang404yan.platypus.core.launch.GameRuntime
import io.github.huang404yan.platypus.core.launch.LaunchArguments
import io.github.huang404yan.platypus.core.launch.LaunchContext
import io.github.huang404yan.platypus.core.launch.LaunchDiagnosis
import io.github.huang404yan.platypus.core.launch.LaunchEvent
import io.github.huang404yan.platypus.core.launch.LaunchRequest
import io.github.huang404yan.platypus.core.launch.NativesExtractor
import io.github.huang404yan.platypus.core.launch.VersionResolver
import io.github.huang404yan.platypus.core.version.VersionJson
import java.io.BufferedReader
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
 * 分工与 t3 的 [DesktopJavaRuntimeService] 一致——core 里只放纯计算
 * （[LaunchArguments] 拼参数、[NativesExtractor] 解压、[LaunchDiagnosis] 归因），
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
        val layout = resolveLayout(request.instance)
        val resolved = VersionResolver.load(layout.gameDir, layout.versionDir.name)
            ?: throw IllegalStateException(
                "版本文件不存在：" + File(layout.versionDir, layout.versionDir.name + ".json").path,
            )
        resolved.warnings.forEach { onEvent(LaunchEvent.LogLine(it)) }
        val version = resolved.version

        onEvent(LaunchEvent.Stage("整理 classpath", 0.1f))
        val context = buildContext(request, layout, version, resolved.clientJar)

        onEvent(LaunchEvent.Stage("解压原生库", 0.15f))
        extractNatives(version, context) { message -> onEvent(LaunchEvent.LogLine(message)) }

        val command = LaunchArguments.build(version, context)
        onEvent(LaunchEvent.Stage("启动游戏", 0.2f))

        val java = File(request.java.path)
        if (!java.isFile) {
            throw IllegalStateException("找不到 Java 可执行文件：" + request.java.path)
        }

        val arguments = mutableListOf(java.path)
        arguments += command.toArgumentList()

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

    /** 逐行读日志，同时留在环形缓冲里供退出归因使用。 */
    private fun pump(
        process: Process,
        handle: DesktopGameHandle,
        onLine: (String) -> Unit,
    ) {
        runCatching {
            BufferedReader(process.inputStream.reader(Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    handle.append(line)
                    onLine(line)
                }
            }
        }
    }

    /** 版本目录、游戏根目录、库与资源目录——都从实例目录反推。 */
    private fun resolveLayout(instance: Instance): GameLayout {
        val versionDir = File(instance.directory)
        // InstanceScanner 把 instance.directory 设成 versions/<id>，所以游戏根目录是上两级。
        val gameDir = versionDir.parentFile?.parentFile ?: versionDir
        return GameLayout(
            versionDir = versionDir,
            gameDir = gameDir,
            librariesDir = File(gameDir, "libraries"),
            assetsDir = File(gameDir, "assets"),
        )
    }

    private fun buildContext(
        request: LaunchRequest,
        layout: GameLayout,
        version: VersionJson,
        resolvedJar: File?,
    ): LaunchContext {
        val nativesDir = LaunchArguments.nativesDirFor(layout.gameDir, layout.versionDir.name)
        val placeholder = LaunchContext(
            gameDir = layout.gameDir,
            assetsDir = layout.assetsDir,
            librariesDir = layout.librariesDir,
            nativesDir = nativesDir,
            classpath = emptyList(),
            session = request.session,
            versionName = layout.versionDir.name,
            memoryMb = request.instance.memoryMb,
            launcherName = launcherName,
            launcherVersion = launcherVersion,
            extraJvmArgs = request.instance.extraJvmArgs + request.jvmArgs,
            extraGameArgs = request.gameArgs,
        )
        val classpath = buildList {
            addAll(LaunchArguments.classpathLibraries(version, placeholder))
            (resolvedJar ?: clientJar(version, layout))?.let { add(it) }
        }
        return placeholder.copy(classpath = classpath)
    }

    /**
     * 客户端 jar：先看 json 里的 downloads.client.path，再退回 versions/<id>/<id>.jar。
     *
     * Forge 的继承型 json 自己不写 client，需要用父版本的 id 去找 —— 这里按「继承链最底层的 id」兜底。
     */
    private fun clientJar(version: VersionJson, layout: GameLayout): File? {
        val declared = version.downloads?.client?.path
        if (!declared.isNullOrBlank()) {
            val file = File(layout.librariesDir, declared)
            if (file.isFile) return file
        }
        // 合并后 id 是子版本，client jar 却常常在父版本目录下，逐个试。
        val candidates = layout.gameDir.resolve("versions").listFiles()
            ?.filter { it.isDirectory }
            ?.map { File(it, it.name + ".jar") }
            ?.filter { it.isFile }
            ?.sortedBy { it.path }
            .orEmpty()
        return candidates.firstOrNull { it.parentFile?.name == version.id }
            ?: candidates.firstOrNull()
    }

    private fun extractNatives(
        version: VersionJson,
        context: LaunchContext,
        log: (String) -> Unit,
    ) {
        val archives = LaunchArguments.nativeArchives(version, context)
        if (archives.isEmpty()) return
        val result = NativesExtractor.extract(archives, context.nativesDir)
        result.warnings.forEach(log)
    }

    /** 一次启动用到的目录集合。 */
    private data class GameLayout(
        val versionDir: File,
        val gameDir: File,
        val librariesDir: File,
        val assetsDir: File,
    )
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
