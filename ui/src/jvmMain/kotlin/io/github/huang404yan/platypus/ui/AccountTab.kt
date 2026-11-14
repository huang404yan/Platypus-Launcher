package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.auth.DeviceCodeGrant
import io.github.huang404yan.platypus.core.auth.MicrosoftAccountAuth
import io.github.huang404yan.platypus.core.auth.MicrosoftAuth
import io.github.huang404yan.platypus.core.auth.StoredAccount
import java.awt.Desktop
import java.net.URI
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
 *
 * 登录之前必须先有一个自己注册的 Azure 应用 ID（[clientId]）—— 安装包里刻意不内置，
 * 理由写在 [MicrosoftEndpoints.DEFAULT_CLIENT_ID] 的注释里。这个页面负责让用户
 * 「照着四步申请、粘进来、当场能用」，所以那段指引不能只写在文档里。
 */
@Composable
internal fun AccountTab(
    backend: LauncherBackend?,
    activeUuid: String,
    clientId: String,
    onUseAccount: (String) -> Unit,
    onUseOffline: () -> Unit,
    onSaveClientId: (String) -> Unit,
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
    // 输入的 ID 与已保存的分开：没点「保存」不该影响下一次登录用哪个。
    var idText by remember(clientId) { mutableStateOf(clientId) }
    var idProblem by remember(clientId) { mutableStateOf(MicrosoftAuth.clientIdProblem(clientId)) }
    // 保存成功的提示必须留在按钮旁边：页面底部的 notice 在两张卡片之后，
    // 用户点完「保存 ID」视线还停在输入框上，看不到那儿（2026-11-14 实点截图发现）。
    // 这里**不能**写成 remember(clientId)：保存成功正是 clientId 变化的那一刻，
    // key 一变 remember 就把它重置成 false，提示永远显示不出来（实点验证时踩到）。
    var idSaved by remember { mutableStateOf(false) }
    var openingPortal by remember { mutableStateOf("") }
    // Client ID 在界面上默认遮成圆点。它不是密钥（公共客户端没有 client_secret），但它是
    // 「哪个应用在替我调微软接口」的指纹，截图、录屏、共享屏幕时没必要露出来。
    // 做法照 PCL 自用版：默认遮住、按住才看明文、这个框不允许复制（粘贴照常）。
    var revealId by remember { mutableStateOf(false) }
    var copyBlocked by remember { mutableStateOf(false) }
    // 「这个框里的东西不许外带」从写剪贴板那一层掐掉：委托给系统 ClipboardManager，
    // 只覆盖 setText —— 粘贴照旧可用（走 getText）。用委托而不是实现整个接口，
    // 是因为这个接口在不同 Compose 版本上成员会增减（getClip / setClip 是后来的）。
    val systemClipboard = LocalClipboardManager.current
    val guardedClipboard = remember(systemClipboard) {
        object : ClipboardManager by systemClipboard {
            override fun setText(annotatedString: AnnotatedString) {
                copyBlocked = true
            }
        }
    }
    // 按住显示用 pointerInput 的 onPress，而不是「点一下切换」的开关：切换开关总有人忘了关，
    // 遮罩就长期失效。切走窗口时顺手把按住状态收掉，免得切回来还露着明文。
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (!windowFocused) {
            revealId = false
            copyBlocked = false
        }
    }

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
        // 本地先挡一道：空 ID 发出去只会换回一个 AADSTS700016，用户看不懂那串东西。
        val problem = MicrosoftAuth.clientIdProblem(idText)
        if (problem != null) {
            noticeIsError = true
            notice = problem
            return
        }
        // 输入框里改了却没点「保存 ID」时，登录读到的是设置里那个旧值。
        // 不拦这一下，用户会以为新 ID 已经生效，然后在微软那边看到用的是旧应用。
        if (MicrosoftAuth.normalizeClientId(idText) != clientId) {
            noticeIsError = true
            notice = "这个 ID 还没保存。先点上面的「保存 ID」—— 登录与刷新用的都是保存下来的那个。"
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
        title = "第一步：填一个自己的应用 ID",
        hint = "微软不发放通用的客户端 ID，任何「随安装包一起发」的 ID 都等于所有使用者共用一份" +
            "别人的应用注册，出了限流或被收紧，拿到安装包的人无从处理；仓库公开后谁都能抄走。" +
            "所以这一版不内置，需要你花三分钟自己申请一个 —— 只填这一次，之后一直用。",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "申请步骤（Azure 门户，免费，不需要订阅）：",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // 步骤写成一段一段的，而不是一个长段落：用户是在另一个窗口里照着做。
            Text(
                "1. 打开 portal.azure.com，用你的微软账号登录（哪个账号都行，跟要玩的游戏账号可以不同）。\n" +
                    "2. 进「Microsoft Entra ID → 应用注册 → 新注册」，名字随便填，账户类型选" +
                    "「仅此组织目录中的账户」以外的任意一项都可以，重定向 URI 留空。\n" +
                    "3. 建好后在「身份验证」里把「允许公共客户端流」打开（选「是」），保存。\n" +
                    "4. 回到「概述」，把「应用程序(客户端) ID」那一串 36 位复制下来，粘到下面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { openingPortal = openUrl(PORTAL_URL) }) {
                    Text("打开 Azure 门户")
                }
            }
            if (openingPortal.isNotBlank()) {
                Text(
                    openingPortal,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 复制出口在 ClipboardManager 这一层关掉，而不是只拦 Ctrl+C：
            // 右键菜单的「复制」、菜单栏 Edit → Copy 都不走 key event；而且实点验证时
            // 发现 key event 已经被我们消费了（提示条弹出来了）剪贴板照样被写进明文 ——
            // 说明 Compose Desktop 的复制不全走键盘分发。粘贴不受影响，读取原样转发。
            CompositionLocalProvider(LocalClipboardManager provides guardedClipboard) {
                OutlinedTextField(
                    value = idText,
                    onValueChange = {
                        idText = it
                        idProblem = MicrosoftAuth.clientIdProblem(it)
                        idSaved = false
                        copyBlocked = false
                    },
                    label = { Text("应用程序(客户端) ID") },
                    placeholder = { Text("00000000-0000-0000-0000-000000000000") },
                    singleLine = true,
                    // 没按住「按住显示」时整串换成圆点，数量与真实长度一致（PasswordVisualTransformation
                    // 自己按长度铺圆点，不用手写遮罩层）。
                    visualTransformation = if (revealId) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation('●')
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // 键盘这一层仍然拦：提示能立刻出现，比等裁剪板那层更跟手。
                        .onPreviewKeyEvent { event ->
                            val copying = event.type == KeyEventType.KeyDown &&
                                event.isCtrlPressed &&
                                event.key == Key.C
                            if (copying) {
                                copyBlocked = true
                                true
                            } else {
                                false
                            }
                        },
                    supportingText = {
                        Text(
                            idProblem ?: "格式没问题。点「保存 ID」生效，之后登录与刷新都用它。" +
                                "默认遮成圆点，按住下面的「按住显示」看明文。",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (idProblem != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }
            if (copyBlocked) {
                Text(
                    "这个框不允许复制，只能粘贴。要看明文请按住「按住显示」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 按下去看明文，松手立刻恢复圆点 —— 与 PCL 自用版同一个手势。
                // 注意这里必须在 Initial 阶段监听：Main 阶段的事件会先被 Button 自己的
                // clickable 消费掉，detectTapGestures 里 awaitFirstDown 默认要未消费的按下，
                // 于是 onPress 永远不触发（实点验证时踩到，按钮按了没反应）。
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                revealId = true
                                try {
                                    var pressed = true
                                    while (pressed) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        pressed = event.changes.any { it.pressed }
                                    }
                                } finally {
                                    revealId = false
                                }
                            }
                        }
                    },
                ) { Text("按住显示") }
                Button(
                    onClick = {
                        onSaveClientId(MicrosoftAuth.normalizeClientId(idText))
                        idSaved = true
                        noticeIsError = false
                        notice = ""
                    },
                    enabled = idProblem == null && MicrosoftAuth.normalizeClientId(idText) != clientId,
                ) { Text("保存 ID") }
                if (idText != clientId) {
                    OutlinedButton(onClick = { idText = clientId }) { Text("撤销改动") }
                }
                if (idSaved) {
                    Text(
                        "已保存，现在可以登录了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }

    SettingCard(
        title = "第二步：登录",
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

/** 微软应用注册的入口地址，界面上的「打开 Azure 门户」按这个走。 */
private const val PORTAL_URL = "https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"

/**
 * 用系统默认浏览器打开一个网址，返回一句可以直接显示的提示。
 *
 * 桌面端可能没有关联浏览器（极简系统、Server 版），也可能被安全软件拦；
 * 失败时把网址原样贴出来让用户自己复制，比一句「打开失败」有用。
 */
private fun openUrl(url: String): String = runCatching {
    if (!Desktop.isDesktopSupported()) return@runCatching "这台机器没法自动打开浏览器，手动复制：" + url
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) {
        return@runCatching "这台机器没法自动打开浏览器，手动复制：" + url
    }
    desktop.browse(URI(url))
    "已用默认浏览器打开。"
}.getOrElse { "打不开浏览器（" + (it.message ?: it.javaClass.simpleName) + "），手动复制：" + url }

/** 把毫秒时间戳写成「2026-11-14 18:02」；0 表示没记录过。 */
private fun formatLoginTime(millis: Long): String {
    if (millis <= 0L) return "没有记录"
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return format.format(Date(millis))
}
