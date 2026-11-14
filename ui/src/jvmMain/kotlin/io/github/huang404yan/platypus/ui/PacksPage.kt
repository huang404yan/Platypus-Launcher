package io.github.huang404yan.platypus.ui

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
import io.github.huang404yan.platypus.core.mods.ModProject
import io.github.huang404yan.platypus.core.packs.PackEntry
import io.github.huang404yan.platypus.core.packs.PackFileOps
import io.github.huang404yan.platypus.core.packs.PackInstallProgress
import io.github.huang404yan.platypus.core.packs.PackInstaller
import io.github.huang404yan.platypus.core.packs.PackKind
import io.github.huang404yan.platypus.core.packs.PackScanner
import io.github.huang404yan.platypus.core.packs.PackScanResult
import io.github.huang404yan.platypus.core.search.SearchTerm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 以前这里有 PACKS_TAB_SHADERS / PACKS_TAB_RESOURCEPACKS 两个二级项 id。
// 现在光影与资源包是「下载」下面的两个平级项，页面直接收 PackKind，不再靠字符串猜。

/**
 * 光影 / 资源包页。
 *
 * 和模组页是同一套形状（选实例 → 看已装 / 联网搜着装），差在三个地方：
 * 1. 落盘目录是 `shaderpacks/` 或 `resourcepacks/`，不是 `mods/`；
 * 2. 文件是 `.zip`，停用后缀是 `.zip.disabled`（[PackFileOps] 那套）；
 * 3. 光影没有依赖关系，不用展开依赖树 —— 装了就能用，缺前置只可能是没装 Iris 本体。
 *
 * 为什么不复用一个「模组 / 光影 / 资源包」通用页：三者的元数据读法完全不同（模组读 jar 里的
 * fabric.mod.json，资源包读 pack.mcmeta，光影只能看有没有 shaders/），硬塞进一个页面会让
 * 每一处都变成 if 判断。
 */
