package io.github.huang404yan.platypus.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.Endpoints
import io.github.huang404yan.platypus.core.download.DownloadPreference
import io.github.huang404yan.platypus.core.download.DownloadSource
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.launch.RunningGames
import io.github.huang404yan.platypus.core.packs.PackKind
import io.github.huang404yan.platypus.core.theme.LauncherTheme
import io.github.huang404yan.platypus.core.theme.launcherThemeOf
import java.io.File

/** 桌面默认的 .minecraft 位置：优先用 %APPDATA%\\.minecraft。 */
fun defaultGameRoot(): String {
    val appData = System.getenv("APPDATA") ?: return ""
    val candidate = File(appData, ".minecraft")
    return if (candidate.isDirectory) candidate.absolutePath else ""
}

@Composable
fun App(
    initialGameRoot: String? = null,
    backend: LauncherBackend? = null,
    // 主题在 Run 时才定下来（要先读设置），而标题栏是宿主窗口画的、这里碰不到，
    // 所以把「最终选了哪套」回调出去，由 app-desktop 去改标题栏。
    onThemeChanged: (LauncherTheme) -> Unit = {},
    /**
     * 宿主每收到一次「关闭窗口」就把它加一。
     *
     * 为什么不让宿主自己判断能不能关：有没有游戏在跑，只有这一层知道（见 [RunningGames]）。
     * 宿主只管把请求递进来，关不关由这里决定 —— 有游戏在跑就先问一句。
     * 这个形状对安卓端也成立：安卓有返回键，同样要把「按了返回但游戏还在跑」这件事问清楚。
     */
    closeSignal: Int = 0,
    /** 确认可以关的时候回调宿主，由它去真正结束进程。 */
    onCloseConfirmed: () -> Unit = {},
    /**
     * 把窗口收起来（不结束进程）的办法，宿主能提供时才给。
     *
     * 传 null 表示这台机器没有托盘：关窗口只能是真的退出，对话框里也就不该出现
     * 「收进托盘」这种点了没用的选项。参数是当时还在跑的游戏数，宿主用它决定气泡里说什么。
     */
    onHideToTray: ((runningGameCount: Int) -> Unit)? = null,
    /**
     * 把一条完成提示也送到系统通知区，宿主能提供时才给。
     *
     * 为什么由宿主来做：窗口藏进托盘之后界面上那张小卡没人看得见，而界面上那张卡又
     * 必须留着（窗口还开着的时候看这里最快）。两边各说一遍，由这一层决定什么时候说。
     * 传 null 表示没有托盘，界面上那张卡是唯一的出口。
     */
    onSystemNotice: ((title: String, body: String) -> Unit)? = null,
) {
    // 配色提到 MaterialTheme 外面：整套 ColorScheme 由它决定，改它等于换一层皮，
    // 页面代码一行都不用知道 —— 全仓库的颜色都走 MaterialTheme.colorScheme。
    var theme by remember { mutableStateOf(LauncherTheme.AUTO) }

    // 界面模式（完整 / 简洁）。与 theme 一样是最外层持有的：它决定左列分几组、
    // 各页露哪些块，改它要整棵树重画。初值取 UiMode.DEFAULT（简洁）：
    // 设置还没读回来之前就按简版画，读回来若是完整再一次性展开，不闪。
    var uiMode by remember { mutableStateOf(UiMode.DEFAULT) }

    // 「少动一点」。true 时所有动画压到 1ms（见 Motion.kt）。它不像 theme 那样要
    // 通知宿主窗口，只在这一棵树里生效。
    var reduceMotion by remember { mutableStateOf(false) }

    // 只在最外层读一次设置。放这里而不是每页各读一次：切页会重建页面，
    // 每页各读会让主题在切换瞬间闪一下，而且同一份文件被反复解析。
    LaunchedEffect(backend) {
        val repository = backend?.settings ?: return@LaunchedEffect
        val settings = runCatching { repository.load() }.getOrNull() ?: return@LaunchedEffect
        theme = runCatching { launcherThemeOf(settings.theme) }.getOrDefault(LauncherTheme.AUTO)
        // 没存过（空串）就是默认档（简洁），见 UiMode.of 与 UiMode.DEFAULT。
        uiMode = UiMode.of(settings.uiMode)
        reduceMotion = settings.reduceMotion
        // 下载源偏好是进程级全局量（见 DownloadPreference 的说明）：这里灌一次，
        // 之后所有新建的下载器都按它排序。设置页改的时候会就地覆写。
        DownloadPreference.source = DownloadSource.of(settings.downloadSource)
        // 同时下几个文件同理：进程级全局量，开程序灌一次，设置页改完就地覆写。
        DownloadPreference.connections = DownloadPreference.normalizeConnections(settings.downloadConnections)
        // 空壳包的镜像根同理：所有地址都从 Endpoints.serverBase 拼，开程序时灌一次，
        // 设置页改完就地覆写。完整版这个字段恒为空串，setServerBase 也只会存空。
        Endpoints.setServerBase(settings.serverBase)
        // 隔离的默认值也是进程级全局量（见 InstanceLayouts.effectiveIsolated 的说明）：
        // 三十多个地方要用它，判断收在 core 的那一个函数里，这里只负责在开程序时灌一次。
        // 设置页改完会就地覆写，不用等下次开程序。
        InstanceLayouts.defaultIsolated = settings.defaultIsolation
    }

    LaunchedEffect(theme) { onThemeChanged(theme) }

    PlatypusTheme(theme) {
        // 动效开关喂给整棵树：各处动画统一从 motionMillis / motionTween 取时长，
        // 这里一关，它们全部变成瞬间到位，不用逐个页面加判断。
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        // 默认落在「启动」：开启动器的第一件事是「今天玩哪个版本、用谁的身份」。
        var page by remember { mutableStateOf(Page.LAUNCH) }

        // 启动页点「版本选择 / 版本设置」时，只交一个 key，不把整个对象长期挂在这里。
        var focusKey by remember { mutableStateOf<String?>(null) }

        // 联机页点「直接进」时把地址交给启动页。存在这一层而不是启动页里：
        // 切页会把启动页整个销毁，地址跟着丢，回去就变成停在主菜单。
        var quickPlay by remember { mutableStateOf("") }

        // 每页选中了哪个二级项。存在这里而不是页面里：页面切走就被销毁了，
        // 记在页面里的话，去下载页看一眼再回来，设置页会跳回第一个 tab。
        val subSelection = remember { mutableStateMapOf<String, String>() }
        fun subTabOf(target: Page): String =
            subSelection[target.name] ?: defaultSubTab(target)

        // 左列「刷新」按钮的信号：每次点加一，页面拿它当 LaunchedEffect 的 key。
        // 只传一个计数而不是传回调进左列，是因为刷新要做的事在页面里
        // （要读哪些状态、是重扫磁盘还是重拉网络列表），左列既看不见也不该知道。
        var refreshTick by remember { mutableStateOf(0) }

        // 副页面（压在主界面上的，带返回键）。存对象引用而不是只存 key：
        // 版本设置要的就是「当时点的那个实例」，重扫一次磁盘反而可能把它扫没了。
        var overlay by remember { mutableStateOf<Overlay?>(null) }
        var overlayInstance by remember { mutableStateOf<Instance?>(null) }

        // 副页面关掉之后，底下那一页要重扫一次。版本选择页里新登记或刚下载的实例只写进
        // instances.json，启动页手里那份 registered 还停在打开副页面之前 —— 不通知它，
        // 就会出现「明明登记成功了，主页的版本卡片里没有」。
        var overlayTick by remember { mutableStateOf(0) }

        // 「哪个版本在跑」是进程的事实，不能记在启动页里：切一次页，页面整个被销毁重建，
        // 再回到启动页就会以为什么都没在跑。表放在这一层，页面只负责往里登记与读。
        // tick 是因为 RunningGames 故意不是 Compose 快照状态（core 模块不认识 Compose），
        // 它变了只会调用我们给的 onChange，这里把 tick 加一，界面才会重画。
        var runningTick by remember { mutableStateOf(0) }
        val runningGames = remember { RunningGames { runningTick++ } }

        // 首次运行那扇门走完时加一。模式选择排在它后面：正式版那扇门没正版账号是关不掉的，
        // 摆在它前面等于给「点两下绕过登录」开了条路。
        var firstRunTick by remember { mutableStateOf(0) }
        fun closeOverlay() {
            overlay = null
            overlayTick++
        }

        fun openSetup(instance: Instance) {
            overlayInstance = instance
            overlay = Overlay.InstanceSetup(instanceKey(instance))
        }

        LaunchedEffect(Unit) { println("[ui] initial page=" + page) }

        // 顶栏那颗胶囊读的是数量，跟着 tick 重算：RunningGames 不是快照状态。
        val runningCount = remember(runningTick) { runningGames.size }

        // 装完 / 导入完的提示。任务活在页面之外（切页不打断），
        // 完成那一刻用户多半不在那一页，所以提示必须画在这一层。
        val notices = Notices.items.collectAsState().value

        // 新提示出现时也往系统通知区送一份。要不要真的弹，由宿主按「窗口现在看不看得见」
        // 决定 —— 可见性只有宿主知道，这一层不假装知道。窗口在面前时界面上那张卡
        // 已经说完了，宿主那边会跳过，不会双重打扰。
        // key 用最后一条的 id 而不是 size：一条消失的同时进来一条，size 没变但内容变了。
        val latestNoticeId = notices.lastOrNull()?.id ?: 0L
        LaunchedEffect(latestNoticeId) {
            val mirror = onSystemNotice ?: return@LaunchedEffect
            val latest = notices.lastOrNull() ?: return@LaunchedEffect
            mirror(latest.title, latest.body)
        }

        val closeScope = rememberCoroutineScope()

        // 「去某个页面」只有一份写法：顶栏胶囊、通知卡都走它。
        // 两处各自抄一遍的后果是将来加页面时只改到一处，点下去看着有反应但不跳。
        // 这里刻意不套 remember：lambda 读的是 page，套了 remember 就会永远读到第一次
        // 组合时那个值（stale capture），表现为「点通知没反应」。重建 lambda 的代价可以忽略。
        val goToPage: (Page) -> Unit = { target ->
            if (page != target) subSelection.remove(target.name)
            page = target
            closeOverlay()
        }

        // 键盘入口：Ctrl+1..5 去五个一级页，Esc 退出副页面。
        // 副页面盖着时不收数字键 —— 用户正对着「版本设置」做改动，底下的页面被换掉
        // 只会让人以为点错了。
        KeyboardShortcutHook(
            overlayOpen = overlay != null,
            enabled = overlay == null,
        ) { intent ->
            when (intent) {
                is LauncherIntent.GoPage -> goToPage(intent.page)
                LauncherIntent.CloseTop -> closeOverlay()
            }
        }

        // 宿主说要关窗，但还有版本在跑：先让用户看见有哪几个，并给两条出路。
        // 不直接杀进程 —— 用户点右上角那个 × 的意图可能只是「先把启动器收起来」，
        // 拿它当「结束游戏」会使存档停在半截。
        var closeAsk by remember { mutableStateOf(false) }
        LaunchedEffect(closeSignal) {
            if (closeSignal == 0) return@LaunchedEffect
            when {
                // 有版本在跑：先问一句，别让存档停在半截。
                runningGames.size > 0 -> closeAsk = true
                // 没游戏在跑但有托盘：点 × 就是收起来，不是退出。
                // 这是托盘程序的通行做法（PCL / HMCL / 绝大多数常驻程序都这样）：
                // 通知区还有图标，用户随时能叫回来；真要走托盘右键的「退出」。
                // 反过来做（点 × 直接杀进程、托盘图标跟着消失）会让「最小化到托盘」
                // 这个能力实际上永远用不上 —— 用户根本没有别的办法把窗口收起来。
                onHideToTray != null -> onHideToTray(0)
                else -> onCloseConfirmed()
            }
        }

        Column(Modifier.fillMaxSize()) {
            TopBar(
                current = page,
                runningCount = runningCount,
                // 回到启动页并关掉可能正盖着的副页面：只在顶栏点一下却还停在
                // 「版本设置」的覆盖层上，看起来就是「点了没反应」。
                onOpenRunning = { goToPage(Page.LAUNCH) },
                onSelect = { target ->
                    // 点的是另一个一级项才重置它自己的二级项：从「下载 / 光影包」切到「设置」，
                    // 再点「下载」回来，应该回到「原版游戏」；而点当前这个一级项本身
                    // （常见于想回内容区顶部）不该把二级项踢回默认。
                    if (target != page) subSelection.remove(target.name)
                    page = target
                    closeOverlay()
                },
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // 左列二级：只有「下载 / 设置 / 更多」有，启动与联机没有 —— 那两页的东西全在一屏里。
                // 简洁模式把不常用的二级项挪到「更多」那一组下面。分组只在左列生效，
                // 选中态与刷新按钮仍按 sub.id 走，与完整模式完全一样。
                val groups = UiPolicy.subTabGroups(page, uiMode)
                if (groups.any { it.tabs.isNotEmpty() }) {
                    SubColumn(
                        groups = groups,
                        currentSub = subTabOf(page),
                        onSelectSub = { sub -> subSelection[page.name] = sub },
                        onRefreshSub = { sub -> if (sub == subTabOf(page)) refreshTick++ },
                    )
                }
                // 留白与小窗口有关：1280 宽时 24dp 只是呼吸感，1024 宽时它和左列一起
                // 把表单挤得只剩一半。窄窗口收到 16dp，给内容让出位置。
                val compact = LocalWindowInfo.current.containerSize.width < 1100
                Box(
                    Modifier.weight(1f).fillMaxHeight().padding(if (compact) 16.dp else 24.dp),
                ) {
                    // 换一级页时淡入 + 一点点横向位移，代替原来的硬切。
                    //
                    // 两个细节：
                    //  1. 内容按 targetState（shown）渲染，不读外层 page —— 读外层的话，
                    //     过渡期间两帧都在画「新页」，看起来就是闪了一下而不是换过去。
                    //  2. 位移只有宽度的 1/24（1280 宽时约 53px ≈ 40dp 里再靠内），
                    //     方向按一级项顺序。取太大就成了「滑屏」，用户会等它滑完。
                    val pageMillis = motionMillis(Motion.BASE)
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            val forward = targetState.ordinal >= initialState.ordinal
                            val shift = if (forward) 1 else -1
                            (
                                fadeIn(animationSpec = tween(pageMillis)) +
                                    slideInHorizontally(animationSpec = tween(pageMillis)) { full ->
                                        shift * full / 24
                                    }
                                ).togetherWith(
                                fadeOut(animationSpec = tween(pageMillis)) +
                                    slideOutHorizontally(animationSpec = tween(pageMillis)) { full ->
                                        -shift * full / 24
                                    },
                            )
                        },
                        label = "page",
                    ) { shown ->
                    val sub = subTabOf(shown)
                    // tick 只在「下载」页与「设置」页有意义（两处都有需要重读的清单）；
                    // 别的页面忽略它，免得切页时误触发。
                    val tick = if (shown == Page.DOWNLOAD || shown == Page.SETTINGS) refreshTick else 0
                    // 启动页另有一路信号：副页面关掉（overlayTick）时重扫登记表。
                    // 只在那时加一，不跟左列「刷新」按钮混用 —— 那个是给下载页重拉网络列表的。
                    when (shown) {
                        Page.LAUNCH -> LaunchPage(
                            backend = backend,
                            initialGameRoot = initialGameRoot,
                            focusKey = focusKey,
                            quickPlayServer = quickPlay,
                            onOpenSelect = { overlay = Overlay.InstanceSelect },
                            onOpenSetup = { instance -> openSetup(instance) },
                            onOpenAccountPage = {
                                // 只切一级页的话用户会落在「设置 → 启动」上，找不到登录按钮；
                                // 顺手把二级项也定成「账号」（帮助页那儿踩过一次同样的坑）。
                                subSelection[Page.SETTINGS.name] = SETTINGS_TAB_ACCOUNT
                                page = Page.SETTINGS
                            },
                            refreshTick = overlayTick,
                            uiMode = uiMode,
                            runningGames = runningGames,
                            runningTick = runningTick,
                        )
                        Page.DOWNLOAD -> SubContent(sub) { shownSub -> when (shownSub) {
                            DOWNLOAD_TAB_MODS -> ModsPage(
                                initialGameRoot = initialGameRoot,
                                backend = backend,
                                refreshTick = tick,
                                // 「装的是什么」和「这个版本装了什么」是同一件事的两头：
                                // 从详情页跳过来时带上那个实例，用户不用再选一遍。
                                focusInstanceKey = focusKey.orEmpty(),
                            )
                            // 整合包页没有可刷新的清单（见 Navigation.kt 里那一项的说明），不传 tick
                            DOWNLOAD_TAB_MODPACK -> ModpackPage(initialGameRoot, backend) { page = Page.LAUNCH }
                            DOWNLOAD_TAB_DATAPACKS -> PacksPage(
                                initialGameRoot = initialGameRoot,
                                backend = backend,
                                kind = PackKind.DATAPACK,
                                refreshTick = tick,
                                focusInstanceKey = focusKey.orEmpty(),
                            )
                            DOWNLOAD_TAB_RESOURCEPACKS -> PacksPage(
                                initialGameRoot = initialGameRoot,
                                backend = backend,
                                kind = PackKind.RESOURCE_PACK,
                                refreshTick = tick,
                                focusInstanceKey = focusKey.orEmpty(),
                            )
                            DOWNLOAD_TAB_SHADERS -> PacksPage(
                                initialGameRoot = initialGameRoot,
                                backend = backend,
                                kind = PackKind.SHADER,
                                refreshTick = tick,
                                // 详情页「去下载光影」跳过来时带上那个实例
                                focusInstanceKey = focusKey.orEmpty(),
                            )
                            // 服务端不登记实例，所以没有「装完去启动」的回调
                            DOWNLOAD_TAB_SERVER -> ServerPage(initialGameRoot, backend)
                            else -> DownloadsPage(initialGameRoot, backend, tick) { page = Page.LAUNCH }
                        } }
                        Page.LINK -> LinkPage(initialGameRoot = initialGameRoot, backend = backend) { address ->
                            quickPlay = address
                            page = Page.LAUNCH
                        }
                        // 设置页的「启动」二级项要刷新信号（重读本机 Java 清单），其它二级项不用
                        Page.SETTINGS -> SubContent(sub) { shownSub -> SettingsPage(
                            backend = backend,
                            subTab = shownSub,
                            refreshTick = tick,
                            theme = theme,
                            uiMode = uiMode,
                            // 换模式与换配色同一套：点一下立刻重画，同时由设置页自己写盘。
                            onUiModeChange = { uiMode = it },
                            reduceMotion = reduceMotion,
                            onReduceMotionChange = { reduceMotion = it },
                            // 只改这一层的皮。标题栏由 LaunchedEffect(theme) 回调出去，
                            // 那边会等内容帧提交之后再重画（见 Main.kt 的两个 withFrameNanos），
                            // 所以这里不能提前同步调用，否则标题栏又会抢在内容前面变。
                            onThemeChange = { theme = it },
                        ) }
                        Page.MORE -> SubContent(sub) { shownSub -> when (shownSub) {
                            MORE_TAB_ABOUT -> AboutPage()
                            MORE_TAB_DIAG -> DiagnosticPage(backend = backend, initialGameRoot = initialGameRoot)
                            MORE_TAB_TOOLBOX -> ToolboxPage(backend = backend, initialGameRoot = initialGameRoot)
                            else -> HelpPage { target ->
                                // 「去这里」要既切页又选中那一页的二级项：只切页的话，
                                // 用户会落在默认的二级项上，还是找不到说明里说的那个入口。
                                when (target) {
                                    is NavTarget.Tab -> {
                                        if (target.sub != null) {
                                            subSelection[target.page.name] = target.sub
                                        }
                                        page = target.page
                                    }
                                    NavTarget.InstanceSelect -> {
                                        page = Page.LAUNCH
                                        overlay = Overlay.InstanceSelect
                                    }
                                }
                            }
                        } }
                    }
                    }
                }
            }
        }

        // 副页面盖在最上面。不放 when(page) 里：副页面是「从某个页面出发的」，
        // 它自己的返回键负责把它摘掉，底下那一页还留着，回来时状态都在。
        //
        // 必须自己铺一层不透明的底：Compose 的 Box / Surface 会把子项**叠**在一起，
        // 不铺底的话底下那一页的字会从副页面后面透出来 —— preview.17 实测就是这样，
        // 「版本选择」四个字和顶栏的品牌名糊成了一团。
        val currentOverlay = overlay
        // 退出动画那几帧里 overlay 已经被置空，直接读它会让内容先消失、只剩一层空底色
        // 往右滑出去。记住最近一次非空的那个，动画期间继续画它。用 LaunchedEffect 而不是
        // 直接在组合里赋值：组合期写 state 会触发额外一轮重组。
        var lastOverlay by remember { mutableStateOf<Overlay?>(null) }
        LaunchedEffect(currentOverlay) {
            if (currentOverlay != null) lastOverlay = currentOverlay
        }
        val shownOverlay = currentOverlay ?: lastOverlay
        // 副页面是整屏换的，位移取宽度的 1/8（1280 宽约 160px），比一级页那 1/24 明显一档，
        // 但又不到「滑屏」的地步。时长用 SLOW：它比换页「重」一点，慢一点才像被推进来的。
        val overlayMillis = motionMillis(Motion.SLOW)
        AnimatedVisibility(
            visible = currentOverlay != null,
            enter = fadeIn(tween(overlayMillis)) +
                slideInHorizontally(tween(overlayMillis)) { full -> full / 8 },
            exit = fadeOut(tween(overlayMillis)) +
                slideOutHorizontally(tween(overlayMillis)) { full -> full / 8 },
        ) {
            Surface(
                Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                // 副页面以前是「铺满整块、文字从窗口左边缘起」，和主内容区那 24dp 留白
                // 完全不是一个观感 —— 用户的原话是「离左侧有些近」。这里补上同一档留白，
                // 窄窗口同样收到 16dp，免得表单被挤成一条。
                val compactOverlay = LocalWindowInfo.current.containerSize.width < 1100
                Box(
                    Modifier.fillMaxSize()
                        .padding(if (compactOverlay) 16.dp else 24.dp),
                ) {
                    when (shownOverlay) {
                        null -> Unit
                        Overlay.InstanceSelect -> InstancePage(
                            backend = backend,
                            initialGameRoot = initialGameRoot,
                            onBack = { closeOverlay() },
                            onLaunch = { instance ->
                                focusKey = instanceKey(instance)
                                page = Page.LAUNCH
                                closeOverlay()
                            },
                            onOpenSetup = { instance -> openSetup(instance) },
                        )
                        is Overlay.InstanceSetup -> {
                            val instance = overlayInstance
                            if (instance == null) {
                                closeOverlay()
                            } else {
                                InstanceDetailPanel(
                                    instance = instance,
                                    backend = backend,
                                    onBack = { closeOverlay() },
                                    onNotice = { println("[setup] " + it) },
                                    // 跳下载页：先记下二级项再切一级页，同时把覆盖层关掉；
                                    // 不关的话覆盖层还盖在上面，用户会以为按钮没反应。
                                    onOpenDownload = { sub ->
                                        // 顺手把这个实例记成启动页的焦点：下载页据此
                                        // 自动挑中「刚才在详情里看的那个」，而不是列表第一个。
                                        overlayInstance?.let { focusKey = instanceKey(it) }
                                        subSelection[Page.DOWNLOAD.name] = sub
                                        page = Page.DOWNLOAD
                                        closeOverlay()
                                    },
                                    // 删完实例：关掉覆盖层并让底下那页重扫（closeOverlay 里会 overlayTick++），
                                    // 否则列表上还留着一条指向已经不存在的目录。
                                    onDeleted = {
                                        println("[setup] 实例已删除")
                                        closeOverlay()
                                    },
                                    uiMode = uiMode,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 首次启动挡在最前面。放在主体之后而不是之前：它是覆盖式的弹窗，
        // 底下的页面照常创建，用户在弹窗里犹豫时背景不是一片空白。
        // 「去登录」只切一级页不够 —— 不选二级项的话用户会落在「设置 → 启动」上，
        // 找不到填应用 ID 的那张卡（这个问题在帮助页那儿已经踩过一次）。
        FirstRunGate(
            backend = backend,
            onGoAccount = {
                subSelection[Page.SETTINGS.name] = SETTINGS_TAB_ACCOUNT
                page = Page.SETTINGS
            },
            onFinished = { firstRunTick++ },
        )

        // 只在第一次进来时问一句「看得全一点还是少一点」。选过就不再问，
        // 想换了去设置 → 界面。与上面那扇门一样是覆盖式弹窗，底下的页面照常建好。
        UiModeChooserGate(
            backend = backend,
            tick = firstRunTick,
            onPicked = { uiMode = it },
        )

        // 还有版本在跑时的关窗询问。三条路各说各的后果，不替用户选：
        // 「收进托盘」是把窗口藏起来、进程与游戏都留着（托盘里能再叫回来）；
        // 「结束游戏并退出」走的是游戏自己的 stop（会先发退出命令，给存档一点时间）。
        // 没有托盘（onHideToTray 为 null）时退回老的「只关启动器 + 说清后果」。
        if (closeAsk) {
            val busyEntries = remember(runningTick, closeAsk) { runningGames.all() }
            AlertDialog(
                onDismissRequest = { closeAsk = false },
                title = { Text(if (busyEntries.size == 1) "还有一个游戏在跑" else "还有 " + busyEntries.size + " 个游戏在跑") },
                text = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        busyEntries.forEach { entry ->
                            Text(
                                entry.name.ifBlank { entry.gameVersion },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            if (onHideToTray != null) {
                                "选「收进托盘」，游戏照常在后台跑，双击通知区的图标就能把启动器叫回来。" +
                                    "想让它们正常退出，选「结束游戏并退出」——那条会让游戏自己走一遍存盘再关。"
                            } else {
                                "选「只关启动器」，游戏会在后台继续跑，但启动器退出后就不再记得它们 ——" +
                                    "下次打开看不到这几条记录，要停只能在任务管理器里找 java。" +
                                    "想让它们正常退出，选「结束游戏并退出」：那条会让游戏自己走一遍存盘再关。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { closeAsk = false }) { Text("先不关") }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val hide = onHideToTray
                        TextButton(onClick = {
                            closeAsk = false
                            // 有托盘就收起来（进程留着，游戏继续），没有就只能是退出。
                            if (hide != null) hide(busyEntries.size) else onCloseConfirmed()
                        }) { Text(if (hide != null) "收进托盘" else "只关启动器") }
                        TextButton(
                            onClick = {
                                closeAsk = false
                                closeScope.launch {
                                    busyEntries.forEach { entry ->
                                        runCatching { entry.handle.stop() }
                                        runningGames.remove(entry.key)
                                    }
                                    onCloseConfirmed()
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("结束游戏并退出") }
                    }
                },
            )
        }

        // 完成提示画在最上层：页面的覆盖层与弹窗都可能盖住内容区，但谁都盖不住这里。
        // 没提示时整块不组合，不占位置也不吃点击。
        if (notices.isNotEmpty()) {
            Box(Modifier.fillMaxSize()) {
                NoticeStack(notices) { notice ->
                    Notices.dismiss(notice.id)
                    notice.goto?.let(goToPage)
                }
            }
        }
        }
    }
}

/**
 * 顶部一排五个一级项（照 PCL 的 FormMain.xaml:123-131 那一排 MyRadioButton）。
 *
 * 两处按用户口径改过（preview.19）：
 *  1. 五项**放大并均分**整条宽度。原来是靠左挤着排的一串小字，五个项加起来还没顶栏一半宽，
 *     右边一大片空着 —— 均分之后每一项都是一个大按钮，手指（触屏）与鼠标都好点。
 *  2. 左右两端**可折叠**，收起后各留一个代表标志。品牌名与版本号回答的是
 *     「这是哪个启动器」，看一次就够了；折起来把宽度让给中间那排五分。
 *
 * 为什么顶栏而不是侧栏：横排五项在 16:9 的窗口里只占一行，横着排一眼全看见；
 * 竖排侧栏要吃掉 180~220dp 的宽度，还把「同一件事的入口」摊成好几个一级项。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TopBar(
    current: Page,
    onSelect: (Page) -> Unit,
    /** 这一轮里还有几个版本在跑。0 就不画提示。 */
    runningCount: Int = 0,
    /** 点那个提示时去哪儿。默认切到启动页。 */
    onOpenRunning: () -> Unit = {},
) {
    val compact = LocalWindowInfo.current.containerSize.width < 1100
    // 两端默认展开：第一次打开要让人知道自己用的是哪个启动器（孵化版）。
    var brandOpen by remember { mutableStateOf(true) }
    var versionOpen by remember { mutableStateOf(true) }
    // 一级项的文字可以藏起来，只留图标（用户 2026-11-16 口径）。
    // 藏起来不是为了省那点宽度，是为了让五个图标自己站成一排 —— 用熟了以后认形状比读字快。
    var showLabels by remember { mutableStateOf(true) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 54.dp else 60.dp)
                .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左端：标志 + 名字。点一下收起名字，只剩标志。
            Row(
                Modifier
                    .clickable { brandOpen = !brandOpen }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(if (compact) 24.dp else 28.dp)
                if (brandOpen) {
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            if (Brand.isPreview) Brand.PREVIEW_NAME_ZH else Brand.NAME_ZH,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        if (!compact) {
                            Text(
                                if (Brand.isPreview) Brand.PREVIEW_NAME_EN else Brand.NAME_EN,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
            // 中间：五项权重均分，把剩下的宽度全吃掉。
            Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Page.entries.forEach { item ->
                    // 悬停给一句「这项叫什么 + 快捷键」：文字藏起来时只看图标认不全，
                    // 而快捷键不写出来，没人会知道它存在。
                    TooltipArea(
                        tooltip = {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.inverseSurface,
                                tonalElevation = 0.dp,
                            ) {
                                Text(
                                    item.label + "  ·  " + pageShortcut(item),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                )
                            }
                        },
                        delayMillis = 520,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        TopTab(
                            page = item,
                            selected = item == current,
                            showLabel = showLabels,
                            glyphSize = if (compact) 18.dp else 22.dp,
                            modifier = Modifier.fillMaxSize(),
                        ) { onSelect(item) }
                    }
                }
            }
            // 右端：在跑的版本数 + 文字开关 + 版本号。
            //
            // 为什么摆在顶栏而不是只留在启动页：游戏跑起来之后，用户会切去下载页装模组、
            // 去设置里改内存，这时启动页上那些「运行中」标记全看不见了。顶栏是每一页都在的，
            // 一个数字就够说明「还开着」，点一下回到启动页去结束。
            if (runningCount > 0) {
                Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
                RunningPill(
                    count = runningCount,
                    compact = compact,
                    onClick = onOpenRunning,
                )
            }
            // 文字开关放在这里而不是某个一级项里：它改的是「这一排怎么显示」，
            // 不是某一页的内容，塞进任何一项下面都属于找错地方。
            Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
            Box(
                Modifier
                    .clickable { showLabels = !showLabels }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                LabelToggle(
                    size = if (compact) 18.dp else 20.dp,
                    expanded = showLabels,
                    tint = if (showLabels) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Box(
                Modifier
                    .clickable { versionOpen = !versionOpen }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (versionOpen) {
                    Text(
                        "v" + Brand.version,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                } else {
                    VersionDot(18.dp)
                }
            }
        }
    }
}

/**
 * 一个一级项：均分宽度、居中、选中时底色加底部一条高亮。
 *
 * 图标在左、文字在右，照 PCL 的顶栏（FormMain.xaml:123-131 那排 MyRadioButton 每个都带图标）。
 * 文字可以藏起来（showLabel），藏起来之后项还是那个项、位置不变 —— 所以只靠图标也认得出。
 *
 * 底部那条高亮是给「选中」加第二层信号的 —— 只有底色的话，在深色主题里
 * primary 的 12% 透明度和 surfaceVariant 差别很小，一眼看不出来点在哪一项上。
 */
@Composable
private fun TopTab(
    page: Page,
    selected: Boolean,
    showLabel: Boolean,
    glyphSize: Dp,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    // 选中态的颜色跟着走一小段，而不是「啪」地换色：顶栏是鼠标最常经过的地方，
    // 硬切会让人误以为点错了地方又点回来。
    val tint by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motionTween(Motion.FAST),
        label = "tabTint",
    )
    val tabBackground by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = motionTween(Motion.FAST),
        label = "tabBg",
    )
    Box(
        modifier
            .padding(horizontal = 2.dp)
            .background(tabBackground, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (showLabel) 8.dp else 0.dp),
        ) {
            TabGlyph(page = page, size = glyphSize, tint = tint)
            if (showLabel) {
                Text(
                    text = page.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(if (showLabel) 0.46f else 0.34f)
                    .height(2.5.dp)
                    .background(accent, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
            )
        }
    }
}

/**
 * 顶栏右端那颗「N 个在跑」的胶囊。
 *
 * 用主色底 + 主色字，旁边配一个小三角（游戏的形状）而不是文字标签：
 * 顶栏这一排都是中性灰的字，唯一带色的一块自然就是「正在发生的事」。
 * 数量只有 1 时也写「1」，不省成「运行中」—— 多开是这一版的关键能力，
 * 数字要一直在，用户才会意识到「原来还能再开一个」。
 */
@Composable
private fun RunningPill(count: Int, compact: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val background by animateColorAsState(
        targetValue = accent.copy(alpha = 0.14f),
        animationSpec = motionTween(Motion.FAST),
        label = "runningPillBg",
    )
    Row(
        Modifier
            .background(background, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 7.dp else 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(Modifier.size(if (compact) 11.dp else 12.dp)) {
            val w = this.size.width
            val tri = Path().apply {
                moveTo(w * 0.16f, w * 0.06f)
                lineTo(w * 0.94f, w * 0.5f)
                lineTo(w * 0.16f, w * 0.94f)
                close()
            }
            drawPath(tri, accent)
        }
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
    }
}

/**
 * 五个一级项的图标，照 PCL 顶栏那一排：启动是播放三角、下载是落进托盘的下箭头、
 * 联机是 wifi 弧、设置是六边形里一个圆、更多是四宫格。
 *
 * 全部 Canvas 自绘：ui 模块没有图标库依赖，为五个 20dp 的图形拉一整个 material-icons
 * 不划算，而且图标库里的形状和 PCL 那套对不上。
 */
@Composable
private fun TabGlyph(page: Page, size: Dp, tint: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val line = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (page) {
            Page.LAUNCH -> {
                // 播放三角：这一项的动作就是「开游戏」
                val tri = Path().apply {
                    moveTo(w * 0.30f, h * 0.18f)
                    lineTo(w * 0.82f, h * 0.5f)
                    lineTo(w * 0.30f, h * 0.82f)
                    close()
                }
                drawPath(tri, tint)
            }
            Page.DOWNLOAD -> {
                // 下箭头落进托盘
                drawLine(tint, Offset(w * 0.5f, h * 0.12f), Offset(w * 0.5f, h * 0.6f), w * 0.11f, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.28f, h * 0.4f)
                    lineTo(w * 0.5f, h * 0.64f)
                    lineTo(w * 0.72f, h * 0.4f)
                }
                drawPath(head, tint, style = line)
                val tray = Path().apply {
                    moveTo(w * 0.16f, h * 0.7f)
                    lineTo(w * 0.16f, h * 0.88f)
                    lineTo(w * 0.84f, h * 0.88f)
                    lineTo(w * 0.84f, h * 0.7f)
                }
                drawPath(tray, tint, style = line)
            }
            Page.LINK -> {
                // wifi：三段同心弧 + 底部一个点，弧口朝下
                val cx = w * 0.5f
                val cy = h * 0.82f
                listOf(0.46f, 0.30f, 0.15f).forEach { frac ->
                    val radius = w * frac
                    drawArc(
                        color = tint,
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(cx - radius, cy - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = w * 0.1f, cap = StrokeCap.Round),
                    )
                }
                drawCircle(tint, radius = w * 0.075f, center = Offset(cx, cy))
            }
            Page.SETTINGS -> {
                // 六边形外框 + 中心一个圆（PCL 的设置图标就是这个结构）
                val hex = Path()
                val radius = w * 0.44f
                for (i in 0 until 6) {
                    val angle = Math.toRadians((60.0 * i - 90.0))
                    val x = w * 0.5f + (radius * Math.cos(angle)).toFloat()
                    val y = h * 0.5f + (radius * Math.sin(angle)).toFloat()
                    if (i == 0) hex.moveTo(x, y) else hex.lineTo(x, y)
                }
                hex.close()
                drawPath(hex, tint, style = line)
                drawCircle(tint, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f))
            }
            Page.MORE -> {
                // 四宫格：三个圆角方块 + 一个方框，读起来是「还有别的」
                val cell = w * 0.34f
                val gap = w * 0.12f
                val origin = (w - cell * 2f - gap) / 2f
                for (row in 0 until 2) {
                    for (col in 0 until 2) {
                        drawRoundRect(
                            color = tint,
                            topLeft = Offset(origin + col * (cell + gap), origin + row * (cell + gap)),
                            size = Size(cell, cell),
                            cornerRadius = CornerRadius(w * 0.07f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶栏右端那个「显示文字 / 只留图标」的开关：三条横线，展开时右边多一道竖线收口。
 * 收起后图标变淡，一眼能看出当前是哪种状态。
 */
@Composable
private fun LabelToggle(size: Dp, expanded: Boolean, tint: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.11f
        val ys = listOf(0.24f, 0.5f, 0.76f)
        ys.forEachIndexed { i, frac ->
            val endX = if (expanded) w * 0.86f else w * 0.62f + i * w * 0.08f
            drawLine(
                color = tint,
                start = Offset(w * 0.14f, h * frac),
                end = Offset(endX, h * frac),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        if (expanded) {
            // 展开状态再加一道竖线，像「文字就在这几行里」
            drawLine(
                color = tint,
                start = Offset(w * 0.9f, h * 0.24f),
                end = Offset(w * 0.9f, h * 0.76f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
/**
 * 品牌标志：一颗蛋（鸭嘴兽是从蛋里孵出来的，「孵化版」也接着这层意思）。
 *
 * 自己用 Canvas 画而不是引图标库：ui 模块没有 material-icons 依赖，
 * 为一个 24dp 的标志拉一整个图标库不划算。
 */
@Composable
private fun BrandMark(size: Dp) {
    val accent = MaterialTheme.colorScheme.primary
    val crack = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val egg = Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            cubicTo(w * 0.86f, h * 0.15f, w * 0.93f, h * 0.62f, w * 0.5f, h * 0.96f)
            cubicTo(w * 0.07f, h * 0.62f, w * 0.14f, h * 0.15f, w * 0.5f, h * 0.06f)
            close()
        }
        drawPath(egg, accent)
        // 一道裂纹：两点之间折一下，暗示「刚孵出来」。
        val zig = Path().apply {
            moveTo(w * 0.18f, h * 0.56f)
            lineTo(w * 0.34f, h * 0.44f)
            lineTo(w * 0.5f, h * 0.58f)
            lineTo(w * 0.66f, h * 0.46f)
            lineTo(w * 0.84f, h * 0.58f)
        }
        drawPath(
            zig,
            crack,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** 版本号收起后的那个小标：一个空心圆加中心点，意思是「关于 / 版本」。 */
@Composable
private fun VersionDot(size: Dp) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(
            color,
            radius = w * 0.42f,
            center = Offset(w * 0.5f, h * 0.5f),
            style = Stroke(width = w * 0.11f),
        )
        drawCircle(color, radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.5f))
    }
}

/**
 * 同一页里换二级项时的过渡：只淡入淡出，不带位移。
 *
 * 为什么不做左右滑：左列是竖排的，二级项之间的关系是「上下换一栏」，横向位移会暗示
 * 「翻到旁边去了」，跟左列的方向对不上。换一级页才带一点点横向位移（见上面 AnimatedContent）。
 *
 * 只有下载、设置、更多三页有二级项，所以只有它们用到这一层。
 */
@Composable
private fun SubContent(current: String, content: @Composable (String) -> Unit) {
    val millis = motionMillis(Motion.BASE)
    Crossfade(targetState = current, animationSpec = tween(millis), label = "subTab") { shown ->
        content(shown)
    }
}

/**
 * 左列二级项（PCL 的 PageDownloadLeft / PageSetupLeft 那种）。
 *
 * 只有当前一级项配了二级表才画这一列：启动页和联机页没有，内容区就自己铺满
 * —— 少一列空槽比留一条留白条纹好。
 *
 * 二级项右侧的刷新按钮照 PCL 的 MyListItem.Buttons 做：刷新是这一项的属性，
 * 不是内容区顶部的一个控件。
 */
@Composable
private fun SubColumn(
    groups: List<SubTabGroup>,
    currentSub: String,
    onSelectSub: (String) -> Unit,
    onRefreshSub: (String) -> Unit,
) {
    // 窄窗口收到 150dp：这一列只放中文短标签，不需要更宽。
    val compact = LocalWindowInfo.current.containerSize.width < 1100
    Surface(
        modifier = Modifier.width(if (compact) 150.dp else 176.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 12.dp),
        ) {
            groups.forEach { group ->
                // 带标题的组前面先画一行小字。只在简洁模式下出现（完整模式只有一个无标题组），
                // 作用是告诉用户「下面这几项还在，只是不常点」。
                if (group.label != null) {
                    Text(
                        group.label,
                        Modifier.padding(start = 10.dp, top = 10.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                group.tabs.forEach { sub ->
                    val active = sub.id == currentSub
                    // 选中底色淡进去而不是跳过去：二级项切换本身是 Crossfade，
                    // 左边这一列如果硬切，两边节奏就对不上了。
                    val rowBackground by animateColorAsState(
                        targetValue = if (active) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        },
                        animationSpec = motionTween(Motion.FAST),
                        label = "subBg",
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .background(rowBackground, MaterialTheme.shapes.small)
                            .clickable { onSelectSub(sub.id) }
                            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            sub.label,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (sub.refreshable) {
                            // 用「↻」字符而不是 material-icons：ui 模块没有 icons 依赖，
                            // 为一个按钮引一整个图标库不划算。
                            IconButton(
                                onClick = { onRefreshSub(sub.id) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Text(
                                    "↻",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                Brand.DISCLAIMER_SHORT,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}