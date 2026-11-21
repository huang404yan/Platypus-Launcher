package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.install.ServerInstaller
import io.github.huang404yan.platypus.core.install.ServerPorts
import io.github.huang404yan.platypus.core.server.RconClient
import io.github.huang404yan.platypus.core.server.ServerProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 一个正在跑（或刚跑过）的服务端。
 *
 * 与 [ServerRunner]（下载安装）分开：那个是一次性的下载任务，这个是**长驻进程**，
 * 要能发命令、能停、退出后还要留住输出。两件事的生命周期完全不同。
 */
internal data class ServerConsoleState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val dir: String = "",
    val lines: List<String> = emptyList(),
    val exitCode: Int? = null,
    val notice: String = "",
    /**
     * 命令走哪条通道。空串 = 还没定（刚起、正在试 RCON）。
     *
     * 这不是给日志看的，是给用户看「我发的命令到底送到哪去了」：
     * 走标准输入时只有**我们自己起的那个进程**收得到，用户用 start.bat 另开的服发命令是没反应的。
     */
    val channel: String = "",
)

/**
 * 一条命令发出去之后，它到底走到哪了。
 *
 * 为什么要把这件事返回给调用方、而不是只写进控制台日志：白名单与管理员那两个按钮
 * 需要按结果说不同的话 —— 走 RCON 是「已经生效」，走标准输入是「发出去了，看控制台回执」，
 * 服务端没在跑就得说「只改了文件，下次开服才生效」。日志里那句 `[本地]` 用户看不明白，
 * 而这些差别恰好是他唯一能判断「我现在能不能进服」的依据。
 */
internal sealed interface CommandSend {
    /** 走到 RCON，拿到回执（回执可能为空字符串）。 */
    data class Rcon(val reply: String) : CommandSend

    /** 走到我们起的那个进程的标准输入。没有回执可等 —— 原版服务端不发确认。 */
    data object Stdin : CommandSend

    /** 服务端没在跑（或者不是我们起的），命令没发出去。 */
    data object NotRunning : CommandSend

    /** 写出去了但失败了，例如进程刚退出、输出流已关闭。 */
    data class Failed(val reason: String) : CommandSend
}

/**
 * 进程级的服务端运行时。
 *
 * 为什么是 object 而不是页面里的 remember：服务端一跑就是几小时，用户切到实例页
 * 再切回来不能把进程弄丢，也不能把已经滚过去的日志清空。
 *
 * **不自己拼 java 命令，直接执行目录里的开服脚本**：
 * 原版 / Paper / Fabric 的 `start.bat` 是我们生成的，内存参数用户可能改过；
 * Forge / NeoForge 的 `run.bat` 里带着安装器生成的一长串模块参数，自己拼只会拼错。
 * 执行脚本还顺带解决了「用户双击能开、启动器开不了」的口径不一致。
 */
internal object ServerRuntime {

    /** 日志只留最后这么多行：服务端开一天能刷几十万行，全留会把内存吃干。 */
    private const val MAX_LINES = 600

    private val _state = MutableStateFlow(ServerConsoleState())
    val state: StateFlow<ServerConsoleState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null

    /** 两条命令通道的名字，state.channel 用它。 */
    private const val RCON_CHANNEL = "RCON"
    private const val STDIN_CHANNEL = "标准输入"

    /** 等服务端把 RCON 端口监听起来，最长等这么久。世界加载慢的服要十几秒。 */
    private const val RCON_ATTACH_TIMEOUT_MS = 25_000L
    private const val RCON_RETRY_MILLIS = 700L

    /** 连上之后的 RCON 连接；为 null 表示走标准输入。 */
    @Volatile
    private var rcon: RconClient? = null

    /**
     * 需要一直留在屏幕上的提示（目前只有端口绑不上这一种）。
     *
     * [append] 每来一行日志都会把 notice 清空 —— 那是给「起不来：xxx」这类一次性消息用的。
     * 但端口冲突的说明是在日志**中间**认出来的：设完它，服务端还会再刷几行然后退出，
     * 按老逻辑这几行会立刻把说明擦掉，用户就只剩一屏看不懂的英文堆栈。
     */
    private var pinned: String = ""

