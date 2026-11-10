package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.instance.DeletionPlan
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceDeleter
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.instance.LocalInstance
import io.github.huang404yan.platypus.core.instance.ScanResult
import io.github.huang404yan.platypus.core.instance.formatBytes
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.instance.mergeInstances
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.runtime.findExactMajor
import io.github.huang404yan.platypus.core.install.InstallOutcome
import io.github.huang404yan.platypus.core.install.InstallStep
import io.github.huang404yan.platypus.core.install.InstallStepException
import io.github.huang404yan.platypus.core.install.OneClickInstaller
import io.github.huang404yan.platypus.core.install.StepProgress
import io.github.huang404yan.platypus.core.install.VanillaInstaller
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 新建实例的整体阶段。
 *
 * 只在界面层用，不进 core：它描述的是「界面怎么表现一次安装」，不是业务模型。
 */
private sealed interface NewPhase {
    data object Idle : NewPhase

    data class Running(
        val step: InstallStep,
        val detail: String,
        val done: Long,
        val total: Long,
    ) : NewPhase

    data class Failed(val step: InstallStep, val message: String) : NewPhase

    /** [outcome] 里的 javaInstalled 为 true 表示这次真的从 Mojang 下了一个运行时。 */
    data class Done(val outcome: InstallOutcome) : NewPhase
}

/**
 * 实例页：扫描游戏目录、登记实例、新建实例、改内存。
 *
 * 三种来源在心智上要分开：
 * - **已登记**：本启动器建过或用户点过「登记」，存在 `<游戏目录>/platypus/instances.json`，能改内存；
 * - **仅扫描到**：目录里有 `versions/<id>/<id>.json`，但本启动器没记过，只读；
 * - **新建**：选一个版本走「下载 → 补 Java → 登记」，与下载页「一键安装」是同一条实现。
 */
