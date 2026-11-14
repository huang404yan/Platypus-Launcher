package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.settings.LauncherSettings
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

/** 「Java」二级项的 id。 */
const val SETTINGS_TAB_JAVA = "java"

/** 「其他」二级项的 id。 */
const val SETTINGS_TAB_OTHER = "other"

/**
 * 设置页：启动器级别的三件事——离线用户名、默认游戏目录、默认内存。
 *
 * 都直接决定「双击能不能玩起来」：名字写死改不了、目录每次重填、新实例内存给错，
 * 都会把用户堵在启动前。实例自己的内存仍然在启动页改、存在 instances.json 里，
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
    /** [SETTINGS_TAB_LAUNCH] / [SETTINGS_TAB_ACCOUNT] / [SETTINGS_TAB_JAVA] / [SETTINGS_TAB_UI] / [SETTINGS_TAB_OTHER]。 */
    subTab: String = SETTINGS_TAB_LAUNCH,
    /**
     * 侧边栏那个「↻」的计数，只有「Java」页用得上（重读一次官方运行时清单）。
     *
     * 别的二级项没有可重读的远端清单，传了也不理 —— 与下载页那几项同一个约定。
     */
    refreshTick: Int = 0,
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
    var loaded by remember { mutableStateOf(false) }
    var loadedFrom by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var noticeIsError by remember { mutableStateOf(false) }
    var savedAt by remember { mutableStateOf(0L) }
    var activeAccountUuid by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val settings = withContext(Dispatchers.IO) { runCatching { repository.load() }.getOrDefault(LauncherSettings()) }
        username = settings.offlineUsername
        gameRoot = settings.defaultGameRoot
        memoryText = settings.defaultMemoryMb.toString()
        activeAccountUuid = settings.activeAccountUuid
        loaded = true
        loadedFrom = (repository as? JsonSettingsRepository)?.path.orEmpty()
    }

    fun save() {
        val parsedMemory = memoryText.trim().toIntOrNull()
        if (parsedMemory == null) {
            notice = "默认内存要填整数，单位 MB。"
            noticeIsError = true
            return
        }
        val clamped = Instance.clampMemoryMb(parsedMemory)
        val cleanedName = normalizeOfflineUsername(username)
        scope.launch {
            val result = runCatching {
                repository.save(
                    LauncherSettings(
                        offlineUsername = cleanedName,
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = clamped,
                        activeAccountUuid = activeAccountUuid,
                    ),
                )
            }
            result.onSuccess { stored ->
                username = stored.offlineUsername
                gameRoot = stored.defaultGameRoot
                memoryText = stored.defaultMemoryMb.toString()
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
                    LauncherSettings(
                        offlineUsername = normalizeOfflineUsername(username),
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = Instance.clampMemoryMb(parsedMemory),
                        activeAccountUuid = uuid,
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

    fun resetDefaults() {
        username = LauncherSettings.DEFAULT_OFFLINE_USERNAME
        gameRoot = ""
        memoryText = Instance.DEFAULT_MEMORY_MB.toString()
        activeAccountUuid = ""
        noticeIsError = false
        notice = "已填回默认值，点「保存」才生效。"
    }

    val nameProblem = usernameProblem(username)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "这里的改动会存成一份 JSON，下次打开程序还在。实例自己内存请去启动页改。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (subTab) {
            // Java 页不读写设置文件，所以下面那排「保存 / 填回默认值」对它不显示。
            // 账号页自己落盘（点「用这个」立刻生效），不走下面那排保存。
            SETTINGS_TAB_ACCOUNT -> AccountTab(
                backend = backend,
                activeUuid = activeAccountUuid,
                onUseAccount = { useAccount(it) },
                onUseOffline = { useAccount("") },
            )
            SETTINGS_TAB_JAVA -> JavaTab(backend, gameRoot, refreshTick)
            SETTINGS_TAB_UI -> InterfaceTab()
            SETTINGS_TAB_OTHER -> OtherTab(
                loadedFrom = loadedFrom,
                savedAt = savedAt,
                loaded = loaded,
                onResetDefaults = { resetDefaults() },
            )
            else -> LaunchTab(
                username = username,
                onUsernameChange = { username = it },
                nameProblem = nameProblem,
                gameRoot = gameRoot,
                onGameRootChange = { gameRoot = it },
                memoryText = memoryText,
                onMemoryChange = { memoryText = it },
                loaded = loaded,
            )
        }

        if (subTab != SETTINGS_TAB_JAVA && subTab != SETTINGS_TAB_ACCOUNT) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { save() }, enabled = loaded) { Text("保存") }
                OutlinedButton(onClick = { resetDefaults() }, enabled = loaded) { Text("填回默认值") }
            }
        }

        if (notice.isNotBlank()) {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 「启动」：进游戏时会用到的那几项。 */
@Composable
private fun LaunchTab(
    username: String,
    onUsernameChange: (String) -> Unit,
    nameProblem: String?,
    gameRoot: String,
    onGameRootChange: (String) -> Unit,
    memoryText: String,
    onMemoryChange: (String) -> Unit,
    loaded: Boolean,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingCard("离线用户名", "进游戏时显示的名字。离线 UUID 由名字算出来，换名字等于换一个玩家身份。") {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("离线用户名") },
                singleLine = true,
                enabled = loaded,
                isError = nameProblem != null,
                supportingText = {
                    Text(
                        nameProblem ?: "3–16 位，英文字母、数字或下划线。",
                        color = if (nameProblem != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
        }

        SettingCard(
            "默认游戏目录",
            "启动页与实例页会用它做初值；下载页也默认往这里装。留空表示每次按系统默认位置猜。",
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
            "新实例默认内存",
            "在下载页一键装完时会按这个值登记新实例。之后可以在这个实例的「详情」里单独改 —— " +
                "这里只是初值，改这里不会动已经建好的实例。",
        ) {
            OutlinedTextField(
                value = memoryText,
                onValueChange = onMemoryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("内存（MB）") },
                singleLine = true,
                enabled = loaded,
                isError = memoryText.trim().toIntOrNull() == null,
                supportingText = {
                    Text(
                        "允许范围 " + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + " MB，默认 " +
                            Instance.DEFAULT_MEMORY_MB + " MB。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

/**
 * 「界面」：现在全是只读信息。
 *
 * 没有真控件可放 —— 主题、字号、动画开关属于阶段三（美化 U1–U4），
 * 与其在这里摆一排点不动的开关，不如把「当前是什么样、去哪儿改」讲清楚。
 */
@Composable
private fun InterfaceTab() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingCard("当前界面", "下面这些暂时不可调，阶段三会做成可选项。") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoLine("主题", "跟随界面的默认配色（紫调 Material 3），深浅色还没做")
                InfoLine("字号", "Material 3 默认五档，未提供整体缩放")
                InfoLine("导航", "左侧一级栏 + 选中项的二级项；二级项的选中状态随页面切换保留到本次运行结束")
                InfoLine("窗口", "启动后可以随意拉伸；内容区按可用宽度自适应")
            }
        }
        SettingCard("为什么这里是空的", "没做的设置项不摆控件。") {
            Text(
                "往下要补的是：深浅色切换、整体字号、减少动画（无障碍）。" +
                    "这些要么牵动整套配色，要么要逐个页面调，做在功能补齐之后才不会返工。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
