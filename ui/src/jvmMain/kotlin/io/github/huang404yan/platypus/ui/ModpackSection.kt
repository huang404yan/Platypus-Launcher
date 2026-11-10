package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.install.ModpackImportException
import io.github.huang404yan.platypus.core.install.ModpackImportOutcome
import io.github.huang404yan.platypus.core.install.ModpackImporter
import io.github.huang404yan.platypus.core.install.ModpackPreview
import io.github.huang404yan.platypus.core.install.ModpackProgress
import io.github.huang404yan.platypus.core.install.ModpackStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** 整合包导入的整体阶段。 */
private sealed interface ImportPhase {
    data object Idle : ImportPhase
    data object Running : ImportPhase
    data class Finished(val outcome: ModpackImportOutcome) : ImportPhase
    data class Failed(val step: ModpackStep, val message: String) : ImportPhase
}

/**
 * 整合包导入区：选一个 .mrpack / MCBBS v2 包，一次装成能启动的实例。
 *
 * 为什么放在下载页而不是新开一个一级导航：导入整合包的用户做的事和「装一个版本」
 * 是同一件事（都会下载游戏本体、都要装加载器），只是入口多了一个 .zip 文件。
 * 单独开一页会让下载页与导入页各自维护一套进度与错误显示。
 *
 * 选完文件先 [ModpackImporter.inspect] 做一次只读预览 —— 用户能在真正下载几个 G
 * 之前看到「这是什么包、要装哪个版本、带什么加载器、多大」。
 */
