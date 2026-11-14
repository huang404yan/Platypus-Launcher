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
import androidx.compose.foundation.layout.height
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
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.packs.PackKind
import java.io.File

/**
 * 一级导航。
 *
 * 六个入口：PCL 那边「装点什么」全在「下载」一个入口下面分层，我们照搬这个分法 ——
 * 用户想装东西时心里想的是「装个光影」，不是「去光影资源包页」。删掉的 MODS / PACKS
 * 两个一级页没有丢功能，只是降级成「下载」下的五个二级项（见 [PAGE_SUB_TABS]）。
 * 启动与实例留在一级：这两件事天天用，藏进二级只会多一次点击。
 */
enum class Page(val label: String) {
    LAUNCH("启动"),
    INSTANCE("实例"),
    DOWNLOAD("下载"),
    LINK("联机"),
    SETTINGS("设置"),
    ABOUT("关于"),
}

/** 桌面默认的 .minecraft 位置：优先用 %APPDATA%\\.minecraft。 */
fun defaultGameRoot(): String {
    val appData = System.getenv("APPDATA") ?: return ""
    val candidate = File(appData, ".minecraft")
    return if (candidate.isDirectory) candidate.absolutePath else ""
}

@Composable
fun App(initialGameRoot: String? = null, backend: LauncherBackend? = null) {
    MaterialTheme {
        var page by remember { mutableStateOf(Page.INSTANCE) }

        // 实例页点「去启动」时带上这个实例：只交一个 key，不把整个对象塞给启动页当长期状态。
        var focusKey by remember { mutableStateOf<String?>(null) }

        // 联机页点「直接进」时把地址交给启动页。存在这一层而不是启动页里：
        // 切页会把启动页整个销毁，地址跟着丢，回去就变成停在主菜单。
        var quickPlay by remember { mutableStateOf("") }

        // 每页选中了哪个二级项。存在这里而不是页面里：页面切走就被销毁了，
        // 记在页面里的话，去下载页看一眼再回来，设置页会跳回第一个 tab。
        val subSelection = remember { mutableStateMapOf<String, String>() }
        fun subTabOf(target: Page): String =
            subSelection[target.name] ?: defaultSubTab(target)

        // 左栏「刷新」按钮的信号：每次点加一，页面拿它当 LaunchedEffect 的 key。
        // 只传一个计数而不是传回调进侧边栏，是因为刷新要做的事在页面里（要读哪些状态、
        // 是重扫磁盘还是重拉网络列表），侧边栏既看不见也不该知道。
        var refreshTick by remember { mutableStateOf(0) }

        LaunchedEffect(Unit) { println("[ui] initial page=$page") }
        Row(Modifier.fillMaxSize()) {
            SideBar(
                current = page,
                currentSub = subTabOf(page),
                onSelect = { target ->
                    // 点的是另一个一级项才重置它自己的二级项：从「下载/光影包」切到「设置」，
                    // 再点「下载」回来，应该回到「原版游戏」；而点当前这个一级项本身
                    // （常见于想回内容区顶部）不该把二级项踢回默认。
                    if (target != page) subSelection.remove(target.name)
                    page = target
                },
                onSelectSub = { sub -> subSelection[page.name] = sub },
                onRefreshSub = { sub ->
                    if (sub == subTabOf(page)) refreshTick++
                },
            )
            // 留白与小窗口有关：1280 宽时 24dp 只是呼吸感，1024 宽时它和侧栏一起
            // 把表单挤得只剩一半。窄窗口收到 16dp，给内容让出位置。
            val compact = LocalWindowInfo.current.containerSize.width < 1100
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(if (compact) 16.dp else 24.dp),
            ) {
                val sub = subTabOf(page)
                // tick 只在「下载」页有意义；别的页面忽略它，免得切页时误触发。
                val tick = if (page == Page.DOWNLOAD) refreshTick else 0
                when (page) {
                    Page.LAUNCH -> LaunchPage(backend, initialGameRoot, focusKey, quickPlay)
                    Page.INSTANCE -> InstancePage(backend, initialGameRoot) { instance: Instance ->
                        focusKey = instanceKey(instance)
                        page = Page.LAUNCH
                    }
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
                    Page.LINK -> LinkPage { address ->
                        quickPlay = address
                        page = Page.LAUNCH
                    }
                    Page.SETTINGS -> SettingsPage(backend, subTabOf(Page.SETTINGS))
                    Page.ABOUT -> AboutPage()
                }
            }
        }
    }
}

/**
 * 左侧导航。
 *
 * 两级：一级项常驻，选中的那个下面展开它自己的二级项（PCL 的 PageSetupLeft 那种）。
 * 只有选中项展开而不是全部展开，是因为五个一级项 + 下载下的五项已经占掉大半屏高；
 * 全部展开会把「设置」和「关于」挤到看不见的地方。
 *
 * 二级项右侧的刷新按钮照 PCL 的 `MyListItem.Buttons` 做：刷新是这一项的属性，
 * 不是内容区顶部的一个控件。
 */
@Composable
private fun SideBar(
    current: Page,
    currentSub: String,
    onSelect: (Page) -> Unit,
    onSelectSub: (String) -> Unit,
    onRefreshSub: (String) -> Unit,
) {
    // 窄窗口把侧栏收到 180dp：1024 宽下 220dp 侧栏 + 24dp 边距会吃掉四分之一屏，
    // 剩下的宽度放不下一行「目录输入框 + 选择 + 扫描 + 新建」四个控件。
    val compact = LocalWindowInfo.current.containerSize.width < 1100
    Surface(
        modifier = Modifier.width(if (compact) 180.dp else 220.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxHeight().padding(if (compact) 12.dp else 16.dp)) {
            Text(
                if (Brand.isPreview) Brand.PREVIEW_NAME_ZH else Brand.NAME_ZH,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (Brand.isPreview) Brand.PREVIEW_NAME_EN else Brand.NAME_EN,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Page.entries.forEach { item ->
                val selected = item == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                            MaterialTheme.shapes.small,
                        )
                        .clickable { onSelect(item) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selected) {
                    PAGE_SUB_TABS[item].orEmpty().forEach { sub ->
                        val active = sub.id == currentSub
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, top = 1.dp, bottom = 1.dp)
                                .clickable { onSelectSub(sub.id) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "· " + sub.label,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
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
            }
            Spacer(Modifier.weight(1f))
            Text(
                "v" + Brand.version,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Brand.DISCLAIMER_SHORT,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}