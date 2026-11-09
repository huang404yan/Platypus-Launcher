package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadProgress
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.version.DownloadPlanner
import io.github.huang404yan.platypus.core.version.ManifestVersion
import io.github.huang404yan.platypus.core.version.VersionManifest
import io.github.huang404yan.platypus.core.version.parseAssetIndex
import io.github.huang404yan.platypus.core.version.parseVersionJson
import io.github.huang404yan.platypus.core.version.parseVersionManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载页：拉 Mojang 版本清单 → 选版本 → 规划文件 → 真实下载到游戏目录。
 *
 * 这一步只负责「把文件铺到位」：客户端 jar、libraries、assets 索引与资源对象。
 * 启动（JVM、渲染、参数拼装）属于 runtime-android，不在这里。
 *
 * 平台规则用的是 core 的 [io.github.huang404yan.platypus.core.version.OsFamily.current]。
 * Android 上会得到 LINUX，于是 natives 按 Linux 取——手机真正运行时要不要换一批库，
 * 由 S1 spike 的结论决定，本页只保证目录内容与官方布局一致。
 */

private const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
private const val USER_AGENT = "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"

private sealed interface CatalogState {
    data object Loading : CatalogState
    data class Ready(val manifest: VersionManifest) : CatalogState
    data class Failed(val message: String) : CatalogState
}

internal sealed interface Phase {
    data object Idle : Phase
    data class Preparing(val stage: String) : Phase
    data class Downloading(val progress: DownloadProgress) : Phase
    data object Cancelled : Phase
    data class Done(val files: Int, val bytes: Long) : Phase
    data class Failed(val message: String, val details: List<String>) : Phase
}

