package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.lan.Terracotta
import io.github.huang404yan.platypus.core.lan.TerracottaClient
import io.github.huang404yan.platypus.core.lan.TerracottaInstaller
import io.github.huang404yan.platypus.core.lan.TerracottaReleases
import io.github.huang404yan.platypus.core.lan.TerracottaState
import io.github.huang404yan.platypus.core.lan.TerracottaStages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 联机页里的「陶瓦联机」卡：一键建房 / 加入，拿到房间码发给朋友。
 *
 * 为什么把陶瓦单独做一张卡而不是塞进上面的局域网流程：两者解决的完全是两个问题。
 * 上面那套是「两台机器在同一个 Wi-Fi 里，把地址填对就行」；陶瓦是「跨网络，
 * 让中间那台公网服务器替你们打洞」。混在一起用户会以为「本地扫不到就点这个」，
 * 而实际上是「不在同一个网里才需要它」。
 *
 * 三条与 PCL 自编版对齐的界面规矩：
 *  1. **不打包二进制**。Terracotta 是 AGPL-3.0，鸭嘴兽是 Apache-2.0 且公开发布产物，
 *     把它的二进制塞进我们的分发包会触发传染条款（见 docs/21 的决策 A）。
 *     所以这里只有「安装 / 更新」一个动作，由用户触发、下载到用户自己的盘上。
 *  2. **房主态没有直连地址**。陶瓦给房主的那个 url 是游客本机的回环地址，房主拿到也发不出去，
 *     所以房主态干脆不显示「复制直连地址」—— 留着一个必定失败的按钮比没有更糟。
 *  3. **对端消失要说清是谁走了**。房主关游戏后，游客侧的游戏只会说「连接中断」，
 *     界面必须补一句「房主已断开」，否则用户会反复去连一个已经死掉的房间。
 *
 * 游戏进程句柄不在这里：启动页与联机页是两个页面，拿不到对方的状态。
 * 所以 PCL 那条「游戏关闭后提前 30 秒软提示」这里没有，只能等陶瓦自己报。
 */
