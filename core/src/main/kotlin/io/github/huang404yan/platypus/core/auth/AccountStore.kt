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
) {
    /** 同一个微软账号（按 UUID 认）再次登录，不该在列表里多出一条。 */
    val key: String get() = MicrosoftAuth.compactUuid(uuid).lowercase()
}

/** 账号的存取。和实例、服务端一样走单文件 JSON。 */
interface AccountRepository {
    suspend fun list(): List<StoredAccount>
    suspend fun save(account: StoredAccount)
    suspend fun remove(uuid: String)
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
