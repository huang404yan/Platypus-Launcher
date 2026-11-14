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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.install.ServerInstallProgress
import io.github.huang404yan.platypus.core.install.ServerInstaller
import io.github.huang404yan.platypus.core.install.ServerInstaller.ServerInstallResult
import io.github.huang404yan.platypus.core.install.ServerEntry
import io.github.huang404yan.platypus.core.install.ServerKind
import io.github.huang404yan.platypus.core.install.ServerLoader
import io.github.huang404yan.platypus.core.install.serverEntryOf
import io.github.huang404yan.platypus.core.install.VanillaInstaller
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
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
    fun start(
        targetDir: File,
        versionId: String,
        loader: ServerLoader,
        memoryMb: Int,
        manifest: VersionManifest?,
        javaPath: String? = null,
    ) {
        if (_state.value.phase is ServerPhase.Running) return
        job?.cancel()
        _state.value = ServerState(
            phase = ServerPhase.Running,
            message = "正在准备 " + loader.kind.label + " 服务端（" + versionId + "）…",
        )
        job = scope.launch {
            try {
                val result = ServerInstaller.install(
                    targetDir = targetDir,
                    versionId = versionId,
                    loader = loader,
                    manifest = manifest,
                    memoryMb = memoryMb,
                    javaPath = javaPath,
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
 * 「下载 / 服务端」页：下一个能直接开的服务端，并把它变成双击能开的样子。
 *
 * 五种加载器各有各的路子（原版拿 Mojang 的 server.jar、Paper 拿 fill.papermc.io 的构建、
 * Fabric 用 meta 现成的 server jar、Forge 与 NeoForge 跑各自的 installer），差异都收在
 * [ServerInstaller] 里，这一页只负责选参数与显示进度。
 *
 * 装完自动写进服务端登记（[ServerEntry]），下次进来在列表里还看得见 —— 但刻意不给
 * 「启动」按钮：开服是长驻进程，要接控制台输入输出，属于另一件事。
 *
 * 为什么不复用 [DownloadsPage]：那边一整套是「装进游戏目录并登记成实例」，
 * 服务端不登记实例、不装 Java、也不共享 versions/ 布局，共用只会两边都变成 if。
 */
@Composable
fun ServerPage(initialGameRoot: String? = null, backend: LauncherBackend? = null) {
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

    // 加载器：默认原版。Forge / NeoForge 要跑安装器，得先有一个 Java。
    var kind by remember { mutableStateOf(ServerKind.VANILLA) }
    var loaderVersion by remember { mutableStateOf("") }
    var javaList by remember { mutableStateOf<List<JavaRuntime>>(emptyList()) }
    var javaPath by remember { mutableStateOf<String?>(null) }
    var javaError by remember { mutableStateOf("") }
    var loadingJava by remember { mutableStateOf(false) }

    LaunchedEffect(kind) {
        // 换加载器时输入框里的旧值就没意义了（"196" 对 Fabric 是错的）
        loaderVersion = ""
    }

    suspend fun detectJava() {
        val service = backend?.javaRuntimes
        if (service == null) {
            javaError = "后端没装配，探测不了 Java。"
            return
        }
        if (loadingJava) return
        loadingJava = true
        javaError = ""
        try {
            val found = withContext(Dispatchers.IO) { service.detect() }
            javaList = found
            // 挑一个能用的：优先 17 及以上（1.17 之后的服务端安装器要 16+），
            // 没有再取主版本最大的那个 —— 老版本 Forge 反而要 Java 8，让用户自己改。
            javaPath = javaPath?.takeIf { picked -> found.any { it.path == picked } }
                ?: found.filter { it.majorVersion >= 17 }.maxByOrNull { it.majorVersion }?.path
                ?: found.maxByOrNull { it.majorVersion }?.path
        } catch (t: Throwable) {
            javaError = t.message ?: (t::class.simpleName ?: "探测 Java 失败")
        } finally {
            loadingJava = false
        }
    }

    val javaScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (javaList.isEmpty()) detectJava()
    }

    // 装过的服务端登记：进页面先读一次，装完再刷新
    var entries by remember { mutableStateOf<List<ServerEntry>>(emptyList()) }
    // 本次启动用的内存。登记时写它而不是读输入框：用户装完之后可能已经改了输入框，
    // 而已经装好的那个服务端用的还是当时那个值。
    var lastMemoryMb by remember { mutableStateOf(ServerInstaller.DEFAULT_MEMORY_MB) }
    var registryNotice by remember { mutableStateOf("") }
    var registryError by remember { mutableStateOf("") }
    var registeredDir by remember { mutableStateOf("") }

    suspend fun reloadEntries() {
        val repo = backend?.servers
        if (repo == null) {
            registryError = "后端没装配，服务端登记不可用（这次装完只在本次运行里能用）。"
            return
        }
        registryError = ""
        entries = runCatching { withContext(Dispatchers.IO) { repo.list() } }
            .getOrElse {
                registryError = "读服务端登记失败：" + (it.message ?: it::class.simpleName.orEmpty())
                emptyList()
            }
    }

    LaunchedEffect(Unit) { reloadEntries() }

    // 装完自动登记一次。用目录去重而不是用 id：页面切走再回来还会看到同一个 Finished，
    // 那时结果对象还是原来那个，重复登记只会把同一条覆盖成一样的内容。
    LaunchedEffect(state.phase) {
        val finished = state.phase as? ServerPhase.Finished ?: return@LaunchedEffect
        val repo = backend?.servers ?: return@LaunchedEffect
        val dir = finished.result.dir.absolutePath
        if (dir == registeredDir) return@LaunchedEffect
        val entry = serverEntryOf(finished.result, memoryMb = lastMemoryMb, javaPath = javaPath)
        val error = runCatching { withContext(Dispatchers.IO) { repo.save(entry) } }.exceptionOrNull()
        if (error != null) {
            registryError = "装好了，但写不进服务端登记：" + (error.message ?: error::class.simpleName.orEmpty())
            return@LaunchedEffect
        }
        registeredDir = dir
        reloadEntries()
    }

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
        if (kind.needsInstaller && javaPath.isNullOrBlank()) {
            ServerRunner.hint(
                "装 " + kind.label + " 服务端要用 Java 跑安装器：先在下面选一个 Java，" +
                    "本机一个都没探测到的话，去「启动」页点「自动补 Java」。",
            )
            return
        }
        lastMemoryMb = memory
        ServerRunner.start(
            targetDir = File(dir),
            versionId = id,
            loader = ServerLoader(kind = kind, loaderVersion = loaderVersion.trim().ifBlank { null }),
            memoryMb = memory,
            manifest = manifest,
            javaPath = javaPath,
        )
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
            "下一个能直接开的服务端，顺手写好 eula.txt 和双击就能开的启动脚本。" +
                "五种都支持：原版走 Mojang 的 server.jar、Paper 走 fill.papermc.io、" +
                "Fabric 用 meta 现成的 server jar、Forge 与 NeoForge 跑各自的 installer（要几分钟）。",
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

        // 加载器：五个平铺按钮，跟 PCL 的服务端安装页一样（那边也是选完版本再选加载器）
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "加载器",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ServerKind.entries.forEach { item ->
                LoaderChip(
                    label = item.label,
                    selected = item == kind,
                    enabled = !running,
                    onClick = { kind = item },
                )
            }
        }

        // 加载器版本：原版不需要，其它都要（留空就用推荐版）
        if (kind != ServerKind.VANILLA) {
            OutlinedTextField(
                value = loaderVersion,
                onValueChange = { loaderVersion = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(kind.label + " 版本") },
                singleLine = true,
                enabled = !running,
                supportingText = { Text(loaderHintOf(kind)) },
            )
        }

        // Forge / NeoForge 要跑安装器，必须有一个 Java；其它加载器不需要，不占地方
        if (kind.needsInstaller) {
            JavaPickerRow(
                runtimes = javaList,
                selected = javaPath,
                loading = loadingJava,
                error = javaError,
                enabled = !running,
                onPick = { javaPath = it },
                onRedetect = { javaScope.launch { detectJava() } },
            )
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

        if (registryError.isNotBlank()) ServerHint(registryError, error = true)
        if (registryNotice.isNotBlank()) ServerHint(registryNotice)

        if (entries.isNotEmpty()) {
            ServerRegistryCard(
                entries = entries,
                onOpen = { entry -> registryNotice = openDirectory(File(entry.directory)) },
                onForget = { entry ->
                    val repo = backend?.servers
                    if (repo == null) {
                        registryNotice = "后端没装配，删不掉登记。"
                    } else {
                        javaScope.launch {
                            runCatching { withContext(Dispatchers.IO) { repo.remove(entry.id) } }
                                .onSuccess {
                                    registryNotice = "已从列表里去掉 " + entry.title + "（目录和文件都还在）。"
                                    reloadEntries()
                                }
                                .onFailure {
                                    registryNotice = "删登记失败：" + (it.message ?: it::class.simpleName.orEmpty())
                                }
                        }
                    }
                },
            )
        }
    }
}

/** 加载器选择用的小按钮。选中的填主题色底，跟左栏一级项一个视觉语言。 */
@Composable
private fun LoaderChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }
    Card(
        Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .background(background, MaterialTheme.shapes.small),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * 给 Forge / NeoForge 挑一个 Java。
 *
 * 为什么让用户挑而不是自动选死：安装器是子进程，跑的是 java -jar installer.jar，
 * 版本不对的 java（拿 Java 25 去跑给 Java 8 写的 1.12 安装器）会直接
 * UnsupportedClassVersionError，而那个报错埋在安装器日志里，界面上只看到「失败」。
 */
@Composable
private fun JavaPickerRow(
    runtimes: List<JavaRuntime>,
    selected: String?,
    loading: Boolean,
    error: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
    onRedetect: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (loading) "正在探测 Java…" else "安装器用的 Java",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onRedetect, enabled = enabled && !loading) { Text("重新探测") }
        }
        if (error.isNotBlank()) {
            ServerHint(error, error = true)
        }
        if (runtimes.isEmpty() && !loading && error.isBlank()) {
            ServerHint("本机没探测到 Java。去「启动」页点「自动补 Java」，回来再点「重新探测」。")
        }
        if (runtimes.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 120.dp).padding(4.dp)) {
                    items(runtimes, key = { it.path }) { runtime ->
                        val isSelected = runtime.path == selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    } else {
                                        Color.Transparent
                                    },
                                    MaterialTheme.shapes.small,
                                )
                                .clickable(enabled = enabled) { onPick(runtime.path) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Java " + runtime.majorVersion,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                runtime.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 加载器版本输入框下面的说明：留空会发生什么，各加载器不一样。 */
private fun loaderHintOf(kind: ServerKind): String = when (kind) {
    ServerKind.VANILLA -> ""
    ServerKind.PAPER -> "填构建号，例如 196；留空取最新构建。"
    ServerKind.FABRIC -> "填 loader 版本，例如 0.15.11；留空取最新稳定版。"
    ServerKind.FORGE -> "填 Forge 版本，例如 47.4.0；留空取推荐版。"
    ServerKind.NEOFORGE -> "填 NeoForge 版本，例如 21.1.266；留空取适配该游戏版本的版本。"
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
                "开服脚本：" + result.startScript.name + "（双击就能开）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (result.kind.needsInstaller) {
                // Forge / NeoForge 的 run 脚本是安装器生成的，内存不在脚本里而在 user_jvm_args.txt，
                // 说错地方用户会以为改了没生效。
                Text(
                    "改内存改 user_jvm_args.txt（安装器生成的 run.bat 会读它），别改 run.bat 本身。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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


/**
 * 装过的服务端列表。
 *
 * 只有「打开目录」和「从列表里去掉」两个动作，刻意没有「启动」：开服是长驻进程，
 * 要接控制台输入输出、要能看日志，属于另一件事。现在给一个按钮只会让人以为点完就开好了。
 * 去掉只是不显示，不动磁盘上的任何文件 —— 这句话要写在按钮旁边，否则用户不敢点。
 */
@Composable
private fun ServerRegistryCard(
    entries: List<ServerEntry>,
    onOpen: (ServerEntry) -> Unit,
    onForget: (ServerEntry) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "装过的服务端（" + entries.size + "）",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            entries.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth(),
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
                    Text(
                        "内存 " + entry.memoryMb + " MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { onOpen(entry) }) { Text("打开目录") }
                    OutlinedButton(onClick = { onForget(entry) }) { Text("从列表里去掉") }
                }
            }
            Text(
                "「从列表里去掉」只是不在这里显示，目录和里面的文件一个都不删。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
