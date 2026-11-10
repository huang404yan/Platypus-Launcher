package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.instance.mergeInstances
import io.github.huang404yan.platypus.core.mods.InstanceMods
import io.github.huang404yan.platypus.core.mods.InstanceModsSnapshot
import io.github.huang404yan.platypus.core.mods.LegacyAdoptResult
import io.github.huang404yan.platypus.core.mods.ModEntry
import io.github.huang404yan.platypus.core.mods.ModInstallProgress
import io.github.huang404yan.platypus.core.mods.ModFileOps
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

/** 「已装」二级项的 id，与 Navigation.kt 里 PAGE_SUB_TABS 的定义对应。 */
const val MODS_TAB_INSTALLED = "installed"

/** 「浏览」二级项的 id。 */
const val MODS_TAB_BROWSE = "browse"

/**
 * 模组页：先选实例，然后看这个实例装了什么，或者从 Modrinth 搜一个装进去。
 *
 * P1 之后每个实例有自己的 mods 目录（「游戏目录/versions/实例/mod」），所以这一页必须
 * 先知道操作哪个实例。以前这里是「游戏目录 → 共享 mods/」，隔离后那样写会一边装进
 * 新目录、一边显示旧目录，谁也说不清模组到底装到哪去了。
 *
 * 隔离前留在游戏根目录 mods 里的那批模组，这里单独提示一行并给「搬进这个实例」，
 * 不静默忽略 —— 用户会以为模组被吞了。
 *
 * 只做「装单个模组」——依赖求解没做（见 ModInstaller 的注释），所以装完会提示
 * requiredDependencies 有几个，让用户自己去装，不假装解决了。
 *
 * 二级导航（[subTab]）把这个页面拆成两件事：已装（本地清单）与浏览（联网搜、装）。
 * 以前两栏并排，右边搜完左边才看得见结果；拆开后每件事都能占满整屏，
 * 列表也不用再被 132.dp 那种高度限制挤成一条缝。
 */
