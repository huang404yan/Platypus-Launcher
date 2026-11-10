package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.DirUsage
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceDetails
import io.github.huang404yan.platypus.core.instance.InstanceDetailsLoader
import io.github.huang404yan.platypus.core.instance.InstanceEditResult
import io.github.huang404yan.platypus.core.instance.InstanceEdits
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 实例详情（手机）。
 *
 * 桌面端的 [InstanceDetailPanel] 有一半是桌面独有的：探测本机 Java、用资源管理器打开目录。
 * 手机上没有这两样，所以这里只保留「这个实例到底是什么」和「它能改的那几个值」，
 * 磁盘统计全部来自 core 的 [InstanceDetailsLoader] —— 一行扫描逻辑都没重写。
 *
 * 改出来的内存与 JVM 参数写进 <游戏目录>/platypus/instances.json，与桌面端是同一个文件：
 * 同一份游戏目录在手机和电脑之间拷来拷去时，两边看到的是同一份设置。
 */
@Composable
internal fun InstanceDetailScreen(
    instance: Instance,
    gameRoot: String,
    onBack: () -> Unit,
    onSaved: (Instance) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var details by remember(instance.directory) { mutableStateOf<InstanceDetails?>(null) }
    var loading by remember(instance.directory) { mutableStateOf(true) }
    var memoryText by remember(instance.directory, instance.memoryMb) { mutableStateOf(instance.memoryMb.toString()) }
    var jvmArgsText by remember(instance.directory) { mutableStateOf(instance.extraJvmArgs.joinToString("\n")) }
    var javaPath by remember(instance.directory, instance.javaPath) { mutableStateOf(instance.javaPath.orEmpty()) }
    var notice by remember(instance.directory) { mutableStateOf("") }
    var saving by remember(instance.directory) { mutableStateOf(false) }

    LaunchedEffect(instance.directory) {
        loading = true
        try {
            details = withContext(Dispatchers.IO) { InstanceDetailsLoader.load(instance) }
        } catch (e: CancellationException) {
            throw e
        } finally {
            loading = false
        }
    }

    /**
     * 保存：只写登记文件，不动版本 json、不动目录结构。
     *
     * 内存走 [Instance.clampMemoryMb]，与桌面端同一套钳制（512–32768），
     * 手填成 999999 不会写出一个起不来的实例。
     */
    fun save() {
        // 输入合并规则在 core 的 InstanceEdits 里，与桌面详情页是同一个函数。
        val result = when (val edited = InstanceEdits.fromInput(instance, memoryText, jvmArgsText, javaPath)) {
            is InstanceEditResult.Invalid -> {
                notice = edited.message
                return
            }
            is InstanceEditResult.Ok -> edited
        }
        val updated = result.instance
        val memoryMb = updated.memoryMb
        val root = details?.layout?.gameRoot ?: File(gameRoot.trim())
        saving = true
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root)).save(updated)
                }.exceptionOrNull()
            }
            saving = false
            if (error != null) {
                notice = "保存失败：" + (error.message ?: error.toString())
                return@launch
            }
            notice = if (result.memoryWasClamped) {
                "已按允许范围（" + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + " MB）改成 " + memoryMb + " MB。"
            } else {
                InstanceEdits.savedMessage(result)
            }
            onSaved(updated)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(instance.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "游戏 " + instance.gameVersion + (instance.loaderVersion?.let { " · 加载器 " + it } ?: "") +
                (if (instance.registered) " · 已登记" else " · 仅扫描到"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val d = details
        if (loading || d == null) {
            Card { Text("正在读实例目录…", Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }

        DetailCard("这个实例在哪") {
            DetailLine("版本目录", d.versionDir.absolutePath)
            DetailLine("游戏根目录", d.gameRoot.absolutePath)
            DetailLine(
                "共享目录",
                "libraries/ 与 assets/ 由所有实例共用；存档、模组、配置、日志都在版本目录里。",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { notice = copyToClipboard(context, d.versionDir.absolutePath) },
                ) { Text("复制实例路径") }
                OutlinedButton(
                    onClick = { notice = copyToClipboard(context, d.gameRoot.absolutePath) },
                ) { Text("复制根目录") }
            }
        }

        DetailCard("版本文件") {
            DetailLine("版本 json", if (d.versionJsonExists) "有" else "缺（这个实例起不来）")
            DetailLine(
                "client jar",
                if (d.clientJarExists) "有，" + fmtBytes(d.clientJarBytes) else "缺（启动会报缺少客户端 jar）",
            )
            if (d.mainClass.isNotBlank()) DetailLine("mainClass", d.mainClass)
            d.inheritsFrom?.let { DetailLine("继承自", it) }
            DetailLine("库条目", d.libraryCount.toString() + " 个")
        }

        DetailCard("Java") {
            val required = d.requiredJavaMajor ?: GameJavaRules.requiredMajor(instance.gameVersion)
            DetailLine("需要的 Java", "Java " + required + (d.javaComponent?.let { "（" + it + "）" } ?: ""))
            DetailLine("当前设置", javaPath.ifBlank { "没设" })
            OutlinedTextField(
                value = javaPath,
                onValueChange = { javaPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Java 路径（留空 = 桌面端自动找）") },
                singleLine = true,
            )
            Text(
                "手机端还不能启动游戏：应用内起 JVM 与图形转译属于 runtime-android 模块，现在还不存在。" +
                    "这个值先存着，桌面端读到同一份 instances.json 会用它。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DetailCard("内存与启动参数") {
            OutlinedTextField(
                value = memoryText,
                onValueChange = { memoryText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("最大内存（MB，" + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + "）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = jvmArgsText,
                onValueChange = { jvmArgsText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("额外 JVM 参数（一行一个）") },
                minLines = 2,
            )
            Text(
                "只在桌面端启动时生效；手机端现在不启动游戏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DetailCard("占了多少地方") {
            d.dirs.forEach { DirLine(it) }
            Text(
                "合计 " + fmtBytes(d.totalBytes) + "（不含共享的 libraries/ 与 assets/）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (d.warnings.isNotEmpty()) {
            DetailCard("注意") {
                d.warnings.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { save() }, enabled = !saving) { Text(if (saving) "保存中…" else "保存") }
            if (notice.isNotBlank()) {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** 手机屏窄，标签固定 96dp（按比例分会让「客户端 jar」这类词挤成竖排）。 */
@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DirLine(usage: DirUsage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            usage.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            if (!usage.exists) "没有" else usage.fileCount.toString() + " 个文件 · " + fmtBytes(usage.bytes),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}
