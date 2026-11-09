package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.version.VersionManifest
import io.github.huang404yan.platypus.ui.download.InstallOutcome
import io.github.huang404yan.platypus.ui.download.InstallStep
import io.github.huang404yan.platypus.ui.download.InstallStepException
import io.github.huang404yan.platypus.ui.download.OneClickInstaller
import io.github.huang404yan.platypus.ui.download.StepProgress
import io.github.huang404yan.platypus.ui.download.VanillaInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 单个安装步骤在界面上的状态。 */
private sealed interface StepState {
    data object Pending : StepState
    data object Running : StepState
    data object Succeeded : StepState
    data class Failed(val message: String) : StepState
}

/** 一键装的整体阶段。 */
private sealed interface InstallPhase {
    data object Idle : InstallPhase
    data object Running : InstallPhase
    data class Finished(val outcome: InstallOutcome) : InstallPhase
    data class Failed(val step: InstallStep, val message: String) : InstallPhase
}

/** 界面上依次展示的步骤（不含 IDLE 与 DONE）。 */
private val VISIBLE_STEPS = listOf(
    InstallStep.PLANNING,
    InstallStep.DOWNLOADING,
    InstallStep.JAVA_CHECK,
    InstallStep.JAVA_INSTALL,
    InstallStep.REGISTERING,
)

/**
 * 下载页：选一个原版版本，一次完成「下载 → 补 Java → 建实例」。
 *
 * 解析、下载、解压都在 Dispatchers.IO 上跑，界面只读状态，不阻塞 UI 线程。
 */
