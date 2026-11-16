package io.github.huang404yan.platypus.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.mods.InstanceMods
import io.github.huang404yan.platypus.core.mods.InstanceModsSnapshot
import io.github.huang404yan.platypus.core.mods.LegacyAdoptResult
import io.github.huang404yan.platypus.core.mods.ModConflict
import io.github.huang404yan.platypus.core.mods.ModConflictChecker
import io.github.huang404yan.platypus.core.mods.ModConflictReport
import io.github.huang404yan.platypus.core.mods.ModEntry
import io.github.huang404yan.platypus.core.mods.ModFileOps
import io.github.huang404yan.platypus.core.mods.ModInstaller
import io.github.huang404yan.platypus.core.mods.ModScanner
import io.github.huang404yan.platypus.core.mods.ModUpdateChecker
import io.github.huang404yan.platypus.core.mods.ModUpdateResult
import io.github.huang404yan.platypus.core.mods.ModUpdateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「这个版本装了什么模组」——启停、删除、检查更新、查冲突、搬隔离前的旧模组。
 *
 * 这块原来在「下载 → Mod」的「已装」分栏里，和搜索下载挤在同一页。现在下载页只负责
 * 「搜一个装进来」，装完之后的管理动作跟着具体实例走，落在这里：用户是从某个版本的
 * 「版本设置」点进来的，看到的清单天然就是这个实例的，不用再挑一遍实例。
 *
 * 布局上不再用 `weight(1f)` 把清单撑满：详情页右栏本身是一个 `verticalScroll` 的
 * Column，没有可分配的剩余高度。这里把条目直接铺开，跟着整页一起滚。
 */
