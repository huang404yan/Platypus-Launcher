package io.github.huang404yan.platypus.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.install.ServerInstallProgress
import io.github.huang404yan.platypus.core.install.ServerInstaller
import io.github.huang404yan.platypus.core.install.ServerInstaller.ServerInstallResult
import io.github.huang404yan.platypus.core.install.VanillaInstaller
import io.github.huang404yan.platypus.core.version.ManifestVersion
import io.github.huang404yan.platypus.core.version.VersionManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * 服务端下载的三个阶段。
 *
 * [Finished] 与 [Failed] 分开而不是靠一句 message：下载成功之后要给出可点的东西
 * （打开目录、看开服脚本），失败时则要留住「卡在哪一步」。
 */
internal sealed interface ServerPhase {
    data object Idle : ServerPhase
    data object Running : ServerPhase
    data class Finished(val result: ServerInstallResult) : ServerPhase
    data class Failed(val message: String) : ServerPhase
}

/** 服务端页在界面上的全部可读状态。 */
internal data class ServerState(
    val phase: ServerPhase = ServerPhase.Idle,
    val progress: ServerInstallProgress? = null,
    val message: String = "",
)

/**
 * 进程级的服务端下载任务。
 *
 * 和 [OneClickRunner] 同一个理由：页面里的 rememberCoroutineScope 会随页面离开组合树
 * 被取消。服务端 jar 有几十 MB，装到一半切去看实例就得从头再来一次。
 */
internal object ServerRunner {

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** 同一时间只跑一个；正在跑时再点按钮直接忽略。 */
    fun start(targetDir: File, versionId: String, memoryMb: Int, manifest: VersionManifest?) {
        if (_state.value.phase is ServerPhase.Running) return
        job?.cancel()
        _state.value = ServerState(
            phase = ServerPhase.Running,
            message = "正在查询 " + versionId + " 的服务端…",
        )
        job = scope.launch {
            try {
                val result = ServerInstaller.install(
                    targetDir = targetDir,
                    versionId = versionId,
                    manifest = manifest,
                    memoryMb = memoryMb,
                ) { step ->
                    _state.update { current ->
                        current.copy(
                            progress = step,
                            message = if (step.detail.isNotBlank()) step.detail else current.message,
                        )
                    }
                }
                _state.update {
                    it.copy(phase = ServerPhase.Finished(result), progress = null, message = result.summary)
                }
            } catch (e: CancellationException) {
                // 取消也要说出来：留在 Running 会一直转进度条，看着像卡死
                _state.update {
                    it.copy(
                        phase = ServerPhase.Idle,
                        progress = null,
                        message = "已取消。已经下到一半的 server.jar 还在，再点一次会接着下。",
                    )
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = ServerPhase.Failed(e.message ?: e.toString()),
                        progress = null,
                    )
                }
            }
        }
    }

    fun cancel() {
        if (_state.value.phase !is ServerPhase.Running) return
        job?.cancel()
        job = null
    }

    /** 输入校验没过时只改一句话，不动阶段。 */
    fun hint(text: String) {
        _state.update { it.copy(message = text) }
    }
}

/**
 * 「下载 / 服务端」页：下一个原版服务端，并把它变成双击能开的样子。
 *
 * 只有原版。Forge/NeoForge/Fabric/Paper 的服务端各有各的坑（要跑安装器再换主类、
 * 要另一套下载源），一锅端进来只会让这一版既不可靠也说不清。这一点在页面上写清楚。
 *
 * 为什么不复用 [DownloadsPage]：那边一整套是「装进游戏目录并登记成实例」，
 * 服务端不登记实例、不装 Java、也不共享 versions/ 布局，共用只会两边都变成 if。
 */