    /** 目录里能开服的那个脚本。Windows 优先 .bat，其它平台 .sh。 */
    fun scriptOf(dir: File): File? {
        val names = if (isWindows()) {
            listOf("start.bat", "run.bat", "start.sh", "run.sh")
        } else {
            listOf("start.sh", "run.sh", "start.bat", "run.bat")
        }
        return names.map { File(dir, it) }.firstOrNull { it.isFile }
    }

    fun start(dirText: String) {
        val current = _state.value
        if (current.running || current.starting) return
        val dir = File(dirText.trim())
        if (!dir.isDirectory) {
            notice("目录不存在：" + dirText)
            return
        }
        val script = scriptOf(dir) ?: run {
            notice("这个目录里没有开服脚本（start.bat / run.bat）。先在上面装一次服务端，或者把脚本放进来。")
            return
        }
        // EULA 没同意时服务端会直接退出并打一行英文，用户看到的就是「闪一下没了」。
        // 在这里先挡住，说清怎么处理，比让他读那行英文强。
        val eula = File(dir, "eula.txt")
        val agreed = runCatching { eula.isFile && ServerInstaller.eulaAgreed(eula.readText(Charsets.UTF_8)) }
            .getOrDefault(false)
        if (!agreed) {
            notice("还没同意 EULA：目录里没有 eula=true。点上面的「安装」会自动写；手动开服要先看过 https://aka.ms/MinecraftEULA 再改 eula.txt。")
            return
        }

        pinned = ""
        _state.value = ServerConsoleState(
            starting = true,
            dir = dir.absolutePath,
            lines = listOf("[启动] " + script.name),
        )
        // 端口预检放后台：查占用者要跑一遍 netstat，最长等 1.5 秒，压在界面线程上会卡一下。
        // 先亮「正在启动」，用户点完立刻有反馈，检查完再决定是真起还是拦住。
        scope.launch { startProcess(dir, script) }
    }

    /**
     * 预检端口，通过了才真的起进程。
     *
     * 端口被占时不放行，让服务端自己去撞：那样界面上只会看到日志刷两行、状态变回
     * 「已停止」，真正的原因（那句 FAILED TO BIND TO PORT）夹在一屏英文中间，很容易漏掉。
     */
    private fun startProcess(dir: File, script: File) {
        val port = ServerPorts.portOf(dir)
        if (!ServerPorts.isFree(port)) {
            _state.update { it.copy(starting = false, notice = ServerPorts.occupiedHint(dir, port)) }
            return
        }
        val command = if (isWindows()) {
            listOf("cmd.exe", "/c", script.name)
        } else {
            listOf("/bin/sh", script.name)
        }
        val started = runCatching {
            ProcessBuilder(command)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        }
        val p = started.getOrElse { error ->
            _state.update {
                it.copy(
                    starting = false,
                    notice = "起不来：" + (error.message ?: error::class.simpleName.orEmpty()),
                )
            }
            return
        }
        process = p
        _state.update { it.copy(starting = false, running = true) }
        scope.launch { pump(p) }
        scope.launch { watch(p) }
        scope.launch { attachRcon(dir) }
    }

