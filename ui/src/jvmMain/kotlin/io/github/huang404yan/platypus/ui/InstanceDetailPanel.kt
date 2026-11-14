package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.DirUsage
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceDetails
import io.github.huang404yan.platypus.core.instance.InstanceDetailsLoader
import io.github.huang404yan.platypus.core.instance.InstanceEditResult
import io.github.huang404yan.platypus.core.instance.InstanceEdits
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.formatBytes
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.runtime.findExactMajor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * 实例详情：一个实例到底是什么、装在哪、用什么 Java、多少内存。
 *
 * 之前这些信息在实例卡片上被压成四行小字，改 Java 只能改内存 ——
 * 用户想「把 Java 换成 17」「看看这个实例到底占多少地方」时没有入口。
 *
 * 这里不新建页面路由：详情是实例列表里的一次「钻进一个」，用返回按钮回去，
 * 左侧导航的高亮仍然是「实例」，不会出现「详情」这种一次性入口占一格导航。
 */
@Composable
internal fun InstanceDetailPanel(
    instance: Instance,
    backend: LauncherBackend?,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
) {
    var details by remember(instance.directory) { mutableStateOf<InstanceDetails?>(null) }
    var loading by remember(instance.directory) { mutableStateOf(true) }
    var runtimes by remember(instance.directory) { mutableStateOf<List<JavaRuntime>>(emptyList()) }

    var javaPath by remember(instance.directory, instance.javaPath) { mutableStateOf(instance.javaPath.orEmpty()) }
    var memoryText by remember(instance.directory, instance.memoryMb) { mutableStateOf(instance.memoryMb.toString()) }
    var jvmArgsText by remember(instance.directory) { mutableStateOf(instance.extraJvmArgs.joinToString("\n")) }
    // 版本隔离：关掉之后 --gameDir 指回游戏根目录，存档/模组/配置与同目录下别的版本共用。
    // 只有标准布局（versions/<id>）才给这个开关，别的目录关不关都是同一个地方。
    val canToggleIsolation = remember(instance.directory) {
        InstanceLayouts.supportsIsolation(File(instance.directory.trim().trim('"')))
    }
    var isolated by remember(instance.directory, instance.isolated) { mutableStateOf(instance.isolated) }

    var notice by remember(instance.directory) { mutableStateOf("") }
    var saving by remember(instance.directory) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(instance.directory) {
        loading = true
        try {
            details = withContext(Dispatchers.IO) { InstanceDetailsLoader.load(instance) }
            runtimes = withContext(Dispatchers.IO) {
                runCatching { backend?.javaRuntimes?.detect() }.getOrNull().orEmpty()
            }
        } finally {
            loading = false
        }
    }

    fun save() {
        if (backend == null) {
            notice = "后端未装配：改不了实例设置。"
            return
        }
        // 输入合并规则在 core 的 InstanceEdits 里，安卓详情页用的是同一个函数：
        // 两端写同一份 instances.json，规则分开写迟早会对不上。
        val result = when (
            val edited = InstanceEdits.fromInput(
                instance,
                memoryText,
                jvmArgsText,
                javaPath,
                isolated = if (canToggleIsolation) isolated else null,
            )
        ) {
            is InstanceEditResult.Invalid -> {
                notice = edited.message
                return
            }
            is InstanceEditResult.Ok -> edited
        }
        val updated = result.instance
        val memoryMb = updated.memoryMb
        saving = true
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { backend.instances.save(updated) }.exceptionOrNull()
            }
            saving = false
            if (error != null) {
                notice = "保存失败：" + (error.message ?: error.toString())
                return@launch
            }
            notice = when {
                // 说清楚「你填的数没生效，是这个范围钳制的结果」，否则用户以为填的生效了
                result.memoryWasClamped ->
                    "已按允许范围（" + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + " MB）改成 " + memoryMb + " MB。"
                // 关了隔离要明说存档挪到哪去了，否则用户回游戏发现「存档没了」
                canToggleIsolation && updated.isolated != instance.isolated && !updated.isolated ->
                    "已关掉版本隔离：存档、模组、配置现在读写 " + details?.gameRoot?.absolutePath + " 下那一份（和同目录其他版本共用）。"
                canToggleIsolation && updated.isolated != instance.isolated ->
                    "已打开版本隔离：存档、模组、配置读写 " + updated.directory + " 下那一份。"
                else -> InstanceEdits.savedMessage(result)
            }
            // 保存成功后调用方会立刻把详情页关掉（InstancePage 的 onNotice 里 detailKey = null），
            // 上面那句 notice 用户根本来不及看见 —— 真机点过一次：填 99999 保存，界面直接跳回列表，
            // 只看到 32768，不知道是自己填错还是被启动器截断了。所以回列表那句必须带同样的信息。
            onNotice(
                "已保存 " + instance.name + " 的设置。" +
                    if (result.memoryWasClamped) {
                        "内存超出允许范围（" + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + " MB），已改成 " + memoryMb + " MB。"
                    } else {
                        ""
                    }
            )
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack) { Text("← 返回实例列表") }
            Text(instance.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "游戏 " + instance.gameVersion + (instance.loaderVersion?.let { " · 加载器 " + it } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val d = details
        if (loading || d == null) {
            Card { Text("正在读实例目录…", Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) }
            return@Column
        }

        DetailCard("这个实例在哪") {
            DetailLine("版本目录", d.versionDir.absolutePath)
            DetailLine("游戏根目录", d.gameRoot.absolutePath)
            DetailLine(
                "存档与模组在",
                d.gameDir.absolutePath + if (d.isolated) "" else "（与同目录其他版本共用）",
            )
            DetailLine(
                "共享目录",
                "libraries/ 与 assets/ 由所有实例共用（放不下第二份）。",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { openFolder(d.versionDir) }) { Text("打开实例目录") }
                OutlinedButton(onClick = { openFolder(d.gameDir) }) { Text("打开存档目录") }
            }
        }

        DetailCard("版本隔离") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.fillMaxWidth(0.78f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (isolated) "开：这个实例自己一份存档、模组、配置" else "关：与同目录下的其他版本共用一份",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (canToggleIsolation) {
                            if (isolated) {
                                "装模组、存档、改配置都只影响这一个实例。整合包把 mods/ 平铺在游戏根目录时，要关掉才看得见那些模组。"
                            } else {
                                "游戏读写 " + d.gameRoot.absolutePath + " 下的 mods/、saves/、options.txt。" +
                                    "别的版本改了配置、换了模组，这边也会跟着变。"
                            }
                        } else {
                            "这个目录不是 versions/<id> 的形状，开不开隔离都落在同一处，所以不给开关。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isolated,
                    onCheckedChange = { isolated = it },
                    enabled = canToggleIsolation && !saving,
                )
            }
            if (canToggleIsolation && isolated != instance.isolated) {
                Text(
                    "改完要点下面的「保存」才生效，下次启动游戏才用新目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        DetailCard("版本文件") {
            DetailLine("版本 json", if (d.versionJsonExists) "有" else "缺（这个实例起不来）")
            DetailLine(
                "client jar",
                if (d.clientJarExists) "有，" + formatBytes(d.clientJarBytes) else "缺（启动会报缺少客户端 jar）",
            )
            if (d.mainClass.isNotBlank()) DetailLine("mainClass", d.mainClass)
            d.inheritsFrom?.let { DetailLine("继承自", it) }
            DetailLine("库条目", d.libraryCount.toString() + " 个")
            DetailLine(
                "需要的 Java",
                (d.requiredJavaMajor?.let { "Java " + it } ?: "版本 json 没写，按规则推断 Java " +
                    GameJavaRules.requiredMajor(instance.gameVersion)) +
                    (d.javaComponent?.let { "（" + it + "）" } ?: ""),
            )
        }

        DetailCard("Java") {
            val required = d.requiredJavaMajor ?: GameJavaRules.requiredMajor(instance.gameVersion)
            DetailLine("当前设置", javaPath.ifBlank { "没设，启动时按版本要求自动找" })
            OutlinedTextField(
                value = javaPath,
                onValueChange = { javaPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Java 可执行文件路径（留空 = 自动找）") },
                singleLine = true,
            )
            if (runtimes.isEmpty()) {
                Text(
                    "本机没探测到 Java。去「启动」页点「自动补 Java」现装一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "本机探测到 " + runtimes.size + " 个，点一下填进上面的框：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    runtimes.sortedBy { it.majorVersion }.forEach { runtime ->
                        val exact = runtime.majorVersion == required
                        OutlinedButton(
                            onClick = { javaPath = runtime.path },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Java " + runtime.majorVersion +
                                    (runtime.vendor?.takeIf { it.isNotBlank() }?.let { " · " + it } ?: "") +
                                    (if (exact) "  ← 这个版本要求的" else "") +
                                    "   " + runtime.path,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
                val hit = runtimes.findExactMajor(required)
                if (hit == null) {
                    Text(
                        "注意：没有正好是 Java " + required + " 的运行时。本启动器不做高版本顶替，启动会失败。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        DetailCard("内存与启动参数") {
            OutlinedTextField(
                value = memoryText,
                onValueChange = { memoryText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("最大内存（MB，范围 " + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + "）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = jvmArgsText,
                onValueChange = { jvmArgsText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("额外 JVM 参数（一行一个，如 -XX:+UseG1GC）") },
                minLines = 3,
            )
        }

        DetailCard("占了多少地方") {
            d.dirs.forEach { DirLine(it) }
            Text(
                "合计 " + formatBytes(d.totalBytes) + "（只算上面这些目录，不含共享的 libraries/ 与 assets/）",
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

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.18f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DirLine(usage: DirUsage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            usage.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.18f),
        )
        Text(
            if (!usage.exists) {
                "没有"
            } else {
                usage.fileCount.toString() + " 个文件 · " + formatBytes(usage.bytes)
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 打开目录：失败不抛，返回 false 让调用方自己决定要不要提示。 */
private fun openFolder(dir: File): Boolean = runCatching {
    if (!dir.exists()) return false
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Desktop.getDesktop().open(dir)
        true
    } else {
        false
    }
}.getOrDefault(false)
