package io.github.huang404yan.platypus.launcher.preview

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.saves.AutoBackupPolicy
import io.github.huang404yan.platypus.core.saves.Saves
import io.github.huang404yan.platypus.core.saves.SavesAutoBackup
import io.github.huang404yan.platypus.core.saves.SavesSnapshot
import io.github.huang404yan.platypus.core.saves.WorldBackup
import io.github.huang404yan.platypus.core.saves.WorldEntry
import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 存档（手机）。
 *
 * 桌面端的 SavesPanel 干的是同一件事，磁盘那一半全在 core 的 [Saves] / [SavesAutoBackup] 里，
 * 一行都没重写；这里换掉的只有三处平台差异：
 *
 * 1. 「打开目录」没有资源管理器可调，改成把路径复制到剪贴板（文件管理器里自己粘）。
 * 2. 「导入存档包」走系统文件选择器（SAF），选中后先拷进 cacheDir 再交给 core ——
 *    core 只认 File，而 SAF 给的是 content:// 的 Uri。
 * 3. 世界缩略图用 BitmapFactory 解（桌面那边是 loadImageBitmap，只有 JVM 源集有）。
 *
 * 自动备份的三个格（开关 / 间隔 / 份数）留在这一页而不是塞进设置页：手机上的设置页
 * 还没有「存档」分栏，而这三个值离开存档页就没人知道是干什么的。它们写的是同一份
 * platypus/settings.json，桌面端「设置 → 存档」看到的是同一个值。
 */
