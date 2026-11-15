package io.github.huang404yan.platypus.core.auth

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 外置登录里不联网的那一半：地址归一、响应解析、报错翻译、启动参数拼装。
 *
 * 真正发 HTTP 的那几条（authenticate / refresh / metadata）不在单测里打真服务器 ——
 * 它们要的是「对方配合」，而单测要的是「我们算得对」。协议形状用真实的响应样本喂。
 */
class YggdrasilClientTest {

    private val root: File = Files.createTempDirectory("platypus-ygg-test").toFile()

    @AfterTest
    fun tearDown() {
        runCatching { root.deleteRecursively() }
    }

    @Test
    fun `各家教程里贴的地址都能归一成根地址`() {
        assertEquals(
            "https://littleskin.cn/api/yggdrasil",
            YggdrasilClient.normalizeApiRoot("https://littleskin.cn/api/yggdrasil"),
        )
        // 末尾斜杠、前后空格、粘贴带的引号，都是真实会遇到的
        assertEquals(
            "https://littleskin.cn/api/yggdrasil",
            YggdrasilClient.normalizeApiRoot("  \"https://littleskin.cn/api/yggdrasil/\"  "),
        )
        // 有人直接贴 authenticate 那种带 /authserver 的地址
        assertEquals(
            "https://littleskin.cn/api/yggdrasil",
            YggdrasilClient.normalizeApiRoot("https://littleskin.cn/api/yggdrasil/authserver"),
        )
        // 只剥一次：根路径本身就叫 authserver 的服务端不能被剥空
        assertEquals(
            "https://example.com/authserver",
            YggdrasilClient.normalizeApiRoot("https://example.com/authserver/authserver"),
        )
    }

    @Test
    fun `地址不能用时给出能照着改的提示`() {
        assertNotNull(YggdrasilClient.apiRootProblem(""))
        assertNotNull(YggdrasilClient.apiRootProblem("littleskin.cn/api/yggdrasil"))
        assertNotNull(YggdrasilClient.apiRootProblem("https://"))
        assertNull(YggdrasilClient.apiRootProblem("https://littleskin.cn/api/yggdrasil"))
        assertNull(YggdrasilClient.apiRootProblem("http://localhost:8080"))
    }

    @Test
    fun `解析登录响应里的角色与两枚令牌`() {
        val text = "{" +
            "\"accessToken\":\"token-abc\"," +
            "\"clientToken\":\"client-xyz\"," +
            "\"selectedProfile\":{\"id\":\"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0\",\"name\":\"Steve\"}," +
            "\"availableProfiles\":[{\"id\":\"b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0\",\"name\":\"Steve\"}]" +
            "}"
        val session = YggdrasilClient.parseSession(text)
        assertEquals("token-abc", session.accessToken)
        assertEquals("client-xyz", session.clientToken)
        assertEquals("Steve", session.profile.name)
        assertEquals("b0b0b0b0-b0b0-b0b0-b0b0-b0b0b0b0b0b0", session.profile.prettyId)
        assertEquals(1, session.availableProfiles.size)
    }

    @Test
    fun `只给了角色列表没给选中角色时取第一个`() {
        val text = "{\"accessToken\":\"t\",\"clientToken\":\"c\"," +
            "\"availableProfiles\":[{\"id\":\"11111111111111111111111111111111\",\"name\":\"Alex\"}]}"
        val session = YggdrasilClient.parseSession(text)
        assertEquals("Alex", session.profile.name)
    }

    @Test
    fun `没有 accessToken 时明确说失败`() {
        val failure = runCatching { YggdrasilClient.parseSession("{\"clientToken\":\"c\"}") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("accessToken"))
    }

    @Test
    fun `账号下没有游戏角色时说清楚该去做什么`() {
        val failure = runCatching {
            YggdrasilClient.parseSession("{\"accessToken\":\"t\",\"availableProfiles\":[]}")
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("角色"))
    }

    @Test
    fun `解析认证服务器元数据并保留原文`() {
        val raw = "{\"meta\":{\"serverName\":\"LittleSkin\",\"implementationName\":\"Yggdrasil API\"}," +
            "\"skinDomains\":[\"littleskin.cn\"],\"signaturePublickey\":\"-----BEGIN PUBLIC KEY-----\"}"
        val meta = YggdrasilClient.parseMetadata("https://littleskin.cn/api/yggdrasil", raw)
        assertEquals("LittleSkin", meta.serverName)
        assertEquals("LittleSkin", meta.displayName)
        assertEquals(listOf("littleskin.cn"), meta.skinDomains)
        assertEquals("-----BEGIN PUBLIC KEY-----", meta.signaturePublicKey)
        // 原文必须一字不改地带走：prefetched 要靠它，重新序列化签名就不对了
        assertEquals(raw, meta.raw)
        assertTrue(meta.prefetched.isNotBlank())
    }

