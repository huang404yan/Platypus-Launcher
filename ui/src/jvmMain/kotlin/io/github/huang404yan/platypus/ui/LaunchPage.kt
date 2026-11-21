package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.auth.AccountBootstrap
import io.github.huang404yan.platypus.core.auth.ClientIds
import io.github.huang404yan.platypus.core.auth.OfflineProfile
import io.github.huang404yan.platypus.core.auth.StoredAccount
import io.github.huang404yan.platypus.core.edition.Edition
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.MemoryDefaults
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.ScanResult
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.instance.mergeInstances
import io.github.huang404yan.platypus.core.instance.pruneMissing
import io.github.huang404yan.platypus.core.launch.CrashReport
import io.github.huang404yan.platypus.core.launch.CrashReports
import io.github.huang404yan.platypus.core.launch.GameHandle
import io.github.huang404yan.platypus.core.launch.LaunchArguments
import io.github.huang404yan.platypus.core.launch.LaunchDiagnosis
import io.github.huang404yan.platypus.core.launch.LaunchEvent
import io.github.huang404yan.platypus.core.launch.LaunchRequest
import io.github.huang404yan.platypus.core.launch.RunningGames
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.runtime.JavaRecommender
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.runtime.findExactMajor
import io.github.huang404yan.platypus.core.saves.AutoBackupPolicy
import io.github.huang404yan.platypus.core.saves.SavesAutoBackup
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import io.github.huang404yan.platypus.core.settings.normalizeOfflineUsername
import io.github.huang404yan.platypus.core.version.parseVersionJson
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 启动页：选实例 → 选 Java → 起进程 → 看日志。内存不在这里调（在「设置 → 启动」里定默认值），
 * 这一页只在启动前把生效值对齐到实例上，具体见 [resolveLaunchInstance]。
 *
 * 实例来自两处：本地登记（[io.github.huang404yan.platypus.core.instance.InstanceRepository]）
 * 与目录扫描（[InstanceScanner]），前者优先、后者补充，互不覆盖。
 *
 * 线程模型：GameRuntime 的 onEvent 来自 IO 线程，这里直接写 Compose 快照状态
 * （快照状态本身线程安全），读日志的循环留在后端，界面不做轮询。
 */
