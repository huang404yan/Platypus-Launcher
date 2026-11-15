package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.download.DownloadPreference
import io.github.huang404yan.platypus.core.download.DownloadSource
import io.github.huang404yan.platypus.core.instance.Instance
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
                    overlay = null
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
                        )
                        Page.DOWNLOAD -> when (sub) {
                            DOWNLOAD_TAB_MODS -> ModsPage(initialGameRoot, backend, tick)
                            // 整合包页没有可刷新的清单（见 Navigation.kt 里那一项的说明），不传 tick
                            DOWNLOAD_TAB_MODPACK -> ModpackPage(initialGameRoot, backend) { page = Page.LAUNCH }
                            DOWNLOAD_TAB_DATAPACKS ->
                                PacksPage(initialGameRoot, backend, PackKind.DATAPACK, tick)
                            DOWNLOAD_TAB_RESOURCEPACKS ->
                                PacksPage(initialGameRoot, backend, PackKind.RESOURCE_PACK, tick)
                            DOWNLOAD_TAB_SHADERS ->
                                PacksPage(initialGameRoot, backend, PackKind.SHADER, tick)
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
                when (currentOverlay) {
                    Overlay.InstanceSelect -> InstancePage(
                        backend = backend,
                        initialGameRoot = initialGameRoot,
                        onBack = { overlay = null },
                        onLaunch = { instance ->
                            focusKey = instanceKey(instance)
                            page = Page.LAUNCH
                            overlay = null
                        },
                        onOpenSetup = { instance -> openSetup(instance) },
                    )
                    is Overlay.InstanceSetup -> {
                        val instance = overlayInstance
                        if (instance == null) {
                            overlay = null
                        } else {
                            InstanceDetailPanel(
                                instance = instance,
                                backend = backend,
                                onBack = { overlay = null },
                                onNotice = { println("[setup] " + it) },
                            )
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
 * 顶部横排五个一级项（照 PCL 的 FormMain.xaml:123-131 那一排 MyRadioButton）。
 *
 * 为什么顶栏而不是侧栏：横排五项在 16:9 的窗口里只占一行，横着排一眼全看见；
 * 竖排侧栏要吃掉 180"220dp 的宽度，还把「同一件事的入口」摊成好几个一级项。
 * PCL 右上是浮动的任务管理 / 关闭游戏，我们这儿放版本号，把右上角留给窗口按钮。
 */
@Composable
private fun TopBar(current: Page, onSelect: (Page) -> Unit) {
    val compact = LocalWindowInfo.current.containerSize.width < 1100
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 12.dp else 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    if (Brand.isPreview) Brand.PREVIEW_NAME_ZH else Brand.NAME_ZH,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!compact) {
                    Text(
                        if (Brand.isPreview) Brand.PREVIEW_NAME_EN else Brand.NAME_EN,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(if (compact) 14.dp else 28.dp))
            Page.entries.forEach { item ->
                val selected = item == current
                Box(
                    Modifier
                        .padding(horizontal = 1.dp)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                Color.Transparent
                            },
                            MaterialTheme.shapes.small,
                        )
                        .clickable { onSelect(item) }
                        .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "v" + Brand.version,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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