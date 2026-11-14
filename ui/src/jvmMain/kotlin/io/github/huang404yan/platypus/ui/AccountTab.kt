package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.auth.DeviceCodeGrant
import io.github.huang404yan.platypus.core.auth.MicrosoftAccountAuth
import io.github.huang404yan.platypus.core.auth.StoredAccount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账号页：微软正版登录。
 *
 * 流程照 PCL 与各家启动器都在用的设备码（device code）：点登录 → 这里给出一个短码 →
 * 用户拿手机或浏览器去 microsoft.com/link 输码 → 这里轮询到结果。好处是不用起本地
 * 回调端口（防火墙、端口占用、浏览器关联都不碰），代价是用户要多抄一次码。
 *
 * 登录成功只留下 refresh_token（存进 <游戏目录>/platypus/accounts.json）。启动游戏时
 * 拿它现换一份会话 —— MC 令牌两小时就过期，缓存没有意义。
 *
 * [onUseAccount] 负责把选中的账号写进设置；[onUseOffline] 清掉这个选择。
 */
@Composable
internal fun AccountTab(
    backend: LauncherBackend?,
    activeUuid: String,
    onUseAccount: (String) -> Unit,
    onUseOffline: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val repository = backend?.accounts
    val auth = backend?.auth as? MicrosoftAccountAuth

    var accounts by remember { mutableStateOf<List<StoredAccount>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var grant by remember { mutableStateOf<DeviceCodeGrant?>(null) }
    var remaining by remember { mutableStateOf(0) }
    var loggingIn by remember { mutableStateOf(false) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var notice by remember { mutableStateOf("") }
    var noticeIsError by remember { mutableStateOf(false) }

    LaunchedEffect(repository) {
        loading = true
        accounts = withContext(Dispatchers.IO) {
            repository?.let { runCatching { it.list() }.getOrNull() }.orEmpty()
        }
        loading = false
    }

    fun reload() {
        scope.launch {
            accounts = withContext(Dispatchers.IO) {
                repository?.let { runCatching { it.list() }.getOrNull() }.orEmpty()
            }
        }
    }

    fun startLogin() {
        if (auth == null) {
            noticeIsError = true
            notice = "当前后端没有提供登录实现，正版登录不可用。"
            return
        }
        notice = ""
        noticeIsError = false
        grant = null
        remaining = 0
        loggingIn = true
        loginJob = scope.launch {
            try {
                val stored = auth.login(
                    onCode = { code -> grant = code },
                    onTick = { seconds -> remaining = seconds },
                )
                accounts = withContext(Dispatchers.IO) {
                    repository?.let { runCatching { it.list() }.getOrNull() }.orEmpty()
                }
                // 刚登录的账号默认就是接下来要用的那个 —— 用户点「登录」的意图就是这个。
                onUseAccount(stored.uuid)
                notice = "已登录：" + stored.name + "，接下来启动游戏就用这个账号。"
                noticeIsError = false
            } catch (e: CancellationException) {
                notice = "已取消登录。"
                noticeIsError = false
                throw e
            } catch (e: Exception) {
                noticeIsError = true
                notice = "登录失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                grant = null
                remaining = 0
                loggingIn = false
                loginJob = null
            }
        }
    }

    fun forget(account: StoredAccount) {
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { repository?.remove(account.uuid) }.exceptionOrNull()
            }
            if (failure != null) {
                noticeIsError = true
                notice = "移除失败：" + (failure.message ?: failure.javaClass.simpleName)
                return@launch
            }
            if (account.key == activeUuid.trim().lowercase()) onUseOffline()
            noticeIsError = false
            notice = "已从列表里移除 " + account.name + "（只删本地令牌，账号本身没事）。"
            reload()
        }
    }

    SettingCard(
        title = "正版账号",
        hint = "登录走微软官方的设备码流程：点下面的按钮，拿手机或浏览器打开 microsoft.com/link " +
            "输一串短码，输完这边自动继续。登录成功后本地只留一枚 refresh_token，存在游戏目录下的 " +
            "platypus/accounts.json 里 —— 那个文件等价于账号密码，别发给别人。",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loggingIn) {
                val code = grant
                if (code == null) {
                    Text("正在向微软要短码…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text("在浏览器里输入这串码", style = MaterialTheme.typography.labelLarge)
                    Text(
                        code.userCode,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        code.verificationUri.ifBlank { "https://www.microsoft.com/link" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (remaining > 0) "还剩 " + remaining + " 秒，输完不用点任何东西。" else "输完不用点任何东西。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { loginJob?.cancel() }) { Text("取消登录") }
            } else {
                Button(onClick = { startLogin() }) { Text("用微软账号登录") }
            }

            if (loading) {
                Text("正在读已登录的账号…", style = MaterialTheme.typography.bodySmall)
            } else if (accounts.isEmpty()) {
                Text(
                    "还没有登录过任何账号。不登录也能玩：离线模式的用户名在「启动」那一页填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                accounts.forEach { account ->
                    AccountRow(
                        account = account,
                        active = account.key == activeUuid.trim().lowercase(),
                        busy = loggingIn,
                        onUse = { onUseAccount(account.uuid) },
                        onForget = { forget(account) },
                    )
                }
            }
        }
    }

    SettingCard(
        title = "离线模式",
        hint = "启动游戏时用「启动」页填的用户名，UUID 由名字算出来。原版服务器要开正版验证就进不去，" +
            "但单机、局域网、关掉验证的服务器都能玩。",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (activeUuid.isBlank()) "当前：离线模式" else "当前：正版账号",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            if (activeUuid.isNotBlank()) {
                OutlinedButton(onClick = { onUseOffline() }) { Text("改用离线模式") }
            }
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

@Composable
private fun AccountRow(
    account: StoredAccount,
    active: Boolean,
    busy: Boolean,
    onUse: () -> Unit,
    onForget: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                account.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (active) {
                Text(
                    "启动时用这个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!active) {
                TextButton(onClick = onUse, enabled = !busy) { Text("用这个") }
            }
            TextButton(onClick = onForget, enabled = !busy) { Text("移除") }
        }
        Text(
            account.uuid,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "上次登录：" + formatLoginTime(account.lastLoginAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 把毫秒时间戳写成「2026-11-14 18:02」；0 表示没记录过。 */
private fun formatLoginTime(millis: Long): String {
    if (millis <= 0L) return "没有记录"
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return format.format(Date(millis))
}
