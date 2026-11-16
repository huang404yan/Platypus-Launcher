package io.github.huang404yan.platypus.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.huang404yan.platypus.core.auth.StoredAccount
import io.github.huang404yan.platypus.core.edition.FirstRun
import io.github.huang404yan.platypus.core.edition.FirstRunState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次启动时挡在最前面的那个弹窗。
 *
 * 两件事它都要做：
 * - 孵化版（开源版）：告诉用户「想用正版账号得先自己申请一个应用 ID」，然后放行。
 *   这一版不强制登录 —— 源码是公开的，强制登录对着源码改一行就绕过去了，只会挡住
 *   想先看看界面的人。
 * - 正式版：没有正版账号时**不让关**，只能去「设置 → 账号」登录。
 *
 * 「该拦还是该放」的判定全在 core 的 [FirstRunState] 里，这里只负责画与落盘，
 * 不写第二份 if —— 两处各写一份判断，迟早会出现「界面放行了但内部当没走完」。
 */
@Composable
internal fun FirstRunGate(
    backend: LauncherBackend?,
    /** 点「去登录」时切到「设置 → 账号」。 */
    onGoAccount: () -> Unit,
) {
    val repository = backend?.settings
    val accounts = backend?.accounts
    val scope = rememberCoroutineScope()

    // null = 还没读出来（第一帧）。读出来之前不画任何东西：画一个「欢迎」再画一个「先登录」，
    // 用户会看到字在跳。
    var state by remember { mutableStateOf<FirstRunState?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(repository, accounts) {
        if (repository == null) return@LaunchedEffect
        val settings = runCatching { repository.load() }.getOrNull() ?: return@LaunchedEffect
        // 账号只在真的要判的时候才读：孵化版根本用不上，多一次磁盘读只是拖慢启动。
        val edition = io.github.huang404yan.platypus.core.edition.Edition.current()
        var signedIn = false
        var ownsGame = false
        var loaded = true
        if (edition == io.github.huang404yan.platypus.core.edition.Edition.FORMAL) {
            val list = withContext(Dispatchers.IO) {
                runCatching { accounts?.list() }.getOrNull()
            }
            // null 表示读失败（仓库没装配或文件坏了）：当成「还不知道」而不是「没有账号」，
            // 免得把一句读盘错误变成一堵登录墙。
            loaded = list != null
            val active = list.orEmpty().firstOrNull {
                it.kind == StoredAccount.KIND_MICROSOFT && settings.activeAccountUuid.isNotBlank() &&
                    it.uuid.equals(settings.activeAccountUuid.trim(), ignoreCase = true)
            }
            signedIn = active != null
            // 所有权校验的结果记在账号自己身上（登录时 GET /entitlements/mcstore 过了才有那个时间戳），
            // 这里只读它，不再发一次网络请求。
            ownsGame = active?.ownsGame == true
        }
        state = FirstRun.state(
            settings,
            edition,
            signedIn = signedIn,
            accountsLoaded = loaded,
            accountOwnsGame = ownsGame,
        )
    }

    val current = state ?: return
    if (!current.show) return

    fun finish() {
        if (busy) return
        busy = true
        scope.launch {
            // 落盘失败也把弹窗收掉：一个写不进去的设置文件不该让程序停在欢迎页。
            // 代价是下次启动还会弹一次，比「打不开」好。
            runCatching {
                val repository = repository ?: return@runCatching
                val latest = repository.load()
                if (latest.firstRunAt <= FirstRun.NOT_DONE) {
                    repository.save(latest.copy(firstRunAt = FirstRun.doneAt(System.currentTimeMillis())))
                }
            }
            state = current.copy(needed = false)
        }
    }

    AlertDialog(
        // 正式版没有「关掉」这条路：点外面、按 Esc 都不能把它关了，否则那句「必须登录」
        // 就成了「看一眼就能跳过」。孵化版可以随手关。
        onDismissRequest = { if (current.dismissible) finish() },
        title = { Text(current.title) },
        text = { Text(current.body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = {
                // 已经合格的（孵化版，或正式版选着一个验过的号）直接收掉弹窗，
                // 不再把人往登录页赶 —— 从老版本升上来的正是这种情形。
                if (current.canProceed) {
                    finish()
                } else {
                    // 去登录这条路不写 firstRunAt：登录成功后弹窗自己会被放行，
                    // 没登录成功则下次启动继续拦。
                    onGoAccount()
                }
            }) {
                Text(current.primaryLabel)
            }
        },
        dismissButton = current.secondaryLabel?.let { label ->
            { TextButton(onClick = { finish() }) { Text(label) } }
        },
    )
}
