package io.github.huang404yan.platypus.core.lan

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `本机至少要能读出一张网卡或不报错`() {
        // 真实网卡内容随机器变，这里只验「不抛、结果结构合法」。
        val list = LocalAddresses.list()
        assertTrue(list.all { it.host.isNotBlank() })
    }
}