@Composable
fun PacksPage(
    initialGameRoot: String? = null,
    backend: LauncherBackend? = null,
    /** 光影还是资源包。由 App.kt 按侧栏点的是哪一项决定。 */
    kind: PackKind = PackKind.SHADER,
    /** 左栏「刷新」按钮的信号：每点一次加一，页面拿它当 LaunchedEffect 的 key。 */
    refreshTick: Int = 0,
) {
    val scope = rememberCoroutineScope()

    var rootText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var scanError by remember { mutableStateOf("") }
    /** 选中的实例，用 instanceKey 记：切换目录后重新扫描，靠它把选择找回来。 */
    var selectedKey by remember { mutableStateOf("") }
    /** 搜索与安装用的游戏版本，切实例时自动带出实例自己的值。 */
    var gameVersion by remember { mutableStateOf("") }

    var snapshot by remember { mutableStateOf<PackScanResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var opNotice by remember { mutableStateOf("") }
    var opError by remember { mutableStateOf("") }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ModProject>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var installingSlug by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<PackInstallProgress?>(null) }
    var notice by remember { mutableStateOf("") }

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
            selectedKey = merged.firstOrNull()?.let { instanceKey(it) }.orEmpty()
        }
        return merged
    }

    /** 扫选中实例的 shaderpacks/ 或 resourcepacks/。 */
    suspend fun rescanPacks() {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey }
        if (target == null) {
            snapshot = null
            return
        }
        scanning = true
        try {
            val dir = File(target.directory.trim().trim('"'))
            snapshot = withContext(Dispatchers.IO) { runCatching { PackScanner.scan(kind, dir) }.getOrNull() }
        } finally {
            scanning = false
        }
        if (gameVersion.isBlank() && target.gameVersion.isNotBlank()) {
            gameVersion = target.gameVersion
        }
    }

    /** 当前选中的实例目录（实例 = 版本目录），没有就返回 null。 */
    fun currentInstanceDir(): File? {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey } ?: return null
        return File(target.directory.trim().trim('"'))
    }

    /**
     * 启用 / 停用：改的是文件名（.zip ↔ .zip.disabled）。
     *
     * 文件夹形态的包这里会失败并把原因显示出来 —— 对目录改名游戏照样认，假装停用只会让人困惑。
     */
    suspend fun setEnabled(entry: PackEntry, enabled: Boolean) {
        val dir = currentInstanceDir() ?: run {
            opError = "先选一个实例。"
            return
        }
        opNotice = ""
        opError = ""
        try {
            val renamed = withContext(Dispatchers.IO) {
                PackFileOps.setEnabled(PackScanner.dirOf(kind, dir), entry.fileName, enabled)
            }
            opNotice = (if (enabled) "已启用 " else "已停用 ") + renamed.name
            rescanPacks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = e.message ?: e.toString()
        }
    }

    suspend fun deletePack(entry: PackEntry) {
        val dir = currentInstanceDir() ?: run {
            opError = "先选一个实例。"
            return
        }
        opNotice = ""
        opError = ""
        try {
            val removed = withContext(Dispatchers.IO) {
                PackFileOps.delete(PackScanner.dirOf(kind, dir), entry.fileName)
            }
            opNotice = if (removed) "已删除 " + entry.fileName else "文件已经不在了：" + entry.fileName
            rescanPacks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = e.message ?: e.toString()
        }
    }

    suspend fun search() {
        searchError = ""
        notice = ""
        val text = query.trim()
        if (text.isEmpty()) {
            searchError = "先输入关键词，比如 " + if (kind == PackKind.SHADER) "complementary、bsl、photon" else "fresh、faithful、xali"
            return
        }
        if (gameVersion.isBlank()) {
            // 包对版本敏感：1.20.1 的资源包放到 1.21 上 pack_format 就对不上
            searchError = "先填游戏版本（如 1.20.1）。留空会搜到最新版的包，跟你的游戏对不上。"
            return
        }
        searching = true
        try {
            results = PackInstaller.search(text, gameVersion.trim(), kind)
            searched = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            searchError = e.message ?: e.toString()
            results = emptyList()
            searched = true
        } finally {
            searching = false
        }
    }

    suspend fun install(project: ModProject) {
        val dir = currentInstanceDir() ?: run {
            notice = "先选一个实例。"
            return
        }
        notice = ""
        installingSlug = project.slug
        progress = null
        try {
            val result = PackInstaller.install(dir, project.slug, gameVersion.trim().ifBlank { null }, kind) {
                progress = it
            }
            notice = "已装 " + result.entry.fileName + "（" + result.version + "，" +
                formatBytesShort(result.downloadedBytes) + "）"
            // 装完立刻刷新已装清单，切过去就能看见
            rescanPacks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = "装 " + project.title + " 失败：" + (e.message ?: e.toString())
        } finally {
            installingSlug = ""
            progress = null
        }
    }

    /** 首次组合走完没有。用来区分「首屏」和「切二级页」两种重扫。 */
    var booted by remember { mutableStateOf(false) }

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
            rescanPacks()
        }
        booted = true
    }

    /*
     * 切换「光影 / 资源包」时必须重扫一次。
     *
     * 只靠上面那个 LaunchedEffect(Unit) 不够：它的 key 是 Unit，二级页切换只会重组、不会重启它，
     * 于是资源包页会一直挂着光影页扫出来的清单（实测：切到「资源包」后，标题写「资源包包」，
     * 已装列表里躺着三个光影包、路径还是 .../shaderpacks）。
     *
     * 顺手把上一次的搜索结果清掉，否则搜「complementary」的结果会跟着跑到资源包页去。
     */
    LaunchedEffect(kind) {
        if (!booted) return@LaunchedEffect
        results = emptyList()
        searched = false
        searchError = ""
        notice = ""
        opNotice = ""
        opError = ""
        rescanPacks()
    }

    // 左栏那个「↻」：把实例和已装清单都重扫一遍。tick 为 0 是首次组合，不重复扫。
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0 && rootText.isNotBlank()) {
            rescanInstances()
            rescanPacks()
        }
    }

    val current = snapshot

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 别拼 kind.label + "包"：「资源包」+「包」会变成「资源包包」（实测出来的）
        Text(
            if (kind == PackKind.RESOURCE_PACK) "资源包" else "光影包",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (kind == PackKind.RESOURCE_PACK) {
                "从 Modrinth 搜资源包，装进选中实例的 resourcepacks/。"
            } else {
                "从 Modrinth 搜光影，装进选中实例的 shaderpacks/。要先用 Iris 或 OptiFine 才能加载。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = rootText,
                onValueChange = { rootText = it },
                modifier = Modifier.weight(1f),
                label = { Text("游戏目录（.minecraft）") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    pickDirectory(rootText)?.let {
                        rootText = it.absolutePath
                        scope.launch { rescanInstances(); rescanPacks() }
                    }
                },
            ) { Text("选择…") }
            // 重扫按钮在左栏「下载 / 光影包」或「资源包」那一项上（↻）；这里不再放第二个入口。
            if (scanning) {
                Text(
                    "扫描中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PackInstancePicker(
            instances = instances,
            selectedKey = selectedKey,
            loading = scanning,
            onSelect = {
                selectedKey = it
                gameVersion = ""
                results = emptyList()
                searched = false
                notice = ""
                searchError = ""
                scope.launch { rescanPacks() }
            },
        )
        if (scanError.isNotBlank()) PackHint(scanError, error = true)

        BrowsePacksTab(
            modifier = Modifier.fillMaxWidth().weight(1f),
            kind = kind,
            kindLabel = kind.label,
            query = query,
            onQueryChange = { query = it },
            gameVersion = gameVersion,
            onGameVersionChange = { gameVersion = it },
            searching = searching,
            onSearch = { scope.launch { search() } },
            searchError = searchError,
            notice = notice,
            installingSlug = installingSlug,
            progress = progress,
            results = results,
            searched = searched,
            onInstall = { project -> scope.launch { install(project) } },
            installedModifier = null,
            snapshot = current,
            opNotice = opNotice,
            opError = opError,
            onRefresh = { scope.launch { rescanPacks() } },
            onToggleEnabled = { entry, enabled -> scope.launch { setEnabled(entry, enabled) } },
            onDelete = { entry -> scope.launch { deletePack(entry) } },
            kindDirName = kind.dirName,
        )
    }
}

