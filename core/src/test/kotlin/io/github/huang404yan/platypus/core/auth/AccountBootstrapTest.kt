package io.github.huang404yan.platypus.core.auth

import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AccountBootstrap] 的四件事：路径跟着游戏目录走、写设置不冲掉别人的字段、
 * 认得出当前账号、没有账号时给的是离线会话。
 *
 * 微软与外置两条真链路要联网，不在这里测；离线那条是纯函数，正好测全。
 */
class AccountBootstrapTest {

    private val roots = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        roots.forEach { root -> root.deleteRecursively() }
        roots.clear()
    }

    private fun tempRoot(): File {
        val root = Files.createTempDirectory("platypus-account").toFile()
        roots += root
        return root
    }

    @Test
    fun `账号文件与设置文件都在游戏目录下的 platypus 里`() {
        val root = tempRoot()
        assertEquals(File(root, "platypus/accounts.json"), AccountBootstrap.indexFile(root))
        assertEquals(File(root, "platypus/settings.json"), AccountBootstrap.settingsFile(root))
    }

    @Test
    fun `切换账号只改 activeAccountUuid 不冲掉别的设置`() = runBlocking {
        val root = tempRoot()
        val store = JsonSettingsRepository(AccountBootstrap.settingsFile(root))
        store.save(
            LauncherSettings(
                offlineUsername = "小明",
                defaultMemoryMb = 4096,
                theme = "light",
            ),
        )
        AccountBootstrap.markActive(root, " abcdef0123456789abcdef0123456789 ")
        val stored = store.load()
        assertEquals("abcdef0123456789abcdef0123456789", stored.activeAccountUuid)
        assertEquals("小明", stored.offlineUsername)
        assertEquals(4096, stored.defaultMemoryMb)
        assertEquals("light", stored.theme)
    }

    @Test
    fun `保存应用 ID 时不冲掉离线用户名`() = runBlocking {
        val root = tempRoot()
        val store = JsonSettingsRepository(AccountBootstrap.settingsFile(root))
        store.save(LauncherSettings(offlineUsername = "小明", defaultMemoryMb = 2048))
        AccountBootstrap.saveClientId(root, "  00000000-0000-0000-0000-000000000000  ")
        val stored = store.load()
        assertEquals("00000000-0000-0000-0000-000000000000", stored.microsoftClientId)
        assertEquals("小明", stored.offlineUsername)
        assertEquals(2048, stored.defaultMemoryMb)
    }

    @Test
    fun `包着引号的应用 ID 会被清干净`() = runBlocking {
        val root = tempRoot()
        AccountBootstrap.saveClientId(root, " 00000000-0000-0000-0000-000000000000 ")
        assertEquals(
            "00000000-0000-0000-0000-000000000000",
            AccountBootstrap.settings(root)?.microsoftClientId,
        )
    }

    @Test
    fun `没登录过时当前账号是空的`() = runBlocking {
        val root = tempRoot()
        assertNull(AccountBootstrap.active(root))
        assertEquals(emptyList(), AccountBootstrap.list(root))
    }

    @Test
    fun `设置里指向一个不存在的账号时算没登录`() = runBlocking {
        val root = tempRoot()
        AccountBootstrap.markActive(root, "deadbeefdeadbeefdeadbeefdeadbeef")
        // 账号文件里没有这一条：不能凭设置里那一串就当它有账号，否则启动时会拿空令牌去连。
        assertNull(AccountBootstrap.active(root))
    }

    @Test
    fun `设置里的 UUID 大小写不影响匹配账号`() = runBlocking {
        val root = tempRoot()
        val repository = AccountBootstrap.repository(root)
        repository.save(
            StoredAccount(
                uuid = "ABCDEF01-2345-6789-ABCD-EF0123456789",
                name = "Steve",
                refreshToken = "rt",
            ),
        )
        AccountBootstrap.markActive(root, "ABCDEF01-2345-6789-ABCD-EF0123456789")
        val found = AccountBootstrap.active(root)
        assertEquals("Steve", found?.name)
    }

    @Test
    fun `设置里存的是带横线的 UUID 也认得出账号`() = runBlocking {
        val root = tempRoot()
        AccountBootstrap.repository(root).save(
            StoredAccount(uuid = "abcdef0123456789abcdef0123456789", name = "Steve", refreshToken = "rt"),
        )
        // 老版本界面写进去的是微软那种带横线的形状；按 key 直接比会一条都对不上。
        val store = JsonSettingsRepository(AccountBootstrap.settingsFile(root))
        store.save(store.load().copy(activeAccountUuid = "abcdef01-2345-6789-abcd-ef0123456789"))
        assertEquals("Steve", AccountBootstrap.active(root)?.name)
    }

    @Test
    fun `切换账号后写进设置的是账号的 key`() = runBlocking {
        val root = tempRoot()
        AccountBootstrap.repository(root).save(
            StoredAccount(uuid = "ABCDEF01-2345-6789-ABCD-EF0123456789", name = "Steve", refreshToken = "rt"),
        )
        AccountBootstrap.markActive(root, "ABCDEF01-2345-6789-ABCD-EF0123456789")
        val stored = JsonSettingsRepository(AccountBootstrap.settingsFile(root)).load()
        // 只存一种写法，省得下次比较时又碰到「大写带横线 vs 小写不带」这种对不上的情况。
        assertEquals("abcdef0123456789abcdef0123456789", stored.activeAccountUuid)
    }

    @Test
    fun `没有账号时换出来的就是离线会话`() = runBlocking {
        val root = tempRoot()
        val session = AccountBootstrap.resolve(root, " 阿强 ")
        assertTrue(session.isOffline)
        assertNull(session.account)
        assertEquals(emptyList(), session.jvmArgs)
        assertEquals("阿强", session.session.username)
        assertEquals("离线（阿强）", session.label)
        assertFalse(session.label.contains("正版"))
    }

    @Test
    fun `离线用户名是空白时用默认值`() = runBlocking {
        val root = tempRoot()
        val session = AccountBootstrap.resolve(root, "   ")
        assertEquals(LauncherSettings.DEFAULT_OFFLINE_USERNAME, session.session.username)
    }

    @Test
    fun `读不出来的设置文件不会让这一页崩掉`() = runBlocking {
        val root = tempRoot()
        val file = AccountBootstrap.settingsFile(root)
        file.parentFile?.mkdirs()
        file.writeText("{ 这不是 json ", Charsets.UTF_8)
        // 读失败一律退回默认值：账号页不该因为一个手改坏的文件整页白屏。
        assertNull(AccountBootstrap.settings(root)?.activeAccountUuid?.takeIf { it.isNotBlank() })
        assertTrue(AccountBootstrap.resolve(root, "阿强").isOffline)
    }
}