@Composable
internal fun TerracottaSection(gameRoot: String?, onNotice: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()

    // 目录跟着游戏根目录走：陶瓦是「这份游戏目录的附属物」，换个目录就该重新装一份。
    val root = remember(gameRoot) {
        val text = gameRoot?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
        if (text == null) null else File(text)
    }
    val dir = remember(root) { root?.let { Terracotta.dirOf(it) } }
    val client = remember(dir) { dir?.let { TerracottaClient(it) } }

    var installed by remember(dir) { mutableStateOf(dir != null && Terracotta.isInstalled(dir)) }
    // 本地记着的版本号与在线查到的最新版。前者来自安装时写下的小文件，后者只读不写。
    var installedVersion by remember(dir) { mutableStateOf(dir?.let { TerracottaInstaller.readInstalledVersion(it) }.orEmpty()) }
    var latestVersion by remember(dir) { mutableStateOf("") }
    var latestProblem by remember(dir) { mutableStateOf("") }
    var state by remember(dir) { mutableStateOf(TerracottaState()) }
    var busy by remember(dir) { mutableStateOf("") }
    var notice by remember(dir) { mutableStateOf("") }
    var roomInput by remember(dir) { mutableStateOf("") }
    var lastStage by remember(dir) { mutableStateOf("") }
    // 对端消失的专用文案。必须跨轮询保持：只在阶段切换那一秒显示，会被下一帧覆盖回通用文案。
    var overrideText by remember(dir) { mutableStateOf("") }

    fun copy(text: String, label: String) {
        val ok = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        }.isSuccess
        notice = if (ok) "已复制「" + label + "」：" + text else "复制失败，请手动选中这段：" + text
    }

    // 每秒轮询一次。只在装了之后才轮询：没装的时候轮询什么都拿不到，白花一个协程。
    // 轮询走 autoStart = false —— 看一眼不该把进程拉起来，那是「建房」「加入」才做的事。
    LaunchedEffect(dir, installed) {
        if (!installed || client == null) return@LaunchedEffect
        while (true) {
            val fresh = withContext(Dispatchers.IO) { runCatching { client.state(false) }.getOrDefault(TerracottaState()) }
            state = fresh
            val stage = if (fresh.ok) fresh.stage else ""
            if (stage != lastStage) {
                // 阶段迁移：先清旧文案，再按「从哪来、到哪去」决定要不要说一句重话。
                overrideText = ""
                if (stage == TerracottaStages.EXCEPTION) {
                    if (lastStage == TerracottaStages.GUEST_OK) {
                        // 游客侧不清房间码：exception 也可能只是本机网络抖了一下，
                        // 房主的房间还在，同一个码多半还能重连。清掉等于逼用户重新找房主要码。
                        overrideText = Terracotta.NOTE_GUEST_ENDED
                    } else if (lastStage == TerracottaStages.HOST_OK) {
                        // 房主侧必须清：游戏进程没了，这个码定义上就是死的，留着只会被复制走。
                        overrideText = Terracotta.NOTE_HOST_ENDED
                    }
                }
                lastStage = stage
            }
            delay(1000)
        }
    }

    // 装上之后问一句官方最新是哪个版本。只用于显示「有新版本」提示，不自动动用户的文件。
    // 失败（限流、断网、空壳包没配地址）只留一句原因，不打扰用户。
    LaunchedEffect(dir, installed) {
        if (!installed) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            runCatching { TerracottaReleases.fetchLatest() }
        }
        latestVersion = result.getOrNull()?.version.orEmpty()
        latestProblem = result.exceptionOrNull()?.message.orEmpty()
    }

    fun run(label: String, block: suspend () -> String) {
        if (busy.isNotBlank()) return
        busy = label
        scope.launch {
            try {
                val text = block()
                if (text.isNotBlank()) notice = text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.message ?: e.toString()
            } finally {
                busy = ""
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "陶瓦联机（跨网络）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    when {
                        dir == null -> "先在上面填游戏目录"
                        !installed -> "未安装"
                        busy.isNotBlank() -> busy
                        else -> Terracotta.stageText(state.stage)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.stage == TerracottaStages.EXCEPTION) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Text(
                "不在同一个网络时用它：一台机器建房，把房间码发给朋友，对方填进来就能进。" +
                    "走的是陶瓦自己的中转与打洞，双方都不用改路由器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (dir == null) {
                TerracottaHint("先在上面的「让朋友连到你」那一块把游戏目录填好，陶瓦装在它下面的 platypus 目录里。", true)
                return@Column
            }

            // ── 未安装：只给「安装」 ──────────────────────────────────────────
            if (!installed) {
                TerracottaHint(Terracotta.notInstalledText())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            run("正在安装陶瓦…") {
                                // onProgress 必须写名字：ensure 的最后一个参数是 force（Boolean），
                                // 尾随 lambda 会绑到 force 上，报「但这里要 Boolean」。
                                val exe = TerracottaInstaller.ensure(
                                    dir,
                                    onProgress = { p ->
                                        busy = if (p.total > 0L) {
                                            p.step + "（" + (p.fraction * 100).toInt() + "%）"
                                        } else {
                                            p.step
                                        }
                                    },
                                    checkUpdate = true,
                                )
                                installed = true
                                installedVersion = TerracottaInstaller.readInstalledVersion(dir).orEmpty()
                                "陶瓦装好了（" + installedVersion.ifBlank { "版本未知" } + "）：" +
                                    exe.name + "。可以点「创建房间」了。"
                            }
                        },
                        enabled = busy.isBlank(),
                    ) { Text(if (busy.isNotBlank()) busy else "安装 / 更新陶瓦") }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "约 8 MB，下到 " + dir.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "为什么不让启动器直接带着它：陶瓦是 AGPL-3.0，鸭嘴兽是 Apache-2.0 且公开发布产物，" +
                        "把它的二进制打进我们的包里会触发传染条款。所以这里只是替你下载，你自己装一份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // ── 已安装：状态 + 动作 ─────────────────────────────────────────
            val statusText = if (overrideText.isNotBlank()) {
                overrideText
            } else {
                Terracotta.statusText(state)
            }
            SelectionContainer {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.stage == TerracottaStages.EXCEPTION && overrideText.isBlank()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            // 建房 / 复位 / 关闭三个动作
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        run("正在建房…") {
                            withContext(Dispatchers.IO) { client?.host().orEmpty() }.also {
                                state = withContext(Dispatchers.IO) {
                                    runCatching { client?.state(false) }.getOrNull() ?: TerracottaState()
                                }
                            }
                        }
                    },
                    enabled = busy.isBlank() && client != null,
                ) { Text("创建房间") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        run("正在复位…") {
                            withContext(Dispatchers.IO) { client?.reset() }
                            roomInput = ""
                            lastStage = ""
                            overrideText = ""
                            state = TerracottaState()
                            "已复位，当前房间码作废。"
                        }
                    },
                    enabled = busy.isBlank() && state.ok,
                ) { Text("复位") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        run("正在关闭陶瓦…") {
                            withContext(Dispatchers.IO) { client?.shutdown() }
                            lastStage = ""
                            overrideText = ""
                            state = TerracottaState()
                            "已请求关闭陶瓦，房间码作废，游戏里的联机会随之断开。"
                        }
                    },
                    enabled = busy.isBlank() && state.ok,
                ) { Text("关闭陶瓦") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        run("正在更新陶瓦…") {
                            // 更新前先把正在跑的进程停掉：Windows 上正在执行的 exe 是锁着的，
                            // 直接覆盖会解压失败，报一个「拒绝访问」让人摸不着头脑。
                            withContext(Dispatchers.IO) { runCatching { client?.shutdown() } }
                            lastStage = ""
                            overrideText = ""
                            state = TerracottaState()
                            val exe = TerracottaInstaller.ensure(
                                dir,
                                onProgress = { p ->
                                    busy = if (p.total > 0L) {
                                        p.step + "（" + (p.fraction * 100).toInt() + "%）"
                                    } else {
                                        p.step
                                    }
                                },
                                checkUpdate = true,
                            )
                            installedVersion = TerracottaInstaller.readInstalledVersion(dir).orEmpty()
                            installed = true
                            // 装完再问一次在线最新。不能直接把 latestVersion 写成 installedVersion ——
                            // 查不到官方版本时 ensure 会退回内置版本，那样写会把「其实没查到」说成「已是最新」。
                            val fresh = withContext(Dispatchers.IO) {
                                runCatching { TerracottaReleases.fetchLatest() }
                            }
                            latestVersion = fresh.getOrNull()?.version.orEmpty()
                            latestProblem = fresh.exceptionOrNull()?.message.orEmpty()
                            "陶瓦现在是 " + installedVersion.ifBlank { "版本未知" } + "：" + exe.name
                        }
                    },
                    enabled = busy.isBlank(),
                ) { Text(if (latestVersion.isNotBlank() && latestVersion != installedVersion) "更新到 " + latestVersion else "检查更新") }
            }

            // 版本一行：本地这份是哪版、官方最新是哪版。查不到就说查不到，不装作没事。
            Text(
                when {
                    installedVersion.isBlank() && latestVersion.isBlank() -> "版本：未知（这份是早期装的，没有版本记录）"
                    latestVersion.isBlank() -> "版本：" + installedVersion + "。没查到官方最新版" +
                        (if (latestProblem.isNotBlank()) "（" + latestProblem + "）" else "") + "。"
                    installedVersion.isBlank() -> "版本：未知（这份是早期装的）。官方最新是 " + latestVersion + "。"
                    installedVersion == latestVersion -> "版本：" + installedVersion + "（已是最新）。"
                    else -> "版本：" + installedVersion + "，官方最新是 " + latestVersion + "。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 房间码：房主态显示；游客态不显示（游客拿到的是别人的码，自己那份没有意义）
            val room = state.room
            if (state.stage == TerracottaStages.HOST_OK && room.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("房间码", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    SelectionContainer {
                        Text(
                            room,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = { copy(room, "房间码") }) { Text("复制房间码") }
                }
            }

            // 直连地址：只在游客态显示。房主态那个地址是游客本机的回环地址，房主拿到也发不出去。
            if (!state.isHost && state.stage == TerracottaStages.GUEST_OK) {
                if (state.url.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("直连地址", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        SelectionContainer {
                            Text(state.url, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = { copy(state.url, "直连地址") }) { Text("复制直连地址") }
                    }
                }
            }
            if (state.isHost && state.stage == TerracottaStages.HOST_OK) {
                Text(
                    "房主这一侧没有可以发给别人的直连地址 —— 陶瓦给房主的那个地址是游客本机的回环地址，" +
                        "发出去对方也连不上。发给朋友的是上面的房间码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(2.dp))

            // 加入：输入房间码
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = roomInput,
                    onValueChange = { roomInput = it.take(40) },
                    label = { Text("朋友的房间码（U/XXXX-XXXX-XXXX-XXXX）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        run("正在加入房间…") {
                            withContext(Dispatchers.IO) { client?.join(roomInput).orEmpty() }.also {
                                state = withContext(Dispatchers.IO) {
                                    runCatching { client?.state(false) }.getOrNull() ?: TerracottaState()
                                }
                            }
                        }
                    },
                    enabled = busy.isBlank() && roomInput.isNotBlank(),
                ) { Text("加入房间") }
            }

            NoticeFade(notice) { shown -> TerracottaHint(shown) }

            Text(
                "建房那一步要你先在游戏里按 Esc →「对局域网开放」→「开始」，陶瓦才拿得到那个世界。" +
                    "连上之后进游戏「多人游戏」，双击「陶瓦联机大厅」即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 顺带把同一句话抛给页面顶部的提示区，用户滚到别处也看得到
    LaunchedEffect(notice) {
        if (notice.isNotBlank()) onNotice(notice)
    }
}

@Composable
private fun TerracottaHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}