package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.MemoryDefaults
import io.github.huang404yan.platypus.core.launch.GameLanguage
import io.github.huang404yan.platypus.core.theme.LauncherTheme
import io.github.huang404yan.platypus.core.theme.paletteOf
import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import io.github.huang404yan.platypus.core.settings.normalizeOfflineUsername
import io.github.huang404yan.platypus.core.settings.usernameProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页（手机）。
 *
 * 与桌面 SettingsPage 读写同一个 [JsonSettingsRepository]：游戏目录下的
 * `platypus/settings.json`，所以同一个目录在手机与桌面之间来回拷也能读。
 *
 * 只放几件真正影响「关掉再开还能用」的事：离线用户名、默认游戏目录、默认内存、
 * 新建实例默认隔离、游戏语言。
 * 实例自己的内存与隔离仍然存在 instances.json 里，这里只决定「第一次建实例按什么来」。
 */
@Composable
internal fun SettingsPage(theme: LauncherTheme = LauncherTheme.AUTO, onThemeChange: (LauncherTheme) -> Unit = {}) {
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var repo by remember { mutableStateOf<JsonSettingsRepository?>(null) }
    // 读出来的整份设置。保存时在它上面改三个字段再写回去，
    // 不能凭空 new 一个 LauncherSettings(...) —— 那会把桌面端写进来的
    // activeAccountUuid、microsoftClientId、theme 一起冲成默认值。
    var base by remember { mutableStateOf(LauncherSettings()) }
    var username by remember { mutableStateOf(LauncherSettings.DEFAULT_OFFLINE_USERNAME) }
    var gameRoot by remember { mutableStateOf(defaultGameRoot()) }
    var memoryMb by remember { mutableStateOf(Instance.DEFAULT_MEMORY_MB.toString()) }
    var memoryAuto by remember { mutableStateOf(true) }
    // 新建实例默认隔离：与桌面端同一个字段，实例里标了 isolatedFollowsDefault 的跟着它走。
    var defaultIsolation by remember { mutableStateOf(true) }
    // 游戏语言：空串 = 跟随系统（进游戏时按手机语言映射一个），其余是这个值的原样。
    var gameLanguage by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    // 「自动」在安卓上按设备的物理内存算，与桌面端同一个 MemoryDefaults。
    // 拿不到（ActivityManager 异常、或旧机型不给 totalMem）就是 0，autoMb 会退回 2048。
    val context = LocalContext.current
    val autoMemoryMb = MemoryDefaults.autoMb(deviceTotalMemoryMb(context))

    suspend fun load() {
        val root = File(gameRoot.trim().ifBlank { defaultGameRoot() })
        val store = JsonSettingsRepository(JsonSettingsRepository.defaultFile(root))
        val settings = withContext(Dispatchers.IO) { store.load() }
        repo = store
        base = settings
        username = settings.offlineUsername
        if (settings.defaultGameRoot.isNotBlank()) gameRoot = settings.defaultGameRoot
        memoryAuto = settings.defaultMemoryAuto
        memoryMb = MemoryDefaults.resolve(settings.defaultMemoryMb, settings.defaultMemoryAuto, deviceTotalMemoryMb(context))
            .toString()
        defaultIsolation = settings.defaultIsolation
        gameLanguage = settings.gameLanguage
        // 进程级镜像：起 JVM 前手机这边也是先读设置再灌进去，这里顺手对齐一次。
        InstanceLayouts.defaultIsolated = settings.defaultIsolation
        loaded = true
    }

    suspend fun save() {
        val store = repo ?: return
        // 自动模式下输入框是灰的、值就是算出来的那个，不用再解析它。
        val memory = if (memoryAuto) autoMemoryMb else memoryMb.trim().toIntOrNull()
        if (memory == null) {
            status = "内存要填数字（MB），比如 2048。"
            return
        }
        val problem = usernameProblem(username)
        try {
            val saved = withContext(Dispatchers.IO) {
                store.save(
                    base.copy(
                        offlineUsername = normalizeOfflineUsername(username),
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = memory,
                        defaultMemoryAuto = memoryAuto,
                        defaultIsolation = defaultIsolation,
                        gameLanguage = gameLanguage,
                        theme = theme.id,
                    ),
                )
            }
            username = saved.offlineUsername
            defaultIsolation = saved.defaultIsolation
            gameLanguage = saved.gameLanguage
            InstanceLayouts.defaultIsolated = saved.defaultIsolation
            memoryAuto = saved.defaultMemoryAuto
            memoryMb = MemoryDefaults.resolve(saved.defaultMemoryMb, saved.defaultMemoryAuto, deviceTotalMemoryMb(context))
                .toString()
            status = "已保存到 " + store.path +
                if (problem != null) "（注意：$problem）" else ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "保存失败：" + (e.message ?: e.toString())
        }
    }

    /**
     * 换配色：立刻换皮 + 立刻落盘。
     *
     * 不跟下面那个「保存」按钮走 —— 换配色是即时反馈，再点一次保存，
     * 中间那一眼看到的新颜色就像没生效。其余字段从 [base] 上带，不动用户没碰的东西。
     */
    fun pickTheme(picked: LauncherTheme) {
        onThemeChange(picked)
        val store = repo ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { store.save(base.copy(theme = picked.id)) }
            }.onSuccess { stored ->
                base = stored
                status = "配色已改成「" + picked.label + "」。"
            }.onFailure {
                status = "配色没能存下来：" + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "存在游戏目录下的 platypus/settings.json，与桌面端同一个文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingCard(
            "配色",
            "两套。「跟随版本」按你现在装的是正式版还是孵化版自动挑："
                + "孵化版走亮一点的蓝橙，正式版走护眼的深色。手动选过就记住，不再看版本号。"
                + "点一下立刻生效，不用再点下面的「保存」。",
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LauncherTheme.entries.forEach { option ->
                    ThemeRow(
                        option = option,
                        effective = if (option == LauncherTheme.AUTO) {
                            theme.resolve(Brand.isPreview)
                        } else {
                            option.resolve(Brand.isPreview)
                        },
                        selected = option == theme,
                        onClick = { pickTheme(option) },
                    )
                }
            }
        }

        SettingCard("离线用户名", "离线模式进游戏用的名字；3–16 位，字母数字下划线。") {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            val problem = usernameProblem(username)
            if (problem != null) {
                Text(problem, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }

        SettingCard("游戏目录（.minecraft）", "扫描实例、下载、装模组都指向这里。") {
            OutlinedTextField(
                value = gameRoot,
                onValueChange = { gameRoot = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            val exists = File(gameRoot.trim()).isDirectory
            Text(
                if (exists) "目录存在。" else "目录不存在（第一次下载时会建）。",
                style = MaterialTheme.typography.labelSmall,
                color = if (exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }

        SettingCard("新实例拿多少内存", "之后可以在实例页单独改。") {
            // 「自动」按这台手机的物理内存算（四成、按 128 MB 取整、上限留 1 GB 给系统），
            // 与桌面端共用 MemoryDefaults，所以同一份 settings.json 两边读出来的数一致。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("自动（按物理内存算）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "本机物理内存约 " + formatGbShort(deviceTotalMemoryMb(context)) +
                            "，自动值 " + autoMemoryMb + " MB（" + MemoryDefaults.AUTO_PERCENT + "%）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = memoryAuto, onCheckedChange = { memoryAuto = it }, enabled = loaded)
            }
            OutlinedTextField(
                value = memoryMb,
                onValueChange = { memoryMb = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = loaded && !memoryAuto,
                label = { Text(if (memoryAuto) "自动算出来的值" else "MB") },
            )
            Text(
                if (memoryAuto) {
                    "关掉上面那个开关才能手填。允许范围 " + Instance.MIN_MEMORY_MB + "–" +
                        Instance.MAX_MEMORY_MB + " MB。"
                } else {
                    "允许范围 " + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + " MB。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingCard(
            "新建实例默认隔离",
            "刚装好的版本按这个来：开着是各自一个目录（存档、模组都各管各的），关掉是共用游戏根目录。"
                + "已经单独设过的版本不受影响 —— 去它的「版本设置 → 高级 → 版本隔离」把「跟随设置里的默认」关掉再改。",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("默认开隔离", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (defaultIsolation) "新装的版本各用各的目录。" else "新装的版本共用游戏根目录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = defaultIsolation, onCheckedChange = { defaultIsolation = it }, enabled = loaded)
            }
        }

        SettingCard(
            "游戏语言",
            "每次进游戏前都写一次 options.txt 的 lang: 那一行。只改这一行，别的键不动。",
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    // 启动器界面只有中文，能跟随的只有系统语言；这么写是为了别让人以为
                    // 「跟随」跟的是启动器界面。
                    if (gameLanguage.isBlank()) {
                        "现在跟随系统语言：这台手机映射成「" + GameLanguage.labelOf(GameLanguage.systemDefault()) +
                            "」（" + GameLanguage.systemDefault() + "）。"
                    } else {
                        "现在固定用「" + GameLanguage.labelOf(gameLanguage) + "」（" + gameLanguage + "）。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 「跟随系统语言」排第一项，后面是固定语言。手机上竖排一行一个，比挤在 FlowRow 里好点。
                (listOf(GameLanguage.Choice("", "跟随系统语言")) + GameLanguage.CHOICES).forEach { option ->
                    val selected = option.id == gameLanguage
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { gameLanguage = option.id },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (selected) {
                                Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { save() } }, enabled = loaded) { Text("保存") }
            OutlinedButton(
                onClick = {
                    username = LauncherSettings.DEFAULT_OFFLINE_USERNAME
                    gameRoot = defaultGameRoot()
                    memoryAuto = true
                    memoryMb = autoMemoryMb.toString()
                    defaultIsolation = true
                    gameLanguage = ""
                },
                enabled = loaded,
            ) { Text("填回默认值") }
        }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("这个设置文件在哪", fontWeight = FontWeight.SemiBold)
                Text(
                    repo?.path ?: JsonSettingsRepository.defaultFile(File(gameRoot.trim())).absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 一行配色选项：左边一块双色小样，中间名字与说明，右边选中标记。
 *
 * 手机上横向更挤，所以小样只有 44×26，说明那行也只留一句。
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
            Row(Modifier.width(44.dp).height(26.dp)) {
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
                    when (option) {
                        LauncherTheme.AUTO -> "现在用的是「" + effective.label + "」"
                        LauncherTheme.NATURAL -> "深潭青底、泥土棕卡片、鸭嘴橙"
                        LauncherTheme.PERRY -> "灰白底、泰瑞蓝绿模块、亮橙"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Text("✓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, hint: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** 把 MB 写成「16 GB」「7.5 GB」这样的短标签，只在这一页用。 */
private fun formatGbShort(totalMb: Int): String {
    if (totalMb <= 0) return "未知"
    val gb = totalMb / 1024.0
    val rounded = Math.round(gb * 10) / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString() + " GB"
    } else {
        String.format(java.util.Locale.US, "%.1f GB", rounded)
    }
}