/**
 * 实例选择条。与 ModsPage 里那条同形，但不排「原版靠后」—— 原版实例照样能用资源包，
 * 光影还要看有没有装 Iris，这一点在选择前无法可靠判断，就不做排序暗示。
 */
@Composable
private fun PackInstancePicker(
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
                items(instances, key = { instanceKey(it) }) { instance ->
                    val key = instanceKey(instance)
                    val active = key == selectedKey
                    OutlinedButton(
                        onClick = { onSelect(key) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            instance.name + "  ·  " + instance.gameVersion +
                                if (instance.loader == LoaderKind.VANILLA) "  原版" else "  " + loaderLabelOf(instance.loader),
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

/**
 * 一屏两栏：上面搜 / 装，下面这个实例已经装了什么。
 *
 * 与模组页不同，这里没有二级切分 —— 光影和资源包通常只有几个，一次看完比来回切省事。
 */
@Composable
private fun BrowsePacksTab(
    modifier: Modifier,
    kind: PackKind,
    kindLabel: String,
    query: String,
    onQueryChange: (String) -> Unit,
    gameVersion: String,
    onGameVersionChange: (String) -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    searchError: String,
    notice: String,
    installingSlug: String,
    progress: PackInstallProgress?,
    results: List<ModProject>,
    searched: Boolean,
    onInstall: (ModProject) -> Unit,
    /** 预留：调用方若想单独渲染已装区可以传自己的 Modifier；当前页面总是接在下面。 */
    installedModifier: Modifier?,
    snapshot: PackScanResult?,
    opNotice: String,
    opError: String,
    onRefresh: () -> Unit,
    onToggleEnabled: (PackEntry, Boolean) -> Unit,
    onDelete: (PackEntry) -> Unit,
    kindDirName: String,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                // 例子分开算：直接写成 a + if (…) x else y + z 会被解析成 a + (if … else (y + z))
                label = { Text("在 Modrinth 搜" + kindLabel + "（如 " + packExampleOf(kindLabel) + "）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = gameVersion,
                onValueChange = onGameVersionChange,
                modifier = Modifier.width(180.dp),
                label = { Text("游戏版本（必填）") },
                singleLine = true,
            )
            Button(onClick = onSearch, enabled = !searching) {
                Text(if (searching) "搜索中…" else "搜索")
            }
        }

        // 光影/资源包的中文叫法比模组更常用（「互补」「原味素材」），中文关键词一律搜不到。
        ChineseSearchHint(
            query = query,
            kind = if (kind == PackKind.SHADER) SearchTerm.Kind.SHADER else SearchTerm.Kind.RESOURCE_PACK,
            onPick = onQueryChange,
        )

        if (notice.isNotBlank()) PackHint(notice)
        if (searchError.isNotBlank()) PackHint("Modrinth：" + searchError, error = true)
        if (installingSlug.isNotBlank()) {
            PackHint("正在装 " + installingSlug + "…" + packProgressDetail(progress))
        }

        if (results.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().weight(0.45f)) {
                Column(Modifier.fillMaxSize().padding(10.dp)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(results, key = { it.projectId }) { project ->
                            PackSearchRow(
                                project = project,
                                busy = installingSlug.isNotBlank(),
                                targetDir = kindDirName,
                                onInstall = { onInstall(project) },
                            )
                        }
                    }
                }
            }
        } else if (searched) {
            PackHint("没搜到。换个关键词，或者确认游戏版本填对了。")
        }

        InstalledPacksColumn(
            modifier = installedModifier ?: Modifier.fillMaxWidth().weight(1f),
            snapshot = snapshot,
            opNotice = opNotice,
            opError = opError,
            onRefresh = onRefresh,
            onToggleEnabled = onToggleEnabled,
            onDelete = onDelete,
        )
    }
}

/** 已装清单：卡片列表 + 启停勾选 + 二次确认删除。 */
@Composable
private fun InstalledPacksColumn(
    modifier: Modifier,
    snapshot: PackScanResult?,
    opNotice: String,
    opError: String,
    onRefresh: () -> Unit,
    onToggleEnabled: (PackEntry, Boolean) -> Unit,
    onDelete: (PackEntry) -> Unit,
) {
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (snapshot == null) "已装" else "已装 · " + snapshot.totalCount + " 个",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    snapshot?.let {
                        Text(
                            it.dir.absolutePath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(onClick = onRefresh) { Text("重新扫描") }
            }
            if (opNotice.isNotBlank()) PackHint(opNotice)
            if (opError.isNotBlank()) PackHint(opError, error = true)

            val current = snapshot
            when {
                current == null -> PackHint("先选一个实例。")
                !current.exists -> PackHint("这个实例还没有 " + current.dir.name + "/ 目录。从上面搜一个装进来，或者把 .zip 手动放进去。")
                current.totalCount == 0 -> PackHint("空的。从上面搜一个装进来，或者把 .zip 手动放进 " + current.dir.name + "/。")
                else -> {
                    val all = current.packs + current.disabled
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(all, key = { it.path }) { entry ->
                            InstalledPackRow(
                                entry = entry,
                                onToggleEnabled = { onToggleEnabled(entry, it) },
                                onDelete = { onDelete(entry) },
                            )
                        }
                    }
                    current.warnings.forEach { PackHint(it) }
                }
            }
        }
    }
}

