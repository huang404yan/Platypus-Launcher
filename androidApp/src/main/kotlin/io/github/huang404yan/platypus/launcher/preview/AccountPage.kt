package io.github.huang404yan.platypus.launcher.preview

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.auth.AccountBootstrap
import io.github.huang404yan.platypus.core.auth.ClientIds
import io.github.huang404yan.platypus.core.auth.DeviceCodeGrant
import io.github.huang404yan.platypus.core.auth.MicrosoftAccountAuth
import io.github.huang404yan.platypus.core.auth.MicrosoftAuth
import io.github.huang404yan.platypus.core.auth.StoredAccount
import io.github.huang404yan.platypus.core.auth.YggdrasilAccountAuth
import io.github.huang404yan.platypus.core.auth.YggdrasilClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账号页（手机）。
 *
 * ## 为什么手机上也必须有这一页
 *
 * 少了它，手机上唯一能用的身份就是离线号：进正版服会被踢，进外置服直接被当成陌生玩家。
 * 而登录本身与桌面端没有任何平台差别 —— 设备码流程就是「网站上输一串短码」，手机恰好更顺手
 * （点一下就能跳到浏览器）。所以这一页不是把桌面端抄一遍，而是把那套
 * `MicrosoftAccountAuth` / `YggdrasilAccountAuth` 直接拿来用，换一套适合手指点的排版。
 *
 * ## 与桌面端的三个差别
 *
 * 1. 没有可以弹的独立窗口，短码就写在卡片里，配一个「打开浏览器」；
 * 2. 跳转走 `Intent.ACTION_VIEW`，桌面端是 `Desktop.browse`；
 * 3. 应用 ID 的输入框只在安装包确实没带 ID 时才出现 —— 带了的包（正式版）不该让用户
 *    看见一个「填 ID」的框，那会让人以为还得自己申请。
 *
 * 账号与设置都存在游戏目录下的 `platypus/` 里，与桌面端同一个文件。
 */
