package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import io.github.huang404yan.platypus.core.mods.ModInstaller
import io.github.huang404yan.platypus.core.mods.ModProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 模组页：先选实例，左边看这个实例的 mods/ 里有什么，右边从 Modrinth 搜一个装进去。
 *
 * **P1 之后每个实例有自己的 mods 目录**（`<游戏目录>/versions/<id>/mods`），所以这一页
 * 必须先知道操作哪个实例。以前这里是「游戏目录 → 共享 mods/」，隔离后那样写会一边装进
 * 新目录、一边显示旧目录，谁也说不清模组到底装到哪去了。
 *
 * 隔离前留在 `<游戏目录>/mods` 里的那批模组，这里单独提示一行并给「搬进这个实例」，
 * 不静默忽略 —— 用户会以为模组被吞了。
 *
 * 只做「装单个模组」——依赖求解没做（见 ModInstaller 的注释），所以装完会提示
 * requiredDependencies 有几个，让用户自己去装，不假装解决了。
 */
@Composable
fun ModsPage(initialGameRoot: String? = null, backend: LauncherBackend? = null) {
    val scope = rememberCoroutineScope()

    var rootText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var scanError by remember { mutableStateOf("") }
    /** 选中的实例，用 instanceKey 记：切换目录后重新扫描，靠它把选择找回来。 */
    var selectedKey by remember { mutableStateOf("") }

    var gameVersion by remember { mutableStateOf("") }
    var loader by remember { mutableStateOf<LoaderKind?>(LoaderKind.FABRIC) }
    var query by remember { mutableStateOf("") }

    var snapshot by remember { mutableStateOf<InstanceModsSnapshot?>(null) }
    var scanning by remember { mutableStateOf(false) }

    var results by remember { mutableStateOf<List<ModProject>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }

    var installingSlug by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<ModInstallProgress?>(null) }
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
            // 选择失效就退到第一个：先按名字排，原版实例排后面（原版装不了模组）
            selectedKey = merged
                .sortedBy { it.loader == LoaderKind.VANILLA }
                .firstOrNull()
                ?.let { instanceKey(it) }
                .orEmpty()
        }
        return merged
    }

    /** 扫选中实例的 mods/，并把它记着的游戏版本带进搜索条件。 */
    suspend fun rescanMods() {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey }
        if (target == null) {
            snapshot = null
            return
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
        notice = ""
        val result: LegacyAdoptResult? = withContext(Dispatchers.IO) {
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

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模组", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "每个实例有自己的 mods/。左边是这个实例已装的，右边从 Modrinth 搜了装进去。",
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
                results = emptyList()
                searched = false
                notice = ""
                scope.launch { rescanMods() }
            },
        )
        if (scanError.isNotBlank()) ModHint(scanError, error = true)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                label = { Text("在 Modrinth 搜模组（如 sodium、jei、iris）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = gameVersion,
                onValueChange = { gameVersion = it },
                modifier = Modifier.width(180.dp),
                label = { Text("游戏版本（必填）") },
                singleLine = true,
            )
            OutlinedButton(onClick = { loader = nextLoader(loader) }) {
                Text("加载器：" + loaderLabel(loader))
            }
            Button(onClick = { scope.launch { search() } }, enabled = !searching) {
                Text(if (searching) "搜索中…" else "搜索")
            }
        }

        if (notice.isNotBlank()) ModHint(notice)
        if (searchError.isNotBlank()) ModHint("Modrinth：" + searchError, error = true)

        val current = snapshot
        if (current != null && current.hasLegacy) {
            LegacyModsRow(
                snapshot = current,
                busy = scanning,
                onAdopt = { scope.launch { adoptLegacy() } },
            )
        }

        if (current != null) {
            Text(
                if (current.scan.exists) {
                    "这个实例已装 " + current.scan.totalCount + " 个（启用 " + current.scan.mods.size +
                        " · 停用 " + current.scan.disabled.size + "）"
                } else {
                    "这个实例还没有 mods/，装第一个模组时会自动建。"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                current.modsDir.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            current.scan.warnings.forEach { ModHint(it) }
        }

        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地 mods/", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val installed = current?.let { it.scan.mods + it.scan.disabled }.orEmpty()
                    if (installed.isEmpty()) {
                        Box(Modifier.fillMaxSize()) {
                            ModHint(
                                when {
                                    rootText.isBlank() -> "先填游戏目录。"
                                    instances.isEmpty() -> "这个目录下没有实例，先去「实例」页建一个。"
                                    else -> "这个实例还没有模组。"
                                },
                            )
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(installed, key = { it.path }) { entry -> InstalledModRow(entry) }
                        }
                    }
                }
            }

            Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Modrinth 搜索结果",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val running = installingSlug
                    if (running.isNotBlank()) {
                        ModHint("正在装 " + running + "…" + progressDetail(progress))
                    }
                    if (results.isEmpty()) {
                        Box(Modifier.fillMaxSize()) {
                            ModHint(if (searched) "没搜到。换个关键词，或放宽版本 / 加载器过滤。" else "输入名字后点「搜索」。")
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(results, key = { it.projectId }) { project ->
                                SearchResultRow(
                                    project = project,
                                    busy = running.isNotBlank(),
                                    onInstall = { scope.launch { install(project) } },
                                )
                            }
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
private fun LegacyModsRow(snapshot: InstanceModsSnapshot, busy: Boolean, onAdopt: () -> Unit) {
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
                OutlinedButton(onClick = onAdopt, enabled = !busy) { Text("搬进这个实例") }
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

private fun loaderLabel(kind: LoaderKind?): String = when (kind) {
    LoaderKind.FABRIC -> "Fabric"
    LoaderKind.FORGE -> "Forge"
    LoaderKind.QUILT -> "Quilt"
    LoaderKind.NEOFORGE -> "NeoForge"
    else -> "不限"
}

@Composable
private fun InstalledModRow(entry: ModEntry) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    if (entry.enabled) formatBytesShort(entry.sizeBytes) else "停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val detail = buildString {
                append(entry.fileName)
                entry.loader?.let { append(" · ").append(loaderLabel(it)) }
                if (!entry.enabled) append(" · 已停用（改名去掉 .disabled 才会加载）")
            }
            Text(
                detail,
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
