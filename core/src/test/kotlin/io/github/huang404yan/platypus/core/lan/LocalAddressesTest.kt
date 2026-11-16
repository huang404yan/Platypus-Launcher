package io.github.huang404yan.platypus.core.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAddressesTest {

    private fun entry(host: String, name: String = "eth0", up: Boolean = true, loopback: Boolean = false) =
        LocalAddresses.Entry(host = host, interfaceName = name, up = up, loopback = loopback)

    @Test
    fun `只留下启用的非回环地址`() {
        val picked = LocalAddresses.pick(
            listOf(
                entry("192.168.1.20"),
                entry("127.0.0.1", loopback = true),
                entry("10.0.0.5", name = "eth1", up = false),
            ),
        )
        assertEquals(listOf("192.168.1.20"), picked.map { it.host })
    }

    @Test
    fun `私网 IPv4 认作局域网`() {
        val picked = LocalAddresses.pick(
            listOf(entry("10.1.2.3"), entry("172.20.0.9", name = "b"), entry("192.168.0.7", name = "c")),
        )
        assertEquals(3, picked.size)
        assertTrue(picked.all { it.kind == LocalBinding.Kind.LAN })
    }

    @Test
    fun `公网 IPv4 归到其它类但不丢`() {
        val picked = LocalAddresses.pick(listOf(entry("203.0.113.9")))
        assertEquals(1, picked.size)
        assertEquals(LocalBinding.Kind.OTHER, picked.single().kind)
    }

    @Test
    fun `链路本地地址一律丢掉`() {
        val picked = LocalAddresses.pick(
            listOf(entry("169.254.10.10"), entry("fe80::1", name = "b"), entry("192.168.1.2", name = "c")),
        )
        assertEquals(listOf("192.168.1.2"), picked.map { it.host })
    }

    @Test
    fun `IPv6 去掉 zone id 后保留`() {
        val picked = LocalAddresses.pick(listOf(entry("2001:db8::5%eth0")))
        assertEquals(listOf("2001:db8::5"), picked.map { it.host })
        assertEquals(LocalBinding.Kind.IPV6, picked.single().kind)
    }

    @Test
    fun `同一个地址只留一条`() {
        val picked = LocalAddresses.pick(
            listOf(entry("192.168.1.2", name = "a"), entry("192.168.1.2", name = "b")),
        )
        assertEquals(1, picked.size)
        assertEquals("a", picked.single().interfaceName)
    }

    @Test
    fun `排序先局域网再 IPv6 最后其它`() {
        val picked = LocalAddresses.pick(
            listOf(entry("203.0.113.9", name = "a"), entry("2001:db8::5", name = "b"), entry("192.168.1.2", name = "c")),
        )
        assertEquals(
            listOf(LocalBinding.Kind.LAN, LocalBinding.Kind.IPV6, LocalBinding.Kind.OTHER),
            picked.map { it.kind },
        )
    }

    @Test
    fun `不是 IP 的字符串丢掉`() {
        assertTrue(LocalAddresses.pick(listOf(entry("localhost"), entry("192.168.1.300"))).isEmpty())
    }

    @Test
    fun `拼端口时 IPv6 加方括号`() {
        assertEquals("192.168.1.2:25565", LocalBinding("192.168.1.2", LocalBinding.Kind.LAN).withPort(25565))
        assertEquals("[2001:db8::5]:25565", LocalBinding("2001:db8::5", LocalBinding.Kind.IPV6).withPort(25565))
    }

    // ── 运营商级 NAT 判定（2026-11-16 补公网直连时加） ──────────────

    @Test
    fun `100_64 段认作运营商级 NAT`() {
        // 100.64.0.0/10 是 RFC 6598 给运营商级 NAT 留的段，这一段地址拿来做端口映射没有意义：
        // 外面还有一层运营商的 NAT，别人连不进来。
        assertTrue(LocalAddresses.isCarrierNat("100.64.0.1"))
        assertTrue(LocalAddresses.isCarrierNat("100.100.20.30"))
        assertTrue(LocalAddresses.isCarrierNat("100.127.255.254"))
    }

    @Test
    fun `100_64 段之外不算运营商级 NAT`() {
        assertFalse(LocalAddresses.isCarrierNat("100.63.255.255"), "100.63 不在 100.64/10 里")
        assertFalse(LocalAddresses.isCarrierNat("100.128.0.1"), "100.128 不在 100.64/10 里")
        // 真公网 IPv4：能直接做端口映射，不能被误判进 NAT 那类
        assertFalse(LocalAddresses.isCarrierNat("203.0.113.9"))
        assertFalse(LocalAddresses.isCarrierNat("192.168.1.2"))
        // IPv6 与非 IP 字符串一律不算
        assertFalse(LocalAddresses.isCarrierNat("2001:db8::5"))
        assertFalse(LocalAddresses.isCarrierNat("localhost"))
        assertFalse(LocalAddresses.isCarrierNat(""))
    }

    @Test
    fun `运营商级 NAT 段仍然归在其它类`() {
        // 判定只影响界面怎么解释它，不改 Kind —— 加枚举值会打断安卓端那份 LinkPage。
        val picked = LocalAddresses.pick(listOf(entry("100.64.0.1")))
        assertEquals(1, picked.size)
        assertEquals(LocalBinding.Kind.OTHER, picked.single().kind)
        assertTrue(LocalAddresses.isCarrierNat(picked.single().host))
    }

    @Test
    fun `本机至少要能读出一张网卡或不报错`() {
        // 真实网卡内容随机器变，这里只验「不抛、结果结构合法」。
        val list = LocalAddresses.list()
        assertTrue(list.all { it.host.isNotBlank() })
    }
}