@Composable
private fun InstalledPackRow(
    entry: PackEntry,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = entry.enabled, onCheckedChange = { onToggleEnabled(it) })
                Text(entry.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = true }) { Text("删除") }
            }
            entry.displayName?.let {
                Text(
                    entry.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                buildString {
                    append(formatBytesShort(entry.sizeBytes))
                    entry.packFormat?.let { append("  ·  pack_format ").append(it) }
                    if (entry.isDirectory) append("  ·  文件夹")
                    if (!entry.enabled) append("  ·  已停用")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.problem?.let { PackHint(it, error = true) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删掉 " + entry.fileName + "？") },
            text = { Text("会直接删掉这个文件，不进回收站。只是暂时不想用的话，把左边的勾去掉就行。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PackSearchRow(project: ModProject, busy: Boolean, targetDir: String, onInstall: () -> Unit) {
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
                Button(onClick = onInstall, enabled = !busy) { Text("装进 " + targetDir + "/") }
            }
        }
    }
}

@Composable
private fun PackHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun packProgressDetail(progress: PackInstallProgress?): String {
    if (progress == null) return ""
    return if (progress.total > 0L) {
        "（" + progress.step + " " + (progress.fraction * 100).toInt() + "%）"
    } else {
        "（" + progress.step + "）"
    }
}

private fun formatBytesShort(bytes: Long): String = when {
    bytes <= 0L -> "-"
    bytes < 1024L -> bytes.toString() + " B"
    bytes < 1024L * 1024L -> (bytes / 1024L).toString() + " KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 搜索框里的示例关键词：光影和资源包的热门包完全不同。 */
private fun packExampleOf(kindLabel: String): String =
    if (kindLabel == "光影") "complementary、bsl、photon" else "fresh、faithful、xali"

/** 加载器名：光影页的实例标签上要显示，模组页那个是 private，这里留一份。 */
private fun loaderLabelOf(kind: LoaderKind): String = when (kind) {
    LoaderKind.FABRIC -> "Fabric"
    LoaderKind.FORGE -> "Forge"
    LoaderKind.QUILT -> "Quilt"
    LoaderKind.NEOFORGE -> "NeoForge"
    LoaderKind.OPTIFINE -> "OptiFine"
    else -> "不限"
}
