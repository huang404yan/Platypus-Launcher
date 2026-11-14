package io.github.huang404yan.platypus.launcher.preview

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.Credit
import io.github.huang404yan.platypus.core.CreditKind
import io.github.huang404yan.platypus.core.Credits
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.instance.ScanResult
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.instance.mergeInstances
import io.github.huang404yan.platypus.core.theme.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 配色从设置里读一次就定了：整棵界面都走 MaterialTheme.colorScheme，
            // 换主题只要换这一层，页面代码一行都不用知道主题存在。
            var theme by remember { mutableStateOf(LauncherTheme.AUTO) }
            LaunchedEffect(Unit) { theme = readThemePreference() }
            // PlatypusTheme 自己已经铺了一层 background 色的 Surface：这里再包一层
            // 默认取 surface 的 Surface 会把那层盖住（浅色那套 surface 是纯白，
            // background 才是色卡里那个 #F4F7F6），底色就变成白的了。
            PlatypusTheme(theme) {
                AppRoot(
                    theme = theme,
                    onThemeChange = { picked ->
                        theme = picked
                        // 落盘跟着切页走不了：这里是 Activity 级，没有 settings 仓库可用，
                        // 所以由设置页自己写文件，写完再回调上来换皮。
                    },
                )
            }
        }
    }
}

/**
 * 侧边栏的顺序与桌面端对齐：实例 / 下载 / 模组 / 光影资源包 / 联机 / 启动 / 设置 / 诊断 / 关于。
 *
 * 「诊断」是安卓独有的（桌面没有 EGL、也没有「所有文件访问权限」这类要查的东西）；
 * 「更多」这个名字原来挂着一个永远「还没做」的占位，现在被模组、联机、设置三个真页面取代。
 *
 * 「光影资源包」在桌面上是侧边栏的一个页面、里面再分光影 / 资源包 / 数据包三个二级项；
 * 手机侧边栏放不下二级，所以这里只留一个 Tab，三种包由页面顶部的按钮切换。
 *
 * 「服务端」也是一个 Tab。手机上只能装原版 / Paper / Fabric 三种（Forge 与 NeoForge
 * 要起 java 子进程跑安装器，这一版不做），装完没有应用内开服，只留目录和脚本。
 *
 * 「Java」紧跟设置：它跟桌面一样是「往盘上装一份运行时」，不是改设置，但也不需要单独
 * 占一个顶层位置放前面 —— 装一次就不常来了。
 */
private enum class Tab(val label: String) {
    INSTANCE("实例"),
    DOWNLOAD("下载"),
    MODS("模组"),
    PACKS("光影资源包"),
    SERVER("服务端"),
    LINK("联机"),
    LAUNCH("启动"),
    SETTINGS("设置"),
    JAVA("Java"),
    DIAGNOSTIC("诊断"),
    ABOUT("关于"),
}

