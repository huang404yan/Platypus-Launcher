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
import androidx.compose.foundation.layout.width
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
import java.io.File

enum class Page(val label: String) {
    LAUNCH("启动"),
    INSTANCE("实例"),
    DOWNLOAD("下载"),
    MODS("模组"),
    PACKS("光影资源包"),
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

        LaunchedEffect(Unit) { println("[ui] initial page=$page") }
        Row(Modifier.fillMaxSize()) {
            SideBar(
                current = page,
                currentSub = subTabOf(page),
                onSelect = { page = it },
                onSelectSub = { sub -> subSelection[page.name] = sub },
            )
            // 留白与小窗口有关：1280 宽时 24dp 只是呼吸感，1024 宽时它和侧栏一起
            // 把表单挤得只剩一半。窄窗口收到 16dp，给内容让出位置。
            val compact = LocalWindowInfo.current.containerSize.width < 1100
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(if (compact) 16.dp else 24.dp),
            ) {
                when (page) {
                    Page.LAUNCH -> LaunchPage(backend, initialGameRoot, focusKey, quickPlay)
                    Page.INSTANCE -> InstancePage(backend, initialGameRoot) { instance: Instance ->
                        focusKey = instanceKey(instance)
                        page = Page.LAUNCH
                    }
                    Page.DOWNLOAD -> DownloadsPage(initialGameRoot, backend) { page = Page.LAUNCH }
                    Page.MODS -> ModsPage(initialGameRoot, backend, subTabOf(Page.MODS))
                    Page.PACKS -> PacksPage(initialGameRoot, backend, subTabOf(Page.PACKS))
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
 * 只有选中项展开而不是全部展开，是因为七个一级项已经占掉大半屏高；全部展开会把
 * 「更多」和「关于」挤到看不见的地方。
 */
@Composable
private fun SideBar(
    current: Page,
    currentSub: String,
    onSelect: (Page) -> Unit,
    onSelectSub: (String) -> Unit,
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
                                style = MaterialTheme.typography.bodySmall,
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
