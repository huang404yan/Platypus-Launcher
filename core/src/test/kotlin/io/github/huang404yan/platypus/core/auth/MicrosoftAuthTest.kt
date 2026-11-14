package io.github.huang404yan.platypus.core.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 微软登录的协议细节。
 *
 * 全都不联网 —— 拼请求、读响应、翻错误都是纯函数，这样才敢在每次提交前跑。
 * 真正跟微软打交道的部分只能靠手动点一遍，见 docs/40「怎么验」。
 */
class MicrosoftAuthTest {

    private val deviceCodeJson = """
        {"user_code":"49VPEH8F","device_code":"SBgABIQEAAAA","verification_uri":"https://microsoft.com/link",
         "expires_in":900,"interval":5,"message":"去网页输码"}
    """.trimIndent()

    private val tokenJson = """
        {"token_type":"Bearer","scope":"XboxLive.signin","expires_in":3600,
         "access_token":"EwBwA8l6","refresh_token":"M.C542_BAY.0.U.-Ct"}
    """.trimIndent()

    private val xblJson = """
        {"IssueInstant":"2026-11-14T09:00:00Z","NotAfter":"2026-11-28T09:00:00Z","Token":"eyJhbGci",
         "DisplayClaims":{"xui":[{"gtg":"x","xid":"1","uhs":"1234567890"}]}}
    """.trimIndent()

    private val xstsJson = """
        {"Token":"eyJhbGciXSTS","DisplayClaims":{"xui":[{"uhs":"1234567890"}]}}
    """.trimIndent()

    private val profileJson = """
        {"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch","skins":[]}
    """.trimIndent()

    @Test
    fun 设备码能解析出短码与轮询地址() {
        val grant = MicrosoftAuth.parseDeviceCode(deviceCodeJson)
        assertEquals("49VPEH8F", grant.userCode)
        assertEquals("SBgABIQEAAAA", grant.deviceCode)
        assertEquals("https://microsoft.com/link", grant.verificationUri)
        assertEquals(900, grant.expiresInSeconds)
        assertEquals(5, grant.intervalSeconds)
    }

    @Test
    fun 设备码缺了短码就报错而不是给个空串() {
        val e = assertFailsWith<MicrosoftAuthException> {
            MicrosoftAuth.parseDeviceCode("""{"device_code":"x"}""")
        }
        assertTrue(e.message!!.contains("user_code"), e.message)
    }

    @Test
    fun 令牌能解析出刷新令牌与有效期() {
        val tokens = MicrosoftAuth.parseTokens(tokenJson)
        assertEquals("EwBwA8l6", tokens.accessToken)
        assertEquals("M.C542_BAY.0.U.-Ct", tokens.refreshToken)
        assertEquals(3600L, tokens.expiresInSeconds)
    }

    @Test
    fun 刷新时不发新的刷新令牌就保持空串让调用方保留旧值() {
        val tokens = MicrosoftAuth.parseTokens("""{"access_token":"only-new","expires_in":3600}""")
        assertEquals("", tokens.refreshToken)
    }

    @Test
    fun 轮询用的授权类型是标准里那个长串() {
        val form = MicrosoftAuth.pollForm("cid", "dc")
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", form["grant_type"])
        assertEquals("dc", form["device_code"])
    }

    @Test
    fun 刷新令牌的表单必须带离线授权() {
        val form = MicrosoftAuth.refreshForm("cid", "rt")
        assertEquals("refresh_token", form["grant_type"])
        assertEquals(MicrosoftEndpoints.SCOPE, form["scope"])
        assertTrue(MicrosoftEndpoints.SCOPE.contains("offline_access"))
    }

    @Test
    fun 换Xbox令牌时没带d前缀要补上() {
        val body = MicrosoftAuth.xblBody("EwBwA8l6")
        assertTrue(body.contains("\"RpsTicket\":\"d=EwBwA8l6\""), body)
    }

    @Test
    fun 换Xbox令牌时已经带d前缀就不重复补() {
        val body = MicrosoftAuth.xblBody("d=EwBwA8l6")
        assertTrue(body.contains("\"RpsTicket\":\"d=EwBwA8l6\""), body)
        assertFalse(body.contains("d=d="), body)
    }

    @Test
    fun 换XSTS时把沙盒写死成正式服() {
        val body = MicrosoftAuth.xstsBody("tkn")
        assertTrue(body.contains("\"SandboxId\":\"RETAIL\""), body)
        assertTrue(body.contains("rp://api.minecraftservices.com/"), body)
    }

    @Test
    fun 换MC令牌时把用户哈希与令牌拼成XBL3格式() {
        val body = MicrosoftAuth.mcLoginBody("xsts-token", "uhs-1")
        assertTrue(body.contains("XBL3.0 x=uhs-1;xsts-token"), body)
    }

    @Test
    fun XSTS能解析出令牌与用户哈希() {
        val xbox = MicrosoftAuth.parseXsts(xstsJson)
        assertEquals("eyJhbGciXSTS", xbox.token)
        assertEquals("1234567890", xbox.userHash)
    }

    @Test
    fun XSTS缺了用户哈希就报错() {
        val e = assertFailsWith<MicrosoftAuthException> {
            MicrosoftAuth.parseXsts("""{"Token":"t"}""")
        }
        assertTrue(e.message!!.contains("uhs"), e.message)
    }

    @Test
    fun XboxLive令牌单独解析() {
        assertEquals("eyJhbGci", MicrosoftAuth.parseXbl(xblJson))
    }

