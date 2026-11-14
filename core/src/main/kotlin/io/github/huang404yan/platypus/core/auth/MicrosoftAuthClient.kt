package io.github.huang404yan.platypus.core.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * 微软登录的网络层。
 *
 * 只做三件事：发请求、把 HTTP 状态翻成 [MicrosoftAuthException]、把「用户还没输完码」
 * 这种非错误状态自己消化掉。协议细节全在 [MicrosoftAuth] 里（那边不联网，好测）。
 *
 * 整条链路要走四次 Xbox 往返，实测 1–3 秒；设备码轮询那一步可能要等用户几十秒，
 * 所以全程 suspend，并且每一轮都 ensureActive()，用户点「取消」能立刻停。
 */
class MicrosoftAuthClient(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) {

    /**
     * 拿设备码。这一步不需要任何凭据，调用完就该把 [DeviceCodeGrant.userCode] 显示给用户。
     */
    suspend fun requestDeviceCode(clientId: String = MicrosoftEndpoints.DEFAULT_CLIENT_ID): DeviceCodeGrant {
        // 空 ID 在本地就挡掉：发出去只会换回一个 AADSTS700016，用户看不懂。
        val resolved = MicrosoftAuth.requireClientId(clientId)
        val body = postForm(MicrosoftEndpoints.DEVICE_CODE_URL, MicrosoftAuth.deviceCodeForm(resolved))
        return MicrosoftAuth.parseDeviceCode(body)
    }

