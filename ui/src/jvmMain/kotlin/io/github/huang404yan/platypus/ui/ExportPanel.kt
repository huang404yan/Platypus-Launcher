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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.modpack.ModpackExport
import io.github.huang404yan.platypus.core.modpack.ModpackExportCategory
import io.github.huang404yan.platypus.core.modpack.ModpackExportException
import io.github.huang404yan.platypus.core.modpack.ModpackExportFormat
import io.github.huang404yan.platypus.core.modpack.ModpackExportOptions
import io.github.huang404yan.platypus.core.modpack.ModpackExportProgress
import io.github.huang404yan.platypus.core.modpack.ModpackExportResult
import io.github.huang404yan.platypus.core.mods.CurseForgeLookup
import io.github.huang404yan.platypus.core.mods.ModrinthHostedLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把一个实例导出成整合包。
 *
 * 为什么导出是「选目录 + 填名字」而不是「一键全打」：
 * 打包发出去的东西会进别人电脑，存档、日志、账号相关的东西一旦打进去就收不回来。
 * 所以默认只勾模组、配置、资源包、光影四项，存档必须自己勾；
 * 而且导出前会给一份清单让人看一眼，而不是点完就生成文件。
 */
@Composable
internal fun ExportPanel(
    instance: Instance,
    backend: LauncherBackend? = null,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val gameDir = remember(instance.directory) {
        File(instance.directory.trim().trim('"'))
    }

    var format by remember(instance.directory) { mutableStateOf(ModpackExportFormat.MCBBS) }
    var categories by remember(instance.directory) {
        mutableStateOf<Set<ModpackExportCategory>>(ModpackExportOptions.DEFAULT_CATEGORIES)
    }
    var name by remember(instance.directory) { mutableStateOf(instance.name) }
    var versionText by remember(instance.directory) { mutableStateOf("1.0.0") }
    var author by remember(instance.directory) { mutableStateOf("") }
    var description by remember(instance.directory) { mutableStateOf("") }

    var scanning by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ExportPreview?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ModpackExportProgress?>(null) }
    var result by remember { mutableStateOf<ModpackExportResult?>(null) }
    var failure by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    // 待确认覆盖的目标：写进 remember 而不是文件级变量，否则关掉面板再进来还带着上一次的选择。
    var pendingTarget by remember { mutableStateOf<File?>(null) }

    val options = ModpackExportOptions(
        name = name.trim(),
        version = versionText.trim().ifBlank { "1.0.0" },
        author = author.trim(),
        description = description.trim(),
        format = format,
        categories = categories,
    )

    // 勾选项一变就重新数一遍文件：用户要知道自己正在打包多少东西
    LaunchedEffect(gameDir, categories) {
        scanning = true
        preview = withContext(Dispatchers.IO) {
            runCatching { ExportPreview.of(ModpackExport.collect(gameDir, options)) }.getOrNull()
        }
        scanning = false
    }

    fun startExport(target: File) {
        if (exporting) return
        exporting = true
        failure = ""
        result = null
        job = scope.launch {
            try {
                val exported = withContext(Dispatchers.IO) {
                    ModpackExport.export(
                        instance = instance,
                        target = target,
                        options = options,
                        // 只有 Modrinth 格式才值得联网核对：MCBBS 包本来就要求文件随包走。
                        hostedLookup = if (format == ModpackExportFormat.MRPACK) {
                            ModrinthHostedLookup.asHostedLookup()
                        } else {
                            null
                        },
                        // CurseForge 那条要 API Key，从设置里读；没填就传 null（files 列表留空，
                        // 文件照样全打进包里 —— 这是正常结果，不是失败）。
                        curseLookup = if (format == ModpackExportFormat.CURSE) {
                            val key = runCatching { backend?.settings?.load() }.getOrNull()?.curseForgeApiKey.orEmpty()
                            if (key.isBlank()) null else CurseForgeLookup.asCurseLookup(key)
                        } else {
                            null
                        },
                    ) { p -> progress = p }
                }
                result = exported
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModpackExportException) {
                failure = e.message.orEmpty()
            } catch (e: Exception) {
                failure = "导出失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                exporting = false
                progress = null
                job = null
            }
        }
    }

    fun pickTarget() {
        val safeName = sanitizeFileName(name.trim().ifBlank { instance.name })
        val suggested = safeName + "-" + versionText.trim().ifBlank { "1.0.0" } + "." + format.extension
        val startDir = File(System.getProperty("user.home"), "Desktop").takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"))
        val chosen = chooseSaveFile(
            title = "导出整合包到",
            suggestedName = suggested,
            extension = format.extension,
            startDir = startDir,
        ) ?: return
        val target = if (chosen.extension.equals(format.extension, ignoreCase = true)) {
            chosen
        } else {
            File(chosen.parentFile, chosen.name + "." + format.extension)
        }
        if (target.exists()) {
            confirmOverwrite = true
            pendingTarget = target
        } else {
            startExport(target)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回详情") }
            Spacer(Modifier.width(8.dp))
            Text("导出整合包", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "把 " + instance.name + " 打包成能发给别人的整合包。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ExportCard("包格式") {
            ModpackExportFormat.entries.forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = format == option,
                        onClick = { format = option },
                        enabled = !exporting,
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            formatNoteOf(option),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "两种格式不能混写：解析方一见到 modrinth.index.json 就按 Modrinth 包读，加载器版本对不上会整个导入失败。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ExportCard("打进去什么") {
            ModpackExportCategory.entries.forEach { category ->
                val checked = category in categories
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { on ->
                            categories = if (on) categories + category else categories - category
                        },
                        enabled = !exporting,
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(category.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            category.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ExportCard("包信息") {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("整合包名称") },
                singleLine = true,
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = versionText,
                onValueChange = { versionText = it },
                label = { Text("版本号") },
                placeholder = { Text("1.0.0") },
                singleLine = true,
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("你自己给这版包起的号，跟游戏版本不是一回事。") },
            )
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text("作者（可留空）") },
                singleLine = true,
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("简介（可留空）") },
                enabled = !exporting,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ExportCard("清单") {
            val snapshot = preview
            when {
                scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在数文件…", style = MaterialTheme.typography.bodySmall)
                }
                snapshot == null || snapshot.count == 0 -> Text(
                    "选中的目录里没有文件。换一下上面的勾选项。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    Text(
                        snapshot.count.toString() + " 个文件，共 " + snapshot.sizeText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    snapshot.perCategory.forEach { (category, count) ->
                        if (count > 0) {
                            Text(
                                "· " + category.label + "：" + count + " 个",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (snapshot.truncated) {
                        Text(
                            "文件太多，清单只数了前 " + ModpackExport.MAX_FILES + " 个；导出时也只打这些。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (snapshot.biggest.isNotEmpty()) {
                        Text(
                            "最大的几个：" + snapshot.biggest.joinToString("、") { it.first + "（" + it.second + "）" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { pickTarget() },
                enabled = !exporting && (preview?.count ?: 0) > 0,
            ) {
                Text(if (exporting) "正在导出…" else "选择保存位置并导出")
            }
            if (exporting) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    progress?.let { it.step + "（" + it.done + "/" + it.total + "）" } ?: "准备中…",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { job?.cancel() }) { Text("取消") }
            }
        }

        val done = result
        if (done != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("导出完成", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(done.archive.absolutePath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text(
                        "打进包里 " + done.packaged + " 个，只留下载地址 " + done.hosted + " 个" +
                            (if (done.curseFiles > 0) "，清单里写了 " + done.curseFiles + " 个文件的 CurseForge ID" else "") +
                            "，共 " + sizeTextOf(done.bytes) + "。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    done.warnings.forEach { warning ->
                        Text(
                            "· " + warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Row {
                        TextButton(onClick = { openInExplorer(done.archive.parentFile) }) { Text("打开所在文件夹") }
                    }
                }
            }
        }
        if (failure.isNotBlank()) {
            Text(failure, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmOverwrite) {
        val target = pendingTarget
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("文件已经存在") },
            text = { Text((target?.absolutePath ?: "") + " 已经有了。继续会覆盖它。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmOverwrite = false
                    if (target != null) startExport(target)
                }) { Text("覆盖") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = false }) { Text("取消") }
            },
        )
    }
}

/** Windows 文件名里不能出现的字符；换成下划线而不是删掉，免得两个不同的名字撞成同一个。 */
private fun sanitizeFileName(raw: String): String {
    val cleaned = raw.map { ch ->
        if (ch in charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')) '_' else ch
    }.joinToString("")
    return cleaned.trim().ifBlank { "modpack" }
}

private data class ExportPreview(
    val count: Int,
    val bytes: Long,
    val perCategory: Map<ModpackExportCategory, Int>,
    val biggest: List<Pair<String, String>>,
    val truncated: Boolean,
) {
    val sizeText: String get() = sizeTextOf(bytes)

    companion object {
        fun of(entries: List<io.github.huang404yan.platypus.core.modpack.ModpackExportEntry>): ExportPreview {
            val perCategory = linkedMapOf<ModpackExportCategory, Int>()
            ModpackExportCategory.entries.forEach { perCategory[it] = 0 }
            entries.forEach { entry ->
                val top = entry.path.substringBefore('/')
                val category = ModpackExportCategory.entries.firstOrNull { it.dirName == top } ?: return@forEach
                perCategory[category] = (perCategory[category] ?: 0) + 1
            }
            val biggest = entries.sortedByDescending { it.bytes }.take(3)
                .map { it.path.substringAfterLast('/') to sizeTextOf(it.bytes) }
            return ExportPreview(
                count = entries.size,
                bytes = entries.sumOf { it.bytes },
                perCategory = perCategory,
                biggest = biggest,
                truncated = entries.size >= ModpackExport.MAX_FILES,
            )
        }
    }
}

private fun sizeTextOf(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}

private fun formatNoteOf(format: ModpackExportFormat): String = when (format) {
    ModpackExportFormat.MCBBS -> "HMCL、PCL 都认，文件全打在包里，离线也能装。包会比较大。"
    ModpackExportFormat.MRPACK -> "Modrinth 包。认得出的模组只留下载地址，包小；认不出的仍然打进去。"
    ModpackExportFormat.CURSE -> "CurseForge 包。只写 manifest.json，模组、配置、资源包全打在包里，离线也能装。体积与 MCBBS 一样大。"
}

@Composable
private fun ExportCard(title: String, content: @Composable () -> Unit) {
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
