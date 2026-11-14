package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.install.JsonServerRepository
import io.github.huang404yan.platypus.core.install.ServerEntry
import io.github.huang404yan.platypus.core.install.ServerInstaller
import io.github.huang404yan.platypus.core.install.ServerKind
import io.github.huang404yan.platypus.core.install.ServerLoader
import io.github.huang404yan.platypus.core.install.serverEntryOf
import io.github.huang404yan.platypus.core.version.ManifestVersion
import io.github.huang404yan.platypus.core.version.VersionManifest
import io.github.huang404yan.platypus.core.version.parseVersionManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 服务端页（手机）。
 *
 * 和桌面 [io.github.huang404yan.platypus.ui.ServerPage] 共用 core 的 [ServerInstaller]，
 * 差别只有两处：
 *
 * 1. **只有原版 / Paper / Fabric**。Forge 与 NeoForge 要起一个 java 子进程跑 installer，
 *    手机上没有可用的 java，也没有在这个进程里起子进程的余地 —— 与其放两个点了必失败的
 *    按钮，不如不出现，在说明里写清楚「这两类去电脑上装」。
 * 2. **没有「打开目录」**。安卓上 file:// 的 Intent 从 7.0 起直接抛 FileUriExposedException，
 *    要给 FileProvider 才能分享，为一条路径不值当；改成「复制路径」，用户自己去文件管理器粘。
 *
 * 装好的服务端会登记进 `<游戏目录>/platypus/servers.json`，和桌面同一套 [JsonServerRepository]。
 */
/** 安卓上能装的三类。Forge / NeoForge 要 java 子进程，手机上不做。 */
private val ANDROID_SERVER_KINDS = listOf(ServerKind.VANILLA, ServerKind.PAPER, ServerKind.FABRIC)

private const val SERVER_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
private const val SERVER_USER_AGENT = "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"

