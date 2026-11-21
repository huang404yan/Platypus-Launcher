package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.JunkCleaner
import io.github.huang404yan.platypus.core.instance.JunkCleanOutcome
import io.github.huang404yan.platypus.core.instance.JunkReport
import io.github.huang404yan.platypus.core.instance.JunkTarget
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.instance.SpaceAnalyzer
import io.github.huang404yan.platypus.core.instance.SpaceItem
import io.github.huang404yan.platypus.core.instance.SpaceReport
import io.github.huang404yan.platypus.core.instance.formatBytes
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.instance.mergeInstances
import io.github.huang404yan.platypus.core.instance.pruneMissing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 百宝箱（设置 → 百宝箱）。
 *
 * 桌面端在「更多 → 百宝箱」。手机侧栏只有七项、二级项长在设置下面，所以落到这里 ——
 * 位置不同，做的是同一件事：清日志垃圾、看谁占着盘。
 *
 * 与桌面那份的区别只有数据来源：桌面通过 LauncherBackend 拿实例，手机直接在
 * 游戏目录里扫 `instances.json` + versions/。核心逻辑都是 core 里的同一份
 * [JunkCleaner] 与 [SpaceAnalyzer]，所以两边「可删的东西」「统计的口径」完全一致。
 */
@Composable
internal fun ToolboxPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by rememberFileAccessState(context)

    var gameRoot by remember { mutableStateOf(defaultGameRoot()) }
    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var selectedKey by remember { mutableStateOf("") }
    var junk by remember { mutableStateOf<JunkReport?>(null) }
    var space by remember { mutableStateOf<SpaceReport?>(null) }
    var scanningJunk by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var confirmClean by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val selected = instances.firstOrNull { instanceKey(it) == selectedKey }

    // 手机上的启动器数据目录是应用私有目录（java 运行时、下载缓存都在里面），
    // 它跟游戏目录不是一个地方，占几百 MB 也不奇怪，所以单独列一行。
    val dataDir = remember { context.filesDir }

    suspend fun loadInstances(root: File) {
        val registered = withContext(Dispatchers.IO) {
            runCatching { JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root)).list() }
                .getOrNull().orEmpty()
        }
        val scan = withContext(Dispatchers.IO) { runCatching { InstanceScanner.scan(root) }.getOrNull() }
        // 登记的实例可能早就在资源管理器里被删了，只摘展示、不写盘（理由见 pruneMissing）。
        val live = pruneMissing(registered, scan?.versionNames)
        val merged = mergeInstances(live.kept, scan?.instances.orEmpty(), scan?.libraryVersions.orEmpty())
        instances = merged
        if (merged.none { instanceKey(it) == selectedKey }) {
            selectedKey = merged.maxByOrNull { it.lastPlayedAt }?.let { instanceKey(it) }
                ?: merged.firstOrNull()?.let { instanceKey(it) }
                ?: ""
        }
    }

    fun rescanJunk(instance: Instance) {
        if (scanningJunk) return
        scanningJunk = true
        scope.launch {
            junk = withContext(Dispatchers.IO) { runCatching { JunkCleaner.scan(instance) }.getOrNull() }
            scanningJunk = false
        }
    }

    fun reanalyze() {
        if (analyzing) return
        analyzing = true
        val root = File(gameRoot.trim())
        scope.launch {
            space = withContext(Dispatchers.IO) {
                runCatching { SpaceAnalyzer.analyze(root, dataDir = dataDir) }.getOrNull()
            }
            analyzing = false
        }
    }

    LaunchedEffect(gameRoot, granted) {
        if (!granted) return@LaunchedEffect
        val root = File(gameRoot.trim())
        loadInstances(root)
        // 首屏就把空间分析跑起来：这一页是「看一眼」的性质，不该还要按一次按钮。
        analyzing = true
        space = withContext(Dispatchers.IO) {
            runCatching { SpaceAnalyzer.analyze(root, dataDir = dataDir) }.getOrNull()
        }
        analyzing = false
    }

    // 换实例就重扫：上一个实例的数字留在屏幕上，会让人以为新选的那个也这么多。
    LaunchedEffect(selectedKey, granted) {
        val instance = selected
        junk = null
        if (instance != null && granted) {
            scanningJunk = true
            junk = withContext(Dispatchers.IO) { runCatching { JunkCleaner.scan(instance) }.getOrNull() }
            scanningJunk = false
        }
    }

    fun doClean(report: JunkReport) {
        if (cleaning) return
        cleaning = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { JunkCleaner.clean(report) }.getOrNull() }
            cleaning = false
            if (outcome == null) {
                notice = "清理失败：一个文件都没删成，可能是目录被别的程序占着。"
                return@launch
            }
            notice = describeClean(outcome)
            val instance = selected
            if (instance != null) {
                junk = withContext(Dispatchers.IO) { runCatching { JunkCleaner.scan(instance) }.getOrNull() }
            }
            reanalyze()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("百宝箱", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "两件事：清掉游戏自己写出来的日志垃圾，看清是哪个目录占着盘。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!granted) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("还没有文件访问权限", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Android 11 起要读 /storage/emulated/0 下的目录，必须手动开「所有文件访问权限」。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { openAllFilesSettings(context) }) { Text("去授权") }
                }
            }
            return@Column
        }

        if (notice.isNotBlank()) {
            Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        ToolboxCard(
            title = "清理游戏垃圾",
            hint = "只删游戏日志、崩溃报告和 Mixin 输出 —— 这三样游戏每次启动都会自己重写。" +
                "存档、模组、配置一个都不碰。",
        ) {
            if (instances.isEmpty()) {
                Text(
                    "这个游戏目录下还没有版本，没什么可清的。先去「下载」装一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                InstancePicker(
                    instances = instances,
                    selected = selected,
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    onPick = { expanded = false; selectedKey = instanceKey(it) },
                )
                val report = junk
                when {
                    report == null -> Text(
                        if (scanningJunk) "正在数…" else "还没扫。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        report.targets.forEach { target -> JunkLine(target) }
                        Text(
                            if (report.isEmpty) "没有可清理的垃圾，已经很干净了。"
                            else "合计 " + report.totalFiles + " 个文件 · " + formatBytes(report.totalBytes) +
                                if (report.truncated) "（超过上限，实际更多）" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { selected?.let { rescanJunk(it) } },
                                enabled = !scanningJunk && !cleaning,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (scanningJunk) "数着…" else "重新扫") }
                            Button(
                                onClick = { confirmClean = true },
                                enabled = !report.isEmpty && !scanningJunk && !cleaning,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (cleaning) "清理中…" else "清理") }
                        }
                        Text(
                            "日志目录本身会留着，游戏下次启动照常往里写。被游戏占着的文件删不掉，" +
                                "清理结果里会如实告诉你几个没删成。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ToolboxCard(
            title = "空间分析",
            hint = "游戏根目录下每一项各自占多少，只列最大的几项。只读，不会删任何东西。",
        ) {
            Text(
                "根目录：" + gameRoot,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val report = space
            when {
                report == null -> Text(
                    if (analyzing) "正在算…" else "还没算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                report.isEmpty -> Text(
                    "这个目录里没有可统计的东西。可能游戏目录还没建好。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val max = report.items.maxOf { it.bytes }.coerceAtLeast(1L)
                    report.items.forEach { item -> SpaceLine(item, max) }
                    if (report.hiddenCount > 0) {
                        Text(
                            "…另有 " + report.hiddenCount + " 项更小的没列出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "合计 " + formatBytes(report.totalBytes) +
                                if (report.truncated) "（有的目录文件太多，是估的下限）" else "",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = { reanalyze() }, enabled = !analyzing) {
                            Text(if (analyzing) "算着…" else "重新统计")
                        }
                    }
                    Text(
                        "「启动器数据」是应用私有目录里的 Java 运行时与缓存，卸载启动器才会没。" +
                            "其余目录删了游戏就跑不起来，要动请从版本详情里确认它是什么。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val report = junk
    if (confirmClean && report != null) {
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text("要清掉这些吗") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "将删除 " + report.totalFiles + " 个文件，约 " + formatBytes(report.totalBytes) + "。",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "删的是 logs（游戏日志）、crash-reports（崩溃报告）和 .mixin.out（Mixin 输出）。" +
                            "不会动存档、模组和配置文件。要留着日志排查问题的话，先复制走再清。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmClean = false; doClean(report) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("删掉") }
            },
            dismissButton = { TextButton(onClick = { confirmClean = false }) { Text("先不清") } },
        )
    }
}

/** 清理结果的一句话。与桌面端的 describeClean 同一套分支，安卓这边单独一份因为模块不共享。 */
internal fun describeClean(outcome: JunkCleanOutcome): String = when {
    outcome.deletedFiles == 0 && outcome.failedFiles > 0 ->
        "一个都没删成：有 " + outcome.failedFiles + " 个文件被占着。先把游戏关掉再试。"
    outcome.deletedFiles == 0 -> "没有可清理的垃圾。"
    outcome.ok -> "已清理 " + outcome.deletedFiles + " 个文件，释放 " + formatBytes(outcome.freedBytes) + "。"
    else -> "已清理 " + outcome.deletedFiles + " 个文件，释放 " + formatBytes(outcome.freedBytes) +
        "；还有 " + outcome.failedFiles + " 个被占着没删成，关掉游戏再试一次。"
}

@Composable
private fun ToolboxCard(title: String, hint: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** 选版本：点一下铺开一列，而不是弹菜单 —— 手机上没有悬停，弹层收不收得回去全靠猜。 */
@Composable
private fun InstancePicker(
    instances: List<Instance>,
    selected: Instance?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onPick: (Instance) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onToggleExpanded, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected?.let { it.gameVersion + "  ·  " + it.name.ifBlank { it.id } } ?: "选一个版本",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (expanded) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    instances.forEach { instance ->
                        val isSelected = selected != null && instanceKey(instance) == instanceKey(selected)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onPick(instance) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (isSelected) "●" else "○",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                instance.gameVersion,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                instance.name.ifBlank { instance.id },
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (InstanceLayouts.effectiveIsolated(instance)) "隔离" else "共用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JunkLine(target: JunkTarget) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            target.label,
            Modifier.widthIn(min = 76.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (!target.exists) "没有这个目录"
            else if (target.files == 0) "空的"
            else target.files.toString() + " 个文件 · " + formatBytes(target.bytes) +
                if (target.truncated) "（超过上限）" else "",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一行：名字 + 一条按最大项归一的长条 + 数字。归一理由见桌面端同一组件。 */
@Composable
private fun SpaceLine(item: SpaceItem, maxBytes: Long) {
    val ratio = (item.bytes.toDouble() / maxBytes.toDouble()).coerceIn(0.0, 1.0)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(
                formatBytes(item.bytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp).background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(3.dp),
            ),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio.toFloat().coerceIn(0.005f, 1f))
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), RoundedCornerShape(3.dp)),
            )
        }
    }
}
