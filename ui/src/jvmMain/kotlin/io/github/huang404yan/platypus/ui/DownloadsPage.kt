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
import io.github.huang404yan.platypus.core.download.DownloadFailure
import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadProgress
import io.github.huang404yan.platypus.core.version.VersionManifest
import io.github.huang404yan.platypus.ui.download.VanillaInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 下载流程所处的阶段，只用来决定界面显示什么。 */
private sealed interface InstallPhase {
    data object Idle : InstallPhase
    data object Planning : InstallPhase
    data object Downloading : InstallPhase
    data object Finished : InstallPhase
    data class Failed(val message: String) : InstallPhase
}

/**
 * 下载页：选一个原版版本，装进指定的 .minecraft 目录。
 *
 * 解析与下载都在 Dispatchers.IO 上跑，界面只读状态，不阻塞 UI 线程。
 */
@Composable
fun DownloadsPage(initialGameRoot: String?) {
    var gameDirText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    var versionText by remember { mutableStateOf("") }
    var filterText by remember { mutableStateOf("") }

    var manifest by remember { mutableStateOf<VersionManifest?>(null) }
    var manifestError by remember { mutableStateOf<String?>(null) }
    var loadingManifest by remember { mutableStateOf(false) }

    var phase by remember { mutableStateOf<InstallPhase>(InstallPhase.Idle) }
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }
    var speedBytesPerSecond by remember { mutableStateOf(0L) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var failures by remember { mutableStateOf<List<DownloadFailure>>(emptyList()) }
    var planInfo by remember { mutableStateOf<VanillaInstaller.InstallPlan?>(null) }
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

    fun startFresh() {
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
        val dir = File(dirText)
        job?.cancel()
        meter.reset()
        progress = null
        failures = emptyList()
        planInfo = null
        speedBytesPerSecond = 0L
        elapsedMs = 0L
        phase = InstallPhase.Planning
        message = "正在解析 " + id + " 的版本元数据…"
        val startedAt = System.currentTimeMillis()

        job = scope.launch(Dispatchers.IO) {
            try {
                val plan = VanillaInstaller.plan(dir, id, manifest)
                planInfo = plan
                message = "版本 " + plan.versionId + "：共 " + plan.totalFiles +
                    " 个文件，约 " + formatBytes(plan.totalBytes) + "，开始下载…"
                phase = InstallPhase.Downloading
                val report = VanillaInstaller.download(plan.items) { snapshot ->
                    progress = snapshot
                    val speed = meter.sample(snapshot.completedBytes)
                    if (speed >= 0) speedBytesPerSecond = speed
                    elapsedMs = System.currentTimeMillis() - startedAt
                }
                elapsedMs = System.currentTimeMillis() - startedAt
                speedBytesPerSecond = 0L
                failures = report.failures
                phase = InstallPhase.Finished
                message = if (report.ok) {
                    "下载完成：" + report.succeeded + " 个文件，用时 " + formatDuration(elapsedMs)
                } else {
                    "下载结束：" + report.succeeded + " 个成功，" + report.failures.size +
                        " 个失败，用时 " + formatDuration(elapsedMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = InstallPhase.Failed(e.message ?: (e::class.simpleName ?: "下载失败"))
                message = "下载失败：" + (e.message ?: (e::class.simpleName ?: "未知错误"))
            }
        }
    }

    fun retryFailures() {
        val retryItems: List<DownloadItem> = failures.map { it.item }
        if (retryItems.isEmpty()) return
        job?.cancel()
        meter.reset()
        progress = null
        speedBytesPerSecond = 0L
        elapsedMs = 0L
        phase = InstallPhase.Downloading
        message = "正在重试 " + retryItems.size + " 个失败项…"
        val startedAt = System.currentTimeMillis()

        job = scope.launch(Dispatchers.IO) {
            try {
                val report = VanillaInstaller.download(retryItems) { snapshot ->
                    progress = snapshot
                    val speed = meter.sample(snapshot.completedBytes)
                    if (speed >= 0) speedBytesPerSecond = speed
                    elapsedMs = System.currentTimeMillis() - startedAt
                }
                elapsedMs = System.currentTimeMillis() - startedAt
                speedBytesPerSecond = 0L
                failures = report.failures
                phase = InstallPhase.Finished
                message = if (report.ok) {
                    "重试全部成功，用时 " + formatDuration(elapsedMs)
                } else {
                    "重试后仍有 " + report.failures.size + " 个失败"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = InstallPhase.Failed(e.message ?: (e::class.simpleName ?: "重试失败"))
            }
        }
    }

    LaunchedEffect(Unit) {
        if (manifest == null) refreshManifest()
    }

    val busy = phase == InstallPhase.Planning || phase == InstallPhase.Downloading
    val releases = manifest?.releases().orEmpty()
    val visible = if (filterText.isBlank()) {
        releases
    } else {
        releases.filter { it.id.contains(filterText.trim(), ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("下载", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "从 Mojang 拉版本清单，把原版客户端、库文件和资源下载到指定目录。文件按 sha1 校验，" +
                "已存在的文件会跳过，可以中断后再来一次。",
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
            Button(onClick = { startFresh() }, enabled = !busy) { Text("开始下载") }
            if (busy) {
                OutlinedButton(onClick = {
                    job?.cancel()
                    job = null
                    phase = InstallPhase.Idle
                    speedBytesPerSecond = 0L
                    message = "已取消。已下好的文件不会丢，再点一次会跳过校验通过的部分。"
                }) { Text("取消") }
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
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 170.dp).padding(4.dp)) {
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

        val snapshot = progress
        if (snapshot != null) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProgressBar(snapshot.fraction)
                Text(
                    "已完成 " + snapshot.completedFiles + " / " + snapshot.totalFiles + " 个文件 · " +
                        formatBytes(snapshot.completedBytes) + " / " + formatBytes(snapshot.totalBytes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "速度 " + formatSpeed(speedBytesPerSecond) + " · 已用 " + formatDuration(elapsedMs) +
                        (planInfo?.let { " · 版本 " + it.versionId } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        planInfo?.let { plan ->
            Text(
                "版本 json：" + plan.versionJsonPath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (failures.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "失败 " + failures.size + " 项",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { retryFailures() }, enabled = !busy) { Text("重试失败项") }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(failures, key = { it.item.target }) { failure ->
                    FailureCard(failure)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
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
private fun FailureCard(failure: DownloadFailure) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(
                File(failure.item.target).path,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(failure.reason, style = MaterialTheme.typography.bodySmall)
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