@Composable
private fun AppRoot(theme: LauncherTheme, onThemeChange: (LauncherTheme) -> Unit) {
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
                Tab.DOWNLOAD -> DownloadPage()
                Tab.MODS -> ModsPage()
                Tab.PACKS -> PacksPage()
                Tab.SERVER -> ServerPage()
                Tab.LINK -> LinkPage()
                Tab.SETTINGS -> SettingsPage(theme = theme, onThemeChange = onThemeChange)
                Tab.JAVA -> JavaPage()
                Tab.DIAGNOSTIC -> DiagnosticPage()
                Tab.ABOUT -> AboutPage()
                // 启动是唯一还没做的：它要的应用内 JVM 与图形转译属于 runtime-android，
                // 现在整个模块还不存在（见 docs/03-luxian-yu-youxianji.md 第 3 节的 S1 spike）。
                Tab.LAUNCH -> LaunchPlaceholder()
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
            text = if (Brand.isPreview) Brand.PREVIEW_NAME_ZH else Brand.NAME_ZH,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            text = if (Brand.isPreview) Brand.PREVIEW_NAME_EN else Brand.NAME_EN,
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
        Spacer(Modifier.weight(1f))
        Text(
            text = "v" + Brand.version,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            text = Brand.DISCLAIMER_SHORT,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
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
    var selected by remember { mutableStateOf<Instance?>(null) }

    fun scan() {
        val path = root.trim()
        if (path.isEmpty() || !granted) return
        scanning = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                val dir = File(path)
                // 扫描结果 + 本启动器登记过的实例合并：同一条目录以登记的那份为准，
                // 这样改过的内存与 Java 路径不会在下次扫描时被打回默认值。
                val registered = runCatching {
                    JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(dir)).list()
                }.getOrNull().orEmpty()
                val scanned = InstanceScanner.scan(dir)
                scanned.copy(instances = mergeInstances(registered, scanned.instances))
            }
            result = r
            scanning = false
            // 详情页开着时重扫，跟着刷成最新那份（否则保存完还显示旧值）
            selected = selected?.let { old -> r.instances.firstOrNull { instanceKey(it) == instanceKey(old) } }
        }
    }

    LaunchedEffect(granted) {
        if (granted) scan()
    }

    val open = selected
    if (open != null) {
        InstanceDetailScreen(
            instance = open,
            gameRoot = root,
            onBack = { selected = null },
            onSaved = { saved ->
                selected = saved
                result = result?.let { r ->
                    r.copy(instances = mergeInstances(listOf(saved), r.instances.filterNot { instanceKey(it) == instanceKey(saved) }))
                }
            },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("实例", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "扫描手机上的游戏目录，把 versions/ 下的每个版本识别成实例。点一个看详情、改内存。",
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
                        items(current.instances) { instance ->
                            InstanceCard(instance, onClick = { selected = instance })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstanceCard(instance: Instance, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
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
private fun AboutPage() {
    // 加了致谢段之后这一页比一屏长，必须能滚 —— 桌面端也一样（那是 ScrollView）。
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("关于", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Text(
            if (Brand.isPreview) Brand.PREVIEW_NAME_ZH else Brand.NAME_ZH,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (Brand.isPreview) Brand.PREVIEW_NAME_EN else Brand.NAME_EN,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (Brand.isPreview) Brand.PREVIEW_SLOGAN else Brand.SLOGAN,
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow("版本", Brand.version)
                InfoRow("通道", if (Brand.isPreview) "孵化版（Platypus Preview）" else "正式版（Platypus Launcher）")
                InfoRow("协议", "Apache-2.0")
                InfoRow("仓库", Brand.HOMEPAGE)
            }
        }

        HorizontalDivider()

        CreditsSection()

        HorizontalDivider()

        Text("免责声明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            Brand.DISCLAIMER_ZH,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            Brand.DISCLAIMER_EN,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 开源致谢。跟桌面端 `ui/About.kt` 的 `CreditsSection` 同一套数据结构（core 的 `Credits`），
 * 只是排得紧一点 —— 手机屏幕窄，授权协议名放第二行而不是挤在标题右侧。
 */
@Composable
private fun CreditsSection() {
    Text("开源致谢", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        Credits.INTRO_ZH,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (kind in CreditKind.entries) {
                val group = Credits.of(kind)
                if (group.isEmpty()) continue
                Text(
                    kind.label + " · " + group.size,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    creditKindExplanation(kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (item in group) {
                    CreditCard(item)
                }
            }
        }
    }
}

@Composable
private fun CreditCard(item: Credit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                item.license,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.note.isNotBlank()) {
                Text(
                    item.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.url.isNotBlank()) {
                Text(item.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 每一类致谢到底意味着什么 —— 尤其要让用户分清「GPL 代码没进这个项目」。 */
private fun creditKindExplanation(kind: CreditKind): String = when (kind) {
    CreditKind.CODE -> "这些项目的实现被用进了本项目，版权归原作者，按各自的许可使用。"
    CreditKind.REFERENCE -> "只看过它们的文档、issue 与源码来理解问题怎么解，代码是自己重写的。"
    CreditKind.SPEC -> "用的是公开的接口、数据格式或社区约定，没有链接也没有修改对方的代码。"
    CreditKind.LIBRARY -> "编译进安装包的第三方库，各自保留原版权与许可。"
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 启动页的占位，但不是一句「还没做」。
 *
 * 手机上进游戏需要「应用内起 JVM + OpenGL 转译」，那是 runtime-android 模块的全部内容，
 * 与桌面端起 java 进程完全是两件事（见 docs/03-luxian-yu-youxianji.md 第 3 节）。
 * 把它讲清楚，比放一个哑按钮有用：用户能知道现在能干什么、缺的是什么。
 */
@Composable
private fun LaunchPlaceholder() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("启动", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "手机上进游戏要先在应用内起 JVM，再把 OpenGL 转译成手机的图形接口——" +
                "这是独立的 runtime-android 模块，现在还不存在。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "这一版能做的：把游戏文件下全（下载页）、认出已有的实例（实例页）、装模组（模组页）、" +
                "装光影 / 资源包 / 数据包（那一页）、下一个能开的服务端（服务端页）、找局域网世界（联机页）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

internal fun hasFileAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

internal fun openAllFilesSettings(context: Context) {
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
internal fun defaultGameRoot(): String {
    val base = Environment.getExternalStorageDirectory().absolutePath
    val candidates = listOf(
        "$base/.minecraft",
        "$base/minecraft",
        "$base/games/PojavLauncher/.minecraft",
        "$base/Android/data/net.kdt.pojavlaunch/files/.minecraft",
    )
    return candidates.firstOrNull { File(it).isDirectory } ?: "$base/.minecraft"
}