@Composable
internal fun DownloadPage() {
    val context = LocalContext.current

    var granted by remember { mutableStateOf(hasFileAccess(context)) }
    var root by remember { mutableStateOf(defaultGameRoot()) }
    var filter by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<CatalogState>(CatalogState.Loading) }
    var selected by remember { mutableStateOf<String?>(null) }

    // 进度由进程级 DownloadJobRunner 持有：切走再回来进度还在，退出应用才会中断。
    val phase by DownloadJobRunner.phase.collectAsState()

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { CatalogState.Ready(parseVersionManifest(fetchText(MANIFEST_URL))) }
                .getOrElse { CatalogState.Failed(describe(it)) }
        }
        catalog = loaded
    }

    val releases = (catalog as? CatalogState.Ready)?.manifest?.releases().orEmpty()
    val chosen = releases.firstOrNull { it.id == selected }
    val busy = phase is Phase.Preparing || phase is Phase.Downloading

    fun startDownload() {
        val target = chosen ?: return
        if (root.isBlank()) return
        granted = hasFileAccess(context)
        if (!granted) return
        DownloadJobRunner.start(target, File(root.trim()))
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.width(260.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("下载", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索版本") },
                singleLine = true,
            )
            when (val state = catalog) {
                CatalogState.Loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("读取版本清单…", style = MaterialTheme.typography.bodySmall)
                }
                is CatalogState.Failed -> {
                    Text("读取版本清单失败", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text(
                        "检查网络；Mojang 主站在国内可能连不上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is CatalogState.Ready -> {
                    val visible = releases.filter {
                        filter.isBlank() || it.id.contains(filter.trim(), ignoreCase = true)
                    }
                    Text(
                        "共 ${releases.size} 个正式版，显示 ${visible.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(visible) { version ->
                            VersionRow(
                                version = version,
                                selected = version.id == selected,
                                onClick = { if (!busy) selected = version.id },
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("目标目录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                        if (!granted) openAllFilesSettings(context)
                    },
                ) {
                    Text(if (granted) "已授权" else "去授权")
                }
            }

            if (!granted) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("还没有文件访问权限", fontWeight = FontWeight.SemiBold)
                        Text(
                            "下载要往 /storage/emulated/0 写文件，必须先开「所有文件访问权限」。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            val current = chosen
            if (current == null) {
                Text(
                    "从左边选一个版本。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(current.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "类型 ${current.type} · 发布于 ${current.releaseTime.take(10)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "要下载：客户端 jar、libraries 里当前平台的库、assets 索引与全部资源对象。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Button(onClick = { startDownload() }, enabled = !busy && granted) {
                    Text(if (busy) "进行中…" else "下载到上面这个目录")
                }
                if (busy) {
                    OutlinedButton(onClick = { DownloadJobRunner.cancel() }) { Text("取消") }
                }
                if (phase is Phase.Cancelled) {
                    OutlinedButton(onClick = { DownloadJobRunner.reset() }) { Text("清除提示") }
                }
            }

            PhaseView(phase)
        }
    }
}

@Composable
private fun VersionRow(version: ManifestVersion, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = version.id,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (version.type != "release") {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    version.type,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun PhaseView(phase: Phase) {
    when (phase) {
        Phase.Idle -> Unit
        is Phase.Preparing -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(phase.stage, style = MaterialTheme.typography.bodySmall)
        }
        is Phase.Downloading -> {
            val progress = phase.progress
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "文件 ${progress.completedFiles}/${progress.totalFiles} · " +
                    "${formatBytes(progress.completedBytes)} / ${formatBytes(progress.totalBytes)}" +
                    " · ${(progress.fraction * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "下载中可以切到别的页，回来进度还在跑；退出应用会中断，已完成的文件下次会跳过。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Phase.Cancelled -> {
            Text(
                "已取消。已下载完的文件会保留，下次继续时按 sha1 校验跳过。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is Phase.Done -> {
            Text(
                "完成：${phase.files} 个文件，${formatBytes(phase.bytes)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is Phase.Failed -> {
            val context = LocalContext.current
            var copied by remember { mutableStateOf(false) }
            Text(
                phase.message,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            phase.details.forEach { detail ->
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            // 拿不到 logcat 时，这段文字就是唯一的现场证据，必须能整段带走。
            OutlinedButton(
                onClick = {
                    val dump = buildString {
                        appendLine("鸭嘴兽启动器 · 下载失败")
                        appendLine("阶段：下载")
                        appendLine("结果：" + phase.message)
                        phase.details.forEach { appendLine("详情：" + it) }
                    }
                    copyToClipboard(context, dump)
                    copied = true
                },
            ) {
                Text(if (copied) "已复制" else "复制失败信息")
            }
        }
    }
}

/** 阻塞式 GET，调用方负责放到 IO 线程。 */
private fun fetchText(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
    }
    return try {
        val code = connection.responseCode
        if (code !in 200..299) error("HTTP $code（$url）")
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.size - 1) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} B" else String.format("%.1f %s", value, units[index])
}

/**
 * 进程级下载任务：页面切走、重组都不会打断它，只有杀进程才停。
 *
 * 单独放在页面外面，是因为 [DownloadPage] 里用 rememberCoroutineScope 起的协程
 * 会随页面离开而取消——下载到一半切到「实例」页就会被砍断。
 */
internal object DownloadJobRunner {

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** 同一时间只跑一个下载任务；正在跑时再点按钮直接忽略。 */
    fun start(target: ManifestVersion, gameDir: File) {
        val current = _phase.value
        if (current is Phase.Preparing || current is Phase.Downloading) return
        job = scope.launch { run(target, gameDir) }
    }

    /**
     * 取消当前任务。已写好的文件留在盘上（次次下载会按 sha1 跳过），
     * 只丢掉这次没下完的 .part——HttpURLConnection 读流被 cancel 后会走失败分支。
     */
    fun cancel() {
        val current = _phase.value
        if (current !is Phase.Preparing && current !is Phase.Downloading) return
        job?.cancel()
        job = null
        _phase.value = Phase.Cancelled
    }

    fun reset() {
        val current = _phase.value
        if (current is Phase.Preparing || current is Phase.Downloading) return
        _phase.value = Phase.Idle
    }

    private suspend fun run(target: ManifestVersion, gameDir: File) {
        _phase.value = Phase.Preparing("获取版本信息…")
        try {
            val versionText = withContext(Dispatchers.IO) { fetchText(target.url) }
            val version = withContext(Dispatchers.IO) { parseVersionJson(versionText) }
            withContext(Dispatchers.IO) { gameDir.mkdirs() }

            // 版本 json 本身也要落到 versions/<id>/<id>.json，
            // 否则 InstanceScanner 扫不到刚下完的这个版本。
            if (version.id.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    val dir = DownloadPlanner.versionDir(gameDir, version.id)
                    dir.mkdirs()
                    File(dir, "${version.id}.json").writeText(versionText)
                }
            }
            val downloader = HttpDownloader()

            val base = buildList {
                DownloadPlanner.clientJar(version, gameDir)?.let(::add)
                addAll(DownloadPlanner.libraries(version, gameDir))
            }

            val objects = DownloadPlanner.assetIndex(version, gameDir)?.let { index ->
                _phase.value = Phase.Preparing("下载资源索引…")
                val report = downloader.download(listOf(index))
                val failure = report.failures.firstOrNull()
                if (failure != null) {
                    _phase.value = Phase.Failed("资源索引下载失败（" + File(index.target).name + "）", listOf(failure.reason))
                    return
                }
                withContext(Dispatchers.IO) {
                    runCatching { parseAssetIndex(File(index.target).readText()) }.getOrNull()
                }
            }

            val items: List<DownloadItem> = if (objects != null) {
                base + DownloadPlanner.assets(objects, gameDir)
            } else {
                base
            }
            if (items.isEmpty()) {
                _phase.value = Phase.Failed("这个版本没有可下载的文件", emptyList())
                return
            }

            val totalBytes = items.sumOf { if (it.size > 0) it.size else 0L }
            _phase.value = Phase.Downloading(
                DownloadProgress(completedFiles = 0, totalFiles = items.size, completedBytes = 0, totalBytes = totalBytes),
            )
            val report = downloader.download(items) { progress ->
                _phase.value = Phase.Downloading(progress)
            }
            _phase.value = if (report.ok) {
                Phase.Done(items.size, totalBytes)
            } else {
                Phase.Failed(
                    "有 ${report.failures.size} 个文件下载失败",
                    report.failures.take(6).map { "${File(it.item.target).name}：${it.reason}" },
                )
            }
        } catch (e: CancellationException) {
            // 用户主动取消：不当作失败，取消按钮已经把状态置成 Cancelled。
            throw e
        } catch (e: Exception) {
            // 只显示 message 会丢掉类名，用户回报时无从判断是网络、权限还是磁盘。
            _phase.value = Phase.Failed(describe(e), emptyList())
        } finally {
            job = null
        }
    }
}