@Composable
internal fun ServerPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(hasFileAccess(context)) }
    var root by remember { mutableStateOf(defaultGameRoot()) }
    var dirText by remember { mutableStateOf(defaultServerDir(root)) }
    var versionText by remember { mutableStateOf("") }
    var memoryText by remember { mutableStateOf(ServerInstaller.DEFAULT_MEMORY_MB.toString()) }
    var filterText by remember { mutableStateOf("") }

    var manifest by remember { mutableStateOf<VersionManifest?>(null) }
    var manifestError by remember { mutableStateOf("") }
    var loadingManifest by remember { mutableStateOf(false) }

    var kind by remember { mutableStateOf(ServerKind.VANILLA) }
    var loaderVersion by remember { mutableStateOf("") }

    var entries by remember { mutableStateOf<List<ServerEntry>>(emptyList()) }
    var registryNotice by remember { mutableStateOf("") }
    var registryError by remember { mutableStateOf("") }
    /** 已经登记过的目录，装完自动登记时用它去重（页面切走再回来还会看到同一次结果）。 */
    var registeredDir by remember { mutableStateOf("") }
    /** 本次启动用的内存，登记写它而不是读输入框。 */
    var lastMemoryMb by remember { mutableStateOf(ServerInstaller.DEFAULT_MEMORY_MB) }

    // 进度由进程级 runner 持有：切页、切 tab 都不打断，只有杀进程才停。
    val state = ServerInstallRunner.state.collectAsState().value
    val running = state.busy

    fun repoOf(): JsonServerRepository = JsonServerRepository(
        JsonServerRepository.defaultIndexFile(File(root.trim().ifBlank { defaultGameRoot() })),
    )

    suspend fun reloadEntries() {
        registryError = ""
        entries = runCatching { withContext(Dispatchers.IO) { repoOf().list() } }
            .getOrElse {
                registryError = "读服务端登记失败：" + (it.message ?: it.toString())
                emptyList()
            }
    }

    suspend fun refreshManifest() {
        loadingManifest = true
        manifestError = ""
        try {
            val text = withContext(Dispatchers.IO) { fetchServerText(SERVER_MANIFEST_URL) }
            manifest = parseVersionManifest(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            manifestError = e.message ?: e.toString()
        } finally {
            loadingManifest = false
        }
    }

    LaunchedEffect(Unit) {
        granted = hasFileAccess(context)
        reloadEntries()
        if (manifest == null) refreshManifest()
    }

    // 装完自动登记一次。用目录去重：页面切走再回来 state 还是那一次结果，
    // 重复登记只会把同一条覆盖成一样的内容。
    LaunchedEffect(state.doneAt) {
        val result = state.result ?: return@LaunchedEffect
        val dir = result.dir.absolutePath
        if (dir == registeredDir) return@LaunchedEffect
        val saved = runCatching {
            withContext(Dispatchers.IO) { repoOf().save(serverEntryOf(result, memoryMb = lastMemoryMb)) }
        }
        saved.onSuccess {
            registeredDir = dir
            reloadEntries()
        }.onFailure {
            registryError = "装好了，但写不进服务端登记：" + (it.message ?: it.toString())
        }
    }

    fun startDownload() {
        val dir = dirText.trim()
        if (dir.isBlank()) {
            ServerInstallRunner.hint("先填一个保存目录，比如 /storage/emulated/0/mc-server。")
            return
        }
        val id = versionText.trim()
        if (id.isBlank()) {
            ServerInstallRunner.hint("先选一个版本号，比如 1.20.1。")
            return
        }
        val memory = memoryText.trim().toIntOrNull()?.takeIf { it in 512..32768 }
        if (memory == null) {
            ServerInstallRunner.hint("内存填 512–32768 之间的整数（单位 MB）。")
            return
        }
        granted = hasFileAccess(context)
        if (!granted) {
            openAllFilesSettings(context)
            return
        }
        lastMemoryMb = memory
        ServerInstallRunner.start(
            targetDir = File(dir),
            versionId = id,
            loader = ServerLoader(kind = kind, loaderVersion = loaderVersion.trim().ifBlank { null }),
            memoryMb = memory,
        )
    }

    val releases = manifest?.releases().orEmpty()
    val visible = if (filterText.isBlank()) {
        releases
    } else {
        releases.filter { it.id.contains(filterText.trim(), ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("服务端", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "下一个能直接开的服务端，顺手写好 eula.txt 和启动脚本。" +
                        "手机上支持原版、Paper、Fabric 三种；Forge 与 NeoForge 要跑安装器，" +
                        "那要有一个 java 子进程，去电脑上装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { ServerStatusCard(running, state.failed, state.message, state.progress) }

        if (!granted) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("还没有文件访问权限", fontWeight = FontWeight.SemiBold)
                        Text(
                            "下载要往 /storage/emulated/0 写文件，必须先开「所有文件访问权限」。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { openAllFilesSettings(context) }) { Text("去授权") }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = dirText,
                    onValueChange = { dirText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("保存目录") },
                    singleLine = true,
                    enabled = !running,
                )
                Button(
                    onClick = { granted = hasFileAccess(context); if (!granted) openAllFilesSettings(context) },
                ) { Text(if (granted) "已授权" else "去授权") }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "加载器",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ANDROID_SERVER_KINDS.forEach { option ->
                        val active = option == kind
                        OutlinedButton(
                            onClick = { if (!active) { kind = option; loaderVersion = "" } },
                            modifier = Modifier.weight(1f),
                            enabled = !running,
                        ) {
                            Text(
                                option.label,
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
        }

        if (kind != ServerKind.VANILLA) {
            item {
                OutlinedTextField(
                    value = loaderVersion,
                    onValueChange = { loaderVersion = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(kind.label + " 版本") },
                    singleLine = true,
                    enabled = !running,
                    supportingText = { Text(androidLoaderHint(kind)) },
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = versionText,
                    onValueChange = { versionText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("版本号") },
                    singleLine = true,
                    enabled = !running,
                    supportingText = { Text("例如 1.20.1") },
                )
                OutlinedTextField(
                    value = memoryText,
                    onValueChange = { memoryText = it.filter { ch -> ch.isDigit() }.take(5) },
                    modifier = Modifier.width(110.dp),
                    label = { Text("内存 MB") },
                    singleLine = true,
                    enabled = !running,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { startDownload() }, enabled = !running, modifier = Modifier.weight(1f)) {
                    Text(if (running) "下载中…" else "开始下载")
                }
                if (running) {
                    OutlinedButton(onClick = { ServerInstallRunner.cancel() }) { Text("取消") }
                }
            }
        }

        item {
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("在版本列表里过滤（只看正式版）") },
                singleLine = true,
            )
        }

        if (loadingManifest) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("读取版本清单…", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (manifestError.isNotBlank()) {
            item {
                Text(
                    "读取版本清单失败：" + manifestError + "（不影响手填版本号）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (releases.isNotEmpty() && visible.isEmpty()) {
            item {
                Text(
                    "没有名字里带「" + filterText.trim() + "」的正式版。清空过滤框能看全部 " +
                        releases.size + " 个；也可以直接在版本号里手填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (visible.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(4.dp)) {
                        items(visible) { entry ->
                            ServerVersionRow(
                                entry = entry,
                                selected = entry.id == versionText.trim(),
                                enabled = !running,
                                onClick = { versionText = entry.id },
                            )
                        }
                    }
                }
            }
        }

        val done = state.result
        if (done != null && !state.failed) {
            item {
                ServerReadyCard(
                    summary = done.summary,
                    dir = done.dir.absolutePath,
                    script = done.startScript.name,
                    onCopy = { registryNotice = copyToClipboard(context, it) },
                )
            }
        }

        if (registryError.isNotBlank()) {
            item {
                Text(
                    registryError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (registryNotice.isNotBlank()) {
            item {
                Text(
                    registryNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (entries.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "装过的服务端（" + entries.size + "）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        entries.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        entry.directory,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedButton(
                                    onClick = { registryNotice = copyToClipboard(context, entry.directory) },
                                ) { Text("复制路径") }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val error = runCatching {
                                                withContext(Dispatchers.IO) { repoOf().remove(entry.id) }
                                            }.exceptionOrNull()
                                            if (error != null) {
                                                registryNotice = "删登记失败：" + (error.message ?: error.toString())
                                            } else {
                                                registryNotice = "已从列表里去掉 " + entry.title + "（目录和文件都还在）。"
                                                reloadEntries()
                                            }
                                        }
                                    },
                                ) { Text("去掉") }
                            }
                        }
                        Text(
                            "「去掉」只是不在这里显示，目录和里面的文件一个都不删。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerStatusCard(
    running: Boolean,
    failed: Boolean,
    message: String,
    progress: io.github.huang404yan.platypus.core.install.ServerInstallProgress?,
) {
    if (message.isBlank()) return
    val container = if (failed) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (failed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                if (running) "正在下载服务端" else if (failed) "没有装成" else "服务端",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
            Text(message, style = MaterialTheme.typography.bodySmall, color = content)
            if (running && progress != null && progress.total > 0L) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ServerVersionRow(
    entry: ManifestVersion,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.id,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            entry.releaseTime.take(10),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 装好之后给的东西：路径 + 一条必须说的事。没有「打开目录」，换成复制路径（理由见文件头）。 */
@Composable
private fun ServerReadyCard(summary: String, dir: String, script: String, onCopy: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "启动脚本：" + script,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "目录：" + dir,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "eula.txt 已经写成 eula=true，等于替你同意了 Minecraft 最终用户许可协议。" +
                    "不同意的话把它改成 false，服务端会拒绝启动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "手机上没有应用内开服：把整个目录拷到电脑、或者在有 java 的环境里跑 start.sh。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { onCopy(dir) }) { Text("复制目录路径") }
        }
    }
}

/** 加载器版本输入框下面那句：留空会发生什么，两种加载器不一样。 */
private fun androidLoaderHint(kind: ServerKind): String = when (kind) {
    ServerKind.PAPER -> "填构建号，例如 196；留空取最新构建。"
    ServerKind.FABRIC -> "填 loader 版本，例如 0.15.11；留空取最新稳定版。"
    else -> ""
}

/** 服务端的默认落点：游戏目录旁边的 mc-server/，不和 .minecraft 混在一起。 */
private fun defaultServerDir(gameRoot: String): String {
    val base = File(gameRoot.trim().ifBlank { defaultGameRoot() }).parentFile ?: return "mc-server"
    return File(base, "mc-server").absolutePath
}

/** 拉一份文本：安卓上没有 core 的 okhttp 封装（那是 JVM 侧的），这里用 HttpURLConnection。 */
private fun fetchServerText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", SERVER_USER_AGENT)
        val code = connection.responseCode
        if (code !in 200..299) error("HTTP " + code + "（" + url + "）")
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
