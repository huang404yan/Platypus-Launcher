package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.instance.ScanResult
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.instance.mergeInstances
import java.io.File
import kotlinx.coroutines.launch

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
        LaunchedEffect(Unit) { println("[ui] initial page=$page") }
        Row(Modifier.fillMaxSize()) {
            SideBar(current = page, onSelect = { page = it })
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(24.dp),
            ) {
                when (page) {
                    Page.LAUNCH -> LaunchPage(backend, initialGameRoot)
                    Page.INSTANCE -> InstancePage(backend, initialGameRoot)
                    Page.DOWNLOAD -> DownloadsPage(initialGameRoot, backend) { page = Page.LAUNCH }
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
private fun InstancePage(backend: LauncherBackend?, initialGameRoot: String?) {
    var rootText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var registered by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var scannedAt by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    fun rescan() {
        val text = rootText.trim()
        result = if (text.isEmpty()) null else InstanceScanner.scan(File(text))
        scannedAt = System.currentTimeMillis()
        if (backend != null) {
            scope.launch {
                registered = runCatching { backend.instances.list() }.getOrDefault(emptyList())
            }
        }
    }

    LaunchedEffect(Unit) {
        if (rootText.isNotBlank()) rescan()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("实例", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "扫描已有游戏目录里的版本，并合并启动器自己登记的实例。扫描只读，不改动任何文件；" +
                "登记的实例优先显示（带内存设置）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = rootText,
                onValueChange = { rootText = it },
                modifier = Modifier.weight(1f),
                label = { Text("游戏目录（.minecraft）") },
                singleLine = true,
            )
            Button(onClick = { rescan() }) { Text("扫描") }
        }

        val current = result
        val merged = mergeInstances(registered, current?.instances.orEmpty())
        when {
            current == null && merged.isEmpty() -> Hint("还没有扫描。填入目录后点「扫描」。")
            current != null && !current.isGameDirectory && merged.isEmpty() -> {
                Hint("这个目录看起来不是游戏目录。")
                current.warnings.forEach { WarningLine(it) }
            }
            else -> {
                Text(
                    "找到 " + merged.size + " 个实例（登记 " + registered.size + " · 扫描 " +
                        (current?.instances?.size ?: 0) + "）" +
                        if (current == null || current.warnings.isEmpty()) "" else "，" + current.warnings.size + " 条提示",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                current?.warnings.orEmpty().forEach { WarningLine(it) }
                if (merged.isEmpty()) {
                    Hint("没有识别到版本。去「下载」页装一个，或检查 versions/ 下是否每个版本都有同名 .json。")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(merged, key = { instanceKey(it) }) { instance -> InstanceCard(instance) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstanceCard(instance: Instance) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(instance.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "游戏 ${instance.gameVersion}" + (instance.loaderVersion?.let { " · 加载器 $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    instance.directory,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    instance.memoryMb.toString() + " MB" + if (instance.registered) " · 已登记" else " · 仅扫描到",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LoaderBadge(instance.loader)
        }
    }
}

@Composable
private fun LoaderBadge(loader: LoaderKind) {
    val (label, color) = when (loader) {
        LoaderKind.VANILLA -> "原版" to MaterialTheme.colorScheme.secondaryContainer
        LoaderKind.FORGE -> "Forge" to MaterialTheme.colorScheme.tertiaryContainer
        LoaderKind.NEOFORGE -> "NeoForge" to MaterialTheme.colorScheme.tertiaryContainer
        LoaderKind.FABRIC -> "Fabric" to MaterialTheme.colorScheme.primaryContainer
        LoaderKind.QUILT -> "Quilt" to MaterialTheme.colorScheme.primaryContainer
        LoaderKind.OPTIFINE -> "OptiFine" to MaterialTheme.colorScheme.secondaryContainer
        LoaderKind.OTHER -> "其他" to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun PlaceholderPage(page: Page) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(page.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("还没做。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WarningLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
