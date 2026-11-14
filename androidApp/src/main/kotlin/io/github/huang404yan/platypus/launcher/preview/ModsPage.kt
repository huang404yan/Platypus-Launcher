package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.collectAsState
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
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.mods.InstanceMods
import io.github.huang404yan.platypus.core.mods.InstanceModsSnapshot
import io.github.huang404yan.platypus.core.mods.ModConflict
import io.github.huang404yan.platypus.core.mods.ModConflictChecker
import io.github.huang404yan.platypus.core.mods.ModConflictReport
import io.github.huang404yan.platypus.core.mods.ModEntry
import io.github.huang404yan.platypus.core.mods.ModFileOps
import io.github.huang404yan.platypus.core.mods.ModInstallProgress
import io.github.huang404yan.platypus.core.mods.ModInstaller
import io.github.huang404yan.platypus.core.mods.ModProject
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
 * 模组页（手机）。
 *
 * 手机上放不下桌面的左右双栏，改成一条竖着滚的流：游戏目录 → 选实例 → 本地已装 → 搜索 → 结果。
 * 逻辑与桌面端共用 [ModInstaller] / [InstanceMods]，一行都没重写。
 *
 * P1 之后每个实例有自己的 mods 目录（`<游戏目录>/versions/<id>/mods`），所以这页要先选实例：
 * 以前这里直接把游戏根目录当 mods 的父目录，隔离后那么写会显示旧目录、装进新目录，
 * 两边对不上。
 *
 * 游戏版本是**必填**的：留空会让 Modrinth 的 versions facet 被省略，pickBest 取到最新版
 * （实测桌面端曾把 mc26.3 的 Sodium 装进 1.20.1 的目录）。这里除了拦，还会从选中的
 * 实例里把版本号带出来给用户点。
 */
