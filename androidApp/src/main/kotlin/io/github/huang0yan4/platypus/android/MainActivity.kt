package io.github.huang0yan4.platypus.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.huang0yan4.platypus.core.instance.Instance
import io.github.huang0yan4.platypus.core.instance.InstanceScanner
import io.github.huang0yan4.platypus.core.instance.LoaderKind
import io.github.huang0yan4.platypus.core.instance.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

private enum class Tab(val label: String) {
    INSTANCE("实例"),
    DOWNLOAD("下载"),
    LAUNCH("启动"),
    MORE("更多"),
}

@Composable
private fun AppRoot() {
    var tab by remember { mutableStateOf(Tab.INSTANCE) }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        SideBar(current = tab, onSelect = { tab = it })
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            when (tab) {
                Tab.INSTANCE -> InstancePage()
                else -> Placeholder(tab)
            }
        }
    }
}

@Composable
private fun SideBar(current: Tab, onSelect: (Tab) -> Unit) {
    Column(
        modifier = Modifier
            .width(180.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "鸭嘴兽 · 孵化版",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            text = "Platypus Preview",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))

        Tab.entries.forEach { item ->
            val selected = item == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
    }
}

@Composable
private fun InstancePage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasFileAccess(context)) }
    var root by remember { mutableStateOf(defaultGameRoot()) }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var scanning by remember { mutableStateOf(false) }

    fun scan() {
        val path = root.trim()
        if (path.isEmpty() || !granted) return
        scanning = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { InstanceScanner.scan(File(path)) }
            result = r
            scanning = false
        }
    }

    LaunchedEffect(granted) {
        if (granted) scan()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("实例", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "扫描手机上的游戏目录，把 versions/ 下的每个版本识别成实例。只读。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!granted) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("还没有文件访问权限", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Android 11 起要读 /storage/emulated/0 下的目录，必须手动开「所有文件访问权限」。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { openAllFilesSettings(context) }) { Text("去授权") }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = root,
                onValueChange = { root = it },
                modifier = Modifier.weight(1f),
                label = { Text("游戏目录") },
                singleLine = true,
            )
            Button(
                onClick = {
                    granted = hasFileAccess(context)
                    scan()
                },
                enabled = !scanning,
            ) {
                Text(if (scanning) "扫描中…" else "扫描")
            }
        }

        val current = result
        when {
            current == null -> Text(
                "填入目录后点「扫描」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            !current.isGameDirectory -> {
                Text("这个目录不是游戏目录。", color = MaterialTheme.colorScheme.error)
                current.warnings.forEach { Warning(it) }
            }
            else -> {
                Text(
                    "找到 ${current.instances.size} 个实例",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                current.warnings.forEach { Warning(it) }
                if (current.instances.isEmpty()) {
                    Text(
                        "没有识别到版本。检查 versions/ 下是否每个版本都有同名 .json。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.instances) { instance -> InstanceCard(instance) }
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
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(instance.name, fontWeight = FontWeight.SemiBold)
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
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Placeholder(tab: Tab) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tab.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("还没做。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

private fun hasFileAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun openAllFilesSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val perApp = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    runCatching { context.startActivity(perApp) }
        .onFailure { runCatching { context.startActivity(fallback) } }
}

@Suppress("DEPRECATION")
private fun defaultGameRoot(): String {
    val base = Environment.getExternalStorageDirectory().absolutePath
    val candidates = listOf(
        "$base/.minecraft",
        "$base/minecraft",
        "$base/games/PojavLauncher/.minecraft",
        "$base/Android/data/net.kdt.pojavlaunch/files/.minecraft",
    )
    return candidates.firstOrNull { File(it).isDirectory } ?: "$base/.minecraft"
}
