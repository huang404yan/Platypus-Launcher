package io.github.huang404yan.platypus.core.settings

import io.github.huang404yan.platypus.core.Endpoints
import io.github.huang404yan.platypus.core.download.DownloadPreference
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
 *   这里只决定「第一次建实例给多少」。默认是按 [defaultMemoryAuto] 由物理内存算出来的，
 *   见 [io.github.huang404yan.platypus.core.instance.MemoryDefaults]。
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
     * 默认内存按本机物理内存自动算（2026-11-16 加）。
     *
     * 为什么默认开：这一版是发给别人的，2048 MB 在 8 GB 的机器上开模组整合包必爆、
     * 在 64 GB 的机器上又浪费。算法在 [io.github.huang404yan.platypus.core.instance.MemoryDefaults]，
     * 读这个字段的界面**不要**直接用 [defaultMemoryMb]，要走
     * `MemoryDefaults.resolve(defaultMemoryMb, defaultMemoryAuto, 物理内存)`。
     *
     * 默认 true 会改变旧数据的意思（老用户存的那个数不再生效），这是有意的：
     * 老用户里绝大多数没调过这一项，留着 2048 反而是 bug。真拖过滑条的人，
     * 界面上把开关关掉就回到手动模式，值还在 [defaultMemoryMb] 里没丢。
     */
    val defaultMemoryAuto: Boolean = true,
    /**
     * 新建实例默认开不开版本隔离（2026-11-16 从「版本设置」里提出来的全局默认）。
     *
     * 隔离的意思见 [Instance.isolated]：开 = 这个版本自己一份存档/模组/配置，
     * 关 = 与同目录下别的版本共用游戏根目录那一份。
     *
     * 为什么要有全局默认：绝大多数人从来不需要改它，而每个实例各有一个开关意味着
     * 每装一个版本都要重新确认一次。关掉它的人通常是「整合包把 mods/ 平铺在根目录」，
     * 那种人一次只能玩一个包，全局一个开关刚好。
     *
     * 已经单独设过的实例不受影响：它们自己存着 [Instance.isolated]，只有标了
     * [Instance.isolatedFollowsDefault] 的才跟着这一项走。
     */
    val defaultIsolation: Boolean = true,
    /**
     * 进游戏用哪个语言；空串表示跟随系统（2026-11-16 加）。
     *
     * 存的是 Minecraft 的 options.txt 里 lang: 那一行的值（zh_cn、en_us…），
     * 不是给人看的名字 —— 启动前原样写进文件，省一层映射就不会两边对不上。
     * 空串时按系统语言猜，见 [io.github.huang404yan.platypus.core.launch.GameLanguage]。
     */
    val gameLanguage: String = "",
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
     * 下载源偏好：auto 官方源优先，mirror 优先国内镜像（默认，2026-11-16 起）。
     *
     * 存 id 字符串而不是枚举下标，理由同 theme。这一项影响的是全部下载
     *（游戏本体、模组、整合包、Java、服务端），因为国内网络下官方源经常不是慢而是根本连不上。
     */
    val downloadSource: String = "mirror",
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
    /**
     * 安卓端初次引导走完的时刻（毫秒）；0 表示还没走过。
     *
     * 为什么不复用 [firstRunAt]：那一格在桌面端也读，而且它管的是「正版账号那扇门」。
     * 手机上还有两件桌面没有的事 —— 应用内 JRE 与图形转译库 —— 缺了第一件事就寸步难行，
     * 混进同一格会让「桌面上走过了、导出到手机」的设备直接跳过正准备的事。
     * 分开记的代价只是多一格（见 [io.github.huang404yan.platypus.core.setup.AndroidSetup]）。
     */
    val androidGuideAt: Long = 0L,
    /**
     * 界面模式：`full` 完整、`simple` 简洁；空串表示还没选过，按默认档渲染（简洁，见 `UiMode.DEFAULT`）。
     *
     * 存 id 字符串而不是布尔或下标，理由同 [theme]：以后加第三档（自定义）不用改读法，
     * 认不出来的一律当完整模式（[io.github.huang404yan.platypus.ui.UiMode.of]）。
     *
     * 这一格**只影响渲染**：简洁模式下藏起来的二级项、区块仍然存在，切回完整模式原样回来，
     * 盘上的实例、存档、模组一个字节都不动。所以它不进 [normalize] 的白名单校验 ——
     * 认不出的值让读取方退默认，不要把用户手改的字符串洗成空。
     */
    val uiMode: String = "",
    /**
     * 关掉界面动效（2026-11-21 加）。
     *
     * 远程桌面、录屏、老机器上动画会拖慢操作，而「这台机器跑不跑得动动画」没法自动判断
     *（帧率采样要在渲染时才有数，第一帧就已经在动了）。所以给一个直白的开关，
     * 打开后所有过渡时长压到最短，功能不受影响。
     */
    val reduceMotion: Boolean = false,
    /**
     * 关窗口时收进通知区（托盘）还是直接退出（2026-11-22 加，默认关）。
     *
     * 原来只要系统支持托盘就一律装上，用户点「×」以为退了，进程其实还在，
     * 后台的下载与游戏也还在跑——「以为关了其实没关」比「没法最小化到托盘」更让人难受，
     * 所以默认按「关了就是关了」来。打开这个开关之后才装托盘图标、才把「×」变成收起。
     *
     * 与 [reduceMotion] 一样是纯渲染/纯行为开关，不进 [normalize] 的白名单：
     * 加新布尔字段不该逼着旧配置文件跟着补一行。
     */
    val trayEnabled: Boolean = false,
    /**
     * 同时下几个文件；0 表示自适应。
     *
     * 只影响「一次装多少个文件并发下」，不影响单个文件内部的分段（那个上限是
     * [io.github.huang404yan.platypus.core.download.HttpDownloader] 里写死的：
     * 实测国内镜像对多段并发 Range 不友好，开得越多越慢，所以不给用户调）。
     */
    val downloadConnections: Int = 0,
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
            gameLanguage = raw.gameLanguage.trim().lowercase(),
            activeAccountUuid = raw.activeAccountUuid.trim(),
            microsoftClientId = raw.microsoftClientId.trim().trim('"').trim(),
            theme = raw.theme.trim().lowercase(),
            uiMode = raw.uiMode.trim().lowercase(),
            downloadSource = raw.downloadSource.trim().lowercase(),
            downloadConnections = DownloadPreference.normalizeConnections(raw.downloadConnections),
            serverBase = Endpoints.normalizeBase(raw.serverBase),
            curseForgeApiKey = raw.curseForgeApiKey.trim().trim('"').trim(),
            lastInstanceKey = raw.lastInstanceKey.trim(),
            // 负数一律当没走过：时间戳只可能从 0 往上长，负值是手改文件或写坏的结果。
            firstRunAt = raw.firstRunAt.coerceAtLeast(0L),
            androidGuideAt = raw.androidGuideAt.coerceAtLeast(0L),
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

    /**
     * 写设置，并说明「用户手填的那几格」该怎么处置。
     *
     * 应用 ID、服务地址、CurseForge Key 只能由用户填出来，程序变不出新值。而写设置的
     * 地方有一大把（配置页保存、切账号、换配色、换模式、换下载源），任何一处拿着一份
     * 不完整的设置写盘，这三格就永久变成空串了 —— 用户得重新申请一遍、重新找一遍地址。
     *
     * 默认实现退回 [save]（= 不特殊处理），这样只关心读写的实现（测试用的假仓库、
     * 冒烟脚本）不用跟着改；[JsonSettingsRepository] 覆写它来真正护住那三格。
     *
     * @param keepUserFilled true（默认）= 传进来的为空而盘上有值时留盘上的；
     *   false = 原样写，用于用户在界面上亲手清空的场景。
     */
    suspend fun save(settings: LauncherSettings, keepUserFilled: Boolean): LauncherSettings =
        save(settings)
}
