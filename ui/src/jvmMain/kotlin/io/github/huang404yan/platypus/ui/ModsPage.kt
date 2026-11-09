package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.mods.ModEntry
import io.github.huang404yan.platypus.core.mods.ModScanResult
import io.github.huang404yan.platypus.core.mods.ModScanner
import io.github.huang404yan.platypus.core.mods.ModInstallProgress
import io.github.huang404yan.platypus.core.mods.ModInstaller
import io.github.huang404yan.platypus.core.mods.ModProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 模组页：左边看 mods/ 里已经装了什么，右边从 Modrinth 搜一个装进去。
 *
 * 只做「装单个模组」——依赖求解没做（见 ModInstaller 的注释），所以装完会提示
 * requiredDependencies 有几个，让用户自己去装，不假装解决了。
 */
@Composable
fun ModsPage(initialGameRoot: String? = null, backend: LauncherBackend? = null) {
    val scope = rememberCoroutineScope()

    var rootText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    var gameVersion by remember { mutableStateOf("") }
    // 游戏目录里扫出来的版本（同一个游戏目录下可能有 1.20.1、1.20.1-forge-47.4.28 等多个）。
    // 用来给「游戏版本」填空：留空去搜会把最新版模组装进来（实测装进了 mc26.3 的 Sodium，
    // 而启动的是 1.20.1），所以这里主动把可用版本列出来，能填就自动填。
    var knownVersions by remember { mutableStateOf<List<String>>(emptyList()) }
    // 过滤用的加载器：null 表示不限。
    var loader by remember { mutableStateOf<LoaderKind?>(LoaderKind.FABRIC) }
    var query by remember { mutableStateOf("") }

    var scan by remember { mutableStateOf<ModScanResult?>(null) }
    var scanning by remember { mutableStateOf(false) }

    var results by remember { mutableStateOf<List<ModProject>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }

    var installingSlug by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<ModInstallProgress?>(null) }
    var notice by remember { mutableStateOf("") }

    val gameDir = File(rootText.trim().ifBlank { "." })

    suspend fun rescan() {
        if (rootText.isBlank()) {
            scan = null
            return
        }
        scanning = true
        try {
            scan = withContext(Dispatchers.IO) { ModScanner.scan(gameDir) }
        } finally {
            scanning = false
        }
        knownVersions = withContext(Dispatchers.IO) {
            runCatching { instanceGameVersions(gameDir) }.getOrDefault(emptyList())
        }
        // 只在自己还空着的时候自动带出，不覆盖用户手填的值。
        if (gameVersion.isBlank()) {
            knownVersions.firstOrNull()?.let { gameVersion = it }
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
            searchError = if (knownVersions.isEmpty()) {
                "先填游戏版本（如 1.20.1），或者把游戏目录指到一个装过版本的 .minecraft。留空会搜到最新版模组，跟你的游戏对不上。"
            } else {
                "先选游戏版本：这个目录下有 " + knownVersions.take(4).joinToString("、") + "。"
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
        if (rootText.isBlank()) {
            notice = "先填游戏目录。"
            return
        }
        if (gameVersion.isBlank()) {
            notice = "先填游戏版本，否则装进来的模组跟你的游戏对不上。"
            return
        }
        val kind = loader ?: LoaderKind.FABRIC
        installingSlug = project.slug
        progress = null
        notice = ""
        try {
            val result = withContext(Dispatchers.IO) {
                ModInstaller.install(
                    gameDir = gameDir,
                    slug = project.slug,
                    gameVersion = gameVersion.trim().ifBlank { null },
                    loader = kind,
                ) { p: ModInstallProgress -> progress = p }
            }
            notice = "已装 " + result.entry.title + " " + result.version +
                "（" + formatBytesShort(result.downloadedBytes) + "）" +
                if (result.requiredDependencies.isEmpty()) {
                    "。"
                } else {
                    "。还差 " + result.requiredDependencies.size + " 个前置模组，需要自己再装。"
                }
            rescan()
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
        if (rootText.isNotBlank()) rescan()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模组", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "mods/ 在游戏目录下，所有实例共用。左边是本地已装的，右边从 Modrinth 搜。",
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
                onClick = { scope.launch { rescan() } },
                enabled = !scanning && rootText.isNotBlank(),
            ) { Text(if (scanning) "扫描中…" else "刷新已装") }
        }

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

        // 游戏目录里已经有的版本，点一下直接填进「游戏版本」。
        // 模组版本强绑定 MC 版本，让用户手打容易打错或干脆留空。
        if (knownVersions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "目录里的版本：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                knownVersions.take(6).forEach { version ->
                    TextButton(
                        onClick = { gameVersion = version },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) { Text(version, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        if (notice.isNotBlank()) ModHint(notice)
        if (searchError.isNotBlank()) ModHint("Modrinth：" + searchError, error = true)

        val current = scan
        if (current != null) {
            Text(
                if (current.exists) {
                    "已装 " + current.totalCount + " 个（启用 " + current.mods.size +
                        " · 停用 " + current.disabled.size + "）"
                } else {
                    "这个目录下还没有 mods/，装第一个模组时会自动建。"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            current.warnings.forEach { ModHint(it) }
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
                    val installed = current?.let { it.mods + it.disabled }.orEmpty()
                    if (installed.isEmpty()) {
                        Box(Modifier.fillMaxSize()) {
                            ModHint(if (rootText.isBlank()) "先填游戏目录。" else "这里还没有模组。")
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
 * 从游戏目录的 versions/ 里取出所有版本对应的 Minecraft 版本号（去重、原版在前）。
 *
 * 用 InstanceScanner 而不是自己读 json：它已经处理了 inheritsFrom、加载器版本 id 解析，
 * 扫不出东西（目录不存在 / 没有 versions/）就返回空列表，不抛错。
 */
private fun instanceGameVersions(gameDir: File): List<String> {
    if (!gameDir.isDirectory) return emptyList()
    val scanned = InstanceScanner.scan(gameDir)
    if (!scanned.isGameDirectory) return emptyList()
    // 原版实例排前面：mods 的兼容性以 MC 版本为准，加载器不影响。
    return scanned.instances
        .sortedBy { it.loader != LoaderKind.VANILLA }
        .map { it.gameVersion }
        .filter { it.isNotBlank() }
        .distinct()
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