    @Test
    fun `服务端自报的 apiRoot 优先于用户填的`() {
        val meta = YggdrasilClient.parseMetadata(
            "https://mirror.example.com/api/yggdrasil",
            "{\"apiRoot\":\"https://real.example.com/api/yggdrasil\"}",
        )
        assertEquals("https://real.example.com/api/yggdrasil", meta.apiRoot)
    }

    @Test
    fun `元数据不是 JSON 时不抛空表而是说清收到了什么`() {
        val failure = runCatching { YggdrasilClient.parseMetadata("https://a.com", "<html>404</html>") }
            .exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("不是 JSON"))
    }

    @Test
    fun `登录失败按状态码翻成人话并附上服务端原话`() {
        val forbidden = YggdrasilClient.describeLoginFailure(
            403,
            "{\"error\":\"ForbiddenOperationException\",\"errorMessage\":\"Invalid credentials. Invalid username or password.\"}",
        )
        assertTrue(forbidden.contains("用户名或密码不对"), forbidden)
        assertTrue(forbidden.contains("Invalid credentials"), forbidden)
        val notFound = YggdrasilClient.describeLoginFailure(404, "")
        assertTrue(notFound.contains("/api/yggdrasil"), notFound)
        val limited = YggdrasilClient.describeLoginFailure(429, "")
        assertTrue(limited.contains("太频繁"), limited)
    }

    @Test
    fun `续期被拒时让用户回去重输密码`() {
        val text = YggdrasilClient.describeStatus(403, "", "续期登录状态")
        assertTrue(text.contains("重新"), text)
    }

    @Test
    fun `外置会话的 userType 是 mojang 而不是 msa`() {
        val session = YggdrasilSession(
            accessToken = "t",
            clientToken = "c",
            profile = YggdrasilProfile("B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0", "Steve"),
        )
        val launch = session.toSession()
        assertEquals("mojang", launch.userType)
        assertEquals("Steve", launch.username)
        // 启动参数只要 32 位不带横线
        assertEquals("B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0".lowercase(), launch.uuid.lowercase())
    }

    @Test
    fun `authlib-injector 的启动参数三件套齐全`() {
        val jar = File(root, "authlib-injector.jar")
        val meta = YggdrasilClient.parseMetadata("https://a.com/api/yggdrasil", "{\"meta\":{\"serverName\":\"X\"}}")
        val args = AuthlibInjector.jvmArgs(jar, "https://a.com/api/yggdrasil/", meta)
        assertEquals(3, args.size, args.toString())
        assertTrue(args[0].startsWith("-javaagent:"), args[0])
        assertTrue(args[0].endsWith("=https://a.com/api/yggdrasil"), args[0])
        assertFalse(args[0].endsWith("/"), args[0])
        assertTrue(args[1].startsWith("-Dauthlibinjector.yggdrasil.prefetched="), args[1])
        assertEquals("-Dauthlibinjector.side=client", args[2])
    }

    @Test
    fun `路径里有空格时 javaagent 那一项加引号`() {
        val jar = File(File(root, "my games"), "authlib-injector.jar")
        val args = AuthlibInjector.jvmArgs(jar, "https://a.com", null)
        assertTrue(args[0].contains("\""), args[0])
        // 没有元数据就不该出现 prefetched 那一项
        assertEquals(2, args.size, args.toString())
    }

    @Test
    fun `解析 authlib-injector 的版本信息`() {
        val text = "{\"build_number\":56,\"version\":\"1.2.8\"," +
            "\"download_url\":\"https://authlib-injector.yushi.moe/artifact/56/authlib-injector-1.2.8.jar\"," +
            "\"checksums\":{\"sha256\":\"9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10\"}}"
        val build = AuthlibInjector.parseBuild(text)
        assertEquals("1.2.8", build.version)
        assertEquals(56L, build.buildNumber)
        assertTrue(build.downloadUrl.endsWith("authlib-injector-1.2.8.jar"))
        assertEquals("9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10", build.sha256)
        // jar 落在一个固定的位置，换版本不改路径
        assertEquals(
            File(File(File(root, "platypus"), "authlib-injector"), "authlib-injector.jar").absolutePath,
            AuthlibInjector.jarOf(root).absolutePath,
        )
    }

    @Test
    fun `校验失败或文件不存在时不算装好`() {
        val jar = File(root, "authlib-injector.jar")
        assertFalse(AuthlibInjector.isInstalled(jar))
        jar.writeText("not a jar", Charsets.UTF_8)
        assertTrue(AuthlibInjector.isInstalled(jar))
        // 空期望值只判非空文件
        assertTrue(AuthlibInjector.verify(jar, ""))
        // 有期望值就认真算
        assertFalse(AuthlibInjector.verify(jar, "00".repeat(32)))
    }

    @Test
    fun `外置账号的 key 带上服务器地址，两个服同 UUID 不会互相顶掉`() {
        val a = StoredAccount(
            uuid = "b0b0b0b0-b0b0-b0b0-b0b0-b0b0b0b0b0b0",
            name = "Steve",
            refreshToken = "t",
            kind = StoredAccount.KIND_YGGDRASIL,
            serverUrl = "https://a.com/api/yggdrasil",
        )
        val b = a.copy(serverUrl = "https://b.com/api/yggdrasil")
        assertTrue(a.key != b.key, a.key + " vs " + b.key)
        // 末尾斜杠不该算成两个服
        assertEquals(a.key, a.copy(serverUrl = "https://a.com/api/yggdrasil/").key)
    }

    @Test
    fun `老文件里没有 kind 字段的记录仍然当成微软账号`() {
        val account = StoredAccount(uuid = "b0b0b0b0-b0b0-b0b0-b0b0-b0b0b0b0b0b0", name = "S", refreshToken = "t")
        assertFalse(account.isYggdrasil)
        assertEquals("microsoft", account.kind)
        // 微软账号的 key 保持原样（32 位小写，不带前缀）
        assertEquals("b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0", account.key)
    }

    @Test
    fun `按 key 删除只删那一条`() {
        val file = File(root, "accounts.json")
        val repository = JsonAccountRepository(file)
        kotlinx.coroutines.runBlocking {
            repository.save(
                StoredAccount(
                    uuid = "11111111-1111-1111-1111-111111111111",
                    name = "MS",
                    refreshToken = "t1",
                ),
            )
            repository.save(
                StoredAccount(
                    uuid = "11111111-1111-1111-1111-111111111111",
                    name = "YggA",
                    refreshToken = "t2",
                    kind = StoredAccount.KIND_YGGDRASIL,
                    serverUrl = "https://a.com/api/yggdrasil",
                ),
            )
            assertEquals(2, repository.list().size)
            // 按 UUID 删只会删掉微软那条，外置服上那个同名账号不是同一个人
            repository.remove("11111111-1111-1111-1111-111111111111")
            val left = repository.list()
            assertEquals(1, left.size)
            assertEquals("YggA", left[0].name)
            repository.removeKey(left[0].key)
            assertEquals(0, repository.list().size)
        }
    }

    @Test
    fun `往账号表里存两种登录方式后读回来还是两种`() {
        val file = File(root, "roundtrip.json")
        val repository = JsonAccountRepository(file)
        kotlinx.coroutines.runBlocking {
            repository.save(
                StoredAccount(
                    uuid = "22222222-2222-2222-2222-222222222222",
                    name = "MSUser",
                    refreshToken = "ms-token",
                ),
            )
            repository.save(
                StoredAccount(
                    uuid = "33333333-3333-3333-3333-333333333333",
                    name = "YggUser",
                    refreshToken = "yg-token",
                    kind = StoredAccount.KIND_YGGDRASIL,
                    serverUrl = "https://b.com/api/yggdrasil",
                    clientToken = "client-token",
                ),
            )
        }
        // 换一个实例读：证明真的落盘了，不是内存里的
        val reread = kotlinx.coroutines.runBlocking { JsonAccountRepository(file).list() }
        assertEquals(2, reread.size)
        val ygg = reread.first { it.isYggdrasil }
        assertEquals("https://b.com/api/yggdrasil", ygg.serverUrl)
        assertEquals("client-token", ygg.clientToken)
        assertFalse(reread.first { !it.isYggdrasil }.isYggdrasil)
    }

    // ── 2026-11-15 打真站之后补的一条 ────────────────────────────────────

    @Test
    fun `密码错时把服务端的原话带出来而不是空白`() {
        // 实测 LittleSkin 对「用户名或密码不对」回的是 403（不是 401），
        // 而 403 在这份代码里还有另一个含义：令牌失效。所以这两条路必须分开 ——
        // 曾经 request 对所有请求都先抛 TokenRejected，导致密码错的用户
        // 只看到一句空白的「外置登录失败：」，既不知道是密码错也不知道改什么。
        val text = YggdrasilClient.describeLoginFailure(
            403,
            "{\"error\":\"ForbiddenOperationException\",\"errorMessage\":\"用户名或密码错误。Incorrect username or password.\"}",
        )
        assertTrue(text.contains("用户名或密码不对"), text)
        assertTrue(text.contains("Incorrect username or password"), text)
    }
}