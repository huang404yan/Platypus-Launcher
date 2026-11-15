package io.github.huang404yan.platypus.core.server

import io.github.huang404yan.platypus.core.auth.OfflineProfile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 服务端的白名单与管理员名单。
 *
 * 两个文件都是服务端自己维护的 JSON，结构几乎一样（`ops.json` 多一个 `level` 与 `bypassesPlayerLimit`）。
 * 这里刻意**不直接手写这两个文件**来做「加个人」：服务端在线时会把自己的内存状态定期写回去，
 * 我们改盘上的文件会被它覆盖。所以真正的入口是控制台命令（`whitelist add <名字>` / `op <名字>`），
 * 而这里的读写有两个用途：① 看现在有谁（不进游戏也能查）② 服务端**没在跑**时直接改文件。
 *
 * 判断「名字对应哪个 UUID」用的是官方那一套：正版走 Mojang 的档案接口，查不到就是离线
 * UUID（`OfflinePlayer:<名字>` 的 MD5）。这跟 [OfflineProfile] 是同一个算法 —— 两边算不一样，
 * 离线启动器进来的玩家就永远加不进白名单。
 */
@Serializable
data class ServerPlayer(
    val uuid: String,
    val name: String,
    /** 只在 `ops.json` 里有；1..4，4 是最高。 */
    val level: Int = 4,
    val bypassesPlayerLimit: Boolean = false,
) {
    /** 32 位小写无横线，比较用。 */
    val key: String get() = uuid.replace("-", "").lowercase()
}

/** 加的这个人是怎么认出来的 —— 界面要说清楚，否则用户不知道名字会不会踩到别人。 */
enum class PlayerLookupKind(val label: String) {
    /** Mojang 档案接口查到了。 */
    MOJANG("正版账号"),

    /** 接口说没有这个人，按离线规则算。 */
    OFFLINE("离线（接口里没这个正版账号）"),

    /** 网络不通，按离线规则算 —— 这一条必须提示，因为正版服里大概率是错的。 */
    ASSUMED_OFFLINE("网络不通，先按离线规则算"),
}

data class PlayerLookup(val name: String, val uuid: String, val kind: PlayerLookupKind)

object ServerPlayers {

    const val WHITELIST_NAME = "whitelist.json"
    const val OPS_NAME = "ops.json"

    private const val MOJANG_LOOKUP = "https://api.mojang.com/users/profiles/minecraft/"
    private const val USER_AGENT = "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun whitelistFile(dir: File): File = File(dir, WHITELIST_NAME)

    fun opsFile(dir: File): File = File(dir, OPS_NAME)

    /** 读白名单。文件不存在、读坏了都返回空表 —— 界面上显示「还没有人」比报错有用。 */
    fun whitelist(dir: File): List<ServerPlayer> = read(whitelistFile(dir))

    fun ops(dir: File): List<ServerPlayer> = read(opsFile(dir))