@Composable
internal fun ModsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(hasFileAccess(context)) }
    var root by remember { mutableStateOf(defaultGameRoot()) }
    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var selectedKey by remember { mutableStateOf("") }
    var gameVersion by remember { mutableStateOf("") }
    var loader by remember { mutableStateOf<LoaderKind?>(LoaderKind.FABRIC) }
    var query by remember { mutableStateOf("") }

    var snapshot by remember { mutableStateOf<InstanceModsSnapshot?>(null) }
    var scanning by remember { mutableStateOf(false) }

    var results by remember { mutableStateOf<List<ModProject>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    // 安装跑在进程级的 ModInstallRunner 里：切页、切 tab 都不会打断。
    val installState = ModInstallRunner.state.collectAsState().value

    // P2 的四件事，与桌面端一一对应：检查更新、更新单条、启用停用、删除
    var updates by remember { mutableStateOf<List<ModUpdateResult>>(emptyList()) }
    var checkingUpdates by remember { mutableStateOf(false) }

    // 冲突检测：与桌面端同一份核心逻辑（ModConflictChecker），null = 还没查过、不显示卡片
    var conflictReport by remember { mutableStateOf<ModConflictReport?>(null) }
    var checkingConflicts by remember { mutableStateOf(false) }
    var conflictProgress by remember { mutableStateOf("") }
    var conflictError by remember { mutableStateOf("") }
    var updatingFile by remember { mutableStateOf("") }
    var opError by remember { mutableStateOf("") }
    var opNotice by remember { mutableStateOf("") }

    /**
     * 加载器跟着实例走：换了实例就把选择对齐到那个实例的加载器。
     *
     * 记 [loaderInstanceKey] 而不是每次 rescan 都覆盖，是因为用户手动轮换过加载器时，
     * 刷一下列表不该把选择改回去。原版实例没有加载器，[loaderOf] 返回 null 时不动当前选择。
     */
    var loaderInstanceKey by remember { mutableStateOf("") }

    val current = instances.firstOrNull { instanceKey(it) == selectedKey }

    /** 扫游戏目录里的实例，尽量保住当前选择。 */
    suspend fun rescanInstances() {
        val path = root.trim()
        if (path.isEmpty() || !granted) return
        val scanned = withContext(Dispatchers.IO) {
            runCatching { InstanceScanner.scan(File(path)) }.getOrNull()
        }
        val found = scanned?.instances.orEmpty()
        instances = found
        if (found.none { instanceKey(it) == selectedKey }) {
            // 原版实例排后面：它们装不了模组
            selectedKey = found.sortedBy { it.loader == LoaderKind.VANILLA }
                .firstOrNull()
                ?.let { instanceKey(it) }
                .orEmpty()
        }
        error = when {
            scanned == null -> "读不了这个目录：$path"
            !scanned.isGameDirectory -> "这个目录不是游戏目录（缺少 versions/、libraries/ 或 assets/）。"
            else -> ""
        }
    }

    /** 扫选中实例的 mods/，并把实例记着的游戏版本带进搜索条件。 */
    suspend fun rescanMods() {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey }
        if (target == null) {
            snapshot = null
            return
        }
        // 加载器跟着实例对齐，规则同桌面端：只在这个实例还没对齐过时动，别覆盖用户手选的
        val key = instanceKey(target)
        if (loaderInstanceKey != key) {
            loaderInstanceKey = key
            loaderOf(target.loader)?.let { loader = it }
        }
        scanning = true
        try {
            snapshot = withContext(Dispatchers.IO) { runCatching { InstanceMods.scan(target) }.getOrNull() }
            if (gameVersion.isBlank() && target.gameVersion.isNotBlank()) gameVersion = target.gameVersion
        } finally {
            scanning = false
        }
        // 文件集合变了，上一轮的冲突结论就过期了 —— 留着比没有更误导（与桌面端同一条规则）
        conflictReport = null
        conflictProgress = ""
    }

    suspend fun rescan() {
        rescanInstances()
        rescanMods()
    }

    suspend fun adoptLegacy() {
        val target = current ?: return
        notice = ""
        val result = withContext(Dispatchers.IO) {
            runCatching { InstanceMods.adoptLegacy(target) }.getOrElse { e ->
                notice = "搬模组失败：" + (e.message ?: e.toString())
                null
            }
        }
        if (result != null) {
            notice = buildString {
                append("从共享 mods/ 搬进这个实例 ").append(result.copied.size).append(" 个")
                if (result.skipped.isNotEmpty()) {
                    append("；").append(result.skipped.size).append(" 个同名跳过（不覆盖已有文件）")
                }
                if (result.failed.isNotEmpty()) {
                    append("；").append(result.failed.size).append(" 个失败")
                }
                append("。共享目录里的原件没删。")
            }
            rescanMods()
        }
    }

    suspend fun search() {
        val text = query.trim()
        error = ""
        notice = ""
        if (text.isBlank()) {
            error = "先输入要搜的模组名（如 sodium、jei）。"
            return
        }
        if (gameVersion.isBlank()) {
            error = "先填游戏版本（如 1.20.1）。留空会搜到最新版模组，跟你的游戏对不上。"
            return
        }
        searching = true
        try {
            results = withContext(Dispatchers.IO) {
                ModInstaller.search(text, gameVersion.trim(), loader ?: LoaderKind.VANILLA)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results = emptyList()
            error = e.message ?: e.toString()
        } finally {
            searched = true
            searching = false
        }
    }

    suspend fun install(project: ModProject) {
        val target = current
        if (target == null) {
            notice = "先选一个实例：模组装进实例自己的 mods/，没有实例就没有目标目录。"
            return
        }
        if (gameVersion.isBlank()) {
            notice = "先填游戏版本，否则装进来的模组跟你的游戏对不上。"
            return
        }
        notice = ""
        // 跑在进程级作用域里：切页不会打断，回来还能看到进度和结果。
        ModInstallRunner.start(
            instanceDir = InstanceLayouts.of(target).gameDir,
            instanceName = target.name,
            slug = project.slug,
            title = project.title,
            gameVersion = gameVersion.trim(),
            loader = loader ?: LoaderKind.FABRIC,
        )
    }

    /** 启用 / 停用：只改文件名，.jar ↔ .jar.disabled。改错了再点一次。 */
    suspend fun toggleEnabled(entry: ModEntry, enabled: Boolean) {
        val target = current ?: run {
            opError = "先选一个实例。"
            return
        }
        opNotice = ""
        opError = ""
        try {
            val renamed = withContext(Dispatchers.IO) {
                ModFileOps.setEnabled(ModScanner.modsDir(InstanceLayouts.of(target).gameDir), entry.fileName, enabled)
            }
            opNotice = (if (enabled) "已启用 " else "已停用 ") + renamed.name
            // 改名之后旧的选择与更新记录都对不上，重扫最省事
            rescanMods()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = e.message ?: e.toString()
        }
    }

    /** 删一个模组。**不进回收站**，所以界面必须二次确认。 */
    suspend fun deleteMod(entry: ModEntry) {
        val target = current ?: run {
            opError = "先选一个实例。"
            return
        }
        opNotice = ""
        opError = ""
        try {
            val removed = withContext(Dispatchers.IO) {
                ModFileOps.delete(ModScanner.modsDir(InstanceLayouts.of(target).gameDir), entry.fileName)
            }
            opNotice = if (removed) "已删除 " + entry.fileName else "文件本来就不在了：" + entry.fileName
            updates = updates.filterNot { it.fileName == entry.fileName }
            rescanMods()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = e.message ?: e.toString()
        }
    }

    /** 检查更新：本地 jar 的 sha1 一批问 Modrinth，按四类结论回填。 */
    suspend fun checkUpdates() {
        val target = current ?: run {
            opError = "先选一个实例。"
            return
        }
        if (gameVersion.isBlank()) {
            opError = "先填游戏版本：不知道给哪个版本查更新，会把别的版本的包当成「最新」。"
            return
        }
        checkingUpdates = true
        opNotice = ""
        opError = ""
        try {
            val found = withContext(Dispatchers.IO) {
                ModUpdateChecker.check(
                    modsDir = ModScanner.modsDir(InstanceLayouts.of(target).gameDir),
                    gameVersion = gameVersion.trim().ifBlank { null },
                    loader = loader ?: LoaderKind.FABRIC,
                ) { opNotice = it }
            }
            updates = found
            val outdated = found.count { it.status == ModUpdateStatus.OUTDATED }
            opNotice = if (found.isEmpty()) {
                "没有可检查的 .jar（停用的模组不算）。"
            } else {
                "查了 " + found.size + " 个，可更新 " + outdated + " 个。"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updates = emptyList()
            opError = e.message ?: e.toString()
        } finally {
            checkingUpdates = false
        }
    }

    /**
     * 查冲突：读每个 jar 的作者声明（Modrinth 的 dependency_type = "incompatible"）。
     *
     * 与桌面端共用 [ModConflictChecker]，措辞也一样：只报「作者声明不兼容」，不下「你必崩」的结论。
     */
    suspend fun checkConflicts() {
        val target = current ?: run {
            conflictError = "先选一个实例。"
            return
        }
        checkingConflicts = true
        conflictError = ""
        conflictProgress = ""
        try {
            val report = withContext(Dispatchers.IO) {
                ModConflictChecker.check(ModScanner.modsDir(InstanceLayouts.of(target).gameDir)) { conflictProgress = it }
            }
            conflictReport = report
            conflictProgress = when {
                report.scanned == 0 -> "这个实例没有可查的 .jar。"
                report.presentConflicts.isNotEmpty() ->
                    "查出 " + report.presentConflicts.size + " 条已经装着的冲突，见下面的卡片。"
                report.conflicts.isNotEmpty() -> "两个都装着的不兼容没发现；另有作者提到但没装的，列在卡片里。"
                else -> "没查到作者声明的不兼容。"
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

    /** 把一条 OUTDATED 换成新版本。旧文件先停用而不是删掉：新版有问题还能开回来。 */
    suspend fun updateOne(result: ModUpdateResult) {
        val target = current ?: return
        val latest = result.latest ?: return
        // 项目详情没查回来时 slug 退回 project_id —— Modrinth 两种都认
        val slug = result.project?.slug ?: result.lookup?.projectId ?: return
        updatingFile = result.fileName
        opNotice = ""
        opError = ""
        try {
            val installed = withContext(Dispatchers.IO) {
                ModInstaller.installFile(InstanceLayouts.of(target).gameDir, slug, latest)
            }
            val modsDir = ModScanner.modsDir(InstanceLayouts.of(target).gameDir)
            val disableNote = withContext(Dispatchers.IO) {
                runCatching {
                    // 新文件名与旧文件同名说明是同一个包，不需要停用
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
                if (it.fileName == result.fileName) it.copy(lookup = it.lookup, latest = latest) else it
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

    LaunchedEffect(Unit) {
        granted = hasFileAccess(context)
        if (granted) rescan()
    }

    /*
     * 装完之后重扫一次 mods/。
     *
     * runner 不知道自己装机时页面在不在，所以只发布一个 doneAt 时间戳，页面回来看到变了就自己重扫；
     * 直接在页面里等安装结果的话，切页回来那次已经拿不到了。
     * 首屏那次 doneAt 还是 0，不会白扫一遍。
     */
    LaunchedEffect(installState.doneAt) {
        if (installState.doneAt > 0L) rescanMods()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("模组", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "每个实例有自己的 mods/。先选实例，下面是它已装的，再往下从 Modrinth 搜。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!granted) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("还没有文件访问权限", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Android 11 起要读 /storage/emulated/0，必须手动开「所有文件访问权限」。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { openAllFilesSettings(context) }) { Text("去授权") }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = root,
                    onValueChange = { root = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("游戏目录") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        granted = hasFileAccess(context)
                        scope.launch { rescan() }
                    },
                    enabled = !scanning && root.isNotBlank(),
                ) { Text(if (scanning) "扫描中…" else "刷新") }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (instances.isEmpty()) {
                        if (scanning) "正在找实例…" else "没有实例。先去「实例」页看看这个目录下有什么。"
                    } else {
                        "装在哪个实例里（共 " + instances.size + " 个）："
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (instances.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(instances.sortedBy { it.loader == LoaderKind.VANILLA }, key = { instanceKey(it) }) { instance ->
                            val key = instanceKey(instance)
                            val active = key == selectedKey
                            OutlinedButton(
                                onClick = {
                                    selectedKey = key
                                    // 换实例等于换游戏版本，之前带出来的版本要跟着换
                                    gameVersion = ""
                                    loaderInstanceKey = ""
                                    results = emptyList()
                                    searched = false
                                    notice = ""
                                    updates = emptyList()
                                    opNotice = ""
                                    opError = ""
                                    scope.launch { rescanMods() }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    instance.name + "  ·  " + instance.gameVersion +
                                        (if (instance.loader == LoaderKind.VANILLA) "  原版" else "  " + loaderLabel(instance.loader)),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("搜模组（sodium、jei、iris）") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = {
                        loader = when (loader) {
                            LoaderKind.FABRIC -> LoaderKind.FORGE
                            LoaderKind.FORGE -> LoaderKind.QUILT
                            LoaderKind.QUILT -> LoaderKind.NEOFORGE
                            else -> LoaderKind.FABRIC
                        }
                    },
                ) { Text(if (loader == null) "不限" else loaderLabel(loader!!)) }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = gameVersion,
                    onValueChange = { gameVersion = it },
                    modifier = Modifier.width(140.dp),
                    label = { Text("游戏版本") },
                    singleLine = true,
                )
                Button(
                    onClick = { scope.launch { search() } },
                    enabled = !searching,
                    modifier = Modifier.weight(1f),
                ) { Text(if (searching) "搜索中…" else "搜索") }
            }
        }

        val snap = snapshot
        if (snap != null && snap.hasLegacy) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "共享 mods/ 里还有 " + snap.legacyCount + " 个模组（隔离前装的）",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "这个实例现在看不到它们。搬过去是复制：不覆盖同名文件，共享目录原件不删。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(" ", modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { scope.launch { adoptLegacy() } }, enabled = !scanning) {
                                Text("搬进这个实例")
                            }
                        }
                    }
                }
            }
        }

        if (error.isNotBlank()) {
            item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (notice.isNotBlank()) {
            item { Text(notice, style = MaterialTheme.typography.bodySmall) }
        }
        if (installState.busy) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "正在装 " + installState.title.ifBlank { installState.busySlug } + "…" +
                            progressDetail(installState.progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { ModInstallRunner.cancel() }) { Text("取消") }
                }
            }
        }
        if (!installState.busy && installState.message.isNotBlank()) {
            item {
                Text(
                    installState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (installState.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        snap == null -> "先选一个实例。"
                        snap.scan.exists ->
                            "这个实例已装 " + snap.scan.totalCount + " 个（启用 " + snap.scan.mods.size +
                                " · 停用 " + snap.scan.disabled.size + "）"
                        else -> "这个实例还没有 mods/（装第一个时自动建）。"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (snap != null) {
                    OutlinedButton(
                        onClick = { scope.launch { checkUpdates() } },
                        enabled = !checkingUpdates && !scanning,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text(if (checkingUpdates) "检查中…" else "检查更新", style = MaterialTheme.typography.labelSmall) }
                    OutlinedButton(
                        onClick = { scope.launch { checkConflicts() } },
                        enabled = !checkingConflicts && !scanning,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text(if (checkingConflicts) "查冲突…" else "查冲突", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        if (snap != null) {
            item {
                Text(
                    snap.modsDir.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (opError.isNotBlank()) {
            item { Text(opError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (opNotice.isNotBlank()) {
            item { Text(opNotice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        if (conflictError.isNotBlank()) {
            item { Text("查冲突失败：" + conflictError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } else if (conflictProgress.isNotBlank()) {
            item { Text(conflictProgress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val report = conflictReport
        if (report != null) {
            item {
                ConflictCard(
                    report = report,
                    onDismiss = {
                        conflictReport = null
                        conflictProgress = ""
                    },
                )
            }
        }

        val installed = snap?.let { it.scan.mods + it.scan.disabled }.orEmpty()
        items(installed, key = { it.path }) { entry ->
            val update = updates.firstOrNull { it.fileName == entry.fileName }
            InstalledModRow(
                entry = entry,
                update = update,
                busy = updatingFile.isNotBlank() || checkingUpdates,
                updating = updatingFile == entry.fileName,
                onToggleEnabled = { enabled -> scope.launch { toggleEnabled(entry, enabled) } },
                onDelete = { scope.launch { deleteMod(entry) } },
                onUpdate = { result -> scope.launch { updateOne(result) } },
            )
        }

        if (searched) {
            item {
                Text(
                    if (results.isEmpty()) "没搜到。换个关键词，或放宽版本 / 加载器过滤。" else "Modrinth 搜索结果",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(results, key = { it.projectId }) { project ->
                SearchResultRow(
                    project = project,
                    busy = installState.busy,
                    onInstall = { scope.launch { install(project) } },
                )
            }
        }
    }
}

private fun loaderLabel(kind: LoaderKind): String = when (kind) {
    LoaderKind.FABRIC -> "Fabric"
    LoaderKind.FORGE -> "Forge"
    LoaderKind.QUILT -> "Quilt"
    LoaderKind.NEOFORGE -> "NeoForge"
    else -> "不限"
}

/** 实例记着的加载器 → 搜索用的加载器。原版实例没有加载器，返回 null 表示不动当前选择。 */
private fun loaderOf(kind: LoaderKind): LoaderKind? = when (kind) {
    LoaderKind.VANILLA, LoaderKind.OTHER -> null
    else -> kind
}

/**
 * 一条已装模组。
 *
 * 勾选框就是启用/停用（.jar ↔ .jar.disabled），删除要二次确认——模组文件不进回收站，
 * 点一下真没了。这两件事以前在手机上只能靠用户自己开文件管理器做。
 */
@Composable
private fun InstalledModRow(
    entry: ModEntry,
    update: ModUpdateResult?,
    busy: Boolean,
    updating: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onUpdate: (ModUpdateResult) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = entry.enabled, onCheckedChange = { onToggleEnabled(it) })
                Text(entry.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (update != null && update.status == ModUpdateStatus.OUTDATED) {
                    Text(
                        "有新版本 " + (update.latestVersion ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    if (entry.enabled) fmtBytes(entry.sizeBytes) else "停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                buildString {
                    append(entry.fileName)
                    entry.loader?.let { append(" · ").append(loaderLabel(it)) }
                    if (!entry.enabled) append(" · 停用中（不会加载）")
                    update?.let {
                        if (it.status == ModUpdateStatus.UP_TO_DATE) append(" · 已是最新 ").append(it.currentVersion)
                        if (it.status == ModUpdateStatus.NOT_ON_MODRINTH) append(" · Modrinth 不认识这个文件")
                        if (it.status == ModUpdateStatus.NO_COMPATIBLE_VERSION) append(" · 没有适配当前版本的更新")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.problem?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("", modifier = Modifier.weight(1f))
                if (update != null && update.status == ModUpdateStatus.OUTDATED) {
                    OutlinedButton(onClick = { onUpdate(update) }, enabled = !busy) {
                        Text(if (updating) "更新中…" else "更新到 " + (update.latestVersion ?: "新版"))
                    }
                }
                TextButton(onClick = { confirmDelete = true }, enabled = !confirmDelete) { Text("删除") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个模组文件？") },
            text = {
                Text(
                    "会直接删掉 " + entry.fileName + "，不进回收站。只是暂时不想用的话，把左边的勾去掉就行。",
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
 * 冲突卡片（手机）。
 *
 * 与桌面端同一套结构与措辞：先「现在就装着」的组合，再「作者提到但没装」的预防性提醒。
 * 手机屏窄，条目一律单行，长文件名靠换行；不放「展开全部」—— 手机滚动本来就便宜，
 * 有条就全列出来，少一个「点开才知道」的隐藏状态。
 */
@Composable
private fun ConflictCard(report: ModConflictReport, onDismiss: () -> Unit) {
    val present = report.presentConflicts
    val potential = report.potentialConflicts

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

            present.forEach { conflict: ModConflict ->
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
                potential.forEach { conflict: ModConflict ->
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
                Text("", modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun SearchResultRow(project: ModProject, busy: Boolean, onInstall: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(project.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    project.downloadsLabel + " 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                project.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    project.slug,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onInstall, enabled = !busy) { Text("装进 mods/") }
            }
        }
    }
}

private fun progressDetail(progress: ModInstallProgress?): String {
    if (progress == null) return ""
    return if (progress.total > 0L) {
        "（" + progress.step.label + " " + (progress.fraction * 100).toInt() + "%）"
    } else {
        "（" + progress.step.label + "）"
    }
}