@Composable
fun DownloadsPage(
    initialGameRoot: String?,
    backend: LauncherBackend? = null,
    onInstalled: (Instance) -> Unit = {},
) {
    var gameDirText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    // 设置里指定的默认内存；一键装按它登记新实例，用户装完还能在启动页单独改
    var defaultMemoryMb by remember { mutableStateOf(Instance.DEFAULT_MEMORY_MB) }
    var versionText by remember { mutableStateOf("") }
    var filterText by remember { mutableStateOf("") }

    var manifest by remember { mutableStateOf<VersionManifest?>(null) }
    var manifestError by remember { mutableStateOf<String?>(null) }
    var loadingManifest by remember { mutableStateOf(false) }

    var phase by remember { mutableStateOf<InstallPhase>(InstallPhase.Idle) }
    var currentStep by remember { mutableStateOf(InstallStep.IDLE) }
    var stepStates by remember { mutableStateOf<Map<InstallStep, StepState>>(emptyMap()) }
    var progress by remember { mutableStateOf<StepProgress?>(null) }
    var speedBytesPerSecond by remember { mutableStateOf(0L) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val meter = remember { SpeedMeter() }

    suspend fun refreshManifest() {
        loadingManifest = true
        manifestError = null
        withContext(Dispatchers.IO) {
            try {
                manifest = VanillaInstaller.loadManifest()
            } catch (e: Exception) {
                manifestError = e.message ?: (e::class.simpleName ?: "拉取版本列表失败")
            }
        }
        loadingManifest = false
    }

    fun startInstall() {
        val dirText = gameDirText.trim()
        if (dirText.isEmpty()) {
            message = "先填一个目标目录，比如 D:/mc-test。"
            return
        }
        val id = versionText.trim()
        if (id.isEmpty()) {
            message = "先选或手填一个版本号，比如 1.20.1。"
            return
        }
        if (backend == null) {
            message = "后端未装配：一键安装需要桌面端注入 Java 与实例存储实现。"
            return
        }
        val dir = File(dirText)
        job?.cancel()
        meter.reset()
        stepStates = emptyMap()
        progress = null
        speedBytesPerSecond = 0L
        elapsedMs = 0L
        currentStep = InstallStep.PLANNING
        phase = InstallPhase.Running
        message = "正在解析 " + id + " 的版本元数据…"
        val startedAt = System.currentTimeMillis()

        job = scope.launch {
            try {
                val outcome = OneClickInstaller.install(
                    gameDir = dir,
                    versionId = id,
                    javaRuntimes = backend.javaRuntimes,
                    instances = backend.instances,
                    manifest = manifest,
                    memoryMb = defaultMemoryMb,
                ) { step ->
                    if (step.step != currentStep) {
                        val previous = currentStep
                        if (previous != InstallStep.IDLE && previous != InstallStep.DONE) {
                            stepStates = stepStates + (previous to StepState.Succeeded)
                        }
                        currentStep = step.step
                    }
                    if (step.step != InstallStep.IDLE && step.step != InstallStep.DONE) {
                        stepStates = stepStates + (step.step to StepState.Running)
                    }
                    progress = step
                    if (step.detail.isNotBlank()) message = step.detail
                    elapsedMs = System.currentTimeMillis() - startedAt
                    if (step.step == InstallStep.DOWNLOADING) {
                        val speed = meter.sample(step.done)
                        if (speed >= 0) speedBytesPerSecond = speed
                    }
                }
                elapsedMs = System.currentTimeMillis() - startedAt
                progress = null
                stepStates = VISIBLE_STEPS.associateWith { StepState.Succeeded }
                currentStep = InstallStep.DONE
                phase = InstallPhase.Finished(outcome)
                message = buildString {
                    append("已完成：")
                    append(outcome.versionId)
                    append("，Java ")
                    append(outcome.java.majorVersion)
                    append(if (outcome.javaInstalled) "（这次新下的）" else "（本机已有）")
                    append("，内存 ")
                    append(outcome.instance.memoryMb)
                    append(" MB（可在启动页改），用时 ")
                    append(formatDuration(outcome.elapsedMs))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InstallStepException) {
                stepStates = stepStates + (e.step to StepState.Failed(e.message ?: "失败"))
                currentStep = e.step
                phase = InstallPhase.Failed(e.step, e.message ?: "失败")
                message = "卡在「" + e.step.label + "」：" + (e.message ?: "未知错误")
            } catch (e: Exception) {
                val step = currentStep.takeIf { it != InstallStep.IDLE } ?: InstallStep.PLANNING
                stepStates = stepStates + (step to StepState.Failed(e.message ?: "失败"))
                phase = InstallPhase.Failed(step, e.message ?: (e::class.simpleName ?: "未知错误"))
                message = "卡在「" + step.label + "」：" + (e.message ?: (e::class.simpleName ?: "未知错误"))
            }
        }
    }

    fun cancelInstall() {
        job?.cancel()
        job = null
        phase = InstallPhase.Idle
        currentStep = InstallStep.IDLE
        speedBytesPerSecond = 0L
        message = "已取消。已下好的文件不会丢，再点一次会跳过校验通过的部分。"
    }

    LaunchedEffect(Unit) {
        // 设置里的默认游戏目录与默认内存先读，避免用户每次都要重填目录、装出来的实例内存是写死的
        val repository = backend?.settings
        if (repository != null) {
            val stored = withContext(Dispatchers.IO) { runCatching { repository.load() }.getOrNull() }
            if (stored != null) {
                if (initialGameRoot.isNullOrBlank() && stored.defaultGameRoot.isNotBlank()) {
                    gameDirText = stored.defaultGameRoot
                }
                defaultMemoryMb = stored.defaultMemoryMb
            }
        }
        if (manifest == null) refreshManifest()
    }

    val busy = phase is InstallPhase.Running
    val releases = manifest?.releases().orEmpty()
    val visible = if (filterText.isBlank()) {
        releases
    } else {
        releases.filter { it.id.contains(filterText.trim(), ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("下载", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "选一个版本点「一键安装」：下载原版文件、按需补 Java 运行时、登记成实例，完成后直接去启动。" +
                "文件按 sha1 校验，已存在的会跳过，可以中断后再来一次。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = gameDirText,
                onValueChange = { gameDirText = it },
                modifier = Modifier.weight(1f),
                label = { Text("目标目录（会当作 .minecraft 用）") },
                singleLine = true,
                enabled = !busy,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = versionText,
                onValueChange = { versionText = it },
                modifier = Modifier.weight(1f),
                label = { Text("版本号（可手填，如 1.20.1）") },
                singleLine = true,
                enabled = !busy,
            )
            Button(onClick = { startInstall() }, enabled = !busy) { Text("一键安装") }
            if (busy) {
                OutlinedButton(onClick = { cancelInstall() }) { Text("取消") }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                modifier = Modifier.weight(1f),
                label = { Text("在版本列表里过滤（只看正式版）") },
                singleLine = true,
            )
            Text(
                when {
                    loadingManifest -> "正在拉取版本列表…"
                    manifestError != null -> "版本列表拉取失败"
                    else -> releases.size.toString() + " 个正式版"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { scope.launch { refreshManifest() } }, enabled = !loadingManifest) {
                Text("刷新列表")
            }
        }

        manifestError?.let { DownloadHint(it + "（不影响手填版本号：会走 BMCLAPI 的版本接口兜底）", error = true) }

        if (visible.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 132.dp).padding(4.dp)) {
                    items(visible, key = { it.id }) { entry ->
                        val selected = entry.id == versionText.trim()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else Color.Transparent,
                                    MaterialTheme.shapes.small,
                                )
                                .clickable(enabled = !busy) { versionText = entry.id }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                entry.id,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                entry.releaseTime.take(10),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (message.isNotBlank()) {
            DownloadHint(message, error = phase is InstallPhase.Failed)
        }

        // 失败后给一个明确的下一动作：直接重来，已下好并通过 sha1 的文件会跳过。
        // 用户不用自己判断「再点一次一键安装是不是会从头下」。
        if (phase is InstallPhase.Failed && !busy) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = { startInstall() }) { Text("重试这一步") }
                Text(
                    "已下好的文件会跳过，不会从头再来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 分步进度：每一步一行，卡在哪一步一眼能看出来。
        if (currentStep != InstallStep.IDLE) {
            StepList(currentStep = currentStep, states = stepStates)
        }

        val snapshot = progress
        if (snapshot != null && snapshot.total > 0L) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProgressBar(snapshot.fraction)
                Text(
                    formatProgressDetail(snapshot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snapshot.step == InstallStep.DOWNLOADING) {
                    Text(
                        "速度 " + formatSpeed(speedBytesPerSecond) + " · 已用 " + formatDuration(elapsedMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val finished = phase as? InstallPhase.Finished
        if (finished != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "实例已就绪：" + finished.outcome.instance.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "游戏目录 " + File(finished.outcome.instance.directory).parentFile?.parentFile?.path.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Java：" + finished.outcome.java.path,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onInstalled(finished.outcome.instance) }) { Text("去启动") }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

/** 「步骤名 —— 状态」的一行；失败时把原因原样显示出来。 */
@Composable
private fun StepList(currentStep: InstallStep, states: Map<InstallStep, StepState>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        VISIBLE_STEPS.forEach { step ->
            val state = states[step] ?: StepState.Pending
            val (mark, color) = when (state) {
                is StepState.Pending -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
                is StepState.Running -> "…" to MaterialTheme.colorScheme.primary
                is StepState.Succeeded -> "✓" to MaterialTheme.colorScheme.primary
                is StepState.Failed -> "✗" to MaterialTheme.colorScheme.error
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    mark + " " + step.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (step == currentStep) FontWeight.SemiBold else FontWeight.Normal,
                    color = color,
                )
                if (state is StepState.Failed) {
                    Text(
                        state.message,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun formatProgressDetail(step: StepProgress): String = when (step.step) {
    InstallStep.DOWNLOADING -> step.detail + " · " + formatBytes(step.done) + " / " + formatBytes(step.total)
    InstallStep.JAVA_INSTALL -> "Java 运行时下载 " + (step.fraction * 100f).toInt() + "%"
    else -> step.detail
}

@Composable
private fun ProgressBar(fraction: Float) {
    val value = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
    ) {
        if (value > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(value)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
            )
        }
    }
}

@Composable
private fun DownloadHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 用相邻采样点的字节差算瞬时速度；间隔太短就沿用上一次的值。 */
private class SpeedMeter {
    private var lastBytes = Long.MIN_VALUE
    private var lastAt = 0L

    fun reset() {
        synchronized(this) {
            lastBytes = Long.MIN_VALUE
            lastAt = 0L
        }
    }

    /** 返回字节/秒；样本不足时返回 -1，调用方应保留原值。 */
    fun sample(totalBytes: Long): Long {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (lastBytes == Long.MIN_VALUE || lastAt == 0L) {
                lastBytes = totalBytes
                lastAt = now
                return -1L
            }
            val elapsed = now - lastAt
            if (elapsed < 300L) return -1L
            val delta = totalBytes - lastBytes
            lastBytes = totalBytes
            lastAt = now
            return if (delta <= 0L) 0L else delta * 1000L / elapsed
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.size - 1) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.ROOT, "%.1f ", value) + units[index]
}

private fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0L) "—" else formatBytes(bytesPerSecond) + "/s"

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) minutes.toString() + " 分 " + seconds + " 秒" else seconds.toString() + " 秒"
}
