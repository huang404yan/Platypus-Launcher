package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.auth.OfflineProfile
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.ScanResult
import io.github.huang404yan.platypus.core.launch.GameHandle
import io.github.huang404yan.platypus.core.launch.LaunchDiagnosis
import io.github.huang404yan.platypus.core.launch.LaunchEvent
import io.github.huang404yan.platypus.core.launch.LaunchRequest
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.runtime.findExactMajor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 启动页：选实例 → 选 Java → 起进程 → 看日志。
 *
 * 三个后端能力都从 [LauncherBackend] 注入（core 里的接口），这个文件不 new 任何实现，
 * 也不碰平台 API，方便单独替换成假的实现做预览。
 *
 * 线程模型：GameRuntime 的 onEvent 来自 IO 线程，这里直接写 Compose 快照状态
 * （快照状态本身线程安全），读日志的循环留在后端，界面不做轮询。
 */
@Composable
fun LaunchPage(backend: LauncherBackend?, initialGameRoot: String?) {
    if (backend == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("启动", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "后端未装配：启动页需要桌面端注入 Java 探测与游戏运行实现。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }

    val scope = rememberCoroutineScope()

    var rootText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    var scanned by remember { mutableStateOf<ScanResult?>(null) }
    var selected by remember { mutableStateOf<Instance?>(null) }

    var javaPhase by remember { mutableStateOf<JavaPhase>(JavaPhase.Idle) }
    var selectedJava by remember { mutableStateOf<JavaRuntime?>(null) }

    var username by remember { mutableStateOf("Dev") }
    var phase by remember { mutableStateOf<LaunchPhase>(LaunchPhase.Idle) }
    var stageText by remember { mutableStateOf("") }

    val logs = remember { mutableStateListOf<String>() }
    val buffer = remember { LogBuffer() }
    var handle by remember { mutableStateOf<GameHandle?>(null) }
    var menuReached by remember { mutableStateOf(false) }

    fun rescan() {
        val text = rootText.trim()
        val result = if (text.isEmpty()) null else InstanceScanner.scan(File(text))
        scanned = result
        if (selected == null || selected?.directory !in result?.instances.orEmpty().map { it.directory }) {
            selected = result?.instances?.firstOrNull()
        }
    }

    fun detectJava() {
        if (javaPhase is JavaPhase.Detecting) return
        javaPhase = JavaPhase.Detecting
        scope.launch {
            javaPhase = try {
                JavaPhase.Done(backend.javaRuntimes.detect())
            } catch (t: Throwable) {
                JavaPhase.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun launchGame() {
        val instance = selected ?: return
        val java = selectedJava ?: return
        logs.clear()
        buffer.clear()
        menuReached = false
        stageText = ""
        handle = null
        phase = LaunchPhase.Starting
        val request = LaunchRequest(
            instance = instance,
            java = java,
            session = OfflineProfile.session(username.trim().ifBlank { "Dev" }),
        )
        scope.launch {
            try {
                val started = backend.gameRuntime.launch(request) { event ->
                    when (event) {
                        is LaunchEvent.Stage -> stageText = event.name
                        is LaunchEvent.Started -> {
                            stageText = "已启动"
                            phase = LaunchPhase.Running(event.pid)
                        }
                        is LaunchEvent.LogLine -> {
                            buffer.add(event.text)
                            logs.add(event.text)
                            if (logs.size > MAX_VISIBLE_LINES) logs.removeAt(0)
                            if (!menuReached && LaunchDiagnosis.reachedMenu(listOf(event.text))) {
                                menuReached = true
                            }
                        }
                        is LaunchEvent.Exited -> {
                            stageText = ""
                            phase = LaunchPhase.Exited(
                                code = event.code,
                                diagnosis = event.diagnosis,
                                reachedMenu = LaunchDiagnosis.reachedMenu(buffer.tail(1_000)),
                            )
                        }
                    }
                }
                handle = started
            } catch (t: Throwable) {
                phase = LaunchPhase.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun stopGame() {
        val current = handle ?: return
        stageText = "正在停止"
        scope.launch {
            runCatching { current.stop() }
            handle = null
            if (phase !is LaunchPhase.Exited) phase = LaunchPhase.Idle
        }
    }

    LaunchedEffect(Unit) {
        if (rootText.isNotBlank()) rescan()
        detectJava()
    }

    // 选中实例后按游戏版本要求挑一个主版本正好匹配的 Java；没有就不选，由界面提示缺什么。
    LaunchedEffect(selected?.directory, javaPhase) {
        val instance = selected ?: return@LaunchedEffect
        val list = (javaPhase as? JavaPhase.Done)?.runtimes ?: return@LaunchedEffect
        val required = GameJavaRules.requiredMajor(instance.gameVersion)
        val exact = list.findExactMajor(required)
        selectedJava = exact ?: selectedJava?.takeIf { it.majorVersion == required }
    }

    val busy = phase is LaunchPhase.Starting || phase is LaunchPhase.Running
    val requiredMajor = selected?.let { GameJavaRules.requiredMajor(it.gameVersion) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("启动", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "选一个实例、选一个 Java，点「启动」。离线模式，不登录任何账号。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ① 实例
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = rootText,
                onValueChange = { rootText = it },
                modifier = Modifier.weight(1f),
                label = { Text("游戏目录（.minecraft）") },
                singleLine = true,
                enabled = !busy,
            )
            Button(onClick = { rescan() }, enabled = !busy) { Text("扫描") }
        }

        val result = scanned
        when {
            result == null -> SectionHint("还没有扫描。")
            !result.isGameDirectory -> SectionHint("这个目录看起来不是游戏目录。")
            result.instances.isEmpty() -> SectionHint("没有识别到版本。")
            else -> {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 132.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(result.instances) { instance ->
                        InstanceRow(
                            instance = instance,
                            selected = instance.directory == selected?.directory,
                            enabled = !busy,
                            onClick = { selected = instance },
                        )
                    }
                }
            }
        }

        // ② Java
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Java 运行时", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (requiredMajor != null) {
                Text(
                    "游戏需要 Java " + requiredMajor,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { detectJava() }, enabled = javaPhase !is JavaPhase.Detecting && !busy) {
                Text(if (javaPhase is JavaPhase.Detecting) "探测中…" else "重新探测")
            }
        }

        when (val current = javaPhase) {
            is JavaPhase.Idle -> SectionHint("还没有探测。")
            is JavaPhase.Detecting -> SectionHint("正在探测本机 Java…")
            is JavaPhase.Failed -> SectionHint("探测失败：" + current.message, error = true)
            is JavaPhase.Done -> {
                if (current.runtimes.isEmpty()) {
                    SectionHint("没有找到任何 Java。装一个 JDK 再点「重新探测」。", error = true)
                } else {
                    if (requiredMajor != null && current.runtimes.findExactMajor(requiredMajor) == null) {
                        SectionHint(
                            "本机没有 Java " + requiredMajor + "。直接启动大概率会因为版本不匹配失败。",
                            error = true,
                        )
                    }
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 132.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(current.runtimes) { runtime ->
                            JavaRow(
                                runtime = runtime,
                                selected = runtime.path == selectedJava?.path,
                                recommended = runtime.majorVersion == requiredMajor,
                                enabled = !busy,
                                onClick = { selectedJava = runtime },
                            )
                        }
                    }
                }
            }
        }

        // ③ 启动
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.weight(1f),
                label = { Text("离线用户名") },
                singleLine = true,
                enabled = !busy,
            )
            Button(onClick = { launchGame() }, enabled = !busy && selected != null && selectedJava != null) {
                Text("启动")
            }
            OutlinedButton(onClick = { stopGame() }, enabled = phase is LaunchPhase.Running) { Text("停止") }
        }

        StatusLine(phase = phase, stageText = stageText, menuReached = menuReached)

        // ④ 日志
        val listState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) listState.scrollToItem(logs.size - 1)
        }
        Surface(
            Modifier.fillMaxWidth().weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
        ) {
            if (logs.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text(
                        "启动后游戏的 stdout/stderr 会实时出现在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(10.dp), state = listState) {
                    items(logs) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_VISIBLE_LINES = 500

/** Java 探测的三种状态；探测失败不能当成「没有 Java」。 */
private sealed interface JavaPhase {
    data object Idle : JavaPhase
    data object Detecting : JavaPhase
    data class Done(val runtimes: List<JavaRuntime>) : JavaPhase
    data class Failed(val message: String) : JavaPhase
}

/** 一次启动的生命周期。 */
private sealed interface LaunchPhase {
    data object Idle : LaunchPhase
    data object Starting : LaunchPhase
    data class Running(val pid: Long) : LaunchPhase
    data class Exited(val code: Int, val diagnosis: String?, val reachedMenu: Boolean) : LaunchPhase
    data class Failed(val message: String) : LaunchPhase
}

/** 归因用的日志尾部；界面只显示最近若干行，判定「是否进过主菜单」要更长的历史。 */
private class LogBuffer {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun tail(count: Int): List<String> = lines.toList().takeLast(count)

    @Synchronized
    fun clear() = lines.clear()

    private companion object {
        const val CAPACITY = 5_000
    }
}

@Composable
private fun StatusLine(phase: LaunchPhase, stageText: String, menuReached: Boolean) {
    val (text, color) = when (phase) {
        is LaunchPhase.Idle -> "未启动" to MaterialTheme.colorScheme.onSurfaceVariant
        is LaunchPhase.Starting -> "启动中：" + stageText to MaterialTheme.colorScheme.onSurfaceVariant
        is LaunchPhase.Running -> "运行中 · PID " + phase.pid to MaterialTheme.colorScheme.primary
        is LaunchPhase.Exited -> buildString {
            append("已退出，退出码 ").append(phase.code)
            if (phase.reachedMenu || menuReached) append(" · 日志里出现过主菜单信号") else append(" · 未见主菜单信号")
            phase.diagnosis?.let { append(" · ").append(it) }
        } to if (phase.code == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
        is LaunchPhase.Failed -> "启动失败：" + phase.message to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun InstanceRow(instance: Instance, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(instance.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "游戏 " + instance.gameVersion + " · " + instance.loader.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun JavaRow(runtime: JavaRuntime, selected: Boolean, recommended: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Java " + runtime.majorVersion + (runtime.vendor?.let { " · " + it } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    runtime.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (recommended) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        "推荐",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
