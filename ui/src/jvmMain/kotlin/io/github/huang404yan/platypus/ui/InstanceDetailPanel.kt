package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.InstanceMigration
import io.github.huang404yan.platypus.core.instance.MemoryDefaults
import io.github.huang404yan.platypus.core.instance.formatBytes
import io.github.huang404yan.platypus.core.packs.PackEntry
import io.github.huang404yan.platypus.core.packs.PackFileOps
import io.github.huang404yan.platypus.core.packs.PackKind
import io.github.huang404yan.platypus.core.packs.PackScanResult
import io.github.huang404yan.platypus.core.packs.PackScanner
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.runtime.findExactMajor
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
/**
 * 版本设置左栏的七个分区。
 *
 * 照 PCL 的 PageSetup（总览 / 启动 / 界面 / 其他）拆，但拆的依据是「玩家会为了改这件事而来找这个版本」：装模组、给模组分配内存、换 Java、解决存档找不到。
 */
internal const val SETUP_OVERALL = "overall"
internal const val SETUP_LAUNCH = "launch"
internal const val SETUP_MODS = "mods"
internal const val SETUP_PACKS = "packs"
internal const val SETUP_SAVES = "saves"
internal const val SETUP_ADV = "adv"
internal const val SETUP_EXPORT = "export"

/** 左栏顺序即展示顺序。 */
internal val SETUP_SECTIONS = listOf(
    SETUP_OVERALL to "总览",
    SETUP_LAUNCH to "启动与 Java",
    SETUP_MODS to "Mod",
    SETUP_PACKS to "资源包与光影",
    SETUP_SAVES to "存档与备份",
    SETUP_ADV to "高级",
    SETUP_EXPORT to "导出整合包",
)

/** 详情页里「要删的东西属于哪一类」。和 pendingDelete 一起用，只在这个文件里有意义（模组的删除已搬进 InstalledModsSection）。 */
private const val KIND_RESOURCE_PACK = "resourcepack"
private const val KIND_SHADER = "shader"

