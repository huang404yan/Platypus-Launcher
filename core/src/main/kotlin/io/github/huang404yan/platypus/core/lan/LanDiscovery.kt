package io.github.huang404yan.platypus.core.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * 局域网里的一个「对局域网开放」的世界。
 *
 * Minecraft 客户端开「对局域网开放」后，每 1.5 秒往组播地址 224.0.2.60:4445 发一条
 * 纯文本广播，形如：
 *
 * ```
 * [MOTD]Steve 的世界[/MOTD][AD]54321[/AD]
 * ```
 *
 * [motd] 是原样文本（可能带 § 颜色码），[port] 是房主机器上的临时端口 —— 每次开房间都会变，
 * 所以不能缓存，只能现场听。
 */
data class LanWorld(
    /** 世界名（[MOTD] 里的原文，已剥掉 § 颜色码）。 */
    val motd: String,
    /** 房主机器上对局域网开放的端口。 */
    val port: Int,
    /** 广播包的来源地址，也就是房主的内网 IP；监听失败时为 null。 */
    val host: String? = null,
    /** 收到这个广播的时刻（毫秒）。 */
    val receivedAt: Long = System.currentTimeMillis(),
) {
    /** 可以直接粘进「多人游戏 → 直接连接」的地址。 */
    val address: String get() = if (host.isNullOrBlank()) ":$port" else "$host:$port"

    /** 同一台机器上的同一个端口算同一个世界，用来去重。 */
    val key: String get() = "${host ?: "?"}:$port"
}

/** 一次扫描的结果。 */
data class LanScanResult(
    val worlds: List<LanWorld> = emptyList(),
    /** 监听时长（毫秒），界面上用来解释「扫了多久」。 */
    val durationMs: Long = 0L,
    /** 本机组播不可用时写原因，空表示正常扫完。 */
    val error: String? = null,
)

/**
 * 局域网世界发现。
 *
 * 只做「听广播」，不做 NAT 穿透、不做房间服务器 —— 这条路是 Minecraft 客户端自带的，
 * 任何启动器都只是帮用户把地址找出来，不需要自己的服务端。
 */
object LanDiscovery {

    /** Minecraft 的局域网广播组播地址与端口，写死在客户端里，不可配置。 */
    const val GROUP = "224.0.2.60"
    const val PORT = 4445

    /** 默认听这么久：客户端每 1.5 秒发一次，听 5 秒足够漏不掉。 */
    const val DEFAULT_LISTEN_MS = 5_000L

    private val MOTD_PATTERN = Regex("\\[MOTD\\](.*?)\\[/MOTD\\]", RegexOption.DOT_MATCHES_ALL)
    private val AD_PATTERN = Regex("\\[AD\\](\\d{1,5})\\[/AD\\]")

    /**
     * 解析一条广播文本。
     *
     * 两种格式都要认：
     * - 1.6 以后：`[MOTD]名字[/MOTD][AD]端口[/AD]`
     * - 1.6 以前（极老版本）：只有 `[AD]端口[/AD]`，没有 MOTD
     *
     * 端口不合法、或者压根找不到 [AD] 段，就返回 null —— 局域网里可能有别的软件也在往这个端口
     * 发东西，不能把无关的包当成世界。
     */
    fun parse(payload: String, host: String? = null, receivedAt: Long = System.currentTimeMillis()): LanWorld? {
        if (payload.isBlank()) return null
        val portText = AD_PATTERN.find(payload)?.groupValues?.get(1) ?: return null
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val motd = MOTD_PATTERN.find(payload)?.groupValues?.get(1)
            ?.let { stripColors(it) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "未命名世界"
        return LanWorld(motd = motd, port = port, host = host, receivedAt = receivedAt)
    }

    /**
     * 剥掉 Minecraft 的 § 颜色/样式码（§a、§l 之类）。
     *
     * 界面里没法渲染这些码，留着会显示成方块；顺便把字符串两端裸露的 §x 也清掉。
     */
    fun stripColors(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '§') {
                // § 后面跟的是样式码；结尾单独一个 § 没有后续字符，也一并丢掉，
                // 否则界面上会显示成一个光秃秃的方块。
                i += if (i + 1 < text.length) 2 else 1
                continue
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /**
     * 监听 [durationMs] 毫秒，把听到的世界收集起来。
     *
     * 用 [MulticastSocket] 加入组播组；同一个世界收到多次只留最后一次（端口可能变）。
     * 组播在任何一台机器上都可能被防火墙挡掉，所以这里不抛异常，失败写进 [LanScanResult.error]，
     * 让界面如实说「本机听不到广播」，而不是假装「局域网里没有世界」。
     */
    suspend fun listen(
        durationMs: Long = DEFAULT_LISTEN_MS,
        onWorld: ((LanWorld) -> Unit)? = null,
    ): LanScanResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val deadline = started + durationMs.coerceAtLeast(200L)
        val found = LinkedHashMap<String, LanWorld>()
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(PORT)
            socket.reuseAddress = true
            socket.joinGroup(InetAddress.getByName(GROUP))
            val buffer = ByteArray(1500)
            while (System.currentTimeMillis() < deadline) {
                val wait = (deadline - System.currentTimeMillis()).coerceAtLeast(1L).toInt()
                socket.soTimeout = wait.coerceAtMost(500)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val host = packet.address?.hostAddress
                val world = parse(text, host) ?: continue
                found[world.key] = world
                onWorld?.invoke(world)
            }
            LanScanResult(
                worlds = found.values.sortedByDescending { it.receivedAt },
                durationMs = System.currentTimeMillis() - started,
            )
        } catch (e: Exception) {
            LanScanResult(
                worlds = found.values.toList(),
                durationMs = System.currentTimeMillis() - started,
                error = describe(e),
            )
        } finally {
            runCatching { socket?.leaveGroup(InetAddress.getByName(GROUP)) }
            runCatching { socket?.close() }
        }
    }

    /** 绑定 4445 失败最常见的两个原因，给成人话。 */
    private fun describe(e: Exception): String = when (e) {
        is java.net.BindException ->
            "端口 4445 被占用（可能有另一个启动器正在扫局域网），关掉它再试。原始报错：" + e.message
        is java.net.SocketException ->
            "本机组播不可用（常见原因是防火墙拦了 UDP 4445）。原始报错：" + e.message
        else -> "监听失败：" + (e.message ?: e.toString())
    }

    /**
     * 手动输入的地址是否像个能连的 `主机:端口`。
     *
     * 允许省略端口（Minecraft 直接连接默认 25565），也允许只填 IP。
     */
    fun parseAddress(text: String): LanAddress? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        // 只有「:25565」这种主机为空的不算地址；IPv6 里的冒号不在这一版处理范围内。
        if (raw.startsWith(":")) return null
        val host: String
        val port: Int
        val colon = raw.lastIndexOf(':')
        if (colon > 0 && raw.indexOf(':') == colon) {
            host = raw.substring(0, colon).trim()
            port = raw.substring(colon + 1).trim().toIntOrNull() ?: return null
        } else {
            host = raw
            port = 25565
        }
        if (host.isEmpty()) return null
        if (port !in 1..65535) return null
        return LanAddress(host, port)
    }
}

/** 手动填写的直连地址。 */
data class LanAddress(val host: String, val port: Int = 25565) {
    val text: String get() = "$host:$port"
}
