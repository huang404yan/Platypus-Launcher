package io.github.huang404yan.platypus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.MemoryDefaults
import io.github.huang404yan.platypus.core.loader.FabricLoaderVersion
import io.github.huang404yan.platypus.core.loader.ForgeVersion
import io.github.huang404yan.platypus.core.loader.NeoForgeVersion
import io.github.huang404yan.platypus.core.loader.QuiltLoaderVersion
import io.github.huang404yan.platypus.core.loader.QuiltMeta
import io.github.huang404yan.platypus.core.version.VersionManifest
import io.github.huang404yan.platypus.core.install.FabricInstaller
import io.github.huang404yan.platypus.core.install.ForgeInstaller
import io.github.huang404yan.platypus.core.install.InstallStep
import io.github.huang404yan.platypus.core.install.LoaderChoice
import io.github.huang404yan.platypus.core.install.NeoForgeInstaller
import io.github.huang404yan.platypus.core.install.QuiltInstaller
import io.github.huang404yan.platypus.core.install.StepProgress
import io.github.huang404yan.platypus.core.install.VanillaInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 装完之后进度条停在 100% 的时长。
 *
 * 走满就消失会让人来不及确认「真的满了」，一直停着又像还没结束；
 * 5 秒是用户点名要的时长（docs/62 第 3 条）。
 */
private const val COMPLETION_HOLD_MS = 5000L

/** 下拉里的一项：版本号 + 右侧标签（stable/beta、推荐/最新）。 */
private data class LoaderOption(val version: String, val tag: String, val selected: Boolean)

/**
 * 「顺带装某个加载器」的一行：勾选框 + 状态说明 + 版本下拉。
 *
 * Fabric 与 Forge 共用这一份 UI，只把「状态文案」「选项」「错误前缀」交给调用方决定：
 * 两边拉列表的机制完全不同（Fabric 一次几十个 stable/beta，Forge 只有推荐与最新两条），
 * 但用户看到的那一行应该长得一模一样。
 *
 * 下拉用展开式列表而不是 ExposedDropdownMenu——Compose MP 桌面端的 Menu 组件
 * 在弹层里对点击的响应不稳，直接铺一个可滚动的 Card 更省事。
 */
