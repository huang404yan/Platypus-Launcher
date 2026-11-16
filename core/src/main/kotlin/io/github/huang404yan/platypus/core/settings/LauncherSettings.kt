package io.github.huang404yan.platypus.core.settings

import io.github.huang404yan.platypus.core.Endpoints
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.saves.AutoBackupPolicy
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
     * settings.json 里，不进仓库也不进安装包。申请步骤写在设置页的账号那一栏里。
     */
    val microsoftClientId: String = "",
    /**
     * 配色方案：`auto` 跟随版本（孵化版蓝橙 / 正式版深色），`natural` 或 `perry` 是用户手动选的。
     *
     * 存 id 字符串而不是枚举下标：以后加主题、改顺序都不会把老文件读歪。
     * 认不出来的一律当 auto（[io.github.huang404yan.platypus.core.theme.launcherThemeOf]）。
     */
    val theme: String = "auto",
    /**
     * 下载源偏好：auto 官方源优先（默认），mirror 优先国内镜像。
     *
     * 存 id 字符串而不是枚举下标，理由同 theme。这一项影响的是全部下载
     *（游戏本体、模组、整合包、Java、服务端），因为国内网络下官方源经常不是慢而是根本连不上。
     */
    val downloadSource: String = "auto",
    /**
     * 空壳发布包用的镜像根地址（见 [Endpoints]）；完整版这里是空串，根本不读。
     *
     * 为什么存进设置：空壳包里所有下载/搜索地址都从这个根拼出来（[Endpoints.use]），
     * 用户填一次就整个会话有效，下次开程序还得能记住。只认 http/https 的完整地址，
     * 填歪了一律当归零处理（[Endpoints.normalizeBase]），不在这里抛错。
     */
    val serverBase: String = "",
    /**
     * 存档自动备份开着没有。
     *
     * 默认开着：存档是玩家唯一无法重新下载的东西（模组能重下、Java 能重装、
     * 整合包能重导），而「备份」这件事人一定会忘。代价只是盘上多几个 zip。
     */
    val autoBackupEnabled: Boolean = true,
    /**
     * 同一个世界隔多少小时再备份一次。
     *
     * 存数字而不是枚举档位：用户可能就想改成 6 小时，档位里塞不下这些组合。
     * 超范围的值由 [normalize] 夹回 [io.github.huang404yan.platypus.core.saves.AutoBackupPolicy.INTERVAL_RANGE]。
     */
    val autoBackupHours: Int = AutoBackupPolicy.DEFAULT_INTERVAL_HOURS,
    /** 每个世界最多留几份自动备份；超出的从最旧的开始删。 */
    val autoBackupKeep: Int = AutoBackupPolicy.DEFAULT_KEEP,
    /**
     * 用户自己申请的 CurseForge API Key；空串表示没填，导出 CurseForge 包时查不了文件归属。
     *
     * 刻意不内置：CurseForge 的 API 要 Key，而内置进仓库的 Key 会随源码一起公开 ——
     * 轻则被刷爆额度，重则整个应用被封。填一次就行，只存在本机 settings.json 里。
     * 没填时导出照样能导，只是 manifest.json 的 files 列表空着（文件全在 overrides/ 里）。
     */
    val curseForgeApiKey: String = "",
    /**
     * **已废弃**：界面上不再有「自动分配」这个开关（2026-11-16 删）。
     *
     * 它从来没有接进 JVM 参数 —— [io.github.huang404yan.platypus.core.launch.LaunchPlanner]
     * 直接取实例上的 memoryMb，LaunchArguments 拿它拼 -Xmx，中间没人看这个字段。
     * 现在留着只为读旧 settings.json 时别报错（@Serializable 有默认值，删了也能读，
     * 但保留字段可以避免「存回去就把用户原来的值丢了」）；不要在新代码里读它。
     */
    val memoryAuto: Boolean = true,
    // 这里原来还有 memoryOptimize（内存优化）与 memoryAskSkipped（「以后都这样」）两格，
    // 2026-11-16 一并删了：前者在设置页有个勾选框，但启动链从没读过它 —— -Xmx 只认实例上的
    // memoryMb，勾了也不会往 JVM 参数里加任何东西；后者全仓库没有任何读取方，是个纯存储位。
    // 删字段不会让旧 settings.json 读失败：Json 配了 ignoreUnknownKeys，多出来的键直接丢掉。
    /**
     * 上次真正启动过的实例（[io.github.huang404yan.platypus.core.instance.instanceKey] 的写法）。
     *
     * 启动页扫完目录后按「实例页点名 > 当前选中 > 上次启动的那个 > 列表第一个」挑，
     * 这样隔天再开程序，默认停在昨天玩的那个版本上，而不是按名字排在最前面的那个。
     * 存 key 而不是 uuid：实例目录被挪走、重新扫描后 id 会变，目录才是稳定身份。
     */
    val lastInstanceKey: String = "",
    /**
     * 首次启动引导走完的时刻（毫秒）；0 表示还没走过。
     *
     * 用时间戳而不是一个布尔：布尔能回答「走没走过」，时间戳还能回答「什么时候装的」。
     * 后者在排查「我明明登录过，为什么又弹窗」时是唯一的线索 —— 常见原因是游戏目录换了，
     * 而 settings.json 是跟着游戏目录走的（见 [io.github.huang404yan.platypus.core.edition.FirstRun]）。
     */
    val firstRunAt: Long = 0L,
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
            downloadSource = raw.downloadSource.trim().lowercase(),
            serverBase = Endpoints.normalizeBase(raw.serverBase),
            curseForgeApiKey = raw.curseForgeApiKey.trim().trim('"').trim(),
            lastInstanceKey = raw.lastInstanceKey.trim(),
            // 负数一律当没走过：时间戳只可能从 0 往上长，负值是手改文件或写坏的结果。
            firstRunAt = raw.firstRunAt.coerceAtLeast(0L),
            autoBackupHours = raw.autoBackupHours.coerceIn(
                AutoBackupPolicy.INTERVAL_RANGE.first,
                AutoBackupPolicy.INTERVAL_RANGE.last,
            ),
            autoBackupKeep = raw.autoBackupKeep.coerceIn(
                AutoBackupPolicy.KEEP_RANGE.first,
                AutoBackupPolicy.KEEP_RANGE.last,
            ),
        )

        /** 设置里的三格合成自动备份策略，界面与启动页都从这一处取。 */
        fun policyOf(settings: LauncherSettings): AutoBackupPolicy = AutoBackupPolicy.of(
            enabled = settings.autoBackupEnabled,
            intervalHours = settings.autoBackupHours,
            keep = settings.autoBackupKeep,
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