    private fun read(file: File): List<ServerPlayer> {
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ServerPlayer.serializer()), text)
        }.getOrElse { emptyList() }
    }

    /** 写白名单（服务端没在跑时才该调用，理由见类注释）。先写 .tmp 再改名。 */
    fun writeWhitelist(dir: File, players: List<ServerPlayer>): File = write(whitelistFile(dir), players)

    fun writeOps(dir: File, players: List<ServerPlayer>): File = write(opsFile(dir), players)

    private fun write(target: File, players: List<ServerPlayer>): File {
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("建不了目录：" + parent.absolutePath)
        }
        // 服务端自己的两个文件都是数组；空的也要写 []，不是空文件。
        val text = json.encodeToString(ListSerializer(ServerPlayer.serializer()), players.distinctBy { it.key })
        val temp = File(target.parentFile ?: target.absoluteFile.parentFile, target.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
        return target
    }

    /**
     * 一个名字对应哪个 UUID。
     *
     * 先问 Mojang 的档案接口（正版名字是全局唯一的，这个接口也是它给的）；
     * 接口返回 204/404 表示「没有这个正版账号」，那就按离线规则算；
     * 网络不通时**也**按离线规则算，但把 [PlayerLookupKind.ASSUMED_OFFLINE] 带回去，
     * 让界面能说「网络不通，这个 UUID 可能不对」。
     */
    fun lookup(name: String): PlayerLookup {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) throw IllegalArgumentException("名字不能为空")
        if (cleaned.length > 16) throw IllegalArgumentException("Minecraft 名字最长 16 个字符")
        if (!cleaned.all(::isNameChar)) {
            throw IllegalArgumentException("Minecraft 名字只能用字母、数字和下划线")
        }
        val online = runCatching { lookupMojang(cleaned) }
        val uuid = online.getOrNull()
        return when {
            uuid != null -> PlayerLookup(cleaned, uuid, PlayerLookupKind.MOJANG)
            online.isSuccess -> PlayerLookup(cleaned, OfflineProfile.uuidFor(cleaned), PlayerLookupKind.OFFLINE)
            else -> PlayerLookup(cleaned, OfflineProfile.uuidFor(cleaned), PlayerLookupKind.ASSUMED_OFFLINE)
        }
    }

    /** 查到了返回带横线的标准 UUID；接口明确说没有这个人返回 null；网络错抛异常。 */
    private fun lookupMojang(name: String): String? {
        val connection = (URL(MOJANG_LOOKUP + name).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            when (val code = connection.responseCode) {
                in 200..299 -> {
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val id = runCatching {
                        json.parseToJsonElement(body).let { element ->
                            element.jsonObject["id"]?.toString()?.trim('\"')
                        }
                    }.getOrNull()
                    if (id.isNullOrBlank()) null else dashUuid(id)
                }
                // 204 是「没有这个人」，404 是老接口的写法，两个都算明确否定。
                204, 404 -> null
                else -> throw IllegalStateException("Mojang 档案接口返回 " + code)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 32 位无横线转成 8-4-4-4-12。 */
    fun dashUuid(compact: String): String {
        val hex = compact.replace("-", "").lowercase()
        if (hex.length != 32) return compact
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) +
            "-" + hex.substring(16, 20) + "-" + hex.substring(20)
    }

    /** 加一个白名单；已经在里面就不动。 */
    fun addWhitelist(dir: File, player: ServerPlayer): List<ServerPlayer> {
        val current = whitelist(dir)
        if (current.any { it.key == player.key }) return current
        val next = current + player
        writeWhitelist(dir, next)
        return next
    }

    fun removeWhitelist(dir: File, key: String): List<ServerPlayer> {
        val current = whitelist(dir)
        val next = current.filterNot { it.key == key.replace("-", "").lowercase() }
        if (next.size != current.size) writeWhitelist(dir, next)
        return next
    }

    fun addOp(dir: File, player: ServerPlayer): List<ServerPlayer> {
        val current = ops(dir)
        val normalized = player.copy(level = player.level.coerceIn(1, 4))
        val index = current.indexOfFirst { it.key == player.key }
        val next = if (index >= 0) current.toMutableList().also { it[index] = normalized } else current + normalized
        writeOps(dir, next)
        return next
    }

    fun removeOp(dir: File, key: String): List<ServerPlayer> {
        val current = ops(dir)
        val next = current.filterNot { it.key == key.replace("-", "").lowercase() }
        if (next.size != current.size) writeOps(dir, next)
        return next
    }

    /** 名字里允许的字符：原版允许字母数字下划线，正版名不分大小写但不含空格。 */
    private fun isNameChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    /** ops.json 里写的权限等级是什么意思。 */
    fun levelLabel(level: Int): String = when (level.coerceIn(1, 4)) {
        1 -> "1 · 能绕过出生点保护"
        2 -> "2 · 能用大部分命令"
        3 -> "3 · 能踢人、封人"
        else -> "4 · 全部权限"
    }
}