@Composable
fun InstancePage(
    backend: LauncherBackend?,
    initialGameRoot: String?,
    onLaunch: (Instance) -> Unit = {},
) {
    var rootText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var registered by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var message by remember { mutableStateOf("") }

    var showForm by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<NewPhase>(NewPhase.Idle) }

    var editingKey by remember { mutableStateOf<String?>(null) }
    var editNotice by remember { mutableStateOf("") }

    /** 正在看详情的实例（按 instanceKey 记，切目录后能对上）。null = 列表视图。 */
    var detailKey by remember { mutableStateOf<String?>(null) }

    /** 正在确认删除的实例（null = 没有弹窗）。 */
    var deleteTarget by remember { mutableStateOf<Instance?>(null) }
    /** 弹窗里算出来的删除计划：路径、文件数、字节数、能不能删。 */
    var deletePlan by remember { mutableStateOf<DeletionPlan?>(null) }
    /** 「连存档一起删」勾选框；默认不勾，存档是唯一重新下载不回来的东西。 */
    var deleteSavesToo by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    suspend fun rescan() {
        val text = rootText.trim()
        val scan = withContext(Dispatchers.IO) {
            if (text.isEmpty()) null else InstanceScanner.scan(File(text))
        }
        val saved = withContext(Dispatchers.IO) {
            if (backend == null) {
                emptyList()
            } else {
                runCatching { backend.instances.list() }.getOrDefault(emptyList())
            }
        }
        result = scan
        registered = saved
    }

    /** 把「仅扫描到」的实例登记下来：内存用默认值，Java 找主版本精确匹配的那个。 */
    fun register(instance: Instance) {
        if (backend == null) {
            message = "后端未装配：没法写实例登记。"
            return
        }
        scope.launch {
            val javaPath = detectJavaPathFor(backend, instance.gameVersion)
            val toSave = instance.copy(
                name = instance.name.ifBlank { instance.id },
                javaPath = javaPath,
                memoryMb = Instance.clampMemoryMb(instance.memoryMb),
                registered = true,
            )
            val error = withContext(Dispatchers.IO) {
                runCatching { backend.instances.save(toSave) }.exceptionOrNull()
            }
            if (error != null) {
                message = "登记失败：" + describe(error)
                return@launch
            }
            message = if (javaPath == null) {
                val required = GameJavaRules.requiredMajor(instance.gameVersion)
                "已登记 " + instance.name + "。本机没有 Java " + required +
                    "，去「启动」页点「自动补 Java」现下一个。"
            } else {
                "已登记 " + instance.name + "。"
            }
            rescan()
        }
    }

    fun saveEdit(instance: Instance, memoryText: String) {
        if (backend == null) {
            editNotice = "后端未装配：没法写实例登记。"
            return
        }
        val parsed = memoryText.trim().toIntOrNull()
        if (parsed == null) {
            editNotice = "内存要填整数，单位 MB。"
            return
        }
        val clamped = Instance.clampMemoryMb(parsed)
        val updated = instance.copy(memoryMb = clamped, registered = true)
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { backend.instances.save(updated) }.exceptionOrNull()
            }
            if (error != null) {
                editNotice = "保存失败：" + describe(error)
                return@launch
            }
            editNotice = if (clamped != parsed) {
                "已按允许范围（" + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + " MB）改成 " + clamped + " MB。"
            } else {
                "已保存：" + clamped + " MB。"
            }
            editingKey = null
            rescan()
        }
    }

    fun removeRegistration(instance: Instance) {
        if (backend == null) {
            message = "后端未装配：没法移除实例登记。"
            return
        }
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { backend.instances.remove(instance.id) }.exceptionOrNull()
            }
            if (error != null) {
                message = "移除登记失败：" + describe(error)
                return@launch
            }
            message = "已移除 " + instance.name + " 的登记。文件没删，随时可以再登记回来。"
            rescan()
        }
    }

    /** 点「删除文件…」：只算计划、弹确认框，不动磁盘。 */
    fun prepareDelete(instance: Instance) {
        val dirText = rootText.trim()
        if (dirText.isEmpty()) {
            message = "先在上面填游戏目录，才知道要删的是哪个路径。"
            return
        }
        val gameRoot = File(dirText)
        val known = registered
        // 统计文件数要遍历目录，放 IO 线程；算完才弹窗，弹窗里显示的数字就是真实数字。
        scope.launch {
            val plan = withContext(Dispatchers.IO) {
                InstanceDeleter.inspect(
                    gameRoot = gameRoot,
                    instanceId = instance.id,
                    knownInstances = known,
                )
            }
            deleteTarget = instance
            deletePlan = plan
            deleteSavesToo = false
        }
    }

    fun dismissDelete() {
        deleteTarget = null
        deletePlan = null
        deleteSavesToo = false
    }

    /** 确认框里点「删除」。删完把 instances.json 里的那条登记一并移除。 */
    fun confirmDelete() {
        val plan = deletePlan ?: return
        val instance = deleteTarget ?: return
        if (!plan.deletable) {
            message = plan.refusalReason
            dismissDelete()
            return
        }
        deleting = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                InstanceDeleter.delete(plan, includeSaves = deleteSavesToo)
            }
            deleting = false
            if (!outcome.ok) {
                // 失败时保留弹窗里的关键信息，但先关掉弹窗把原因写进页面提示。
                message = outcome.summary
                dismissDelete()
                return@launch
            }
            // 文件没了，登记也必须跟着没：否则下次扫描会把「已登记却指向不存在的目录」留在列表里。
            val backendRef = backend
            if (backendRef != null) {
                val error = withContext(Dispatchers.IO) {
                    runCatching { backendRef.instances.remove(instance.id) }.exceptionOrNull()
                }
                if (error != null) {
                    message = outcome.summary + " 但移除登记失败：" + describe(error)
                    dismissDelete()
                    rescan()
                    return@launch
                }
            }
            message = outcome.summary +
                if (outcome.savesKept && outcome.savesMovedTo != null) {
                    " 存档保留在 " + outcome.savesMovedTo!!.absolutePath
                } else {
                    ""
                }
            dismissDelete()
            rescan()
        }
    }

    fun createInstance(versionId: String, name: String, memoryText: String) {
        if (backend == null) {
            message = "后端未装配：新建实例需要桌面端注入 Java 与实例存储实现。"
            return
        }
        val dirText = rootText.trim()
        if (dirText.isEmpty()) {
            message = "先填一个游戏目录，比如 D:/mc-test。"
            return
        }
        val id = versionId.trim()
        if (id.isEmpty()) {
            message = "先填一个版本号，比如 1.20.1。"
            return
        }
        val parsed = memoryText.trim().toIntOrNull()
        if (parsed == null) {
            message = "内存要填整数，单位 MB。"
            return
        }
        val memoryMb = Instance.clampMemoryMb(parsed)
        val dir = File(dirText)
        val displayName = name.trim().ifBlank { id }

        phase = NewPhase.Running(InstallStep.PLANNING, "准备中…", 0L, 0L)
        message = ""

        scope.launch {
            try {
                // 版本已经在本地、client jar 也齐了：不联网重下一遍，直接登记。
                // 这条路径让「我别的启动器已经下好了，只是想让鸭嘴兽管起来」不用再等几分钟。
                // 判断本身在 core 的 LocalInstance 里，有单测盯着「缺 jar / 空 jar 不许认」。
                val javaPath = detectJavaPathFor(backend, id)
                val adopted = withContext(Dispatchers.IO) {
                    LocalInstance.adopt(
                        gameRoot = dir,
                        versionId = id,
                        name = displayName,
                        memoryMb = memoryMb,
                        javaPath = javaPath,
                    )
                }
                if (adopted != null) {
                    withContext(Dispatchers.IO) { backend.instances.save(adopted) }
                    phase = NewPhase.Done(
                        InstallOutcome(
                            instance = adopted,
                            versionId = adopted.id,
                            java = JavaRuntime(
                                path = adopted.javaPath.orEmpty(),
                                majorVersion = GameJavaRules.requiredMajor(adopted.gameVersion),
                                managed = false,
                            ),
                            javaInstalled = false,
                            downloadReport = DownloadReport(0, emptyList()),
                            elapsedMs = 0L,
                        ),
                    )
                    message = "本地已经有 " + adopted.id + "，没有联网下载，直接登记成实例。"
                    showForm = false
                    rescan()
                    return@launch
                }

                val manifest = withContext(Dispatchers.IO) {
                    runCatching { VanillaInstaller.loadManifest() }.getOrNull()
                }
                val outcome = OneClickInstaller.install(
                    gameDir = dir,
                    versionId = id,
                    javaRuntimes = backend.javaRuntimes,
                    instances = backend.instances,
                    manifest = manifest,
                    memoryMb = memoryMb,
                    instanceName = displayName,
                ) { step: StepProgress ->
                    phase = NewPhase.Running(step.step, step.detail, step.done, step.total)
                }
                phase = NewPhase.Done(outcome)
                showForm = false
                message = "已建好实例 " + outcome.instance.name + "，用时 " + (outcome.elapsedMs / 1000L) + " 秒。"
                rescan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InstallStepException) {
                phase = NewPhase.Failed(e.step, e.message ?: "未知错误")
            } catch (e: Exception) {
                val step = (phase as? NewPhase.Running)?.step ?: InstallStep.PLANNING
                phase = NewPhase.Failed(step, describe(e))
            }
        }
    }

    LaunchedEffect(Unit) {
        val repository = backend?.settings
        if (repository != null) {
            val stored = withContext(Dispatchers.IO) {
                runCatching { repository.load() }.getOrNull()
            }
            if (stored != null && initialGameRoot.isNullOrBlank() && stored.defaultGameRoot.isNotBlank()) {
                rootText = stored.defaultGameRoot
            }
        }
        if (rootText.isNotBlank()) rescan()
    }

    val busy = phase is NewPhase.Running
    val merged = mergeInstances(registered, result?.instances.orEmpty())
    val scannedOnly = merged.count { !it.registered }

    val detailInstance = detailKey?.let { key -> merged.firstOrNull { instanceKey(it) == key } }
    if (detailInstance != null) {
        InstanceDetailPanel(
            instance = detailInstance,
            backend = backend,
            onBack = { detailKey = null },
            onNotice = { detailKey = null; message = it; scope.launch { rescan() } },
        )
        return
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("实例", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "扫描目录里已有的版本，或新建一个（下载 → 补 Java → 登记）。扫描只读；登记的实例才能改内存。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = rootText,
                onValueChange = { rootText = it },
                modifier = Modifier.weight(1f),
                label = { Text("游戏目录（.minecraft）") },
                singleLine = true,
                enabled = !busy,
            )
            Button(onClick = { scope.launch { rescan() } }, enabled = !busy) { Text("扫描") }
            Button(
                onClick = {
                    showForm = !showForm
                    if (!showForm) phase = NewPhase.Idle
                },
                enabled = !busy && backend != null,
            ) {
                Text(if (showForm) "收起新建" else "新建实例")
            }
        }

        if (showForm) {
            NewInstanceCard(
                gameDir = rootText.trim(),
                busy = busy,
                phase = phase,
                onCancel = {
                    showForm = false
                    phase = NewPhase.Idle
                },
                onSubmit = { versionId, name, memory -> createInstance(versionId, name, memory) },
            )
        } else if (phase is NewPhase.Done) {
            val done = phase as NewPhase.Done
            Hint("已建好：" + done.outcome.instance.name + "（" + done.outcome.instance.memoryMb + " MB）")
        }

        if (message.isNotBlank()) Hint(message)

        val current = result
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                current == null && merged.isEmpty() -> Hint("还没有扫描。填入目录后点「扫描」。")

                current != null && !current.isGameDirectory && merged.isEmpty() -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Hint("这个目录看起来不是游戏目录。")
                        current.warnings.forEach { WarningLine(it) }
                    }
                }

                merged.isEmpty() -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Hint("没有识别到版本。点「新建实例」装一个，或检查 versions/ 下每个版本是否都有同名 .json。")
                        current?.warnings.orEmpty().forEach { WarningLine(it) }
                    }
                }

                else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val warningCount = current?.warnings.orEmpty().size
                    Text(
                        "找到 " + merged.size + " 个实例（已登记 " + registered.size + " · 仅扫描到 " + scannedOnly + "）" +
                            if (warningCount == 0) "" else "，" + warningCount + " 条提示",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    current?.warnings.orEmpty().forEach { WarningLine(it) }
                    if (editNotice.isNotBlank()) Hint(editNotice)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(merged, key = { instanceKey(it) }) { instance ->
                            InstanceCard(
                                instance = instance,
                                editing = editingKey == instanceKey(instance),
                                onStartEdit = {
                                    editingKey = instanceKey(instance)
                                    editNotice = ""
                                },
                                onCancelEdit = {
                                    editingKey = null
                                    editNotice = ""
                                },
                                onSaveEdit = { text -> saveEdit(instance, text) },
                                onRegister = { register(instance) },
                                onLaunch = { onLaunch(instance) },
                                onRemove = { removeRegistration(instance) },
                                onDelete = { prepareDelete(instance) },
                                onDetails = { detailKey = instanceKey(instance) },
                                enabled = !busy && !deleting,
                            )
                        }
                    }
                }
            }
        }
    }

    val pendingDelete = deletePlan
    val pendingTarget = deleteTarget
    if (pendingDelete != null && pendingTarget != null) {
        DeleteConfirmDialog(
            instance = pendingTarget,
            plan = pendingDelete,
            savesToo = deleteSavesToo,
            onSavesTooChange = { deleteSavesToo = it },
            deleting = deleting,
            onDismiss = { if (!deleting) dismissDelete() },
            onConfirm = { confirmDelete() },
        )
    }
}

