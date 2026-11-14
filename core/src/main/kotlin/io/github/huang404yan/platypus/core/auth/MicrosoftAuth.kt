package io.github.huang404yan.platypus.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 微软账号登录用到的端点。
 *
 * 整条链路上有四个不同的服务，报错各说各话，所以分开写：
 *
 * 1. login.microsoftonline.com 发设备码、换 OAuth 令牌（纯账号层，还不知道你玩不玩 MC）
 * 2. user.auth.xboxlive.com 换 Xbox Live 令牌
 * 3. xsts.auth.xboxlive.com 换 XSTS 令牌（这一步才带上「要访问 Minecraft」的意图）
 * 4. api.minecraftservices.com 拿 MC 令牌、查有没有买过游戏、查玩家档案
 *
 * 中间任何一步失败后面全都拿不到，所以异常文案必须说清楚卡在哪一步 ——
 * 用户看到「登录失败」三个字没法自救。
 */
object MicrosoftEndpoints {

    /** 设备码：用户不需要在应用里输密码，只要在浏览器里敲一段短码。 */
    const val DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"

    /**
     * 换令牌 / 刷新令牌。
     *
     * 必须和 [DEVICE_CODE_URL] 同一个 v2 端点：v2 签发的 refresh_token 拿去
     * login.live.com/oauth20_token.srf（PCL 用的 v1 端点）会被拒，反过来也一样。
     */
    const val TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"

    const val XBL_URL = "https://user.auth.xboxlive.com/user/authenticate"
    const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
    const val MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
    const val MC_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore"
    const val MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"

    /** offline_access 是拿到 refresh_token 的前提，少了它下次启动就得重新登录。 */
    const val SCOPE = "XboxLive.signin offline_access"

    /**
     * 内置的 Client ID —— **开源 / 测试版里是空串**。
     *
     * 设备码流程必须先有一个在 Azure 上注册过的公共客户端，而任何随安装包发出去的 ID
     * 都等于让所有使用者共用一份别人的应用注册：被限流、被微软收紧、被认定滥用，
     * 都不是拿到安装包的人能处理的；仓库公开之后谁都能把那个 ID 抄走用。
     *
     * 所以从 0.8.0-preview.10 起，ID 一律由用户自己注册一个，填进「设置 → 账号」，
     * 只存在本地的 settings.json 里，不进仓库、不进安装包。申请步骤见 docs/41。
     *
     * 空串时登录在发请求之前就会报「还没有填 Client ID」，不会拿空值去撞微软。
     */
    const val DEFAULT_CLIENT_ID = ""

    /** 微软让你「去这个网页输入短码」时给的地址，拿不到响应里的值时当兜底。 */
    const val DEFAULT_VERIFICATION_URI = "https://www.microsoft.com/link"
}

/** 设备码。用户拿 [userCode] 去 [verificationUri] 输，应用这边拿着 [deviceCode] 轮询。 */
data class DeviceCodeGrant(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
    val message: String = "",
)

/** OAuth 令牌。[refreshToken] 可能为空 —— 刷新时微软不一定每次都发新的。 */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

/** XSTS 那一步除了令牌，还要 uhs（用户哈希），换 MC 令牌时两个都要带。 */
data class XboxToken(val token: String, val userHash: String)

/** MC 玩家档案。[id] 是带横线的标准 UUID，启动参数那边会自己去掉横线。 */
data class MinecraftProfile(val id: String, val name: String)

/** 登录链路上任何一步失败都抛这个，message 直接显示给用户。 */
class MicrosoftAuthException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 微软登录的协议细节：拼请求、读响应、把错误翻成人话。
 *
 * 这个文件里一个网络调用都没有 —— 全是纯函数，所以单测不需要联网。
 * 真正发请求的在 MicrosoftAuthClient。
 */
object MicrosoftAuth {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 把用户填进来的 Client ID 归一化：去首尾空白，顺手抹掉从网页复制时常带的引号。
     *
     * 用户是在 Azure 门户里抄一段 ID 再粘过来的，多一个空格或一对引号很常见，
     * 直接拿去发请求会得到 AADSTS700016（找不到这个应用），排查起来极费劲。
     */
    fun normalizeClientId(raw: String): String = raw.trim().trim('"').trim()

    /**
     * 校验用户填的 Client ID，合法返回 null，不合法返回中文原因。
     *
     * 只做形状检查（GUID：36 位、四个横线、其余是十六进制），**不联网验证** ——
     * 真假要等登录时由微软回话，这里能挡住的是「漏填」和「粘错了一段」。
     */
    fun clientIdProblem(raw: String): String? {
        val id = normalizeClientId(raw)
        if (id.isEmpty()) {
            return "还没有填 Client ID，正版登录用不了。申请办法见「设置 → 账号」页里的说明。"
        }
        val badShape = id.length != 36 || id.count { it == '-' } != 4 ||
            id.any { it != '-' && !it.isHexDigit() }
        if (badShape) {
            return "这不像一个应用 ID。Azure 给的是 36 位、带四个横线的形式，" +
                "例如 00000000-0000-0000-0000-000000000000。"
        }
        return null
    }

