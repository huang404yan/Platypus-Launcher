package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.Endpoints
import io.github.huang404yan.platypus.core.download.DownloadPreference
import io.github.huang404yan.platypus.core.download.DownloadSource
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.MemoryDefaults
import io.github.huang404yan.platypus.core.launch.GameLanguage
import io.github.huang404yan.platypus.core.launch.GameOptions
import io.github.huang404yan.platypus.core.saves.AutoBackupPolicy
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import io.github.huang404yan.platypus.core.theme.LauncherTheme
import io.github.huang404yan.platypus.core.theme.paletteOf
import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
import io.github.huang404yan.platypus.core.settings.normalizeOfflineUsername
import io.github.huang404yan.platypus.core.settings.usernameProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「启动」二级项的 id，与 Navigation.kt 里 PAGE_SUB_TABS 的定义对应。 */
const val SETTINGS_TAB_LAUNCH = "launch"

/** 「界面」二级项的 id。 */
const val SETTINGS_TAB_UI = "ui"

/** 「账号」二级项的 id。 */
const val SETTINGS_TAB_ACCOUNT = "account"

/**
 * 「Java」的 id。
 *
 * 2026-11-15 起它不再是一个二级项（JavaTab 直接跟在「启动」卡片后面），但常量留着：帮助页与旧深链还得能指到它，且它作为「启动」页里那一块的锚点。
 */
const val SETTINGS_TAB_JAVA = "java"

/** 「下载」二级项的 id：下载源偏好。 */
const val SETTINGS_TAB_DOWNLOAD = "download"

/** 「存档」二级项的 id：自动备份的开关、间隔与保留份数。 */
const val SETTINGS_TAB_SAVES = "saves"

/** 「其他」二级项的 id。 */
const val SETTINGS_TAB_OTHER = "other"

/**
 * 设置页：启动器级别的三件事——离线用户名、默认游戏目录、默认内存。
 *
 * 都直接决定「双击能不能玩起来」：名字写死改不了、目录每次重填、新实例内存给错，
 * 都会把用户堵在启动前。实例自己的内存仍在「版本设置」里改、存在 instances.json 里，
 * 这里只决定「新建实例的初值」。
 *
 * 读写都走 [io.github.huang404yan.platypus.core.settings.SettingsRepository]，
 * 界面只管输入与提示，不碰文件。
 *
 * 二级导航把这一页按「改了会影响什么」拆开：启动（目录与内存）、界面（现在只有只读信息）、
 * 其他（文件位置、上次保存、填回默认值）。以前三个卡片一屏到底，用户分不清
 * 「默认内存」和「实例内存」不是一回事 —— 拆开后在「启动」里挨着目录放，
 * 说明文字就能贴着控件讲清楚。
 *
 * 保存按钮不在 tab 里，而在这三层外面：三个 tab 改的是同一份 JSON，
 * 分成三个保存按钮反而会出现「这个 tab 存了、那个没存」。
 */
