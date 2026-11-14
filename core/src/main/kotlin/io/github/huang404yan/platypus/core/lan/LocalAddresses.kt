package io.github.huang404yan.platypus.core.lan

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 本机的一个地址，别人可以把它填进「直接连接」。
 *
 * 带 [kind] 是为了让界面能说清「这个给同一 Wi-Fi 的朋友、那个给外网的」——
 * 光给一串 IP，用户不知道该把哪个发出去，也不知道为什么对方连不上。
 */
data class LocalBinding(
    val host: String,
    val kind: Kind,
    val interfaceName: String = "",
) {
    enum class Kind {
        /** 私网 IPv4（10./172.16-31./192.168.）：同一局域网或同一 Wi-Fi 能用。 */
        LAN,

        /** 全局 IPv6：双方都有公网 IPv6 时可以跨网络直连，不需要端口转发。 */
        IPV6,

        /** 其它（公网 IPv4、运营商级 NAT 段）：单靠它通常连不上，但列出来免得用户以为漏了。 */
        OTHER,
    }

    /** 拼成游戏里能直接粘的写法：IPv6 要加方括号，否则端口会被当成地址的一部分。 */
    fun withPort(port: Int): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

/**
 * 枚举本机的可用地址。
 *
 * 只做「本地看得见的地址」，不联网查公网 IP —— 查公网 IP 意味着把请求发给第三方服务，
 * 而这个功能只在用户主动展开时才需要，没必要为它引入一个外部依赖；何况查到的公网 IP
 * 在开了 NAT 的情况下本来也连不进来（少了端口转发这一环）。
 *
 * 会不会有地址也不代表能连通：防火墙、路由器的 AP 隔离、ISP 的公网 IP 都在这之外。
 * 所以界面文案说的是「把你的地址发给朋友试试」，不是「这样就能连」。
 */
object LocalAddresses {

    /**
     * 枚举结果里的一项，[pick] 的输入。
     *
     * 把网卡读出来的东西先摊平成这个，过滤规则就能单测 —— 真实网卡内容随机器变，
     * 拿它当断言依据只能测出「这台机器现在有几个网卡」。
     */
    data class Entry(
        val host: String,
        val interfaceName: String = "",
        val up: Boolean = true,
        val loopback: Boolean = false,
    )

    /** 读本机网卡。读不到（权限、系统限制）返回空表，不抛。 */
    fun list(): List<LocalBinding> {
        val entries = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { nif ->
                val up = runCatching { nif.isUp }.getOrDefault(false)
                val loopback = runCatching { nif.isLoopback }.getOrDefault(false)
                nif.inetAddresses.toList().mapNotNull { addr ->
                    runCatching { addr.hostAddress }.getOrNull()?.let {
                        Entry(host = it, interfaceName = nif.name, up = up, loopback = loopback)
                    }
                }
            }
        }.getOrDefault(emptyList())
        return pick(entries)
    }

    /**
     * 从摊平的网卡项里挑出能用的地址。
     *
     * 丢掉：没启用的网卡、回环、链路本地（169.254./fe80::，只在同一段链路里有效，
     * 填进游戏必失败）、以及带 zone id 的 IPv6（`fe80::1%wlan0` 那种，游戏不认）。
     * 同一个地址出现在多张网卡上只留一条。
     */
    fun pick(entries: List<Entry>): List<LocalBinding> {
        val seen = LinkedHashMap<String, LocalBinding>()
        for (entry in entries) {
            if (!entry.up || entry.loopback) continue
            val host = entry.host.substringBefore('%').trim()
            if (host.isEmpty()) continue
            if (isLinkLocal(host)) continue
            val kind = classify(host) ?: continue
            seen.putIfAbsent(host, LocalBinding(host = host, kind = kind, interfaceName = entry.interfaceName))
        }
        // 先局域网、再 IPv6、最后其它；同类按网卡名与地址排，顺序稳定，界面不会跳来跳去。
        return seen.values.sortedWith(
            compareBy<LocalBinding> { it.kind.ordinal }
                .thenBy { it.interfaceName }
                .thenBy { it.host },
        )
    }

    /** 判断一个地址能不能用；不认识的写法（比如不是 IP）返回 null，直接丢掉。 */
    private fun classify(host: String): LocalBinding.Kind? {
        val v4 = parseV4(host)
        if (v4 != null) {
            return when {
                v4[0] == 10 -> LocalBinding.Kind.LAN
                v4[0] == 172 && v4[1] in 16..31 -> LocalBinding.Kind.LAN
                v4[0] == 192 && v4[1] == 168 -> LocalBinding.Kind.LAN
                // 100.64/10 是运营商级 NAT：同一家 ISP 的用户之间有时能通，但不受我们控制
                v4[0] == 100 && v4[1] in 64..127 -> LocalBinding.Kind.OTHER
                else -> LocalBinding.Kind.OTHER
            }
        }
        if (host.contains(':')) {
            val lower = host.lowercase()
            if (lower == "::1") return null
            return LocalBinding.Kind.IPV6
        }
        return null
    }

    private fun isLinkLocal(host: String): Boolean {
        if (host.startsWith("169.254.")) return true
        val lower = host.lowercase()
        // fe80::/10 的前缀：fe80、fe90、fea0、feb0 开头
        return lower.startsWith("fe8") || lower.startsWith("fe9") ||
            lower.startsWith("fea") || lower.startsWith("feb")
    }

    /** 只认四段十进制点分；不是合法 IPv4 返回 null。 */
    private fun parseV4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            nums[i] = n
        }
        return nums
    }
}
