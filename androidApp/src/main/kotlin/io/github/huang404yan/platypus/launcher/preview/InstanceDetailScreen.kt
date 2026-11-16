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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import io.github.huang404yan.platypus.core.instance.DeletionPlan
import io.github.huang404yan.platypus.core.instance.DirUsage
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceDeleter
import io.github.huang404yan.platypus.core.instance.InstanceDetails
import io.github.huang404yan.platypus.core.instance.InstanceDetailsLoader
import io.github.huang404yan.platypus.core.instance.InstanceEditResult
import io.github.huang404yan.platypus.core.instance.InstanceEdits
import io.github.huang404yan.platypus.core.instance.InstanceIntegrity
import io.github.huang404yan.platypus.core.instance.IntegrityReport
import io.github.huang404yan.platypus.core.instance.IntegrityState
import io.github.huang404yan.platypus.core.instance.MemoryDefaults
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
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
    /** 这个实例的版本目录已经被删掉了：调用方要退出详情页并重扫。 */
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var details by remember(instance.directory) { mutableStateOf<InstanceDetails?>(null) }
    var loading by remember(instance.directory) { mutableStateOf(true) }
    var memoryText by remember(instance.directory, instance.memoryMb) { mutableStateOf(instance.memoryMb.toString()) }
    // 设置页的「默认内存」。跟随默认的实例，这一格显示的是生效值而不是 instances.json 里存的老数
    // （规则见 [Instance.effectiveMemoryMb]，与桌面详情页同一套）。
    // 它要读一次 settings.json，是异步的，所以下面另有 LaunchedEffect 回填。
    var defaultMemoryMb by remember { mutableStateOf(Instance.DEFAULT_MEMORY_MB) }
    var jvmArgsText by remember(instance.directory) { mutableStateOf(instance.extraJvmArgs.joinToString("\n")) }
    var javaPath by remember(instance.directory, instance.javaPath) { mutableStateOf(instance.javaPath.orEmpty()) }
    var notice by remember(instance.directory) { mutableStateOf("") }
    var saving by remember(instance.directory) { mutableStateOf(false) }

    // 完整性检查。core 里的 InstanceIntegrity 是纯 Kotlin、不碰平台，
    // 桌面端「版本设置 → 总览」用的就是它，这里只是换一套 Compose 界面。
    var integrity by remember(instance.directory) { mutableStateOf<IntegrityReport?>(null) }
    var integrityChecking by remember(instance.directory) { mutableStateOf(false) }
    var integrityProgress by remember(instance.directory) { mutableStateOf(0 to 0) }
    var repairing by remember(instance.directory) { mutableStateOf(false) }
    var repairText by remember(instance.directory) { mutableStateOf("") }

    // 删除实例：先算计划（要遍历目录统计体积，放 IO），拿到数字才弹确认框。
    var deletePlan by remember(instance.directory) { mutableStateOf<DeletionPlan?>(null) }
    var deleteSavesToo by remember(instance.directory) { mutableStateOf(false) }
    var deleting by remember(instance.directory) { mutableStateOf(false) }

    // 存档是整页替换的（与桌面端「版本设置 → 存档」一样是「返回详情」那种），
    // 不塞成详情页里的一张卡：世界多的时候那一列会很长，且它自己有一堆按钮与对话框。
    var showSaves by remember(instance.directory) { mutableStateOf(false) }

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

    LaunchedEffect(instance.directory, gameRoot) {
        val settingsRoot = File(gameRoot.trim().ifBlank { defaultGameRoot() })
        val stored = withContext(Dispatchers.IO) {
            runCatching {
                JsonSettingsRepository(JsonSettingsRepository.defaultFile(settingsRoot)).load()
            }.getOrNull()
        } ?: return@LaunchedEffect
        // 设置页那边开着「自动」时，defaultMemoryMb 里存的是上次算出来的快照；
        // 换了台机器、或者设置被手改过，就地按这台手机的物理内存重算一遍才准。
        val fallback = MemoryDefaults.resolve(
            stored.defaultMemoryMb,
            stored.defaultMemoryAuto,
            deviceTotalMemoryMb(context),
        )
        defaultMemoryMb = fallback
        // 跟随默认的实例：这一格要显示生效的那个数，而不是 instances.json 里写死的老数。
        if (instance.memoryFollowsDefault) memoryText = fallback.toString()
    }

    /**
     * 按版本清单对一遍这个实例的文件。
     *
     * 只在本地读：拿 version json（含 inheritsFrom 合并）算出「应该有哪些文件」，
     * 再逐个比大小与 sha1。不联网、不写盘。
     *
     * @param verifyHashes false 时只比大小，几秒钟能过几万个文件，但同样大小的坏文件发现不了。
     */
    fun checkIntegrity(verifyHashes: Boolean) {
        if (integrityChecking || repairing) return
        repairText = ""
        integrityChecking = true
        integrityProgress = 0 to 0
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    InstanceIntegrity.inspect(instance, verifyHashes = verifyHashes) { done, total ->
                        integrityProgress = done to total
                    }
                }.getOrElse { error ->
                    IntegrityReport(
                        versionId = instance.gameVersion,
                        warnings = listOf("检查失败：" + describe(error)),
                    )
                }
            }
            integrity = report
            integrityChecking = false
        }
    }

    /** 把上一步查出来的问题文件补回来，补完立刻复检一遍（用与上次相同的方式）。 */
    fun repairIntegrity() {
        val report = integrity ?: return
        if (repairing || integrityChecking) return
        repairing = true
        repairText = "正在补齐 " + report.problemFiles + " 个文件…"
        scope.launch {
            val verifyHashes = report.hashesChecked
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    InstanceIntegrity.repair(report) { progress ->
                        repairText = "已补 " + progress.completedFiles + "/" + progress.totalFiles + " 个 · " +
                            fmtBytes(progress.completedBytes) + " / " + fmtBytes(progress.totalBytes)
                    }
                }
            }
            val failure = outcome.exceptionOrNull()
            repairing = false
            if (failure != null) {
                repairText = "补齐失败：" + describe(failure)
                return@launch
            }
            val result = outcome.getOrNull()
            repairText = if (result == null || result.ok) {
                "补完了，正在复核…"
            } else {
                "补完了，但还有 " + result.failures.size + " 个文件没下来，复核一遍看看。"
            }
            val again = withContext(Dispatchers.IO) {
                runCatching {
                    InstanceIntegrity.inspect(instance, verifyHashes = verifyHashes) { done, total ->
                        integrityProgress = done to total
                    }
                }.getOrNull()
            }
            if (again != null) integrity = again
        }
    }

    /** 点「删除这个实例」：只算计划、弹确认框，不动磁盘。 */
    fun prepareDelete() {
        if (deletePlan != null || deleting) return
        val root = details?.layout?.gameRoot ?: File(gameRoot.trim())
        scope.launch {
            val plan = withContext(Dispatchers.IO) {
                InstanceDeleter.inspect(gameRoot = root, instanceId = instance.id)
            }
            deletePlan = plan
            deleteSavesToo = false
        }
    }

    /** 确认框里点「删除」：删版本目录，删完把 instances.json 里那条登记也移除，再退出详情页。 */
    fun confirmDelete() {
        val plan = deletePlan ?: return
        if (!plan.deletable) {
            notice = plan.refusalReason
            deletePlan = null
            return
        }
        deleting = true
        val root = details?.layout?.gameRoot ?: File(gameRoot.trim())
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                InstanceDeleter.delete(plan, includeSaves = deleteSavesToo)
            }
            if (!outcome.ok) {
                deleting = false
                deletePlan = null
                notice = outcome.summary
                return@launch
            }
            // 文件没了，登记也必须跟着没：否则下次扫描会把「已登记却指向不存在的目录」留在列表里。
            withContext(Dispatchers.IO) {
                runCatching { JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root)).remove(instance.id) }
            }
            deleting = false
            deletePlan = null
            onDeleted()
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
        // 填的数等于「跟随默认」时的生效值 → 继续跟随；填成别的数就脱离。
        // 与桌面详情页、启动页同一条口径，三处不能各判一套。
        val following = instance.memoryFollowsDefault &&
            result.instance.memoryMb == Instance.effectiveMemoryMb(instance, defaultMemoryMb)
        val updated = result.instance.copy(memoryFollowsDefault = following)
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
            notice = when {
                result.memoryWasClamped ->
                    "已按允许范围（" + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + " MB）改成 " + memoryMb + " MB。"
                following -> "已保存：这一版跟随设置里的默认内存（" + memoryMb + " MB）。"
                else -> InstanceEdits.savedMessage(result)
            }
            onSaved(updated)
        }
    }

    // 存档整页替换详情：它自己有一列世界、一列备份和三个对话框，塞成卡片会太长。
    if (showSaves) {
        SavesScreen(instance = instance, gameRoot = gameRoot, onBack = { showSaves = false })
        return
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
                if (instance.memoryFollowsDefault) {
                    "这一版现在跟随设置里的默认内存（" + Instance.effectiveMemoryMb(instance, defaultMemoryMb) +
                        " MB）。填回这个数、或者干脆不动它，保存后就继续跟随；填成别的数就只对这一版生效。"
                } else {
                    "只在桌面端启动时生效；手机端现在不启动游戏。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 放在「占了多少地方」之前：先看能不能跑，再看占多大。与桌面端同一个顺序。
        DetailCard("检查文件完整性") {
            Text(
                "按版本清单（含父版本）对一遍：client jar、资源索引、库、资源对象。" +
                    "只读本地，不联网；缺的坏的可以只补那几个，不用整份重下。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (integrity == null && !integrityChecking) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { checkIntegrity(verifyHashes = true) },
                        enabled = !repairing,
                    ) { Text("检查（逐个算 sha1）") }
                    OutlinedButton(
                        onClick = { checkIntegrity(verifyHashes = false) },
                        enabled = !repairing,
                    ) { Text("快速检查（只比大小）") }
                }
            }
            if (integrityChecking) {
                val (done, total) = integrityProgress
                Text(
                    if (total > 0) "正在检查 " + done + "/" + total + " 个文件…" else "正在读版本清单…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            integrity?.let { report ->
                DetailLine("清单条目", report.totalFiles.toString() + " 个文件")
                DetailLine("结论", report.summary)
                if (!report.healthy) {
                    DetailLine("缺的文件", report.missing.size.toString() + " 个")
                    DetailLine("对不上的文件", report.broken.size.toString() + " 个")
                    if (report.missingBytes > 0L) {
                        DetailLine("要补多少", fmtBytes(report.missingBytes))
                    }
                    // 只列前几个：几百条的时候全列出来会把页面撑爆，
                    // 而用户真正要的是「点补齐」。
                    report.problems.take(5).forEach { file ->
                        DetailLine(
                            if (file.state == IntegrityState.MISSING) "缺" else "坏",
                            file.target.absolutePath,
                        )
                    }
                    if (report.problems.size > 5) {
                        Text(
                            "还有 " + (report.problems.size - 5) + " 个没列出来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!report.hashesChecked) {
                    Text(
                        "这次只比了大小，同样大小的坏文件发现不了；想查细一点用「检查（逐个算 sha1）」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                report.warnings.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!report.healthy) {
                        Button(
                            onClick = { repairIntegrity() },
                            enabled = !repairing && !integrityChecking,
                        ) { Text(if (repairing) "正在补齐…" else "补齐这 " + report.problemFiles + " 个文件") }
                    }
                    OutlinedButton(
                        onClick = { integrity = null; repairText = "" },
                        enabled = !repairing && !integrityChecking,
                    ) { Text("清掉结果") }
                }
                if (repairText.isNotBlank()) {
                    Text(
                        repairText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        DetailCard("占了多少地方") {
            d.dirs.forEach { DirLine(it) }
            Text(
                "合计 " + fmtBytes(d.totalBytes) + "（不含共享的 libraries/ 与 assets/）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 存档放在「删掉这个实例」之前：删实例时默认保不保留存档，得先知道自己有没有存档。
        DetailCard("存档与备份") {
            Text(
                "每个世界都能单独备份成一个 zip；删世界会先自动备份。自动备份的开关与间隔也在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { showSaves = true }) { Text("打开存档…") }
        }

        if (d.warnings.isNotEmpty()) {
            DetailCard("注意") {
                d.warnings.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // 删除放在最下面，且不是那个主按钮：这是唯一会真的删掉磁盘文件的地方，
        // 手滑点到的代价比「多滑一屏」大得多。
        DetailCard("删掉这个实例") {
            Text(
                "会删掉版本目录 " + d.versionDir.absolutePath + " 里的文件，并把 instances.json 里那条登记一起移除。" +
                    "共享的 libraries/ 与 assets/ 不动。删掉之后只能重新下载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { prepareDelete() },
                enabled = !deleting && !saving,
            ) { Text(if (deleting) "正在删除…" else "删除这个实例…") }
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

    deletePlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deletePlan = null },
            title = { Text("删除实例文件？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!plan.deletable) {
                        Text(plan.refusalReason, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(plan.versionDir.absolutePath, style = MaterialTheme.typography.bodySmall)
                        Text(
                            plan.fileCount.toString() + " 个文件，" + fmtBytes(plan.totalBytes) + "。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (plan.hasSaves) {
                            Text(
                                "存档 " + plan.savesFileCount + " 个文件（" + fmtBytes(plan.savesBytes) +
                                    "）默认保留，会搬到 <游戏目录>/platypus/saves-backup/ 下。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = deleteSavesToo,
                                    onCheckedChange = { deleteSavesToo = it },
                                    enabled = !deleting,
                                )
                                Text("连存档一起删掉", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            "会直接从磁盘删掉，不进回收站。删掉之后要重新下载。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                if (plan.deletable) {
                    Button(onClick = { confirmDelete() }, enabled = !deleting) {
                        Text(if (deleting) "删除中…" else "删除")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!deleting) deletePlan = null }, enabled = !deleting) { Text("算了") }
            },
        )
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
