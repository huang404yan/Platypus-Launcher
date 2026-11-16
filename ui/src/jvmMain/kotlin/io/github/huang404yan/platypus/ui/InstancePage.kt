package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.instance.DeletionPlan
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceDeleter
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.instance.LocalInstance
import io.github.huang404yan.platypus.core.instance.MemoryDefaults
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstancePage(
    backend: LauncherBackend?,
    initialGameRoot: String?,
    onBack: () -> Unit = {},
    onLaunch: (Instance) -> Unit = {},
    onOpenSetup: (Instance) -> Unit = {},
) {
    var rootText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    // 「设置 → 启动 → 默认内存」的那个数。新建表单的滑条从它起步 ——
    // 以前表单写死 Instance.DEFAULT_MEMORY_MB，设置里改了默认值对新实例根本不生效。
    var defaultMemoryMb by remember { mutableStateOf(Instance.DEFAULT_MEMORY_MB) }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var registered by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var message by remember { mutableStateOf("") }

    var showForm by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<NewPhase>(NewPhase.Idle) }

    var editNotice by remember { mutableStateOf("") }

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

    /**
     * 把卡片上滑条选的内存写进实例。滑条只会给出档位里的整数，越界由 clampMemoryMb 兜底。
     *
     * 2026-11-16：卡片的「编辑态 + 手填 MB + 保存/取消」换成了常驻的 [MemorySlider]，
     * 所以这里不再需要 editingKey 那套进出编辑的流程 —— 点档位就直接落盘。
     */
    fun saveMemoryMb(instance: Instance, memoryMb: Int) {
        if (backend == null) {
            editNotice = "后端未装配：没法写实例登记。"
            return
        }
        val clamped = Instance.clampMemoryMb(memoryMb)
        // 与启动页 saveMemory、详情页 save 同一条口径：拖回「跟随默认」的那个数就继续跟随，
        // 拖到别处就脱离跟随。三处各判一套的话，同一个数在不同页面会得到不同的落盘结果。
        val following = instance.memoryFollowsDefault &&
            clamped == Instance.effectiveMemoryMb(instance, defaultMemoryMb)
        val updated = instance.copy(
            memoryMb = clamped,
            registered = true,
            memoryFollowsDefault = following,
        )
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { backend.instances.save(updated) }.exceptionOrNull()
            }
            if (error != null) {
                editNotice = "保存失败：" + describe(error)
                return@launch
            }
            editNotice = if (following) {
                "已保存：" + clamped + " MB（跟随设置里的默认内存）。"
            } else {
                "已保存：" + clamped + " MB。"
            }
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

    fun createInstance(versionId: String, name: String, memoryMbInput: Int) {
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
        val memoryMb = Instance.clampMemoryMb(memoryMbInput)
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
            if (stored != null) {
                defaultMemoryMb = MemoryDefaults.resolve(stored.defaultMemoryMb, stored.defaultMemoryAuto, SystemMemory.totalMb)
            }
        }
        if (rootText.isNotBlank()) rescan()
    }

    val busy = phase is NewPhase.Running
    val merged = mergeInstances(registered, result?.instances.orEmpty())
    val scannedOnly = merged.count { !it.registered }

    // 整页滚动：默认窗口里卡片一多，下面几张就被切在窗口外，用户得先最大化才看得全。
    // 这里不用「固定头 + weight(1f) 列表」是因为顶部那几行（目录、新建卡片、提示）
    // 在窄窗口下自己就会占掉大半屏，权重分完列表只剩几十像素，滚轮落在列表外就没反应。
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 这个页面现在是启动页压上来的副页面（PCL 的「版本选择」），
        // 所以第一行是返回而不是标题 —— 用户是从哪儿进来的要能原路回去。
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onBack, enabled = !busy) { Text("← 返回") }
            Text("版本选择", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "挑一个版本回启动页。扫描目录里已有的版本，或新建一个（下载 → 补 Java → 登记）；扫描只读，登记的实例才能改内存。",
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
            OutlinedButton(
                onClick = {
                    pickDirectory(rootText)?.let {
                        rootText = it.absolutePath
                        scope.launch { rescan() }
                    }
                },
                enabled = !busy,
            ) { Text("选择…") }
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
                defaultMemoryMb = defaultMemoryMb,
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
        Box(Modifier.fillMaxWidth()) {
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

                else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val warningCount = current?.warnings.orEmpty().size
                    Text(
                        "找到 " + merged.size + " 个实例（已登记 " + registered.size + " · 仅扫描到 " + scannedOnly + "）" +
                            if (warningCount == 0) "" else "，" + warningCount + " 条提示",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    current?.warnings.orEmpty().forEach { WarningLine(it) }
                    if (editNotice.isNotBlank()) Hint(editNotice)
                    // 实例是个位数到十几的量级：整段铺开、交给外层滚动，
                    // 比 LazyColumn 再套一层滚动更可预期（滚轮只作用在一个方向上）。
                    // 卡片网格：版本是“我盘上有几个”的东西，一屏看得到比一条条往下读快。
                    // 用 FlowRow 而不是 LazyVerticalGrid：外层已经是整页滚动，
                    // 嵌一个自带滚动的网格会出现两层滚轮。
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                    merged.forEach { instance ->
                        key(instanceKey(instance)) {
                            InstanceCard(
                                instance = instance,
                                // 跟随默认内存的实例，卡片上显示与滑条都用生效值（见 Instance.effectiveMemoryMb）。
                                effectiveMemoryMb = Instance.effectiveMemoryMb(instance, defaultMemoryMb),
                                onMemoryChange = { picked -> saveMemoryMb(instance, picked) },
                                onRegister = { register(instance) },
                                onLaunch = { onLaunch(instance) },
                                onRemove = { removeRegistration(instance) },
                                onDelete = { prepareDelete(instance) },
                                onDetails = { onOpenSetup(instance) },
                                enabled = !busy && !deleting,
                            )
                        }
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
    /** 表单初值：设置页的「默认内存」。 */
    defaultMemoryMb: Int,
    busy: Boolean,
    phase: NewPhase,
    onCancel: () -> Unit,
    onSubmit: (versionId: String, name: String, memoryMb: Int) -> Unit,
) {
    var versionText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var memoryMb by remember(defaultMemoryMb) { mutableStateOf(Instance.clampMemoryMb(defaultMemoryMb)) }

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
            }
            // 内存也走滑条：三个输入框并排时那一格只有 100dp 宽，写「32768」会被截断。
            MemorySlider(
                valueMb = memoryMb,
                onChange = { memoryMb = it },
                enabled = !busy,
                showHint = false,
            )
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
                    onClick = { onSubmit(versionText, nameText, memoryMb) },
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

/**
 * 单个实例卡片：上面是信息，下面是动作，最底下一条内存滑条。
 *
 * 尺寸（2026-11-16 定）：宽 336dp、高 340dp 固定。宽度本来各卡就一样，出现「大小不一致」
 * 的是高度 —— 实例名长短、目录深浅、有没有 Java 那一行都会改内容高度，FlowRow 于是排成
 * 参差不齐的瀑布。这里把高度写死，信息区吃 weight(1f)（多出来的部分截断），
 * 动作行与内存滑条贴着底部，于是同一行的卡片必然同高同宽。
 */
@Composable
private fun InstanceCard(
    modifier: Modifier = Modifier,
    instance: Instance,
    /** 这一版实际给多少内存：跟随默认时是设置页那个数，否则等于 instance.memoryMb。 */
    effectiveMemoryMb: Int,
    onMemoryChange: (Int) -> Unit,
    /** 「详情」= 版本设置：内存、JVM 参数、Mod / 资源包 / 光影、隔离、导出都在那里。 */
    onDetails: () -> Unit,
    onRegister: () -> Unit,
    onLaunch: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    Card(
        modifier.width(336.dp).height(340.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        instance.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "游戏 " + instance.gameVersion +
                            (instance.loaderVersion?.let { " · 加载器 " + it } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        instance.directory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        effectiveMemoryMb.toString() + " MB" +
                            (if (instance.memoryFollowsDefault) " · 跟随默认" else "") +
                            if (instance.registered) " · 已登记" else " · 仅扫描到",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val java = instance.javaPath
                    if (!java.isNullOrBlank()) {
                        Text(
                            "Java：" + java,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                LoaderBadge(instance.loader)
            }

            // 动作按钮用 FlowRow 折行：卡片宽度固定而按钮文字随「已登记 / 仅扫描到」变化，
            // 用 Row 会在窄卡上把最后一个按钮挤出边界。
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = onLaunch, enabled = enabled) { Text("去启动") }
                OutlinedButton(onClick = onDetails, enabled = enabled) { Text("详情") }
                // 删除文件对「已登记」和「仅扫描到」都开放：用户清理磁盘时不关心当初是谁登记的。
                OutlinedButton(onClick = onDelete, enabled = enabled) { Text("删除文件…") }
                if (instance.registered) {
                    OutlinedButton(onClick = onRemove, enabled = enabled) { Text("移除登记") }
                } else {
                    OutlinedButton(onClick = onRegister, enabled = enabled) { Text("登记成实例") }
                }
            }

            // 内存滑条常驻在卡片底部：以前要先点「改内存」进编辑态才能填数，
            // 现在直接拖或点档位就落盘（见调用方的 saveMemoryMb）。
            MemorySlider(
                valueMb = effectiveMemoryMb,
                onChange = onMemoryChange,
                enabled = enabled,
                // 卡片里只留滑条与数值：十个档位按钮在这儿要折三四行，
                // 卡片就得跟着长高，一屏里的卡片大小就不齐了。要跳档去「详情」里点。
                showPresets = false,
                showHint = false,
            )
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