internal const val IDENTITY_MS = "ms"
internal const val IDENTITY_OFFLINE = "offline"
internal const val IDENTITY_AUTH = "auth"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LaunchPage(
    backend: LauncherBackend?,
    initialGameRoot: String?,
    /** 从实例页点「去启动」时带过来的实例键；只有它能对上时才会改变默认选中项。 */
    focusKey: String? = null,
    /** 从联机页点「直接进」时带过来的服务器地址；非空就启动后直达那个世界。 */
    quickPlayServer: String = "",
    /**
     * 打开「版本选择」副页面（照 PCL 的 PageLaunchLeft 里那个「版本选择」按钮）。
     *
     * 实例列表已经不在这一页上了 —— 它挪去了 App 层的副页面，因为「这一版怎么启动」
     * 和「我盘上有哪些版本」是两件事，挤在同一栏里谁都不够用。
     */
    onOpenSelect: () -> Unit = {},
    /** 打开「版本设置」副页面：对当前选中的实例做精细设置。 */
    onOpenSetup: (Instance) -> Unit = {},
    /** 身份三格底下的「管理账号 →」，直接落到「设置 → 账号」。 */
    onOpenAccountPage: () -> Unit = {},
    /**
     * 副页面（版本选择 / 版本设置）关掉后加一，这一页据此重扫。
     *
     * 为什么需要：版本选择页里「登记」或「新建」只写进 instances.json，启动页手里那份
     * registered 是打开副页面之前的快照。没有这个信号，登记完回到主页看不到新版本 ——
     * 用户 2026-11-16 报的那条 bug 就是这个。
     */
    refreshTick: Int = 0,
    /**
     * 「这轮运行里哪些版本还在跑」。
     *
     * 由 App 层持有并传进来，不在这一页里 new：切页会把这一页整个销毁，
     * 记在这儿的话，用户去下载页装个模组再回来，游戏还在跑、界面却说没在跑，
     * 于是又点一次启动 —— 盘上多一个 java 进程，两份进程抢同一份存档。
     */
    runningGames: RunningGames = remember { RunningGames() },
    /**
     * [runningGames] 每次增减都会加一，用来触发重组。
     *
     * 那张表是普通对象不是 Compose 快照状态：它要能在非 Compose 的回调（进程退出）
     * 里安全地改，同时又不该让 ui 模块去依赖 state 之外的线程模型。用一个计数器当信号源
     * 是这类「外部可变的共享事实」最省事也最不容易错的做法。
     */
    runningTick: Int = 0,
    /**
     * 界面模式。
     *
     * 简洁模式把「游戏目录与 Java」整块收起来（见 [UiPolicy.showsGameDirAndJava]）：
     * 那是装好之后基本不再动的两件东西，真要改去「版本设置」里还有一份完整的。
     * 这一页仍要收这个参数，因为提示文案得跟着变 —— 否则简洁模式下会说「点下面某一块」，
     * 而那一块根本不在。
     */
    uiMode: UiMode = UiMode.DEFAULT,
) {
    if (backend == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("启动", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "后端未装配：启动页需要桌面端注入 Java 探测与游戏运行实现。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }

    val scope = rememberCoroutineScope()

    var rootText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    var scanned by remember { mutableStateOf<ScanResult?>(null) }
    var registered by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var selected by remember { mutableStateOf<Instance?>(null) }

    var javaPhase by remember { mutableStateOf<JavaPhase>(JavaPhase.Idle) }
    var selectedJava by remember { mutableStateOf<JavaRuntime?>(null) }

    // 启动页不设内存了（2026-11-16 用户定）：给多少内存在「设置 → 启动 → 默认内存」里一次定好，
    // 实例跟着那个默认值走（见 Instance.effectiveMemoryMb）；要给某一版单独设，就在卡片底部的
    // 「版本设置」里改 —— 那里和设置页写的是同一份数据，不会出现两处数不一样。
    //
    // 但启动这一下仍要把生效值写回实例：-Xmx 只认实例上的 memoryMb（LaunchArguments 就是这么拼的）。
    // 少了这一步，「上次把这版设成 8192、后来在设置里把默认值改成 4096」会继续给 8192。
    // 所以下面 resolveLaunchInstance() 在点启动时对齐一次，其余时候启动页不碰内存。
    var defaultMemoryMb by remember { mutableStateOf(Instance.DEFAULT_MEMORY_MB) }

    // 上次启动过的实例（设置里的 lastInstanceKey）。扫完目录后用它兜底选中 ——
    // 不记这个的话，每次开程序都停在按名字排第一的那个版本上。
    var lastInstanceKey by remember { mutableStateOf("") }

    // 设置里的 microsoftClientId 原值。只有孵化版（开源版）拿它做启动门禁：
    // 仓库里不带 Client ID，没填就登不了正版，给了也会在登录时才失败 —— 不如提前拦住。
    var clientIdValue by remember { mutableStateOf("") }
    // 进游戏用哪个语言（设置 → 启动 → 游戏语言）。空串 = 不插手 options.txt。
    var gameLanguage by remember { mutableStateOf("") }
    // 侧栏那个「测试启动」勾选：孵化版没填 ID 时唯一的绕行口，只跳门禁，不修登录。
    var testLaunch by remember { mutableStateOf(false) }

    var username by remember { mutableStateOf("Dev") }
    // 上次从设置文件里读到的名字；启动时只有在用户改过名的情况下才回写，避免每次启动都写盘
    var savedUsername by remember { mutableStateOf("") }
    // 设置里选中的账号；null = 离线模式。令牌不在这里，只有 UUID 与名字。
    var activeAccount by remember { mutableStateOf<StoredAccount?>(null) }
    // 账号文件里的全部身份。启动页要能换身份，就得人手一份名单 ——
    // 只有一个 activeAccount 的话，用户还得跑去「设置 → 账号」才知道自己有几个号。
    var accountList by remember { mutableStateOf<List<StoredAccount>>(emptyList()) }
    // 身份三格当前在哪一格（正版 / 离线 / 第三方）。与 activeAccount 分开：
    // 切格子是「我要看这一类身份」，选行是「就用这个号」，两件事。
    // 初始格在读完设置后按当前身份定（见 LaunchedEffect(Unit)）。
    var identityTab by remember { mutableStateOf(IDENTITY_MS) }
    // 主区的「游戏目录与 Java」默认收起。简洁模式整块不出现（见 UiPolicy.showsGameDirAndJava）。
    var showAdvanced by remember { mutableStateOf(false) }
    val showsDirAndJava = UiPolicy.showsGameDirAndJava(uiMode)
    var phase by remember { mutableStateOf<LaunchPhase>(LaunchPhase.Idle) }
    var stageText by remember { mutableStateOf("") }
    // 启动阶段的假进度（准备版本文件 0.05 → 解压原生库 0.15 → 起进程 0.2）。
    // 浮层上的进度条就吃这个数：它不是真实百分比，但能让「点了之后有没有动静」有个可见的回答。
    var stageFraction by remember { mutableStateOf(0f) }
    // 浮层上点了「取消启动」。这里只记一个意愿，不直接砍协程：
    // DesktopGameRuntime.launch 在 withContext 里同步起进程，中途取消会让 withContext 抛异常、
    // 已经起来的 java 进程再也拿不到句柄，只能等用户自己去任务管理器结束它。
    // 所以改成「先记下来，等 launch 返回句柄时立刻 stop()」。
    var launchCancelRequested by remember { mutableStateOf(false) }

    val logs = remember { mutableStateListOf<String>() }
    val buffer = remember { LogBuffer() }
    var handle by remember { mutableStateOf<GameHandle?>(null) }
    /**
     * 这一页的日志 / 阶段条现在归属哪一个实例。
     *
     * 同一页先后可以起好几个实例（见 RunningGames），但界面只有一份日志区：
     * 不区分归属的话，A 的日志和 B 的日志会混在同一列里，读起来是一份谁都不是的东西。
     * 所以只有「当前归属」那一个的事件才写界面状态，其余的只更新 RunningGames。
     */
    var logOwnerKey by remember { mutableStateOf("") }
    var menuReached by remember { mutableStateOf(false) }
    // 日志区按钮的一次性反馈（复制/导出成功或失败），不做气泡通知，一行字够了
    var notice by remember { mutableStateOf("") }
    // 本次启动的开始时刻：退出后只认比它新的 crash-report，
    // 否则实例上一次崩过的话，这次正常退出也会把旧报告当成新的挂出来。
    var startedAt by remember { mutableStateOf(0L) }
    // 退出后自动备份的结果，一行字贴在状态行下面；没有备份（关着 / 没到间隔 / 没事可做）就留空
    var autoBackupNotice by remember { mutableStateOf("") }
    // 联机页点「直接进」带过来的地址。用户清掉（或换实例）就回到普通启动。
    var quickTarget by remember { mutableStateOf(quickPlayServer.trim()) }
    LaunchedEffect(quickPlayServer) { quickTarget = quickPlayServer.trim() }
    // 这个版本的版本文件认不认 --quickPlayMultiplayer（1.20 起才有）。
    // 先问清楚再给按钮，比让用户点完发现停在主菜单强。
    var quickSupported by remember { mutableStateOf(true) }
    LaunchedEffect(selected?.directory) {
        val instance = selected ?: return@LaunchedEffect
        // 读版本 json 要碰磁盘（几百 KB 的 json 在机械盘上不是瞬时的），
        // 放在组合线程里会让「切实例」这一下卡一帧。
        quickSupported = withContext(Dispatchers.IO) {
            runCatching {
                val json = File(File(instance.directory), File(instance.directory).name + ".json")
                if (!json.isFile) return@runCatching true
                LaunchArguments.supportsQuickPlay(parseVersionJson(json.readText()))
            }.getOrDefault(true)
        }
    }

    /** 扫描目录 + 读登记，登记的排在前面。 */
    suspend fun rescan() = coroutineScope {
        // 每次重扫顺手对一次「还在跑」的账：进程退出事件会丢（外面杀了、启动器崩过），
        // 丢一次界面就把那一版永远画成运行中，按钮也永远停在「结束这个」上。
        // 放在这里而不是定时轮询：重扫是「用户正在看这份列表」的信号，够频、也够省。
        runningGames.pruneDead()
        val text = rootText.trim()
        // 扫目录与读登记表互不依赖，并行发起：扫目录要遍历 versions/ 下几十个 json，
        // 读登记表只是一次小文件读取。串着做的话打开启动器时那段等待是两份相加。
        val result = async(Dispatchers.IO) {
            if (text.isEmpty()) null else InstanceScanner.scan(File(text))
        }
        val saved = async(Dispatchers.IO) {
            runCatching { backend.instances.list() }.getOrDefault(emptyList())
        }
        val scannedResult = result.await()
        val savedList = saved.await()
        scanned = scannedResult
        // 登记的实例可能早就在资源管理器里被删了：只摘展示、不写盘（理由见 pruneMissing）。
        val live = pruneMissing(savedList, scannedResult?.versionNames)
        registered = live.kept
        val merged = mergeInstances(
            live.kept,
            scannedResult?.instances.orEmpty(),
            scannedResult?.libraryVersions.orEmpty(),
        )
        val focused = focusKey?.takeIf { it.isNotBlank() }?.let { key ->
            merged.firstOrNull { instanceKey(it) == key }
        }
        val previous = selected?.let { current ->
            merged.firstOrNull { instanceKey(it) == instanceKey(current) }
        }
        // 选中优先级（照 PCL 的「记住上次玩的版本」）：实例页点名要启动的那个
        // > 用户当前手选的 > 上次启动过的那个 > 列表第一个。
        val last = lastInstanceKey.takeIf { it.isNotBlank() }?.let { key ->
            merged.firstOrNull { instanceKey(it) == key }
        }
        selected = focused ?: previous ?: last ?: merged.firstOrNull()
    }

    /** 版本 json 里声明的 javaVersion.component；读不到就返回 null，由后端按主版本推。 */
    suspend fun javaComponentOf(instance: Instance): String? = withContext(Dispatchers.IO) {
        runCatching {
            val json = File(File(instance.directory), File(instance.directory).name + ".json")
            if (!json.isFile) return@withContext null
            parseVersionJson(json.readText()).javaVersion?.component?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * 静默装一份托管 Java：进度照常打在 [javaPhase] 上，失败不弹窗、不留错误页。
     *
     * 「探测不到任何 Java」本来就是个空列表，界面上那句话还在（「没有找到任何 Java」），
     * 用户看到的是它在自己下载 —— 这正是要的效果。装失败也只是回到空列表 + 原来那句提示，
     * 他仍可以点「自动补 Java N」重试（那里会把失败原文显示出来）。
     */
    fun installJavaSilently(major: Int) {
        if (javaPhase is JavaPhase.Installing) return
        javaPhase = JavaPhase.Installing(0f, "本机还没有 Java，正在自动准备 Java " + major)
        scope.launch {
            try {
                backend.javaRuntimes.ensureRuntime(major, null) { fraction ->
                    javaPhase = JavaPhase.Installing(fraction, "正在下载 Java " + major)
                }
                val refreshed = runCatching { backend.javaRuntimes.detect(useCache = false) }
                    .getOrDefault(emptyList())
                javaPhase = JavaPhase.Done(refreshed)
            } catch (t: Throwable) {
                val fallback = runCatching { backend.javaRuntimes.detect(useCache = false) }
                    .getOrDefault(emptyList())
                javaPhase = JavaPhase.Done(fallback)
            }
        }
    }

    /**
     * 探测本机 Java；一个都没有就顺手补一份 Java [DEFAULT_JAVA_MAJOR]。
     *
     * 自动补只在「一份 Java 都探测不到」时触发，不是「缺这个版本要的那个」：用户机器上已经有
     * Java 21 而要玩 1.20.1（要 17）时，悄悄下 150 MB 太越界 —— 那种情况留在界面上让他点「自动补」。
     */
    fun detectJava() {
        if (javaPhase is JavaPhase.Detecting) return
        javaPhase = JavaPhase.Detecting
        scope.launch {
            val found = try {
                // 这是「探一次」的显式动作（打开启动器 / 点重新探测），绕开缓存：
                // 用户在这个页面上最常问的就是「我刚装的 Java 怎么没出来」。
                backend.javaRuntimes.detect(useCache = false)
            } catch (t: Throwable) {
                // 探测失败不能当成「没有 Java」，原文要原样带到界面上
                javaPhase = JavaPhase.Failed(t.message ?: t.javaClass.simpleName, duringInstall = false)
                return@launch
            }
            javaPhase = JavaPhase.Done(found)
            if (found.isEmpty()) installJavaSilently(DEFAULT_JAVA_MAJOR)
        }
    }

    fun installJava() {
        val instance = selected ?: return
        val major = GameJavaRules.requiredMajor(instance.gameVersion)
        if (javaPhase is JavaPhase.Installing) return
        javaPhase = JavaPhase.Installing(0f, "准备下载 Java " + major)
        scope.launch {
            try {
                val runtime = backend.javaRuntimes.ensureRuntime(major, javaComponentOf(instance)) { fraction ->
                    javaPhase = JavaPhase.Installing(fraction, "正在下载 Java " + major)
                }
                // 刚下完，必须看盘上新状态，不能拿缓存里的旧列表把这一份盖掉。
                val refreshed = runCatching { backend.javaRuntimes.detect(useCache = false) }
                    .getOrDefault(emptyList())
                javaPhase = JavaPhase.Done(refreshed)
                selectedJava = runtime
            } catch (t: Throwable) {
                // 下载失败的原文（清单里哪几个文件、什么原因）必须留在界面上
                javaPhase = JavaPhase.Failed(t.message ?: t.javaClass.simpleName, duringInstall = true)
            }
        }
    }

    /**
     * 在启动页直接换身份。写盘只动 activeAccountUuid 一个字段 ——
     * 用 copy 而不是 new，免得把别处刚改过的离线用户名、内存、配色冲掉。
     * 空串 = 离线。换完不用清别的东西：令牌与 jvmArgs 都是 launchGame 里现换现拼的。
     */
    fun pickIdentity(account: StoredAccount?) {
        activeAccount = account
        val value = account?.let { AccountBootstrap.settingValueOf(it) }.orEmpty()
        val repository = backend.settings
        if (repository == null) return
        scope.launch {
            runCatching {
                val stored = withContext(Dispatchers.IO) { repository.load() }
                withContext(Dispatchers.IO) {
                    repository.save(stored.copy(activeAccountUuid = value))
                }
            }
        }
    }

    /**
     * 启动前对齐一次内存：把生效值（跟随默认的实例读设置里的数）落到实例上。
     *
     * 为什么不是「改内存」：启动页不再有内存控件，这里只是让 -Xmx 用上当前生效的数。
     * 跟随默认的实例原样保留 memoryFollowsDefault = true —— 落盘的只是缓存值，
     * 下次改设置它还会跟着变；写 registered = true 是因为点过启动就算登记过
     * （扫到目录里的外部版本，PCL 也是点了启动才接管）。
     *
     * 写盘放在后台协程里，返回值立刻给 launchGame 用：等磁盘不应该是点启动的延迟。
     */
    fun resolveLaunchInstance(): Instance? {
        val target = selected ?: return null
        val effective = Instance.effectiveMemoryMb(target, defaultMemoryMb)
        val updated = target.copy(memoryMb = effective, registered = true)
        selected = updated
        if (updated != target) {
            scope.launch {
                runCatching { backend.instances.save(updated) }
                val saved = runCatching { backend.instances.list() }.getOrDefault(emptyList())
                registered = saved
            }
        }
        return updated
    }

    /**
     * 在启动页给某个版本加 / 摘收藏。
     *
     * 与实例页那颗星是同一个字段（Instance.pinned），所以这里点完不必自己重排：
     * [rescan] 走 mergeInstances，排序里置顶压在最前。写盘用 copy（只改一个字段），
     * 免得把别处刚存的 lastPlayedAt 或内存冲回去。
     */
    fun togglePin(instance: Instance) {
        val updated = instance.copy(pinned = !instance.pinned, registered = true)
        // 先就地改一下列表里这一条，点下去立刻能看到星填充；磁盘失败再重扫回真实状态。
        registered = registered.map { if (it.id == updated.id) updated else it }
        selected = selected?.let { if (it.id == updated.id) updated else it }
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { backend.instances.save(updated) }.exceptionOrNull()
            }
            if (error != null) {
                notice = "收藏没存上：" + (error.message ?: error.javaClass.simpleName)
            }
            rescan()
        }
    }

    /**
     * 退出后跑一轮自动备份，返回给界面看的一句话。
     *
     * 只在这个页面里做，不放到 core 的后台服务里：启动器关掉之后不该还在背后动文件。
     * 崩溃退出（退出码非 0）不进来 —— 见调用处的理由。
     */
    fun runAutoBackup(instance: Instance, policy: AutoBackupPolicy): String {
        if (!policy.enabled) return ""
        return runCatching {
            val layout = InstanceLayouts.of(instance)
            val result = SavesAutoBackup.run(
                gameDir = layout.gameDir,
                gameRoot = layout.gameRoot,
                policy = policy,
            )
            if (result.hasWork) {
                val text = result.summary()
                if (result.backedUp.isEmpty()) "自动备份没做成：$text" else "已自动备份存档：$text"
            } else {
                ""
            }
        }.getOrElse { "自动备份没做成：" + (it.message ?: it.javaClass.simpleName) }
    }

    /**
     * 启动。孵化版（开源版）是空壳：包里既不内置应用 ID，也不带任何下载与搜索服务地址，
     * 没有 ID 时正版登录这条路走不通 —— 默认在这里拦住；
     * [bypassClientGate] 只有侧栏那个「测试启动」勾选会传 true。
     */
    fun launchGame(bypassClientGate: Boolean = false) {
        // 门禁放在最前面（早于写盘与备份）：被拦下时不该顺手改用户的设置文件。
        if (Edition.current() == Edition.PREVIEW && !bypassClientGate) {
            val issue = ClientIds.missingProblem(clientIdValue)
            if (issue != null) {
                phase = LaunchPhase.Failed(
                    "孵化版是开源空壳：包里没有内置的应用 ID，也没有任何下载与搜索服务地址，" +
                        "正版登录这条路走不通，所以没有启动。" +
                        "要联网就得自己填：先去「设置 → 下载 → 服务地址」填一个镜像根，" +
                        "再去「设置 → 账号」填一个自己申请的应用 ID。" +
                        "只想跑这一次，就勾上左栏的「测试启动」。",
                )
                return
            }
        }
        launchCancelRequested = false
        val instance = resolveLaunchInstance() ?: return
        val java = selectedJava ?: run {
            // 按钮正常情况下是禁用的，但内存保存失败或并发改动仍可能走到这里：
            // 不能静默什么都不做，否则用户只会觉得「点了没反应」。
            val needMajor = GameJavaRules.requiredMajor(instance.gameVersion)
            phase = LaunchPhase.Failed(
                "还没选 Java：本机没有 Java " + needMajor + "，先点「自动补 Java " + needMajor + "」，或在列表里选一个。",
            )
            return
        }
        // 名字改过就顺手记住，下次打开还是这个名字；没改就不重复写盘。
        // 「上次启动的版本」也在这一次写盘里带上：两件事各写一遍 settings.json 会互相覆盖
        //（都是「读出来改一个字段再存回去」），合并成一次既少一次 IO，也不会丢字段。
        val cleanedName = normalizeOfflineUsername(username)
        username = cleanedName
        val launchedKey = instanceKey(instance)
        val renameNeeded = cleanedName != savedUsername
        val rememberKeyNeeded = launchedKey != lastInstanceKey
        if (renameNeeded || rememberKeyNeeded) {
            val repository = backend.settings
            if (repository != null) {
                if (renameNeeded) savedUsername = cleanedName
                if (rememberKeyNeeded) lastInstanceKey = launchedKey
                scope.launch {
                    runCatching {
                        val stored = withContext(Dispatchers.IO) { repository.load() }
                        withContext(Dispatchers.IO) {
                            repository.save(
                                stored.copy(
                                    offlineUsername = if (renameNeeded) cleanedName else stored.offlineUsername,
                                    lastInstanceKey = if (rememberKeyNeeded) launchedKey else stored.lastInstanceKey,
                                ),
                            )
                        }
                    }.onFailure {
                        if (renameNeeded) savedUsername = ""
                        if (rememberKeyNeeded) lastInstanceKey = ""
                    }
                }
            }
        }
        logs.clear()
        buffer.clear()
        menuReached = false
        stageText = ""
        stageFraction = 0f
        handle = null
        // 界面上那点东西（日志、阶段条、状态行）从此归这个实例。
        // 别的实例的事件不再写它们 —— 两路日志混成一列，读起来就是一份谁都不是的东西。
        logOwnerKey = launchedKey
        val launchStartedAt = System.currentTimeMillis()
        startedAt = launchStartedAt
        autoBackupNotice = ""
        phase = LaunchPhase.Starting
        val offlineSession = OfflineProfile.session(username.trim().ifBlank { "Dev" })
        val account = activeAccount
        scope.launch {
            try {
                // 备份策略在启动这一刻读一次：用户可能刚在设置里改过间隔，
                // 每次启动重读比缓存到进程里更不容易出现「改了没生效」。
                val backupPolicy = withContext(Dispatchers.IO) {
                    runCatching { backend.settings?.load() }
                        .getOrNull()
                        ?.let { LauncherSettings.policyOf(it) }
                        ?: AutoBackupPolicy.DEFAULT
                }
                // 正版账号每次启动现换一份会话：MC 令牌两小时就过期，缓存下来十个九个是废的。
                // 换不到就停在这儿说清楚 —— 静默退回离线会让人以为自己登录着，
                // 等进了服务器才发现是个离线号，那时候已经说不清了。
                // 外置登录在「验证账号」之外还多一步：往 JVM 参数里挂 authlib-injector。
                // 只拿到令牌而不挂 agent，游戏根本不去用它 —— 玩家会进游戏发现是个离线号。
                val yggdrasil = backend.yggdrasil
                val external = account != null && account.isYggdrasil
                val auth = if (external) yggdrasil ?: backend.auth else backend.auth
                val session = if (account != null && auth != null) {
                    stageText = if (external) "正在验证外置登录账号" else "正在验证正版账号"
                    stageFraction = 0f
                    try {
                        withContext(Dispatchers.IO) { auth.sessionFor(account) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        phase = LaunchPhase.Failed(
                            (if (external) "外置登录验证失败：" else "正版账号验证失败：") +
                                (e.message ?: e.javaClass.simpleName) +
                                " 去「设置 → 账号」重新登录，或者在那里改用离线模式。",
                        )
                        stageText = ""
                        return@launch
                    }
                } else {
                    offlineSession
                }
                // 令牌拿到之后再挂 agent：反过来的话，密码过期也要先下完 jar 才告诉用户。
                val extraJvmArgs = if (account != null && external && yggdrasil != null) {
                    stageText = "正在准备外置登录"
                    try {
                        withContext(Dispatchers.IO) {
                            yggdrasil.launchArgs(account) { text -> stageText = text }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        phase = LaunchPhase.Failed(
                            "外置登录准备失败：" + (e.message ?: e.javaClass.simpleName) +
                                " 去「设置 → 账号」重新登录一次。",
                        )
                        stageText = ""
                        return@launch
                    }
                } else {
                    emptyList()
                }
                val request = LaunchRequest(
                    instance = instance,
                    java = java,
                    session = session,
                    jvmArgs = extraJvmArgs,
                    quickPlayServer = quickTarget,
                    gameLanguage = gameLanguage,
                )
                // 事件回调来自 IO 线程，可能同时有别的实例在跑：只有「当前归属这一版」的事件
                // 才写界面上那点状态，其余实例的只更新运行表。判断放在这里而不是各分支里，
                // 免得将来加一种事件又漏一处。注意 Started / Exited 对每一个实例都要处理
                // （运行表靠它们），真正要分归属的只有日志行与阶段文字。
                val started = backend.gameRuntime.launch(request) { event ->
                    val ownsUi = logOwnerKey == launchedKey
                    when (event) {
                        is LaunchEvent.Stage -> {
                            if (ownsUi) {
                                stageText = event.name
                                // 只前进不后退：解压原生库会边解边报 0.15，回退会让进度条来回抖。
                                if (event.fraction > stageFraction) stageFraction = event.fraction
                            }
                        }
                        is LaunchEvent.Started -> {
                            if (ownsUi) {
                                stageText = "已启动"
                                stageFraction = 1f
                                phase = LaunchPhase.Running(event.pid)
                            }
                            // 「上次玩什么时候」只在这一刻记：进程真的起来了才算玩过。
                            // 放在退出时记会有两个问题 —— 崩在启动阶段的那次也算一次，
                            // 而崩溃时正好不该再动磁盘。写盘失败不拦启动，也不提示：
                            // 它只影响列表排序，不影响这一局。
                            val playedAt = System.currentTimeMillis()
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        backend.instances.save(instance.copy(lastPlayedAt = playedAt))
                                    }
                                }
                            }
                        }
                        is LaunchEvent.LogLine -> {
                            // buffer 只给归属者用：它喂的是「这次启动有没有到主菜单」与崩溃归因，
                            // 别的实例的行混进来，这份归因就不再是这一次的。
                            if (ownsUi) {
                                buffer.add(event.text)
                                logs.add(event.text)
                                if (logs.size > MAX_VISIBLE_LINES) logs.removeAt(0)
                                if (!menuReached && LaunchDiagnosis.reachedMenu(listOf(event.text))) {
                                    menuReached = true
                                }
                            }
                        }
                        is LaunchEvent.Exited -> {
                            // 先摘运行表：不管这一页现在盯着谁，这个进程都确实没了。
                            // 「结束这个」按钮、卡片上的运行中标记都靠它。
                            runningGames.remove(launchedKey)
                            // 退出的不是现在盯着的那一版：界面状态（状态行、崩溃卡片、自动备份）
                            // 都不该被它改掉 —— 那会让用户以为正在玩的那个崩了。
                            if (ownsUi) {
                                stageText = ""
                                // 事件回调本来就在 IO 线程，这里同步读一次崩溃报告（最多 256 KB）即可，
                                // 不额外开协程：退出这一刻界面正等着出结论，异步读会让卡片晚一拍才出现。
                                // 两道闸：退出码为 0 说明游戏自己退干净了；报告还得比本次启动新。
                                val crash = if (event.code == 0) null else runCatching {
                                    CrashReports.read(File(instance.directory))
                                        ?.takeIf { it.modifiedAt >= startedAt - CRASH_MTIME_SLACK_MS }
                                }.getOrNull()
                                phase = LaunchPhase.Exited(
                                    code = event.code,
                                    diagnosis = event.diagnosis,
                                    reachedMenu = LaunchDiagnosis.reachedMenu(buffer.tail(1_000)),
                                    crash = crash,
                                )
                                // 自动备份：只在退出码为 0 时做。非 0 可能是崩在半路，
                                // 那时世界文件未必写完，打出来的 zip 有可能是半份 ——
                                // 备份的意义是留一个能用的还原点，不是留一个刚好坏在那儿的文件。
                                // 回调本就在 IO 线程，这里直接同步跑（与上面读崩溃报告同一个理由）。
                                if (event.code == 0) {
                                    autoBackupNotice = runAutoBackup(instance, backupPolicy)
                                }
                            }
                        }
                    }
                }
                handle = started
                // 登记到「这轮运行里哪些版本在跑」：进程起来了才算，失败的那次不进表。
                // 之后页面上所有「这一版在不在跑」的判断都读这张表，不读这一页的 phase。
                runningGames.put(
                    RunningGames.Entry(
                        key = launchedKey,
                        name = instance.name.ifBlank { instance.id },
                        gameVersion = instance.gameVersion,
                        directory = instance.directory,
                        pid = started.pid ?: -1L,
                        startedAt = launchStartedAt,
                        handle = started,
                    ),
                )
                // 用户在浮层上点了「取消启动」：进程这时才拿到句柄，立刻停它。
                if (launchCancelRequested) {
                    launchCancelRequested = false
                    runCatching { started.stop() }
                    runningGames.remove(launchedKey)
                    handle = null
                    stageText = ""
                    stageFraction = 0f
                    if (phase is LaunchPhase.Running) phase = LaunchPhase.Idle
                }
            } catch (t: Throwable) {
                // 启动失败也要摘表：进程根本没起来，留在表里会让界面一直显示「运行中」。
                runningGames.remove(launchedKey)
                if (logOwnerKey == launchedKey) {
                    phase = LaunchPhase.Failed(t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    /**
     * 结束某个实例的游戏。
     *
     * 默认结束当前选中那一版；卡片上没给 key 时，退回「这一页最近起的那一个」（handle）。
     * 两者都读运行表而不读 phase：phase 只记得最近一次由这一页起的进程。
     */
    fun stopGame(key: String? = null) {
        val targetKey = key ?: selected?.let { instanceKey(it) } ?: return
        val entry = runningGames.get(targetKey)
        if (entry == null) {
            // 表里没有但它还在这页手里（例如登记之前就失败了）：兜底把本地句柄停掉。
            val current = handle ?: return
            scope.launch {
                runCatching { current.stop() }
                handle = null
                if (phase !is LaunchPhase.Exited) phase = LaunchPhase.Idle
            }
            return
        }
        if (logOwnerKey == targetKey) stageText = "正在停止"
        scope.launch {
            runCatching { entry.handle.stop() }
            // 进程可能自己先退了（Exited 里已经摘过），这里再摘一次是无害的幂等操作。
            runningGames.remove(targetKey)
            if (logOwnerKey == targetKey) {
                handle = null
                stageText = ""
                if (phase !is LaunchPhase.Exited) phase = LaunchPhase.Idle
            }
        }
    }

    LaunchedEffect(Unit) {
        // 设置先读：默认游戏目录与离线用户名都要在首次扫描、首次启动前就位。
        val repository = backend.settings
        if (repository != null) {
            val stored = withContext(Dispatchers.IO) { runCatching { repository.load() }.getOrNull() }
            if (stored != null) {
                username = stored.offlineUsername
                savedUsername = stored.offlineUsername
                if (rootText.isBlank() && stored.defaultGameRoot.isNotBlank()) {
                    rootText = stored.defaultGameRoot
                }
                defaultMemoryMb = MemoryDefaults.resolve(stored.defaultMemoryMb, stored.defaultMemoryAuto, SystemMemory.totalMb)
                clientIdValue = stored.microsoftClientId
                gameLanguage = stored.gameLanguage
                // 要在下面的 rescan() 之前就位：它是「扫完后选谁」的一级兜底。
                lastInstanceKey = stored.lastInstanceKey
                val wanted = stored.activeAccountUuid
                val repositoryOfAccounts = backend.accounts
                if (repositoryOfAccounts != null) {
                    val all = withContext(Dispatchers.IO) {
                        runCatching { repositoryOfAccounts.list() }.getOrNull().orEmpty()
                    }
                    accountList = all
                    activeAccount = if (wanted.isBlank()) {
                        null
                    } else {
                        all.firstOrNull { AccountBootstrap.matches(it, wanted) }
                    }
                    // 初始格跟着当前身份走：用着外置号就落在「第三方」格，没登录就落在「离线」格。
                    // 不能死落在「正版」：一个外置账号用户打开启动页，就看不见自己正在用的那个号。
                    identityTab = when {
                        activeAccount == null -> IDENTITY_OFFLINE
                        activeAccount!!.isYggdrasil -> IDENTITY_AUTH
                        else -> IDENTITY_MS
                    }
                }
            }
        }
        // 先起 Java 探测，再扫目录：两者互不依赖，探测要跑注册表查询和若干个 java 进程，
        // 串起来就是「打开启动器 → 先等实例列表、再等 Java 行出现」，两个进度条一前一后。
        // detectJava 内部本来就 scope.launch 立即返回，把顺序换过来就等于让它们并行。
        detectJava()
        if (rootText.isNotBlank()) rescan()
    }

    // 副页面关掉后重扫一次：登记 / 新建 / 删除都发生在那边，这一页的 registered 需要跟上。
    // 同时把设置重读一遍：用户很可能刚在「设置 → 账号」里填了 Client ID，不重读的话
    // 启动按钮会一直灰着，非得重启程序才认。
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) {
            val settingsRepository = backend.settings
            if (settingsRepository != null) {
                val stored = withContext(Dispatchers.IO) { runCatching { settingsRepository.load() }.getOrNull() }
                if (stored != null) {
                    clientIdValue = stored.microsoftClientId
                    gameLanguage = stored.gameLanguage
                    defaultMemoryMb = MemoryDefaults.resolve(stored.defaultMemoryMb, stored.defaultMemoryAuto, SystemMemory.totalMb)
                }
            }
            if (rootText.isNotBlank()) rescan()
        }
    }

    // 选中实例后按游戏版本要求挑一个主版本正好匹配的 Java；没有就不选，由界面提示缺什么。
    // 挑法统一交给 [JavaRecommender]，详情页与设置页说的是同一套话，不会这页说「缺 17」那页说「没有正好是 17 的」。
    LaunchedEffect(selected?.directory, javaPhase) {
        val instance = selected ?: return@LaunchedEffect
        val list = (javaPhase as? JavaPhase.Done)?.runtimes ?: return@LaunchedEffect
        val required = GameJavaRules.requiredMajor(instance.gameVersion)
        val pick = JavaRecommender.recommend(required, list).runtime
        // 挑不到就只在「现有这份主版本还对」时保留它，否则清空 —— 界面靠 selectedJava 为空来提示缺 Java。
        selectedJava = pick ?: selectedJava?.takeIf { it.majorVersion == required }
    }

    // busy 只表示「这一页正在拉起一个进程」。
    //
    // 这里以前把「运行中」也算进去，于是只要有一个游戏在跑，整页的按钮全灰：
    // 想趁游戏跑着再开一个别的版本，或者只是想看一眼别的版本设置，都做不到。
    // 现在运行中不锁页面 —— 要防的只是同一个实例被起两次，那一条由侧栏按钮
    // （selectedRunning 非空时变成「结束这个」）和卡片上的运行中标记来挡。
    val busy = phase is LaunchPhase.Starting

    /** 正在跑的那批（列表用来给卡片打「运行中」标记、侧栏用来显示「共 N 个」）。 */
    val runningEntries = remember(runningTick) { runningGames.all() }
    /** 当前选中这一版的运行记录；非空时左栏按钮改成「结束这个」。 */
    val selectedRunning = remember(runningTick, selected?.directory) {
        selected?.let { runningGames.get(instanceKey(it)) }
    }

    // 孵化版没有内置 Client ID 时，正版登录这条路根本走不通；预先拦住，别让用户在登录那一刻才发现。
    // missingProblem 读的是「内置 + 设置里的」两份来源，用户填过就从这里消失（见 ClientIds）。
    val clientIssue = ClientIds.missingProblem(clientIdValue)
    val clientGatePassed = Edition.current() != Edition.PREVIEW || clientIssue == null
    val requiredMajor = selected?.let { GameJavaRules.requiredMajor(it.gameVersion) }
    val merged = mergeInstances(registered, scanned?.instances.orEmpty(), scanned?.libraryVersions.orEmpty())
    // 本机有没有这一版要的 Java，统一问 JavaRecommender：它把「本机已有优先、没有再看能不能下」
    // 这一套判断收在一处，界面只负责把结论转成人话。
    val recommendation = JavaRecommender.recommend(
        requiredMajor = requiredMajor,
        runtimes = (javaPhase as? JavaPhase.Done)?.runtimes.orEmpty(),
    )
    val exactJava = recommendation.runtime

    // PCL 的启动页是左栏选版本 + 大号启动按钮、右栏放设置与日志（PageLaunchLeft.xaml）。
    // 照这个分法：左栏回答「现在要启动哪一个」，右栏回答「启动过程中与之后发生了什么」。
    // 浮层要盖住整页，所以外面套一层 Box；它只在「正在启动」时出现，跑起来就撤掉。
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LaunchSidePanel(
            selected = selected,
            busy = busy,
            running = selectedRunning != null,
            /** 这一轮里一共有几个进程在跑（含别的版本）。 */
            runningCount = runningEntries.size,
            stageText = stageText,
            username = username,
            onUsernameChange = { username = it },
            accountList = accountList,
            activeAccount = activeAccount,
            onPickIdentity = { pickIdentity(it) },
            identityTab = identityTab,
            onIdentityTab = { identityTab = it },
            onOpenAccountPage = onOpenAccountPage,
            // 选中的这一版已经在跑时，主按钮不能再拉一次：同一个实例起两份会互抢存档目录。
            // 这时左栏下方那颗「结束这个版本的游戏」就是这一版唯一的主动作。
            canLaunch = selected != null && selectedJava != null && (clientGatePassed || testLaunch) &&
                selectedRunning == null,
            // 「按钮是灰的，为什么」——以前只在折叠块里才看得到缺 Java，用户以为是要先填 clientId。
            blockedHint = when {
                selected == null -> "还没选版本。在右边点一张版本卡片。"
                // 已经跑起来的那一版不再重复说明缺什么：此刻按钮灰的原因是「它在跑」，
                // 再讲一遍 Java 或应用 ID 会把人引到错的方向。要结束就点下面的按钮。
                selectedRunning != null -> ""
                // 这条要排在「缺 Java」前面：门禁没过时补 Java 也没用，说了反而误导。
                !clientGatePassed && !testLaunch ->
                    "孵化版是开源空壳：没有内置应用 ID，也没有下载与搜索服务地址，正版登录走不通，所以启动被拦下了。" +
                        "要能联网，先去「设置 → 下载」填服务地址、再去「设置 → 账号」填自己申请的应用 ID；" +
                        "只想跑这一次就勾上上面的「测试启动」。"
                selectedJava != null -> ""
                // 刚进页面时 Java 还在探测、自动挑选还没发生：这时说「缺 Java」是误报，先等等
                javaPhase is JavaPhase.Detecting || javaPhase is JavaPhase.Idle -> ""
                // 简洁模式没有下面那一块，改口指去「版本设置」—— 那里同样能补 Java，
                // 只是要先进去，所以单独写一套文案而不是让用户去找一个不存在的按钮。
                requiredMajor == null -> if (showsDirAndJava) {
                    "这个版本没读到要哪个 Java。展开下面「游戏目录与 Java」手选一个。"
                } else {
                    "这个版本没读到要哪个 Java。去「版本设置 → 启动与 Java」手选一个。"
                }
                (javaPhase as? JavaPhase.Done)?.runtimes?.isEmpty() == true -> if (showsDirAndJava) {
                    "本机一个 Java 都没有。点下面「游戏目录与 Java（点开改）」，里面能一键补 Java。"
                } else {
                    "本机一个 Java 都没有。去「版本设置 → 启动与 Java」，里面能一键补 Java。"
                }
                // 推荐已经说「本机有」，但挑选那一步还没跑（换实例后的同一帧）：
                // 这时说「缺 Java」是误报，等一下就好。
                recommendation.ready -> "正在准备 Java " + requiredMajor + "…"
                // 说法统一由 JavaRecommender 给：它知道本机到底是没有、还是官方清单也没有这一版。
                else -> JavaRecommender.describe(recommendation) + if (showsDirAndJava) {
                    "点下面「游戏目录与 Java（点开改）」，里面能一键补。"
                } else {
                    "去「版本设置 → 启动与 Java」，里面能一键补。"
                }
            },
            onLaunch = { launchGame(bypassClientGate = testLaunch) },
            showTestLaunch = Edition.current() == Edition.PREVIEW,
            testLaunch = testLaunch,
            onTestLaunchChange = { testLaunch = it },
            onStop = { stopGame() },
        )
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("启动", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "点一张卡就是选定它；身份在左边那一排 —— 正版 / 离线 / 第三方，不用进二级菜单找。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 实例卡片网格，照 HTML 预览的主区（dist/xin-jiagou-yulan.html 的 .grid / .icard）。
            // 以前主区是一路往下的表单：目录、Java、直连、日志，用户点「启动」之前要先往下滚。
            // 现在主区回答「盘上有哪些版本、今天玩哪一个」，点一张卡就选定它。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (merged.isEmpty()) "版本" else "版本 · " + merged.size + " 个",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onOpenSelect, enabled = !busy) { Text("管理版本…") }
            }
            if (merged.isEmpty()) {
                SectionHint(
                    "这个目录里没扫到版本。点右上角「管理版本…」去下载、或者换个目录 —— " +
                        if (showsDirAndJava) {
                            "目录在下面的「游戏目录与 Java」里改。"
                        } else {
                            "目录在「设置 → 启动 → 游戏目录」里改。"
                        },
                )
            } else {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    merged.forEach { instance ->
                        val selectedKey = selected?.let { instanceKey(it) }
                        InstanceTile(
                            instance = instance,
                            // 卡片上要写「这一版实际会用多少」：跟随默认的实例，自己存的数不生效，
                            // 直接写 instance.memoryMb 会和设置页里的默认值对不上。
                            effectiveMemoryMb = Instance.effectiveMemoryMb(instance, defaultMemoryMb),
                            selected = selectedKey != null && instanceKey(instance) == selectedKey,
                            // 「这个版本正在跑」跟页面无关，是进程的事实：从这张表读，
                            // 不从这一页的 phase 读 —— 后者只记得「最近一次由这一页起的那个」。
                            running = runningGames.isRunning(instanceKey(instance)),
                            enabled = !busy,
                            onClick = {
                                selected = instance
                            },
                            onOpenSetup = { onOpenSetup(instance) },
                            onStop = { stopGame(instanceKey(instance)) },
                            onTogglePin = { togglePin(instance) },
                        )
                    }
                }
            }
            // 游戏目录与 Java 收成一个可折叠的块：HTML 预览里启动页主区就是「版本卡片 + 直连 + 日志」，
            // 这两项属于「一次配好就不再动」的设置，铺在卡片下面会把卡片挤下去。
            // 折叠不是删除 —— 换目录、补 Java 仍然在这一页，只是要点一下才展开。
            if (showsDirAndJava) {
                TextButton(onClick = { showAdvanced = !showAdvanced }, enabled = !busy) {
                    Text(if (showAdvanced) "收起游戏目录与 Java" else "游戏目录与 Java（点开改）")
                }
            }
            if (showsDirAndJava && showAdvanced) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                // 目录：一处目录决定左栏能选到哪些实例，所以留在内容区顶部而不是左栏。
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
                        enabled = !busy,
                    )
                    OutlinedButton(
                        onClick = {
                            pickDirectory(rootText)?.let {
                                rootText = it.absolutePath
                                scope.launch { rescan() }
                            }
                        },
                        enabled = !busy,
                    ) { Text("选择…") }
                    Button(onClick = { scope.launch { rescan() } }, enabled = !busy) { Text("扫描") }
                }
                // Java 运行时
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Java 运行时", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (requiredMajor != null) {
                        Text(
                            "游戏需要 Java " + requiredMajor,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val installing = javaPhase as? JavaPhase.Installing
                    if (requiredMajor != null && exactJava == null && javaPhase is JavaPhase.Done && !busy) {
                        Button(onClick = { installJava() }, enabled = installing == null) {
                            Text(if (installing != null) "补 Java 中…" else "自动补 Java " + requiredMajor)
                        }
                    }
                    OutlinedButton(
                        onClick = { detectJava() },
                        enabled = javaPhase !is JavaPhase.Detecting && installing == null && !busy,
                    ) {
                        Text(if (javaPhase is JavaPhase.Detecting) "探测中…" else "重新探测")
                    }
                }

                when (val current = javaPhase) {
                    is JavaPhase.Idle -> SectionHint("还没有探测。")
                    is JavaPhase.Detecting -> SectionHint("正在探测本机 Java…")
                    is JavaPhase.Installing -> SectionHint(
                        current.detail + "（" + (current.fraction * 100f).toInt() + "%）",
                    )
                    is JavaPhase.Failed -> SectionHint(
                        (if (current.duringInstall) "补 Java 失败：" else "探测 Java 失败：") + current.message,
                        error = true,
                    )
                    is JavaPhase.Done -> {
                        if (current.runtimes.isEmpty()) {
                            SectionHint("没有找到任何 Java。可以点上面的「自动补 Java」现下一个。", error = true)
                        } else {
                            if (requiredMajor != null && current.runtimes.findExactMajor(requiredMajor) == null) {
                                SectionHint(
                                    "本机没有 Java " + requiredMajor + "。点「自动补 Java " + requiredMajor + "」会从 Mojang 下一份到启动器自己的 java/ 里。",
                                    error = true,
                                )
                            }
                            LazyColumn(
                                Modifier.fillMaxWidth().heightIn(max = 110.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(current.runtimes, key = { it.path }) { runtime ->
                                    JavaRow(
                                        runtime = runtime,
                                        selected = runtime.path == selectedJava?.path,
                                        recommended = runtime.majorVersion == requiredMajor,
                                        enabled = !busy,
                                        onClick = { selectedJava = runtime },
                                    )
                                }
                            }
                        }
                    }
                }

                }
            }
            // 直连：联机页点「直接进」会填到这里，也可以手填
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = quickTarget,
                    onValueChange = { quickTarget = it.trim() },
                    modifier = Modifier.weight(1f),
                    label = { Text("直连服务器（留空 = 停在主菜单）") },
                    singleLine = true,
                    enabled = !busy,
                    supportingText = {
                        Text(
                            if (quickTarget.isBlank()) {
                                "填 192.168.1.10:54321 这种地址，启动后直接进那个世界。"
                            } else {
                                "启动后跳过主菜单，直连这个地址。"
                            },
                        )
                    },
                )
                if (quickTarget.isNotBlank()) {
                    OutlinedButton(onClick = { quickTarget = "" }, enabled = !busy) { Text("清空") }
                }
            }
            if (quickTarget.isNotBlank() && !quickSupported) {
                SectionHint(
                    "这个版本的版本文件里没有 --quickPlayMultiplayer（1.20 起才有），" +
                        "启动时会改用 --server/--port 直连，效果一样（同样跳过主菜单）。",
                )
            }

            StatusLine(phase = phase, stageText = stageText, menuReached = menuReached)

            // 自动备份的结果：只占一行。失败用 error 色（这时通常是备份目录被占住、
            // 或者磁盘满了），成功用 onSurfaceVariant 而不是主色 —— 它不是需要被注意的消息。
            if (autoBackupNotice.isNotBlank()) {
                Text(
                    autoBackupNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (autoBackupNotice.startsWith("自动备份没做成")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // 崩溃报告：退出后才有，读不到就不占位置
            (phase as? LaunchPhase.Exited)?.crash?.let { crash ->
                CrashCard(
                    crash = crash,
                    instanceDir = selected?.let { File(it.directory) },
                    onNotice = { notice = it },
                )
            }

            // 日志
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (logs.isEmpty()) "日志" else "日志 · " + logs.size + " 行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { notice = copyText(logs.joinToString("\n"), "日志") },
                    enabled = logs.isNotEmpty(),
                ) { Text("复制") }
                TextButton(
                    onClick = { notice = exportLogs(selected?.let { File(it.directory) }, logs) },
                    enabled = logs.isNotEmpty() && selected != null,
                ) { Text("导出") }
                TextButton(
                    onClick = { notice = openCrashDir(selected?.let { File(it.directory) }) },
                    enabled = selected != null,
                ) { Text("打开崩溃报告目录") }
            }
            NoticeFade(notice) { shown ->
                Text(
                    shown,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 固定高度而不是 weight(1f)：外层已经是可滚动 Column，weight 在滚动容器里拿不到
            // 有界高度；日志列表本身用 LazyColumn，也必须有个确定的上界才能滚动。
            Surface(
                Modifier.fillMaxWidth().height(240.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                if (logs.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text(
                            "启动后游戏的 stdout/stderr 会实时出现在这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    AutoScrollLogList(
                        itemCount = logs.size,
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                    ) {
                        items(logs) { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
        if (phase is LaunchPhase.Starting) {
            LaunchOverlay(
                instanceName = selected?.name.orEmpty(),
                stageText = stageText,
                fraction = stageFraction,
                onCancel = {
                    launchCancelRequested = true
                    stageText = "正在取消"
                },
            )
        }
    }
}

private const val MAX_VISIBLE_LINES = 500

/** 「一个 Java 都没有」时默认装哪个主版本。17 是 1.18–1.20.4 的要求，也是整合包最常用基线；25 只对 1.21.9+ 有用。 */
private const val DEFAULT_JAVA_MAJOR = 17

/**
 * 允许报告比启动时刻早一点：文件系统时间戳精度、以及崩溃写盘到进程真正退出之间的几十毫秒。
 * 取 5 秒 —— 比它早的报告一定来自更早的一次启动。
 */
private const val CRASH_MTIME_SLACK_MS = 5_000L

/** 复制到剪贴板；失败不抛，返回一行提示。 */
private fun copyText(text: String, label: String): String {
    if (text.isBlank()) return label + "是空的，没有可复制的内容。"
    val ok = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }.isSuccess
    return if (ok) "已复制" + label + "（" + text.length + " 字符）" else "复制失败，请手动选中再复制。"
}

/** 导出日志到实例目录下 logs/platypus-时间.log，返回一行提示。 */
private fun exportLogs(instanceDir: File?, logs: List<String>): String {
    if (instanceDir == null) return "没有选中实例，无法确定导出位置。"
    if (logs.isEmpty()) return "日志是空的，没有可导出的内容。"
    val dir = File(instanceDir, "logs")
    if (!dir.exists() && !dir.mkdirs()) return "建不了目录：" + dir.absolutePath
    val name = "platypus-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log"
    val target = File(dir, name)
    return runCatching {
        target.writeText(logs.joinToString("\n"), Charsets.UTF_8)
        "已导出到 " + target.absolutePath
    }.getOrElse { "导出失败：" + (it.message ?: it.javaClass.simpleName) }
}

/** 打开实例目录下的 crash-reports/；目录不存在时先不创建，直接提示。 */
private fun openCrashDir(instanceDir: File?): String {
    if (instanceDir == null) return "没有选中实例，无法定位崩溃报告目录。"
    val dir = CrashReports.dirOf(instanceDir)
    if (!dir.exists()) return "这个实例还没有 crash-reports 目录（没崩过，或报告被清掉了）。"
    val ok = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(dir)
            true
        } else false
    }.getOrDefault(false)
    return if (ok) "已打开 " + dir.absolutePath else "打不开资源管理器，路径是：" + dir.absolutePath
}

/**
 * 报告文件读出来给「复制全文 / 查看全文」用；读失败返回一句说明，不抛。
 * 只取前 256 KB —— 卡死循环的 mod 能把 crash-report 写到几十 MB，界面里逐行铺开会直接卡住。
 */
private fun File.readTextSafe(): String = runCatching {
    val text = readText(Charsets.UTF_8)
    if (text.length > 256 * 1024) text.take(256 * 1024) + "\n…（已截断，全文见文件本身）" else text
}.getOrElse { "读取失败：" + (it.message ?: it.javaClass.simpleName) }

private fun formatStamp(millis: Long): String =
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}

/** Java 探测的四种状态；探测失败不能当成「没有 Java」。 */
private sealed interface JavaPhase {
    data object Idle : JavaPhase
    data object Detecting : JavaPhase
    data class Done(val runtimes: List<JavaRuntime>) : JavaPhase
    data class Installing(val fraction: Float, val detail: String) : JavaPhase

    /** [duringInstall] 区分「探测本机 Java 失败」与「补 Java 下载失败」——两者诱因完全不同。 */
    data class Failed(val message: String, val duringInstall: Boolean) : JavaPhase
}

/** 一次启动的生命周期。 */
private sealed interface LaunchPhase {
    data object Idle : LaunchPhase
    data object Starting : LaunchPhase
    data class Running(val pid: Long) : LaunchPhase
    /**
     * [crash] 是退出后从实例目录 crash-reports/ 里读到的最新一份报告。
     * 读不到（没装模组、Java 崩在写报告之前、目录被清理过）时保持 null，界面不显示崩溃卡片。
     */
    data class Exited(
        val code: Int,
        val diagnosis: String?,
        val reachedMenu: Boolean,
        val crash: CrashReport? = null,
    ) : LaunchPhase
    data class Failed(val message: String) : LaunchPhase
}

/** 归因用的日志尾部；界面只显示最近若干行，判定「是否进过主菜单」要更长的历史。 */
private class LogBuffer {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun tail(count: Int): List<String> = lines.toList().takeLast(count)

    @Synchronized
    fun clear() = lines.clear()

    private companion object {
        const val CAPACITY = 5_000
    }
}

/**
 * 崩溃报告卡片：只显示摘要（文件、时间、Description、第一个异常），
 * 整份报告留给「打开目录」去看 —— 界面上摊开 256 KB 栈对用户没有意义。
 */
@Composable
private fun CrashCard(crash: CrashReport, instanceDir: File?, onNotice: (String) -> Unit) {
    var expanded by remember(crash.file.path) { mutableStateOf(false) }
    // 报告全文只读一次：展开时读盘，之后重组、滚动都用这份缓存。
    // 原来写在 items(...) 的参数里，每重组一次就重新读一遍 256 KB。
    val reportLines = remember(crash.file.path, expanded) {
        if (expanded) crash.file.readTextSafe().lines() else emptyList()
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "崩溃报告 · " + crash.fileName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                formatStamp(crash.modifiedAt) + " · " + humanBytes(crash.bytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (crash.description.isNotBlank()) {
                Text(
                    "Description: " + crash.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (crash.exception.isNotBlank()) {
                Text(
                    crash.exception,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起全文" else "查看全文")
                }
                TextButton(onClick = { onNotice(copyText(crash.file.readTextSafe(), "崩溃报告全文")) }) {
                    Text("复制全文")
                }
                TextButton(
                    enabled = instanceDir != null,
                    onClick = { onNotice(openCrashDir(instanceDir)) },
                ) { Text("打开目录") }
            }
            if (expanded) {
                Surface(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
                        items(reportLines.size) { index ->
                            Text(
                                reportLines[index],
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 启动中浮层，照 PCL 的 `PanLaunching` 做（`PageLaunchLeft.xaml:76-158`）。
 *
 * 为什么需要它：点「启动」之后，准备版本文件、整理 classpath、解压原生库这些步骤
 * 都要几秒到十几秒，期间界面除了按钮变灰没有任何反馈 —— 用户会以为没点着，然后连点。
 * 浮层把「现在到哪一步了」顶到屏幕中央，并顺手给一个取消入口。
 *
 * 与 PCL 的差别：那边的进度条是两列 Grid 按列宽比例画、信息区固定四行（步骤/登录方式/进度/速度），
 * 我方没有正版登录也没有下载阶段，所以只留「当前步骤」一行；进度用 LinearProgressIndicator，
 * fraction 来自 LaunchEvent.Stage 的阶段值（0.05 → 0.2 → 1.0），不是真实百分比。
 */
@Composable
private fun LaunchOverlay(
    instanceName: String,
    stageText: String,
    fraction: Float,
    onCancel: () -> Unit,
) {
    // 遮罩挡住下面的按钮：这期间再点一次「启动」没有意义，PCL 也是把 IsHitTestVisible 关掉的。
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.width(320.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "正在启动游戏",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (instanceName.isNotBlank()) {
                    Text(
                        instanceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "当前步骤",
                        Modifier.width(72.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stageText.ifBlank { "准备中" },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "启动进度",
                        Modifier.width(72.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        (fraction.coerceIn(0f, 1f) * 100).toInt().toString() + " %",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("取消启动")
                }
            }
        }
    }
}

@Composable
private fun StatusLine(phase: LaunchPhase, stageText: String, menuReached: Boolean) {
    val (text, color) = when (phase) {
        is LaunchPhase.Idle -> "未启动" to MaterialTheme.colorScheme.onSurfaceVariant
        is LaunchPhase.Starting -> "启动中：" + stageText to MaterialTheme.colorScheme.onSurfaceVariant
        is LaunchPhase.Running -> "运行中 · PID " + phase.pid to MaterialTheme.colorScheme.primary
        is LaunchPhase.Exited -> buildString {
            append("已退出，退出码 ").append(phase.code)
            if (phase.reachedMenu || menuReached) append(" · 日志里出现过主菜单信号") else append(" · 未见主菜单信号")
            phase.diagnosis?.let { append(" · ").append(it) }
        } to if (phase.code == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
        is LaunchPhase.Failed -> "启动失败：" + phase.message to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

/**
 * 启动页左栏：实例列表 + 用户名 + 内存 + 大号启动按钮。
 *
 * 为什么搬到这里：PCL 的 `PageLaunchLeft.xaml` 就是这样分的 —— 左栏回答「现在要启动哪一个」，
 * 右栏放设置与日志。我方原来是「一列往下滚的表单」，实例列表、内存、用户名、启动按钮
 * 依次排在页面中间，点启动要先往下滚一段，日志又被顶到更下面。
 *
 * 实例列表用 weight(1f) 吃掉剩余高度：实例多的时候这一栏自己滚，不会把启动按钮挤出屏幕 ——
 * 按钮必须永远在看得见的地方。
 */
@Composable
private fun LaunchSidePanel(
    /** 当前选中的实例；null = 还没选（这一栏只剩「版本选择」可用）。 */
    selected: Instance?,
    busy: Boolean,
    running: Boolean,
    /** 这一轮里一共几个版本在跑；> 1 时左栏会多一行说明有多份进程。 */
    runningCount: Int = 0,
    stageText: String,
    username: String,
    onUsernameChange: (String) -> Unit,
    /** 账号文件里的全部身份；空表示一个都没登录过，只剩离线。 */
    accountList: List<StoredAccount>,
    /** 当前选中的那个；null = 离线模式。正版进游戏时用户名由会话决定，输入框就没意义了。 */
    activeAccount: StoredAccount?,
    /** 换身份：传 null 表示改回离线。写盘在调用方那边做，这里只负责画。 */
    onPickIdentity: (StoredAccount?) -> Unit,
    /** 身份三格在哪一格（IDENTITY_MS / IDENTITY_OFFLINE / IDENTITY_AUTH）。 */
    identityTab: String,
    onIdentityTab: (String) -> Unit,
    /** 「管理账号 →」要去哪里。 */
    onOpenAccountPage: () -> Unit,
    canLaunch: Boolean,
    /** 不能启动时的一句话原因（空串 = 能启动）。只用来解释按钮为什么是灰的。 */
    blockedHint: String,
    /** 孵化版才画「测试启动」那个勾选框；正式版自带 Client ID，没有这条路。 */
    showTestLaunch: Boolean,
    testLaunch: Boolean,
    onTestLaunchChange: (Boolean) -> Unit,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
) {
    val compact = LocalWindowInfo.current.containerSize.width < 1100
    Surface(
        Modifier.width(if (compact) 248.dp else 300.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        // 这一栏分两层：上面是可变高的内容（版本名 / 身份三格 / 图示 / 启动参数），
        // 下面是钉死的「启动游戏」。以前整栏一个 Column 从头排到底，实例少的时候
        // 按钮下面空了小半屏，点启动前手指还得往下找 —— PCL 的启动按钮永远在栏底。
        Column(
            Modifier.fillMaxHeight().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 当前玩的是哪一版：这一栏不再列全部实例（那是「版本选择」副页面的事），
                // 只显示手上这一个，点「版本选择」才展开整张清单。
                Text(
                    if (selected == null) "还没选版本" else selected.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    if (selected == null) {
                        "右边点一张版本卡片挑一个，或者点「管理版本…」去装新的。"
                    } else {
                        "游戏 " + selected.gameVersion + " · " + selected.loader.name +
                            (selected.loaderVersion?.takeIf { it.isNotBlank() }?.let { " " + it } ?: "")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 身份三格照 PCL 的 PageLaunchLeft.xaml:43-60（正版 / 离线 / 第三方 三个 MyRadioButton）。
                // 格子上只留三个词，解释放到下面账号行里 —— 三格回答「用哪一类身份」，
                // 具体用哪个号在下面选，不让用户在格子上先读一遍说明书。
                IdentityPicker(
                    accountList = accountList,
                    active = activeAccount,
                    enabled = !busy,
                    tab = identityTab,
                    onTab = onIdentityTab,
                    onPick = onPickIdentity,
                    onOpenAccountPage = onOpenAccountPage,
                )
                IdentityBadge(tab = identityTab)
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                )
            }
            // 离线用户名挪到滚动区外面、紧挨着启动按钮（2026-11-16 用户定）：填完名字抬手就是
            // 「启动游戏」，不用先在滚动区里找到它、填完再往下找按钮。
            //
            // 原来它上方还有一行「这个版本的启动参数」小标题，以及一句「给这一版 N MB…」的
            // 内存提示 —— 小标题下面只剩这一个输入框、框本身已有「离线用户名」label，
            // 内存那栏也已整体搬去「设置 → 启动」，两行字都是多余，一并删掉。
            if (activeAccount == null && identityTab == IDENTITY_OFFLINE) {
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("离线用户名") },
                    singleLine = true,
                    enabled = !busy,
                    supportingText = { Text("点「启动」会记住这个名字。") },
                )
            }
            if (!canLaunch && blockedHint.isNotBlank() && !busy) {
                SectionHint(blockedHint, error = true)
            }
            // 大号按钮照 PCL 的 54 高：它是这一页唯一的主要动作，不该和旁边的次要按钮一样大。
            Button(
                onClick = onLaunch,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                enabled = !busy && canLaunch,
            ) {
                // 这一版在跑时按钮是灰的，文案必须跟着变：还写「启动游戏」的灰按钮，
                // 用户会以为是自己没满足什么条件，而真正的原因恰恰相反。
                Text(
                    when {
                        running -> "这个版本正在运行"
                        busy -> "启动中…"
                        else -> "启动游戏"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // 孵化版唯一的绕行口：没填 Client ID 也能把这一版跑起来（登录该失败还是失败）。
            if (showTestLaunch) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Checkbox(checked = testLaunch, onCheckedChange = onTestLaunchChange, enabled = !busy)
                    Text(
                        "测试启动（跳过 ID 检查）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 运行中不再把启动按钮变灰（见调用方的 busy 说明），所以这里必须给一条
            // 明确出口：否则用户看着「启动游戏」亮着，点了会被挡回来，却不知道为什么。
            if (running) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("结束这个版本的游戏")
                }
                if (runningCount > 1) {
                    SectionHint("这一轮里一共 " + runningCount + " 个版本在跑。右边卡片上标着「运行中」的那些，点进去也能单独结束。")
                }
            } else if (busy) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth(), enabled = false) {
                    Text("正在启动…")
                }
                // 正在起新版、旧版还在跑：这时候最容易被忽略的就是那几份进程，
                // 一句提示比让用户自己去找卡片上的标记省事。
                if (runningCount > 0) {
                    SectionHint("另外还有 " + runningCount + " 个版本在跑，可以从右边卡片上单独结束。")
                }
            } else if (stageText.isNotBlank()) {
                SectionHint(stageText)
            }
        }
    }
}

/**
 * 启动栏里的身份选择，照 HTML 预览（dist/xin-jiagou-yulan.html 的 .idtypes）一对一复刻：
 * 上面三个横排格子「正版 / 离线 / 第三方」，点哪一格就在下面显示那一类身份的行。
 *
 * 为什么不是「一列全摆出来」：账号多了之后这一列会长到把启动按钮挤出屏幕，
 * 而九成时候只用一个号；三格的顺序是固定的，第二格永远是离线 —— 肌肉记忆比搜索快。
 *
 * 三格只切面板，不直接改身份（离线那格除外）。切到「正版」时若无脑挑第一个号，
 * 用户在只是「看看自己有哪些号」的时候就被换掉了身份，下次启动才发现登录的是别人。
 */
/**
 * 实例卡片，照 HTML 预览的 .icard：版本号 + 加载器徽标 + 一行说明 + 「版本设置」。
 *
 * 为什么不用 InstancePage 里的那张 InstanceCard：那张是「管理列表」里的条目，带删文件、
 * 移除登记这些动作；启动页这张只回答「点它就用这个版本」。
 *
 * 尺寸（2026-11-16 定）：与 InstancePage 的卡片同宽同高（336 × 340dp），
 * 只把里面的动作减到「版本设置」一个 —— 用户在两个页面看到的是同一种尺寸的单位。
 * 内容区吃 weight(1f)，把「版本设置」顶到底部，所以同一行的卡片必然对齐。
 */
@Composable
private fun InstanceTile(
    instance: Instance,
    /** 这一版实际会用多少 MB（跟随默认时是设置里的值，见 [Instance.effectiveMemoryMb]）。 */
    effectiveMemoryMb: Int,
    selected: Boolean,
    /** 这一版的进程现在还活着（见 RunningGames）。与 [selected] 是两件事：选中的不一定在跑。 */
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onOpenSetup: () -> Unit,
    /** 结束这一版的进程。只在 [running] 时才会画出来。 */
    onStop: () -> Unit = {},
    /** 点卡片头部那颗星：收藏 / 取消收藏。收藏后本页也会把它排到最前（见 instanceOrder）。 */
    onTogglePin: () -> Unit = {},
) {
    Surface(
        Modifier
            .width(336.dp)
            .height(340.dp)
            .clickable(enabled = enabled, onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            Modifier.fillMaxHeight().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        instance.gameVersion,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    LoaderTag(instance.loader)
                    Spacer(Modifier.weight(1f))
                    // 「运行中」排在「当前」前面：已经跑起来的那一版最该被一眼看到，
                    // 用户切回来第一件事往往是「我的游戏还在不在」。
                    if (running) {
                        Text(
                            "运行中",
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (selected) {
                        Text(
                            "当前",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 星贴在行尾：它是「我对这一版的态度」，而旁边那排徽标
                    //（运行中 / 当前）都是机器事实 —— 靠边站才不会和它们看混。
                    Spacer(Modifier.width(6.dp))
                    PinMark(pinned = instance.pinned, enabled = enabled, onClick = onTogglePin)
                }
                Text(
                    buildString {
                        append(instance.loaderLabel())
                        append(" · ")
                        append(effectiveMemoryMb)
                        append(" MB")
                        if (instance.memoryFollowsDefault) append(" · 跟随默认")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    instance.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 「上次玩」只在真玩过的时候占一行：新装的实例还没启动过，
                // 每张卡都挂一句「还没启动过」只是噪声，用户需要知道的是哪些是最近玩的。
                if (instance.lastPlayedAt > 0L) {
                    Text(
                        "上次玩 " + lastPlayedText(instance.lastPlayedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 「版本设置」= 这个版本的详情：内存、JVM 参数、Mod / 资源包 / 光影都在那个面板里，
            // 所以启动页这边不再单开「版本选择」副页面入口（主区的「管理版本…」已经管这件事）。
            // 版本在跑时，「版本设置」旁边再多一个「结束」：用户在图上看得出哪一版在跑，
            // 就必然想就地把它关掉，否则得先去左栏选中它、再点左栏那颗按钮 —— 绕了一圈。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("结束", style = MaterialTheme.typography.labelSmall)
                    }
                }
                OutlinedButton(onClick = onOpenSetup, modifier = Modifier.weight(1f), enabled = enabled) {
                    Text("版本设置", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** 加载器徽标（原版 / Fabric / Forge / NeoForge / Quilt / OptiFine）。 */
@Composable
private fun LoaderTag(loader: io.github.huang404yan.platypus.core.instance.LoaderKind) {
    val label = when (loader) {
        io.github.huang404yan.platypus.core.instance.LoaderKind.VANILLA -> "原版"
        io.github.huang404yan.platypus.core.instance.LoaderKind.FABRIC -> "Fabric"
        io.github.huang404yan.platypus.core.instance.LoaderKind.FORGE -> "Forge"
        io.github.huang404yan.platypus.core.instance.LoaderKind.NEOFORGE -> "NeoForge"
        io.github.huang404yan.platypus.core.instance.LoaderKind.QUILT -> "Quilt"
        io.github.huang404yan.platypus.core.instance.LoaderKind.OPTIFINE -> "OptiFine"
        io.github.huang404yan.platypus.core.instance.LoaderKind.OTHER -> "其他"
    }
    Text(
        label,
        Modifier
            .background(
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
    )
}

/** 「Fabric 0.20.1」这种一行说明；原版就只有「原版」。 */
private fun Instance.loaderLabel(): String {
    val tag = when (loader) {
        io.github.huang404yan.platypus.core.instance.LoaderKind.VANILLA -> "原版"
        io.github.huang404yan.platypus.core.instance.LoaderKind.FABRIC -> "Fabric"
        io.github.huang404yan.platypus.core.instance.LoaderKind.FORGE -> "Forge"
        io.github.huang404yan.platypus.core.instance.LoaderKind.NEOFORGE -> "NeoForge"
        io.github.huang404yan.platypus.core.instance.LoaderKind.QUILT -> "Quilt"
        io.github.huang404yan.platypus.core.instance.LoaderKind.OPTIFINE -> "OptiFine"
        io.github.huang404yan.platypus.core.instance.LoaderKind.OTHER -> "其他"
    }
    val version = loaderVersion?.takeIf { it.isNotBlank() }
    return if (version == null || tag == "原版") tag else tag + " " + version
}

/**
 * 身份标志：三格下面那一块，回答「这格是干什么的」。
 *
 * 为什么自己用 Canvas 画而不是引图标库：ui 模块没有 material-icons 依赖，
 * 为一个侧栏图示拉一整个图标库不划算；三个图形都是几笔直线曲线，几十行就够。
 * 图形跟着三格切换 —— 正版是盾牌加勾，外置是机架（皮肤站/认证服务器），
 * 离线是一个没有连线的人形。
 */
@Composable
private fun IdentityBadge(tab: String) {
    val accent = MaterialTheme.colorScheme.primary
    val title: String
    val detail: String
    when (tab) {
        IDENTITY_MS -> {
            title = "正版账号"
            detail = "微软账号登录，uuid 与皮肤都是官方的，能进正版服。"
        }
        IDENTITY_AUTH -> {
            title = "外置登录"
            detail = "皮肤站账号（Authlib-Injector），LittleSkin 这类服认它。"
        }
        else -> {
            title = "离线模式"
            detail = "不联网、名字随便起；别人看到的是默认皮肤。"
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(60.dp)) {
            val w = size.width
            val h = size.height
            val line = Stroke(width = w * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (tab) {
                IDENTITY_MS -> {
                    // 盾牌：正版的身份是被微软「担保」过的
                    val shield = Path().apply {
                        moveTo(w * 0.5f, h * 0.07f)
                        lineTo(w * 0.87f, h * 0.23f)
                        lineTo(w * 0.87f, h * 0.53f)
                        cubicTo(w * 0.87f, h * 0.78f, w * 0.68f, h * 0.9f, w * 0.5f, h * 0.95f)
                        cubicTo(w * 0.32f, h * 0.9f, w * 0.13f, h * 0.78f, w * 0.13f, h * 0.53f)
                        lineTo(w * 0.13f, h * 0.23f)
                        close()
                    }
                    drawPath(shield, accent.copy(alpha = 0.16f))
                    drawPath(shield, accent, style = line)
                    val tick = Path().apply {
                        moveTo(w * 0.31f, h * 0.5f)
                        lineTo(w * 0.45f, h * 0.64f)
                        lineTo(w * 0.71f, h * 0.35f)
                    }
                    drawPath(tick, accent, style = line)
                }
                IDENTITY_AUTH -> {
                    // 机架：外置登录背后是一台认证服务器
                    drawRoundRect(
                        accent.copy(alpha = 0.16f),
                        topLeft = Offset(w * 0.16f, h * 0.15f),
                        size = Size(w * 0.68f, h * 0.7f),
                        cornerRadius = CornerRadius(w * 0.1f),
                    )
                    drawRoundRect(
                        accent,
                        topLeft = Offset(w * 0.16f, h * 0.15f),
                        size = Size(w * 0.68f, h * 0.7f),
                        cornerRadius = CornerRadius(w * 0.1f),
                        style = line,
                    )
                    // 中间两道隔板 + 每格左侧一个指示点，读起来像「一台服务器上的几层」
                    drawLine(
                        accent,
                        Offset(w * 0.16f, h * 0.38f),
                        Offset(w * 0.84f, h * 0.38f),
                        strokeWidth = w * 0.04f,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        accent,
                        Offset(w * 0.16f, h * 0.62f),
                        Offset(w * 0.84f, h * 0.62f),
                        strokeWidth = w * 0.04f,
                        cap = StrokeCap.Round,
                    )
                    listOf(0.265f, 0.5f, 0.735f).forEach { y ->
                        drawCircle(accent, radius = w * 0.035f, center = Offset(w * 0.28f, h * y))
                    }
                }
                else -> {
                    // 人形：没有账号，只有你自己起的一个名字
                    drawCircle(accent.copy(alpha = 0.16f), radius = w * 0.17f, center = Offset(w * 0.5f, h * 0.31f))
                    drawCircle(accent, radius = w * 0.17f, center = Offset(w * 0.5f, h * 0.31f), style = line)
                    drawArc(
                        accent.copy(alpha = 0.16f),
                        195f,
                        150f,
                        useCenter = false,
                        topLeft = Offset(w * 0.17f, h * 0.55f),
                        size = Size(w * 0.66f, h * 0.66f),
                        style = Stroke(width = w * 0.28f, cap = StrokeCap.Round),
                    )
                    drawArc(
                        accent,
                        195f,
                        150f,
                        useCenter = false,
                        topLeft = Offset(w * 0.17f, h * 0.55f),
                        size = Size(w * 0.66f, h * 0.66f),
                        style = line,
                    )
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun IdentityPicker(
    accountList: List<StoredAccount>,
    active: StoredAccount?,
    enabled: Boolean,
    tab: String,
    onTab: (String) -> Unit,
    onPick: (StoredAccount?) -> Unit,
    onOpenAccountPage: () -> Unit,
) {
    val microsoft = accountList.filter { !it.isYggdrasil }
    val external = accountList.filter { it.isYggdrasil }
    val shown = if (tab == IDENTITY_AUTH) external else microsoft
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IdentityTypeCell("正版", tab == IDENTITY_MS, enabled) { onTab(IDENTITY_MS) }
            IdentityTypeCell("离线", tab == IDENTITY_OFFLINE, enabled) {
                // 离线这一格是唯一「点了就生效」的：它没有可选的号，名字就是身份。
                onTab(IDENTITY_OFFLINE)
                onPick(null)
            }
            IdentityTypeCell("第三方", tab == IDENTITY_AUTH, enabled) { onTab(IDENTITY_AUTH) }
        }
        when {
            tab == IDENTITY_OFFLINE -> Unit // 名字的输入框在调用方那边，紧跟着这一块画
            shown.isEmpty() -> SectionHint(
                if (tab == IDENTITY_AUTH) {
                    "还没有外置登录的账号。去「设置 → 账号」填一个认证服务器。"
                } else {
                    "还没有登录过微软账号。去「设置 → 账号」加一个。"
                },
            )
            else -> shown.forEach { account ->
                IdentityRow(
                    title = account.name,
                    detail = if (account.isYggdrasil) "外置登录" else "微软账号",
                    selected = active != null && active.key == account.key,
                    enabled = enabled,
                    onClick = { onPick(account) },
                )
            }
        }
        Text(
            if (tab == IDENTITY_AUTH) "添加外置登录 →" else "管理账号 →",
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onOpenAccountPage)
                .padding(vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.IdentityTypeCell(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                MaterialTheme.shapes.small,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun IdentityRow(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            if (selected) "已选" else "选这个",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
private fun JavaRow(runtime: JavaRuntime, selected: Boolean, recommended: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Java " + runtime.majorVersion + (runtime.vendor?.let { " · " + it } ?: "") +
                        if (runtime.managed) " · 启动器托管" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    runtime.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (recommended) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        "推荐",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