@Composable
internal fun InstalledModsSection(
    instance: Instance,
    onOpenDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    /** 这个实例实际读写的游戏目录：关掉版本隔离时是游戏根目录那份 mods/。 */
    val baseDir = remember(instance.directory, instance.isolated) { InstanceLayouts.of(instance).gameDir }
    val gameVersion = instance.gameVersion
    val loader = instance.loader.takeIf { it != LoaderKind.VANILLA && it != LoaderKind.OTHER } ?: LoaderKind.FABRIC

    var snapshot by remember { mutableStateOf<InstanceModsSnapshot?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var adoptNotice by remember { mutableStateOf("") }
    var opNotice by remember { mutableStateOf("") }
    var opError by remember { mutableStateOf("") }
    var updates by remember { mutableStateOf<List<ModUpdateResult>>(emptyList()) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf("") }
    var updateError by remember { mutableStateOf("") }
    var updatingFile by remember { mutableStateOf("") }
    var conflictReport by remember { mutableStateOf<ModConflictReport?>(null) }
    var checkingConflicts by remember { mutableStateOf(false) }
    var conflictProgress by remember { mutableStateOf("") }
    var conflictError by remember { mutableStateOf("") }

    // 冲突卡片的「展开全部」状态放在这一层：卡片重绘时不该把用户刚展开的又收回去
    var showAllConflicts by remember { mutableStateOf(false) }
    // 多选更新：平时看不见，点「多选更新」才出来。重置 key 与 [instance] 绑在一起，
    // 换实例时它自己就清掉了。
    var pickMode by remember(instance.directory) { mutableStateOf(false) }
    var pickedFiles by remember(instance.directory) { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun rescan() {
        scanning = true
        try {
            snapshot = withContext(Dispatchers.IO) {
                runCatching { InstanceMods.scan(instance) }.getOrNull()
            }
        } finally {
            scanning = false
        }
        // 文件集合变了（装/删/停用），上轮冲突结论就过期了：留着会指着已经删掉的模组说冲突
        conflictReport = null
        conflictProgress = ""
    }

    LaunchedEffect(instance.directory) {
        rescan()
    }

    suspend fun adoptLegacy() {
        adoptNotice = ""
        val result: LegacyAdoptResult? = withContext(Dispatchers.IO) {
            runCatching { InstanceMods.adoptLegacy(instance) }.getOrElse { e ->
                adoptNotice = "搬模组失败：" + (e.message ?: e.toString())
                null
            }
        }
        if (result != null) {
            adoptNotice = buildString {
                append("从共享 mods/ 搬进这个实例 ").append(result.copied.size).append(" 个")
                if (result.skipped.isNotEmpty()) {
                    append("；").append(result.skipped.size).append(" 个同名跳过（不覆盖已有文件）")
                }
                if (result.failed.isNotEmpty()) {
                    append("；").append(result.failed.size).append(" 个失败")
                }
                append("。共享目录里的原件没删。")
            }
            rescan()
        }
    }

    /**
     * 启用 / 停用：改的是文件名（.jar ↔ .jar.disabled），不是某个配置文件。
     *
     * 沿用 MC 生态的老规矩：游戏只认看得见的 .jar，所以停用就是改个名。好处是用户从
     * 资源管理器里也能看到、也能改回来，不依赖启动器的私有状态。
     */
    suspend fun setEnabled(entry: ModEntry, enabled: Boolean) {
        opNotice = ""
        opError = ""
        try {
            val renamed = withContext(Dispatchers.IO) {
                ModFileOps.setEnabled(ModScanner.modsDir(baseDir), entry.fileName, enabled)
            }
            opNotice = (if (enabled) "已启用 " else "已停用 ") + renamed.name
            // 停用/启用会换文件名，旧的选择与更新记录都对不上了，重新扫一遍最省事
            rescan()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = e.message ?: e.toString()
        }
    }

    suspend fun deleteMod(entry: ModEntry) {
        opNotice = ""
        opError = ""
        try {
            val removed = withContext(Dispatchers.IO) {
                ModFileOps.delete(ModScanner.modsDir(baseDir), entry.fileName)
            }
            opNotice = if (removed) "已删除 " + entry.fileName else "文件本来就不在了：" + entry.fileName
            updates = updates.filterNot { it.fileName == entry.fileName }
            rescan()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = e.message ?: e.toString()
        }
    }

    /** 检查更新：全部模组一批问 Modrinth，按四类结论回填。 */
    suspend fun checkUpdates() {
        if (gameVersion.isBlank()) {
            updateError = "这个实例没记游戏版本，不知道给哪个版本查更新，会把别的版本的包当成「最新」。"
            return
        }
        checkingUpdates = true
        updateError = ""
        updateProgress = ""
        try {
            val found = withContext(Dispatchers.IO) {
                ModUpdateChecker.check(
                    modsDir = ModScanner.modsDir(baseDir),
                    gameVersion = gameVersion.trim().ifBlank { null },
                    loader = loader,
                ) { updateProgress = it }
            }
            updates = found
            val outdated = found.count { it.status == ModUpdateStatus.OUTDATED }
            updateProgress = if (found.isEmpty()) {
                "没有可检查的 .jar（停用的模组不算）。"
            } else {
                "查了 " + found.size + " 个，可更新 " + outdated + " 个。"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updates = emptyList()
            updateError = e.message ?: e.toString()
        } finally {
            checkingUpdates = false
        }
    }

    /**
     * 查「已装模组之间有没有互相声明不兼容」。
     *
     * 只用 Modrinth 的 dependency_type = incompatible 这一条线索，不替作者做判断：
     * 有些声明是「功能重复」，两个都装着不一定崩；有些是真的抢同一个 mixin 目标，装了就黑屏。
     * 所以结论一律按「作者声明不兼容」来说，并区分「两个都装着」与「只是被点名」。
     */
    suspend fun checkConflicts() {
        checkingConflicts = true
        conflictError = ""
        conflictProgress = ""
        try {
            val report = withContext(Dispatchers.IO) {
                ModConflictChecker.check(ModScanner.modsDir(baseDir)) { conflictProgress = it }
            }
            conflictReport = report
            conflictProgress = when {
                report.scanned == 0 -> "mods/ 里没有启用的 .jar，没什么可查的。"
                report.presentConflicts.isNotEmpty() ->
                    "查出 " + report.presentConflicts.size + " 条已经装着的冲突，见下面的卡片。"
                report.conflicts.isNotEmpty() -> "两个都装着的不兼容没发现；另有作者提到但没装的，列在卡片里。"
                else -> "查了 " + report.scanned + " 个文件（认得出 " + report.recognized + " 个），没发现冲突。"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            conflictReport = null
            conflictError = e.message ?: e.toString()
        } finally {
            checkingConflicts = false
        }
    }

    /** 把一条 OUTDATED 换成新版本。旧文件先停用而不是直接删：万一新版有问题还能开回来。 */
    suspend fun updateOne(result: ModUpdateResult) {
        val latest = result.latest ?: return
        // 项目详情可能没查回来（网络抖一下），这时 slug 退回 project_id —— Modrinth 两种都认
        val slug = result.project?.slug ?: result.lookup?.projectId ?: return
        updatingFile = result.fileName
        opNotice = ""
        opError = ""
        try {
            val installed = withContext(Dispatchers.IO) {
                ModInstaller.installFile(baseDir, slug, latest)
            }
            val modsDir = ModScanner.modsDir(baseDir)
            val disableNote = withContext(Dispatchers.IO) {
                runCatching {
                    // 新文件名与旧文件同名时它会拒绝（那是同一个包），此时不需要停用
                    if (installed.file.name == result.fileName) {
                        null
                    } else {
                        ModFileOps.setEnabled(modsDir, result.fileName, false)
                    }
                }.fold(
                    onSuccess = { renamed: File? ->
                        if (renamed == null) "" else "旧版已停用（改名成 " + renamed.name + "），想回退就删掉新版再启用它。"
                    },
                    onFailure = { "旧版没停用：" + (it.message ?: "") },
                )
            }
            opNotice = "已更新到 " + installed.version + "：" + installed.file.name + "。" + disableNote
            updates = updates.map {
                if (it.fileName == result.fileName) {
                    it.copy(lookup = it.lookup, latest = latest)
                } else {
                    it
                }
            }
            checkUpdates()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = "更新失败：" + (e.message ?: e.toString())
        } finally {
            updatingFile = ""
        }
    }

    /**
     * 批量更新：把勾上的那些一次性换成新版本。
     *
     * 与 [updateOne] 的区别只有两点：条目循环着装、最后只问一次 Modrinth。
     * 单个失败不中断其余（作者撤了某个版本不该让另外九个白等），失败项报在 opError 里。
     */
    suspend fun updateMany(targets: List<ModUpdateResult>) {
        if (targets.isEmpty()) {
            opError = "没有勾上可更新的模组。"
            return
        }
        updatingFile = "*"
        opNotice = ""
        opError = ""
        val failed = mutableListOf<String>()
        var done = 0
        try {
            val modsDir = ModScanner.modsDir(baseDir)
            for (result in targets) {
                val latest = result.latest ?: continue
                val slug = result.project?.slug ?: result.lookup?.projectId ?: continue
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val installed = ModInstaller.installFile(baseDir, slug, latest)
                        // 新文件名与旧文件同名时它本来就是同一个包，不需要停用旧版
                        if (installed.file.name != result.fileName) {
                            runCatching { ModFileOps.setEnabled(modsDir, result.fileName, false) }
                        }
                        installed
                    }
                }
                if (outcome.isSuccess) done++ else failed += result.fileName + "（" + (outcome.exceptionOrNull()?.message ?: "失败") + "）"
            }
            opNotice = "批量更新完成：" + done + " 个装好" +
                if (failed.isEmpty()) "。" else "，" + failed.size + " 个失败。"
            if (failed.isNotEmpty()) opError = "这些没更新成功：" + failed.joinToString("；")
            checkUpdates()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = "批量更新失败：" + (e.message ?: e.toString())
        } finally {
            updatingFile = ""
        }
    }

    val outdated = updates.filter { it.status == ModUpdateStatus.OUTDATED && it.latest != null }
    val outdatedCount = outdated.size

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    snapshot == null -> if (scanning) "正在扫描 mods 目录…" else "还没有读到 mods 目录。"
                    snapshot!!.scan.exists -> {
                        "已装 " + snapshot!!.scan.totalCount + " 个（启用 " + snapshot!!.scan.mods.size +
                            " · 停用 " + snapshot!!.scan.disabled.size + "）"
                    }
                    else -> "还没有 mods/，装第一个模组时会自动建。"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { scope.launch { checkUpdates() } }, enabled = !checkingUpdates && snapshot != null) {
                Text(if (checkingUpdates) "检查中…" else "检查更新")
            }
            // 多选更新只在真查出可更新的东西时出现：平时这一行已经四个按钮，再多一个就是纯噪音。
            if (outdatedCount > 0) {
                OutlinedButton(
                    onClick = {
                        pickMode = !pickMode
                        if (!pickMode) pickedFiles = emptySet()
                    },
                    enabled = updatingFile.isBlank(),
                ) {
                    Text(if (pickMode) "退出多选" else "多选更新")
                }
            }
            OutlinedButton(onClick = { scope.launch { checkConflicts() } }, enabled = !checkingConflicts && snapshot != null) {
                Text(if (checkingConflicts) "查冲突…" else "查冲突")
            }
            OutlinedButton(onClick = onOpenDownload) { Text("去下载 Mod") }
            OutlinedButton(onClick = { scope.launch { rescan() } }) { Text("重新扫描") }
        }
        if (pickMode) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已选 " + pickedFiles.size + " 个（只认带可更新版本的条目）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        // 「全选」只选真的能更新的：把 75 个都勾上再一个个跳过，看着像坏了
                        val updatable = updates.filter {
                            it.status == ModUpdateStatus.OUTDATED && it.latest != null
                        }.map { it.fileName }.toSet()
                        pickedFiles = if (pickedFiles.containsAll(updatable)) emptySet() else updatable
                    },
                    enabled = updatingFile.isBlank(),
                ) {
                    Text(if (pickedFiles.containsAll(outdated.map { it.fileName })) "取消全选" else "全选可更新")
                }
                Button(
                    onClick = { scope.launch { updateMany(outdated.filter { it.fileName in pickedFiles }) } },
                    enabled = pickedFiles.isNotEmpty() && updatingFile.isBlank(),
                ) {
                    Text(if (updatingFile == "*") "更新中…" else "更新选中 " + pickedFiles.size + " 个")
                }
            }
        }
        snapshot?.let {
            Text(
                it.modsDir.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            it.scan.warnings.forEach { warning -> ModHint(warning) }
        }

        if (adoptNotice.isNotBlank()) ModHint(adoptNotice)
        if (opNotice.isNotBlank()) ModHint(opNotice)
        if (opError.isNotBlank()) ModHint(opError, error = true)
        if (updateProgress.isNotBlank() || updateError.isNotBlank()) {
            ModHint(if (updateError.isNotBlank()) "检查更新失败：" + updateError else updateProgress, error = updateError.isNotBlank())
        }
        if (conflictError.isNotBlank()) {
            ModHint("查冲突失败：" + conflictError, error = true)
        } else if (conflictProgress.isNotBlank()) {
            ModHint(conflictProgress)
        }
        if (conflictReport != null) {
            ConflictCard(
                report = conflictReport!!,
                expanded = showAllConflicts,
                onToggleExpanded = { showAllConflicts = !showAllConflicts },
                onDismiss = {
                    showAllConflicts = false
                    conflictReport = null
                    conflictProgress = ""
                },
            )
        }
        snapshot?.takeIf { it.hasLegacy }?.let {
            LegacyModsRow(snapshot = it, onAdopt = { scope.launch { adoptLegacy() } })
        }

        val installed = snapshot?.let { it.scan.mods + it.scan.disabled }.orEmpty()
        if (installed.isEmpty()) {
            ModHint(
                when {
                    snapshot == null -> "正在扫描 mods 目录…"
                    else -> "这个实例还没有模组。去「下载 → Mod」搜一个装进来，或者点上面的「重新扫描」。"
                },
            )
        } else {
            val updateByFile = updates.associateBy { it.fileName }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                installed.forEach { entry ->
                    val canPick = updateByFile[entry.fileName]?.let {
                        it.status == ModUpdateStatus.OUTDATED && it.latest != null
                    } == true
                    InstalledModRow(
                        entry = entry,
                        update = updateByFile[entry.fileName],
                        busy = updatingFile.isNotBlank(),
                        updating = updatingFile == entry.fileName,
                        pickMode = pickMode,
                        picked = entry.fileName in pickedFiles,
                        onTogglePicked = {
                            if (canPick) {
                                pickedFiles = if (entry.fileName in pickedFiles) {
                                    pickedFiles - entry.fileName
                                } else {
                                    pickedFiles + entry.fileName
                                }
                            }
                        },
                        onToggleEnabled = { enabled -> scope.launch { setEnabled(entry, enabled) } },
                        onDelete = { scope.launch { deleteMod(entry) } },
                        onUpdate = { result -> scope.launch { updateOne(result) } },
                    )
                }
            }
        }
    }
}

