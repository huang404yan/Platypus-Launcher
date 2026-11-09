package io.github.huang404yan.platypus.launcher.preview

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
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
 * 只放三件真正影响「关掉再开还能用」的事：离线用户名、默认游戏目录、默认内存。
 * 实例自己的内存仍然存在 instances.json 里，这里只决定「第一次建实例给多少」。
 */
@Composable
internal fun SettingsPage() {
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var repo by remember { mutableStateOf<JsonSettingsRepository?>(null) }
    var username by remember { mutableStateOf(LauncherSettings.DEFAULT_OFFLINE_USERNAME) }
    var gameRoot by remember { mutableStateOf(defaultGameRoot()) }
    var memoryMb by remember { mutableStateOf(Instance.DEFAULT_MEMORY_MB.toString()) }
    var status by remember { mutableStateOf("") }

    suspend fun load() {
        val root = File(gameRoot.trim().ifBlank { defaultGameRoot() })
        val store = JsonSettingsRepository(JsonSettingsRepository.defaultFile(root))
        val settings = withContext(Dispatchers.IO) { store.load() }
        repo = store
        username = settings.offlineUsername
        if (settings.defaultGameRoot.isNotBlank()) gameRoot = settings.defaultGameRoot
        memoryMb = settings.defaultMemoryMb.toString()
        loaded = true
    }

    suspend fun save() {
        val store = repo ?: return
        val memory = memoryMb.trim().toIntOrNull()
        if (memory == null) {
            status = "内存要填数字（MB），比如 2048。"
            return
        }
        val problem = usernameProblem(username)
        try {
            val saved = withContext(Dispatchers.IO) {
                store.save(
                    LauncherSettings(
                        offlineUsername = normalizeOfflineUsername(username),
                        defaultGameRoot = gameRoot.trim(),
                        defaultMemoryMb = memory,
                    ),
                )
            }
            username = saved.offlineUsername
            memoryMb = saved.defaultMemoryMb.toString()
            status = "已保存到 " + store.path +
                if (problem != null) "（注意：$problem）" else ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "保存失败：" + (e.message ?: e.toString())
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

        SettingCard("内存（MB）", "新实例默认值，之后可以在实例页单独改。") {
            OutlinedTextField(
                value = memoryMb,
                onValueChange = { memoryMb = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                "允许范围 " + Instance.MIN_MEMORY_MB + "–" + Instance.MAX_MEMORY_MB + " MB。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { save() } }, enabled = loaded) { Text("保存") }
            OutlinedButton(
                onClick = {
                    username = LauncherSettings.DEFAULT_OFFLINE_USERNAME
                    gameRoot = defaultGameRoot()
                    memoryMb = Instance.DEFAULT_MEMORY_MB.toString()
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
