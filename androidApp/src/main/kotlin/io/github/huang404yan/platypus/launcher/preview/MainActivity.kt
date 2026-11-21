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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.Credit
import io.github.huang404yan.platypus.core.CreditKind
import io.github.huang404yan.platypus.core.Credits
import io.github.huang404yan.platypus.core.instance.GameRoots
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceCatalog
import io.github.huang404yan.platypus.core.instance.missingNotice
import io.github.huang404yan.platypus.core.instance.pruneMissing
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.instance.ScanResult
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.instance.groupNames
import io.github.huang404yan.platypus.core.instance.lastPlayedText
import io.github.huang404yan.platypus.core.instance.mergeInstances
import io.github.huang404yan.platypus.core.instance.sectionize
import io.github.huang404yan.platypus.core.instance.withGroup
import io.github.huang404yan.platypus.core.theme.LauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    /**
     * 从系统设置页开完「所有文件访问权限」回来，走到这里。
     *
     * 权限是在别的应用（系统设置）里改的，Compose 这层收不到任何回调，所以必须由
     * Activity 的生命周期转发一下：通知完各页面会用 [rememberFileAccessState] 重查一次，
     * 「去授权」才会自己变成「已授权」。不加这一步，用户开完权限回来看到的还是没授权，
     * 得再点一次按钮才知道其实已经生效了。
     */
    override fun onResume() {
        super.onResume()
        FileAccessGate.notifyResumed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 启动器该占满整屏：顶上那条时间/信号/电量、底下手势条平时不该一直挂着。
        // 藏起来但不锁死 —— 从屏幕边缘下滑仍能临时叫出来（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE），
        // 否则横屏矮屏上白白少掉一条高度，侧栏那 11 项就更挤了。
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            // 配色从设置里读一次就定了：整棵界面都走 MaterialTheme.colorScheme，
            // 换主题只要换这一层，页面代码一行都不用知道主题存在。
            var theme by remember { mutableStateOf(LauncherTheme.AUTO) }
            // null 表示「还没读出来」：第一帧什么都不画，免得先闪一下主界面再跳到引导页。
            var guideNeeded by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) {
                theme = readThemePreference()
                // 隔离默认值与下载连接数同样是进程级全局量，开程序时灌一次；设置页改完会就地覆写。
                applyProcessDefaults()
                guideNeeded = runCatching { androidGuideNeeded() }.getOrDefault(false)
            }
            // PlatypusTheme 自己已经铺了一层 background 色的 Surface：这里再包一层
            // 默认取 surface 的 Surface 会把那层盖住（浅色那套 surface 是纯白，
            // background 才是色卡里那个 #F4F7F6），底色就变成白的了。
            PlatypusTheme(theme) {
                when (guideNeeded) {
                    null -> Unit
                    // 第一次打开先走引导：手机上要自己下 JRE 与图形转译库，藏在两个页面里没人找得到。
                    true -> FirstRunGuideScreen(onDone = { guideNeeded = false })
                    false -> AppRoot(
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
}

/**
 * 侧边栏一共七项，与桌面端同一套骨架：启动 / 实例 / 下载 / 联机 / 帮助 / 设置 / 关于。
 *
 * 2026-11-16 按用户要求重排过一次，原来十二项里有五个是「某个页面下面的一小块」，
 * 平铺在侧栏上会让人以为它们是并列的功能：
 *
 * - 模组 / 光影资源包 / 服务端 收进「下载」。它们本来就是「从网上找一个东西装进游戏目录」，
 *   和装原版是同一条流水线，只是产物落在不同目录；下载页顶部一个分类栏就能装下。
 * - Java / 诊断 / 账号 收进「设置」。前两个是「这台设备上有什么」的检查，第三个是身份，
 *   都属于「用之前配一次就不常来」的东西。
 * - 启动 / 实例 / 联机 / 帮助 / 关于 留在顶层 —— 每次开启动器都要经过其中至少两个。
 *
 * 「启动」默认落在这一页：它是「今天要玩哪个版本、用谁的身份」这一件事的入口，
 * 桌面上也是这个理由（见 ui/App.kt 的注释）。
 */
private enum class Tab(val label: String) {
    LAUNCH("启动"),
    INSTANCE("实例"),
    DOWNLOAD("下载"),
    LINK("联机"),
    HELP("帮助"),
    SETTINGS("设置"),
    ABOUT("关于"),
}

@Composable
private fun AppRoot(theme: LauncherTheme, onThemeChange: (LauncherTheme) -> Unit) {
    // 「少动一点」：读一次设置，页面代码不必各写分支，与桌面 ui/Motion.kt 同一套。
    // 读不到时按 false 走 —— 动效是默认状态，读失败就把界面变死板比偶尔多晃一下更让人困惑。
    var reduceMotion by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { reduceMotion = readReduceMotion() }
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        AppShell(
            theme = theme,
            onThemeChange = onThemeChange,
            reduceMotion = reduceMotion,
            // 设置页改完立刻生效：它自己先落盘，再回调这里换值 —— 两边都写一遍会打架。
            onReduceMotionChange = { reduceMotion = it },
        )
    }
}

/**
 * 七项侧栏 + 内容框 + 通知浮层。
 *
 * 与 [AppRoot] 分开只是为了把「读设置」和「画界面」两件事摆平：
 * [LocalReduceMotion] 必须包住整棵树，而它的值来自一次 io 读盘。
 */
@Composable
private fun AppShell(
    theme: LauncherTheme,
    onThemeChange: (LauncherTheme) -> Unit,
    reduceMotion: Boolean,
    onReduceMotionChange: (Boolean) -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.LAUNCH) }
    // 二级项记在 map 里而不是每个 Tab 一个变量：切走再切回来时还停在上次那一项，
    // 而 "下载 → 模组" 与 "设置 → Java" 互不干扰（桌面 App.kt 用的是同一套写法）。
    val subSelection = remember { mutableStateMapOf<String, String>() }
    val sub = subSelection[tab.name].orEmpty()
    // 安装任务活在页面之外，装完那一刻用户多半已经切到别的页去了，
    // 所以提示得画在这一层，而不是让各个页面自己扛。
    val notices = Notices.items.collectAsState().value
    // 尺寸不写死：手机横屏两千多 px、平板更宽，同一套 dp 在两种屏上观感差很多。
    // 侧栏按屏宽取比例再夹在上下限之间，内容框的留白同理。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidebarWidth = (maxWidth * 0.17f).coerceIn(180.dp, 260.dp)
        // 留白要能躲开屏幕圆角：之前按 0.8% 算，横屏只有 7dp 左右，右边缘被圆角
        // 切掉一个「小三角」。改成 2%、下限提到 12dp，常见机身圆角（16–24dp）就盖不到框了。
        val frameGap = (maxWidth * 0.02f).coerceIn(12.dp, 28.dp)
        // 侧栏的色块一路铺到屏幕最左边，把前置摄像头那块盖住（FCL 就是这么做的）。
        // 所以左侧不留 inset，只避让右侧与底部：否则内容框的右边缘会被屏幕圆角、
        // 右侧挖孔或手势条切掉 —— 2026-11-14 用户报的「右边小框被屏幕遮挡」即此。
        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom),
                ),
        ) {
            SideBar(
                width = sidebarWidth,
                current = tab,
                currentSub = sub,
                onSelect = { tab = it },
                onSelectSub = { subSelection[tab.name] = it },
            )
            // 内容区自己带一个框（留白 + 圆角 + 描边），侧栏不带 —— 侧栏要铺到最左
            // 把摄像头盖住，给它留白就又露出挖孔了。中间不放分隔线：框就是边界。
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(frameGap)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                // 换页给一点位移：没有它的时候整个内容框是一瞬间整体换掉，
                // 眼睛要靠标题才知道点中了；有了方向感，左栏点第几项都一眼看得出来。
                // 时长走 motionMillis，「少动一点」勾上时瞬间到位。
                val pageMillis = motionMillis(Motion.BASE)
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState.ordinal >= initialState.ordinal
                        val shift = if (forward) 1 else -1
                        (fadeIn(tween(pageMillis)) + slideInHorizontally(tween(pageMillis)) { shift * it / 20 }) togetherWith
                            (fadeOut(tween(pageMillis)) + slideOutHorizontally(tween(pageMillis)) { -shift * it / 20 })
                    },
                    label = "tab",
                ) { shown ->
                when (shown) {
                    // 启动页做到「进游戏之前的全部准备」：挑实例与身份、挑运行时与后端、
                    // 六条自检、拼出应用内 JVM 要的完整参数表。最后那一步是 JNI 起 JVM。
                    Tab.LAUNCH -> LaunchPage()
                    Tab.INSTANCE -> InstancePage()
                    Tab.LINK -> LinkPage()
                    Tab.HELP -> HelpPage()
                    Tab.ABOUT -> AboutPage()
                    // 「下载」与「设置」各有四项二级。分类栏长在侧栏里（选中时向下展开），
                    // 不再往页面顶部塞一排按钮：手机上横屏本来就矮，页面顶部那一行会跟
                    // 标题抢位置，而且切页时「我在哪一层」会看不清。
                    //
                    // 二级项之间走 SubContent（只淡入淡出，不位移）：侧栏那几行是竖排的，
                    // 横向滑会暗示「翻到旁边去了」，跟手指点下去的方向对不上。
                    Tab.DOWNLOAD -> SubContent(sub) { picked ->
                        when (picked) {
                            DOWNLOAD_SUB_MODS -> ModsPage()
                            DOWNLOAD_SUB_PACKS -> PacksPage()
                            DOWNLOAD_SUB_SERVER -> ServerPage()
                            else -> DownloadPage()
                        }
                    }
                    Tab.SETTINGS -> SubContent(sub) { picked ->
                        when (picked) {
                            SETTINGS_SUB_ACCOUNT -> AccountPage()
                            SETTINGS_SUB_JAVA -> JavaPage()
                            SETTINGS_SUB_DIAGNOSTIC -> DiagnosticPage()
                            SETTINGS_SUB_TOOLBOX -> ToolboxPage()
                            else -> SettingsPage(
                                theme = theme,
                                onThemeChange = onThemeChange,
                                reduceMotion = reduceMotion,
                                onReduceMotionChange = onReduceMotionChange,
                            )
                        }
                    }
                }
                }
            }
        }
        // 浮卡画在最外层：它得盖住内容框的圆角与侧栏，否则贴边那几行会被裁掉。
        if (notices.isNotEmpty()) {
            NoticeStack(notices) { notice ->
                Notices.dismiss(notice.id)
                // 点一下就去那一页。装完东西的人下一步几乎总是「看一眼它在哪 / 去启动它」，
                // 让他自己回侧栏再点一遍是多余的一步。
                val tip = notice.goto.split('|')
                Tab.entries.firstOrNull { it.name == tip[0] }?.let { target ->
                    val wanted = tip.getOrNull(1).orEmpty()
                    if (wanted.isBlank()) subSelection.remove(target.name) else subSelection[target.name] = wanted
                    tab = target
                }
            }
        }
    }
}

