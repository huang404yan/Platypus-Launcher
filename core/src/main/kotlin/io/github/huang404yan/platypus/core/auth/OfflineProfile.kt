package io.github.huang404yan.platypus.core.auth

import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 离线（未登录）会话。
 *
 * UUID 规则与官方启动器一致：`UUID.nameUUIDFromBytes("OfflinePlayer:<名字>")` 的 32 位无横线十六进制。
 * 名字必须参与计算，否则两个离线玩家会因为 UUID 相同而在服务器上互相顶号。
 *
 * [Session.accessToken] 刻意不给空串：1.20+ 的 authlib 在 accessToken 为空时会抛
 * AuthenticationUnavailableException，给 "0" 这类非空占位才能进主菜单（LaunchDiagnosis 里也有这条归因）。
 */
object OfflineProfile {

    /** 离线 UUID：32 位小写十六进制，无横线。 */
    fun uuidFor(username: String): String =
        UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).toByteArray(StandardCharsets.UTF_8))
            .toString()
            .replace("-", "")

    /** 拼一个可用的离线会话；userType 用 legacy，与官方启动器的离线模式一致。 */
    fun session(username: String, accessToken: String = "0", userType: String = "legacy"): Session =
        Session(
            username = username,
            uuid = uuidFor(username),
            accessToken = accessToken,
            userType = userType,
        )
}
