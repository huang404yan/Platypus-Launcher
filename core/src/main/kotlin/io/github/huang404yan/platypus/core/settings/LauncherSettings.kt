package io.github.huang404yan.platypus.core.settings

import io.github.huang404yan.platypus.core.instance.Instance
import kotlinx.serialization.Serializable

/**
 * 启动器自己的设置（不是某个实例的设置）。
 *
 * 只有三件事值得跨会话记住，都能直接影响「双击就玩」：
 * - [offlineUsername]：离线模式下进游戏的玩家名。写死 "Dev" 的话，用户改完关掉窗口就丢，
 *   换台机器或换个存档还得再改一次。
 * - [defaultGameRoot]：上次用的 .minecraft 目录。检测不到默认位置时，用户每次开程序都要重新填一遍。
 * - [defaultMemoryMb]：新登记实例的默认内存。实例自己的内存仍然存在 instances.json 里，
 *   这里只决定「第一次建实例给多少」。
 *
 * 读写宽容是刻意的：文件损坏、字段缺失、以后加了新字段，都退到默认值继续跑，
 * 不让一个设置文件把启动器顶死。
 */
@Serializable
data class LauncherSettings(
    val offlineUsername: String = DEFAULT_OFFLINE_USERNAME,
    val defaultGameRoot: String = "",
    val defaultMemoryMb: Int = Instance.DEFAULT_MEMORY_MB,
    /**
     * 启动时用哪个正版账号的 UUID；空串表示走离线模式。
     *
     * 只记 UUID 不记令牌：令牌在 platypus/accounts.json 里，由账号仓库单独管。
     * 这样用户在设置页换游戏目录、或者把设置文件发给别人时，不会连账号一起带出去。
     */
    val activeAccountUuid: String = "",
    /**
     * 用户自己注册的微软应用（Client）ID；空串表示没填，正版登录用不了。
     *
     * 刻意不内置一个随包发出去的 ID：那等于让所有使用者共用一份别人的应用注册，
     * 出了限流或被微软收紧，拿到安装包的人无从处理。填一次就行，存在本机这个
     * settings.json 里，不进仓库也不进安装包。申请步骤见 docs/41。
     */
    val microsoftClientId: String = "",
    /**
     * 配色方案：`auto` 跟随版本（孵化版蓝橙 / 正式版深色），`natural` 或 `perry` 是用户手动选的。
     *
     * 存 id 字符串而不是枚举下标：以后加主题、改顺序都不会把老文件读歪。
     * 认不出来的一律当 auto（[io.github.huang404yan.platypus.core.theme.launcherThemeOf]）。
     */
    val theme: String = "auto",
    /** 最近一次写盘时间（毫秒）；0 表示还没写过。 */
    val updatedAt: Long = 0L,
) {
    companion object {
        /** 与官方启动器离线模式的习惯一致；3 位是 Minecraft 用户名的最小长度。 */
        const val DEFAULT_OFFLINE_USERNAME: String = "Dev"

        /** Minecraft 用户名的长度限制（含）。 */
        const val MIN_USERNAME_LENGTH = 3
        const val MAX_USERNAME_LENGTH = 16

        /** 把设置收敛到合法范围：内存夹到区间、用户名去空白、空名回退默认值。 */
        fun normalize(raw: LauncherSettings): LauncherSettings = raw.copy(
            offlineUsername = normalizeOfflineUsername(raw.offlineUsername),
            defaultGameRoot = raw.defaultGameRoot.trim().trim('"'),
            defaultMemoryMb = Instance.clampMemoryMb(raw.defaultMemoryMb),
            activeAccountUuid = raw.activeAccountUuid.trim(),
            microsoftClientId = raw.microsoftClientId.trim().trim('"').trim(),
            theme = raw.theme.trim().lowercase(),
        )
    }
}

/** 用户名去首尾空白；空串回退默认名，避免存下去一个空名字。 */
fun normalizeOfflineUsername(raw: String): String =
    raw.trim().ifBlank { LauncherSettings.DEFAULT_OFFLINE_USERNAME }

/**
 * 校验离线用户名，合法返回 null，不合法返回中文原因。
 *
 * 规则照抄 Minecraft 正版名：3–16 位，只允许英文字母、数字和下划线。
 * 校验失败不阻止保存（离线模式实际比这宽松），只是让界面能提前提醒一句，
 * 免得用户带着一个带空格的名字进游戏、然后在服务器上被踢。
 */
fun usernameProblem(raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty()) return "用户名不能为空。"
    if (name.length < LauncherSettings.MIN_USERNAME_LENGTH || name.length > LauncherSettings.MAX_USERNAME_LENGTH) {
        return "用户名要 " + LauncherSettings.MIN_USERNAME_LENGTH + "–" + LauncherSettings.MAX_USERNAME_LENGTH +
            " 个字符，现在是 " + name.length + " 个。"
    }
    val bad = name.firstOrNull { !it.isAsciiLetterOrDigit() && it != '_' }
    if (bad != null) return "用户名只能用英文字母、数字和下划线，「" + bad + "」不行。"
    return null
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9')

/** 设置的读写。实现自己保证并发安全与「写坏不炸」。 */
interface SettingsRepository {
    suspend fun load(): LauncherSettings
    suspend fun save(settings: LauncherSettings): LauncherSettings
}
