package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import io.github.huang404yan.platypus.core.install.ServerEntry
import io.github.huang404yan.platypus.core.lan.LanDiscovery
import io.github.huang404yan.platypus.core.lan.LocalAddresses
import io.github.huang404yan.platypus.core.lan.LocalBinding
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 联机页的两栏。 */
internal const val LINK_TAB_LOCAL = "local"
internal const val LINK_TAB_SERVER = "server"

/**
 * 联机页，两栏：**本地联机** 与 **服务器**。
 *
 * 为什么分成两栏：这两件事的读者完全不同。前者是「今天想和朋友一起玩，但不在一个网里」，
 * 后者是「我自己开的那个服现在怎么样」。PCL 把两者放在同一页的上下两段，
 * 滚到下面那一半要滑很久，所以这里直接分段，一屏只看一件事。
 *
 * 关于**同一个局域网**：这一栏不再是重点。游戏自带「对局域网开放」，
 * 同一条 Wi-Fi 里把地址念一遍就能连上，启动器插手只会多一步 ——
 * 所以内网 IPv4 地址故意不列。留着的只有公网 IPv6，那是跨网直连唯一不折腾的路子。
 *
 * @param initialGameRoot 游戏根目录；陶瓦装在它下面的 platypus 目录里。
 * @param backend 读已装服务端登记用。为 null 时「服务器」那一栏只显示一句提示。
 * @param onPlay 把地址交给启动页直连。
 */
