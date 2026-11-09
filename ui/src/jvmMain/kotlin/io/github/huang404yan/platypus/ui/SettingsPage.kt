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

/**
 * 设置页：启动器级别的三件事——离线用户名、默认游戏目录、默认内存。
 *
 * 都直接决定「双击能不能玩起来」：名字写死改不了、目录每次重填、新实例内存给错，
 * 都会把用户堵在启动前。实例自己的内存仍然在启动页改、存在 instances.json 里，
 * 这里只决定「新建实例的初值」。
 *
 * 读写都走 [io.github.huang404yan.platypus.core.settings.SettingsRepository]，
 * 界面只管输入与提示，不碰文件。
 */
@Composable
fun SettingsPage(backend: LauncherBackend?) {
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

    LaunchedEffect(Unit) {
        val settings = withContext(Dispatchers.IO) { runCatching { repository.load() }.getOrDefault(LauncherSettings()) }
        username = settings.offlineUsername
        gameRoot = settings.defaultGameRoot
        memoryText = settings.defaultMemoryMb.toString()
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

    fun resetDefaults() {
        username = LauncherSettings.DEFAULT_OFFLINE_USERNAME
        gameRoot = ""
        memoryText = Instance.DEFAULT_MEMORY_MB.toString()
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

        SettingCard("离线用户名", "进游戏时显示的名字。离线 UUID 由名字算出来，换名字等于换一个玩家身份。") {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
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
            OutlinedTextField(
                value = gameRoot,
                onValueChange = { gameRoot = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("游戏目录（.minecraft）") },
                singleLine = true,
                enabled = loaded,
                placeholder = { Text("例如 C:\\Users\\你\\.minecraft") },
            )
        }

        SettingCard("新实例默认内存", "在下载页一键装完时会按这个值登记新实例。之后可以在启动页单独改。") {
            OutlinedTextField(
                value = memoryText,
                onValueChange = { memoryText = it },
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

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { save() }, enabled = loaded) { Text("保存") }
            OutlinedButton(onClick = { resetDefaults() }, enabled = loaded) { Text("填回默认值") }
        }

        if (notice.isNotBlank()) {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }

        if (loadedFrom.isNotBlank()) {
            Text(
                "设置文件：" + loadedFrom,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (savedAt > 0L) {
            Text(
                "上次保存：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(savedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingCard(title: String, hint: String, content: @Composable () -> Unit) {
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
