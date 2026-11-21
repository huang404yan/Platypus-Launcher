package io.github.huang404yan.platypus.core.auth

import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import java.io.File

/**
 * 一次启动要用的会话，以及它是怎么来的。
 *
 * [account] 为 null 表示离线 —— 这时候 [label] 是「离线」打头，界面上要如实显示，
 * 不能让用户以为自己登录着却进了个离线号。
 */
data class AccountSession(
    val session: Session,
    val account: StoredAccount?,
    val jvmArgs: List<String>,
    val label: String,
) {
    val isOffline: Boolean get() = account == null
}

/**
 * 没有 `LauncherBackend` 那一层时，自己把账号装配起来。
 *
 * 桌面端这些由 DesktopServices 一次性装好再经 LauncherBackend 传下去；安卓没有那个壳，
 * 每个页面自己 new。放成一处的理由只有一个：**账号页写进去的东西，启动页必须读得到**，
 * 两边各写一份路径迟早对不上（一边写 platypus/accounts.json、另一边读别处，表现为
 * 「登录成功了但启动还是离线号」）。
 *
 * 路径全跟着游戏目录走，与桌面端同一个文件：同一个人把 .minecraft 在两台设备间同步时，
 * 登录状态也跟着走（令牌仍然各自刷新）。
 */
object AccountBootstrap {

    fun indexFile(gameRoot: File): File = JsonAccountRepository.defaultIndexFile(gameRoot)

    fun settingsFile(gameRoot: File): File = JsonSettingsRepository.defaultFile(gameRoot)

    fun repository(gameRoot: File): JsonAccountRepository = JsonAccountRepository(indexFile(gameRoot))

    suspend fun settings(gameRoot: File): LauncherSettings? =
        runCatching { JsonSettingsRepository(settingsFile(gameRoot)).load() }.getOrNull()

    /** 磁盘上登记的账号；读不出来一律当空表，界面上显示「还没有登录过」。 */
    suspend fun list(gameRoot: File): List<StoredAccount> =
        runCatching { repository(gameRoot).list() }.getOrNull().orEmpty()

    /**
     * 设置里记的那一串能不能对上这条账号。
     *
     * 三种写法都得认：账号自己的 [StoredAccount.key]（小写、去掉横线）、带横线的标准 UUID
     * （微软给的就是这个形状，界面一直存的是它）、以及大写放进去的那些。只比其中一种的话，
     * 会出现「账号明明选过了，启动时还是离线号」——桌面端最早就是拿 key 直接比带横线的 UUID，
     * 结果列表里一条都不高亮。
     */
    fun matches(account: StoredAccount, activeUuid: String): Boolean {
        val wanted = activeUuid.trim().lowercase()
        if (wanted.isEmpty()) return false
        return account.key == wanted ||
            account.uuid.trim().lowercase() == wanted ||
            MicrosoftAuth.compactUuid(account.uuid).lowercase() == wanted
    }

    /** 当前要用的账号：设置里 activeAccountUuid 指哪条就是哪条，指不到算没登录。 */
    suspend fun active(gameRoot: File): StoredAccount? {
        val wanted = settings(gameRoot)?.activeAccountUuid?.trim().orEmpty()
        if (wanted.isBlank()) return null
        return list(gameRoot).firstOrNull { matches(it, wanted) }
    }

    /** 写进设置的那一串。统一存 [StoredAccount.key]：一个账号只有一个 key，比较时不用担心写法。 */
    fun settingValueOf(account: StoredAccount): String = account.key

    /**
     * 切换当前账号；空串表示改回离线。
     *
     * 用的是 copy 而不是重新拼一份 LauncherSettings：设置页与账号页都可能写同一个文件，
     * 凭空 new 一份会把对方刚写进去的离线用户名、内存、配色全冲掉。
     */
    suspend fun markActive(gameRoot: File, uuid: String) {
        val repository = JsonSettingsRepository(settingsFile(gameRoot))
        val current = runCatching { repository.load() }.getOrNull() ?: LauncherSettings()
        val trimmed = uuid.trim()
        // 能对上账号就存它的 key：调用方传进来的是带横线的 UUID，而 key 是小写无横线的，
        // 存哪个都能读回来，但只存一种就不会出现「两种写法并存、比较时对不上」。
        val value = if (trimmed.isEmpty()) {
            ""
        } else {
            list(gameRoot).firstOrNull { matches(it, trimmed) }?.key ?: trimmed
        }
        // 用默认的 keepUserFilled = true：这里改的是「用哪个账号」，与凭据三格无关。
        // 万一盘上那份此刻是坏的（load 退回了默认值），保底逻辑还能把 ID 与服务地址捞回来。
        repository.save(current.copy(activeAccountUuid = value))
    }

    /** 改应用 ID（微软登录用）；同样走 copy，理由见 [markActive]。 */
    suspend fun saveClientId(gameRoot: File, raw: String) {
        val repository = JsonSettingsRepository(settingsFile(gameRoot))
        val current = runCatching { repository.load() }.getOrNull() ?: LauncherSettings()
        // keepUserFilled = false：这一下的目的就是把 ID 改成传入值，包括用户主动清空。
        repository.save(current.copy(microsoftClientId = raw.trim().trim('"').trim()), keepUserFilled = false)
    }

    /**
     * 换出这一局要用的会话。
     *
     * 三个决定：
     *
     * 1. **换不到就抛，不静默退回离线。** 桌面端踩过这个坑：退回离线之后用户以为
     *    自己登录着，进了服务器才发现是个离线号，那时候已经说不清了。
     * 2. **外置登录先换令牌再挂 agent**，顺序反过来的话密码过期也要先把 jar 下完才报错。
     * 3. 令牌每次启动现换，不缓存 —— MC 令牌两小时就过期，存下来的十个九个是废的。
     */
    suspend fun resolve(
        gameRoot: File,
        offlineName: String,
        onStage: (String) -> Unit = {},
    ): AccountSession {
        val name = offlineName.trim().ifBlank { LauncherSettings.DEFAULT_OFFLINE_USERNAME }
        val stored = settings(gameRoot)
        val account = active(gameRoot)
        if (account == null) {
            return AccountSession(
                session = OfflineProfile.session(name),
                account = null,
                jvmArgs = emptyList(),
                label = "离线（" + name + "）",
            )
        }
        val repository = repository(gameRoot)
        if (account.isYggdrasil) {
            val service = YggdrasilAccountAuth(gameRoot = gameRoot, repository = repository)
            onStage("正在验证外置登录账号")
            val session = service.sessionFor(account)
            onStage("正在准备外置登录")
            val args = service.launchArgs(account) { text -> onStage(text) }
            return AccountSession(session, account, args, "外置登录 · " + account.name)
        }
        val userClientId = stored?.microsoftClientId.orEmpty()
        val service = MicrosoftAccountAuth(
            repository = repository,
            clientIdProvider = { ClientIds.resolve(userClientId) },
        )
        onStage("正在验证正版账号")
        val session = service.sessionFor(account)
        return AccountSession(session, account, emptyList(), "正版 · " + account.name)
    }
}