/**
 * 同一页里换二级项时的过渡：只淡入淡出，不带位移。
 *
 * 桌面 App.kt 里那一层写的是 Crossfade，这里手写 AnimatedContent 是因为
 * 安卓这份用的是 `targetState` + `when` 分发的形式，两边语义一样：
 * 过渡期间只画一帧内容，不会出现两份页面同时在屏上抢焦点。
 */
@Composable
private fun SubContent(current: String, content: @Composable (String) -> Unit) {
    val millis = motionMillis(Motion.BASE)
    AnimatedContent(
        targetState = current,
        transitionSpec = { fadeIn(tween(millis)) togetherWith fadeOut(tween(millis)) },
        label = "subTab",
    ) { shown -> content(shown) }
}

/**
 * 「下载」与「设置」下面各有四项。放在这里而不是各页面里，是因为侧栏要画它们、
 * 分发表也要按它们选页面 —— 一处定义、两处消费，免得加一项的时候漏掉一边。
 */
private const val DOWNLOAD_SUB_VANILLA = "vanilla"
private const val DOWNLOAD_SUB_MODS = "mods"
private const val DOWNLOAD_SUB_PACKS = "packs"
private const val DOWNLOAD_SUB_SERVER = "server"
private const val SETTINGS_SUB_GENERAL = "general"
private const val SETTINGS_SUB_ACCOUNT = "account"
private const val SETTINGS_SUB_JAVA = "java"
private const val SETTINGS_SUB_DIAGNOSTIC = "diagnostic"
private const val SETTINGS_SUB_TOOLBOX = "toolbox"