    @Test
    fun 玩家档案的UUID会补上横线() {
        val profile = MicrosoftAuth.parseProfile(profileJson)
        assertEquals("Notch", profile.name)
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", profile.id)
    }

    @Test
    fun 启动参数要的是去掉横线的UUID() {
        assertEquals(
            "069a79f444e94726a5befca90e38aaf5",
            MicrosoftAuth.compactUuid("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
        )
        assertEquals("069a79f444e94726a5befca90e38aaf5", MicrosoftAuth.compactUuid("069a79f444e94726a5befca90e38aaf5"))
    }

    @Test
    fun 持有记录为空说明没买过() {
        assertFalse(MicrosoftAuth.hasGameEntitlement("""{"items":[],"signature":""}"""))
        assertTrue(MicrosoftAuth.hasGameEntitlement("""{"items":[{"name":"product_minecraft"}]}"""))
        assertFalse(MicrosoftAuth.hasGameEntitlement("不是 JSON"))
    }

    @Test
    fun 响应不是JSON时把原文头一段带出来() {
        val e = assertFailsWith<MicrosoftAuthException> { MicrosoftAuth.parseTokens("<html>502</html>") }
        assertTrue(e.message!!.contains("502"), e.message)
    }

    @Test
    fun 四种XSTS错误各给不同的处置办法() {
        assertTrue(MicrosoftAuth.describeXstsError(2148916227L).contains("封禁"))
        assertTrue(MicrosoftAuth.describeXstsError(2148916233L).contains("Xbox 账号"))
        assertTrue(MicrosoftAuth.describeXstsError(2148916235L).contains("国家"))
        assertTrue(MicrosoftAuth.describeXstsError(2148916238L).contains("家长"))
    }

    @Test
    fun 没见过的XSTS错误码也把码报出来() {
        val text = MicrosoftAuth.describeXstsError(123456L)
        assertTrue(text.contains("123456"), text)
    }

    @Test
    fun 拒绝授权与短码过期各有说法() {
        assertTrue(MicrosoftAuth.describeOAuthError("authorization_declined", "").contains("拒绝"))
        assertTrue(MicrosoftAuth.describeOAuthError("expired_token", "").contains("过期"))
    }

    @Test
    fun ClientID不被接受时提示去设置里换() {
        val text = MicrosoftAuth.describeOAuthError("unauthorized_client", "")
        assertTrue(text.contains("Client ID"), text)
        // 文案要指到具体位置（设置 → 账号），只说「去设置里」等于没说。
        assertTrue(text.contains("设置 → 账号") || text.contains("设置页"), text)
    }

    @Test
    fun 没填ClientID时说的是去申请而不是微软的错() {
        val problem = MicrosoftAuth.clientIdProblem("")
        assertNotNull(problem)
        assertTrue(problem.contains("Client ID"), problem)
        assertTrue(problem.contains("申请") || problem.contains("说明"), problem)
    }

    @Test
    fun ClientID的空白与引号会被抹掉() {
        assertEquals(
            "12345678-1234-1234-1234-123456789abc",
            MicrosoftAuth.normalizeClientId("  \"12345678-1234-1234-1234-123456789abc\"  "),
        )
    }

    @Test
    fun 粘错的ClientID会被挡在发请求之前() {
        // 少一位、少一个横线、混进非十六进制字符，三种都算形状不对。
        val bad = listOf(
            "12345678-1234-1234-1234-123456789ab",
            "123456781234-1234-1234-123456789abc",
            "12345678-1234-1234-1234-123456789zzz",
            "hello world",
        )
        bad.forEach { id ->
            val problem = MicrosoftAuth.clientIdProblem(id)
            assertNotNull(problem, id)
            assertTrue(problem.contains("36") || problem.contains("不像"), problem)
        }
    }

    @Test
    fun 形状对的ClientID能通过并要求归一化后再用() {
        assertEquals(null, MicrosoftAuth.clientIdProblem("04b07795-8ddb-461a-bbee-02f9e1bf7b46"))
        assertEquals(
            "04b07795-8ddb-461a-bbee-02f9e1bf7b46",
            MicrosoftAuth.requireClientId(" 04b07795-8ddb-461a-bbee-02f9e1bf7b46 "),
        )
    }

    @Test
    fun 空ClientID在require这一步就抛而不是返回空串() {
        val error = assertFailsWith<MicrosoftAuthException> { MicrosoftAuth.requireClientId("") }
        assertTrue(error.message.orEmpty().contains("Client ID"), error.message.orEmpty())
    }

    @Test
    fun 没见过的OAuth错误优先用微软给的原话() {
        val text = MicrosoftAuth.describeOAuthError("weird_error", "微软说：服务在维护")
        assertTrue(text.contains("服务在维护"), text)
    }

    @Test
    fun 账号被封时优先按封禁解释而不是按状态码() {
        val text = MicrosoftAuth.describeMcLoginStatus(401, """{"error":"ACCOUNT_SUSPENDED"}""")
        assertTrue(text.contains("封禁"), text)
    }

    @Test
    fun 限流与IP异常各有说法() {
        assertTrue(MicrosoftAuth.describeMcLoginStatus(429, "").contains("频繁"))
        assertTrue(MicrosoftAuth.describeMcLoginStatus(403, "").contains("IP"))
        assertTrue(MicrosoftAuth.describeMcLoginStatus(503, "").contains("Mojang"))
    }
}
