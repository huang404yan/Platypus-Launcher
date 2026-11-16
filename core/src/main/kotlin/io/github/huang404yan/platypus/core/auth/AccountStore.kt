package io.github.huang404yan.platypus.core.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 一条正版账号记录。
 *
 * 只存 [refreshToken]，**绝不存 access_token** —— MC 令牌两小时就过期，存下来除了
 * 泄露风险没有任何用处；refresh_token 才是长期凭据，丢了就得重新登录。
 *
 * [uuid] 存带横线的标准写法，方便显示；启动时用 [MicrosoftAuth.compactUuid] 去掉横线。
 */
@Serializable
data class StoredAccount(
    val uuid: String,
    val name: String,
    val refreshToken: String,
    val createdAt: Long = 0L,
    val lastLoginAt: Long = 0L,
    /**
     * 最近一次**确证这个账号真的拥有 Minecraft** 的时刻（毫秒）；0 表示没有记录。
     *
     * 只有一条路径会写它：微软登录走完 XSTS 之后那句
     * `GET /entitlements/mcstore`（见 [MicrosoftAuthClient.authenticate]）。那一步返回空列表，
     * 登录当场就会抛「这个账号没有 Minecraft 的持有记录」—— 也就是说，**能存进这个文件的
     * 微软账号，都已经过了所有权校验**。
     *
     * 记下来的用途只有一个：正式版的首次验证只要一次。没这一格的话，判定只能退回
     * 「这台机器走过首次引导没有」，换个账号再开程序就悄悄放行了 —— 那等于没验证。
     */
    val entitledAt: Long = 0L,
    /**
     * 这条记录是哪种登录：[KIND_MICROSOFT] 或 [KIND_YGGDRASIL]。
     *
     * 默认值是 microsoft —— 这个字段是后加的，老文件里没有它，读出来必须还是微软账号，
     * 否则升级一次所有人的正版账号都变成「不知道是什么」。
     */
    val kind: String = KIND_MICROSOFT,
    /** 外置登录的认证服务器根地址；微软账号这里是空串。 */
    val serverUrl: String = "",
    /**
     * 外置登录的 clientToken（启动器自己生成的随机串，没有过期时间）。
     *
     * 微软那边没有这个概念，所以留空。它必须和 accessToken 一起存：refresh 时服务端
     * 拿它判断「是不是同一个启动器」，对不上会把旧会话踢掉。
     */
    val clientToken: String = "",
) {
    /**
     * 同一个账号不该在列表里多出一条。
     *
     * 微软按 UUID 认。外置登录要多带服务器地址 —— 不同服的两个同名 UUID 是完全不同的
     * 两个账号，只按 UUID 合并会把其中一个顶掉。
     */
    val key: String
        get() = if (isYggdrasil) {
            "yggdrasil|" + MicrosoftAuth.compactUuid(uuid).lowercase() + "|" +
                serverUrl.trim().trimEnd('/').lowercase()
        } else {
            MicrosoftAuth.compactUuid(uuid).lowercase()
        }

    val isYggdrasil: Boolean get() = kind == KIND_YGGDRASIL

    /**
     * 这个账号算不算「验过拥有游戏」。
     *
     * [entitledAt] 是这一版新加的，老文件里没有；而老版本的登录**同样**走了那句
     * entitlements 校验，只是没把时间记下来。所以 `lastLoginAt > 0` 也要算数 ——
     * 否则升级一次，所有老用户都会被当成「没验证过」再拦一道。
     */
    val ownsGame: Boolean get() = entitledAt > 0L || lastLoginAt > 0L

    companion object {
        const val KIND_MICROSOFT = "microsoft"
        const val KIND_YGGDRASIL = "yggdrasil"
    }
}

/** 账号的存取。和实例、服务端一样走单文件 JSON。 */
interface AccountRepository {
    suspend fun list(): List<StoredAccount>
    suspend fun save(account: StoredAccount)
    suspend fun remove(uuid: String)

    /**
     * 按 [StoredAccount.key] 精确删除一条。
     *
     * 外置登录让「按 UUID 删」变得不安全：同一个 UUID 在两个不同的外置服上是两个账号，
     * 按 UUID 删会把不相干的那条也删掉。默认实现退化成 [remove]（对微软账号两者等价），
     * 已有的实现不写这个方法也不会坏。
     */
    suspend fun removeKey(key: String) {
        remove(key)
    }
}

/**
 * 以单个 JSON 文件保存正版账号。
 *
 * 与 [io.github.huang404yan.platypus.core.instance.JsonInstanceRepository] 同一套做法：
 * 人眼能看、写坏了不至于连游戏都开不了；写入走临时文件再改名。
 *
 * **这个文件里有 refresh_token，等价于账号密码**。界面上要提醒用户别把它发给别人；
 * 真正要防的是同步盘（OneDrive 之类）把游戏目录整个传上去，那不在本类能管的范围内。
 */
class JsonAccountRepository(private val indexFile: File) : AccountRepository {

    private val mutex = Mutex()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** 索引文件路径（界面上告诉用户「账号存在哪」用）。 */
    val path: String get() = indexFile.absolutePath

    override suspend fun list(): List<StoredAccount> = mutex.withLock { read() }

    override suspend fun save(account: StoredAccount) = mutex.withLock {
        require(account.uuid.isNotBlank()) { "账号 UUID 不能为空" }
        require(account.refreshToken.isNotBlank()) { "refresh_token 不能为空" }
        val current = read().toMutableList()
        val stored = account.copy(
            uuid = MicrosoftAuth.normalizeUuid(account.uuid),
            createdAt = if (account.createdAt > 0) account.createdAt else System.currentTimeMillis(),
            lastLoginAt = if (account.lastLoginAt > 0) account.lastLoginAt else System.currentTimeMillis(),
        )
        val index = current.indexOfFirst { it.key == stored.key }
        if (index >= 0) current[index] = stored else current += stored
        write(current.sortedBy { it.createdAt })
    }

    override suspend fun remove(uuid: String) = mutex.withLock {
        val key = MicrosoftAuth.compactUuid(uuid).lowercase()
        val current = read()
        // 只删微软那条：同一个 UUID 还可能对应某个外置服上的账号，那不是同一个人。
        val kept = current.filterNot { !it.isYggdrasil && it.key == key }
        if (kept.size != current.size) write(kept)
    }

    override suspend fun removeKey(key: String) = mutex.withLock {
        val current = read()
        val kept = current.filterNot { it.key == key }
        if (kept.size != current.size) write(kept)
    }

    private fun read(): List<StoredAccount> {
        if (!indexFile.isFile) return emptyList()
        val text = runCatching { indexFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StoredAccount.serializer()), text)
        }.getOrElse { emptyList() }
    }

    private fun write(accounts: List<StoredAccount>) {
        val parent = indexFile.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val text = json.encodeToString(ListSerializer(StoredAccount.serializer()), accounts)
        val temp = File(parent ?: indexFile.absoluteFile.parentFile, indexFile.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(indexFile)) {
            // Windows 上目标存在时 renameTo 会失败，退一步「覆盖写」，不比原子更差。
            indexFile.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    companion object {
        /** 默认位置：<gameRoot>/platypus/accounts.json，与实例、服务端索引平级。 */
        fun defaultIndexFile(gameRoot: File): File = File(File(gameRoot, "platypus"), "accounts.json")
    }
}