    /**
     * 试着接管一条 RCON 通道。
     *
     * 为什么值得多这一步：走进程的标准输入时，命令只有**我们自己起的那个进程**收得到。
     * 用户双击 start.bat 起的、或者用别的启动器起的服，我们手上没有它的 stdin；
     * RCON 是 TCP，只要服务端开了就能连，跟谁起的无关。
     *
     * 拿不到不算失败：绝大多数服没开 RCON（原版默认是关的），那就老老实实走标准输入，
     * 界面上把通道写清楚，用户至少知道「为什么我这台机器能发命令、那台不行」。
     */
    private suspend fun attachRcon(dir: File) {
        val settings = runCatching { RconClient.settingsOf(dir) }.getOrNull() ?: return
        if (!settings.usable) {
            _state.update { it.copy(channel = STDIN_CHANNEL) }
            return
        }
        // 服务端要先把世界加载完才监听 RCON 端口，刚起那几秒连不上是正常的，重试到超时。
        val deadline = System.currentTimeMillis() + RCON_ATTACH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val alive = process?.isAlive == true
            if (!alive) return
            val client = runCatching {
                withContext(Dispatchers.IO) {
                    RconClient(port = settings.port, password = settings.password).connect()
                }
            }.getOrNull()
            if (client != null) {
                rcon = client
                _state.update { it.copy(channel = RCON_CHANNEL) }
                append("[本地] 已接上 RCON（127.0.0.1:" + settings.port + "），之后的命令走它")
                return
            }
            kotlinx.coroutines.delay(RCON_RETRY_MILLIS)
        }
        // 到点了还没连上：可能是密码不对，也可能 RCON 就没真开起来。不打断开服，只说明一句。
        _state.update { it.copy(channel = STDIN_CHANNEL) }
        append("[本地] RCON 连不上（127.0.0.1:" + settings.port + "），命令改走标准输入")
    }

    /** 读输出。脚本结尾那句「按任意键关闭窗口」是判断 java 已退出的信号。 */
    private suspend fun pump(p: Process) {
        runCatching {
            // start.bat 里有 chcp 65001，Forge 的 run.bat 通常也有；按 UTF-8 读。
            // 万一某台机器上仍是 GBK，中文会显示成替换字符 —— 英文字段照常可读。
            p.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line)
                    // 我们生成的脚本在 java 退出后会 echo 一句再 pause。不补这个回车，
                    // cmd 会一直停在 pause，进程永远不结束，「运行中」就下不来。
                    if (line.contains("按任意键关闭窗口")) {
                        runCatching {
                            p.outputStream.write("\n".toByteArray())
                            p.outputStream.flush()
                        }
                    }
                }
            }
        }
    }

    private suspend fun watch(p: Process) {
        val ok = runCatching { p.waitFor(24, TimeUnit.HOURS) }.getOrDefault(false)
        if (!ok) return
        val code = runCatching { p.exitValue() }.getOrDefault(-1)
        append("[退出] 退出码 " + code)
        // 连接必须主动关：服务端没了之后那条 socket 只会一直挂着，下次开服还指着这个死连接发命令。
        closeRcon()
        _state.update { it.copy(running = false, exitCode = code, channel = "") }
        process = null
    }

    fun send(text: String): CommandSend {
        val command = text.trim()
        if (command.isEmpty()) return CommandSend.Failed("命令是空的，没发出去。")
        append("[本地] " + command)
        val channel = rcon
        if (channel != null) {
            // RCON 的回执就是命令的输出（不经过 stdout）。原样打出来，
            // 否则用户会发现「发了 list 但屏幕上什么都没有」。
            val reply = runCatching { withContextBlocking { channel.command(command) } }
            val got = reply.getOrNull()
            if (got != null) {
                if (got.isNotBlank()) {
                    got.lineSequence().forEach { append("[RCON] " + it) }
                }
                return CommandSend.Rcon(got)
            }
            val error = reply.exceptionOrNull()
            append("[RCON] 发不出去：" + (error?.message ?: error?.let { it::class.simpleName }.orEmpty()))
            // 连接坏了就退回去走标准输入，别让后面的命令全砸在这一条死连接上。
            // 这条命令本身也顺着往下再走一遍：RCON 断掉多半是服务端重开过，
            // 标准输入那条路还活着 —— 命令不该因为一条死连接就凭空消失。
            closeRcon()
            _state.update { it.copy(channel = STDIN_CHANNEL) }
        }
        val p = process ?: return CommandSend.NotRunning
        try {
            p.outputStream.write((command + "\n").toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
        } catch (e: Exception) {
            val message = e.message ?: "输出流已关闭"
            append("[本地] 发不出去：" + message)
            return CommandSend.Failed(message)
        }
        return CommandSend.Stdin
    }

    /** 在 IO 线程上同步跑一段。RCON 是阻塞 IO，不能压在界面线程上。 */
    private fun <T> withContextBlocking(block: () -> T): T =
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }

    private fun closeRcon() {
        val client = rcon
        rcon = null
        runCatching { client?.close() }
    }

    /**
     * 先请服务端自己关（Minecraft 认 `stop`），15 秒还不走再强杀整棵进程树。
     *
     * 强杀必须连子孙一起：`cmd /c start.bat` 里的 java 是 cmd 的子进程，
     * 只杀 cmd 会留下一个还在写存档的孤儿 java。
     */
    fun stop() {
        val p = process ?: return
        append("[本地] stop")
        runCatching {
            p.outputStream.write("stop\n".toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
        }
        scope.launch {
            val exited = runCatching { p.waitFor(15, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!exited) {
                append("[本地] 15 秒没有退出，强制结束")
                runCatching { p.descendants().forEach { it.destroyForcibly() } }
                runCatching { p.destroyForcibly() }
            }
        }
    }

    /** 是否还有活的进程（切页回来时用来校正状态）。 */
    fun isProcessAlive(): Boolean = process?.isAlive == true

    private fun notice(text: String) {
        // 一次性消息优先：清掉粘住的那条，否则下一次 append 会把它又贴回屏幕上。
        pinned = ""
        _state.update { it.copy(notice = text) }
    }

    private fun append(line: String) {
        val clean = line.trimEnd()
        if (pinned.isEmpty() && ServerPorts.looksLikeBindFailure(clean)) {
            // 预检能挡住绝大部分端口冲突，但挡不住「预检读到的那份 server.properties 和
            // 服务端实际读的不是同一份」：用户刚手改过、或者从别的工作目录起。这时只能事后认领。
            val dir = _state.value.dir.takeIf { it.isNotBlank() }?.let(::File)
            if (dir != null && dir.isDirectory) {
                pinned = ServerPorts.bindFailureHint(dir, ServerPorts.portOf(dir))
            }
        }
        _state.update { s ->
            val next = if (s.lines.size >= MAX_LINES) {
                s.lines.drop(s.lines.size - MAX_LINES + 1) + clean
            } else {
                s.lines + clean
            }
            s.copy(lines = next, notice = pinned)
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")
}

/**
 * 控制台卡片：日志、命令输入、停止。
 *
 * 只在有活动会话（跑过或正在跑）时由页面渲染，不占常驻空间。
 */
@Composable
internal fun ServerConsoleCard(
    state: ServerConsoleState,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    // 新行来了就滚到底：盯着控制台的人要的是最新一行，不是最旧一行。
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.size - 1)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    when {
                        state.starting -> "服务端控制台 · 正在启动"
                        state.running -> "服务端控制台 · 运行中"
                        else -> "服务端控制台 · 已停止"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                state.exitCode?.let { code ->
                    Text(
                        "退出码 " + code,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.running) {
                    Button(onClick = onStop) { Text("停止") }
                }
            }
            Text(
                state.dir,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.channel.isNotBlank()) {
                // 把通道写出来是有用的：走标准输入时命令只有**这个**进程收得到，
                // 用户拿同一个目录另开一个服、在控制台里发命令却没反应，就是因为这个。
                Text(
                    "命令通道：" + state.channel + (
                        if (state.channel == "标准输入") {
                            "（只有本启动器起的这个服务端收得到）"
                        } else {
                            "（服务端开着 RCON，和谁起的进程无关）"
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                if (state.lines.isEmpty()) {
                    Text(
                        "还没有输出。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(state.lines) { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            NoticeFade(state.notice) { shown ->
                Text(
                    shown,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = state.running,
                    placeholder = { Text("输入服务端命令，比如 list / say 你好 / op 玩家名") },
                )
                Button(
                    onClick = {
                        onSend(input)
                        input = ""
                    },
                    enabled = state.running && input.isNotBlank(),
                ) { Text("发送") }
            }
            Text(
                "常用：list 看在线玩家 · say 广播 · op <玩家> 给管理员 · stop 关服（与上面的「停止」等价）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
