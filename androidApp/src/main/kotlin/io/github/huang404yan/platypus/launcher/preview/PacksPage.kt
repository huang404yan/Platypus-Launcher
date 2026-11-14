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
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 光影 / 资源包页（手机）。
 *
 * 和桌面端 PacksPage 是同一套逻辑（PackScanner / PackFileOps / PackInstaller 三个 core 类
 * 一行没重写），只是手机上放不下桌面的左右双栏，改成一条竖着滚的流：
 * 选实例 → 选光影还是资源包 → 搜索装机 → 下面是已装清单。
 *
 * 桌面上「光影」和「资源包」是侧边栏的两个二级项，手机上侧边栏放不下二级，
 * 所以改成页面顶部两个按钮切换；切过去要重扫一次，否则会挂着另一种包扫出来的清单
 * （桌面端踩过：切到资源包页后，列表里躺着三个光影包）。
 *
 * 光影要先用 Iris 或 OptiFine 才能加载 —— 手机端目前还没有应用内启动游戏的能力
 * （runtime-android 未开始，见 docs/03-luxian-yu-youxianji.md 第 3 节），
 * 所以这一页现在的用处是：在手机上把包装好，等启动能力接上后直接可用。
 */
@Composable
internal fun PacksPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(hasFileAccess(context)) }
    var root by remember { mutableStateOf(defaultGameRoot()) }
    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var selectedKey by remember { mutableStateOf("") }
    var gameVersion by remember { mutableStateOf("") }

    /** 当前看的是光影还是资源包。手机上由顶部两个按钮改这个值。 */
    var kind by remember { mutableStateOf(PackKind.SHADER) }

    var snapshot by remember { mutableStateOf<PackScanResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var opNotice by remember { mutableStateOf("") }
    var opError by remember { mutableStateOf("") }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ModProject>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var installingSlug by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<PackInstallProgress?>(null) }
    var notice by remember { mutableStateOf("") }

    /** 当前选中实例的版本目录；没有就 null。 */
    fun currentInstanceDir(): File? {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey } ?: return null
        return File(target.directory.trim().trim('"'))
    }

    /** 扫游戏目录里的实例，尽量保住当前选择。 */
    suspend fun rescanInstances() {
        val path = root.trim()
        if (path.isEmpty() || !granted) return
        val dir = File(path)
        val scanned = withContext(Dispatchers.IO) {
            val registered = runCatching {
                JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(dir)).list()
            }.getOrNull().orEmpty()
            runCatching { InstanceScanner.scan(dir) }.getOrNull()?.let { scan ->
                scan.copy(instances = mergeInstances(registered, scan.instances))
            }
        }
        val found = scanned?.instances.orEmpty()
        instances = found
        if (found.none { instanceKey(it) == selectedKey }) {
            selectedKey = found.firstOrNull()?.let { instanceKey(it) }.orEmpty()
        }
        // 同名状态字段有两处：这里只报「目录读不了」，搜索错误另有 searchError
        if (scanned == null) {
            searchError = "读不了这个目录：" + path
        } else if (!scanned.isGameDirectory) {
            searchError = "这个目录不是游戏目录（缺少 versions/、libraries/ 或 assets/）。"
        }
    }

    /**
     * 扫选中实例的 shaderpacks/ 或 resourcepacks/。
     *
     * 换实例时要把 gameVersion 清掉（见下面的点击回调）—— 1.20.1 的资源包放到 1.21 上
     * pack_format 就对不上，带着上一个实例的版本去搜会装错。
     */
    suspend fun rescanPacks() {
        val dir = currentInstanceDir()
        if (dir == null) {
            snapshot = null
            return
        }
        scanning = true
        try {
            snapshot = withContext(Dispatchers.IO) { runCatching { PackScanner.scan(kind, dir) }.getOrNull() }
            val target = instances.firstOrNull { instanceKey(it) == selectedKey }
            if (gameVersion.isBlank() && target != null && target.gameVersion.isNotBlank()) {
                gameVersion = target.gameVersion
            }
        } finally {
            scanning = false
        }
    }

    suspend fun rescan() {
        rescanInstances()
        rescanPacks()
    }

    /** 启用 / 停用：改文件名，x.zip 与 x.zip.disabled 之间来回。文件夹形态的包会失败，原因显示出来。 */
    suspend fun toggleEnabled(entry: PackEntry, enabled: Boolean) {
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
            // 改名之后旧的条目、旧的选择都对不上，重扫最省事
            rescanPacks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = e.message ?: e.toString()
        }
    }

    /** 删一个包。**不进回收站**，所以界面必须二次确认。 */
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
            opNotice = if (removed) "已删除 " + entry.fileName else "文件本来就不在了：" + entry.fileName
            rescanPacks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            opError = e.message ?: e.toString()
        }
    }

    suspend fun search() {
        val text = query.trim()
        searchError = ""
        notice = ""
        if (text.isBlank()) {
            searchError = "先输入关键词，比如 " + packExampleOf(kind)
            return
        }
        if (gameVersion.isBlank()) {
            searchError = "先填游戏版本（如 1.20.1）。留空会搜到最新版的包，跟你的游戏对不上。"
            return
        }
        searching = true
        try {
            results = withContext(Dispatchers.IO) {
                PackInstaller.search(text, gameVersion.trim(), kind)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results = emptyList()
            searchError = e.message ?: e.toString()
        } finally {
            searched = true
            searching = false
        }
    }

    suspend fun install(project: ModProject) {
        val dir = currentInstanceDir() ?: run {
            notice = "先选一个实例：包要装进实例自己的 " + kind.dirName + "/，没有实例就没有目标目录。"
            return
        }
        installingSlug = project.slug
        progress = null
        notice = ""
        try {
            val result = withContext(Dispatchers.IO) {
                PackInstaller.install(dir, project.slug, gameVersion.trim().ifBlank { null }, kind) { p ->
                    progress = p
                }
            }
            notice = "已装进 " + (instances.firstOrNull { instanceKey(it) == selectedKey }?.name ?: "实例") +
                "：" + result.entry.fileName + "（" + result.version + "，" +
                fmtBytes(result.downloadedBytes) + "）"
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

    /** 首屏扫完了没有。用来区分「首屏」和「切光影 / 资源包」两种重扫。 */
    var booted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        granted = hasFileAccess(context)
        if (granted) rescan()
        booted = true
    }

    /*
     * 切「光影 / 资源包」必须重扫一次，并清掉上一种包的搜索结果。
     *
     * 只靠上面那个 LaunchedEffect(Unit) 不够：它的 key 是 Unit，切按钮只会重组、不会重启它，
     * 于是资源包页会一直挂着光影扫出来的清单（桌面端就是这里踩的坑）。
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 别拼 kind.label + "包"：「资源包」+「包」会变成「资源包包」（桌面端实测出来的）
                Text(
                    if (kind == PackKind.RESOURCE_PACK) "资源包" else "光影包",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "每个实例有自己的 " + kind.dirName + "/。装进实例目录，互不影响。" +
                        if (kind == PackKind.SHADER) "光影要先用 Iris 或 OptiFine 才能加载。" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PackKind.entries.forEach { option ->
                    val active = option == kind
                    OutlinedButton(
                        onClick = { if (!active) kind = option },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            if (option == PackKind.RESOURCE_PACK) "资源包" else "光影包",
                            style = MaterialTheme.typography.labelLarge,
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

        if (!granted) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        items(instances, key = { instanceKey(it) }) { instance ->
                            val key = instanceKey(instance)
                            val active = key == selectedKey
                            OutlinedButton(
                                onClick = {
                                    selectedKey = key
                                    // 换实例等于换游戏版本，之前带出来的版本要跟着换
                                    gameVersion = ""
                                    results = emptyList()
                                    searched = false
                                    notice = ""
                                    opNotice = ""
                                    opError = ""
                                    scope.launch { rescanPacks() }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    instance.name + "  ·  " + instance.gameVersion +
                                        (if (instance.loader == LoaderKind.VANILLA) "  原版" else "  " + packLoaderLabel(instance.loader)),
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

        if (searchError.isNotBlank() && !searched) {
            item { Text(searchError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("搜" + packLabelOf(kind) + "（" + packExampleOf(kind) + "）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = gameVersion,
                    onValueChange = { gameVersion = it },
                    modifier = Modifier.width(130.dp),
                    label = { Text("游戏版本") },
                    singleLine = true,
                )
            }
        }

        item {
            Button(
                onClick = { scope.launch { search() } },
                enabled = !searching,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (searching) "搜索中…" else "在 Modrinth 搜索") }
        }

        if (notice.isNotBlank()) {
            item { Text(notice, style = MaterialTheme.typography.bodySmall) }
        }
        if (searchError.isNotBlank() && searched) {
            item {
                Text(
                    "Modrinth：" + searchError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (installingSlug.isNotBlank()) {
            item {
                Text(
                    "正在装 " + installingSlug + "…" + packProgressDetail(progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    if (snapshot == null) "已装" else "已装 · " + snapshot!!.totalCount + " 个",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { scope.launch { rescanPacks() } },
                    enabled = !scanning,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("重新扫描", style = MaterialTheme.typography.labelSmall) }
            }
        }
        snapshot?.let {
            item {
                Text(
                    it.dir.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (opError.isNotBlank()) {
            item { Text(opError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (opNotice.isNotBlank()) {
            item {
                Text(
                    opNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val snap = snapshot
        if (snap != null && !snap.exists) {
            item {
                Text(
                    "这个实例还没有 " + snap.dir.name + "/ 目录。从上面搜一个装进来，或者把 .zip 手动放进去。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (snap != null && snap.totalCount == 0) {
            item {
                Text(
                    "空的。从上面搜一个装进来，或者把 .zip 手动放进 " + snap.dir.name + "/。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val installed = snap?.let { it.packs + it.disabled }.orEmpty()
        items(installed, key = { it.path }) { entry ->
            InstalledPackRow(
                entry = entry,
                onToggleEnabled = { enabled -> scope.launch { toggleEnabled(entry, enabled) } },
                onDelete = { scope.launch { deletePack(entry) } },
            )
        }

        snap?.warnings?.forEach { warning ->
            item {
                Text(
                    warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (searched) {
            item {
                Text(
                    if (results.isEmpty()) "没搜到。换个关键词，或者确认游戏版本填对了。" else "Modrinth 搜索结果",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(results, key = { it.projectId }) { project ->
                SearchResultRow(
                    project = project,
                    kind = kind,
                    busy = installingSlug.isNotBlank(),
                    onInstall = { scope.launch { install(project) } },
                )
            }
        }
    }
}

private fun packLabelOf(kind: PackKind): String = if (kind == PackKind.RESOURCE_PACK) "资源包" else "光影"

/** 搜索框里的示例关键词：光影和资源包的热门包完全不同。 */
private fun packExampleOf(kind: PackKind): String =
    if (kind == PackKind.RESOURCE_PACK) "fresh、faithful、xali" else "complementary、bsl、photon"

/** 实例标签上的加载器名。模组页那个是 private，这里留一份。 */
private fun packLoaderLabel(kind: LoaderKind): String = when (kind) {
    LoaderKind.FABRIC -> "Fabric"
    LoaderKind.FORGE -> "Forge"
    LoaderKind.QUILT -> "Quilt"
    LoaderKind.NEOFORGE -> "NeoForge"
    LoaderKind.OPTIFINE -> "OptiFine"
    else -> "不限"
}

private fun packProgressDetail(progress: PackInstallProgress?): String {
    if (progress == null) return ""
    return if (progress.total > 0L) {
        "（" + progress.step + " " + (progress.fraction * 100).toInt() + "%）"
    } else {
        "（" + progress.step + "）"
    }
}

/**
 * 一条已装的包。
 *
 * 勾选框就是启用/停用，删除要二次确认——包文件不进回收站，点一下真没了。
 * 文件夹形态的包勾了会失败，原因由 PackFileOps 抛上来显示在下面一行。
 */
@Composable
private fun InstalledPackRow(
    entry: PackEntry,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = entry.enabled, onCheckedChange = { onToggleEnabled(it) })
                Text(entry.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    if (entry.enabled) fmtBytes(entry.sizeBytes) else "停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                buildString {
                    append(entry.fileName)
                    entry.packFormat?.let { append(" · pack_format ").append(it) }
                    if (entry.isDirectory) append(" · 文件夹")
                    if (!entry.enabled) append(" · 停用中（不会加载）")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.problem?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("", modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmDelete = true }, enabled = !confirmDelete) { Text("删除") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删掉这个包？") },
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

@Composable
private fun SearchResultRow(
    project: ModProject,
    kind: PackKind,
    busy: Boolean,
    onInstall: () -> Unit,
) {
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
                Button(onClick = onInstall, enabled = !busy) { Text("装进 " + kind.dirName + "/") }
            }
        }
    }
}
