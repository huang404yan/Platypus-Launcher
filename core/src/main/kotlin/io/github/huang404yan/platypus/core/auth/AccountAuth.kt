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
) : AccountAuth {

    /**
     * 走完设备码登录，返回存下来的账号。
     *
     * [onCode] 拿到短码时调一次 —— 界面必须立刻把它显示出来，用户要在浏览器里抄它。
     * 之后每轮轮询都调 [onTick]，带剩余秒数，界面可用来显示「还剩 N 秒」。
     */
    suspend fun login(
        clientId: String = MicrosoftEndpoints.DEFAULT_CLIENT_ID,
        onCode: (DeviceCodeGrant) -> Unit,
        onTick: (remainingSeconds: Int) -> Unit = {},
    ): StoredAccount {
        val grant = client.requestDeviceCode(clientId)
        onCode(grant)
        val tokens = client.awaitTokens(grant, clientId, onTick)
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
            client.refresh(account.refreshToken)
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
