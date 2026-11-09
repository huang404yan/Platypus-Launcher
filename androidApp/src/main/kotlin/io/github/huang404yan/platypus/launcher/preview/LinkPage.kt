package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.lan.LanDiscovery
import io.github.huang404yan.platypus.core.lan.LanScanResult
import io.github.huang404yan.platypus.core.lan.LanWorld
import kotlinx.coroutines.launch

/**
 * 联机页（手机）。
 *
 * 与桌面 LinkPage 同一套 [LanDiscovery]：听 Minecraft 的组播广播（224.0.2.60:4445），
 * 把「[MOTD]…[/MOTD][AD]端口[/AD]」拆成可复制的地址。
 *
 * 手机上没有「把地址填进游戏」这回事——进入游戏仍然是启动器自己做不了的事
 * （要在应用内起 JVM，那是 S1 spike 的范围）。这里只负责**把地址找出来**，
 * 用户复制后进游戏「多人游戏 → 直接连接」粘贴。
 *
 * 与桌面的两处差别：① 剪贴板走 Android 的 ClipboardManager，不能用 java.awt；
 * ② Android 11+ 的组播要拿着 WifiManager 的 MulticastLock 才收得到包，
 *    这里在扫描前后各拿一次、放开一次（见 [acquireMulticastLock] 的注释）。
 */
@Composable
internal fun LinkPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LanScanResult?>(null) }
    var manual by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    fun scan() {
        if (scanning) return
        scanning = true
        notice = ""
        scope.launch {
            val lock = acquireMulticastLock(context)
            val r = try {
                LanDiscovery.listen()
            } finally {
                lock?.release()
            }
            result = r
            scanning = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("联机", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "在游戏里对局域网开放后，这里能听到那个世界的地址。连进去仍然在游戏里「多人游戏 → 直接连接」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { scan() }, enabled = !scanning) {
                    Text(if (scanning) "正在听…" else "扫描局域网")
                }
                Text(
                    scanningText(result, scanning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (notice.isNotBlank()) {
            item { Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }

        item {
            Text("局域网里的世界", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        val current = result
        when {
            current == null -> item {
                Text(
                    "点「扫描局域网」开始听，只花 5 秒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 先把 error 取成局部不可空串：它是跨模块 public 属性，Kotlin 既不做智能转换，
            // 也不能靠 != null 判断让下一行变成 String。
            current.error != null -> item {
                val message = current.error ?: ""
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("扫描出了问题", fontWeight = FontWeight.SemiBold)
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            current.worlds.isEmpty() -> item {
                Text(
                    "没听到世界。确认房主已经在游戏里「对局域网开放」、两台设备在同一个 Wi-Fi 下，" +
                        "并且路由器没有开「AP 隔离」（开了隔离，设备之间互相看不见）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> items(current.worlds, key = { it.key }) { world ->
                WorldCard(world) { notice = copyToClipboard(context, world.address) }
            }
        }

        item {
            Text("手动直连", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "知道房主的内网 IP 但没听到广播时用。不写端口按 25565。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("地址（如 192.168.1.5:25565）") },
                    singleLine = true,
                )
                OutlinedButton(
                    onClick = {
                        val parsed = LanDiscovery.parseAddress(manual)
                        if (parsed == null) {
                            notice = "地址格式不对：要写成「IP」或「IP:端口」。"
                        } else {
                            notice = copyToClipboard(context, parsed.text)
                        }
                    },
                    enabled = manual.isNotBlank(),
                ) { Text("解析并复制") }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("为什么不在这里「点一下就进」", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Minecraft 的多人游戏要走正版校验与会话服务，绕过游戏去连会丢皮肤、丢会话，也是模组不兼容的最常见来源。" +
                            "所以这里只做「把地址找出来」，真正进世界仍然由游戏自己负责。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "跨网络的联机（不在同一个 Wi-Fi）需要 NAT 穿透或中转服务器，这一版没做。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun scanningText(result: LanScanResult?, scanning: Boolean): String {
    if (scanning) return "听了 " + (LanDiscovery.DEFAULT_LISTEN_MS / 1000) + " 秒左右，稍等。"
    val current = result ?: return ""
    val seconds = String.format("%.1f", current.durationMs / 1000.0)
    return "听了 " + seconds + " 秒，找到 " + current.worlds.size + " 个世界。"
}

@Composable
private fun WorldCard(world: LanWorld, onCopy: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(world.motd, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    world.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onCopy) { Text("复制地址") }
            }
        }
    }
}

/**
 * 拿一个 Wi-Fi 组播锁。
 *
 * Android 在 Wi-Fi 上默认把组播包丢掉（省电），不拿 MulticastLock 的话
 * [LanDiscovery.listen] 会一直听到 0 个世界——看起来像「房主没开局域网」，
 * 实际是本机根本没收到。锁要 CHANGE_WIFI_MULTICAST_STATE 权限（Manifest 已声明）。
 *
 * 拿不到（比如用移动数据、或系统不给锁）就返回 null，扫描照常进行——
 * 有线 / 部分机型本来就不需要它。
 */
private fun acquireMulticastLock(context: android.content.Context): android.net.wifi.WifiManager.MulticastLock? {
    return runCatching {
        val wifi = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager ?: return null
        wifi.createMulticastLock("platypus-lan-scan").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()
}