/** 这个 Tab 展开之后要画哪几行；空表示它本身就是一个页面，不再分词。 */
private val Tab.subItems: List<Pair<String, String>>
    get() = when (this) {
        Tab.DOWNLOAD -> listOf(
            DOWNLOAD_SUB_VANILLA to "原版与加载器",
            DOWNLOAD_SUB_MODS to "模组",
            DOWNLOAD_SUB_PACKS to "光影资源包",
            DOWNLOAD_SUB_SERVER to "服务端",
        )
        Tab.SETTINGS -> listOf(
            SETTINGS_SUB_GENERAL to "常规",
            SETTINGS_SUB_ACCOUNT to "账号",
            SETTINGS_SUB_JAVA to "Java",
            SETTINGS_SUB_DIAGNOSTIC to "诊断",
            SETTINGS_SUB_TOOLBOX to "百宝箱",
        )
        else -> emptyList()
    }

@Composable
private fun SideBar(
    width: Dp,
    current: Tab,
    currentSub: String,
    onSelect: (Tab) -> Unit,
    onSelectSub: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            // 底色先铺满，再避让挖孔：inset 加在 background 之前会连色块一起缩进去，
            // 左侧露出一条底色边，摄像头反而更显眼。加在之后才是「盖住 + 内容不挡」。
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        // 品牌名固定在顶部，不跟着菜单滚。
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 12.dp)) {
            Text(
                text = if (Brand.isPreview) Brand.PREVIEW_NAME_ZH else Brand.NAME_ZH,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (Brand.isPreview) Brand.PREVIEW_NAME_EN else Brand.NAME_EN,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 只有菜单项在滚：weight(1f) 把剩余高度吃满，滚动范围就锁在这一段，
        // 顶部的名字与底部的版本号都不动（2026-11-14 用户要求）。
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab.entries.forEach { item ->
                val selected = item == current
                // 选中底色走 120ms 淡入而不是瞬间跳变：侧栏七行挨着，瞬变看起来像点了没点上
                // 又补了一下，而短一点的颜色过渡能让人确认「点的就是这行」。
                val bg by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                    motionTween(Motion.FAST),
                    label = "tabBg",
                )
                val tint by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    motionTween(Motion.FAST),
                    label = "tabTint",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg, MaterialTheme.shapes.small)
                        // 只切一级，不动二级：subSelection 按 Tab 名分开存，
                        // 「下载 → 服务端」与「设置 → Java」互不影响；从没选过的 Tab
                        // 取不到值，分发时那个 else 分支就已经落到第一项了。
                        .clickable { onSelect(item) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = tint,
                    )
                }
                // 二级只在选中时展开：七项里只有两项有子项，全展开会让侧栏长出一倍，
                // 手机横屏那点高度不够；收起时侧栏还是七行，跟用户要的一样清爽。
                if (selected) {
                    item.subItems.forEach { (key, label) ->
                        val childSelected = key == currentSub
                        val subBg by animateColorAsState(
                            if (childSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
                            motionTween(Motion.FAST),
                            label = "subBg",
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(subBg, MaterialTheme.shapes.small)
                                .clickable { onSelectSub(key) }
                                .padding(start = 25.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (childSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (childSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }

        // 版本号与免责声明固定在底部。
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 20.dp)) {
            Text(
                text = "v" + Brand.version,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = Brand.DISCLAIMER_SHORT,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstancePage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by rememberFileAccessState(context)
    var root by remember { mutableStateOf(defaultGameRoot()) }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Instance?>(null) }
    // 分组对话框的目标。与桌面一样单独存一个实例而不是布尔，理由见 [groupBySection] 注释。
    var groupTarget by remember { mutableStateOf<Instance?>(null) }
    // 默认不分段：老用户进来看到的应该还是原来那一整片，突然被切成几段会以为
    // 列表错乱或实例丢了。只有手动点过「按组摆」之后才按组排。
    var groupBySection by remember { mutableStateOf(false) }
    // 实例一多，列表就得能搜：手机上滚一屏只放得下四五条，
    // 二十多个版本时靠滑很难找到「前几天刚装的那个」。
    var query by remember { mutableStateOf("") }

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
                val merged = mergeInstances(registered, scanned.instances, scanned.libraryVersions)
                // 登记的实例可能早就在资源管理器里被删掉了：扫描那一半反映磁盘现状，
                // 登记那一半是 instances.json 里的老数据，不判一次就永远留在列表里，
                // 点进去是一页空白、点启动要等几秒才报「目录不存在」。
                // 只摘展示、不写盘 —— 盘可能只是暂时不在（移动硬盘没插、U 盘没挂上），
                // 真把登记删了，插回来的人要重新配一遍内存与 Java。
                val pruned = pruneMissing(merged, scanned.versionNames)
                scanned.copy(
                    instances = pruned.kept,
                    warnings = scanned.warnings + listOfNotNull(missingNotice(pruned.missing)),
                )
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

    /**
     * 收藏 / 取消收藏一个实例。
     *
     * 落盘写的是 `instances.json`，不是版本目录下的 ini —— 那个目录可能同时被
     * 别的启动器用着，往里塞私货是污染（PCL 写在 `PCL/Setup.ini`，我们刻意不跟）。
     *
     * 先就地改列表让星立刻填充，再后台写盘：手机上点一下要等 IO 的话，
     * 会以为没点中。写失败就把原样刷回来，并留一句说明。
     */
    fun togglePin(instance: Instance) {
        val updated = instance.copy(pinned = !instance.pinned, registered = true)
        result = result?.let { r ->
            r.copy(instances = r.instances.map { if (instanceKey(it) == instanceKey(updated)) updated else it })
        }
        selected = selected?.let { if (instanceKey(it) == instanceKey(updated)) updated else it }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(File(root.trim())))
                        .save(updated)
                }.isSuccess
            }
            if (!ok) {
                result = result?.let { r ->
                    r.copy(instances = r.instances.map { if (instanceKey(it) == instanceKey(instance)) instance else it })
                }
            }
            scan()
        }
    }

    /**
     * 存分组。
     *
     * 与 [togglePin] 同一套：先就地改列表，让段头和卡片上的组名立刻反映出来，
     * 再后台写盘；失败就把原样刷回来 + 留一句说明。手机上点完看不见变化，用户会当成没点中。
     *
     * 写的是 [withGroup] 的结果（顺手 registered=true）：只扫描到的外部实例不在
     * instances.json 里，不登记下次扫描分组就丢。
     */
    fun saveGroup(instance: Instance, group: String) {
        val updated = withGroup(instance, group)
        if (updated.group == instance.group) {
            // 组名没变（或是只多了首尾空白）：不用写盘，直接收起对话框。
            groupTarget = null
            return
        }
        result = result?.let { r ->
            r.copy(instances = r.instances.map { if (instanceKey(it) == instanceKey(updated)) updated else it })
        }
        selected = selected?.let { if (instanceKey(it) == instanceKey(updated)) updated else it }
        if (updated.group.isNotEmpty()) groupBySection = true
        groupTarget = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(File(root.trim())))
                        .save(updated)
                }.isSuccess
            }
            if (!ok) {
                result = result?.let { r ->
                    r.copy(instances = r.instances.map { if (instanceKey(it) == instanceKey(instance)) instance else it })
                }
                Notices.push(
                    "分组没存上",
                    "「" + instance.name + "」的分组只在这一页生效，下次扫描会回到原来的样子。",
                )
            }
            scan()
        }
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
            onDeleted = {
                // 版本目录没了：退出详情页，重扫一遍把这条从列表里去掉。
                // 不重扫的话 list 里还留着它，点进去会显示「正在读实例目录…」然后读到空目录。
                selected = null
                result = result?.let { r ->
                    r.copy(instances = r.instances.filterNot { instanceKey(it) == instanceKey(open) })
                }
                scan()
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
                    // 搜索框只在真有东西可搜时出现：一条实例的目录里，多一个框只是噪音。
                    if (current.instances.size > 4) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("搜实例（名字、版本号、加载器、目录）") },
                        )
                    }
                    val shown = InstanceCatalog.search(current.instances, query)
                    // 卡片只写一遍，分段与不分段共用：以后给卡片加回调动一处就够，
                    // 写两遍迟早只改了一处。
                    val card: @Composable (Instance) -> Unit = { instance ->
                        InstanceCard(
                            instance = instance,
                            onClick = { selected = instance },
                            onTogglePin = { togglePin(instance) },
                            onGroup = { groupTarget = instance },
                        )
                    }
                    // 组名取全量不取搜索结果：当前搜索词可能把「Mod」那几条筛掉了，
                    // 但它仍然是存在的组，改组时不该看不见。
                    val knownGroups = groupNames(current.instances)
                    val sections = if (groupBySection) sectionize(shown) else emptyList()
                    if (knownGroups.isNotEmpty()) {
                        // 「按组摆」只在真有组时才给。一个组都没有的时候，这个开关点了
                        // 也和现在一样，放那儿只是让人觉得少点了什么。
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { groupBySection = !groupBySection }) {
                                Text(if (groupBySection) "不分段" else "按组摆")
                            }
                            Text(
                                if (query.isBlank()) "共 " + knownGroups.size + " 个组"
                                else "共 " + knownGroups.size + " 个组，下面只列搜到的",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (shown.isEmpty()) {
                        Text(
                            "没有一条对得上「" + query + "」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!groupBySection) {
                            items(shown, key = { instanceKey(it) }) { instance ->
                                card(instance)
                            }
                        } else {
                            sections.forEach { section ->
                                item(key = "section:" + section.name) {
                                    GroupHeader(name = section.name, count = section.instances.size)
                                }
                                items(section.instances, key = { instanceKey(it) }) { instance ->
                                    card(instance)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 对话框放在页面最后、且拿 pending 快照：分组保存后会改写 result 里的同一实例，
    // 直接读 groupTarget 会在保存那一刻闪一下新名字再关掉。
    val pendingGroup = groupTarget
    if (pendingGroup != null) {
        GroupDialog(
            instanceName = pendingGroup.name,
            current = pendingGroup.group,
            knownGroups = groupNames(result?.instances.orEmpty()),
            onDismiss = { groupTarget = null },
            onConfirm = { picked -> saveGroup(pendingGroup, picked) },
        )
    }
}

/**
 * 分段列表里的组标题行。
 *
 * 未分组那段没有名字，写「未分组」而不是空着：空着看起来像列表中间断了一截。
 */
@Composable
private fun GroupHeader(name: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (name.isEmpty()) "未分组" else name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (name.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            count.toString() + " 个",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstanceCard(
    instance: Instance,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onGroup: () -> Unit,
) {
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
                // 玩过几次之后，列表上真正想找的是「上次那个」。没玩过的那些不写这一行，
                // 免得每张卡都挂一句「还没启动过」把真正有信息的那几行挤淡。
                if (instance.lastPlayedAt > 0L) {
                    Text(
                        "上次玩 " + lastPlayedText(instance.lastPlayedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 组名带「组」字前缀：光秃秃一个词（尤其叫「1.20」这种）会和版本名混成一片。
                if (instance.group.isNotBlank()) {
                    Text(
                        "组 " + instance.group,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 星放在徽标上方：星是用户的选择，徽标是机器的事实，两者不该并成一排。
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PinStar(pinned = instance.pinned, onClick = onTogglePin)
                LoaderBadge(instance.loader)
                // 分组按钮与星、徽标同列：卡片主体点一下是进详情，分组是卡片上的
                // 第二个动作，摆在右边竖列的末尾，手指不会在滑动时误触。
                TextButton(onClick = onGroup) {
                    Text(
                        if (instance.group.isBlank()) "分组" else "改组",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
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

        DownloadGuide()

        HorizontalDivider()

        UpdateSection()

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
 * 怎么下载。
 *
 * 手上这台已经装好了，这一页要回答的是另外两件事：桌面端去哪拿、以及「安卓还有一条孵化版」这回事。
 * 安卓的孵化版不发安装包（只公开源码），所以这里只提一句，不指向发布页给空承诺。
 *
 * 跟桌面端 `ui/About.kt` 的 `DownloadGuide` 说同一套话，只是排得紧一点。
 */
@Composable
private fun DownloadGuide() {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("怎么下载", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "桌面的 Windows / Linux 版本与安卓版都放在同一个发布页，仓库里只有源码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "桌面端分两条线。正式版是完整包，内置应用 ID 与服务地址，装完就能联网。" +
                    "孵化版发布出去的那一份是空壳，应用 ID 与下载地址都得自己填；" +
                    "填过一次就一直记住，程序升级不会冲掉。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "安卓端以正式版为主，另一条线也是孵化版（也就是开源版），只公开源码、不发安装包，想要就自己编一份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { openGuideUrl(context, Brand.RELEASES) }) {
                Text("打开发布页")
            }
        }
    }
}

/** 用系统浏览器打开。跟 `UpdateSection.kt` 里那个同名函数各写一份：那边是私有实现，跨文件用不了。 */
private fun openGuideUrl(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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

/**
 * 外部存储根（`/storage/emulated/0`）。
 *
 * 拿不到时退回 `/sdcard` 这层软链：宁可给一条可能不存在的路径，也不要让各页面
 * 拿到空串 —— 空的 `File("")` 指向的是进程的当前目录，那会把东西写到谁也找不到的地方。
 */
@Suppress("DEPRECATION")
internal fun storageRoot(): File = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
    ?: File("/sdcard")

/**
 * 默认游戏目录。
 *
 * 规则在 [GameRoots.androidGameDir]：老位置（`/sdcard/.minecraft`、PojavLauncher 的目录）
 * 里已经有东西就继续用老的，一个都没有才落到 `/sdcard/Platypus/game`。**不静默搬家** ——
 * 升级一下就把用了半年的目录换掉，用户第一眼只会觉得存档全丢了。
 */
internal fun defaultGameRoot(): String = GameRoots.androidGameDir(storageRoot()).absolutePath
