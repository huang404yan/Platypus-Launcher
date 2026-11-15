package io.github.huang404yan.platypus.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 外置登录的 [AccountAuth] 实现。
 *
 * 和微软那条线的差别不只是协议：外置登录的凭据**只有在 game 进程挂上 authlib-injector
 * 之后才有意义**。所以除了 [sessionFor]，本类还负责 [launchArgs] —— 把那个 javaagent
 * 和认证服务器地址拼成 JVM 参数。少了它，拿到的令牌游戏根本不去用。
 *
 * 存盘复用的是同一张 accounts.json：微软账号 kind = microsoft，这里 kind = yggdrasil，
 * 多带 serverUrl 与 clientToken。两类账号混在一张表里，界面上也是一起列的。
 */
class YggdrasilAccountAuth(
    private val gameRoot: File,
    private val repository: AccountRepository? = null,
    /**
     * 下载 authlib-injector 的入口，抽成参数是为了单测不联网。
     *
     * 默认实现走 [AuthlibInjector.ensure]（先官方源、失败退 BMCLAPI）。
     */
    private val ensureJar: suspend (File, (String) -> Unit) -> File = { jar, onProgress ->
        AuthlibInjector.ensure(jar, onProgress)
    },
) : AccountAuth {

    /** jar 将来会被放在哪（界面上显示路径用，不保证已经存在）。 */
    fun jarFile(): File = AuthlibInjector.jarOf(gameRoot)

    /**
     * 用账号密码登录一个外置服。
     *
     * 成功之后本地留下的只有 accessToken 与 clientToken，**密码不落盘**。
     *
     * [onProgress] 用来告诉界面「正在做什么」—— 外置登录要走两三次网络，不报进度
     * 用户会以为卡死了。
     */
    suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
        onProgress: (String) -> Unit = {},
    ): StoredAccount = withContext(Dispatchers.IO) {
        onProgress("正在确认认证服务器")
        val client = YggdrasilClient(serverUrl)
        val metadata = runCatching { client.metadata() }.getOrNull()
        onProgress(
            if (metadata != null && metadata.serverName.isNotBlank()) {
                "正在登录 " + metadata.serverName
            } else {
                "正在登录"
            },
        )
        val session = client.authenticate(username, password)
        val stored = StoredAccount(
            uuid = MicrosoftAuth.normalizeUuid(session.profile.compactId),
            name = session.profile.name,
            // 外置登录没有 refresh_token 这种东西，refreshToken 这一格存的是 accessToken。
            refreshToken = session.accessToken,
            createdAt = System.currentTimeMillis(),
            lastLoginAt = System.currentTimeMillis(),
            kind = StoredAccount.KIND_YGGDRASIL,
            serverUrl = metadata?.apiRoot ?: client.apiRoot,
            clientToken = session.clientToken,
        )
        repository?.save(stored)
        onProgress("登录成功")
        stored
    }

    /**
     * 拿存下来的令牌续期，产出启动用的会话。
     *
     * 每次启动都续一次而不是等着过期：accessToken 有期限（多数服 15 天），拿一份快要
     * 过期的去启动，玩到一半被服务端踢出来，用户完全不知道为什么。
     */
    override suspend fun sessionFor(account: StoredAccount): Session = withContext(Dispatchers.IO) {
        if (!account.isYggdrasil) {
            throw YggdrasilException("这条记录不是外置登录账号。")
        }
        val client = YggdrasilClient(account.serverUrl)
        val refreshed = client.refresh(account.refreshToken, account.clientToken)
        // 令牌轮换过就回存：服务端可能把旧的作废了，不回存下次登录就失败。
        if (refreshed.accessToken != account.refreshToken || refreshed.clientToken != account.clientToken) {
            runCatching {
                repository?.save(
                    account.copy(
                        refreshToken = refreshed.accessToken,
                        clientToken = refreshed.clientToken.ifBlank { account.clientToken },
                        lastLoginAt = System.currentTimeMillis(),
                        name = refreshed.profile.name,
                    ),
                )
            }
        }
        refreshed.toSession()
    }

    /**
     * 启动前准备 authlib-injector，并给出要加进 JVM 的参数。
     *
     * 顺序是有意的：**先 [sessionFor] 拿到令牌，再调这个方法**。反过来的话，这里会先去
     * 下几十 KB 的 jar、再拉元数据，最后才告诉用户「密码过期了」—— 白等一场。
     *
     * 元数据拉不到不算失败（[AuthlibInjector.jvmArgs] 会跳过 prefetched 那一项），
     * 因为游戏启动时会自己再拉一次；但 jar 下不下来就是硬失败。
     */
    suspend fun launchArgs(
        account: StoredAccount,
        onProgress: (String) -> Unit = {},
    ): List<String> = withContext(Dispatchers.IO) {
        if (!account.isYggdrasil) return@withContext emptyList()
        val root = account.serverUrl.trim().trimEnd('/')
        if (root.isEmpty()) {
            throw YggdrasilException("这条外置登录记录没有服务器地址，重新登录一次。")
        }
        val jar = ensureJar(AuthlibInjector.jarOf(gameRoot), onProgress)
        val metadata = runCatching { YggdrasilClient(root).metadata() }.getOrNull()
        val args = AuthlibInjector.jvmArgs(jar, root, metadata)
        if (args.isEmpty()) {
            throw YggdrasilException("拼不出 authlib-injector 的启动参数，检查服务器地址：" + root)
        }
        onProgress("外置登录已挂上")
        args
    }
}