@Composable
internal fun AccountPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf(defaultGameRoot()) }
    var accounts by remember { mutableStateOf<List<StoredAccount>>(emptyList()) }
    var activeUuid by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("") }
    var noticeIsError by remember { mutableStateOf(false) }
    var grant by remember { mutableStateOf<DeviceCodeGrant?>(null) }
    var remaining by remember { mutableStateOf(0) }
    var loggingIn by remember { mutableStateOf(false) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var idText by remember { mutableStateOf("") }
    var idProblem by remember { mutableStateOf<String?>(null) }
    var serverText by remember { mutableStateOf("") }
    var externalUser by remember { mutableStateOf("") }
    var externalPassword by remember { mutableStateOf("") }
    var externalBusy by remember { mutableStateOf(false) }
    var externalStep by remember { mutableStateOf("") }

    val gameRoot = File(root.trim().ifBlank { defaultGameRoot() })
    val repository = remember(gameRoot.absolutePath) { AccountBootstrap.repository(gameRoot) }

    suspend fun reload() {
        val stored = AccountBootstrap.settings(gameRoot)
        accounts = AccountBootstrap.list(gameRoot)
        activeUuid = stored?.activeAccountUuid.orEmpty()
        clientId = stored?.microsoftClientId.orEmpty()
    }

    fun refresh() {
        scope.launch { withContext(Dispatchers.IO) { reload() } }
    }

    /** 切换当前账号；空串 = 改用离线。 */
    fun useAccount(uuid: String) {
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { AccountBootstrap.markActive(gameRoot, uuid) }.exceptionOrNull()
            }
            if (failure != null) {
                noticeIsError = true
                notice = "切换失败：" + describe(failure)
                return@launch
            }
            activeUuid = uuid
            noticeIsError = false
            notice = if (uuid.isBlank()) "已改用离线模式。" else "启动游戏时会用这个账号。"
        }
    }

    fun saveClientId(raw: String) {
        scope.launch {
            val cleaned = raw.trim().trim('"').trim()
            val failure = withContext(Dispatchers.IO) {
                runCatching { AccountBootstrap.saveClientId(gameRoot, cleaned) }.exceptionOrNull()
            }
            if (failure != null) {
                noticeIsError = true
                notice = "保存应用 ID 失败：" + describe(failure)
                return@launch
            }
            clientId = cleaned
            noticeIsError = false
            notice = "应用 ID 已保存。"
        }
    }

    fun runLogin(relogin: Boolean) {
        notice = ""
        noticeIsError = false
        grant = null
        remaining = 0
        loggingIn = true
        loginJob = scope.launch {
            try {
                val service = MicrosoftAccountAuth(
                    repository = repository,
                    clientIdProvider = { ClientIds.resolve(clientId) },
                )
                val stored = service.login(
                    onCode = { code -> grant = code },
                    onTick = { seconds -> remaining = seconds },
                )
                withContext(Dispatchers.IO) { reload() }
                // 刚登录的账号默认就是接下来要用的那个 —— 用户点「登录」的意图就是这个。
                useAccount(stored.uuid)
                notice = (if (relogin) "已重新登录：" else "已登录：") + stored.name +
                    "，接下来启动游戏就用这个账号。"
                noticeIsError = false
            } catch (e: CancellationException) {
                noticeIsError = false
                notice = "已取消登录。"
                throw e
            } catch (e: Exception) {
                noticeIsError = true
                notice = (if (relogin) "重新登录失败：" else "登录失败：") +
                    (e.message ?: e.javaClass.simpleName)
            } finally {
                grant = null
                remaining = 0
                loggingIn = false
                loginJob = null
            }
        }
    }

    fun startLogin() {
        // 包自带 ID 时那张「填 ID」的卡根本不显示，也就没有输入框可校验，直接登录。
        if (ClientIds.builtin().isNotEmpty()) {
            runLogin(relogin = false)
            return
        }
        val problem = MicrosoftAuth.clientIdProblem(idText)
        if (problem != null) {
            noticeIsError = true
            notice = problem
            return
        }
        // 输入框改了却没点保存时，登录读到的是盘上那个旧值。不拦这一下，用户会以为新 ID 生效了。
        if (MicrosoftAuth.normalizeClientId(idText) != clientId) {
            noticeIsError = true
            notice = "这个 ID 还没保存。先点上面的「保存 ID」—— 登录与刷新用的都是保存下来的那个。"
            return
        }
        runLogin(relogin = false)
    }

    fun runExternalLogin() {
        val problem = YggdrasilClient.apiRootProblem(serverText)
        if (problem != null) {
            noticeIsError = true
            notice = problem
            return
        }
        if (externalUser.isBlank() || externalPassword.isBlank()) {
            noticeIsError = true
            notice = "外置登录要填账号和密码（就是你在那个服务器网站上注册的那一对）。"
            return
        }
        notice = ""
        noticeIsError = false
        externalBusy = true
        externalStep = "正在登录"
        loginJob = scope.launch {
            try {
                val service = YggdrasilAccountAuth(gameRoot = gameRoot, repository = repository)
                val stored = service.login(
                    serverUrl = serverText,
                    username = externalUser.trim(),
                    password = externalPassword,
                    onProgress = { step -> externalStep = step },
                )
                withContext(Dispatchers.IO) { reload() }
                // 密码用完就清掉，不在内存里留着。
                externalPassword = ""
                useAccount(stored.uuid)
                noticeIsError = false
                notice = "已登录外置服账号：" + stored.name + "，接下来启动游戏就用这个账号。"
            } catch (e: CancellationException) {
                noticeIsError = false
                notice = "已取消外置登录。"
                throw e
            } catch (e: Exception) {
                noticeIsError = true
                notice = "外置登录失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                externalBusy = false
                externalStep = ""
                loginJob = null
            }
        }
    }

    fun forget(account: StoredAccount) {
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                // 按 key 删而不是按 uuid：外置服上的账号与微软账号可能是同一个 UUID，
                // 按 uuid 删会把另一条也顺手删掉。
                runCatching { repository.removeKey(account.key) }.exceptionOrNull()
            }
            if (failure != null) {
                noticeIsError = true
                notice = "移除失败：" + describe(failure)
                return@launch
            }
            if (AccountBootstrap.matches(account, activeUuid)) useAccount("")
            withContext(Dispatchers.IO) { reload() }
            noticeIsError = false
            notice = "已移除 " + account.name + "。"
        }
    }

    LaunchedEffect(gameRoot.absolutePath) {
        loading = true
        val target = withContext(Dispatchers.IO) {
            // 游戏目录默认取设置里那个；设置里没有才用设备上的默认位置。
            val stored = AccountBootstrap.settings(gameRoot)
            stored?.defaultGameRoot.orEmpty().takeIf { it.isNotBlank() }
        }
        if (root.trim().isBlank() && target != null) {
            root = target
        } else {
            withContext(Dispatchers.IO) { reload() }
        }
        loading = false
    }

    val packagedId = ClientIds.builtin()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "账号",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "登录之后启动游戏就用这个身份。账号存在游戏目录下的 platypus/accounts.json，与桌面端同一个文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AccountCard(
            title = "游戏目录（.minecraft）",
            hint = "账号与设置都放在这个目录下的 platypus/ 里。换了目录等于换了一整套登录状态。",
        ) {
            OutlinedTextField(
                value = root,
                onValueChange = { root = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("路径") },
            )
            TextButton(onClick = { refresh() }) { Text("重新读取") }
        }

        if (packagedId.isNotEmpty()) {
            AccountCard(
                title = "应用 ID",
                hint = "这个安装包已经自带一个应用 ID，正版登录不需要你填任何东西。",
            ) {
                Text(
                    "用的是这个包自己的应用注册（前 8 位 " + packagedId.take(8) +
                        "…）。换成自己申请的那个只会让已经存下来的登录凭据对不上 —— 微软把 refresh_token 绑在应用上，换了 ID 一律要重登。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            AccountCard(
                title = "第一步：填一个自己的应用 ID",
                hint = "微软不发放通用的应用 ID。随包发一个等于所有使用者共用别人的注册，被限流或收紧时拿到包的人没法处理，所以这一版不内置 —— 只填这一次，之后一直用。",
            ) {
                Text(
                    "申请步骤（Azure 门户，免费）：「Microsoft Entra ID」→「应用注册」→「新注册」，重定向 URI 留空；再到「身份验证」里打开「允许公共客户端流」；最后在「概述」里复制 36 位「应用程序（客户端）ID」。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    val opened = runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PORTAL_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                    noticeIsError = !opened
                    notice = if (opened) {
                        "已用浏览器打开。"
                    } else {
                        "这台设备上没有能打开的浏览器，手动访问：" + PORTAL_URL
                    }
                }) { Text("打开 Azure 门户") }
                OutlinedTextField(
                    value = idText,
                    onValueChange = {
                        idText = it
                        idProblem = MicrosoftAuth.clientIdProblem(it)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("应用程序（客户端）ID") },
                    isError = idProblem != null,
                )
                if (idProblem != null) {
                    Text(
                        idProblem.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            saveClientId(idText)
                            idProblem = null
                        },
                        enabled = idProblem == null && MicrosoftAuth.normalizeClientId(idText) != clientId,
                    ) { Text("保存 ID") }
                    OutlinedButton(
                        onClick = {
                            idText = clientId
                            idProblem = null
                        },
                    ) { Text("填回已保存的") }
                }
                if (clientId.isNotBlank()) {
                    Text(
                        "盘上现在是 " + clientId.take(8) + "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        AccountCard(
            title = "第二步：登录微软账号",
            hint = "走微软官方的设备码流程：点下面的按钮，屏幕上会显示一串短码，去浏览器输进去、再确认是你本人就行。登录后本地只留一枚 refresh_token —— 那个文件等价于账号密码，别发给别人。",
        ) {
            if (loggingIn) {
                val code = grant
                if (code == null) {
                    Text("正在向微软要短码…", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Text("在浏览器里输入这串码：", style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer {
                        Text(
                            code.userCode,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(code.verificationUri, style = MaterialTheme.typography.bodyMedium)
                    if (remaining > 0) {
                        Text(
                            "还剩 " + remaining + " 秒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val opened = runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.isSuccess
                            if (!opened) {
                                noticeIsError = false
                                notice = "没有能打开浏览器的应用，手动访问：" + code.verificationUri
                            }
                        }) { Text("打开浏览器") }
                        TextButton(onClick = {
                            loginJob?.cancel()
                            loginJob = null
                        }) { Text("取消登录") }
                    }
                }
            } else {
                Button(onClick = { startLogin() }) { Text("用微软账号登录") }
            }
        }

        AccountCard(
            title = "账号列表",
            hint = "「启动时用这个」是接下来启动游戏的身份。移除只删本地这一条，服务端上的记录不受影响。",
        ) {
            if (loading) {
                Text("正在读账号…", style = MaterialTheme.typography.bodySmall)
            } else if (accounts.isEmpty()) {
                Text(
                    "还没有登录过任何账号。不登录也能玩：离线模式的用户名在「设置」那一页填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                accounts.forEach { account ->
                    AccountRow(
                        account = account,
                        active = AccountBootstrap.matches(account, activeUuid),
                        busy = loggingIn || externalBusy,
                        onUse = { useAccount(account.uuid) },
                        onRelogin = { runLogin(relogin = true) },
                        onForget = { forget(account) },
                    )
                }
                if (activeUuid.isNotBlank()) {
                    TextButton(
                        onClick = { useAccount("") },
                        enabled = !loggingIn && !externalBusy,
                    ) { Text("改用离线模式") }
                }
            }
        }

        AccountCard(
            title = "外置登录（自建服 / 第三方认证）",
            hint = "填认证服务器地址与你在那个站注册的账号密码。第一次用会下一份 authlib-injector（约 350 KB），它不随包分发。",
        ) {
            OutlinedTextField(
                value = serverText,
                onValueChange = { serverText = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("认证服务器") },
                placeholder = { Text("https://littleskin.cn/api/yggdrasil") },
            )
            OutlinedTextField(
                value = externalUser,
                onValueChange = { externalUser = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("账号（邮箱或用户名）") },
            )
            OutlinedTextField(
                value = externalPassword,
                onValueChange = { externalPassword = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(onClick = { runExternalLogin() }, enabled = !externalBusy) {
                Text(if (externalBusy) "正在登录…" else "登录这个服务器")
            }
            if (externalStep.isNotBlank()) {
                Text(
                    externalStep,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (notice.isNotBlank()) {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("账号文件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                SelectionContainer {
                    Text(
                        repository.path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountCard(title: String, hint: String, content: @Composable () -> Unit) {
    Card {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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

@Composable
private fun AccountRow(
    account: StoredAccount,
    active: Boolean,
    busy: Boolean,
    onUse: () -> Unit,
    onRelogin: () -> Unit,
    onForget: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
        Text(
            if (account.isYggdrasil) {
                "外置登录 · " + account.serverUrl.ifBlank { "（地址缺失）" }
            } else {
                "微软账号 · " + account.uuid
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "上次登录：" + formatLoginTime(account.lastLoginAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!active) {
                TextButton(onClick = onUse, enabled = !busy) { Text("用这个") }
            }
            // 外置账号不给「重新登录」：它的重新登录是重填那个服的账号密码，
            // 走微软设备码只会得到一个跟这行无关的微软号。
            if (!account.isYggdrasil) {
                TextButton(onClick = onRelogin, enabled = !busy) { Text("重新登录") }
            }
            TextButton(onClick = onForget, enabled = !busy) { Text("移除") }
        }
    }
}

/** 微软应用注册的入口地址。 */
private const val PORTAL_URL =
    "https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"

/** 把毫秒时间戳写成「2026-11-14 18:02」；0 表示没记录过。 */
private fun formatLoginTime(millis: Long): String {
    if (millis <= 0L) return "没有记录"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}