@Composable
internal fun InstanceDetailPanel(
    instance: Instance,
    backend: LauncherBackend?,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
    /**
     * 跳到「下载」页的某个子标签（传 Navigation 里的 DOWNLOAD_TAB_* 常量）。
     *
     * 这一页只负责「这个版本装了什么、要不要关掉」；找新的模组 / 光影是下载页的事，
     * 所以这里只给一个跳板，不在详情页里再搓一个搜索框 —— 那边的筛选会读这个版本的
     * 加载器与游戏版本，重复实现一遍必然两边不一致。
     */
    onOpenDownload: (String) -> Unit = {},
    /**
     * 这个实例的文件删干净之后叫一声，让调用方把详情页关掉、回列表重扫。
     *
     * 不给默认实现的话，删完详情页还停在一个已经不存在的目录上，
     * 用户点「保存」会写回一条指向空路径的登记。
     */
    onDeleted: () -> Unit = {},
) {
    var details by remember(instance.directory) { mutableStateOf<InstanceDetails?>(null) }
    var loading by remember(instance.directory) { mutableStateOf(true) }
    var runtimes by remember(instance.directory) { mutableStateOf<List<JavaRuntime>>(emptyList()) }

    var javaPath by remember(instance.directory, instance.javaPath) { mutableStateOf(instance.javaPath.orEmpty()) }
    // 内存只留一个整数 —— 界面上是滑条选档，没有手打字符串这一步了，
    // 保留字符串会多出一条「解析失败」的分支，而滑条永远不会给出非数字。
    var memoryMb by remember(instance.directory, instance.memoryMb) { mutableStateOf(instance.memoryMb) }
    // 设置页的「默认内存」。勾了「跟随默认」的实例，滑条上显示的、真正进 -Xmx 的都是这个数
    // （规则见 Instance.effectiveMemoryMb）；它异步读到，所以下面还有一个 LaunchedEffect 回填。
    var defaultMemoryMb by remember { mutableStateOf(Instance.DEFAULT_MEMORY_MB) }
    var jvmArgsText by remember(instance.directory) { mutableStateOf(instance.extraJvmArgs.joinToString("\n")) }
    // 版本隔离：关掉之后 --gameDir 指回游戏根目录，存档/模组/配置与同目录下别的版本共用。
    // 只有标准布局（versions/<id>）才给这个开关，别的目录关不关都是同一个地方。
    val canToggleIsolation = remember(instance.directory) {
        InstanceLayouts.supportsIsolation(File(instance.directory.trim().trim('"')))
    }
    var isolated by remember(instance.directory, instance.isolated) { mutableStateOf(instance.isolated) }
    // 「跟随设置里的默认」开着时，上面那个数不生效，真正用的是这里读到的全局默认值。
    var isolatedFollowsDefault by remember(instance.directory, instance.isolatedFollowsDefault) {
        mutableStateOf(instance.isolatedFollowsDefault)
    }
    var defaultIsolation by remember { mutableStateOf(InstanceLayouts.defaultIsolated) }

    var notice by remember(instance.directory) { mutableStateOf("") }
    var saving by remember(instance.directory) { mutableStateOf(false) }

    LaunchedEffect(instance.directory) {
        val repository = backend?.settings ?: return@LaunchedEffect
        val stored = withContext(Dispatchers.IO) { runCatching { repository.load() }.getOrNull() }
        if (stored != null) {
            val fallback = MemoryDefaults.resolve(stored.defaultMemoryMb, stored.defaultMemoryAuto, SystemMemory.totalMb)
            defaultMemoryMb = fallback
            defaultIsolation = stored.defaultIsolation
            // 进程级那份也顺手对齐：这一页可能比别处先读到设置（比如从冷启动直接进版本设置）。
            InstanceLayouts.defaultIsolated = stored.defaultIsolation
            // 跟随默认的实例：滑条初值要显示生效的那个数，而不是 instances.json 里存的老数。
            if (instance.memoryFollowsDefault) memoryMb = fallback
        }
    }
    //
    // 实例目录里还留着多少东西没搬到游戏根目录。只在「开关拨到关」时才需要看 ——
    // 开着隔离时那份东西正是游戏在用的，谈不上「没搬」。
    var pendingDirs by remember(instance.directory) { mutableStateOf<List<String>>(emptyList()) }
    var merging by remember(instance.directory) { mutableStateOf(false) }

    // 导出整合包与存档管理都是详情页的「子页」：点进去、做完、返回。
    // 不做成独立导航项 —— 这两件事永远是从某个具体实例出发的，脱离实例没有意义。
    var exporting by remember(instance.directory) { mutableStateOf(false) }
    var browsingSaves by remember(instance.directory) { mutableStateOf(false) }
    // 左栏当前分区。默认「总览」：用户从启动页点「版本设置」进来，先要看的是「这是个什么版本」。
    var section by remember(instance.directory) { mutableStateOf(SETUP_OVERALL) }

    // 这个版本目录里真实存在的模组 / 资源包 / 光影。
    // 直接用 core 的扫描结果（不是裸文件名表）：这一页要能直接启用、停用、删掉它们，
    // 扫描结果里已经带着「文件名 → 启用与否」和包元数据，界面不用自己再拼一遍。
    var packScan by remember(instance.directory) { mutableStateOf<PackScanResult?>(null) }
    var shaderScan by remember(instance.directory) { mutableStateOf<PackScanResult?>(null) }
    // 删除前问一句。值是「哪种东西」+「哪个文件名」，两者一起才够删。
    var pendingDelete by remember(instance.directory) { mutableStateOf<Pair<String, String>?>(null) }

    // 删整个实例：先算出「删哪个目录、多少文件、存档留不留」（InstanceDeleter.inspect），
    // 用户确认后才动手。与实例列表页的删除是同一套 core 逻辑，不另写一份。
    var instancePlan by remember(instance.directory) { mutableStateOf<DeletionPlan?>(null) }
    var instanceSavesToo by remember(instance.directory) { mutableStateOf(false) }
    var deletingInstance by remember(instance.directory) { mutableStateOf(false) }

    // 完整性检查。「对照清单」是只读的本地核对（不看网络），「补齐」才会联网下那几个文件。
    // 两者分开是因为检查一次可能要算几 GB 的 sha1：让它顺便把缺的补上是两件不同的事，
    // 用户点「检查」时不该有任何写盘动作。
    var integrity by remember(instance.directory) { mutableStateOf<IntegrityReport?>(null) }
    var integrityChecking by remember(instance.directory) { mutableStateOf(false) }
    // 已查完的文件数 / 总数。从 IO 线程写，Compose 的快照系统允许，界面下一帧就能读到。
    var integrityProgress by remember(instance.directory) { mutableStateOf(0 to 0) }
    var repairing by remember(instance.directory) { mutableStateOf(false) }
    var repairText by remember(instance.directory) { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    /**
     * 重扫这个版本目录里的模组、资源包、光影。
     *
     * 三种东西都走 core 的扫描器（和「下载」页同一套规则）：停用是改文件名不是删文件，
     * 所以扫出来的「启用 / 停用」就是文件名的真实状态，界面不用另存一份开关。
     */
    suspend fun rescanContent(baseDir: File) {
        packScan = withContext(Dispatchers.IO) {
            runCatching { PackScanner.scan(PackKind.RESOURCE_PACK, baseDir) }.getOrNull()
        }
        shaderScan = withContext(Dispatchers.IO) {
            runCatching { PackScanner.scan(PackKind.SHADER, baseDir) }.getOrNull()
        }
    }

    LaunchedEffect(instance.directory) {
        loading = true
        try {
            details = withContext(Dispatchers.IO) { InstanceDetailsLoader.load(instance) }
            runtimes = withContext(Dispatchers.IO) {
                runCatching { backend?.javaRuntimes?.detect() }.getOrNull().orEmpty()
            }
            val baseDir = details?.gameDir
            if (baseDir != null) {
                rescanContent(baseDir)
            }
        } finally {
            loading = false
        }
    }

    // 开关拨到「关」的时候算一次：实例目录里有什么是游戏马上就会看不见的。
    // 放在 LaunchedEffect 而不是渲染里算，因为要 listFiles() 走一遍盘。
    LaunchedEffect(instance.directory, isolated, isolatedFollowsDefault, defaultIsolation) {
        val effectiveIsolation = if (isolatedFollowsDefault) defaultIsolation else isolated
        pendingDirs = if (effectiveIsolation) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    InstanceMigration.pending(File(instance.directory.trim().trim('"')))
                }.getOrDefault(emptyList())
            }
        }
    }

    /**
     * 把实例目录里的存档/模组/配置复制到游戏根目录。
     *
     * 复制而不是移动：用户可能只是关掉隔离试两天，原件留在原地才能随时开回去。
     */
    fun mergeToRoot() {
        merging = true
        notice = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    InstanceMigration.merge(File(instance.directory.trim().trim('"')))
                }
            }
            merging = false
            val merged = result.getOrNull()
            if (merged == null) {
                notice = "合并失败：" + (result.exceptionOrNull()?.message ?: "未知原因")
                return@launch
            }
            // 合并完再算一次：这时列表应当空了，按钮也就该消失了。
            pendingDirs = withContext(Dispatchers.IO) {
                runCatching {
                    InstanceMigration.pending(File(instance.directory.trim().trim('"')))
                }.getOrDefault(emptyList())
            }
            notice = when {
                merged.total == 0 -> "实例目录里没有需要合并的东西。"
                merged.failed > 0 -> "合并了一部分：复制 " + merged.copied + " 个，跳过 " +
                    merged.skipped + " 个（同名），失败 " + merged.failed + " 个。失败的写进了日志以外的清单，" +
                    "可以手动把实例目录里那些文件拷过去。"
                merged.skipped > 0 -> "合并完成：复制 " + merged.copied + " 个，跳过 " + merged.skipped +
                    " 个同名文件（游戏根目录里已经有的没覆盖）。"
                else -> "合并完成：复制了 " + merged.copied + " 个文件到 " + merged.toDir.absolutePath + "。"
            }
        }
    }

    /** 资源包 / 光影启用 / 停用（.zip ↔ .zip.disabled）。 */
    fun togglePack(kind: PackKind, entry: PackEntry, enabled: Boolean) {
        val baseDir = details?.gameDir ?: return
        notice = ""
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { PackFileOps.setEnabled(PackScanner.dirOf(kind, baseDir), entry.fileName, enabled) }
                    .exceptionOrNull()
            }
            notice = if (error == null) {
                (if (enabled) "已启用 " else "已停用 ") + entry.fileName
            } else {
                "改名失败：" + (error.message ?: error.toString())
            }
            rescanContent(baseDir)
        }
    }

    /**
     * 删除一个模组 / 资源包 / 光影。
     *
     * 走 core 的 delete —— 它用的是 File.delete()，**不进回收站**，所以调用前必须过确认框。
     * 想「先不用」的话用启用/停用（改文件名），那才是可逆的。
     */
    fun deleteContent(kind: String, fileName: String) {
        val baseDir = details?.gameDir ?: return
        notice = ""
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    when (kind) {
                        KIND_SHADER -> PackFileOps.delete(PackScanner.dirOf(PackKind.SHADER, baseDir), fileName)
                        else -> PackFileOps.delete(PackScanner.dirOf(PackKind.RESOURCE_PACK, baseDir), fileName)
                    }
                }.exceptionOrNull()
            }
            notice = if (error == null) "已删除 " + fileName else "删除失败：" + (error.message ?: error.toString())
            rescanContent(baseDir)
        }
    }

    /**
     * 删整个实例：先只算计划（要遍历目录数文件，放 IO），算完弹确认框。
     *
     * 和实例列表页的删除按钮走同一套 [InstanceDeleter]：越界、目录不存在、
     * 被别的登记共用同一个目录，这三种拒绝理由都由 core 给，界面不重写一遍。
     */
    fun prepareDeleteInstance() {
        val gameRoot = details?.gameRoot ?: return
        notice = ""
        deletingInstance = true
        scope.launch {
            val todos = runCatching { backend?.instances?.list() }.getOrNull().orEmpty()
            val plan = withContext(Dispatchers.IO) {
                InstanceDeleter.inspect(
                    gameRoot = gameRoot,
                    instanceId = instance.id,
                    knownInstances = todos,
                )
            }
            deletingInstance = false
            instanceSavesToo = false
            instancePlan = plan
        }
    }

    /** 确认框里点「删除」：删文件 → 摘登记 → 告诉调用方回列表重扫。 */
    fun confirmDeleteInstance() {
        val plan = instancePlan ?: return
        if (backend == null) {
            notice = "后端未装配：删不了实例。"
            instancePlan = null
            return
        }
        if (!plan.deletable) {
            notice = plan.refusalReason
            instancePlan = null
            return
        }
        deletingInstance = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                InstanceDeleter.delete(plan, includeSaves = instanceSavesToo)
            }
            if (!outcome.ok) {
                deletingInstance = false
                instancePlan = null
                notice = outcome.summary
                return@launch
            }
            // 文件没了，登记也必须跟着没，否则下次扫描会留下一条指向空路径的实例。
            val error = withContext(Dispatchers.IO) {
                runCatching { backend.instances.remove(instance.id) }.exceptionOrNull()
            }
            deletingInstance = false
            instancePlan = null
            if (error != null) {
                notice = outcome.summary + " 但移除登记失败：" + (error.message ?: error.toString())
                return@launch
            }
            onNotice(outcome.summary)
            onDeleted()
        }
    }

    /**
     * 按版本清单对一遍这个实例的文件。
     *
     * 只在本地读：拿 version json（含 inheritsFrom 合并）算出「应该有哪些文件」，
     * 再逐个比大小与 sha1。不联网、不写盘，所以随时可以点。
     *
     * @param verifyHashes false 时只比大小，几秒钟能过几万个文件，但同样大小的坏文件发现不了。
     */
    fun checkIntegrity(verifyHashes: Boolean) {
        if (integrityChecking || repairing) return
        notice = ""
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
                        warnings = listOf("检查失败：" + (error.message ?: error.toString())),
                    )
                }
            }
            integrity = report
            integrityChecking = false
        }
    }

    /**
     * 把上一步查出来的问题文件补回来。
     *
     * 交给 core 的下载器：它按 sha1 判断要不要重下，坏文件整份替换，好的一个字节都不碰。
     * 补完立刻再查一遍（用与上次相同的方式），让界面上的数字是真的变好，而不是「应该好了」。
     */
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
                            formatBytes(progress.completedBytes) + " / " + formatBytes(progress.totalBytes)
                    }
                }
            }
            val failure = outcome.exceptionOrNull()
            repairing = false
            if (failure != null) {
                repairText = "补齐失败：" + (failure.message ?: failure.toString())
                return@launch
            }
            val result = outcome.getOrNull()
            repairText = if (result == null || result.ok) {
                "补完了，正在复核…"
            } else {
                "补完了，但还有 " + result.failures.size + " 个文件没下来（网络或镜像的问题），复核一遍看看。"
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
                memoryMb.toString(),
                jvmArgsText,
                javaPath,
                isolated = if (canToggleIsolation) isolated else null,
                isolatedFollowsDefault = if (canToggleIsolation) isolatedFollowsDefault else null,
            )
        ) {
            is InstanceEditResult.Invalid -> {
                notice = edited.message
                return
            }
            is InstanceEditResult.Ok -> edited
        }
        // 滑条上的数和「跟随默认」时的生效值一致 → 继续保持跟随；拖到别处才脱离。
        // 与启动页 saveMemory 同一条口径，两边不能各判一套。
        val following = instance.memoryFollowsDefault &&
            memoryMb == Instance.effectiveMemoryMb(instance, defaultMemoryMb)
        val updated = result.instance.copy(memoryFollowsDefault = following)
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
                following ->
                    "已保存：这一版跟随设置里的默认内存（" + memoryMb + " MB）。"
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

    if (exporting) {
        ExportPanel(instance = instance, backend = backend, onBack = { exporting = false })
        return
    }

    if (browsingSaves) {
        SavesPanel(instance = instance, backend = backend, onBack = { browsingSaves = false })
        return
    }

    // 删除确认。core 的 delete 用的是 File.delete()，删了不进回收站，
    // 所以这里不能像「停用」那样点一下就完事。
    pendingDelete?.let { (kind, fileName) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删掉这个文件？") },
            text = {
                Text(
                    "会直接从磁盘删掉 " + fileName + "，不进回收站。" +
                        "只是暂时不想用的话，点那一行改成停用更稳 —— 停用是改文件名，随时能改回来。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    deleteContent(kind, fileName)
                }) { Text("删掉") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("算了") }
            },
        )
    }

    // 删整个实例的确认框。三条要给用户看清：删哪个绝对路径、多少文件多大、
    // 存档默认留着（搬到 platypus/saves-backup/，那才是重新下载不回来的东西）。
    instancePlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { if (!deletingInstance) instancePlan = null },
            title = { Text("删除这个实例？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(instance.name.ifBlank { instance.id }, fontWeight = FontWeight.SemiBold)
                    Text(
                        plan.versionDir.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!plan.deletable) {
                        Text(plan.refusalReason, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(plan.fileCount.toString() + " 个文件，" + formatBytes(plan.totalBytes))
                        if (plan.hasSaves) {
                            Text(
                                "存档 " + plan.savesFileCount + " 个文件，" + formatBytes(plan.savesBytes) +
                                    if (instanceSavesToo) "，会一起删掉。" else "，会搬到 platypus/saves-backup/ 保留。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = instanceSavesToo,
                                    onCheckedChange = { instanceSavesToo = it },
                                    enabled = !deletingInstance,
                                )
                                Text("连存档一起删（不可恢复）")
                            }
                        }
                        if (plan.hasMods) {
                            Text("目录里有 mods/。", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "文件会先改名为 " + plan.versionDir.name + ".deleting-<时间戳> 再逐个删除；" +
                                "中途失败会自动改回原名。删完这条登记也一并从列表里移除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDeleteInstance() },
                    enabled = plan.deletable && !deletingInstance,
                ) { Text(if (deletingInstance) "删除中…" else "删除") }
            },
            dismissButton = {
                TextButton(onClick = { instancePlan = null }, enabled = !deletingInstance) { Text("算了") }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onBack) { Text("← 返回启动页") }
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

        Row(Modifier.weight(1f).fillMaxWidth()) {
            SetupNav(current = section, enabled = !saving) { picked ->
                section = picked
                // 存档与导出原本就是整页子页（返回键回列表），这里保持那个形状：
                // 它们要做的事太多，塞进右栏的一列里会挤成一小条。
                if (picked == SETUP_SAVES) browsingSaves = true
                if (picked == SETUP_EXPORT) exporting = true
            }
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (section) {
                    SETUP_MODS -> {
                        // 模组维护整块搬进了 InstalledModsSection：启停、删除、检查更新、多选更新、
                        // 查冲突、搬隔离前的旧模组都在那儿。这一页只提供落点（实例）和「去下载」的出口。
                        InstalledModsSection(
                            instance = instance,
                            onOpenDownload = { onOpenDownload(DOWNLOAD_TAB_MODS) },
                        )
                    }
                    SETUP_PACKS -> {
                        DetailCard("资源包与光影") {
                            DetailLine("资源包目录", File(d.gameDir, "resourcepacks").absolutePath)
                            DetailLine(
                                "已装资源包",
                                ((packScan?.packs?.size ?: 0) + (packScan?.disabled?.size ?: 0)).toString() + " 个",
                            )
                            DetailLine("光影目录", File(d.gameDir, "shaderpacks").absolutePath)
                            DetailLine(
                                "已装光影",
                                ((shaderScan?.packs?.size ?: 0) + (shaderScan?.disabled?.size ?: 0)).toString() + " 个",
                            )
                            Text(
                                "点一行就是启用 / 停用（改文件名成 .zip.disabled，文件还在原地）。先后顺序要在游戏的「选项 → 资源包」里调，启动器改不了那个顺序。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { openFolder(File(d.gameDir, "resourcepacks")) }) { Text("打开资源包目录") }
                                OutlinedButton(onClick = { openFolder(File(d.gameDir, "shaderpacks")) }) { Text("打开光影目录") }
                                OutlinedButton(onClick = { onOpenDownload(DOWNLOAD_TAB_SHADERS) }) { Text("去下载光影") }
                            }
                            PackContentSection(
                                title = "资源包",
                                scan = packScan,
                                kind = PackKind.RESOURCE_PACK,
                                enabled = !saving,
                                onToggle = { entry, want -> togglePack(PackKind.RESOURCE_PACK, entry, want) },
                                onDelete = { entry -> pendingDelete = KIND_RESOURCE_PACK to entry.fileName },
                            )
                            PackContentSection(
                                title = "光影",
                                scan = shaderScan,
                                kind = PackKind.SHADER,
                                enabled = !saving,
                                onToggle = { entry, want -> togglePack(PackKind.SHADER, entry, want) },
                                onDelete = { entry -> pendingDelete = KIND_SHADER to entry.fileName },
                            )
                        }
                    }
                    SETUP_LAUNCH -> {
                        DetailCard("内存与启动参数") {
                            MemorySlider(
                                valueMb = memoryMb,
                                onChange = { memoryMb = it },
                                enabled = !saving,
                            )
                            OutlinedTextField(
                                value = jvmArgsText,
                                onValueChange = { jvmArgsText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("额外 JVM 参数（一行一个，如 -XX:+UseG1GC）") },
                                minLines = 3,
                            )
                            Text(
                                "这个数直接进 -Xmx：点「保存」写进实例，启动页左栏也是同一个数，两边改都算数。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        JavaCard(
                            required = d.requiredJavaMajor ?: GameJavaRules.requiredMajor(instance.gameVersion),
                            javaPath = javaPath,
                            onJavaPathChange = { javaPath = it },
                            runtimes = runtimes,
                        )
                    }
                    SETUP_ADV -> {
                        DetailCard("版本隔离") {
                            IsolationBody(
                                isolated = isolated,
                                onIsolatedChange = { isolated = it },
                                followsDefault = isolatedFollowsDefault,
                                onFollowsDefaultChange = { follow ->
                                    // 取消跟随时把此刻的生效值固化下来：否则一关，上面那个开关
                                    // 会跳到实例里存的老值上，看着像自己弹了一下。
                                    if (!follow) isolated = if (isolatedFollowsDefault) defaultIsolation else isolated
                                    isolatedFollowsDefault = follow
                                },
                                defaultIsolation = defaultIsolation,
                                canToggleIsolation = canToggleIsolation,
                                saving = saving,
                                changed = isolated != instance.isolated ||
                                    isolatedFollowsDefault != instance.isolatedFollowsDefault,
                                gameRootPath = d.gameRoot.absolutePath,
                                pendingDirs = pendingDirs,
                                merging = merging,
                                onMerge = { mergeToRoot() },
                            )
                        }
                        DetailCard("这个实例在哪") {
                            DetailLine("版本目录", d.versionDir.absolutePath)
                            DetailLine("游戏根目录", d.gameRoot.absolutePath)
                            DetailLine(
                                "存档与模组在",
                                d.gameDir.absolutePath + if (d.isolated) "" else "（与同目录其他版本共用）",
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { openFolder(d.versionDir) }) { Text("打开实例目录") }
                                OutlinedButton(onClick = { openFolder(d.gameDir) }) { Text("打开存档目录") }
                            }
                        }
                        // 删实例放在「高级」最下面：它和「版本隔离」一样是对整个版本目录动手，
                        // 但不可逆，所以摆到最后一块，和上面那些开关拉开距离。
                        DetailCard("删除这个实例") {
                            Text(
                                "把 " + d.versionDir.absolutePath + " 整个删掉，并移除这条登记。" +
                                    "存档默认保留（搬到 platypus/saves-backup/），也可以勾成一起删。" +
                                    "删了不进回收站，下载不回来的只有存档与你自己放的模组。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { prepareDeleteInstance() },
                                enabled = !saving && !deletingInstance,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            ) { Text(if (deletingInstance) "正在统计目录…" else "删除这个实例") }
                            if (notice.isNotBlank()) {
                                Text(
                                    notice,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    else -> {
                        DetailCard("这个实例在哪") {
                            DetailLine("版本目录", d.versionDir.absolutePath)
                            DetailLine("游戏根目录", d.gameRoot.absolutePath)
                            DetailLine(
                                "存档与模组在",
                                d.gameDir.absolutePath + if (d.isolated) "" else "（与同目录其他版本共用）",
                            )
                            DetailLine("共享目录", "libraries/ 与 assets/ 由所有实例共用（放不下第二份）。")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { openFolder(d.versionDir) }) { Text("打开实例目录") }
                                OutlinedButton(onClick = { openFolder(d.gameDir) }) { Text("打开存档目录") }
                                OutlinedButton(onClick = { browsingSaves = true }) { Text("存档与备份") }
                                OutlinedButton(onClick = { exporting = true }) { Text("导出整合包") }
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
                        // 「版本文件」只说有没有 json 与 client jar；真正启动不了往往是某个库或
                        // assets 里的一个文件坏了，这一块才查得出来。放在「占了多少地方」之前：
                        // 先看能不能跑，再看占多大。
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
                                        DetailLine("要补多少", formatBytes(report.missingBytes))
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
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (!report.healthy) {
                                        Button(
                                            onClick = { repairIntegrity() },
                                            enabled = !repairing && !integrityChecking,
                                        ) { Text(if (repairing) "正在补齐…" else "补齐这 " + report.problemFiles + " 个文件") }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            integrity = null
                                            repairText = ""
                                        },
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
                    }
                }

                if (section == SETUP_LAUNCH || section == SETUP_ADV) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
        }
    }
}

@Composable
private fun SetupNav(current: String, enabled: Boolean, onPick: (String) -> Unit) {
    Column(Modifier.width(176.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        SETUP_SECTIONS.forEach { (id, label) ->
            val selected = id == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else Color.Transparent)
                    .clickable(enabled = enabled) { onPick(id) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 一个资源包 / 光影列表。空的就明说，别留一段空白让人以为没扫。 */
@Composable
private fun PackContentSection(
    title: String,
    scan: PackScanResult?,
    kind: PackKind,
    enabled: Boolean,
    onToggle: (PackEntry, Boolean) -> Unit,
    onDelete: (PackEntry) -> Unit,
) {
    val all = scan?.let { it.packs + it.disabled }.orEmpty()
    Text(
        title + " · " + all.size + " 个",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (all.isEmpty()) {
        Text(
            "还没有" + kind.label + "。放进 " + kind.dirName + "/ 就生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        all.forEach { entry ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onToggle(entry, !entry.enabled) },
                colors = CardDefaults.cardColors(
                    containerColor = if (entry.enabled) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        Text(
                            formatBytes(entry.sizeBytes) +
                                (entry.packFormat?.let { " · pack_format " + it } ?: "") +
                                " · " + if (entry.enabled) "已启用，点这条可停用" else "已停用，点这条可启用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        entry.problem?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { onDelete(entry) }, enabled = enabled) {
                        Text("删除", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    scan?.warnings?.forEach {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun JavaCard(
    required: Int,
    javaPath: String,
    onJavaPathChange: (String) -> Unit,
    runtimes: List<JavaRuntime>,
) {
    DetailCard("Java") {
        DetailLine("当前设置", javaPath.ifBlank { "没设，启动时按版本要求自动找" })
        OutlinedTextField(
            value = javaPath,
            onValueChange = onJavaPathChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Java 可执行文件路径（留空 = 自动找）") },
            singleLine = true,
        )
        if (runtimes.isEmpty()) {
            Text(
                "本机没探测到 Java。去「设置 → 启动」那张卡里点「下载 Java」。",
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
                    OutlinedButton(onClick = { onJavaPathChange(runtime.path) }, modifier = Modifier.fillMaxWidth()) {
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
            if (runtimes.findExactMajor(required) == null) {
                Text(
                    "注意：没有正好是 Java " + required + " 的运行时。本启动器不做高版本顶替，启动会失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun IsolationBody(
    isolated: Boolean,
    onIsolatedChange: (Boolean) -> Unit,
    followsDefault: Boolean,
    onFollowsDefaultChange: (Boolean) -> Unit,
    defaultIsolation: Boolean,
    canToggleIsolation: Boolean,
    saving: Boolean,
    changed: Boolean,
    gameRootPath: String,
    pendingDirs: List<String>,
    merging: Boolean,
    onMerge: () -> Unit,
) {
    // 这一页显示的是「生效值」：跟随默认时看全局那一个，否则看实例自己存的。
    val effective = if (followsDefault) defaultIsolation else isolated
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.fillMaxWidth(0.78f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (effective) "开：这个实例自己一份存档、模组、配置" else "关：与同目录下的其他版本共用一份",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (canToggleIsolation) {
                    if (effective) {
                        "装模组、存档、改配置都只影响这一个实例。整合包把 mods/ 平铺在游戏根目录时，要关掉才看得见那些模组。"
                    } else {
                        "游戏读写 " + gameRootPath + " 下的 mods/、saves/、options.txt。别的版本改了配置、换了模组，这边也会跟着变。"
                    }
                } else {
                    "这个目录不是 versions/<id> 的形状，开不开隔离都落在同一处，所以不给开关。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = effective,
            onCheckedChange = onIsolatedChange,
            enabled = canToggleIsolation && !saving && !followsDefault,
        )
    }
    // 全局默认开关（设置 → 启动 → 新建实例默认隔离）。这一页只给「跟随」，
    // 真正的默认值在那儿改 —— 一个开关改所有新实例，摆在这一页会让人以为只改当前这个。
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.fillMaxWidth(0.78f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("跟随设置里的默认", style = MaterialTheme.typography.bodyMedium)
            Text(
                "设置里现在是「" + (if (defaultIsolation) "默认开隔离" else "默认不隔离") + "」。" +
                    "勾着这一项，这一版就跟着那个走，上面那个开关跟着变灰；要把这版单独定下来，先把它关掉。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = followsDefault,
            onCheckedChange = onFollowsDefaultChange,
            enabled = canToggleIsolation && !saving,
        )
    }
    if (changed) {
        Text(
            "改完要点下面的「保存」才生效，下次启动游戏才用新目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (!effective && pendingDirs.isNotEmpty()) {
        Text(
            "这个实例目录里还有 " + pendingDirs.joinToString("、") + "，关掉隔离后游戏就读不到它们了（它会去游戏根目录找）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onMerge, enabled = !merging && !saving) {
                Text(if (merging) "正在合并…" else "复制到游戏根目录")
            }
            Text(
                "复制而不是移动：原件留在实例目录里，想开回隔离随时能开。同名文件不会覆盖。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
