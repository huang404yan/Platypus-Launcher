package io.github.huang404yan.platypus.core.edition

import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstRunTest {

    private fun settings(firstRunAt: Long = 0L, clientId: String = "", active: String = "") =
        LauncherSettings(
            firstRunAt = firstRunAt,
            microsoftClientId = clientId,
            activeAccountUuid = active,
        )

    private fun formal(
        firstRunAt: Long = 0L,
        signedIn: Boolean = false,
        accountsLoaded: Boolean = true,
        clientId: String = "",
    ) = FirstRun.state(
        settings(firstRunAt = firstRunAt, clientId = clientId),
        edition = Edition.FORMAL,
        signedIn = signedIn,
        accountsLoaded = accountsLoaded,
    )

    private fun preview(firstRunAt: Long = 0L) = FirstRun.state(
        settings(firstRunAt = firstRunAt),
        edition = Edition.PREVIEW,
    )

    @Test
    fun `没走过引导时算首次启动`() {
        assertTrue(FirstRun.needed(settings()))
    }

    @Test
    fun `走过之后不再算首次启动`() {
        assertFalse(FirstRun.needed(settings(firstRunAt = 1L)))
        assertFalse(FirstRun.needed(settings(firstRunAt = 1_760_000_000_000L)))
    }

    @Test
    fun `孵化版可以关掉，正式版不行`() {
        assertTrue(preview().dismissible)
        assertFalse(formal().dismissible)
        assertEquals("先随便看看", preview().secondaryLabel)
        assertEquals(null, formal().secondaryLabel)
    }

    @Test
    fun `正式版没登录正版账号时要拦住`() {
        val state = formal(signedIn = false, accountsLoaded = true)
        assertTrue(state.blocked)
        assertEquals("先登录一个正版账号", state.title)
        assertEquals("去登录", state.primaryLabel)
    }

    @Test
    fun `账号还没读出来时先不拦`() {
        // 第一帧按「没有账号」处理的话，每个正式版用户都会先看到一堵墙再半秒消失。
        val state = formal(signedIn = false, accountsLoaded = false)
        assertFalse(state.blocked)
        assertTrue(state.needed)
    }

    @Test
    fun `正式版登录过之后放行`() {
        val state = formal(signedIn = true, accountsLoaded = true)
        assertFalse(state.blocked)
        assertEquals("欢迎使用鸭嘴兽启动器", state.title)
    }

    @Test
    fun `孵化版从来不拦，只是提示一次`() {
        val state = preview()
        assertTrue(state.needed)
        assertFalse(state.blocked)
        assertFalse(state.requiresSignIn)
        assertEquals("欢迎使用鸭嘴兽·孵化版", state.title)
        assertEquals("知道了", state.primaryLabel)
    }

    @Test
    fun `孵化版的提示要讲清怎么申请应用ID`() {
        val body = preview().body
        assertTrue(body.contains("应用 ID"), body)
        assertTrue(body.contains("设置 → 账号"), body)
    }

    @Test
    fun `正式版被拦住时正文说明为什么要登录`() {
        val body = formal(signedIn = false, accountsLoaded = true).body
        assertTrue(body.contains("正版账号"), body)
    }

    @Test
    fun `走完引导写成的时间戳不会是零`() {
        // 写成 0 的话下次启动又会弹一次，而且用户看不出为什么。
        assertEquals(1L, FirstRun.doneAt(0L))
        assertEquals(5L, FirstRun.doneAt(5L))
    }

    @Test
    fun `当前通道跟着版本号走`() {
        val expected = if (Brand.isPreview) Edition.PREVIEW else Edition.FORMAL
        assertEquals(expected, Edition.current())
    }

    @Test
    fun `认不出的通道 id 退回当前构建`() {
        assertEquals(Edition.current(), Edition.of("nonsense"))
        assertEquals(Edition.current(), Edition.of(""))
    }

    @Test
    fun `通道 id 认得出两种写法`() {
        assertEquals(Edition.FORMAL, Edition.of("formal"))
        assertEquals(Edition.PREVIEW, Edition.of(" PREVIEW "))
    }

    @Test
    fun `没填应用ID时正式版也提示还没有ID`() {
        val state = formal(clientId = "")
        assertFalse(state.clientIdReady)
        assertTrue(state.clientProblem.orEmpty().contains("Client ID"), state.clientProblem.orEmpty())
    }

    @Test
    fun `填了合法的应用ID就算准备好了`() {
        val state = formal(clientId = "00000000-0000-0000-0000-000000000000")
        assertTrue(state.clientIdReady)
        assertEquals(null, state.clientProblem)
    }
}
