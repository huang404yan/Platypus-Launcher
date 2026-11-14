package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.install.ModpackImportException
import io.github.huang404yan.platypus.core.install.ModpackImportOutcome
import io.github.huang404yan.platypus.core.install.ModpackImporter
import io.github.huang404yan.platypus.core.install.ModpackPreview
import io.github.huang404yan.platypus.core.install.ModpackProgress
import io.github.huang404yan.platypus.core.install.ModpackStep
import io.github.huang404yan.platypus.core.install.ModpackVerifier
import io.github.huang404yan.platypus.core.mods.ModProject
import io.github.huang404yan.platypus.core.mods.ModrinthMeta
import io.github.huang404yan.platypus.core.modpack.ModpackBrowser
import io.github.huang404yan.platypus.core.modpack.ModpackDownloadProgress
import io.github.huang404yan.platypus.core.search.SearchTerm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 整合包从哪来：本地已有的压缩包，还是当场从 Modrinth 下一个。 */
private enum class ModpackSource { LOCAL, REMOTE }

/**
 * 整合包页：一个 .mrpack / MCBBS v2 包，装成能启动的实例。
 *
 * 内容本体还是 [ModpackSection]（本地文件 / 从 Modrinth 搜两条入口共用一条导入流水线），
 * 这里补上它从下载页独立出来之后必须自己有的东西：标题、目标目录（含「选择…」）、
 * 设置里读出来的默认内存。
 *
 * 为什么从下载页拆出来：PCL 把「安装整合包」和「安装原版」并排放在下载页的左侧栏
 * （`PageDownloadLeft.xaml` 的 ItemInstall / ItemPack）。挤在原版那一页底部时，
 * 用户要先滚过版本列表和一键安装的进度条才看得见它 —— 这正是「藏得深」。
 * 拆成平级项之后，左边一眼就是「我想装什么」。
 */
@Composable
internal fun ModpackPage(
    initialGameRoot: String? = null,
    backend: LauncherBackend? = null,
    onImported: (Instance) -> Unit = {},
) {
    var gameDirText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    // 与下载页同一个来源：设置里指定的默认内存，导入后登记实例时用
    var defaultMemoryMb by remember { mutableStateOf(Instance.DEFAULT_MEMORY_MB) }
    // 远程搜索的游戏版本种子留空：整合包页面前面不再有「版本号输入框」，
    // 猜一个版本反而会把能搜到的包过滤掉。搜完发现不对，用户可以在搜索行里改。
    val versionSeed = ""

    LaunchedEffect(Unit) {
        val repository = backend?.settings
        if (repository != null) {
            val stored = withContext(Dispatchers.IO) { runCatching { repository.load() }.getOrNull() }
            if (stored != null) {
                if (initialGameRoot.isNullOrBlank() && stored.defaultGameRoot.isNotBlank()) {
                    gameDirText = stored.defaultGameRoot
                }
                defaultMemoryMb = stored.defaultMemoryMb
            }
        }
    }

    val running = ModpackRunner.state.collectAsState().value.phase is ImportPhase.Running

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("整合包", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "选一个 .mrpack 或 MCBBS v2 压缩包，直接装成能启动的实例：包里的模组、" +
                "配置、光影都会解到实例目录里，缺的游戏文件与加载器会一起补。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = gameDirText,
                onValueChange = { gameDirText = it },
                modifier = Modifier.weight(1f),
                label = { Text("游戏目录（.minecraft）") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = { pickDirectory(gameDirText)?.let { gameDirText = it.absolutePath } },
                enabled = !running,
            ) { Text("选择…") }
        }

        ModpackSection(
            enabled = !running,
            gameDir = gameDirText,
            defaultMemoryMb = defaultMemoryMb,
            backend = backend,
            onImported = onImported,
            initialGameVersion = versionSeed,
        )
    }
}

/**
 * 整合包导入区：选一个 .mrpack / MCBBS v2 包，一次装成能启动的实例。
 *
 * 两个入口共用后面整条导入流水线：
 * - **本地文件**：用户自己下好的 .mrpack / .zip，选完先只读预览。
 * - **从 Modrinth 搜**：搜到整合包 → 挑一个能装的 .mrpack → 下到系统临时目录 → 同样走预览与导入。
 *   远程这条只多出「把 .mrpack 弄到本地」这一步，装的部分一行都没有复制
 *   （见 [ModpackBrowser] 的注释：两条路只有一份进度与错误处理）。
 *
 * 为什么放在下载页而不是新开一个一级导航：导入整合包的用户做的事和「装一个版本」
 * 是同一件事（都会下载游戏本体、都要装加载器），只是入口多了一个 .zip 文件。
 * 单独开一页会让下载页与导入页各自维护一套进度与错误显示。
 */