@Composable
fun SettingsPage(
    backend: LauncherBackend?,
    /** 五个二级项之一：见 [SETTINGS_TAB_LAUNCH] 与它下面那四个常量。 */
    subTab: String = SETTINGS_TAB_LAUNCH,
    /**
     * 侧边栏那个「↻」的计数，只有「Java」页用得上（重读一次官方运行时清单）。
     *
     * 别的二级项没有可重读的远端清单，传了也不理 —— 与下载页那几项同一个约定。
     */
    refreshTick: Int = 0,
    /**
     * 当前生效的配色。
     *
     * 由 [App] 持有而不是这一页：主题挂在最外层，改它等于换一层皮。这一页只负责
     * 「把用户选的存下来」和「告诉上层立刻换」。
     */
    theme: LauncherTheme = LauncherTheme.AUTO,
    /** 用户点了另一套配色：上层立刻换，同时这一页把它写进设置。 */
    onThemeChange: (LauncherTheme) -> Unit = {},
    /**
     * 当前界面模式。
     *
     * 与 [theme] 同一套约定：由 [App] 持有，这一页只负责「把用户选的存下来」
     * 和「告诉上层立刻重画」—— 左列怎么分组、各页露哪些块都由上层那份状态决定。
     */
    uiMode: UiMode = UiMode.FULL,
    /** 用户点了另一档模式：上层立刻换，同时这一页把它写进设置。 */
    onUiModeChange: (UiMode) -> Unit = {},
    /**
     * 「少动一点」：true 时界面上的动画全部瞬间到位（见 [LocalReduceMotion]）。
     *
     * 与 [theme]、[uiMode] 同一套约定，由 [App] 持有并立刻重画；
     * 这一页只负责存下来和通知上层。
     */
    reduceMotion: Boolean = false,
    /** 用户改了这个开关：上层立刻换，同时写进设置。 */
    onReduceMotionChange: (Boolean) -> Unit = {},
) {
    if (backend == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "后端未装配：设置页需要桌面端注入设置存储实现。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }

    val repository = backend.settings
    if (repository == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "当前后端没有提供设置存储，设置项不可用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }

    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf(LauncherSettings.DEFAULT_OFFLINE_USERNAME) }
    var gameRoot by remember { mutableStateOf("") }
    var memoryText by remember { mutableStateOf(Instance.DEFAULT_MEMORY_MB.toString()) }
    /**
     * 默认内存用「自动（按物理内存算）」还是用手上这个数。
     *
     * 自动时 [memoryText] 仍然跟着算出来的值走 —— 它代表「下次保存会写进设置的那个数」，
     * 也是滑条画的位置。这样用户从自动切回手动时，滑条就停在刚刚看到的那个数上，
     * 不会跳回某个陌生的老值。
     */
    var memoryAuto by remember { mutableStateOf(true) }
    val autoMemoryMb = MemoryDefaults.autoMb(SystemMemory.totalMb)
    /** 新建实例默认开不开隔离；真正读它的是 [InstanceLayouts.effectiveIsolated]，这里只是编辑那一格。 */
    var defaultIsolation by remember { mutableStateOf(true) }
    /** 进游戏用哪个语言；空串 = 跟随系统。 */
    var gameLanguage by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var loadedFrom by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var noticeIsError by remember { mutableStateOf(false) }
    var savedAt by remember { mutableStateOf(0L) }
    var activeAccountUuid by remember { mutableStateOf("") }
    var microsoftClientId by remember { mutableStateOf("") }
    var downloadSource by remember { mutableStateOf(DownloadSource.DEFAULT.id) }
    var autoBackupEnabled by remember { mutableStateOf(AutoBackupPolicy.DEFAULT.enabled) }
    var autoBackupHours by remember { mutableStateOf(AutoBackupPolicy.DEFAULT.intervalHours.toString()) }
    var autoBackupKeep by remember { mutableStateOf(AutoBackupPolicy.DEFAULT.keep.toString()) }
    var curseForgeKey by remember { mutableStateOf("") }
    /** 空壳包的镜像根；完整版恒为空串，界面上也不显示这一格。 */
    var serverBase by remember { mutableStateOf("") }
    /**
     * 这一页手上那份界面模式。
     *
     * 与 [theme] 不同，它同时由上层（[App]）传进来：那一份是「正在生效的」，
     * 这一份是「设置文件里记着的」。正常情况下两者一致，点一下就是先改上层再写盘。
     */
    var uiModeText by remember { mutableStateOf(UiMode.FULL.id) }
    /** 这一页手上那份动效开关。与 [uiModeText] 同理：上层那份正在生效，这份是要写进文件的。 */
    var motionText by remember { mutableStateOf(false) }
    /**
     * 这一页读过的那份设置，**原样留着**，保存时在它上面 copy。
     *
     * 原来六处落盘全是 `LauncherSettings(...)` 新建，新建会把这一页不认识的字段一并
     * 写成默认值 —— firstRunAt 与 lastInstanceKey 就是这么被冲掉的：用户在设置页点一次
     * 「保存」，欢迎窗下次启动又弹一遍，上次玩的版本也忘了 —— 在设置页改一次配色就能
     * 复现这两件事。
     * 改成 copy 之后，以后再加字段不用回来挨个补。
     */
    var base by remember { mutableStateOf(LauncherSettings()) }

    LaunchedEffect(Unit) {
        val settings = withContext(Dispatchers.IO) {
            runCatching { repository.load() }.getOrDefault(LauncherSettings())
        }
        username = settings.offlineUsername
        gameRoot = settings.defaultGameRoot
        memoryAuto = settings.defaultMemoryAuto
        defaultIsolation = settings.defaultIsolation
        gameLanguage = settings.gameLanguage
        memoryText = MemoryDefaults.resolve(
            settings.defaultMemoryMb,
            settings.defaultMemoryAuto,
            SystemMemory.totalMb,
        ).toString()
        activeAccountUuid = settings.activeAccountUuid
        microsoftClientId = settings.microsoftClientId
        downloadSource = DownloadSource.of(settings.downloadSource).id
        autoBackupEnabled = settings.autoBackupEnabled
        autoBackupHours = settings.autoBackupHours.toString()
        autoBackupKeep = settings.autoBackupKeep.toString()
        curseForgeKey = settings.curseForgeApiKey
        serverBase = settings.serverBase
        uiModeText = UiMode.of(settings.uiMode).id
        motionText = settings.reduceMotion
        base = settings
        loaded = true
        loadedFrom = (repository as? JsonSettingsRepository)?.path.orEmpty()
    }

    /** 立刻要落盘的那几处（换账号、存 Client ID、换配色、换下载源）用它带上备份三格。 */
    fun backupPolicyNow(): AutoBackupPolicy =
        AutoBackupPolicy.parse(autoBackupEnabled, autoBackupHours, autoBackupKeep)

    fun save() {
        val parsedMemory = memoryText.trim().toIntOrNull()
        if (parsedMemory == null) {
            notice = "默认内存要填整数，单位 MB。"
            noticeIsError = true
            return
        }
        val parsedHours = autoBackupHours.trim().toIntOrNull()
        if (parsedHours == null) {
            notice = "自动备份的间隔要填整数，单位小时。"
            noticeIsError = true
            return
        }
        val parsedKeep = autoBackupKeep.trim().toIntOrNull()
        if (parsedKeep == null) {
            notice = "自动备份的保留份数要填整数。"
            noticeIsError = true
            return
        }
        val clampedHours = parsedHours.coerceIn(AutoBackupPolicy.INTERVAL_RANGE.first, AutoBackupPolicy.INTERVAL_RANGE.last)
        val clampedKeep = parsedKeep.coerceIn(AutoBackupPolicy.KEEP_RANGE.first, AutoBackupPolicy.KEEP_RANGE.last)
        val clamped = Instance.clampMemoryMb(parsedMemory)
        val cleanedName = normalizeOfflineUsername(username)
        scope.launch {
            val result = runCatching {
                repository.save(
                    base.copy(
                        offlineUsername = cleanedName,
                        defaultGameRoot = gameRoot.trim(),
                        // 自动时把算出来的值也写进字段：instances.json 是给人看的，
                        // 留着一个和实际生效值不一样的老数会让人以为设置没生效。
                        defaultMemoryMb = if (memoryAuto) autoMemoryMb else clamped,
                        defaultMemoryAuto = memoryAuto,
                        defaultIsolation = defaultIsolation,
                        gameLanguage = gameLanguage,
                        activeAccountUuid = activeAccountUuid,
                        microsoftClientId = microsoftClientId,
                        theme = theme.id,
                        // base 里已经是最新值（换模式成功会回写 base），这里显式带上
                        // 是为了「刚写完模式、没等回调就点保存」这一瞬间也不会把它冲回去。
                        uiMode = uiModeText,
                        reduceMotion = motionText,
                        downloadSource = downloadSource,
                        autoBackupEnabled = autoBackupEnabled,
                        autoBackupHours = clampedHours,
                        autoBackupKeep = clampedKeep,
                        curseForgeApiKey = curseForgeKey,
                        serverBase = serverBase,
                    ),
                )
            }
            result.onSuccess { stored ->
                username = stored.offlineUsername
                gameRoot = stored.defaultGameRoot
                memoryText = MemoryDefaults.resolve(
                    stored.defaultMemoryMb,
                    stored.defaultMemoryAuto,
                    SystemMemory.totalMb,
                ).toString()
                autoBackupHours = stored.autoBackupHours.toString()
                autoBackupKeep = stored.autoBackupKeep.toString()
                defaultIsolation = stored.defaultIsolation
                gameLanguage = stored.gameLanguage
                // 进程级那份也要跟着变：否则回到列表页点启动，用的还是开程序时的老默认。
                InstanceLayouts.defaultIsolated = stored.defaultIsolation
                savedAt = stored.updatedAt
                noticeIsError = false
                notice = buildString {
                    append("已保存。")
                    if (clamped != parsedMemory) {
                        append("默认内存按允许范围（")
                        append(Instance.MIN_MEMORY_MB)
                        append("–")
                        append(Instance.MAX_MEMORY_MB)
                        append(" MB）改成了 ")
                        append(clamped)
                        append(" MB。")
                    }
                    if (clampedHours != parsedHours) {
                        append("备份间隔按允许范围（")
                        append(AutoBackupPolicy.INTERVAL_RANGE.first)
                        append("–")
                        append(AutoBackupPolicy.INTERVAL_RANGE.last)
                        append(" 小时）改成了 ")
                        append(clampedHours)
                        append(" 小时。")
                    }
                    if (clampedKeep != parsedKeep) {
                        append("保留份数按允许范围（")
                        append(AutoBackupPolicy.KEEP_RANGE.first)
                        append("–")
                        append(AutoBackupPolicy.KEEP_RANGE.last)
                        append("）改成了 ")
                        append(clampedKeep)
                        append(" 份。")
                    }
                    val problem = usernameProblem(stored.offlineUsername)
                    if (problem != null) append("注意：").append(problem)
                }
            }.onFailure {
                noticeIsError = true
                notice = "保存失败：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /**
     * 立刻把「用哪个账号」写进设置。
     *
     * 不跟「保存」按钮走：账号页那两个按钮点下去就是要马上生效的，用户不会想到
     * 还得再滚回去点一次保存。其余字段从内存里的当前值带上，避免把没保存的改动冲掉。
     */
    fun useAccount(uuid: String) {
        activeAccountUuid = uuid
        val parsedMemory = memoryText.trim().toIntOrNull() ?: Instance.DEFAULT_MEMORY_MB
        scope.launch {
            val result = runCatching {
                repository.save(
                    base.copy(
                        offlineUsername = normalizeOfflineUsername(username),
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = Instance.clampMemoryMb(parsedMemory),
                        activeAccountUuid = uuid,
                        microsoftClientId = microsoftClientId,
                        theme = theme.id,
                        downloadSource = downloadSource,
                        autoBackupEnabled = autoBackupEnabled,
                        autoBackupHours = backupPolicyNow().intervalHours,
                        autoBackupKeep = backupPolicyNow().keep,
                        curseForgeApiKey = curseForgeKey,
                        serverBase = serverBase,
                        defaultIsolation = defaultIsolation,
                        gameLanguage = gameLanguage,
                    ),
                )
            }
            result.onSuccess { stored ->
                savedAt = stored.updatedAt
                noticeIsError = false
                notice = if (stored.activeAccountUuid.isBlank()) {
                    "已改用离线模式。"
                } else {
                    "启动游戏时会用这个正版账号。"
                }
            }.onFailure {
                noticeIsError = true
                notice = "账号选择没能存下来：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /**
     * 立刻把 Client ID 写进设置。
     *
     * 和「用哪个账号」一样不跟底部「保存」按钮走：用户在账号页填完 ID 就会顺手点登录，
     * 如果那时还没落盘，登录读到的还是空串，会报「还没有填 Client ID」——
     * 一个刚刚明明填过的东西说他没填，比报错更让人火大。
     */
    fun saveClientId(raw: String) {
        val cleaned = raw.trim().trim('"').trim()
        microsoftClientId = cleaned
        val parsedMemory = memoryText.trim().toIntOrNull() ?: Instance.DEFAULT_MEMORY_MB
        scope.launch {
            val result = runCatching {
                repository.save(
                    base.copy(
                        offlineUsername = normalizeOfflineUsername(username),
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = Instance.clampMemoryMb(parsedMemory),
                        activeAccountUuid = activeAccountUuid,
                        microsoftClientId = cleaned,
                        theme = theme.id,
                        downloadSource = downloadSource,
                        autoBackupEnabled = autoBackupEnabled,
                        autoBackupHours = backupPolicyNow().intervalHours,
                        autoBackupKeep = backupPolicyNow().keep,
                        curseForgeApiKey = curseForgeKey,
                        serverBase = serverBase,
                        defaultIsolation = defaultIsolation,
                        gameLanguage = gameLanguage,
                    ),
                )
            }
            result.onSuccess { stored ->
                microsoftClientId = stored.microsoftClientId
                savedAt = stored.updatedAt
                // 成功不在这里说话：账号页点完「保存 ID」自己会提示，两处都说会重复。
            }.onFailure {
                noticeIsError = true
                notice = "Client ID 没能存下来：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /**
     * 立刻把配色写进设置并让上层换皮。
     *
     * 与账号、Client ID 一样不跟底部「保存」按钮走：换配色是即时反馈的事。
     * 其余字段从内存当前值带上 —— 用户可能改了目录还没保存，不能把它冲掉。
     */
    fun persistTheme(picked: LauncherTheme) {
        val parsedMemory = memoryText.trim().toIntOrNull() ?: Instance.DEFAULT_MEMORY_MB
        scope.launch {
            runCatching {
                repository.save(
                    base.copy(
                        offlineUsername = normalizeOfflineUsername(username),
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = Instance.clampMemoryMb(parsedMemory),
                        activeAccountUuid = activeAccountUuid,
                        microsoftClientId = microsoftClientId,
                        theme = picked.id,
                        uiMode = uiModeText,
                        reduceMotion = motionText,
                        downloadSource = downloadSource,
                        autoBackupEnabled = autoBackupEnabled,
                        autoBackupHours = backupPolicyNow().intervalHours,
                        autoBackupKeep = backupPolicyNow().keep,
                        curseForgeApiKey = curseForgeKey,
                        serverBase = serverBase,
                        defaultIsolation = defaultIsolation,
                        gameLanguage = gameLanguage,
                    ),
                )
            }.onSuccess { stored ->
                savedAt = stored.updatedAt
                noticeIsError = false
                // 成功不在这里说话：选中的那一行自己会打勾，界面也已经变过来了。
            }.onFailure {
                noticeIsError = true
                notice = "配色没能存下来：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /**
     * 立刻把界面模式写进设置并让上层重画。
     *
     * 与换配色同一套：这是即时反馈的事，不该等一个「保存」按钮。
     * 其余字段从内存当前值带上，用户改了目录还没保存也不能被这一下冲掉。
     */
    fun persistUiMode(picked: UiMode, previous: UiMode) {
        uiModeText = picked.id
        onUiModeChange(picked)
        val parsedMemory = memoryText.trim().toIntOrNull() ?: Instance.DEFAULT_MEMORY_MB
        scope.launch {
            runCatching {
                repository.save(
                    base.copy(
                        offlineUsername = normalizeOfflineUsername(username),
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = Instance.clampMemoryMb(parsedMemory),
                        activeAccountUuid = activeAccountUuid,
                        microsoftClientId = microsoftClientId,
                        theme = theme.id,
                        uiMode = picked.id,
                        reduceMotion = motionText,
                        downloadSource = downloadSource,
                        autoBackupEnabled = autoBackupEnabled,
                        autoBackupHours = backupPolicyNow().intervalHours,
                        autoBackupKeep = backupPolicyNow().keep,
                        curseForgeApiKey = curseForgeKey,
                        serverBase = serverBase,
                        defaultIsolation = defaultIsolation,
                        gameLanguage = gameLanguage,
                    ),
                )
            }.onSuccess { stored ->
                base = stored
                savedAt = stored.updatedAt
                noticeIsError = false
            }.onFailure {
                // 写盘失败要把界面退回去：否则用户看到的是「简洁」，重启又变回完整，
                // 而中间没有任何提示 —— 这种不一致比不给切换更难受。
                uiModeText = previous.id
                onUiModeChange(previous)
                noticeIsError = true
                notice = "界面模式没能存下来：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /**
     * 立刻把「少动一点」写进设置并让上层重画。
     *
     * 与换配色、换模式同一套：这是即时反馈的事，不该等一个「保存」按钮。
     * 写盘失败同样退回原来的值 —— 开关停在「已关」但重启又变成动画，比不给这个开关更糊涂。
     */
    fun persistMotion(picked: Boolean, previous: Boolean) {
        motionText = picked
        onReduceMotionChange(picked)
        val parsedMemory = memoryText.trim().toIntOrNull() ?: Instance.DEFAULT_MEMORY_MB
        scope.launch {
            runCatching {
                repository.save(
                    base.copy(
                        offlineUsername = normalizeOfflineUsername(username),
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = Instance.clampMemoryMb(parsedMemory),
                        activeAccountUuid = activeAccountUuid,
                        microsoftClientId = microsoftClientId,
                        theme = theme.id,
                        uiMode = uiModeText,
                        reduceMotion = picked,
                        downloadSource = downloadSource,
                        autoBackupEnabled = autoBackupEnabled,
                        autoBackupHours = backupPolicyNow().intervalHours,
                        autoBackupKeep = backupPolicyNow().keep,
                        curseForgeApiKey = curseForgeKey,
                        serverBase = serverBase,
                        defaultIsolation = defaultIsolation,
                        gameLanguage = gameLanguage,
                    ),
                )
            }.onSuccess { stored ->
                base = stored
                savedAt = stored.updatedAt
                noticeIsError = false
            }.onFailure {
                motionText = previous
                onReduceMotionChange(previous)
                noticeIsError = true
                notice = "动效开关没能存下来：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun resetDefaults() {
        username = LauncherSettings.DEFAULT_OFFLINE_USERNAME
        gameRoot = ""
        memoryAuto = true
        memoryText = autoMemoryMb.toString()
        defaultIsolation = true
        gameLanguage = ""
        activeAccountUuid = ""
        // Client ID 刻意不跟着填回默认值：默认值是空串，而用户是从 Azure 门户抄来的，
        // 手边没有备份就找不回来了（那个 ID 在门户里还能看到，但他得再走一遍）。
        // 「填回默认值」的语义是「把可重建的设置恢复」，这一项不属于。
        autoBackupEnabled = AutoBackupPolicy.DEFAULT.enabled
        autoBackupHours = AutoBackupPolicy.DEFAULT.intervalHours.toString()
        autoBackupKeep = AutoBackupPolicy.DEFAULT.keep.toString()
        noticeIsError = false
        notice = "已填回默认值，点「保存」才生效。"
    }

    /**
     * 立刻把下载源偏好写进设置，并就地更新进程级的那份。
     *
     * 与配色一样不跟底部「保存」按钮走：用户改完就想马上重试一次下载，
     * 如果那时还没落盘、也没同步到 [DownloadPreference]，点了下载还是老顺序。
     */
    /**
     * 存 CurseForge 的 API Key。与下载源一样是「点完立刻落盘」，不走底部保存按钮 ——
     * 下载页本来就没有那个按钮（底部保存被 SETTINGS_TAB_DOWNLOAD 排除了）。
     */
    fun saveCurseKey(raw: String) {
        val cleaned = raw.trim().trim('"').trim()
        curseForgeKey = cleaned
        val parsedMemory = memoryText.trim().toIntOrNull() ?: Instance.DEFAULT_MEMORY_MB
        scope.launch {
            runCatching {
                repository.save(
                    base.copy(
                        offlineUsername = normalizeOfflineUsername(username),
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = Instance.clampMemoryMb(parsedMemory),
                        activeAccountUuid = activeAccountUuid,
                        microsoftClientId = microsoftClientId,
                        theme = theme.id,
                        downloadSource = downloadSource,
                        autoBackupEnabled = autoBackupEnabled,
                        autoBackupHours = backupPolicyNow().intervalHours,
                        autoBackupKeep = backupPolicyNow().keep,
                        curseForgeApiKey = cleaned,
                        serverBase = serverBase,
                        defaultIsolation = defaultIsolation,
                        gameLanguage = gameLanguage,
                    ),
                )
            }.onSuccess {
                savedAt = it.updatedAt
                noticeIsError = false
                notice = if (cleaned.isBlank()) {
                    "已清空 CurseForge 的 Key。导出 CurseForge 包时不再查文件归属，文件全部打进包里。"
                } else {
                    "CurseForge 的 Key 已保存（" + cleaned.take(4) + "…）。导出 CurseForge 包时会用它查文件归属。"
                }
            }.onFailure {
                noticeIsError = true
                notice = "保存失败：" + (it.message ?: it::class.simpleName)
            }
        }
    }

    /**
     * 存空壳包的镜像根。跟下载源、CurseForge Key 一样是「点完立刻落盘」，不走底部保存按钮。
     *
     * 存完还要就地更新 [Endpoints.serverBase]：进程里所有地址都从它拼，等到下次开程序
     * 才生效的话，用户点完「保存」回去点下载还是空壳状态，会当成没存上。
     */
    fun saveServerBase(raw: String) {
        val cleaned = Endpoints.normalizeBase(raw)
        if (raw.isNotBlank() && cleaned.isEmpty()) {
            noticeIsError = true
            notice = "这个地址不认：要写成 http:// 或 https:// 开头的完整地址，例如 https://你的镜像/。"
            return
        }
        serverBase = cleaned
        Endpoints.setServerBase(cleaned)
        val parsedMemory = memoryText.trim().toIntOrNull() ?: Instance.DEFAULT_MEMORY_MB
        scope.launch {
            runCatching {
                repository.save(
                    base.copy(
                        offlineUsername = normalizeOfflineUsername(username),
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = Instance.clampMemoryMb(parsedMemory),
                        activeAccountUuid = activeAccountUuid,
                        microsoftClientId = microsoftClientId,
                        theme = theme.id,
                        downloadSource = downloadSource,
                        serverBase = cleaned,
                        autoBackupEnabled = autoBackupEnabled,
                        autoBackupHours = backupPolicyNow().intervalHours,
                        autoBackupKeep = backupPolicyNow().keep,
                        curseForgeApiKey = curseForgeKey,
                        defaultIsolation = defaultIsolation,
                        gameLanguage = gameLanguage,
                    ),
                )
            }.onSuccess {
                savedAt = it.updatedAt
                noticeIsError = false
                notice = if (cleaned.isEmpty()) {
                    "已清空服务地址。下载与搜索都停用，界面照旧能看，只是没有内容。"
                } else {
                    "服务地址已保存：" + cleaned + "。下载与搜索从这一刻起走它。"
                }
            }.onFailure {
                noticeIsError = true
                notice = "服务地址没能存下来：" + (it.message ?: it::class.simpleName)
            }
        }
    }

    fun pickDownloadSource(picked: DownloadSource) {
        downloadSource = picked.id
        DownloadPreference.source = picked
        val parsedMemory = memoryText.trim().toIntOrNull() ?: Instance.DEFAULT_MEMORY_MB
        scope.launch {
            runCatching {
                repository.save(
                    base.copy(
                        offlineUsername = normalizeOfflineUsername(username),
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = Instance.clampMemoryMb(parsedMemory),
                        activeAccountUuid = activeAccountUuid,
                        microsoftClientId = microsoftClientId,
                        theme = theme.id,
                        downloadSource = picked.id,
                        autoBackupEnabled = autoBackupEnabled,
                        autoBackupHours = backupPolicyNow().intervalHours,
                        autoBackupKeep = backupPolicyNow().keep,
                        curseForgeApiKey = curseForgeKey,
                        serverBase = serverBase,
                        defaultIsolation = defaultIsolation,
                        gameLanguage = gameLanguage,
                    ),
                )
            }.onSuccess {
                savedAt = it.updatedAt
                noticeIsError = false
                // 成功不在这里说话：选中的那一行自己会打勾。
            }.onFailure {
                noticeIsError = true
                notice = "下载源没能存下来：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }


    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "这里的改动会存成一份 JSON，下次打开程序还在。某一版要单独设内存，去那张卡片的「版本设置」改。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (subTab) {
            // Java 页不读写设置文件，所以下面那排「保存 / 填回默认值」对它不显示。
            // 账号页自己落盘（点「用这个」立刻生效），不走下面那排保存。
            SETTINGS_TAB_ACCOUNT -> AccountTab(
                backend = backend,
                activeUuid = activeAccountUuid,
                clientId = microsoftClientId,
                onUseAccount = { useAccount(it) },
                onUseOffline = { useAccount("") },
                onSaveClientId = { saveClientId(it) },
            )
            SETTINGS_TAB_DOWNLOAD -> DownloadTab(
                current = DownloadSource.of(downloadSource),
                onPick = { pickDownloadSource(it) },
                curseKey = curseForgeKey,
                onCurseKeyChange = { curseForgeKey = it },
                onSaveCurseKey = { saveCurseKey(it) },
                serverBase = serverBase,
                serviceReady = Endpoints.serviceReady(),
                onServerBaseChange = { serverBase = it },
                onSaveServerBase = { saveServerBase(it) },
            )
            SETTINGS_TAB_SAVES -> SavesTab(
                enabled = autoBackupEnabled,
                onEnabledChange = { autoBackupEnabled = it },
                hoursText = autoBackupHours,
                onHoursChange = { autoBackupHours = it },
                keepText = autoBackupKeep,
                onKeepChange = { autoBackupKeep = it },
            )
            SETTINGS_TAB_UI -> InterfaceTab(
                theme = theme,
                onPick = { picked ->
                    onThemeChange(picked)
                    persistTheme(picked)
                },
                uiMode = UiMode.of(uiModeText),
                // 换模式不跟「保存」按钮走：与换配色一样是即时反馈的事。
                onPickUiMode = { picked -> persistUiMode(picked, UiMode.of(base.uiMode)) },
                reduceMotion = motionText,
                onPickReduceMotion = { picked -> persistMotion(picked, base.reduceMotion) },
            )
            SETTINGS_TAB_OTHER -> OtherTab(
                loadedFrom = loadedFrom,
                savedAt = savedAt,
                loaded = loaded,
                onResetDefaults = { resetDefaults() },
            )
            else -> {
                LaunchTab(
                    gameRoot = gameRoot,
                    onGameRootChange = { gameRoot = it },
                    memoryText = memoryText,
                    onMemoryChange = { memoryText = it },
                    memoryAuto = memoryAuto,
                    autoMemoryMb = autoMemoryMb,
                    onMemoryAutoChange = { memoryAuto = it },
                    defaultIsolation = defaultIsolation,
                    onDefaultIsolationChange = { defaultIsolation = it },
                    gameLanguage = gameLanguage,
                    onGameLanguageChange = { gameLanguage = it },
                    loaded = loaded,
                )
                // Java 运行时就跟着启动项放：“这个版本要用哪个 Java”与
                // “本机装了哪些 Java”是同一件事，拆到两个二级项里反而要来回切。
                JavaTab(backend, gameRoot, refreshTick)
            }
        }

        // 「界面」只改配色，点一下就已经落盘了，底部那排「保存 / 填回默认值」
        // 对它没有意义 —— 留着反而让人以为不点保存不生效。
        if (subTab != SETTINGS_TAB_ACCOUNT && subTab != SETTINGS_TAB_UI &&
            subTab != SETTINGS_TAB_DOWNLOAD
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { save() }, enabled = loaded) { Text("保存") }
                OutlinedButton(onClick = { resetDefaults() }, enabled = loaded) { Text("填回默认值") }
            }
        }

        NoticeFade(notice) { shown ->
            Text(
                shown,
                style = MaterialTheme.typography.bodySmall,
                color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 「启动」：进游戏前要定下来的那几件事 —— 目录、内存、Java。
 *
 * 离线用户名那张卡删掉了（2026-11-15）：名字是**每一次启动**才确定的东西，
 * 跟「这一局用谁的身份」是同一件事，所以挪到启动页左栏那三格底下填。
 * 留在设置页只会让人以为得先去注册一个名字才能玩。
 *
 * 内存这一块是一条滑条、一排常用档位，外加一个「自动」开关（2026-11-16 两次改动叠出来的）。
 *
 * 先是把假的「自动分配」删了：那个开关在 core 里没有任何读取方，勾上也不会改变 -Xmx，
 * 留着只会让人以为它在起作用。后来发现「自动」这个需求本身是真的 —— 启动器发给别人用，
 * 写死 2048 MB 在 8 GB 机器上会爆、在 64 GB 机器上浪费 —— 于是换成一个**真算**的自动：
 * 取物理内存的四成，见 [io.github.huang404yan.platypus.core.instance.MemoryDefaults]。
 * 差别在于这个自动值真的会写进 defaultMemoryMb 并在启动时落到实例上，而不是一个死开关。
 *
 * 「内存优化」那个勾选框删了（同一天）：它在 core 里同样没有任何读取方，勾上不会往
 * JVM 参数里加哪怕一条 —— 一个勾了没反应的勾比没有更糟。真要做优化参数，得先让启动链
 * 认它，再回来加界面。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaunchTab(
    gameRoot: String,
    onGameRootChange: (String) -> Unit,
    memoryText: String,
    onMemoryChange: (String) -> Unit,
    memoryAuto: Boolean,
    autoMemoryMb: Int,
    onMemoryAutoChange: (Boolean) -> Unit,
    defaultIsolation: Boolean,
    onDefaultIsolationChange: (Boolean) -> Unit,
    gameLanguage: String,
    onGameLanguageChange: (String) -> Unit,
    loaded: Boolean,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingCard(
            "默认游戏目录",
            "启动页与版本选择会用它做初值；下载页也默认往这里装。留空表示每次按系统默认位置猜。",
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = gameRoot,
                    onValueChange = onGameRootChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("游戏目录（.minecraft）") },
                    singleLine = true,
                    enabled = loaded,
                    placeholder = { Text("例如 C:\\Users\\你\\.minecraft") },
                )
                OutlinedButton(
                    onClick = { pickDirectory(gameRoot)?.let { onGameRootChange(it.absolutePath) } },
                    enabled = loaded,
                ) { Text("选择…") }
            }
        }

        SettingCard(
            "默认内存",
            "新建实例时用这个数。标着「跟随默认」的实例也跟着它走 —— 在这里改一次，" +
                "那些实例一起变；在版本卡片或「版本设置」里把滑条拖到别的数，就改成单独设了。" +
                "默认按本机物理内存算，不想要就关掉开关自己拖。",
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("自动（按物理内存算）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (SystemMemory.totalMb > 0) {
                            "本机物理内存约 " + (SystemMemory.totalMb / 1024) + " GB，自动值 " +
                                autoMemoryMb + " MB（" + memoryPresetLabel(autoMemoryMb) + "，" +
                                MemoryDefaults.AUTO_PERCENT + "%）。"
                        } else {
                            "读不到本机物理内存，自动值退回 " + autoMemoryMb + " MB。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = memoryAuto, onCheckedChange = onMemoryAutoChange, enabled = loaded)
            }
            MemorySlider(
                valueMb = memoryText.trim().toIntOrNull() ?: autoMemoryMb,
                onChange = { onMemoryChange(it.toString()) },
                // 自动模式下这条滑条只是把算出来的数画出来，不让拖 —— 拖了就该切成手动，
                // 而「拖一下就悄悄改掉另一个开关」比灰着更难理解。
                enabled = loaded && !memoryAuto,
                showPresets = !memoryAuto,
            )
            Text(
                if (memoryAuto) {
                    "自动按物理内存的四成算，按 128 MB 取整、最多到物理内存减 1 GB。" +
                        "要固定一个数就把上面这个开关关掉，滑条跟着亮起来。"
                } else {
                    "范围 " + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + " MB，按 128 MB 一档；" +
                        "上限再看本机物理内存收窄（留 1 GB 给系统）。下面那排是常停的几档，点一下就跳过去。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingCard(
            "新建实例默认隔离",
            "刚装好的版本按这个来：开着，每一版自己一份 mods/、saves/、options.txt；" +
                "关掉，所有版本共用游戏根目录那一份。单个版本要例外，去它的「版本设置 → 高级 → 版本隔离」" +
                "把「跟随设置里的默认」关掉再改。",
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("默认开隔离", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (defaultIsolation) {
                            "新装的实例默认各管各的。已经装好的不受影响 —— 它们记着自己的值。"
                        } else {
                            "新装的实例默认共用游戏根目录。整体包（mods/ 平铺在根目录）用这种更省事。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = defaultIsolation,
                    onCheckedChange = onDefaultIsolationChange,
                    enabled = loaded,
                )
            }
        }

        SettingCard(
            "游戏语言",
            "每次进游戏前都写一次 " + GameOptions.LANG_KEY + ": 那一行 —— 文件是实例目录里的 " +
                "options.txt，不隔离的实例就在游戏根目录。游戏本来就支持这些语言，" +
                "只是没人愿意每次重装完再点一遍。",
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    // 启动器自己只有中文界面，没有「跟随启动器语言」这回事 —— 这一格能跟随的
                    // 只有操作系统的语言，所以文案得说清楚，否则用户会以为跟着界面走。
                    if (gameLanguage.isBlank()) {
                        "现在跟随系统语言：这台电脑会写成 " + GameLanguage.systemDefault() +
                            "（" + GameLanguage.labelOf(GameLanguage.systemDefault()) + "）。" +
                            "改这条只在进游戏那一刻生效，游戏里自己再改一次以你改的为准。"
                    } else {
                        "现在固定用 " + gameLanguage + "（" + GameLanguage.labelOf(gameLanguage) + "）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val options = listOf(GameLanguage.Choice("", "跟随系统语言")) + GameLanguage.CHOICES
                    options.forEach { choice ->
                        val picked = choice.id == gameLanguage
                        if (picked) {
                            Button(onClick = { onGameLanguageChange(choice.id) }, enabled = loaded) {
                                Text(choice.label)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onGameLanguageChange(choice.id) },
                                enabled = loaded,
                            ) { Text(choice.label) }
                        }
                    }
                }
            }
        }
    }
}


/**
 * 「下载」：同一个文件先走哪条路。
 *
 * 只有两个选项，且两个都不是「只走一条」—— 差别只是顺序。这条取舍写在
 * DownloadSource 的 KDoc 里，界面上也照原样说给用户：选「优先国内镜像」不代表
 * 拿不到官方原件，镜像挂了照样退回官方源；sha1 校验会兜住镜像内容坏掉的情况。
 */
@Composable
private fun DownloadTab(
    current: DownloadSource,
    onPick: (DownloadSource) -> Unit,
    curseKey: String,
    onCurseKeyChange: (String) -> Unit,
    onSaveCurseKey: (String) -> Unit,
    serverBase: String,
    serviceReady: Boolean,
    onServerBaseChange: (String) -> Unit,
    onSaveServerBase: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 空壳包才显示这一格：完整版的地址在包里写死，用户没有可填的东西。
        if (Endpoints.shell) {
            SettingCard(
                "服务地址",
                "这个包不带下载与搜索服务。填一个镜像根地址，版本清单、加载器、模组搜索都从它拼。",
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = serverBase,
                        onValueChange = onServerBaseChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("镜像根地址") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        placeholder = { Text("例如 https://你的镜像/") },
                    )
                    Button(onClick = { onSaveServerBase(serverBase) }) { Text("保存地址") }
                    if (serverBase.isNotBlank()) {
                        OutlinedButton(onClick = { onSaveServerBase("") }) { Text("清掉") }
                    }
                }
                Text(
                    "约定：这个根替代所有官方主机，路径照抄 BMCLAPI 的布局。填 https://mirror.example.com，" +
                        "版本清单就取 /mc/game/version_manifest_v2.json，资源文件取 /<前两位>/<sha1>。" +
                        "也就是说填的人要自备一个能代理这些上游站的镜像，不填就什么都下载不了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!serviceReady) {
                    Text(
                        "现在还没填，所以下载页与模组搜索都是空的 —— 这不是坏了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        SettingCard(
            "下载源",
            "影响所有下载：游戏本体、模组、整合包、Java、服务端。改完立刻生效，下次下载就按新顺序走。",
        ) {
            DownloadSource.entries.forEach { option ->
                DownloadSourceRow(
                    option = option,
                    selected = option == current,
                    onSelect = { onPick(option) },
                )
            }
        }
        SettingCard(
            "镜像到底是什么",
            "镜像不是另一份内容，是同一批文件的另一台服务器。下载器按文件名与 sha1 校验，" +
                "镜像给的东西对不上就会被判失败并退回官方源，不会装上一个坏文件。",
        ) {
            Text(
                if (Endpoints.shell) {
                    // 空壳包里不能点名任何现成镜像：那一份是别人的机器，随包发出去等于让所有
                    // 下载者共用别人的带宽与配额 —— 正是这一版要断掉的事。这里只说「填进去的那个」。
                    "游戏本体、libraries、assets 都走上面填的那个根。模组与整合包本来就是从 " +
                        "Modrinth / CurseForge 直接下，这一项对它们没有影响。"
                } else {
                    "游戏本体、libraries、assets 都走上面选的那条源。" +
                        "模组与整合包本来就是从 Modrinth / CurseForge 直接下，没有国内镜像可用，" +
                        "这一项对它们没有影响。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingCard(
            "CurseForge API Key（可选）",
            "只在导出 CurseForge 格式的整合包时用到：靠它把包里的文件对上「哪个项目的哪个文件」，" +
                "写进 manifest.json 的 files 列表。不填也能导出，只是那份列表空着。",
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = curseKey,
                    onValueChange = onCurseKeyChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("API Key") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("粘贴 CurseForge 后台给你的那串字符") },
                )
                Button(onClick = { onSaveCurseKey(curseKey) }) { Text("保存 Key") }
                if (curseKey.isNotBlank()) {
                    OutlinedButton(onClick = { onSaveCurseKey("") }) { Text("清掉") }
                }
            }
            val problem = io.github.huang404yan.platypus.core.mods.CurseForgeLookup.apiKeyProblem(curseKey)
            if (problem != null) {
                Text(
                    problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "为什么不内置一个：这个 Key 会随源码一起公开，轻则被刷爆额度，重则整个应用被封。" +
                    "去 CurseForge 开发者后台（docs.curseforge.com 里的「Get an API key」）免费申请一个，填一次就行。" +
                    "它只存在本机的设置文件里，不进安装包、不进仓库。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadSourceRow(
    option: DownloadSource,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                option.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                option.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Text(
                "✓",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 「存档」：自动备份的开关、间隔与保留份数。
 *
 * 为什么默认开：存档是玩家唯一没法重新下载的东西。模组能重下、Java 能重装、
 * 整合包能重导，只有世界里那几百小时没了就是没了 —— 而「记得手动备份」这件事，
 * 人是做不到的。代价只是备份目录里几个 zip。
 *
 * 触发时机是每次游戏正常退出之后（见 [io.github.huang404yan.platypus.ui.LaunchPage]）：
 * 那时世界文件已经全部落盘，是唯一不会备到半份数据的时刻。
 *
 * 与「下载」「界面」不同，这一页的三个值要在底部点「保存」才生效 ——
 * 间隔和份数是输入框，边敲边存会把「2」当成最终值写下去（用户想敲的是 24）。
 */
@Composable
private fun SavesTab(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    hoursText: String,
    onHoursChange: (String) -> Unit,
    keepText: String,
    onKeepChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingCard(
            "自动备份存档",
            "每次游戏正常退出后，把该备份的世界打成一个 zip 放进 platypus/saves-backup。"
                + "改完点下面的「保存」才生效。",
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { onEnabledChange(!enabled) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Checkbox(checked = enabled, onCheckedChange = onEnabledChange)
                Column(Modifier.weight(1f)) {
                    Text("开", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "关掉之后一份都不会自动备；手动备份（实例详情 → 存档与备份）不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { onHoursChange(it.filter { ch -> ch.isDigit() }.take(3)) },
                    label = { Text("间隔（小时）") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.width(150.dp),
                )
                Text(
                    AutoBackupPolicy.INTERVAL_RANGE.first.toString() + "–" +
                        AutoBackupPolicy.INTERVAL_RANGE.last + " 小时。默认 " +
                        AutoBackupPolicy.DEFAULT_INTERVAL_HOURS + " 小时：一天玩一次的人第二天就会有一份新的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = keepText,
                    onValueChange = { onKeepChange(it.filter { ch -> ch.isDigit() }.take(2)) },
                    label = { Text("每个世界留几份") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.width(150.dp),
                )
                Text(
                    "按世界分别算，不是所有世界一共几张。超出时从最旧的那份开始删，" +
                        "最新那份永远留着。默认 " + AutoBackupPolicy.DEFAULT_KEEP + " 份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        SettingCard("什么时候备、备哪些", "这几条是刻意这么定的，不是漏了。") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoLine("时机", "游戏正常退出之后；崩在启动阶段没进世界的那种不算，那时存档没变")
                InfoLine("判据", "按每个世界各自的「上次备份时间」算，不是全局一次 —— 新开的世界第一次就备")
                InfoLine("跳过", "目录里读不出 level.dat 的、文件数为 0 的（空目录）")
                InfoLine("失败", "某个世界备份失败只影响它自己，其余照备；失败时不会删它的旧备份")
                InfoLine("还原", "实例详情 →「存档与备份」里能看到并还原自动备份出来的 zip")
            }
        }
        SettingCard("还没做的", "没做的不摆控件。") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoLine("定时", "只有「退出游戏后」这一个触发点，没有后台定时器 —— 启动器关着的时候不该在背后动文件")
                InfoLine("云备份", "只备到本机 platypus/saves-backup，没有上传")
                InfoLine("整目录", "一个世界一个 zip，不能把整个 saves/ 一次打包")
            }
        }
    }
}

/** 「界面」：选配色。
 *
 * 只有两套可选，外加一个「跟随版本」。这不是设置项没做完 —— 配色多到能调的时候，
 * 用户会先花十分钟纠结，然后所有页面的验收都要乘以主题数量。两套够用来区分
 * 正式版与孵化版的气质，也够让晚上玩的人自己挑一个不刺眼的。
 *
 * 点一下立刻生效并落盘，不跟底部「保存」按钮走：换配色是即时反馈的事，
 * 点完还要再点一次保存，中间那一眼看到的新颜色就像没生效。
 */
@Composable
private fun InterfaceTab(
    theme: LauncherTheme,
    onPick: (LauncherTheme) -> Unit,
    uiMode: UiMode,
    onPickUiMode: (UiMode) -> Unit,
    reduceMotion: Boolean,
    onPickReduceMotion: (Boolean) -> Unit,
) {
    val effective = theme.resolve(Brand.isPreview)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingCard(
            "配色",
            "两套。「跟随版本」会按你现在装的是正式版还是孵化版自动挑："
                + "孵化版走亮一点的蓝橙，正式版走护眼的深色。手动选过就记住你的选择，不再看版本号。"
                + "改完立刻生效并记下来，这一页没有「保存」按钮。",
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LauncherTheme.entries.forEach { option ->
                    ThemeRow(
                        option = option,
                        // 「跟随版本」要显示它现在实际落在哪一套，否则用户看不出自己看到的是哪个。
                        effective = if (option == LauncherTheme.AUTO) effective else option.resolve(Brand.isPreview),
                        selected = option == theme,
                        onClick = { onPick(option) },
                    )
                }
                // 标题栏不跟着配色走，这一点必须单独说明：用户切到深色后发现顶部
                // 还是浅色那条，很容易当成 bug 反复切。用 tertiary 色让它和普通
                // 说明文字区分开，不至于被当成又一段描述扫过去。
                Text(
                    "窗口标题栏的颜色由版本决定（正式版深色、孵化版浅色），"
                        + "切换上面的配色只改界面，不会改标题栏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        SettingCard(
            "界面模式",
            "两档看的是一份东西，只是收起来多少不一样。简洁模式把不常用的二级项收进「更多」，"
                + "启动页与版本设置页里也少几块；切回完整模式原样都在，盘上的实例、存档、模组一个字节不动。"
                + "改完立刻生效并记下来，这一页同样没有「保存」按钮。",
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UiMode.entries.forEach { option ->
                    UiModeRow(
                        option = option,
                        selected = option == uiMode,
                        onClick = { onPickUiMode(option) },
                    )
                }
            }
        }
        SettingCard(
            "动效",
            "换页、换二级项、副页面入栈都带一点过渡，用的是同一套时长（见界面上「动效」这一档）。"
                + "远程桌面、录屏或机器比较慢时勾上这个，全部改成瞬间到位 —— "
                + "动画只是提示「换了」，关掉不影响任何功能。改完立刻生效并记下来。",
        ) {
            Row(
                Modifier.fillMaxWidth().clickable { onPickReduceMotion(!reduceMotion) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = reduceMotion, onCheckedChange = { onPickReduceMotion(it) })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("少动一点", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "换页、展开、提示这些改成直接出现，颜色与布局不变。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SettingCard("还没做的", "没做的不摆控件。") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoLine("字号", "Material 3 默认五档，未提供整体缩放")
                InfoLine("导航", "左侧一级栏 + 选中项的二级项；二级项的选中状态随页面切换保留到本次运行结束")
            }
        }
    }
}

/**
 * 一行界面模式选项。
 *
 * 与 [ThemeRow] 同一个造型（整行可点、选中打勾），但左边不放色块 ——
 * 两档的差别不是颜色，给了色块反而像在说「简洁模式是另一套皮肤」。
 * 改成两块「次要项显示 / 收起」的小示意：上面一行实心、下面一行只有轮廓，
 * 一眼能看出「收起」指的是列表长度而不是功能多少。
 */
@Composable
private fun UiModeRow(option: UiMode, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.width(52.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val shown = if (option == UiMode.FULL) 4 else 2
                repeat(4) { index ->
                    val on = index < shown
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(
                                if (on) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                                },
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    uiModeNoteOf(option),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 每档模式底下那行小字。设置页与首次选择卡共用一份，免得两处写得不一样。 */
internal fun uiModeNoteOf(option: UiMode): String = when (option) {
    UiMode.FULL -> "二级项全平铺；启动页能改目录、Java、内存；版本设置里有「高级」与「导出」"
    UiMode.SIMPLE -> "不常用的二级项收进「更多」；启动页只留身份、版本、启动；导出收成总览一行"
}

/**
 * 一行配色选项：左边一块双色小样，中间名字与说明，右边选中标记。
 *
 * 小样用两格而不是一整块色：用户要判断的是「底是深的还是浅的、强调色跳不跳」，
 * 两个方块并排放比一整块纯色更有信息量。
 */
@Composable
private fun ThemeRow(option: LauncherTheme, effective: LauncherTheme, selected: Boolean, onClick: () -> Unit) {
    val palette = paletteOf(effective)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.width(52.dp).height(30.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(palette.background)))
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(palette.primary)))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    themeNoteOf(option, effective),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 每个选项底下那行小字。 */
private fun themeNoteOf(option: LauncherTheme, effective: LauncherTheme): String = when (option) {
    LauncherTheme.AUTO -> "现在用的是「" + effective.label + "」"
    LauncherTheme.NATURAL -> "深潭青底、泥土棕卡片、鸭嘴橙；夜里不刺眼"
    LauncherTheme.PERRY -> "灰白底、泰瑞蓝绿模块、亮橙；清爽高对比"
}

/** 「其他」：文件在哪、上次什么时候存的、怎么恢复默认。 */
@Composable
private fun OtherTab(
    loadedFrom: String,
    savedAt: Long,
    loaded: Boolean,
    onResetDefaults: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingCard("设置文件", "纯 JSON，可以直接用编辑器改；改坏了删掉它就会回到默认值。") {
            Text(
                if (loadedFrom.isBlank()) "还没读到路径（可能是后端把设置存在别处）。" else loadedFrom,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (savedAt > 0L) {
                    "上次保存：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(savedAt))
                } else {
                    "这次运行还没保存过。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingCard("恢复默认值", "只是把输入框填回默认，要点「保存」才写进文件。") {
            OutlinedButton(onClick = onResetDefaults, enabled = loaded) { Text("填回默认值") }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 2.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 设置页各二级项共用的一张卡片；「Java」页在别的文件里也要用，所以是 internal。 */
@Composable
internal fun SettingCard(title: String, hint: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}