@Composable
private fun LoaderPickerRow(
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    status: String,
    options: List<LoaderOption>,
    onPick: (String) -> Unit,
    errorPrefix: String,
    error: String?,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onCheckedChange(it) },
                enabled = enabled,
            )
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                status,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 这个按钮原来只是个小尺寸的文字按钮，点击区就一行字高 —— 用户抱怨
            // 「选择的框太小点不中」（2026-11-16）。改成占满 1/3 宽、最小 44dp 高，
            // 且把「当前选中的版本号」直接写在按钮上，不展开也知道选了哪个。
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(min = 180.dp)
                    .heightIn(min = 44.dp),
                enabled = enabled && options.isNotEmpty(),
            ) {
                Text(
                    when {
                        expanded -> "收起版本列表"
                        options.isEmpty() -> "选 loader 版本"
                        else -> options.firstOrNull { it.selected }?.let { "版本 " + it.version } ?: "选 loader 版本"
                    },
                    maxLines = 1,
                )
            }
        }

        if (expanded && options.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(4.dp)) {
                    items(options, key = { it.version }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (entry.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else Color.Transparent,
                                    MaterialTheme.shapes.small,
                                )
                                .clickable(enabled = enabled) {
                                    onPick(entry.version)
                                    // 选了版本就等于要吃这个加载器，顺手把勾选框勾上：
                                    // 用户点的是「这个版本」，不该再回头看那个勾选框。
                                    onCheckedChange(true)
                                    expanded = false
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                entry.version,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                entry.tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (error != null) {
            DownloadHint(errorPrefix + "：" + error, error = true)
        }
    }
}

/** Fabric 那一行：把 loader 列表映射成通用行需要的文案与选项。 */
@Composable
private fun FabricRow(
    enabled: Boolean,
    installFabric: Boolean,
    onInstallFabricChange: (Boolean) -> Unit,
    gameVersion: String,
    versions: List<FabricLoaderVersion>,
    selected: String,
    onSelectedChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
) {
    LoaderPickerRow(
        enabled = enabled,
        checked = installFabric,
        onCheckedChange = onInstallFabricChange,
        title = "顺带装 Fabric",
        status = when {
            gameVersion.isBlank() -> "先选一个原版版本"
            loading -> "正在查 loader 列表…"
            error != null -> "loader 列表拉取失败"
            versions.isEmpty() -> "这个版本没有可用的 Fabric"
            selected.isBlank() -> "选一个 loader 版本"
            else -> selected + "（" + versions.size + " 个可选）"
        },
        options = versions.map {
            LoaderOption(version = it.version, tag = if (it.stable) "stable" else "beta", selected = it.version == selected)
        },
        onPick = onSelectedChange,
        errorPrefix = "Fabric",
        error = error,
    )
}

/**
 * Forge 那一行。
 *
 * 选项来自 maven-metadata.xml 的全量清单（几十到上百条），推荐与最新各带一个标签，
 * 其余是历史版本 —— 用户想退到一个旧一点的 Forge 时有得选。
 * promotions_slim.json 仍然要拉，它决定谁是「推荐」。 */
@Composable
private fun ForgeRow(
    enabled: Boolean,
    installForge: Boolean,
    onInstallForgeChange: (Boolean) -> Unit,
    gameVersion: String,
    versions: List<ForgeVersion>,
    selected: String,
    onSelectedChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
) {
    LoaderPickerRow(
        enabled = enabled,
        checked = installForge,
        onCheckedChange = onInstallForgeChange,
        title = "顺带装 Forge",
        status = when {
            gameVersion.isBlank() -> "先选一个原版版本"
            loading -> "正在查 Forge 版本…"
            error != null -> "Forge 版本拉取失败"
            versions.isEmpty() -> "这个版本没有 Forge"
            selected.isBlank() -> "选一个 Forge 版本"
            else -> selected + "（" + versions.size + " 个可选 · 官方安装器约 3 分钟）"
        },
        options = versions.map {
            LoaderOption(version = it.version, tag = it.label, selected = it.version == selected)
        },
        onPick = onSelectedChange,
        errorPrefix = "Forge",
        error = error,
    )
}

/**
 * NeoForge 那一行。
 *
 * 与 Forge 长得一样，但选项来源不同：NeoForge 走自己的 maven-metadata.xml，
 * 而 NeoForge 是 maven 里一长串真实版本号（21.1.265 这种），每个版本都能选。
 * 数量可能上千，所以只列当前游戏版本能用的那几十个。
 */
@Composable
private fun NeoForgeRow(
    enabled: Boolean,
    installNeoForge: Boolean,
    onInstallNeoForgeChange: (Boolean) -> Unit,
    gameVersion: String,
    versions: List<NeoForgeVersion>,
    selected: String,
    onSelectedChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
) {
    LoaderPickerRow(
        enabled = enabled,
        checked = installNeoForge,
        onCheckedChange = onInstallNeoForgeChange,
        title = "顺带装 NeoForge",
        status = when {
            gameVersion.isBlank() -> "先选一个原版版本"
            loading -> "正在查 NeoForge 版本…"
            error != null -> "NeoForge 版本拉取失败"
            versions.isEmpty() -> "这个版本没有 NeoForge"
            selected.isBlank() -> "选一个 NeoForge 版本"
            else -> selected + "（" + versions.size + " 个可选）"
        },
        options = versions.map {
            LoaderOption(version = it.version, tag = it.label, selected = it.version == selected)
        },
        onPick = onSelectedChange,
        errorPrefix = "NeoForge",
        error = error,
    )
}

/**
 * Quilt 那一行。
 *
 * 与 Fabric 长得一样（同样是「原版之上叠一层」），但选项来源不同：
 * Quilt 的清单按构建时间排、且没有 stable 字段，所以默认值要靠自己按版本号挑，
 * 不能像 Fabric 那样拿第一个标了 stable 的。
 */
@Composable
private fun QuiltRow(
    enabled: Boolean,
    installQuilt: Boolean,
    onInstallQuiltChange: (Boolean) -> Unit,
    gameVersion: String,
    versions: List<QuiltLoaderVersion>,
    selected: String,
    onSelectedChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
) {
    LoaderPickerRow(
        enabled = enabled,
        checked = installQuilt,
        onCheckedChange = onInstallQuiltChange,
        title = "顺带装 Quilt",
        status = when {
            gameVersion.isBlank() -> "先选一个原版版本"
            loading -> "正在查 Quilt 版本…"
            error != null -> "Quilt 版本拉取失败"
            versions.isEmpty() -> "这个版本没有 Quilt"
            selected.isBlank() -> "选一个 Quilt 版本"
            else -> selected + "（" + versions.size + " 个可选）"
        },
        options = versions.map {
            LoaderOption(version = it.version, tag = it.label, selected = it.version == selected)
        },
        onPick = onSelectedChange,
        errorPrefix = "Quilt",
        error = error,
    )
}

/**
 * 下载页：选一个原版版本，一次完成「下载 → 补 Java → 建实例」。
 *
 * 解析、下载、解压都在 Dispatchers.IO 上跑，界面只读状态，不阻塞 UI 线程。
 */
@Composable
fun DownloadsPage(
    initialGameRoot: String?,
    backend: LauncherBackend? = null,
    /** 左栏「刷新」按钮的信号：每点一次加一，页面拿它当 LaunchedEffect 的 key。 */
    refreshTick: Int = 0,
    onInstalled: (Instance) -> Unit = {},
) {
    var gameDirText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    // 设置里指定的默认内存；一键装按它登记新实例，用户装完还能在启动页单独改
    var defaultMemoryMb by remember { mutableStateOf(Instance.DEFAULT_MEMORY_MB) }
    var versionText by remember { mutableStateOf("") }
    var filterText by remember { mutableStateOf("") }

    // Fabric：选中的版本变化时重新拉 loader 列表，默认落在最新 stable 上。
    var installFabric by remember { mutableStateOf(false) }
    var loaderVersions by remember { mutableStateOf<List<FabricLoaderVersion>>(emptyList()) }
    var loaderVersion by remember { mutableStateOf("") }
    var loaderError by remember { mutableStateOf<String?>(null) }
    var loadingLoaders by remember { mutableStateOf(false) }

    // Forge：官方只给 recommended / latest 两个频道，拉一次就有全部可选项。
    // 与 Fabric 互斥——一次只能叠一层加载器，两个都勾着装出来的东西没法解释。
    var installForge by remember { mutableStateOf(false) }
    var forgeVersions by remember { mutableStateOf<List<ForgeVersion>>(emptyList()) }
    var forgeVersion by remember { mutableStateOf("") }
    var forgeError by remember { mutableStateOf<String?>(null) }
    var loadingForge by remember { mutableStateOf(false) }

    // NeoForge：一次能列出几十个真实版本号，同样与上面两个互斥。
    var installNeoForge by remember { mutableStateOf(false) }
    var neoforgeVersions by remember { mutableStateOf<List<NeoForgeVersion>>(emptyList()) }
    var neoforgeVersion by remember { mutableStateOf("") }
    var neoforgeError by remember { mutableStateOf<String?>(null) }
    var loadingNeoForge by remember { mutableStateOf(false) }

    // Quilt：同样是「原版之上叠一层」，与上面三个两两互斥。
    var installQuilt by remember { mutableStateOf(false) }
    var quiltVersions by remember { mutableStateOf<List<QuiltLoaderVersion>>(emptyList()) }
    var quiltVersion by remember { mutableStateOf("") }
    var quiltError by remember { mutableStateOf<String?>(null) }
    var loadingQuilt by remember { mutableStateOf(false) }

    var manifest by remember { mutableStateOf<VersionManifest?>(null) }
    var manifestError by remember { mutableStateOf<String?>(null) }
    var loadingManifest by remember { mutableStateOf(false) }

    // 安装状态住在进程级的 OneClickRunner 里，不跟着页面走：
    // 用 remember 存的话，切到「实例」页再切回来，下载到一半的协程会被取消。
    val install = OneClickRunner.state.collectAsState().value
    val phase = install.phase
    val currentStep = install.currentStep
    val stepStates = install.stepStates
    val progress = install.progress
    val speedBytesPerSecond = install.speedBytesPerSecond
    val elapsedMs = install.elapsedMs
    val message = install.message
    // 失败时 phase 里存着原始原因，state.message 也拼了同一句话；而「条件没满足」的提示
    // 只写进 message、不动 phase。下面两处提示靠这一行分辨：同一个话题只说一次。
    val failureText = (phase as? InstallPhase.Failed)?.let { "卡在「" + it.step.label + "」：" + it.message }
    // 点「一键安装 / 重试这一步」时把页面送回顶部：状态摘要卡在那里，不滚回去就等于没反馈。
    // 必须声明在 startInstall() 之前 —— 局部函数看不到它后面才声明的局部变量。
    val scroll = rememberScrollState()
    val scrollScope = rememberCoroutineScope()

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

    /**
     * 点「一键安装」或「重试这一步」都走这里。
     *
     * 两个按钮都在这页偏下的位置，而状态摘要卡在最上面。点完不把视线送回去，用户看到的
     * 就是脚边那一行纹丝不动，于是判断「没反应」—— 所以每次点击都先滚回顶部：要么立刻
     * 看到蓝色进度卡（真开始了），要么看到红色提示（校验没过，缺什么一目了然）。
     */
    fun startInstall() {
        scrollScope.launch { scroll.animateScrollTo(0) }
        // 后端先取出来：下面每条分支都要用它，也省得编译器在 when 之后还以为它可能为 null。
        val ready = backend
        if (ready == null) {
            OneClickRunner.hint("后端未装配：一键安装需要桌面端注入 Java 与实例存储实现。")
            return
        }
        val dirText = gameDirText.trim()
        if (dirText.isEmpty()) {
            OneClickRunner.hint("先填一个目标目录，比如 D:/mc-test。")
            return
        }
        val id = versionText.trim()
        if (id.isEmpty()) {
            OneClickRunner.hint("先选或手填一个版本号，比如 1.20.1。")
            return
        }
        if (installFabric && loaderVersion.isBlank()) {
            OneClickRunner.hint("还没选 Fabric 版本：等下面的 loader 列表拉出来，或者取消「装 Fabric」。")
            return
        }
        if (installForge && forgeVersion.isBlank()) {
            OneClickRunner.hint("还没选 Forge 版本：等下面的版本列表拉出来，或者取消「装 Forge」。")
            return
        }
        if (installNeoForge && neoforgeVersion.isBlank()) {
            OneClickRunner.hint("还没选 NeoForge 版本：等下面的版本列表拉出来，或者取消「装 NeoForge」。")
            return
        }
        if (installQuilt && quiltVersion.isBlank()) {
            OneClickRunner.hint("还没选 Quilt 版本：等下面的版本列表拉出来，或者取消「装 Quilt」。")
            return
        }
        val dir = File(dirText)
        val choice = when {
            installFabric -> LoaderChoice.Fabric(loaderVersion)
            installForge -> LoaderChoice.Forge(forgeVersion)
            installNeoForge -> LoaderChoice.NeoForge(neoforgeVersion)
            installQuilt -> LoaderChoice.Quilt(quiltVersion)
            else -> LoaderChoice.Vanilla
        }
        // 跑在 OneClickRunner 的进程级作用域里：切页不会打断，回来还能看到进度。
        OneClickRunner.start(
            gameDir = dir,
            versionId = id,
            memoryMb = defaultMemoryMb,
            loader = choice,
            manifest = manifest,
            javaRuntimes = ready.javaRuntimes,
            instances = ready.instances,
        )
    }

    /** 拉某个原版版本的 Fabric loader 列表；失败只记原因，不弹错。 */
    suspend fun refreshLoaders(gameVersion: String) {
        if (gameVersion.isBlank()) {
            loaderVersions = emptyList()
            loaderVersion = ""
            return
        }
        loadingLoaders = true
        loaderError = null
        try {
            withContext(Dispatchers.IO) {
                val versions = FabricInstaller.listLoaders(gameVersion)
                loaderVersions = versions
                val preferred = versions.firstOrNull { it.stable } ?: versions.firstOrNull()
                loaderVersion = preferred?.version.orEmpty()
            }
        } catch (e: CancellationException) {
            // 换版本号会重启 LaunchedEffect，正在飞的请求被取消是正常现象，不能当成「拉取失败」显示
            throw e
        } catch (e: Exception) {
            loaderVersions = emptyList()
            loaderVersion = ""
            loaderError = e.message ?: (e::class.simpleName ?: "拉取 Fabric 版本失败")
        } finally {
            // 取消时也要把 loading 收掉，否则界面会一直停在「正在查 loader 列表…」
            loadingLoaders = false
        }
    }


    /** 拉某个原版版本的 Quilt loader 列表；失败只记原因，不弹错。 */
    suspend fun refreshQuiltLoaders(gameVersion: String) {
        if (gameVersion.isBlank()) {
            quiltVersions = emptyList()
            quiltVersion = ""
            return
        }
        loadingQuilt = true
        quiltError = null
        try {
            withContext(Dispatchers.IO) {
                val versions = QuiltInstaller.listLoaders(gameVersion)
                quiltVersions = versions
                // 清单按构建时间排，不能取第一个；latestGrown 按版本号挑最高的正式版。
                quiltVersion = QuiltMeta.latestGrown(versions)?.version.orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            quiltVersions = emptyList()
            quiltVersion = ""
            quiltError = e.message ?: (e::class.simpleName ?: "拉取 Quilt 版本失败")
        } finally {
            loadingQuilt = false
        }
    }

    /** 拉某个原版版本的全部 Forge 版本（推荐/最新带标签）；失败只记原因，不弹错。 */
    suspend fun refreshForgeVersions(gameVersion: String) {
        if (gameVersion.isBlank()) {
            forgeVersions = emptyList()
            forgeVersion = ""
            return
        }
        loadingForge = true
        forgeError = null
        try {
            withContext(Dispatchers.IO) {
                val versions = ForgeInstaller.listVersions(gameVersion)
                forgeVersions = versions
                // 默认落在推荐版；没有推荐的版本（新版本刚发时常见）才用最新。
                val preferred = versions.firstOrNull { it.recommended }
                    ?: versions.firstOrNull { it.latest }
                    ?: versions.firstOrNull()
                forgeVersion = preferred?.version.orEmpty()
            }
        } catch (e: CancellationException) {
            // 与 Fabric 同理：换版本号会重启 LaunchedEffect，取消不是失败
            throw e
        } catch (e: Exception) {
            forgeVersions = emptyList()
            forgeVersion = ""
            forgeError = e.message ?: (e::class.simpleName ?: "拉取 Forge 版本失败")
        } finally {
            loadingForge = false
        }
    }

    /** 拉某个原版版本的 NeoForge 版本列表；失败只记原因，不弹错。 */
    suspend fun refreshNeoForgeVersions(gameVersion: String) {
        if (gameVersion.isBlank()) {
            neoforgeVersions = emptyList()
            neoforgeVersion = ""
            return
        }
        loadingNeoForge = true
        neoforgeError = null
        try {
            withContext(Dispatchers.IO) {
                val versions = NeoForgeInstaller.listVersions(gameVersion)
                neoforgeVersions = versions
                // 默认落在清单里最新的稳定版：与 Fabric 一样，用户大多数时候要的是「能跑的最新」。
                val preferred = versions.lastOrNull { it.stable } ?: versions.lastOrNull()
                neoforgeVersion = preferred?.version.orEmpty()
            }
        } catch (e: CancellationException) {
            // 与 Fabric / Forge 同理：换版本号会重启 LaunchedEffect，取消不是失败
            throw e
        } catch (e: Exception) {
            neoforgeVersions = emptyList()
            neoforgeVersion = ""
            neoforgeError = e.message ?: (e::class.simpleName ?: "拉取 NeoForge 版本失败")
        } finally {
            loadingNeoForge = false
        }
    }

    fun cancelInstall() {
        OneClickRunner.cancel()
    }

    LaunchedEffect(versionText) {
        // 换版本就换加载器列表；不开「装 Fabric / Forge」时也顺手拉，用户一勾就有得选。
        // 手打版本号时每敲一个字符都会触发一次，先等 400ms 让输入停下来，避免连发请求互相取消。
        if (versionText.isBlank()) return@LaunchedEffect
        delay(400)
        refreshLoaders(versionText.trim())
        // Forge 要拉两个文件：promotions_slim.json（4 KB，定推荐/最新）与
        // maven-metadata.xml（约 200 KB，定全量可选版本）。失败也不影响只装原版，
        // 所以和 Fabric 一样只记原因不弹错。
        refreshForgeVersions(versionText.trim())
        // NeoForge 的 maven-metadata.xml 有几十 KB（上千个版本），拉一次只要几百毫秒。
        refreshNeoForgeVersions(versionText.trim())
        // Quilt 的 loader 清单与 Fabric 一样只几 KB，顺手一起拉。
        refreshQuiltLoaders(versionText.trim())
    }

    LaunchedEffect(Unit) {
        // 设置里的默认游戏目录与默认内存先读，避免用户每次都要重填目录、装出来的实例内存是写死的
        val repository = backend?.settings
        if (repository != null) {
            val stored = withContext(Dispatchers.IO) { runCatching { repository.load() }.getOrNull() }
            if (stored != null) {
                if (initialGameRoot.isNullOrBlank() && stored.defaultGameRoot.isNotBlank()) {
                    gameDirText = stored.defaultGameRoot
                }
                defaultMemoryMb = MemoryDefaults.resolve(stored.defaultMemoryMb, stored.defaultMemoryAuto, SystemMemory.totalMb)
            }
        }
        if (manifest == null) refreshManifest()
    }

    // 左栏那个「↻」：重拉版本清单。tick 为 0 是首次组合，交给上面那个 effect 拉就行。
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) refreshManifest()
    }

    val busy = phase is InstallPhase.Running
    val releases = manifest?.releases().orEmpty()
    val visible = if (filterText.isBlank()) {
        releases
    } else {
        releases.filter { it.id.contains(filterText.trim(), ignoreCase = true) }
    }

    // 整页要能滚：一键装的步骤列表、进度、失败重试、整合包导入都在版本列表下面，
    // 1280x800 这种默认窗口高度下不加滚动就有半屏内容是永远看不到的。
    // 这个 scroll 要能被 startInstall() 拿去滚回顶部 —— 所以它不能是匿名实参。
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("下载", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "选一个版本点「一键安装」：下载原版文件、按需补 Java 运行时、登记成实例，完成后直接去启动。" +
                "文件按 sha1 校验，已存在的会跳过，可以中断后再来一次。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 安装状态置顶：原来的进度、速度、失败原因都在整页最下面，一滚就看不见，
        // 用户点完「一键安装」往往以为没反应（窗口小的时候更明显）。这里只放一行摘要，
        // 详细的分步列表仍在下方，两处说的是同一份状态。
        InstallStatusBanner(
            phase = phase,
            currentStep = currentStep,
            progress = progress,
            speedBytesPerSecond = speedBytesPerSecond,
            elapsedMs = elapsedMs,
            message = message,
            onStart = onInstalled,
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
                label = { Text("目标目录（会当作 .minecraft 用）") },
                singleLine = true,
                enabled = !busy,
            )
            OutlinedButton(
                onClick = { pickDirectory(gameDirText)?.let { gameDirText = it.absolutePath } },
                enabled = !busy,
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
                label = { Text("版本号（可手填，如 1.20.1）") },
                singleLine = true,
                enabled = !busy,
            )
            // 勾了加载器就把按钮改名——用户点的是同一个动作，
            // 但按钮文字要能对得上他心里想的那件事。
            Button(onClick = { startInstall() }, enabled = !busy) {
                Text(
                    when {
                        installFabric -> "安装 Fabric"
                        installForge -> "安装 Forge"
                        installNeoForge -> "安装 NeoForge"
                        installQuilt -> "安装 Quilt"
                        else -> "一键安装"
                    },
                )
            }
            if (busy) {
                OutlinedButton(onClick = { cancelInstall() }) { Text("取消") }
            }
        }

        // 点「一键安装」但条件没满足时（目录没填、没选版本、没选 loader）的提示。
        // 原来它在版本列表下面，隔着几十行 —— 用户点了按钮以为坏了，所以要挪到按钮正下方。
        // 失败态同样要显示：以前这里排除了 Failed，于是「装失败之后再点重试、这次校验又没过」
        // 时一个字都不出现，用户只看到那块旧的红字，以为按钮是死的。
        // 与下面失败区重复的那一句（message == failureText）交给那边用 error 色显示。
        // 装完的那一句（describeOutcome）已经进了顶部那张卡的正文，这里再写一遍就是
        // 「同一件事说两次」，正是 docs/62 第 3 条要去掉的那条灰字。
        if (message.isNotBlank() && message != failureText && phase !is InstallPhase.Finished) {
            DownloadHint(message)
        }

        FabricRow(
            enabled = !busy,
            installFabric = installFabric,
            onInstallFabricChange = {
                installFabric = it
                if (it) {
                    installForge = false
                    installNeoForge = false
                    installQuilt = false
                }
            },
            gameVersion = versionText.trim(),
            versions = loaderVersions,
            selected = loaderVersion,
            onSelectedChange = { loaderVersion = it },
            loading = loadingLoaders,
            error = loaderError,
        )

        ForgeRow(
            enabled = !busy,
            installForge = installForge,
            onInstallForgeChange = {
                installForge = it
                if (it) {
                    installFabric = false
                    installNeoForge = false
                    installQuilt = false
                }
            },
            gameVersion = versionText.trim(),
            versions = forgeVersions,
            selected = forgeVersion,
            onSelectedChange = { forgeVersion = it },
            loading = loadingForge,
            error = forgeError,
        )

        NeoForgeRow(
            enabled = !busy,
            installNeoForge = installNeoForge,
            onInstallNeoForgeChange = {
                installNeoForge = it
                if (it) {
                    installFabric = false
                    installForge = false
                    installQuilt = false
                }
            },
            gameVersion = versionText.trim(),
            versions = neoforgeVersions,
            selected = neoforgeVersion,
            onSelectedChange = { neoforgeVersion = it },
            loading = loadingNeoForge,
            error = neoforgeError,
        )

        QuiltRow(
            enabled = !busy,
            installQuilt = installQuilt,
            onInstallQuiltChange = {
                installQuilt = it
                if (it) {
                    installFabric = false
                    installForge = false
                    installNeoForge = false
                }
            },
            gameVersion = versionText.trim(),
            versions = quiltVersions,
            selected = quiltVersion,
            onSelectedChange = { quiltVersion = it },
            loading = loadingQuilt,
            error = quiltError,
        )

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

        manifestError?.let { DownloadHint(it + "（不影响手填版本号：会走 BMCLAPI 的版本接口兜底）", error = true) }

        // 过滤词打错了要立刻说，否则用户会盯着空列表以为版本列表挂了
        if (visible.isEmpty() && releases.isNotEmpty() && !loadingManifest) {
            DownloadHint(
                "没有名字里带「" + filterText.trim() + "」的正式版。清空过滤框能看全部 " +
                    releases.size + " 个；也可以直接在版本号里手填。",
            )
        }

        if (visible.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                // 132dp 只够露出三四行，一滚就找不到刚才那个版本了；放到 200dp 能同时看到八九行。
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(4.dp)) {
                    items(visible, key = { it.id }) { entry ->
                        val selected = entry.id == versionText.trim()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    else Color.Transparent,
                                    MaterialTheme.shapes.small,
                                )
                                .clickable(enabled = !busy) { versionText = entry.id }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
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
                }
            }
        }

        // 失败原因留在这里（顶部摘要卡已经说了「卡在哪个步骤」，这里给完整原文与重试入口）。
        // 用 failureText 而不是 message：message 可能已经被 hint 换成「条件没满足」那种话，
        // 那句已经在按钮下面显示过了，这里再冒一条红色原文会让人以为安装真的失败了一次。
        // 失败提示淡入：它在按钮正下方凭空长出来一行红字，硬切会像是布局跳了一下。
        // 退出动画那几帧 failureText 已经是 null，字还得留着，所以自己记一份上一次的原文。
        var lastFailure by remember { mutableStateOf("") }
        LaunchedEffect(failureText) { if (failureText != null) lastFailure = failureText }
        AnimatedVisibility(
            visible = failureText != null,
            enter = fadeIn(tween(motionMillis(Motion.BASE))),
            exit = fadeOut(tween(motionMillis(Motion.FAST))),
        ) {
            DownloadHint(lastFailure, error = true)
        }

        // 失败后给一个明确的下一动作：直接重来，已下好并通过 sha1 的文件会跳过。
        // 用户不用自己判断「再点一次一键安装是不是会从头下」。
        if (phase is InstallPhase.Failed && !busy) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = { startInstall() }) { Text("重试这一步") }
                Text(
                    "已下好的文件会跳过，不会从头再来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 分步进度：每一步一行，卡在哪一步一眼能看出来。
        // 进度条与百分比不在这里重复了 —— 顶部的摘要卡已经画了一份，两处在同一屏里
        // 显示同一个百分比只会让人怀疑哪个才是真的。
        if (currentStep != InstallStep.IDLE) {
            StepList(currentStep = currentStep, states = stepStates)
        }

        // 装完的那一屏只有顶部那张卡 —— 它的收尾里已经带了「去启动」。
        // 这里原来还有一张「实例已就绪」卡，与顶部那张讲同一件事，
        // 而且它排在整页最下面，窗口小的时候根本看不见（docs/62 第 3、4 条）。
        Spacer(Modifier.weight(1f))
    }
}