@Composable
internal fun ModpackSection(
    enabled: Boolean,
    gameDir: String,
    defaultMemoryMb: Int,
    backend: LauncherBackend?,
    onImported: (Instance) -> Unit,
    initialGameVersion: String = "",
) {
    var source by remember { mutableStateOf(ModpackSource.LOCAL) }
    var allowOverwrite by remember { mutableStateOf(false) }

    // 导入状态住在进程级的 ModpackRunner 里：切页 / 切 tab 都不会打断，
    // 回来还能看到「下到第几个文件」，只有点「取消」才停。
    val importState = ModpackRunner.state.collectAsState().value
    val archivePath = importState.archivePath
    val preview = importState.preview
    val previewError = importState.previewError
    val loadingPreview = importState.loadingPreview
    val phase = importState.phase
    val progress = importState.progress
    val message = importState.message

    // 远程搜索那一段的状态。与导入阶段分开：搜索失败不该把「已装好」的结果清掉。
    var query by remember { mutableStateOf("") }
    var remoteVersion by remember { mutableStateOf(initialGameVersion.trim()) }
    var remoteLoader by remember { mutableStateOf(LoaderKind.FABRIC) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ModProject>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val preparingSlug = importState.preparingSlug
    val preparingDetail = importState.preparingDetail
    val downloadProgress = importState.downloadProgress
    val remoteNotice = importState.remoteNotice
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val scope = rememberCoroutineScope()
    val running = phase is ImportPhase.Running
    val busy = running || searching || preparingSlug.isNotBlank()

    fun inspectArchive(file: File) {
        ModpackRunner.inspect(file)
    }

    fun startImport(archive: File) {
        val dirText = gameDir.trim()
        if (dirText.isEmpty()) {
            ModpackRunner.hint("先在上面填一个目标目录，比如 D:/mc-test。")
            return
        }
        if (backend == null) {
            ModpackRunner.hint("后端未装配：导入整合包需要桌面端注入 Java 与实例存储实现。")
            return
        }
        // 跑在进程级作用域里：切页不会打断，回来进度还在。
        ModpackRunner.startImport(
            archive = archive,
            gameDir = File(dirText),
            memoryMb = defaultMemoryMb,
            allowOverwrite = allowOverwrite,
            javaRuntimes = backend.javaRuntimes,
            instances = backend.instances,
        )
    }

    fun runSearch() {
        searchJob?.cancel()
        searchError = ""
        searching = true
        searchJob = scope.launch {
            try {
                val found = ModpackBrowser.search(
                    query = query,
                    gameVersion = remoteVersion.trim().ifBlank { null },
                    loader = remoteLoader,
                    limit = 12,
                )
                results = found
                searched = true
                if (found.isEmpty()) searchError = "没搜到整合包。换个关键词，或者把游戏版本清空再试。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                searchError = describeImportError(e)
            } finally {
                searching = false
            }
        }
    }

    fun installRemote(project: ModProject) {
        if (backend == null) {
            ModpackRunner.hint("后端未装配：装整合包需要桌面端注入 Java 与实例存储实现。")
            return
        }
        ModpackRunner.downloadRemote(
            project = project,
            gameVersion = remoteVersion.trim().ifBlank { null },
            loader = remoteLoader,
        )
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "导入整合包",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "支持 Modrinth（.mrpack）与 MCBBS v2",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { source = ModpackSource.LOCAL },
                    enabled = !busy,
                ) {
                    Text(
                        "本地文件",
                        fontWeight = if (source == ModpackSource.LOCAL) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                TextButton(
                    onClick = { source = ModpackSource.REMOTE },
                    enabled = !busy,
                ) {
                    Text(
                        "从 Modrinth 搜",
                        fontWeight = if (source == ModpackSource.REMOTE) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }

            if (source == ModpackSource.LOCAL) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val picked = pickArchive()
                            if (picked != null) inspectArchive(picked)
                        },
                        enabled = enabled && !busy && !loadingPreview,
                    ) { Text("选择文件…") }
                }
                if (archivePath.isBlank()) {
                    Text(
                        "选一个 .mrpack 或 .zip：会先只读解析给你看一眼，再决定装不装。" +
                            "包里的模组会下到实例的 mods 目录，overrides 按层覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                RemoteSearchRow(
                    query = query,
                    onQueryChange = { query = it },
                    gameVersion = remoteVersion,
                    onGameVersionChange = { remoteVersion = it },
                    loader = remoteLoader,
                    onCycleLoader = { remoteLoader = nextRemoteLoader(remoteLoader) },
                    searching = searching,
                    onSearch = { runSearch() },
                    enabled = enabled && !running && preparingSlug.isBlank(),
                )
                Text(
                    "搜到的是整个整合包（含游戏版本、加载器、模组清单）。" +
                        "挑一个装：会自动下最新的 .mrpack 到系统临时目录，再走下面同一条导入流水线。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 整合包名以英文为主，但「农夫乐事」这类中文叫法也很常被拿来搜：
                // 与其让用户对着空列表猜，不如直接说明 Modrinth 只认英文并给候选。
                ChineseSearchHint(
                    query = query,
                    kind = SearchTerm.Kind.MODPACK,
                    onPick = { query = it },
                )
                if (searchError.isNotBlank()) {
                    Text(searchError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (remoteNotice.isNotBlank()) {
                    Text(
                        remoteNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remoteNotice.startsWith("已下载")) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (preparingSlug.isNotBlank()) {
                    Text(preparingDetail, style = MaterialTheme.typography.bodySmall)
                    val dp = downloadProgress
                    if (dp != null) ImportProgressBar(dp.fraction)
                }
                if (searched && results.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        results.forEach { project ->
                            RemotePackRow(
                                project = project,
                                busySlug = preparingSlug,
                                enabled = enabled && !running && preparingSlug.isBlank(),
                                onInstall = { installRemote(project) },
                            )
                        }
                    }
                }
            }

            if (archivePath.isNotBlank()) {
                Text(
                    archivePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (loadingPreview) {
                Text("正在读压缩包…", style = MaterialTheme.typography.bodySmall)
            }

            previewError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            preview?.let { p ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.manifest.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "格式 " + p.layout.kind.label + " · 游戏 " + p.manifest.summary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (p.fileCount == 0) {
                            "包内没有额外文件，只有 overrides"
                        } else {
                            p.fileCount.toString() + " 个文件要下载，共 " +
                                (if (p.totalDownloadBytes < 0L) "体积未知" else formatSize(p.totalDownloadBytes))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p.manifest.overrideDirs.isNotEmpty()) {
                        Text(
                            "覆盖层：" + p.manifest.overrideDirs.joinToString(" → "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    p.manifest.warnings.forEach {
                        Text(
                            "· " + it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = allowOverwrite,
                            onCheckedChange = { allowOverwrite = it },
                            enabled = !running,
                        )
                        Text(
                            "允许覆盖已有存档与设置（默认跳过）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { preview?.let { startImport(it.archive) } },
                    enabled = enabled && !running && preview != null && preparingSlug.isBlank(),
                ) { Text("开始导入") }
                if (running) {
                    OutlinedButton(onClick = { ModpackRunner.cancelImport() }) { Text("取消") }
                }
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (phase is ImportPhase.Failed || phase is ImportPhase.Canceled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            val canceled = phase as? ImportPhase.Canceled
            if (canceled != null) {
                Text(
                    "导入已中断（停在「" + canceled.step.label + "」）。实例目录里已经下好的文件不会白费，" +
                        "再点一次「开始导入」会自动续上没下完的那几个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val snapshot = progress
            if (snapshot != null) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        snapshot.step.label + (if (snapshot.detail.isBlank()) "" else " · " + snapshot.detail),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (snapshot.total > 0L && snapshot.totalReliable) {
                        ImportProgressBar(snapshot.fraction)
                    }
                }
            }

            val finished = phase as? ImportPhase.Finished
            if (finished != null) {
                // 「导入完成」只在核对通过时才敢说。清单里有一个文件没落好，就说明这个实例
                // 是半成品 —— 用户拿它进游戏会看到缺模组的报错，比在这里承认没装齐糟得多。
                val incomplete = !finished.outcome.complete
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        (if (incomplete) "导入没装齐：" else "导入完成：") + finished.outcome.instance.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (incomplete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "包内文件 " + finished.outcome.downloadedFiles + " 个（" +
                            formatSize(finished.outcome.downloadedBytes) + "），overrides " +
                            finished.outcome.overrideFiles + " 个，用时 " +
                            formatDurationMs(finished.outcome.elapsedMs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (incomplete) {
                        Text(
                            ModpackVerifier.summarize(finished.outcome.fileIssues),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "再点一次「开始导入」会只补没下完的那些文件；已经下好的不会重下。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    finished.outcome.warnings.forEach {
                        Text(
                            "· " + it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onImported(finished.outcome.instance) }) { Text("去启动") }
                }
            }
        }
    }
}

/** 搜索行：关键词 + 游戏版本 + 加载器 + 搜索按钮。 */
@Composable
private fun RemoteSearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    gameVersion: String,
    onGameVersionChange: (String) -> Unit,
    loader: LoaderKind,
    onCycleLoader: () -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    enabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(2f),
            singleLine = true,
            label = { Text("搜整合包（如 cobblemon、fabulously optimized；留空看热门）") },
            enabled = enabled,
        )
        androidx.compose.material3.OutlinedTextField(
            value = gameVersion,
            onValueChange = onGameVersionChange,
            modifier = Modifier.weight(0.7f),
            singleLine = true,
            label = { Text("游戏版本") },
            enabled = enabled,
        )
        OutlinedButton(onClick = onCycleLoader, enabled = enabled) {
            Text(if (loader == LoaderKind.VANILLA) "不限加载器" else loaderNameOf(loader))
        }
        Button(onClick = onSearch, enabled = enabled && !searching) {
            Text(if (searching) "搜索中…" else "搜索")
        }
    }
}

/** 一条搜索结果。正在准备的包显示进度文案，其余按钮禁用避免并发下载。 */
@Composable
private fun RemotePackRow(
    project: ModProject,
    busySlug: String,
    enabled: Boolean,
    onInstall: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(project.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                val author = project.author?.takeIf { it.isNotBlank() }
                Text(
                    (if (author == null) "" else author + " · ") + project.downloadsLabel + " 次下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (project.description.isNotBlank()) {
                    Text(
                        project.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (busySlug == project.slug) {
                Text("准备中…", style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = onInstall, enabled = enabled) { Text("装这个包") }
            }
        }
    }
}

/** 选文件对话框。Compose 桌面端没有内置文件选择器，直接用 AWT 的原生 FileDialog（Windows 上是系统的那个）。 */
private fun pickArchive(): File? {
    // owner 给 Compose 主窗口，对话框才会出现在窗口上方而不是屏幕角落里；
    // 找不到时退回 null，对话框仍会正常弹出，只是不带父窗口。
    val owner = java.awt.Window.getWindows()
        .filterIsInstance<androidx.compose.ui.awt.ComposeWindow>()
        .firstOrNull { it.isActive }
        ?: java.awt.Window.getWindows().filterIsInstance<androidx.compose.ui.awt.ComposeWindow>().firstOrNull()
    val dialog = java.awt.FileDialog(owner, "选择整合包（.mrpack / .zip）", java.awt.FileDialog.LOAD)
    // Windows 的原生对话框会忽略 FilenameFilter，这里只是给支持的平台加一层过滤。
    dialog.setFilenameFilter { _, name ->
        ModpackImporter.SUPPORTED_EXTENSIONS.any { name.endsWith("." + it, ignoreCase = true) }
    }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    return File(dir, name)
}

/** 整合包只会是原版之上的一层，所以加载器里没有 VANILLA：搜索留空反而更宽。 */
private fun nextRemoteLoader(current: LoaderKind): LoaderKind = when (current) {
    LoaderKind.FABRIC -> LoaderKind.FORGE
    LoaderKind.FORGE -> LoaderKind.NEOFORGE
    LoaderKind.NEOFORGE -> LoaderKind.QUILT
    else -> LoaderKind.FABRIC
}

private fun loaderNameOf(kind: LoaderKind): String = when (kind) {
    LoaderKind.FABRIC -> "Fabric"
    LoaderKind.FORGE -> "Forge"
    LoaderKind.NEOFORGE -> "NeoForge"
    LoaderKind.QUILT -> "Quilt"
    LoaderKind.VANILLA -> "原版"
    else -> "其他"
}

private fun stepOf(error: Throwable): ModpackStep =
    (error as? ModpackImportException)?.step ?: ModpackStep.PARSING

private fun describeImportError(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: (error::class.simpleName ?: "未知错误")

@Composable
private fun ImportProgressBar(fraction: Float) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small),
    ) {
        Column(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
        ) {}
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 0L) return "未知"
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.size - 1) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}

private fun formatDurationMs(millis: Long): String {
    val seconds = millis / 1000L
    return when {
        seconds < 60L -> seconds.toString() + " 秒"
        seconds < 3600L -> (seconds / 60L).toString() + " 分 " + (seconds % 60L) + " 秒"
        else -> (seconds / 3600L).toString() + " 小时 " + ((seconds % 3600L) / 60L) + " 分"
    }
}