/** 共享 mods/ 里还有隔离前留下的模组时，提示一行并给一个「搬进这个实例」。 */
@Composable
private fun LegacyModsRow(snapshot: InstanceModsSnapshot, onAdopt: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "共享 mods/ 里还有 " + snapshot.legacyCount + " 个模组（隔离前装的）",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "在 " + snapshot.legacyModsDir.absolutePath + "。这个实例现在看不到它们。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "搬过去是复制：不覆盖同名文件，共享目录原件不删。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onAdopt) { Text("搬进这个实例") }
            }
        }
    }
}

/**
 * 一条已装模组。
 *
 * 勾选框就是启用/停用：勾上 = 文件名是 .jar，取消 = 改成 .jar.disabled。这一条以前要求
 * 用户自己开资源管理器改名（界面上还理直气壮写着那句话），是「有了但不好用」里最典型的一处。
 *
 * 删除要二次确认：模组文件不在回收站里，点一下就没了。
 */
@Composable
private fun InstalledModRow(
    entry: ModEntry,
    update: ModUpdateResult?,
    busy: Boolean,
    updating: Boolean,
    pickMode: Boolean,
    picked: Boolean,
    onTogglePicked: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onUpdate: (ModUpdateResult) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val canPick = update != null && update.status == ModUpdateStatus.OUTDATED && update.latest != null
    // 整行可点：日常模式下点条目就是启用/停用（用户要的「点一下关、再点一下开」），
    // 多选模式下点条目是加入/移出更新队列。里面的按钮自己会消费点击，不会误触。
    val rowClick: (() -> Unit)? = when {
        pickMode && canPick -> onTogglePicked
        pickMode -> null
        else -> ({ onToggleEnabled(!entry.enabled) })
    }
    // 悬停时描一道边（PCL 的 RectBack）：光靠指针形状判断「这条能不能点」太弱，
    // 一百多个条目扫下来，鼠标停在哪一条上根本看不出来。
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverAlpha by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = tween(durationMillis = if (hovered) 120 else 180),
        label = "modRowHover",
    )
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (rowClick != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = rowClick,
                )
            } else {
                Modifier
            },
        ),
        border = if (hoverAlpha > 0.01f) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = hoverAlpha * 0.5f))
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = when {
                !pickMode -> MaterialTheme.colorScheme.surface
                picked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        // 左边一张方图标、右边两行字：行高从「一行文件名」变成「一张缩略图」，
        // 所以内边距和行距都比原来松一档 —— 挤成一行行小字时，扫一眼分不出哪个是哪个。
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pickMode) {
                Checkbox(checked = picked, onCheckedChange = { onTogglePicked() }, enabled = canPick)
            }
            // 左侧这条 5dp 竖条就是「启用」标记（PCL 的 RectCheck）：勾上时从 0 长到 32dp，
            // 取消时缩回去。比一个勾选框轻，一百多个条目排下来也不会满屏小方块。
            val barHeight by animateDpAsState(
                targetValue = if (entry.enabled) 32.dp else 0.dp,
                animationSpec = tween(
                    durationMillis = if (entry.enabled) 300 else 120,
                    easing = CubicBezierEasing(0.17f, 0.67f, 0.35f, 1f),
                ),
                label = "modEnabledBar",
            )
            Box(
                Modifier
                    .width(5.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            ModIcon(entry, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 停用的条目划一条删除线（PCL 同款）：一眼能分出「装着但没加载」的那些。
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (entry.enabled) null else TextDecoration.LineThrough,
                        color = if (entry.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (update != null && update.status == ModUpdateStatus.OUTDATED) {
                        Text(
                            "有新版本 " + (update.latestVersion ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        formatSizeShort(entry.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val detail = buildString {
                    append(entry.fileName)
                    entry.loader?.let { append(" · ").append(loaderLabel(it)) }
                    if (!entry.enabled) append(" · 停用中（不会加载）")
                    update?.let {
                        if (it.status == ModUpdateStatus.UP_TO_DATE) append(" · 已是最新 ").append(it.currentVersion)
                        if (it.status == ModUpdateStatus.NOT_ON_MODRINTH) append(" · Modrinth 不认识这个文件")
                        if (it.status == ModUpdateStatus.NO_COMPATIBLE_VERSION) append(" · 没有适配当前版本的更新")
                    }
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.problem?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 这里原来是一个 Checkbox。现在整行可点，再放勾选框反而让人以为「只有勾选框能点」，
                    // 于是只留一行状态字，把「点哪」写清楚。
                    Text(
                        when {
                            pickMode && picked -> "已选入更新队列"
                            pickMode -> "点这条选入更新队列"
                            entry.enabled -> "已启用 · 点这条可停用"
                            else -> "已停用 · 点这条可启用"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // 明确的启用/停用按钮：整行也能点，但「点整行会怎样」得先看那行小字才知道，
                    // 多给一个写着动作的按钮，不用试。
                    TextButton(onClick = { onToggleEnabled(!entry.enabled) }, enabled = !busy) {
                        Text(if (entry.enabled) "停用" else "启用")
                    }
                    if (update != null && update.status == ModUpdateStatus.OUTDATED) {
                        OutlinedButton(onClick = { onUpdate(update) }, enabled = !busy) {
                            Text(if (updating) "更新中…" else "更新到 " + (update.latestVersion ?: "新版"))
                        }
                    }
                    TextButton(onClick = { confirmDelete = true }, enabled = !confirmDelete) { Text("删除") }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个模组文件？") },
            text = {
                Text(
                    "会直接删掉 " + entry.fileName + "，不进回收站。只是暂时不想用的话，点「停用」就行。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text("删除文件") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 「作者声明这些不能共存」的卡片。
 *
 * 分两段写是刻意的：第一段是「现在就装着」的组合 —— 用户点一下就能处理；
 * 第二段是「作者提到但没装」的 —— 那是「别去装它」的预防性提醒，混在一起会把第一条压没了。
 *
 * 措辞一律是「作者声明不兼容」而不是「这两个一定崩」：Modrinth 上的 incompatible 有一半是
 * 「功能重复」，两个都装着往往只是白占内存。替用户下「你必崩」的结论是越权，也会误导排查。
 */
@Composable
private fun ConflictCard(
    report: ModConflictReport,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDismiss: () -> Unit,
) {
    val present = report.presentConflicts
    val potential = report.potentialConflicts
    // 默认只列前 5 条：一屏塞十几个选项没人看，展开才给全部
    val limit = 5
    fun visible(list: List<ModConflict>): List<ModConflict> =
        if (expanded) list else list.take(limit)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (present.isEmpty()) "作者声明的模组冲突（没有两个都装着的）" else "模组冲突 · " + present.size + " 条已经装着",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (present.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Text(
                "来源是模组作者在 Modrinth 上写的「不兼容」声明，不是启动器猜的。功能重复的模组也会这么标，两个都装着不一定崩 —— 但作者这么说，出问题先从这里排查。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            visible(present).forEach { conflict ->
                Text(
                    "· " + conflict.sourceTitle + "  ↔  " + conflict.otherLabel +
                        if (conflict.mutual) "（双方都声明了）" else "",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "　都装在这个实例里：" + conflict.sourceFile +
                        (conflict.otherFile?.let { " 与 " + it } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (potential.isNotEmpty()) {
                Text(
                    "作者提到但不兼容的还有 " + potential.size + " 个项目（没装，别去装就行）：",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                visible(potential).forEach { conflict ->
                    Text(
                        "· " + conflict.sourceTitle + "  ✕  " + conflict.otherLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (report.failures.isNotEmpty()) {
                Text(
                    report.failures.size.toString() + " 个文件读不了，没参与比对：" + report.failures.take(3).joinToString("；"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (report.recognized < report.scanned) {
                Text(
                    "另有 " + (report.scanned - report.recognized) + " 个文件 Modrinth 不认识（自己改过、或不是从 Modrinth 下的），查不到它们的声明。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (report.conflicts.size > limit) {
                    TextButton(onClick = onToggleExpanded) {
                        Text(if (expanded) "只看前 " + limit + " 条" else "展开全部 " + report.conflicts.size + " 条")
                    }
                }
                Text("", modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}