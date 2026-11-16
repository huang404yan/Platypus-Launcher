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
     * 到底用哪个应用 ID。
     *
     * 做成函数而不是构造参数，是因为它可能来自设置文件、用户可以随时改：登录那一刻的
     * 值才算数；缓存住会让「刚填完 ID 就点登录」用上旧值。
     *
     * 取舍顺序在 [ClientIds] 里：安装包自带的优先（正式版由构建方注入，下载的人不用
     * 做任何事），没有才用用户在设置里填的（孵化版）。装配处一般直接写
     * `{ ClientIds.resolve(settingsRepository.load().microsoftClientId) }`。
     *
     * **登录与刷新必须用同一个 ID** —— 微软把 refresh_token 绑在应用上，换了 ID
     * 刷新一律 invalid_grant，用户会看到「登录凭据已被作废」，其实只是 ID 变了。
     */
    private val clientIdProvider: suspend () -> String = { ClientIds.builtin() },
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
        // entitledAt 写当下：能走到这一行，说明 [MicrosoftAuthClient.authenticate] 里那句
        // entitlements 校验刚刚放行了（没买过游戏的话它在那里就抛了，根本到不了这里）。
        // 正式版的首次验证就是看这一格，见 [io.github.huang404yan.platypus.core.edition.FirstRun]。
        val stored = StoredAccount(
            uuid = login.profile.id,
            name = login.profile.name,
            refreshToken = login.refreshToken.ifBlank { tokens.refreshToken },
            entitledAt = System.currentTimeMillis(),
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
        } else if (account.entitledAt <= 0L && account.lastLoginAt > 0L) {
            // 老记录补一次：这一版之前登录的账号没记 entitledAt，但当时同样过了所有权校验。
            // 不补的话 ownsGame 只能靠 lastLoginAt 兜着，界面会一直显示「没验证过」。
            repository?.save(account.copy(entitledAt = account.lastLoginAt))
        }
        return client.authenticate(tokens.accessToken, rotated.ifBlank { account.refreshToken }).session
    }
}