@Composable
fun ServerPage(initialGameRoot: String? = null) {
    val state = ServerRunner.state.collectAsState().value
    val running = state.phase is ServerPhase.Running

    // 默认落在游戏目录旁边的 server/，不和 .minecraft 混在一起
    var dirText by remember { mutableStateOf(defaultServerDir(initialGameRoot)) }
    var versionText by remember { mutableStateOf("") }
    var memoryText by remember { mutableStateOf(ServerInstaller.DEFAULT_MEMORY_MB.toString()) }
    var filterText by remember { mutableStateOf("") }
    var manifest by remember { mutableStateOf<VersionManifest?>(null) }
    var manifestError by remember { mutableStateOf<String?>(null) }
    var loadingManifest by remember { mutableStateOf(false) }

    suspend fun refreshManifest() {
        loadingManifest = true
        manifestError = null
        withContext(Dispatchers.IO) {
            try {
                manifest = VanillaInstaller.loadManifest()
            } catch (e: Exception) {
                manifestError = e.message ?: (e::class.simpleName ?: "拉取版本列表失败")
            }
        }
        loadingManifest = false
    }

    fun startDownload() {
        val dir = dirText.trim()
        if (dir.isEmpty()) {
            ServerRunner.hint("先填一个保存目录，比如 D:/mc-server。")
            return
        }
        val id = versionText.trim()
        if (id.isEmpty()) {
            ServerRunner.hint("先选一个版本号，比如 1.20.1。")
            return
        }
        val memory = memoryText.trim().toIntOrNull()?.takeIf { it in 512..32768 }
        if (memory == null) {
            ServerRunner.hint("内存填 512–32768 之间的整数（单位 MB）。")
            return
        }
        ServerRunner.start(File(dir), id, memory, manifest)
    }

    LaunchedEffect(Unit) {
        if (manifest == null) refreshManifest()
    }

    val releases = manifest?.releases().orEmpty()
    val visible = if (filterText.isBlank()) releases else releases.filter {
        it.id.contains(filterText.trim(), ignoreCase = true)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "服务端",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "下一个原版服务端（Mojang 的 server.jar），顺手写好 eula.txt 和双击就能开的 start.bat。" +
                "只有原版：Forge / NeoForge / Fabric / Paper 的服务端要另跑安装器，这一版不做。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ServerStatusBanner(state.phase, state.progress, state.message)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = dirText,
                onValueChange = { dirText = it },
                modifier = Modifier.weight(1f),
                label = { Text("保存目录") },
                singleLine = true,
                enabled = !running,
            )
            OutlinedButton(
                onClick = { pickDirectory(dirText)?.let { dirText = it.absolutePath } },
                enabled = !running,
            ) { Text("选择…") }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = versionText,
                onValueChange = { versionText = it },
                modifier = Modifier.weight(1f),
                label = { Text("版本号") },
                singleLine = true,
                enabled = !running,
                supportingText = { Text("手填也行，例如 1.20.1；1.2.5 之前的版本没有服务端 jar。") },
            )
            OutlinedTextField(
                value = memoryText,
                onValueChange = { memoryText = it.filter { ch -> ch.isDigit() }.take(5) },
                modifier = Modifier.width(120.dp),
                label = { Text("内存 MB") },
                singleLine = true,
                enabled = !running,
            )
            Button(onClick = { startDownload() }, enabled = !running) {
                Text(if (running) "下载中…" else "开始下载")
            }
            if (running) {
                OutlinedButton(onClick = { ServerRunner.cancel() }) { Text("取消") }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                modifier = Modifier.weight(1f),
                label = { Text("在版本列表里过滤（只看正式版）") },
                singleLine = true,
            )
            Text(
                when {
                    loadingManifest -> "正在拉取版本列表…"
                    manifestError != null -> "版本列表拉取失败"
                    else -> releases.size.toString() + " 个正式版"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        manifestError?.let {
            ServerHint(it + "（不影响手填版本号：会走 BMCLAPI 的版本接口兜底）", error = true)
        }

        if (visible.isEmpty() && releases.isNotEmpty() && !loadingManifest) {
            ServerHint(
                "没有名字里带「" + filterText.trim() + "」的正式版。清空过滤框能看全部 " +
                    releases.size + " 个；也可以直接在版本号里手填。",
            )
        }

        if (visible.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(4.dp)) {
                    items(visible, key = { it.id }) { entry ->
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

        (state.phase as? ServerPhase.Finished)?.let { ServerReadyCard(it.result) }
    }
}

/** 顶部状态条：一屏里最靠上的地方说清「现在在干什么」，免得用户以为按钮没反应。 */
@Composable
private fun ServerStatusBanner(
    phase: ServerPhase,
    progress: ServerInstallProgress?,
    message: String,
) {
    if (phase is ServerPhase.Idle && message.isBlank()) return
    val (container, content) = when (phase) {
        is ServerPhase.Failed ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                when (phase) {
                    is ServerPhase.Running -> "正在下载服务端"
                    is ServerPhase.Failed -> "没有装成"
                    is ServerPhase.Finished -> "服务端已就绪"
                    else -> "看看这里"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = content)
            }
            if (phase is ServerPhase.Running && progress != null && progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

/** 版本列表里的一行。 */
@Composable
private fun ServerVersionRow(
    entry: ManifestVersion,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.id,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            entry.releaseTime.take(10),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 装好之后给的东西：路径 + 那两个「接下来怎么办」。
 *
 * 这里不写「启动服务端」按钮：启动是长驻进程 + 要吞输入输出，属于另一件事；
 * 双击 start.bat 才是 Mojang 生态里所有人的习惯，也是用户能自己排查的方式。
 */
@Composable
private fun ServerReadyCard(result: ServerInstallResult) {
    var notice by remember { mutableStateOf("") }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                result.summary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "目录：" + result.dir.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "开服脚本：" + result.startScript.name + "（双击就能开；改内存就改里面的 -Xmx）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "eula.txt 已经写成 eula=true，等于替你同意了 Minecraft 最终用户许可协议。" +
                    "不同意的话把它改成 false，服务端会拒绝启动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { notice = openDirectory(result.dir) }) { Text("打开目录") }
                OutlinedButton(
                    onClick = {
                        notice = runCatching {
                            Desktop.getDesktop().open(result.startScript)
                            "已打开 " + result.startScript.name
                        }.getOrElse { "打不开，路径是：" + result.startScript.absolutePath }
                    },
                ) { Text("看开服脚本") }
            }
            if (notice.isNotBlank()) {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ServerHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 服务端的默认落点：游戏目录不在就退回当前工作目录下的 mc-server。 */
private fun defaultServerDir(initialGameRoot: String?): String {
    val root = initialGameRoot?.trim()?.trim('"')?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(defaultGameRoot())
    val parent = root.parentFile ?: return File("mc-server").absolutePath
    return File(parent, "mc-server").absolutePath
}

/** 打开一个目录；不行就把路径回给用户。 */
private fun openDirectory(dir: File): String = runCatching {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Desktop.getDesktop().open(dir)
        "已打开 " + dir.absolutePath
    } else {
        "打不开资源管理器，路径是：" + dir.absolutePath
    }
}.getOrElse { "打不开资源管理器，路径是：" + dir.absolutePath }