/**
 * 删除确认框。
 *
 * 三条信息必须在动手之前摆出来：删的是哪个**绝对路径**、多少文件多大、
 * 存档留不留。前两条让用户确认「没删错地方」，第三条是因为存档是唯一
 * 重新下载不回来的东西，默认不删。
 */
@Composable
private fun DeleteConfirmDialog(
    instance: Instance,
    plan: DeletionPlan,
    savesToo: Boolean,
    onSavesTooChange: (Boolean) -> Unit,
    deleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("删除实例文件？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(instance.name.ifBlank { instance.id }, fontWeight = FontWeight.SemiBold)
                Text(
                    plan.versionDir.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!plan.deletable) {
                    // 拒绝理由直接照抄 core 的文案：这里是「为什么不能删」的唯一出口，不再改写一遍。
                    Text(plan.refusalReason, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(plan.fileCount.toString() + " 个文件，" + formatBytes(plan.totalBytes))
                    if (plan.hasSaves) {
                        Text(
                            "存档 " + plan.savesFileCount + " 个文件，" + formatBytes(plan.savesBytes) +
                                if (savesToo) "，会一起删掉。" else "，会搬到 platypus/saves-backup/ 保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = savesToo,
                                onCheckedChange = { onSavesTooChange(it) },
                                enabled = !deleting,
                            )
                            Text("连存档一起删（不可恢复）")
                        }
                    }
                    if (plan.hasMods) {
                        Text("目录里有 mods/。", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "文件会先改名为 " + plan.versionDir.name + ".deleting-<时间戳> 再逐个删除；" +
                            "中途失败会自动改回原名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = plan.deletable && !deleting) {
                Text(if (deleting) "删除中…" else "删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text("取消") }
        },
    )
}

/** 新建实例的内联表单：版本号 + 实例名 + 内存，都在这一页完成。 */
@Composable
private fun NewInstanceCard(
    gameDir: String,
    busy: Boolean,
    phase: NewPhase,
    onCancel: () -> Unit,
    onSubmit: (versionId: String, name: String, memoryText: String) -> Unit,
) {
    var versionText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var memoryText by remember { mutableStateOf(Instance.DEFAULT_MEMORY_MB.toString()) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("新建实例", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                if (gameDir.isBlank()) "先在上面填游戏目录。" else "装到 " + gameDir,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = versionText,
                    onValueChange = { versionText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("版本号（如 1.20.1）") },
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("实例名（留空用版本号）") },
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = memoryText,
                    onValueChange = { memoryText = it },
                    modifier = Modifier.weight(0.7f),
                    label = { Text("内存 MB") },
                    singleLine = true,
                    enabled = !busy,
                )
            }
            Text(
                "会下载原版客户端、库与资源，按需补 Java 运行时，最后登记成实例。本地已有该版本时不会重下。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (phase) {
                is NewPhase.Running -> {
                    val fraction = if (phase.total <= 0L) 0f else (phase.done.toDouble() / phase.total).toFloat()
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ProgressBar(fraction)
                        Text(
                            describeStep(phase.step) +
                                (if (phase.detail.isBlank()) "" else " · " + phase.detail) +
                                (if (phase.total <= 0L) "" else " · " + (fraction * 100f).toInt() + "%"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is NewPhase.Failed -> Text(
                    "卡在「" + describeStep(phase.step) + "」：" + phase.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                is NewPhase.Done -> Text(
                    "完成：" + phase.outcome.instance.name +
                        "，Java " + phase.outcome.java.majorVersion +
                        (if (phase.outcome.javaInstalled) "（这次新下的）" else "（本机已有）"),
                    style = MaterialTheme.typography.bodySmall,
                )

                NewPhase.Idle -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onSubmit(versionText, nameText, memoryText) },
                    enabled = !busy && gameDir.isNotBlank() && versionText.isNotBlank(),
                ) {
                    Text(if (busy) "正在创建…" else "创建")
                }
                if (busy) {
                    OutlinedButton(onClick = onCancel) { Text("收起") }
                }
            }
        }
    }
}

/** 单个实例卡片：左半边是信息，右半边是动作；编辑态把动作行换成内存输入框。 */
@Composable
private fun InstanceCard(
    instance: Instance,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: (String) -> Unit,
    onRegister: () -> Unit,
    onLaunch: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    enabled: Boolean,
) {
    var memoryText by remember(instance.memoryMb, editing) { mutableStateOf(instance.memoryMb.toString()) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(instance.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "游戏 " + instance.gameVersion +
                            (instance.loaderVersion?.let { " · 加载器 " + it } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        instance.directory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        instance.memoryMb.toString() + " MB" + if (instance.registered) " · 已登记" else " · 仅扫描到",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val java = instance.javaPath
                    if (!java.isNullOrBlank()) {
                        Text(
                            "Java：" + java,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LoaderBadge(instance.loader)
            }

            if (editing) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = memoryText,
                        onValueChange = { memoryText = it },
                        modifier = Modifier.weight(1f),
                        label = {
                            Text(
                                "最大内存（MB，范围 " + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + "）",
                            )
                        },
                        singleLine = true,
                    )
                    Button(onClick = { onSaveEdit(memoryText) }) { Text("保存") }
                    OutlinedButton(onClick = onCancelEdit) { Text("取消") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onLaunch, enabled = enabled) { Text("去启动") }
                    if (instance.registered) {
                        OutlinedButton(onClick = onStartEdit, enabled = enabled) { Text("改内存") }
                        OutlinedButton(onClick = onRemove, enabled = enabled) { Text("移除登记") }
                    } else {
                        OutlinedButton(onClick = onStartEdit, enabled = enabled) { Text("改内存") }
                    }
                    // 删除文件对「已登记」和「仅扫描到」都开放：用户清理磁盘时不关心当初是谁登记的。
                    OutlinedButton(onClick = onDetails, enabled = enabled) { Text("详情") }
                    OutlinedButton(onClick = onDelete, enabled = enabled) { Text("删除文件…") }
                    if (!instance.registered) {
                        OutlinedButton(onClick = onRegister, enabled = enabled) { Text("登记成实例") }
                    }
                }
            }
        }
    }
}

/** 探测一次本机 Java，挑主版本精确匹配的那个；没有就返回 null，由界面提示去补。 */
private suspend fun detectJavaPathFor(backend: LauncherBackend, gameVersion: String): String? =
    withContext(Dispatchers.IO) {
        val required = GameJavaRules.requiredMajor(gameVersion)
        val runtimes = runCatching { backend.javaRuntimes.detect() }.getOrDefault(emptyList())
        runtimes.findExactMajor(required)?.path
    }

private fun describeStep(step: InstallStep): String = when (step) {
    InstallStep.IDLE -> "准备中"
    InstallStep.PLANNING -> "解析版本元数据"
    InstallStep.DOWNLOADING -> "下载游戏文件"
    InstallStep.JAVA_CHECK -> "检查 Java 运行时"
    InstallStep.JAVA_INSTALL -> "下载 Java 运行时"
    InstallStep.LOADER_INSTALL -> "安装 Fabric 加载器"
    InstallStep.REGISTERING -> "登记实例"
    InstallStep.DONE -> "完成"
}

private fun describe(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: (error::class.simpleName ?: "未知错误")

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
private fun LoaderBadge(loader: LoaderKind) {
    val (label, color) = when (loader) {
        LoaderKind.VANILLA -> "原版" to MaterialTheme.colorScheme.secondaryContainer
        LoaderKind.FORGE -> "Forge" to MaterialTheme.colorScheme.tertiaryContainer
        LoaderKind.NEOFORGE -> "NeoForge" to MaterialTheme.colorScheme.tertiaryContainer
        LoaderKind.FABRIC -> "Fabric" to MaterialTheme.colorScheme.primaryContainer
        LoaderKind.QUILT -> "Quilt" to MaterialTheme.colorScheme.primaryContainer
        LoaderKind.OPTIFINE -> "OptiFine" to MaterialTheme.colorScheme.secondaryContainer
        LoaderKind.OTHER -> "其他" to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WarningLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