@Composable
internal fun ModpackSection(
    enabled: Boolean,
    gameDir: String,
    defaultMemoryMb: Int,
    backend: LauncherBackend?,
    onImported: (Instance) -> Unit,
) {
    var archivePath by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ModpackPreview?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var loadingPreview by remember { mutableStateOf(false) }
    var allowOverwrite by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<ImportPhase>(ImportPhase.Idle) }
    var progress by remember { mutableStateOf<ModpackProgress?>(null) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val running = phase is ImportPhase.Running

    fun inspectArchive(file: File) {
        archivePath = file.absolutePath
        preview = null
        previewError = null
        phase = ImportPhase.Idle
        progress = null
        message = ""
        loadingPreview = true
        scope.launch {
            try {
                preview = ModpackImporter.inspect(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                previewError = describeImportError(e)
            } finally {
                // 取消时也要收掉，否则界面永远停在「正在读压缩包…」
                loadingPreview = false
            }
        }
    }

    fun startImport() {
        val file = preview?.archive ?: return
        val dirText = gameDir.trim()
        if (dirText.isEmpty()) {
            message = "先在上面填一个目标目录，比如 D:/mc-test。"
            return
        }
        if (backend == null) {
            message = "后端未装配：导入整合包需要桌面端注入 Java 与实例存储实现。"
            return
        }
        job?.cancel()
        progress = null
        message = ""
        phase = ImportPhase.Running
        job = scope.launch {
            try {
                val outcome = ModpackImporter.import(
                    archive = file,
                    gameDir = File(dirText),
                    javaRuntimes = backend.javaRuntimes,
                    instances = backend.instances,
                    memoryMb = defaultMemoryMb,
                    allowOverwriteUserData = allowOverwrite,
                ) { step ->
                    progress = step
                    if (step.detail.isNotBlank()) message = step.detail
                }
                progress = null
                phase = ImportPhase.Finished(outcome)
                message = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress = null
                phase = ImportPhase.Failed(stepOf(e), describeImportError(e))
            }
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "导入整合包",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "支持 Modrinth（.mrpack）与 MCBBS v2",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        val picked = pickArchive()
                        if (picked != null) inspectArchive(picked)
                    },
                    enabled = enabled && !running && !loadingPreview,
                ) { Text("选择文件…") }
            }

            if (archivePath.isBlank()) {
                Text(
                    "选一个 .mrpack 或 .zip：会先只读解析给你看一眼，再决定装不装。" +
                        "包里的模组会下到实例的 mods 目录，overrides 按层覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    archivePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (loadingPreview) {
                Text("正在读压缩包…", style = MaterialTheme.typography.bodySmall)
            }

            previewError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            preview?.let { p ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.manifest.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "格式 " + p.layout.kind.label + " · 游戏 " + p.manifest.summary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (p.fileCount == 0) {
                            "包内没有额外文件，只有 overrides"
                        } else {
                            p.fileCount.toString() + " 个文件要下载，共 " +
                                (if (p.totalDownloadBytes < 0L) "体积未知" else formatSize(p.totalDownloadBytes))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p.manifest.overrideDirs.isNotEmpty()) {
                        Text(
                            "覆盖层：" + p.manifest.overrideDirs.joinToString(" → "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    p.manifest.warnings.forEach {
                        Text(
                            "· " + it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = allowOverwrite,
                            onCheckedChange = { allowOverwrite = it },
                            enabled = !running,
                        )
                        Text(
                            "允许覆盖已有存档与设置（默认跳过）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { startImport() },
                    enabled = enabled && !running && preview != null,
                ) { Text("开始导入") }
                if (running) {
                    OutlinedButton(onClick = { job?.cancel() }) { Text("取消") }
                }
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (phase is ImportPhase.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            val snapshot = progress
            if (snapshot != null) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        snapshot.step.label + (if (snapshot.detail.isBlank()) "" else " · " + snapshot.detail),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (snapshot.total > 0L && snapshot.totalReliable) {
                        ImportProgressBar(snapshot.fraction)
                    }
                }
            }

            val finished = phase as? ImportPhase.Finished
            if (finished != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "导入完成：" + finished.outcome.instance.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "包内文件 " + finished.outcome.downloadedFiles + " 个（" +
                            formatSize(finished.outcome.downloadedBytes) + "），overrides " +
                            finished.outcome.overrideFiles + " 个，用时 " +
                            formatDurationMs(finished.outcome.elapsedMs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    finished.outcome.warnings.forEach {
                        Text(
                            "· " + it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onImported(finished.outcome.instance) }) { Text("去启动") }
                }
            }
        }
    }
}

/** 选文件对话框。Compose 桌面端没有内置文件选择器，直接用 AWT 的原生 FileDialog（Windows 上是系统的那个）。 */
private fun pickArchive(): File? {
    // owner 给 Compose 主窗口，对话框才会出现在窗口上方而不是屏幕角落里；
    // 找不到时退回 null，对话框仍会正常弹出，只是不带父窗口。
    val owner = java.awt.Window.getWindows()
        .filterIsInstance<androidx.compose.ui.awt.ComposeWindow>()
        .firstOrNull { it.isActive }
        ?: java.awt.Window.getWindows().filterIsInstance<androidx.compose.ui.awt.ComposeWindow>().firstOrNull()
    val dialog = java.awt.FileDialog(owner, "选择整合包（.mrpack / .zip）", java.awt.FileDialog.LOAD)
    // Windows 的原生对话框会忽略 FilenameFilter，这里只是给支持的平台加一层过滤。
    dialog.setFilenameFilter { _, name ->
        ModpackImporter.SUPPORTED_EXTENSIONS.any { name.endsWith("." + it, ignoreCase = true) }
    }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return File(dir, name)
}

private fun stepOf(error: Throwable): ModpackStep =
    (error as? ModpackImportException)?.step ?: ModpackStep.PARSING

private fun describeImportError(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: (error::class.simpleName ?: "未知错误")

@Composable
private fun ImportProgressBar(fraction: Float) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small),
    ) {
        Column(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
        ) {}
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 0L) return "未知"
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.size - 1) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}

private fun formatDurationMs(millis: Long): String {
    val seconds = millis / 1000L
    return when {
        seconds < 60L -> seconds.toString() + " 秒"
        seconds < 3600L -> (seconds / 60L).toString() + " 分 " + (seconds % 60L) + " 秒"
        else -> (seconds / 3600L).toString() + " 小时 " + ((seconds % 3600L) / 60L) + " 分"
    }
}
