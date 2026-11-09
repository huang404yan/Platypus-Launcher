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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.mods.InstanceMods
import io.github.huang404yan.platypus.core.mods.InstanceModsSnapshot
import io.github.huang404yan.platypus.core.mods.ModEntry
import io.github.huang404yan.platypus.core.mods.ModInstallProgress
import io.github.huang404yan.platypus.core.mods.ModInstaller
import io.github.huang404yan.platypus.core.mods.ModProject
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
    var progress by remember { mutableStateOf<ModInstallProgress?>(null) }
    var installingSlug by remember { mutableStateOf("") }

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
        scanning = true
        try {
            snapshot = withContext(Dispatchers.IO) { runCatching { InstanceMods.scan(target) }.getOrNull() }
            if (gameVersion.isBlank() && target.gameVersion.isNotBlank()) gameVersion = target.gameVersion
        } finally {
            scanning = false
        }
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
        val instanceDir = File(target.directory.trim().trim('"'))
        installingSlug = project.slug
        progress = null
        notice = ""
        try {
            val result = withContext(Dispatchers.IO) {
                ModInstaller.install(
                    gameDir = instanceDir,
                    slug = project.slug,
                    gameVersion = gameVersion.trim(),
                    loader = loader ?: LoaderKind.FABRIC,
                ) { p: ModInstallProgress -> progress = p }
            }
            notice = "已装进 " + target.name + "：" + result.entry.title + " " + result.version +
                "（" + fmtBytes(result.downloadedBytes) + "）" +
                if (result.requiredDependencies.isEmpty()) {
                    "。"
                } else {
                    "。还差 " + result.requiredDependencies.size + " 个前置模组，需要自己再装。"
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
        granted = hasFileAccess(context)
        if (granted) rescan()
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
                                    results = emptyList()
                                    searched = false
                                    notice = ""
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
        if (installingSlug.isNotBlank()) {
            item {
                Text(
                    "正在装 " + installingSlug + "…" + progressDetail(progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
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
            )
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

        val installed = snap?.let { it.scan.mods + it.scan.disabled }.orEmpty()
        items(installed, key = { it.path }) { entry -> InstalledModRow(entry) }

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
                    busy = installingSlug.isNotBlank(),
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

@Composable
private fun InstalledModRow(entry: ModEntry) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    entry.loader?.let { append(" · ").append(loaderLabel(it)) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.problem?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