    /**
     * 发请求前的最后一道检查：不合法就抛，别让微软回一个看不懂的状态码。
     *
     * 返回归一化之后的 ID，调用方拿它去拼请求。
     */
    fun requireClientId(raw: String): String {
        val id = normalizeClientId(raw)
        val problem = clientIdProblem(id)
        if (problem != null) throw MicrosoftAuthException(problem)
        return id
    }

    private fun Char.isHexDigit(): Boolean =
        (this in '0'..'9') || (this in 'a'..'f') || (this in 'A'..'F')

    // ---- 请求体 ----

    fun deviceCodeForm(clientId: String): Map<String, String> = mapOf(
        "client_id" to clientId,
        "scope" to MicrosoftEndpoints.SCOPE,
    )

    /**
     * 轮询时的表单。grant_type 那个长串是 RFC 8628 规定的值，不能简写，
     * 写错会得到 unsupported_grant_type。
     */
    fun pollForm(clientId: String, deviceCode: String): Map<String, String> = mapOf(
        "client_id" to clientId,
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        "device_code" to deviceCode,
    )

    fun refreshForm(clientId: String, refreshToken: String): Map<String, String> = mapOf(
        "client_id" to clientId,
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "scope" to MicrosoftEndpoints.SCOPE,
    )

    /**
     * 换 Xbox Live 令牌。
     *
     * RpsTicket 的值要看令牌本身：以 d= 开头的直接塞，否则补一个 d= 前缀。
     * 漏了这一步 XBL 会回 400 且不说原因（PCL 在 ModLaunch.vb:1017-1031 也是这么判断的）。
     */
    fun xblBody(accessToken: String): String {
        val ticket = if (accessToken.startsWith("d=")) accessToken else "d=" + accessToken
        return buildJsonObject {
            putJsonObject("Properties") {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", ticket)
            }
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }.toString()
    }