/**
 * 安装状态的醒目摘要，紧跟在「一键安装」那几行下面（2026-11-16 重做）。
 *
 * 为什么要做得这么重：原来这里只有一行灰色小字，用户点完安装视线还停在按钮上，
 * 一行小字根本不构成「有反应了」的证据；而带进度条、带百分比的卡片一变出来，
 * 不用读字就知道在下载。详细的分步列表仍在页面下方，这里只回答三个问题：
 * 在干什么、做到几成、还要多久。
 */
@Composable
private fun InstallStatusBanner(
    phase: InstallPhase,
    currentStep: InstallStep,
    progress: StepProgress?,
    speedBytesPerSecond: Long,
    elapsedMs: Long,
    message: String = "",
    onStart: (Instance) -> Unit = {},
) {
    if (phase is InstallPhase.Idle) return
    val finished = phase as? InstallPhase.Finished
    // 装完的那一屏分两段：先是进度条走满 100%（连同目录、Java、去启动一起给出来），
    // 停够 5 秒再把进度条收掉，卡片留成一行摘要。
    // 一直停在画面上会让人以为还在装；直接消失又等于「完成」这件事没有证据。
    var settled by remember(phase) { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (finished == null) return@LaunchedEffect
        delay(COMPLETION_HOLD_MS)
        settled = true
    }

    val (text, isError) = when (phase) {
        is InstallPhase.Running -> {
            val detail = if (progress != null && progress.detail.isNotBlank()) {
                " · " + formatProgressDetail(progress)
            } else {
                ""
            }
            val speed = if (progress?.step == InstallStep.DOWNLOADING && speedBytesPerSecond > 0L) {
                " · " + formatSpeed(speedBytesPerSecond) + "/s"
            } else {
                ""
            }
            ("正在" + currentStep.label + detail + speed + " · 已用 " + formatDuration(elapsedMs)) to false
        }
        is InstallPhase.Failed -> ("卡在「" + phase.step.label + "」：" + phase.message) to true
        is InstallPhase.Finished -> {
            // 摘要那句话（describeOutcome）本来就写在状态里，装完直接拿它当正文，
            // 不再在页面下方另起一行灰字讲同一件事。
            (message.ifBlank { "安装完成：" + phase.outcome.instance.name + " 已就绪" }) to false
        }
        InstallPhase.Idle -> return
    }
    // 总量未知（例如跑 Forge 安装器：只知道已经多少行、不知道还要几行）时
    // fraction 为 null，下面画一条来回跑的不确定进度条，总比一片静止强。
    val fraction = if (phase is InstallPhase.Running) {
        if (progress != null && progress.total > 0L && progress.totalReliable) progress.fraction else null
    } else {
        null
    }
    val doneBar = finished != null && !settled
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(tween(motionMillis(Motion.SLOW)))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val percent = when {
                fraction != null -> (fraction * 100f).toInt().toString() + "%"
                doneBar -> "100%"
                else -> null
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    when {
                        isError -> "安装卡住了"
                        finished != null -> "装好了"
                        else -> "正在下载安装"
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
                if (percent != null) {
                    Text(
                        percent,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                // 收成一行之后仍然要有一个入口 —— 装完这一屏的意义就是「去启动」。
                if (finished != null && settled) {
                    Button(onClick = { onStart(finished.outcome.instance) }) { Text("去启动") }
                }
            }
            when {
                // 装完先走满 100%，停 5 秒再收掉：停一下是让「满了」这件事被看见。
                doneBar -> ProgressBar(1f)
                fraction != null -> ProgressBar(fraction)
                !isError && finished == null -> IndeterminateBar()
                else -> Unit
            }
            Text(
                text,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
            // 装完的那 5 秒里把「装到哪、用哪个 Java」一并给出来；
            // 收起后这些还在实例详情里，不必一直占着这一屏。
            if (finished != null && !settled) {
                Text(
                    "游戏目录 " + File(finished.outcome.instance.directory).parentFile?.parentFile?.path.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Java：" + finished.outcome.java.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Button(onClick = { onStart(finished.outcome.instance) }) { Text("去启动") }
            }
        }
    }
}
/**
 * 「步骤名 —— 状态」的一行；失败时把原因原样显示出来。
 *
 * 只列真跑过的步骤 + 当前步骤：只装原版时不该冒出「安装 Fabric 加载器」这一格，
 * 本机已有 Java 时也不该冒出一格永远是「·」的「下载 Java 运行时」。
 */
@Composable
private fun StepList(currentStep: InstallStep, states: Map<InstallStep, StepState>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        VISIBLE_STEPS.filter { states.containsKey(it) || it == currentStep }.forEach { step ->
            val state = states[step] ?: StepState.Pending
            val (mark, color) = when (state) {
                is StepState.Pending -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
                is StepState.Running -> "…" to MaterialTheme.colorScheme.primary
                is StepState.Succeeded -> "✓" to MaterialTheme.colorScheme.primary
                is StepState.Failed -> "✗" to MaterialTheme.colorScheme.error
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    mark + " " + step.label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (step == currentStep) FontWeight.SemiBold else FontWeight.Normal,
                    color = color,
                )
                if (state is StepState.Failed) {
                    Text(
                        state.message,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun formatProgressDetail(step: StepProgress): String = when (step.step) {
    InstallStep.DOWNLOADING -> if (step.totalReliable) {
        step.detail + " · " + formatBytes(step.done) + " / " + formatBytes(step.total)
    } else {
        // 有文件体积未知：不报总量，免得用户算出一个到不了的百分比
        step.detail + " · 已下 " + formatBytes(step.done)
    }
    InstallStep.JAVA_INSTALL -> "Java 运行时下载 " + (step.fraction * 100f).toInt() + "%"
    else -> step.detail
}

/**
 * 总量未知时的「不确定进度条」：一段高光在底槽里匀速来回跑。
 *
 * 用途是跑 Forge / NeoForge 安装器那种阶段——它是外部 java 子进程，只吐日志不报
 * 总进度，只能证明「还在动」。没有这条，界面几十分钟一动不动，用户以为卡死了。
 */
@Composable
private fun IndeterminateBar() {
    val transition = rememberInfiniteTransition(label = "indeterminate")
    val position by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "indeterminatePosition",
    )
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
    ) {
        // 高光宽 35%，起点在 [0, 65%] 之间来回 —— 用实测宽度算，别写死 dp。
        val highlight = maxWidth * 0.35f
        val travel = maxWidth - highlight
        Box(
            Modifier
                .width(highlight)
                .fillMaxHeight()
                .offset(x = travel * position)
                .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val value = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
    ) {
        if (value > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(value)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
            )
        }
    }
}

@Composable
private fun DownloadHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}