@Composable
fun ModsPage(
    initialGameRoot: String? = null,
    backend: LauncherBackend? = null,
    /** [MODS_TAB_INSTALLED] 或 [MODS_TAB_BROWSE]；由 App.kt 从侧边栏的二级导航传进来。 */
    subTab: String = MODS_TAB_INSTALLED,
) {
    val scope = rememberCoroutineScope()

    var rootText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var scanError by remember { mutableStateOf("") }
    /** 选中的实例，用 instanceKey 记：切换目录后重新扫描，靠它把选择找回来。 */
    var selectedKey by remember { mutableStateOf("") }

    var gameVersion by remember { mutableStateOf("") }
    var loader by remember { mutableStateOf<LoaderKind?>(LoaderKind.FABRIC) }

    /**
     * 加载器是跟着哪个实例对齐的（instanceKey）。
     *
     * 用它而不是每次 rescanMods 都覆盖：用户手动轮换过加载器（比如拿 Fabric 实例去搜
     * 一个 Forge 包）的时候，刷一下列表不该把人家刚选的改回去；换了实例才重新对齐。
     */
    var loaderInstanceKey by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    var snapshot by remember { mutableStateOf<InstanceModsSnapshot?>(null) }
    var scanning by remember { mutableStateOf(false) }

    var results by remember { mutableStateOf<List<ModProject>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }

    var installingSlug by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<ModInstallProgress?>(null) }
    /** 安装结果提示，只在「浏览」里显示。 */
    var notice by remember { mutableStateOf("") }
    /** 搬旧模组的结果提示，只在「已装」里显示：两者分开，免得装完模组切到「已装」还读着一句旧提示。 */
    var adoptNotice by remember { mutableStateOf("") }

    /** 启用/停用/删除的结果提示与报错。改文件是即时动作，结果必须当场说，不能只靠列表变样。 */
    var opNotice by remember { mutableStateOf("") }
    var opError by remember { mutableStateOf("") }

    /** 检查更新的四类结果。key 是文件名（同名文件不可能同时存在两份）。 */
    var updates by remember { mutableStateOf<List<ModUpdateResult>>(emptyList()) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf("") }
    var updateError by remember { mutableStateOf("") }
    /** 正在更新哪个文件（用它禁用按钮，避免连点）。 */
    var updatingFile by remember { mutableStateOf("") }

    /** 装完模组后还缺的前置模组（project_id），以及它们的可读名字。 */
    var pendingDeps by remember { mutableStateOf<List<String>>(emptyList()) }
    var depTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var depsInstalling by remember { mutableStateOf(false) }

    /** 扫游戏目录里的实例清单，尽量保住当前选择。 */
    suspend fun rescanInstances(): List<Instance> {
        if (rootText.isBlank()) {
            instances = emptyList()
            snapshot = null
            return emptyList()
        }
        val root = File(rootText.trim())
        val scanned = withContext(Dispatchers.IO) { runCatching { InstanceScanner.scan(root) }.getOrNull() }
        val registered = withContext(Dispatchers.IO) {
            runCatching { backend?.instances?.list() }.getOrNull().orEmpty()
        }
        scanError = when {
            scanned == null -> "读不了这个目录：" + root.absolutePath
            !scanned.isGameDirectory -> "这里不是游戏目录（缺少 versions/、libraries/ 或 assets/）"
            else -> ""
        }
        val merged = mergeInstances(registered, scanned?.instances.orEmpty())
        instances = merged
        if (merged.none { instanceKey(it) == selectedKey }) {
            // 选择失效就退到第一个：先按名字排，原版实例排后面（原版装不了模组）
            selectedKey = merged
                .sortedBy { it.loader == LoaderKind.VANILLA }
                .firstOrNull()
                ?.let { instanceKey(it) }
                .orEmpty()
        }
        return merged
    }

    /**
     * 扫选中实例的 mods/，并把它的游戏版本与加载器带进搜索条件。
     *
     * 加载器必须跟着实例走：默认是 Fabric，切到一个 NeoForge 实例还不改加载器的话，
     * Modrinth 会把 fabric 版的包当成「最新版」—— 界面显示「有新版本 …-fabric」，
     * 装下去这个实例直接起不来。只在换了实例时对齐一次，用户手动轮换过的选择不覆盖。
     */
    suspend fun rescanMods() {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey }
        if (target == null) {
            snapshot = null
            return
        }
        if (loaderInstanceKey != instanceKey(target)) {
            loaderInstanceKey = instanceKey(target)
            loaderOf(target.loader)?.let { loader = it }
        }
        scanning = true
        try {
            snapshot = withContext(Dispatchers.IO) { runCatching { InstanceMods.scan(target) }.getOrNull() }
        } finally {
            scanning = false
        }
        // 实例自己记着 gameVersion，比让用户手打靠谱得多；用户改过的值不覆盖。
        if (gameVersion.isBlank() && target.gameVersion.isNotBlank()) {
            gameVersion = target.gameVersion
        }
    }

    suspend fun adoptLegacy() {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey } ?: return
        adoptNotice = ""
        val result: LegacyAdoptResult? = withContext(Dispatchers.IO) {
            runCatching { InstanceMods.adoptLegacy(target) }.getOrElse { e ->
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
            rescanMods()
        }
    }

    /** 当前选中的实例目录（实例 = 版本目录），没有就返回 null。 */
    fun currentInstanceDir(): File? {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey } ?: return null
        return File(target.directory.trim().trim('"'))
    }

    /**
     * 启用 / 停用：改的是文件名（.jar ↔ .jar.disabled），不是某个配置文件。
     *
     * 沿用 MC 生态的老规矩：游戏只认看得见的 .jar，所以停用就是改个名。好处是用户从
     * 资源管理器里也能看到、也能改回来，不依赖启动器的私有状态。
     */
    suspend fun setEnabled(entry: ModEntry, enabled: Boolean) {
        val dir = currentInstanceDir() ?: run {
            opError = "先选一个实例。"
            return
        }
        opNotice = ""
        opError = ""
        try {
            val renamed = withContext(Dispatchers.IO) {
                ModFileOps.setEnabled(ModScanner.modsDir(dir), entry.fileName, enabled)
            }
            opNotice = (if (enabled) "已启用 " else "已停用 ") + renamed.name
            // 停用/启用会换文件名，旧的选择与更新记录都对不上了，重新扫一遍最省事
            rescanMods()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = e.message ?: e.toString()
        }
    }

    suspend fun deleteMod(entry: ModEntry) {
        val dir = currentInstanceDir() ?: run {
            opError = "先选一个实例。"
            return
        }
        opNotice = ""
        opError = ""
        try {
            val removed = withContext(Dispatchers.IO) {
                ModFileOps.delete(ModScanner.modsDir(dir), entry.fileName)
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

    /** 检查更新：全部模组一批问 Modrinth，按四类结论回填。 */
    suspend fun checkUpdates() {
        val dir = currentInstanceDir() ?: run {
            updateError = "先选一个实例。"
            return
        }
        if (gameVersion.isBlank()) {
            updateError = "先填游戏版本：不知道给哪个版本查更新，会把别的版本的包当成「最新」。"
            return
        }
        checkingUpdates = true
        updateError = ""
        updateProgress = ""
        try {
            val found = withContext(Dispatchers.IO) {
                ModUpdateChecker.check(
                    modsDir = ModScanner.modsDir(dir),
                    gameVersion = gameVersion.trim().ifBlank { null },
                    loader = loader ?: LoaderKind.FABRIC,
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

    /** 把一条 OUTDATED 换成新版本。旧文件先停用而不是直接删：万一新版有问题还能开回来。 */
    suspend fun updateOne(result: ModUpdateResult) {
        val dir = currentInstanceDir() ?: return
        val latest = result.latest ?: return
        val target = instances.firstOrNull { instanceKey(it) == selectedKey } ?: return
        // 项目详情可能没查回来（网络抖一下），这时 slug 退回 project_id —— Modrinth 两种都认
        val slug = result.project?.slug ?: result.lookup?.projectId ?: return
        updatingFile = result.fileName
        opNotice = ""
        opError = ""
        try {
            val installed = withContext(Dispatchers.IO) {
                ModInstaller.installFile(target.directory.let { File(it.trim().trim('"')) }, slug, latest)
            }
            val modsDir = ModScanner.modsDir(dir)
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

    /** 把装模组时报出来的前置模组一次装齐。 */
    suspend fun installDependencies() {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey } ?: return
        if (gameVersion.isBlank()) {
            opError = "先填游戏版本，否则装进来的前置模组跟你的游戏对不上。"
            return
        }
        val kind = loader ?: LoaderKind.FABRIC
        val dir = File(target.directory.trim().trim('"'))
        depsInstalling = true
        opNotice = ""
        opError = ""
        try {
            val projects = withContext(Dispatchers.IO) {
                runCatching { ModUpdateChecker.resolveProjects(pendingDeps) }.getOrNull().orEmpty()
            }
            val done = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (id in pendingDeps) {
                val slug = projects[id]?.slug
                if (slug.isNullOrBlank()) {
                    failed += (depTitles[id] ?: id) + "（Modrinth 查不到这个项目）"
                    continue
                }
                try {
                    val r = withContext(Dispatchers.IO) {
                        ModInstaller.install(dir, slug, gameVersion.trim().ifBlank { null }, kind)
                    }
                    done += r.entry.title
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += (depTitles[id] ?: slug) + "（" + (e.message ?: "失败") + "）"
                }
            }
            opNotice = buildString {
                if (done.isNotEmpty()) {
                    append("装了 ").append(done.size).append(" 个前置：").append(done.joinToString("、")).append("。")
                }
                if (failed.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("还有 ").append(failed.size).append(" 个没装上：").append(failed.joinToString("；"))
                }
                if (failed.isEmpty()) pendingDeps = emptyList()
            }
            rescanMods()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = "装前置失败：" + (e.message ?: e.toString())
        } finally {
            depsInstalling = false
        }
    }

    suspend fun search() {
        val text = query.trim()
        if (text.isBlank()) {
            searchError = "先输入要搜的模组名（如 sodium、jei）。"
            return
        }
        if (gameVersion.isBlank()) {
            // 留空会让 Modrinth 的 versions facet 被省略，pickBest 取到最新版模组（例如 mc26.3 的
            // Sodium）——装进去要等启动崩溃才发现。宁可拦住。
            searchError = if (instances.isEmpty()) {
                "先在游戏目录里选一个实例：没有实例就不知道要给哪个版本找模组。"
            } else {
                "先填游戏版本（如 1.20.1）。留空会搜到最新版模组，跟你的游戏对不上。"
            }
            return
        }
        searching = true
        searchError = ""
        try {
            results = withContext(Dispatchers.IO) {
                ModInstaller.search(text, gameVersion.trim().ifBlank { null }, loader ?: LoaderKind.VANILLA)
            }
            searched = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results = emptyList()
            searched = true
            searchError = e.message ?: e.toString()
        } finally {
            searching = false
        }
    }

    suspend fun install(project: ModProject) {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey }
        if (target == null) {
            notice = "先选一个实例：模组装进实例自己的 mods/，没有实例就没有目标目录。"
            return
        }
        if (gameVersion.isBlank()) {
            notice = "先填游戏版本，否则装进来的模组跟你的游戏对不上。"
            return
        }
        val kind = loader ?: LoaderKind.FABRIC
        val instanceDir = File(target.directory.trim().trim('"'))
        installingSlug = project.slug
        progress = null
        notice = ""
        try {
            val result = withContext(Dispatchers.IO) {
                ModInstaller.install(
                    gameDir = instanceDir,
                    slug = project.slug,
                    gameVersion = gameVersion.trim().ifBlank { null },
                    loader = kind,
                ) { p: ModInstallProgress -> progress = p }
            }
            notice = "已装进 " + target.name + "：" + result.entry.title + " " + result.version +
                "（" + formatBytesShort(result.downloadedBytes) + "）" +
                if (result.requiredDependencies.isEmpty()) {
                    "。"
                } else {
                    "。还差 " + result.requiredDependencies.size + " 个前置模组。"
                }
            // 把前置模组记下来，「已装」页会显示成一个可点的清单。
            // Modrinth 给的是 project_id，这里顺手换成可读名字，免得界面上出现一串随机 ID。
            pendingDeps = result.requiredDependencies
            depTitles = if (pendingDeps.isEmpty()) {
                emptyMap()
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        ModUpdateChecker.resolveProjects(pendingDeps).mapValues { it.value.title }
                    }.getOrNull().orEmpty()
                }
            }
            rescanMods()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = "装 " + project.title + " 失败：" + (e.message ?: e.toString())
        } finally {
            installingSlug = ""
            progress = null
        }
    }

    LaunchedEffect(Unit) {
        val repository = backend?.settings
        if (repository != null) {
            val stored = withContext(Dispatchers.IO) { runCatching { repository.load() }.getOrNull() }
            if (stored != null && initialGameRoot.isNullOrBlank() && stored.defaultGameRoot.isNotBlank()) {
                rootText = stored.defaultGameRoot
            }
        }
        if (rootText.isNotBlank()) {
            rescanInstances()
            rescanMods()
        }
    }

    val current = snapshot

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模组", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (subTab == MODS_TAB_BROWSE) {
                "从 Modrinth 搜一个模组，装进选中实例自己的 mods/。"
            } else {
                "这个实例的 mods/ 里有什么。每个实例一份，互不影响。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = rootText,
                onValueChange = { rootText = it },
                modifier = Modifier.weight(1f),
                label = { Text("游戏目录（.minecraft）") },
                singleLine = true,
            )
            Button(
                onClick = {
                    scope.launch {
                        rescanInstances()
                        rescanMods()
                    }
                },
                enabled = !scanning && rootText.isNotBlank(),
            ) { Text(if (scanning) "扫描中…" else "刷新") }
        }

        InstancePicker(
            instances = instances,
            selectedKey = selectedKey,
            loading = scanning,
            onSelect = {
                selectedKey = it
                // 换实例等于换游戏版本，之前自动带出的版本要跟着换；
                // 用户手填过的内容在这里没法分辨，直接按新实例重置更可预期。
                gameVersion = ""
                // 换实例要重新对齐加载器，见 rescanMods 的说明
                loaderInstanceKey = ""
                results = emptyList()
                searched = false
                notice = ""
                adoptNotice = ""
                scope.launch { rescanMods() }
            },
        )
        if (scanError.isNotBlank()) ModHint(scanError, error = true)

        if (subTab == MODS_TAB_BROWSE) {
            BrowseTab(
                modifier = Modifier.fillMaxWidth().weight(1f),
                query = query,
                onQueryChange = { query = it },
                gameVersion = gameVersion,
                onGameVersionChange = { gameVersion = it },
                loader = loader,
                onCycleLoader = { loader = nextLoader(loader) },
                searching = searching,
                onSearch = { scope.launch { search() } },
                searchError = searchError,
                notice = notice,
                installingSlug = installingSlug,
                progress = progress,
                results = results,
                searched = searched,
                onInstall = { project -> scope.launch { install(project) } },
            )
        } else {
            InstalledTab(
                modifier = Modifier.fillMaxWidth().weight(1f),
                snapshot = current,
                rootText = rootText,
                instanceCount = instances.size,
                adoptNotice = adoptNotice,
                onAdopt = { scope.launch { adoptLegacy() } },
                onRefresh = { scope.launch { rescanMods() } },
                opNotice = opNotice,
                opError = opError,
                onToggleEnabled = { entry, enabled -> scope.launch { setEnabled(entry, enabled) } },
                onDelete = { entry -> scope.launch { deleteMod(entry) } },
                updates = updates,
                checkingUpdates = checkingUpdates,
                updateProgress = updateProgress,
                updateError = updateError,
                updatingFile = updatingFile,
                onCheckUpdates = { scope.launch { checkUpdates() } },
                onUpdate = { result -> scope.launch { updateOne(result) } },
                pendingDeps = pendingDeps,
                depTitles = depTitles,
                depsInstalling = depsInstalling,
                onInstallDeps = { scope.launch { installDependencies() } },
                onDismissDeps = { pendingDeps = emptyList() },
            )
        }
    }
}
/**
 * 「已装」：这个实例的 mods/ 清单，占满整屏。
 *
 * 以前它只有半屏（右半屏留给搜索），列表一长就得在那个小框里滚。
 * 拆成二级之后这里独占内容区，长清单才真的能看。
 */
@Composable
private fun InstalledTab(
    modifier: Modifier,
    snapshot: InstanceModsSnapshot?,
    rootText: String,
    instanceCount: Int,
    adoptNotice: String,
    onAdopt: () -> Unit,
    onRefresh: () -> Unit,
    opNotice: String,
    opError: String,
    onToggleEnabled: (ModEntry, Boolean) -> Unit,
    onDelete: (ModEntry) -> Unit,
    updates: List<ModUpdateResult>,
    checkingUpdates: Boolean,
    updateProgress: String,
    updateError: String,
    updatingFile: String,
    onCheckUpdates: () -> Unit,
    onUpdate: (ModUpdateResult) -> Unit,
    pendingDeps: List<String>,
    depTitles: Map<String, String>,
    depsInstalling: Boolean,
    onInstallDeps: () -> Unit,
    onDismissDeps: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    snapshot == null -> "还没有选实例。"
                    snapshot.scan.exists -> {
                        "这个实例已装 " + snapshot.scan.totalCount + " 个（启用 " + snapshot.scan.mods.size +
                            " · 停用 " + snapshot.scan.disabled.size + "）"
                    }
                    else -> "这个实例还没有 mods/，装第一个模组时会自动建。"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onCheckUpdates, enabled = !checkingUpdates && snapshot != null) {
                Text(if (checkingUpdates) "检查中…" else "检查更新")
            }
            OutlinedButton(onClick = onRefresh) { Text("重新扫描") }
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
        if (pendingDeps.isNotEmpty()) {
            MissingDepsRow(
                titles = pendingDeps.map { depTitles[it] ?: it },
                busy = depsInstalling,
                onInstall = onInstallDeps,
                onDismiss = onDismissDeps,
            )
        }
        if (snapshot != null && snapshot.hasLegacy) {
            LegacyModsRow(snapshot = snapshot, onAdopt = onAdopt)
        }

        Card(
            Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val installed = snapshot?.let { it.scan.mods + it.scan.disabled }.orEmpty()
                if (installed.isEmpty()) {
                    ModHint(
                        when {
                            rootText.isBlank() -> "先填游戏目录。"
                            instanceCount == 0 -> "这个目录下没有实例，先去「实例」页建一个。"
                            else -> "这个实例还没有模组。去左边「浏览」搜一个装进来。"
                        },
                    )
                } else {
                    val updateByFile = updates.associateBy { it.fileName }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(installed, key = { it.path }) { entry ->
                            InstalledModRow(
                                entry = entry,
                                update = updateByFile[entry.fileName],
                                busy = updatingFile.isNotBlank(),
                                updating = updatingFile == entry.fileName,
                                onToggleEnabled = { onToggleEnabled(entry, it) },
                                onDelete = { onDelete(entry) },
                                onUpdate = { onUpdate(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「浏览」：Modrinth 搜索 + 装进当前实例。结果列表占满整屏。 */
@Composable
private fun BrowseTab(
    modifier: Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    gameVersion: String,
    onGameVersionChange: (String) -> Unit,
    loader: LoaderKind?,
    onCycleLoader: () -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    searchError: String,
    notice: String,
    installingSlug: String,
    progress: ModInstallProgress?,
    results: List<ModProject>,
    searched: Boolean,
    onInstall: (ModProject) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                label = { Text("在 Modrinth 搜模组（如 sodium、jei、iris）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = gameVersion,
                onValueChange = onGameVersionChange,
                modifier = Modifier.width(180.dp),
                label = { Text("游戏版本（必填）") },
                singleLine = true,
            )
            OutlinedButton(onClick = onCycleLoader) {
                Text("加载器：" + loaderLabel(loader))
            }
            Button(onClick = onSearch, enabled = !searching) {
                Text(if (searching) "搜索中…" else "搜索")
            }
        }

        if (notice.isNotBlank()) ModHint(notice)
        if (searchError.isNotBlank()) ModHint("Modrinth：" + searchError, error = true)
        if (installingSlug.isNotBlank()) {
            ModHint("正在装 " + installingSlug + "…" + progressDetail(progress))
        }

        Card(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (results.isEmpty()) {
                    ModHint(if (searched) "没搜到。换个关键词，或放宽版本 / 加载器过滤。" else "输入名字后点「搜索」。")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(results, key = { it.projectId }) { project ->
                            SearchResultRow(
                                project = project,
                                busy = installingSlug.isNotBlank(),
                                onInstall = { onInstall(project) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 实例选择条。
 *
 * 横向滚动而不是下拉：实例通常三五个，一眼能看全，也省了展开/收起的状态。
 * 原版实例排后面 —— 它们装不了模组，排在前面只会让人点了再发现不行。
 */
@Composable
private fun InstancePicker(
    instances: List<Instance>,
    selectedKey: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (instances.isEmpty()) {
                if (loading) "正在找实例…" else "没有实例。先去「实例」页新建一个，或者把游戏目录改成装过版本的那个。"
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
                        onClick = { onSelect(key) },
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

private fun nextLoader(current: LoaderKind?): LoaderKind? = when (current) {
    LoaderKind.FABRIC -> LoaderKind.FORGE
    LoaderKind.FORGE -> LoaderKind.QUILT
    LoaderKind.QUILT -> LoaderKind.NEOFORGE
    else -> LoaderKind.FABRIC
}

/** 实例记着的加载器 → 搜索用的加载器。原版实例没有加载器，返回 null 表示不动当前选择。 */
private fun loaderOf(kind: LoaderKind): LoaderKind? = when (kind) {
    LoaderKind.VANILLA, LoaderKind.OTHER -> null
    else -> kind
}

private fun loaderLabel(kind: LoaderKind?): String = when (kind) {
    LoaderKind.FABRIC -> "Fabric"
    LoaderKind.FORGE -> "Forge"
    LoaderKind.QUILT -> "Quilt"
    LoaderKind.NEOFORGE -> "NeoForge"
    else -> "不限"
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
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onUpdate: (ModUpdateResult) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = entry.enabled,
                    onCheckedChange = { onToggleEnabled(it) },
                )
                Text(entry.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (update != null && update.status == ModUpdateStatus.OUTDATED) {
                    Text(
                        "有新版本 " + (update.latestVersion ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    formatBytesShort(entry.sizeBytes),
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

/** 还缺的前置模组：列出来，给一个「一次装齐」。装不上会逐条说原因。 */
@Composable
private fun MissingDepsRow(
    titles: List<String>,
    busy: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "还差 " + titles.size + " 个前置模组",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                titles.joinToString("、") + "。不带前置启动会崩，或者进游戏后功能是空的。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("", modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !busy) { Text("先不管") }
                OutlinedButton(onClick = onInstall, enabled = !busy) {
                    Text(if (busy) "安装中…" else "一次装齐")
                }
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
                    project.downloadsLabel + " 次下载",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            project.author?.let {
                Text("作者 " + it, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                project.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
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

@Composable
private fun ModHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun progressDetail(progress: ModInstallProgress?): String {
    if (progress == null) return ""
    return if (progress.total > 0L) {
        "（" + progress.step.label + " " + (progress.fraction * 100).toInt() + "%）"
    } else {
        "（" + progress.step.label + "）"
    }
}

private fun formatBytesShort(bytes: Long): String = when {
    bytes <= 0L -> "-"
    bytes < 1024L -> bytes.toString() + " B"
    bytes < 1024L * 1024L -> (bytes / 1024L).toString() + " KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
}