@Composable
internal fun SavesScreen(
    instance: Instance,
    gameRoot: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val layout = remember(instance.directory) { runCatching { InstanceLayouts.of(instance) }.getOrNull() }
    val gameDir: File = layout?.gameDir ?: File(instance.directory.trim().trim('"'))
    val rootDir: File = layout?.gameRoot ?: File(gameRoot.trim().ifBlank { gameDir.absolutePath })
    val settingsFile = remember(rootDir.absolutePath) { JsonSettingsRepository.defaultFile(rootDir) }

    var snapshot by remember(instance.directory) { mutableStateOf<SavesSnapshot?>(null) }
    var backups by remember(instance.directory) { mutableStateOf<List<WorldBackup>>(emptyList()) }
    var loading by remember(instance.directory) { mutableStateOf(true) }
    var busyText by remember(instance.directory) { mutableStateOf("") }
    var notice by remember(instance.directory) { mutableStateOf("") }
    var noticeIsError by remember(instance.directory) { mutableStateOf(false) }
    var pendingDelete by remember(instance.directory) { mutableStateOf<WorldEntry?>(null) }
    var pendingRestore by remember(instance.directory) { mutableStateOf<WorldBackup?>(null) }

    // 自动备份策略：null = 还没读到。读到之后下面那张卡才画得出来。
    var policy by remember(instance.directory) { mutableStateOf<AutoBackupPolicy?>(null) }
    var hoursText by remember(instance.directory) { mutableStateOf("") }
    var keepText by remember(instance.directory) { mutableStateOf("") }
    var savingPolicy by remember(instance.directory) { mutableStateOf(false) }

    fun speak(text: String, error: Boolean = false) {
        notice = text
        noticeIsError = error
    }

    fun reload() {
        scope.launch {
            loading = true
            val scanned = withContext(Dispatchers.IO) { runCatching { Saves.scan(gameDir) }.getOrNull() }
            val listed = withContext(Dispatchers.IO) {
                runCatching { Saves.backups(rootDir) }.getOrDefault(emptyList())
            }
            snapshot = scanned
            backups = listed
            loading = false
        }
    }

    LaunchedEffect(instance.directory) {
        val stored = withContext(Dispatchers.IO) {
            runCatching { JsonSettingsRepository(settingsFile).load() }.getOrNull()
        }
        val loaded = stored?.let { LauncherSettings.policyOf(it) } ?: AutoBackupPolicy.DEFAULT
        policy = loaded
        hoursText = loaded.intervalHours.toString()
        keepText = loaded.keep.toString()
    }

    LaunchedEffect(instance.directory) { reload() }

    /** 备份 / 还原 / 删除 共用一段外壳：置忙、跑、刷新列表、说话。与桌面端同形状。 */
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
        val made = Saves.backup(world, rootDir)
        "备份好了：" + made.zip.name + "（" + sizeTextOf(made.sizeBytes) + "）。"
    }

    fun deleteWorld(world: WorldEntry) = runAction("正在备份并删除 " + world.displayName) {
        val made = Saves.delete(world, rootDir)
        "已删除「" + world.displayName + "」。删之前留了一份备份：" + made.zip.name +
            "，要反悔就在下面的备份列表里还原。"
    }

    fun restoreBackup(backup: WorldBackup, name: String, overwrite: Boolean) =
        runAction("正在还原 " + backup.name) {
            val result = Saves.restore(backup.zip, gameDir, name = name, overwrite = overwrite)
            if (!result.ok) throw IllegalStateException(result.message)
            "还原好了：" + (result.target?.name ?: name) + "（" + result.files + " 个文件）。"
        }

    // 系统文件选择器。回调捕获的是首次组合时的变量，所以里面只用不会变的 gameDir 与 runAction。
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val temp = File(context.cacheDir, "saves-import-" + System.currentTimeMillis() + ".zip")
            runAction("正在导入存档包") {
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: return@runAction "读不到这个文件（系统没给出输入流）。"
                    input.use { source ->
                        temp.outputStream().use { sink -> source.copyTo(sink) }
                    }
                    val result = Saves.importZip(temp, gameDir)
                    if (!result.ok) throw IllegalStateException(result.message)
                    "导入好了：" + (result.target?.name ?: "") + "（" + result.files + " 个文件）。"
                } finally {
                    // 临时文件只用于这一趟导入：core 已经把内容解进存档目录了。
                    runCatching { temp.delete() }
                }
            }
        }
    }

    /** 把自动备份的三个值写回 settings.json。只改这三个字段，别的字段一起带过去。 */
    fun savePolicy(enabled: Boolean) {
        if (savingPolicy) return
        savingPolicy = true
        scope.launch {
            try {
                val parsed = AutoBackupPolicy.parse(enabled, hoursText, keepText)
                val stored = withContext(Dispatchers.IO) {
                    val repository = JsonSettingsRepository(settingsFile)
                    val current = repository.load()
                    repository.save(
                        current.copy(
                            autoBackupEnabled = parsed.enabled,
                            autoBackupHours = parsed.intervalHours,
                            autoBackupKeep = parsed.keep,
                        ),
                    )
                }
                val applied = LauncherSettings.policyOf(stored)
                policy = applied
                hoursText = applied.intervalHours.toString()
                keepText = applied.keep.toString()
                speak(
                    if (applied.enabled) {
                        "自动备份设置已保存：每 " + applied.intervalHours + " 小时最多一份，每个世界留 " +
                            applied.keep + " 份。"
                    } else {
                        "自动备份已关掉。"
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                speak("设置没存下来：" + (e.message ?: e.javaClass.simpleName), error = true)
            } finally {
                savingPolicy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("← 返回详情") }
            Text("存档", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "读写目标是 " + gameDir.absolutePath +
                (if (layout?.gameDir != layout?.gameRoot) "（这一版开着版本隔离）" else "（与同目录其他版本共用）") +
                "。删世界会先自动备份，备份在 " + Saves.backupDir(rootDir).absolutePath + "。",
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
                OutlinedButton(
                    onClick = { notice = copyToClipboard(context, Saves.savesDir(gameDir).absolutePath) },
                ) { Text("复制存档路径") }
                OutlinedButton(
                    onClick = { pickZip.launch(arrayOf("*/*")) },
                    enabled = busyText.isBlank(),
                ) { Text("导入存档包…") }
                OutlinedButton(
                    onClick = { notice = copyToClipboard(context, Saves.backupDir(rootDir).absolutePath) },
                ) { Text("复制备份路径") }
            }
            Text(
                "导入支持世界 zip（多世界整合包的那种也行），选好之后先复制到临时目录再解开。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SavesCard("世界") {
            val current = snapshot
            when {
                loading && current == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  正在读存档目录…", style = MaterialTheme.typography.bodySmall)
                }
                current == null || !current.exists -> Text(
                    "这个实例还没有存档目录。进一次游戏、新建一个世界，这里就会有了。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                current.worlds.isEmpty() -> Text(
                    "存档目录是空的。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        current.worlds.size.toString() + " 个世界，共 " + sizeTextOf(current.totalBytes) + "。",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    current.worlds.forEach { world ->
                        WorldRow(
                            world = world,
                            busy = busyText.isNotBlank(),
                            onBackup = { backupWorld(world) },
                            onDelete = { pendingDelete = world },
                        )
                    }
                    current.warnings.forEach { warning ->
                        Text(
                            "· " + warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }

        val applied = policy
        if (applied != null) {
            SavesCard("自动备份") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("开着", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "每次游戏正常退出后备份一轮；每 " + applied.intervalHours + " 小时最多一份，" +
                                "每个世界留最新的 " + applied.keep + " 份，超出时从最旧的开始删。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = applied.enabled,
                        onCheckedChange = { on -> savePolicy(on) },
                        enabled = !savingPolicy,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { hoursText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("间隔（小时）") },
                        singleLine = true,
                        enabled = !savingPolicy && applied.enabled,
                    )
                    OutlinedTextField(
                        value = keepText,
                        onValueChange = { keepText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("留几份") },
                        singleLine = true,
                        enabled = !savingPolicy && applied.enabled,
                    )
                }
                Button(
                    onClick = { savePolicy(applied.enabled) },
                    enabled = !savingPolicy && applied.enabled,
                ) { Text(if (savingPolicy) "保存中…" else "保存这两个数") }
                if (applied.enabled) {
                    val latest = SavesAutoBackup.lastBackupByWorld(backups)
                    snapshot?.worlds.orEmpty().forEach { world ->
                        val last = latest[world.name]
                        Text(
                            "「" + world.displayName + "」· " +
                                (if (last == null) "还没备份过，下次退出就会备" else "上次 " + formatTime(last.createdAt)),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "这三个值与桌面端共用（桌面在「设置 → 存档」）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    backups.size.toString() + " 份备份，都在 " + Saves.backupDir(rootDir).absolutePath,
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
                                formatTime(backup.createdAt) + " · " + sizeTextOf(backup.sizeBytes) +
                                    " · " + backup.zip.name,
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
                Text("  " + busyText + "…", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (notice.isNotBlank()) {
            Text(
                notice,
                style = MaterialTheme.typography.bodyMedium,
                color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
    }

    pendingDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个存档？") },
            text = {
                Text(
                    "「" + doomed.displayName + "」会被删掉。删之前会自动备份一份到 " +
                        Saves.backupDir(rootDir).absolutePath + "，要反悔就在备份列表里还原回来。备份（" +
                        sizeTextOf(doomed.sizeBytes) + "）会占一份空间。",
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

    pendingRestore?.let { restoring ->
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
 * 默认填备份里的世界名，但可改：同名世界已经存在时直接点「还原」，
 * 要么覆盖玩家正在玩的那个，要么静默失败，两种都糟。
 */
@Composable
private fun RestoreDialog(
    backup: WorldBackup,
    gameDir: File,
    onDismiss: () -> Unit,
    onConfirm: (name: String, overwrite: Boolean) -> Unit,
) {
    // 用 safeFileName 过一遍：core 的 restore 见到不安全的目录名会直接拒绝，
    // 这里先显示成最终会用到的那个名字，免得用户敲完才被告知有非法字符。
    var name by remember(backup.zip.absolutePath) { mutableStateOf(Saves.safeFileName(backup.name)) }
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
                        Text(
                            if (cleaned == name) {
                                "就是存档目录里的世界文件夹名。"
                            } else {
                                "非法字符会换成下划线，实际用「" + cleaned + "」。"
                            },
                        )
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
                    Text(
                        "  读不出元数据",
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
 * 世界缩略图：有 icon.png 就显示，否则画个占位。
 *
 * 图标是玩家在世界列表里认人的主要线索，所以值得读出来；读不出来就画占位，
 * 不因为一张图让整页失败。桌面对应的是 loadImageBitmap，那个只有 JVM 源集有，
 * 安卓这边用 BitmapFactory 解同一份 PNG。
 */
@Composable
private fun WorldIcon(world: WorldEntry) {
    val file = world.iconFile
    val bitmap: ImageBitmap? = remember(file?.absolutePath, world.lastModified) {
        if (file == null) {
            null
        } else {
            runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
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