    /**
     * 轮询等待用户在浏览器里输完码。
     *
     * 轮询间隔取微软返回的 interval（通常 5 秒），并且**严格遵守** —— 打快了会拿到
     * slow_down，反而更慢。总时长按 expires_in 算，超了就抛。
     *
     * [onTick] 每轮调一次，界面可以用它显示「还剩 N 秒」，也可以什么都不做。
     */
    suspend fun awaitTokens(
        grant: DeviceCodeGrant,
        clientId: String = MicrosoftEndpoints.DEFAULT_CLIENT_ID,
        onTick: (remainingSeconds: Int) -> Unit = {},
    ): OAuthTokens {
        // 轮询用的 ID 必须与拿设备码那一步是同一个，否则微软会说码对不上。
        val resolved = MicrosoftAuth.requireClientId(clientId)
        val deadline = System.currentTimeMillis() + grant.expiresInSeconds * 1000L
        var interval = grant.intervalSeconds.coerceAtLeast(1).toLong()
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = ((deadline - System.currentTimeMillis()) / 1000L).toInt()
            if (remaining <= 0) {
                throw MicrosoftAuthException("等了 " + grant.expiresInSeconds + " 秒还没输完短码，重新点一次登录。")
            }
            onTick(remaining)
            delay(interval * 1000L)

            val outcome = try {
                val body = postForm(MicrosoftEndpoints.TOKEN_URL, MicrosoftAuth.pollForm(resolved, grant.deviceCode))
                PollOutcome.Success(MicrosoftAuth.parseTokens(body))
            } catch (e: HttpFailure) {
                val code = e.oauthError
                when (code) {
                    // 用户还没输完，继续等。
                    "authorization_pending" -> PollOutcome.Pending
                    "slow_down" -> {
                        interval += 5
                        PollOutcome.Pending
                    }
                    null -> throw MicrosoftAuthException("换令牌时微软回了 HTTP " + e.status + "。", e)
                    else -> throw MicrosoftAuthException(
                        MicrosoftAuth.describeOAuthError(code, e.oauthDescription),
                        e,
                    )
                }
            }
            when (outcome) {
                is PollOutcome.Success -> return outcome.tokens
                PollOutcome.Pending -> Unit
            }
        }
    }

    /**
     * 用 refresh_token 换一份新的 access_token。
     *
     * 刷新时微软可能不发新的 refresh_token（那说明旧的还能继续用），所以返回结果里
     * 空的 refresh_token 要由调用方保留旧值 —— 这里不替它做主。
     */
    suspend fun refresh(refreshToken: String, clientId: String = MicrosoftEndpoints.DEFAULT_CLIENT_ID): OAuthTokens {
        // 刷新必须用登录时那个 ID：微软把 refresh_token 与应用绑定，换了 ID 一律 invalid_grant。
        val resolved = MicrosoftAuth.requireClientId(clientId)
        val body = postForm(MicrosoftEndpoints.TOKEN_URL, MicrosoftAuth.refreshForm(resolved, refreshToken))
        return MicrosoftAuth.parseTokens(body)
    }

    /**
     * 从 OAuth 令牌一路走到 MC 档案。返回档案 + 一份能长期用的 refresh_token。
     *
     * 中间任何一步失败都抛，文案已经翻成人话。
     */
    suspend fun authenticate(accessToken: String, refreshToken: String): MinecraftLogin {
        val xbl = postJson(MicrosoftEndpoints.XBL_URL, MicrosoftAuth.xblBody(accessToken))
        val xblToken = MicrosoftAuth.parseXbl(xbl)

        val xsts = try {
            postJson(MicrosoftEndpoints.XSTS_URL, MicrosoftAuth.xstsBody(xblToken))
        } catch (e: HttpFailure) {
            throw MicrosoftAuthException(MicrosoftAuth.describeXstsError(e.xerrOrThrow()), e)
        }
        val xbox = MicrosoftAuth.parseXsts(xsts)

        val mcBody = postJson(
            MicrosoftEndpoints.MC_LOGIN_URL,
            MicrosoftAuth.mcLoginBody(xbox.token, xbox.userHash),
        )
        val mcToken = MicrosoftAuth.parseMcLogin(mcBody)

        val entitlements = get(MicrosoftEndpoints.MC_ENTITLEMENTS_URL, mcToken)
        if (!MicrosoftAuth.hasGameEntitlement(entitlements)) {
            throw MicrosoftAuthException(
                "这个微软账号没有 Minecraft 的持有记录。要么没买过（Xbox Game Pass 到期也算），" +
                    "要么游戏买在了另一个微软账号上。",
            )
        }

        val profileBody = get(MicrosoftEndpoints.MC_PROFILE_URL, mcToken)
        val profile = MicrosoftAuth.parseProfile(profileBody)
        val session = Session(
            username = profile.name,
            uuid = MicrosoftAuth.compactUuid(profile.id),
            accessToken = mcToken,
            userType = "msa",
        )
        return MinecraftLogin(profile = profile, session = session, refreshToken = refreshToken)
    }

    // ---- 内部 ----

    private sealed interface PollOutcome {
        data class Success(val tokens: OAuthTokens) : PollOutcome
        data object Pending : PollOutcome
    }

    private suspend fun postForm(url: String, fields: Map<String, String>): String = withContext(Dispatchers.IO) {
        val encoded = fields.entries.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
        request("POST", url, encoded.toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded")
    }

    private suspend fun postJson(url: String, json: String): String = withContext(Dispatchers.IO) {
        request("POST", url, json.toByteArray(Charsets.UTF_8), "application/json")
    }

    private suspend fun get(url: String, bearer: String): String = withContext(Dispatchers.IO) {
        request("GET", url, null, null, bearer)
    }

    /**
     * 统一的一次请求。非 2xx 一律抛 [HttpFailure]，由调用方决定怎么翻 —— 因为同样
     * 一个 400，OAuth 那边是「码不对」、XSTS 那边是「没注册 Xbox 账号」。
     */
    private fun request(
        method: String,
        url: String,
        body: ByteArray?,
        contentType: String?,
        bearer: String? = null,
    ): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.5")
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw HttpFailure(status, text)
            return text
        } catch (e: CancellationException) {
            connection.disconnect()
            throw e
        } catch (e: HttpFailure) {
            connection.disconnect()
            throw e
        } catch (e: IOException) {
            connection.disconnect()
            throw MicrosoftAuthException("连不上微软的服务器（" + e.message + "），检查网络或代理。", e)
        } finally {
            connection.disconnect()
        }
    }

    /** 非 2xx 的响应。[oauthError] 与 [xerr] 都是「这次失败该怎么解释」的线索。 */
    private class HttpFailure(val status: Int, val body: String) :
        RuntimeException("HTTP " + status + "：" + body.take(200)) {

        val oauthError: String? = runCatching {
            HttpFailure.LENIENT.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()

        val oauthDescription: String = runCatching {
            HttpFailure.LENIENT.parseToJsonElement(body)
                .jsonObject["error_description"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty()

        /** XSTS 失败时把错误码放在 XErr 字段里；没有就是 0（对应「未知」那条文案）。 */
        fun xerrOrThrow(): Long = runCatching {
            LENIENT.parseToJsonElement(body).jsonObject["XErr"]?.jsonPrimitive?.content?.toLongOrNull()
        }.getOrNull() ?: 0L

        private companion object {
            val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val DEFAULT_USER_AGENT =
            "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"
    }
}

/** 一整条链路走完的结果。refresh_token 要存起来，下次启动直接刷新，不用再输码。 */
data class MinecraftLogin(
    val profile: MinecraftProfile,
    val session: Session,
    val refreshToken: String,
) {
    /** 启动参数直接吃这个：MC 令牌 + 正版名字 + 去横线的 UUID，userType 固定 msa。 */
    fun toSession(): Session = session
}
