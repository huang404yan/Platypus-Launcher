package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import io.github.huang404yan.platypus.core.saves.AutoBackupPolicy
import io.github.huang404yan.platypus.core.saves.LevelDat
import io.github.huang404yan.platypus.core.saves.Saves
import io.github.huang404yan.platypus.core.saves.SavesAutoBackup
import io.github.huang404yan.platypus.core.saves.SavesSnapshot
import io.github.huang404yan.platypus.core.saves.WorldBackup
import io.github.huang404yan.platypus.core.saves.WorldEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一个实例的存档页：列世界、备份、还原、删、导入。
 *
 * 三处刻意的设计：
 *
 * 1. 删除世界不是「直接删」。它先备一份进 platypus/saves-backup，备份失败就整个中止 ——
 *    存档是玩家唯一无法重新下载的东西（模组能重下、Java 能重装、整合包能重导），
 *    所以这里的默认动作永远是「先留一份」。
 * 2. 还原默认不覆盖同名世界。重名时要求换个名字，要覆盖得显式勾上。
 *    存档目录里那份可能是玩家玩了一百小时的那个，「还原」不该悄悄把它换掉。
 * 3. 世界名优先取 level.dat 里的 LevelName，退回目录名。目录名是英文的 world、
 *    新建世界时随手敲的名字，level.dat 里那个才是玩家在游戏里看到的名字。
 */
