package io.github.huang404yan.platypus.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 命令送达与否的说法、以及权限等级的提醒。
 *
 * 这两段文字是配置页唯一会把「到底生没生效」告诉用户的地方，口径错一个字，
 * 用户就会带着「已经加进去了」的理解回游戏，然后在登录界面被挡住。
 */
class ServerConfigNoticeTest {

    @Test
    fun `走 RCON 时说当场生效，并把命令原样带上`() {
        val text = commandNotice("whitelist add Steve", CommandSend.Rcon("Added Steve"))
        assertTrue(text.contains("whitelist add Steve"))
        assertTrue(text.contains("当场生效"))
    }

    @Test
    fun `走标准输入时说回执要去服务端那一页看`() {
        val text = commandNotice("op Steve", CommandSend.Stdin)
        assertTrue(text.contains("op Steve"))
        assertTrue(text.contains("标准输入"))
        assertTrue(text.contains("回执"))
        // 标准输入没有回执可等，不能承诺「已生效」。
        assertTrue(!text.contains("当场生效"))
    }

    @Test
    fun `进程没了时明说命令没发出去、下次开服才生效`() {
        val text = commandNotice("op Steve", CommandSend.NotRunning)
        assertTrue(text.contains("没发出去"))
        assertTrue(text.contains("下次开服生效"))
    }

    @Test
    fun `发失败时把原因带出来并让用户手动补一次`() {
        val text = commandNotice("deop Steve", CommandSend.Failed("输出流已关闭"))
        assertTrue(text.contains("deop Steve"))
        assertTrue(text.contains("输出流已关闭"))
        assertTrue(text.contains("手动发一次"))
    }

    @Test
    fun `四种结果说四种不同的话`() {
        val all = listOf(
            commandNotice("x", CommandSend.Rcon("")),
            commandNotice("x", CommandSend.Stdin),
            commandNotice("x", CommandSend.NotRunning),
            commandNotice("x", CommandSend.Failed("坏了")),
        )
        assertEquals(4, all.toSet().size)
    }

    @Test
    fun `等级一致时不啰嗦`() {
        assertEquals("", levelNotice(4, 4))
        assertEquals("", levelNotice(1, 1))
    }

    @Test
    fun `等级不一致时把两个值都说出来`() {
        val text = levelNotice(chosen = 2, configured = 4)
        assertTrue(text.contains("4 级"))
        assertTrue(text.contains("2 级"))
        assertTrue(text.contains("ops.json"))
    }

    @Test
    fun `没选等级时不提醒`() {
        assertEquals("", levelNotice(0, 4))
        assertEquals("", levelNotice(-1, 4))
    }
}