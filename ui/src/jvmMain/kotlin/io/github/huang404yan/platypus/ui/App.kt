package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
) {
    // 配色提到 MaterialTheme 外面：整套 ColorScheme 由它决定，改它等于换一层皮，
    // 页面代码一行都不用知道 —— 全仓库的颜色都走 MaterialTheme.colorScheme。
    var theme by remember { mutableStateOf(LauncherTheme.AUTO) }

    // 只在最外层读一次设置。放这里而不是每页各读一次：切页会重建页面，
    // 每页各读会让主题在切换瞬间闪一下，而且同一份文件被反复解析。
    LaunchedEffect(backend) {
        val repository = backend?.settings ?: return@LaunchedEffect
        val settings = runCatching { repository.load() }.getOrNull() ?: return@LaunchedEffect
        theme = runCatching { launcherThemeOf(settings.theme) }.getOrDefault(LauncherTheme.AUTO)
        // 下载源偏好是进程级全局量（见 DownloadPreference 的说明）：这里灌一次，
        // 之后所有新建的下载器都按它排序。设置页改的时候会就地覆写。
        DownloadPreference.source = DownloadSource.of(settings.downloadSource)
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
        fun closeOverlay() {
            overlay = null
            overlayTick++
        }

        fun openSetup(instance: Instance) {
            overlayInstance = instance
            overlay = Overlay.InstanceSetup(instanceKey(instance))
        }

        LaunchedEffect(Unit) { println("[ui] initial page=" + page) }

        Column(Modifier.fillMaxSize()) {
            TopBar(
                current = page,
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
                val subs = PAGE_SUB_TABS[page].orEmpty()
                if (subs.isNotEmpty()) {
                    SubColumn(
                        subs = subs,
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
                    val sub = subTabOf(page)
                    // tick 只在「下载」页与「设置」页有意义（两处都有需要重读的清单）；
                    // 别的页面忽略它，免得切页时误触发。
                    val tick = if (page == Page.DOWNLOAD || page == Page.SETTINGS) refreshTick else 0
                    // 启动页另有一路信号：副页面关掉（overlayTick）时重扫登记表。
                    // 只在那时加一，不跟左列「刷新」按钮混用 —— 那个是给下载页重拉网络列表的。
                    when (page) {
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
                        )
                        Page.DOWNLOAD -> when (sub) {
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
                        }
                        Page.LINK -> LinkPage(initialGameRoot = initialGameRoot, backend = backend) { address ->
                            quickPlay = address
                            page = Page.LAUNCH
                        }
                        // 设置页的「启动」二级项要刷新信号（重读本机 Java 清单），其它二级项不用
                        Page.SETTINGS -> SettingsPage(
                            backend = backend,
                            subTab = subTabOf(Page.SETTINGS),
                            refreshTick = tick,
                            theme = theme,
                            // 只改这一层的皮。标题栏由 LaunchedEffect(theme) 回调出去，
                            // 那边会等内容帧提交之后再重画（见 Main.kt 的两个 withFrameNanos），
                            // 所以这里不能提前同步调用，否则标题栏又会抢在内容前面变。
                            onThemeChange = { theme = it },
                        )
                        Page.MORE -> when (sub) {
                            MORE_TAB_ABOUT -> AboutPage()
                            MORE_TAB_DIAG -> DiagnosticPage(backend = backend, initialGameRoot = initialGameRoot)
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
        if (currentOverlay != null) {
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
                    when (currentOverlay) {
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
        FirstRunGate(backend) {
            subSelection[Page.SETTINGS.name] = SETTINGS_TAB_ACCOUNT
            page = Page.SETTINGS
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
private fun TopBar(current: Page, onSelect: (Page) -> Unit) {
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
                    TopTab(
                        page = item,
                        selected = item == current,
                        showLabel = showLabels,
                        glyphSize = if (compact) 18.dp else 22.dp,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { onSelect(item) }
                }
            }
            // 右端：文字开关 + 版本号。
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
    val tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .padding(horizontal = 2.dp)
            .background(
                if (selected) accent.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
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
    subs: List<SubTab>,
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
            subs.forEach { sub ->
                val active = sub.id == currentSub
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                Color.Transparent
                            },
                            MaterialTheme.shapes.small,
                        )
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
            Spacer(Modifier.weight(1f));
            Text(
                Brand.DISCLAIMER_SHORT,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}