@Composable
internal fun SavesPanel(
    instance: Instance,
    backend: LauncherBackend? = null,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val layout = remember(instance.directory) {
        runCatching { InstanceLayouts.of(instance) }.getOrNull()
    }
    val gameDir: File = layout?.gameDir ?: File(instance.directory.trim().trim('"'))
    val gameRoot: File = layout?.gameRoot ?: gameDir

    var snapshot by remember { mutableStateOf<SavesSnapshot?>(null) }
    var backups by remember { mutableStateOf<List<WorldBackup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyText by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var noticeIsError by remember { mutableStateOf(false) }

    var pendingDelete by remember { mutableStateOf<WorldEntry?>(null) }
    var pendingRestore by remember { mutableStateOf<WorldBackup?>(null) }
    // 自动备份策略：null 表示还没读到（后端没注入设置仓库时也用默认值）。
    var policy by remember { mutableStateOf<AutoBackupPolicy?>(null) }

    LaunchedEffect(gameDir) {
        policy = backend?.settings?.let { repository ->
            withContext(Dispatchers.IO) {
                runCatching { LauncherSettings.policyOf(repository.load()) }.getOrNull()
            }
        } ?: AutoBackupPolicy.DEFAULT
    }

    fun speak(text: String, error: Boolean = false) {
        notice = text
        noticeIsError = error
    }

    fun reload() {
        scope.launch {
            loading = true
            val scanned = withContext(Dispatchers.IO) { runCatching { Saves.scan(gameDir) }.getOrNull() }
            val listed = withContext(Dispatchers.IO) { runCatching { Saves.backups(gameRoot) }.getOrDefault(emptyList()) }
            snapshot = scanned
            backups = listed
            loading = false
        }
    }

    LaunchedEffect(gameDir, gameRoot) { reload() }

    /** 备份、还原、删除三种动作共用一段外壳：置忙、跑、刷新列表、说话。 */
    fun runAction(busy: String, block: suspend () -> String) {
        if (busyText.isNotBlank()) return
        busyText = busy
        notice = ""
        scope.launch {
            try {
                val message = withContext(Dispatchers.IO) { block() }
                speak(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                speak(e.message ?: ("操作失败：" + e.javaClass.simpleName), error = true)
            } finally {
                busyText = ""
                reload()
            }
        }
    }

    fun backupWorld(world: WorldEntry) = runAction("正在备份 " + world.displayName) {
        val made = Saves.backup(world, gameRoot)
        "备份好了：" + made.zip.name + "（" + sizeTextOf(made.sizeBytes) + "）。"
    }

    fun deleteWorld(world: WorldEntry) = runAction("正在备份并删除 " + world.displayName) {
        val made = Saves.delete(world, gameRoot)
        "已删除「" + world.displayName + "」。删之前留了一份备份：" + made.zip.name + "，要反悔就在下面的备份列表里还原。"
    }

    fun restoreBackup(backup: WorldBackup, name: String, overwrite: Boolean) = runAction("正在还原 " + backup.name) {
        val result = Saves.restore(backup.zip, gameDir, name = name, overwrite = overwrite)
        if (!result.ok) throw IllegalStateException(result.message)
        "还原好了：" + (result.target?.name ?: name) + "（" + result.files + " 个文件）。"
    }

    fun importBackup() {
        val startDir = File(System.getProperty("user.home"), "Desktop").takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"))
        val chosen = chooseOpenFile(title = "选一个世界 zip 导进来", extension = "zip", startDir = startDir) ?: return
        runAction("正在导入 " + chosen.name) {
            val result = Saves.importZip(chosen, gameDir)
            if (!result.ok) throw IllegalStateException(result.message)
            "导入好了：" + (result.target?.name ?: "") + "（" + result.files + " 个文件）。"
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回详情") }
            Spacer(Modifier.width(8.dp))
            Text("存档", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "这里的读写目标是 " + gameDir.absolutePath + (if (layout?.gameDir != layout?.gameRoot) "（这个实例开着版本隔离）" else "（与同目录其他版本共用）") +
                "。删世界会先备份，备份在原目录的 platypus/saves-backup 里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SavesCard("存档目录") {
            Text(
                Saves.savesDir(gameDir).absolutePath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { openInExplorer(Saves.savesDir(gameDir)) }) { Text("打开存档目录") }
                OutlinedButton(onClick = { importBackup() }, enabled = busyText.isBlank()) { Text("导入存档包…") }
                OutlinedButton(onClick = { openInExplorer(Saves.backupDir(gameRoot)) }) { Text("打开备份目录") }
            }
        }

        SavesCard("世界") {
            val snapshotValue = snapshot
            when {
                loading && snapshotValue == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在读存档目录…", style = MaterialTheme.typography.bodySmall)
                }
                snapshotValue == null || !snapshotValue.exists -> Text(
                    "这个实例还没有存档目录。进一次游戏、新建一个世界，这里就会有了。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                snapshotValue.worlds.isEmpty() -> Text(
                    "存档目录是空的。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        snapshotValue.worlds.size.toString() + " 个世界，共 " + sizeTextOf(snapshotValue.totalBytes) + "。",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    snapshotValue.worlds.forEach { world ->
                        WorldRow(
                            world = world,
                            busy = busyText.isNotBlank(),
                            onBackup = { backupWorld(world) },
                            onDelete = { pendingDelete = world },
                        )
                    }
                    snapshotValue.warnings.forEach { warning ->
                        Text(
                            "· " + warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }

        val currentPolicy = policy
        if (currentPolicy != null) {
            SavesCard("自动备份") {
                if (!currentPolicy.enabled) {
                    Text(
                        "自动备份是关着的。每次游戏正常退出后不会自动备份，上面那些备份都是手动做的。" +
                            "要打开就去「设置 → 存档」。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "每次游戏正常退出后自动备份。每 " + currentPolicy.intervalHours + " 小时最多一份，" +
                            "每个世界留最新的 " + currentPolicy.keep + " 份，超出时从最旧的开始删。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val latest = SavesAutoBackup.lastBackupByWorld(backups)
                    val shown = snapshot?.worlds.orEmpty()
                    if (shown.isNotEmpty()) {
                        shown.forEach { world ->
                            val last = latest[world.name]
                            Text(
                                "「" + world.displayName + "」· " +
                                    (if (last == null) "还没备份过，下次退出就会备" else "上次 " + formatTime(last.createdAt)),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "间隔与份数在「设置 → 存档」里改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SavesCard("备份") {
            if (backups.isEmpty()) {
                Text(
                    "还没有备份。备份是一个独立的 zip，删了世界也能从它还原回来。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    backups.size.toString() + " 份备份，都在 " + Saves.backupDir(gameRoot).absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                backups.forEach { backup ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(backup.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatTime(backup.createdAt) + " · " + sizeTextOf(backup.sizeBytes) + " · " + backup.zip.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { pendingRestore = backup },
                            enabled = busyText.isBlank(),
                        ) { Text("还原…") }
                    }
                }
            }
        }

        if (busyText.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(busyText + "…", style = MaterialTheme.typography.bodySmall)
            }
        }
        NoticeFade(notice) { shown ->
            Text(
                shown,
                style = MaterialTheme.typography.bodyMedium,
                color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
    }

    val doomed = pendingDelete
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个存档？") },
            text = {
                Text(
                    "「" + doomed.displayName + "」会被删掉。删之前会自动备份一份到 platypus/saves-backup，" +
                        "要反悔就在备份列表里还原回来。备份（" + sizeTextOf(doomed.sizeBytes) + "）会占一份空间。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    deleteWorld(doomed)
                }) { Text("备份并删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    val restoring = pendingRestore
    if (restoring != null) {
        RestoreDialog(
            backup = restoring,
            gameDir = gameDir,
            onDismiss = { pendingRestore = null },
            onConfirm = { name, overwrite ->
                pendingRestore = null
                restoreBackup(restoring, name, overwrite)
            },
        )
    }
}

/**
 * 还原前问一句「用哪个名字」。
 *
 * 默认填备份里的世界名，但它是可改的：同名世界已经存在时，直接点「还原」
 * 要么覆盖玩家玩着的那个，要么静默失败，两种都糟。
 */
@Composable
private fun RestoreDialog(
    backup: WorldBackup,
    gameDir: File,
    onDismiss: () -> Unit,
    onConfirm: (name: String, overwrite: Boolean) -> Unit,
) {
    var name by remember(backup.zip.absolutePath) { mutableStateOf(backup.name) }
    var overwrite by remember(backup.zip.absolutePath) { mutableStateOf(false) }
    val cleaned = Saves.safeFileName(name)
    val exists = File(Saves.savesDir(gameDir), cleaned).exists()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("还原 " + backup.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    formatTime(backup.createdAt) + " 的备份，共 " + sizeTextOf(backup.sizeBytes) + "。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("还原成哪个名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(if (cleaned == name) "就是存档目录里的世界文件夹名。" else "非法字符会换成下划线，实际用「" + cleaned + "」。")
                    },
                )
                if (exists) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = overwrite, onCheckedChange = { overwrite = it })
                        Text(
                            "已经有一个叫「" + cleaned + "」的世界，覆盖它",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!overwrite) {
                        Text(
                            "不勾的话这一步会失败并提示你换个名字 —— 那个世界里可能有别的进度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(cleaned, overwrite) },
                enabled = cleaned.isNotBlank() && (!exists || overwrite),
            ) { Text("还原") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun WorldRow(
    world: WorldEntry,
    busy: Boolean,
    onBackup: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WorldIcon(world)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(world.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (world.broken) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "读不出元数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                listOfNotNull(
                    world.name,
                    sizeTextOf(world.sizeBytes),
                    world.fileCount.toString() + " 个文件",
                    world.info?.let { gameModeLabel(it.gameType) },
                    world.info?.takeIf { it.hardcore }?.let { "硬核" },
                    world.info?.versionName?.takeIf { it.isNotBlank() },
                    "上次玩：" + formatTime(world.info?.lastPlayed ?: world.lastModified),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onBackup, enabled = !busy) { Text("备份") }
        TextButton(onClick = onDelete, enabled = !busy) { Text("删除") }
    }
}

/**
 * 世界图标：存在 icon.png 就显示，否则用一个方块占位。
 *
 * 图标是玩家在世界列表里认人的主要线索（缩略图是那个世界的第一幕），
 * 所以值得读出来 —— 读不出来就画个占位，不因为一张图让整页失败。
 */
@Composable
private fun WorldIcon(world: WorldEntry) {
    val file = world.iconFile
    val bitmap: ImageBitmap? = remember(file?.absolutePath, world.lastModified) {
        if (file == null) {
            null
        } else {
            runCatching { file.inputStream().use { loadImageBitmap(it) } }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier.size(44.dp, 44.dp).background(MaterialTheme.colorScheme.surface, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap),
                contentDescription = world.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.surface, shape),
            )
        } else {
            Text("~", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun gameModeLabel(gameType: Int?): String? = when (gameType) {
    0 -> "生存"
    1 -> "创造"
    2 -> "冒险"
    3 -> "旁观"
    else -> null
}

private fun formatTime(millis: Long): String {
    if (millis <= 0L) return "没有记录"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}

private fun sizeTextOf(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}

@Composable
private fun SavesCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
