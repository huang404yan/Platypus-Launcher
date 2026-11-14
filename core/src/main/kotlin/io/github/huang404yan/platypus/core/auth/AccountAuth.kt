package io.github.huang404yan.platypus.core.auth

import kotlinx.coroutines.CancellationException

/**
 * 「给我一份能直接启动游戏的会话」。
 *
 * 启动游戏时每次都现取：MC 令牌只活两小时，缓存下来十个里有九个是过期的，
 * 而刷新一次只要一两秒。所以这里不缓存 access_token，只认 refresh_token。
 */
interface AccountAuth {
    /**
     * 用存下来的 refresh_token 换一份新会话。
     *
     * 失败一律抛 [MicrosoftAuthException]，文案已经翻成人话；界面把 message 原样显示即可。
     */
    suspend fun sessionFor(account: StoredAccount): Session
}

/**
 * 微软账号的登录与会话。
 *
 * 两件事都在这儿：设备码登录（把结果存进 [AccountRepository]）、以及启动前换会话。
 * 分开写的理由是一次登录只有一次，而换会话每次启动都要 —— 混在一起会让登录那段
 * 逻辑被「刷新失败怎么办」污染。
 */
class MicrosoftAccountAuth(
    private val client: MicrosoftAuthClient = MicrosoftAuthClient(),
    /** 允许为 null：没有仓库时照样能登录，只是下次得重来（假后端 / 单测用）。 */
    private val repository: AccountRepository? = null,
    /**
     * 去哪儿要用户填的 Client ID。
     *
     * 做成函数而不是构造参数，是因为它存在设置文件里、用户可以随时改：登录那一刻的
     * 值才算数；缓存住会让「刚填完 ID 就点登录」用上旧值。挂了设置仓库的装配会
     * 现读 settings.json，没挂的（假后端 / 单测）用 [MicrosoftEndpoints.DEFAULT_CLIENT_ID]。
     *
     * **登录与刷新必须用同一个 ID** —— 微软把 refresh_token 绑在应用上，换了 ID
     * 刷新一律 invalid_grant，用户会看到「登录凭据已被作废」，其实只是 ID 变了。
     */
    private val clientIdProvider: suspend () -> String = { MicrosoftEndpoints.DEFAULT_CLIENT_ID },
) : AccountAuth {

    /**
     * 走完设备码登录，返回存下来的账号。
     *
     * [onCode] 拿到短码时调一次 —— 界面必须立刻把它显示出来，用户要在浏览器里抄它。
     * 之后每轮轮询都调 [onTick]，带剩余秒数，界面可用来显示「还剩 N 秒」。
     */
    suspend fun login(
        /** 留空表示用 [clientIdProvider] 现读的那个。 */
        clientId: String? = null,
        onCode: (DeviceCodeGrant) -> Unit,
        onTick: (remainingSeconds: Int) -> Unit = {},
    ): StoredAccount {
        val resolved = clientId ?: clientIdProvider()
        val grant = client.requestDeviceCode(resolved)
        onCode(grant)
        val tokens = client.awaitTokens(grant, resolved, onTick)
        val login = client.authenticate(tokens.accessToken, tokens.refreshToken)
        val stored = StoredAccount(
            uuid = login.profile.id,
            name = login.profile.name,
            refreshToken = login.refreshToken.ifBlank { tokens.refreshToken },
        )
        repository?.save(stored)
        return stored
    }

    override suspend fun sessionFor(account: StoredAccount): Session {
        val tokens = try {
            client.refresh(account.refreshToken, clientIdProvider())
        } catch (e: CancellationException) {
            throw e
        } catch (e: MicrosoftAuthException) {
            throw e
        }

        // 微软有时不发新的 refresh_token（旧的还能用）。发的时候就换掉 ——
        // 不换的话，旧凭据在微软那边轮换过一次之后就作废了，下次启动会突然要重登。
        val rotated = tokens.refreshToken
        if (rotated.isNotBlank() && rotated != account.refreshToken) {
            repository?.save(account.copy(refreshToken = rotated, lastLoginAt = System.currentTimeMillis()))
        }
        return client.authenticate(tokens.accessToken, rotated.ifBlank { account.refreshToken }).session
    }
}