    /** 换 XSTS。SandboxId 固定 RETAIL（正式服），RelyingParty 必须是 MC 的地址。 */
    fun xstsBody(xblToken: String): String = buildJsonObject {
        putJsonObject("Properties") {
            put("SandboxId", "RETAIL")
            put("UserTokens", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(xblToken)) })
        }
        put("RelyingParty", "rp://api.minecraftservices.com/")
        put("TokenType", "JWT")
    }.toString()

    fun mcLoginBody(xstsToken: String, userHash: String): String = buildJsonObject {
        put("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken)
    }.toString()

    // ---- 响应 ----

    fun parseDeviceCode(text: String): DeviceCodeGrant {
        val root = parseObject(text, "设备码")
        val deviceCode = root.str("device_code")
            ?: throw MicrosoftAuthException("微软没返回 device_code，设备码流程走不下去。")
        val userCode = root.str("user_code")
            ?: throw MicrosoftAuthException("微软没返回 user_code，界面上没有可抄的短码。")
        return DeviceCodeGrant(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = root.str("verification_uri") ?: MicrosoftEndpoints.DEFAULT_VERIFICATION_URI,
            verificationUriComplete = root.str("verification_uri_complete").orEmpty(),
            expiresInSeconds = root.int("expires_in") ?: 900,
            intervalSeconds = root.int("interval") ?: 5,
            message = root.str("message").orEmpty(),
        )
    }

    fun parseTokens(text: String): OAuthTokens {
        val root = parseObject(text, "令牌")
        val access = root.str("access_token")
            ?: throw MicrosoftAuthException("微软没返回 access_token。")
        return OAuthTokens(
            accessToken = access,
            refreshToken = root.str("refresh_token").orEmpty(),
            expiresInSeconds = root.long("expires_in") ?: 3600L,
        )
    }

    fun parseXbl(text: String): String {
        val root = parseObject(text, "Xbox Live 令牌")
        return root.str("Token") ?: throw MicrosoftAuthException("Xbox Live 认证没返回 Token。")
    }

    fun parseXsts(text: String): XboxToken {
        val root = parseObject(text, "XSTS 令牌")
        val token = root.str("Token") ?: throw MicrosoftAuthException("XSTS 认证没返回 Token。")
        val claims = root["DisplayClaims"]?.jsonObject
        val first = claims?.get("xui")?.jsonArray?.firstOrNull()?.jsonObject
        val uhs = first?.str("uhs")
            ?: throw MicrosoftAuthException("XSTS 认证没返回用户哈希（uhs），换不了 Minecraft 令牌。")
        return XboxToken(token = token, userHash = uhs)
    }

    /** MC 换令牌那一步只取 access_token；名字与 UUID 要等 profile 那一步才知道。 */
    fun parseMcLogin(text: String): String {
        val root = parseObject(text, "Minecraft 令牌")
        return root.str("access_token")
            ?: throw MicrosoftAuthException("Minecraft 登录没返回 access_token。")
    }

    fun parseProfile(text: String): MinecraftProfile {
        val root = parseObject(text, "玩家档案")
        val id = root.str("id") ?: throw MicrosoftAuthException("玩家档案里没有 id。")
        val name = root.str("name") ?: throw MicrosoftAuthException("玩家档案里没有名字。")
        return MinecraftProfile(id = normalizeUuid(id), name = name)
    }

    /**
     * 买了游戏没有？items 非空才算。
     *
     * 空列表有两种可能：没买过（或 Xbox Game Pass 到期），或者买在了另一个微软账号上。
     * 这两种情况用户能自己分辨，所以文案里都提一句。
     */
    fun hasGameEntitlement(text: String): Boolean = runCatching {
        parseObject(text, "持有记录")["items"]?.jsonArray?.isNotEmpty() == true
    }.getOrDefault(false)

    // ---- 错误 ----

    /**
     * OAuth 端点返回的错误。authorization_pending 不是错，是「用户还没输完码」，
     * 由 MicrosoftAuthClient 自己消化；这里只翻真正会终止流程的那几种。
     */
    fun describeOAuthError(code: String, description: String): String = when (code) {
        "authorization_declined" -> "你在微软页面上拒绝了授权，登录没继续。"
        "expired_token" -> "这段短码过期了，重新点一次登录拿新的。"
        "bad_verification_code" -> "短码对不上，微软没认出来。重新点一次登录。"
        "invalid_grant" ->
            "登录凭据已经被微软作废了（改过密码，或者在这个账号上点了退出登录），需要重新登录。"
        "unauthorized_client", "invalid_client" ->
            "这个 Client ID 不被微软接受。它要么填错了，要么没在 Azure 里开「允许公共客户端流」——" +
                "去「设置 → 账号」重新检查一遍，那页第一步写着申请步骤。"
        "slow_down" -> "请求太频繁了，微软要求放慢轮询。"
        else -> if (description.isNotBlank()) description else "微软返回了没见过的错误：$code"
    }

    /**
     * XSTS 的 XErr。这四个码占了实际会遇到的九成，各自的处置办法完全不同 ——
     * 尤其 2148916233（没注册 Xbox 账号）和 2148916238（未成年），用户根本想不到
     * 是这两个原因。
     */
    fun describeXstsError(xerr: Long): String = when (xerr) {
        2148916227L -> "这个微软账号被封禁了（Xbox 那边下的），换账号或者去申诉。"
        2148916233L -> "这个微软账号还没注册 Xbox 账号。先去 xbox.com 登录一次把档案建起来，再回来。"
        2148916235L -> "你所在的国家或地区不支持 Xbox Live，这个账号登录不了 Minecraft。"
        2148916238L -> "未成年账号需要家长同意。用家长账号去 account.microsoft.com/family 把它加进家庭组。"
        else -> "Xbox 认证失败（错误码 $xerr）。"
    }

    /** MC 换令牌那一步的 HTTP 状态码。 */
    fun describeMcLoginStatus(status: Int, body: String): String = when {
        body.contains("ACCOUNT_SUSPENDED", ignoreCase = true) ->
            "这个账号被 Minecraft 封禁了，换账号也没用，得去申诉。"
        status == 401 -> "Minecraft 不认这份 Xbox 凭据，重新登录一次试试。"
        status == 403 -> "Mojang 认为这次请求的来源 IP 有问题（挂代理时常见），换网络再试。"
        status == 429 -> "请求太频繁，等一分钟再点。"
        status == 503 -> "Mojang 的登录服务暂时不可用，过一会儿再试。"
        else -> "Minecraft 登录失败（HTTP $status）。"
    }

    /** UUID 统一成带横线的标准写法，方便界面显示；启动参数那边会自己去掉横线。 */
    fun normalizeUuid(raw: String): String {
        val hex = raw.replace("-", "").trim()
        if (hex.length != 32) return hex
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) +
            "-" + hex.substring(16, 20) + "-" + hex.substring(20)
    }

    /** 启动参数只要不带横线的 32 位。 */
    fun compactUuid(raw: String): String = raw.replace("-", "").trim()

    // ---- 内部 ----

    private fun parseObject(text: String, what: String): JsonObject = runCatching {
        json.parseToJsonElement(text).jsonObject
    }.getOrElse {
        throw MicrosoftAuthException(
            what + "的响应不是 JSON（前 120 个字：" + text.take(120).replace("\n", " ") + "）",
            it,
        )
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.int }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.long }.getOrNull()
}
