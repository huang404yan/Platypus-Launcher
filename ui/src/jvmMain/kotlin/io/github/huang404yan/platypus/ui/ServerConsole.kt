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
)

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

        _state.value = ServerConsoleState(
            starting = true,
            dir = dir.absolutePath,
            lines = listOf("[启动] " + script.name),
        )
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
        _state.update { it.copy(running = false, exitCode = code) }
        process = null
    }

    fun send(text: String) {
        val p = process ?: return
        val command = text.trim()
        if (command.isEmpty()) return
        append("[本地] " + command)
        runCatching {
            p.outputStream.write((command + "\n").toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
        }.onFailure {
            append("[本地] 发不出去：" + (it.message ?: "输出流已关闭"))
        }
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
        _state.update { it.copy(notice = text) }
    }

    private fun append(line: String) {
        val clean = line.trimEnd()
        _state.update { s ->
            val next = if (s.lines.size >= MAX_LINES) {
                s.lines.drop(s.lines.size - MAX_LINES + 1) + clean
            } else {
                s.lines + clean
            }
            s.copy(lines = next, notice = "")
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
            if (state.notice.isNotBlank()) {
                Text(
                    state.notice,
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