@Composable
fun LinkPage(
    initialGameRoot: String? = null,
    backend: LauncherBackend? = null,
    onPlay: (String) -> Unit = {},
) {
    var tab by remember { mutableStateOf(LINK_TAB_LOCAL) }
    var notice by remember { mutableStateOf(" ") }

    // 本机地址：点「重新读取」时换一个 key 触发重算，平时不用每秒扫网卡。
    var addressNonce by remember { mutableStateOf(0) }
    val addresses = remember(addressNonce) { LocalAddresses.list() }
    var portText by remember { mutableStateOf("25565") }
    var portError by remember { mutableStateOf(" ") }

    // 本地程序联机：记住那个 exe，点一下就拉起来。
    var programPath by remember { mutableStateOf(" ") }
    var programNotice by remember { mutableStateOf(" ") }

    fun copy(text: String, label: String) {
        val ok = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        }.isSuccess
        notice = if (ok) "已复制「" + label + "」：" + text else "复制失败，请手动选中这段：" + text
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("联机", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "同一个局域网里不用来这一页 —— 游戏里「对局域网开放」就够了。这里管的是「不在一个网里也能一起玩」，还有自己开的服务器。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkTabChip("本地联机", tab == LINK_TAB_LOCAL) { tab = LINK_TAB_LOCAL }
            LinkTabChip("服务器", tab == LINK_TAB_SERVER) { tab = LINK_TAB_SERVER }
        }

        NoticeFade(notice) { shown ->
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        if (tab == LINK_TAB_LOCAL) {
            // 陶瓦置顶：跨网络这件事只有它不需要用户自己折腾路由器。
            // 排在第一位是因为点进这一页的人，九成就是为它来的。
            TerracottaSection(gameRoot = initialGameRoot) { text -> notice = text }

            LinkCard(title = "跨网直连（公网 IP + 端口映射）", badge = "不用装东西") {
                Text(
                    "不用装陶瓦的两条路：对方有公网 IPv6 时直接给他「[IPv6]:端口」；" +
                        "只有公网 IPv4 时，得先在自家路由器上把这个端口映射到本机，再把「公网IP:端口」给他。" +
                        "两条都要游戏先「对局域网开放」，端口填聊天栏里那串数字。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { input ->
                            portText = input.filter { it.isDigit() }.take(5)
                            portError = " "
                        },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.width(150.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { addressNonce++ }) { Text("重新读取网卡") }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "游戏里「对局域网开放」后聊天栏写的那串数字。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (portError.isNotBlank()) LinkHint(portError, error = true)

                val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                val publicV6 = addresses.filter { it.kind == LocalBinding.Kind.IPV6 }
                // 公网 IPv4 与运营商级 NAT 都归在 OTHER：前者做了端口映射能用，后者基本没救，
                // 得分开说，否则用户会拿一个 100.64.x.x 去映射半天。
                val publicV4 = addresses.filter {
                    it.kind == LocalBinding.Kind.OTHER && !LocalAddresses.isCarrierNat(it.host)
                }
                val carrierNat = addresses.filter {
                    it.kind == LocalBinding.Kind.OTHER && LocalAddresses.isCarrierNat(it.host)
                }
                // 做端口映射时要填「内网地址」，直接把它摆出来，省得用户自己去 ipconfig 里翻。
                val lanAddresses = addresses.filter { it.kind == LocalBinding.Kind.LAN }
                when {
                    port == null -> LinkHint("端口要填 1–65535 的数字。", error = true)
                    publicV6.isEmpty() && publicV4.isEmpty() -> Text(
                        "这台机器读不到公网地址：没有公网 IPv6，网卡上也没有公网 IPv4" +
                            "（家用宽带通常就是这样，本机只有一个 192.168.x.x）。" +
                            "先把局域网这条路走通，跨网络就用上面的陶瓦。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        if (publicV6.isNotEmpty()) {
                            LinkHint("公网 IPv6｜对方也有 IPv6 时，把下面这条发过去就能进，不用动路由器。")
                            for (binding in publicV6) {
                                LocalAddressRow(
                                    binding = binding,
                                    port = port,
                                    onCopy = { copy(binding.withPort(port), "公网 IPv6 地址") },
                                )
                            }
                        }
                        if (publicV4.isNotEmpty()) {
                            LinkHint(
                                "公网 IPv4｜先在路由器上把这个端口映射到本机" +
                                    "（路由器后台找「端口转发 / 虚拟服务器」，外部端口与本机端口都填 " + port +
                                    "，内网地址填 " +
                                    (lanAddresses.firstOrNull()?.host ?: "本机在局域网里的那个地址") +
                                    "），映射完再把下面这条发过去。",
                            )
                            for (binding in publicV4) {
                                LocalAddressRow(
                                    binding = binding,
                                    port = port,
                                    onCopy = { copy(binding.withPort(port), "公网 IPv4 地址") },
                                )
                            }
                        }
                        if (carrierNat.isNotEmpty()) {
                            LinkHint(
                                "检测到运营商级 NAT 地址（100.64.x.x）：路由器上映射了也对不上外网，" +
                                    "要么找运营商要公网 IP，要么用上面的陶瓦。",
                                error = true,
                            )
                        }
                    }
                }
            }

            LinkCard(title = "本地程序联机", badge = "拉起一个程序") {
                Text(
                    "这张卡的用途是「手上还有一个别的客户端，想和这边一起开」。这里只做最直接的部分：记住那个 exe，点一下就起来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = programPath,
                        onValueChange = { programPath = it; programNotice = " " },
                        modifier = Modifier.weight(1f),
                        label = { Text("程序路径（.exe）") },
                        singleLine = true,
                    )
                    OutlinedButton(onClick = {
                        chooseOpenFile("选要联机启动的程序", "exe", null)?.let { programPath = it.absolutePath }
                    }) { Text("选择…") }
                    OutlinedButton(onClick = { programPath = " "; programNotice = " " }) { Text("重置") }
                }
                Button(
                    onClick = {
                        val file = File(programPath.trim())
                        programNotice = if (!file.isFile) {
                            "这个路径下没有文件，先用「选择…」挑一个。"
                        } else {
                            runCatching {
                                ProcessBuilder(file.absolutePath).directory(file.parentFile).start()
                                "已启动：" + file.name
                            }.getOrElse { "起不来：" + (it.message ?: it.javaClass.simpleName) }
                        }
                    },
                    enabled = programPath.isNotBlank(),
                ) { Text("启动这个程序") }
                if (programNotice.isNotBlank()) LinkHint(programNotice)
            }

            LinkCard(title = "手动填地址") {
                Text(
                    "知道对方地址（比如他在群里发的 xxx.xxx:25565）就直接填，点「直接进」会用官方的直接连接参数把游戏拉起来，跳过主菜单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ManualJoinRow(onCopy = { copy(it, "直连地址") }, onPlay = onPlay)
            }

            LinkCard(title = "「直接进」是怎么做到的") {
                Text(
                    "用的是游戏自带的 --quickPlayMultiplayer（1.20 起才有）或者老式的 --server / --port，都是官方参数。" +
                        "不自己实现协议栈：绕过游戏连进去会丢皮肤、丢会话，和大部分模组不兼容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ServerColumn(
                backend = backend,
                onNotice = { notice = it },
                onJoin = onPlay,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 「服务器」那一栏：我开的服务器 + 进别人的服务器。
 *
 * 为什么开服 / 停服放在这一页而不是「下载 → 服务端」：那页是**装**，这页是**用**。
 * 装完就不该再回去翻一遍选版本、选加载器的表。
 */
@Composable
private fun ServerColumn(
    backend: LauncherBackend?,
    onNotice: (String) -> Unit,
    onJoin: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val console = ServerRuntime.state.collectAsState().value
    var entries by remember { mutableStateOf<List<ServerEntry>>(emptyList()) }
    var error by remember { mutableStateOf(" ") }
    var configDir by remember { mutableStateOf<String?>(null) }
    var commandText by remember { mutableStateOf(" ") }

    suspend fun reload() {
        val repo = backend?.servers
        if (repo == null) {
            error = "后端没装配，读不到服务端登记。"
            return
        }
        error = " "
        entries = runCatching { withContext(Dispatchers.IO) { repo.list() } }
            .getOrElse {
                error = "读服务端登记失败：" + (it.message ?: it.javaClass.simpleName)
                emptyList()
            }
    }

    LaunchedEffect(Unit) { reload() }

    val openConfig = configDir
    if (openConfig != null) {
        ServerConfigPanel(
            dir = File(openConfig),
            running = console.running && sameDir(console.dir, openConfig),
            onBack = { configDir = null },
            // 第二道闸：命令只进当前这个目录对应的那个控制台。
            onSendCommand = { command ->
                if (console.running && sameDir(console.dir, openConfig)) {
                    ServerRuntime.send(command)
                } else {
                    CommandSend.NotRunning
                }
            },
        )
        return
    }

    LinkCard(title = "我开的服务器", badge = entries.size.toString() + " 个") {
        Text(
            "这里只管开与关。「下载 → 服务端」那一栏负责装。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error.isNotBlank()) LinkHint(error, error = true)
        if (entries.isEmpty()) {
            LinkHint("还没有登记过的服务端。去「下载 → 服务端」装一个。")
        } else {
            entries.forEach { entry ->
                ServerRow(
                    entry = entry,
                    running = console.running && sameDir(console.dir, entry.directory),
                    busy = console.starting,
                    onStart = { ServerRuntime.start(entry.directory) },
                    onStop = { ServerRuntime.stop() },
                    onConfig = { configDir = entry.directory },
                    onOpenDir = { openInExplorer(File(entry.directory)) },
                    onForget = {
                        val repo = backend?.servers
                        if (repo == null) {
                            onNotice("后端没装配，删不了登记。")
                        } else {
                            scope.launch {
                                val failure = runCatching {
                                    withContext(Dispatchers.IO) { repo.remove(entry.id) }
                                }.exceptionOrNull()
                                if (failure != null) {
                                    onNotice("从登记里去掉失败：" + (failure.message ?: failure.javaClass.simpleName))
                                } else {
                                    onNotice("已从登记里去掉「" + entry.title + "」，磁盘上的文件一个没动。")
                                    reload()
                                }
                            }
                        }
                    },
                )
            }
        }
        if (console.running) {
            Text(
                "正在跑：" + console.dir,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (entries.isNotEmpty()) {
            OutlinedButton(onClick = { scope.launch { reload() } }) { Text("刷新列表") }
        }
    }

    if (console.running || console.lines.isNotEmpty()) {
        LinkCard(title = "控制台", badge = if (console.running) "运行中" else "已停止") {
            Surface(
                Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    val tail = console.lines.takeLast(12)
                    if (tail.isEmpty()) {
                        Text(
                            "刚起来，还没有输出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        for (line in tail) {
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("命令（例如 list、op 名字）") },
                    singleLine = true,
                    enabled = console.running,
                )
                Button(
                    onClick = {
                        val result = ServerRuntime.send(commandText)
                        onNotice(commandNotice(commandText, result))
                        commandText = " "
                    },
                    enabled = console.running && commandText.isNotBlank(),
                ) { Text("发送") }
            }
        }
    }

    LinkCard(title = "进别人的服务器") {
        Text(
            "填对方给你的地址。点「直接进」会用官方参数把游戏拉起来。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ManualJoinRow(onCopy = { onNotice("已复制「直连地址」：" + it) }, onPlay = onJoin)
    }

    LinkCard(title = "用 start.bat 自己开的服不会出现在上面") {
        Text(
            "这里只认通过本启动器起的进程：命令能直进它的标准输入，输出能读到。" +
                "自己用脚本开的服要接管，得靠 RCON 连进去 —— 那件事还没做。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerRow(
    entry: ServerEntry,
    running: Boolean,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConfig: () -> Unit,
    onOpenDir: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        entry.directory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (running) "运行中" else "已停止",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (running) {
                    Button(onClick = onStop) { Text("关服") }
                } else {
                    Button(onClick = onStart, enabled = !busy) { Text("开服") }
                }
                OutlinedButton(onClick = onConfig, enabled = !busy) { Text("配置") }
                OutlinedButton(onClick = onOpenDir) { Text("目录") }
                TextButton(onClick = onForget, enabled = !running) { Text("从列表去掉") }
            }
        }
    }
}

/** 「手填地址 → 直接进」两栏都要用，抽出来免得写两遍。 */
@Composable
private fun ManualJoinRow(onCopy: (String) -> Unit, onPlay: (String) -> Unit) {
    var text by remember { mutableStateOf(" ") }
    var problem by remember { mutableStateOf(" ") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; problem = " " },
            label = { Text("地址（如 mc.example.com:25565）") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            isError = problem.isNotBlank(),
        )
        Spacer(Modifier.width(10.dp))
        OutlinedButton(onClick = {
            val parsed = LanDiscovery.parseAddress(text)
            if (parsed == null) problem = "看不懂这个地址，要么填 IP，要么填 IP:端口。" else onCopy(parsed.text)
        }) { Text("解析并复制") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            val parsed = LanDiscovery.parseAddress(text)
            if (parsed == null) {
                problem = "看不懂这个地址，要么填 IP，要么填 IP:端口。"
            } else {
                problem = " "
                onPlay(parsed.text)
            }
        }) { Text("直接进") }
    }
    if (problem.isNotBlank()) LinkHint(problem, error = true)
}

@Composable
private fun LinkCard(title: String, badge: String = " ", content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (badge.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            badge,
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun LinkTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun LocalAddressRow(binding: LocalBinding, port: Int, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                binding.withPort(port),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                // 副行说清这条地址是干什么用的。以前这里只会写「公网 IPv6」，补了公网 IPv4
                // 之后就会把一条 IPv4 说成 IPv6，所以改成按类型分开说。
                when (binding.kind) {
                    LocalBinding.Kind.LAN -> "局域网地址" +
                        (if (binding.interfaceName.isNotBlank()) "（网卡 " + binding.interfaceName + "）" else "")
                    LocalBinding.Kind.IPV6 -> "公网 IPv6，对方也有 IPv6 时可以直接连"
                    else -> "公网 IPv4，做完端口映射后对方才能连进来"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onCopy) { Text("复制地址") }
    }
}

@Composable
private fun LinkHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

/** 两个路径是不是同一处。用 canonicalFile 是为了「D:\\a\\b」和「D:\\a\\.\\b」认成同一处。 */
private fun sameDir(a: String, b: String): Boolean = runCatching {
    File(a).canonicalFile == File(b).canonicalFile
}.getOrDefault(a.equals(b, ignoreCase = true))
