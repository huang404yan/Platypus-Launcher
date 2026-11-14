package io.github.huang404yan.platypus.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.lan.LanAddress
import io.github.huang404yan.platypus.core.lan.LanDiscovery
import io.github.huang404yan.platypus.core.lan.LanScanResult
import io.github.huang404yan.platypus.core.lan.LanWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 联机页：把局域网里「对局域网开放」的世界找出来，并且能直接用这个地址把游戏拉起来。
 *
 * 连接本身仍然交给游戏 —— 走的是 1.20 起的 `--quickPlayMultiplayer`，也就是官方的
 * 「直接连接」参数。不自己实现协议栈：绕过游戏连进去会丢皮肤、丢会话、和大部分模组不兼容。
 *
 * 不做 NAT 穿透、不做房间服务器：那是另一套东西（陶瓦、e4mc 那类），
 * 跟「局域网里几台机器互相看得见」不是一个问题，混在一起会把可用性拖垮。
 *
 * @param onPlay 点「直接进」时把地址交出去；上层负责切到启动页并把地址交给游戏命令行。
 */
@Composable
fun LinkPage(onPlay: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<LanScanResult?>(null) }
    var notice by remember { mutableStateOf("") }
    var manualText by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf("") }

    fun copy(text: String, label: String) {
        val ok = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        }.isSuccess
        notice = if (ok) "已复制「" + label + "」：" + text else "复制失败，请手动选中这段：" + text
    }

    suspend fun scanLan() {
        scanning = true
        scan = null
        notice = ""
        try {
            scan = withContext(Dispatchers.IO) { LanDiscovery.listen(LanDiscovery.DEFAULT_LISTEN_MS) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            scan = LanScanResult(error = e.message ?: e.toString())
        } finally {
            scanning = false
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("联机", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "在游戏里对局域网开放后，这里能听到那个世界的地址。点「直接进」会用官方的直接连接参数把游戏拉起来，跳过主菜单。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(onClick = { scope.launch { scanLan() } }, enabled = !scanning) {
                Text(if (scanning) "正在听广播…" else "扫描局域网")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    scanning -> "听 5 秒，Minecraft 每 1.5 秒广播一次。"
                    scan == null -> "还没扫过。"
                    scan!!.error != null -> "本机听不到广播。"
                    else -> "听了 " + (scan!!.durationMs / 1000.0).toString().take(4) + " 秒，找到 " +
                        scan!!.worlds.size + " 个世界。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        scan?.error?.let { LanHint(it, error = true) }

        if (notice.isNotBlank()) LanHint(notice)

        Text("局域网里的世界", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val worlds = scan?.worlds.orEmpty()
            if (worlds.isEmpty()) {
                Text(
                    when {
                        scanning -> "正在听…"
                        scan == null -> "点上面的「扫描局域网」。"
                        scan!!.error != null -> "没听到内容，见上面的红字。"
                        else -> "没听到世界。确认房主那边已经在游戏里「对局域网开放」，两台机器在同一网段。"
                    },
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(worlds, key = { it.key }) { world ->
                        WorldRow(
                            world = world,
                            onCopy = { copy(world.address, world.motd) },
                            onPlay = { onPlay(world.address) },
                        )
                    }
                }
            }
        }

        Text("手动直连", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "知道房主的内网 IP 但没扫到时，直接填。不写端口按 25565 算。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = manualText,
                onValueChange = { manualText = it; manualError = "" },
                label = { Text("地址（如 192.168.1.10:54321）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                isError = manualError.isNotBlank(),
            )
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = {
                val parsed = LanDiscovery.parseAddress(manualText)
                if (parsed == null) {
                    manualError = "看不懂这个地址，要么填 IP，要么填 IP:端口。"
                } else {
                    copy(parsed.text, "直连地址")
                }
            }) { Text("解析并复制") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val parsed = LanDiscovery.parseAddress(manualText)
                if (parsed == null) {
                    manualError = "看不懂这个地址，要么填 IP，要么填 IP:端口。"
                } else {
                    manualError = ""
                    onPlay(parsed.text)
                }
            }) { Text("直接进") }
        }
        if (manualError.isNotBlank()) LanHint(manualError, error = true)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("「直接进」是怎么做到的", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "用的不是自己写的协议栈，而是游戏自带的 --quickPlayMultiplayer 参数（1.20 起才有）。" +
                        "启动时跳过主菜单，进游戏后由游戏自己走正版校验与会话服务，皮肤、会话、模组都和手动连一样。" +
                        "1.19 及更早的版本没有这条参数，点了会提示换实例——不会假装进了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text("跨网络的联机（不在同一局域网）需要 NAT 穿透或中转服务器，这一版没做。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WorldRow(world: LanWorld, onCopy: () -> Unit, onPlay: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(world.motd, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                world.address + (if (world.host.isNullOrBlank()) "（来源地址未知）" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onCopy) { Text("复制地址") }
        Spacer(Modifier.width(8.dp))
        // 扫到的地址都带了端口，直接能进。
        Button(onClick = onPlay) { Text("直接进") }
    }
}

@Composable
private fun LanHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}
