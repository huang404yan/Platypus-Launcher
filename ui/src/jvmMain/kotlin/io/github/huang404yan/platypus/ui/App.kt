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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

        LaunchedEffect(Unit) { println("[ui] initial page=$page") }
        Row(Modifier.fillMaxSize()) {
            SideBar(current = page, onSelect = { page = it })
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(24.dp),
            ) {
                when (page) {
                    Page.LAUNCH -> LaunchPage(backend, initialGameRoot, focusKey)
                    Page.INSTANCE -> InstancePage(backend, initialGameRoot) { instance: Instance ->
                        focusKey = instanceKey(instance)
                        page = Page.LAUNCH
                    }
                    Page.DOWNLOAD -> DownloadsPage(initialGameRoot, backend) { page = Page.LAUNCH }
                    Page.MODS -> ModsPage(initialGameRoot, backend)
                    Page.SETTINGS -> SettingsPage(backend)
                    Page.ABOUT -> AboutPage()
                    else -> PlaceholderPage(page)
                }
            }
        }
    }
}

@Composable
private fun SideBar(current: Page, onSelect: (Page) -> Unit) {
    Surface(
        modifier = Modifier.width(220.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxHeight().padding(16.dp)) {
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

@Composable
private fun PlaceholderPage(page: Page) {
    Column {
        Text(page.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("还没做。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
