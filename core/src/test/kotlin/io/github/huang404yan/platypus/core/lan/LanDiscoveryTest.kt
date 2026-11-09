package io.github.huang404yan.platypus.core.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanDiscoveryTest {

    @Test
    fun `解析标准的 MOTD 与 AD 段`() {
        val world = LanDiscovery.parse("[MOTD]Steve 的世界[/MOTD][AD]54321[/AD]", "192.168.1.10")
        assertNotNull(world)
        assertEquals("Steve 的世界", world.motd)
        assertEquals(54321, world.port)
        assertEquals("192.168.1.10", world.host)
        assertEquals("192.168.1.10:54321", world.address)
    }

    @Test
    fun `剥掉 § 颜色码`() {
        val world = LanDiscovery.parse("[MOTD]§a生存§r服[/MOTD][AD]25566[/AD]", "10.0.0.2")
        assertNotNull(world)
        assertEquals("生存服", world.motd)
        assertEquals("10.0.0.2:25566", world.address)
    }

    @Test
    fun `只有 AD 没有 MOTD 时给未命名世界`() {
        val world = LanDiscovery.parse("[AD]25565[/AD]", "10.0.0.3")
        assertNotNull(world)
        assertEquals("未命名世界", world.motd)
        assertEquals(25565, world.port)
    }

    @Test
    fun `空 MOTD 也退回未命名世界`() {
        val world = LanDiscovery.parse("[MOTD][/MOTD][AD]25570[/AD]", "10.0.0.4")
        assertNotNull(world)
        assertEquals("未命名世界", world.motd)
    }

    @Test
    fun `不是广播包就返回 null`() {
        assertNull(LanDiscovery.parse("hello world", "10.0.0.5"))
        assertNull(LanDiscovery.parse("", "10.0.0.5"))
        assertNull(LanDiscovery.parse("[MOTD]x[/MOTD]", "10.0.0.5"))
    }

    @Test
    fun `端口越界或不合法就返回 null`() {
        assertNull(LanDiscovery.parse("[AD]0[/AD]"))
        assertNull(LanDiscovery.parse("[AD]70000[/AD]"))
        assertNull(LanDiscovery.parse("[AD]abc[/AD]"))
    }

    @Test
    fun `host 未知时 address 只给端口`() {
        val world = LanDiscovery.parse("[MOTD]x[/MOTD][AD]25565[/AD]", null)
        assertNotNull(world)
        assertEquals(":25565", world.address)
        assertEquals("?:25565", world.key)
    }

    @Test
    fun `同一地址端口算同一个 key`() {
        val a = LanDiscovery.parse("[MOTD]a[/MOTD][AD]1000[/AD]", "1.2.3.4")
        val b = LanDiscovery.parse("[MOTD]b[/MOTD][AD]1000[/AD]", "1.2.3.4")
        val c = LanDiscovery.parse("[MOTD]c[/MOTD][AD]1001[/AD]", "1.2.3.4")
        assertNotNull(a)
        assertNotNull(b)
        assertNotNull(c)
        assertEquals(a.key, b.key)
        assertTrue(a.key != c.key)
    }

    @Test
    fun `stripColors 处理连续与结尾的颜色码`() {
        // 「§a§lb§rc」里 a、l、r 都是样式码，真正的内容只有 b 和 c。
        assertEquals("bc", LanDiscovery.stripColors("§a§lb§rc"))
        assertEquals("abc", LanDiscovery.stripColors("abc§"))
        assertEquals("", LanDiscovery.stripColors("§a§b"))
    }

    @Test
    fun `手动地址省略端口时按 25565`() {
        val addr = LanDiscovery.parseAddress("192.168.1.10")
        assertNotNull(addr)
        assertEquals("192.168.1.10", addr.host)
        assertEquals(25565, addr.port)
        assertEquals("192.168.1.10:25565", addr.text)
    }

    @Test
    fun `手动地址带端口`() {
        val addr = LanDiscovery.parseAddress("  192.168.1.10:54321  ")
        assertNotNull(addr)
        assertEquals(54321, addr.port)
    }

    @Test
    fun `手动地址空白或非法返回 null`() {
        assertNull(LanDiscovery.parseAddress(""))
        assertNull(LanDiscovery.parseAddress("   "))
        assertNull(LanDiscovery.parseAddress(":25565"))
        assertNull(LanDiscovery.parseAddress("1.2.3.4:0"))
        assertNull(LanDiscovery.parseAddress("1.2.3.4:70000"))
        assertNull(LanDiscovery.parseAddress("1.2.3.4:abc"))
    }

    @Test
    fun `组播地址与端口是客户端写死的值`() {
        assertEquals("224.0.2.60", LanDiscovery.GROUP)
        assertEquals(4445, LanDiscovery.PORT)
    }
}
