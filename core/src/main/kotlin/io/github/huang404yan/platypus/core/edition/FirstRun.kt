package io.github.huang404yan.platypus.core.edition

import io.github.huang404yan.platypus.core.Endpoints
import io.github.huang404yan.platypus.core.auth.ClientIds
import io.github.huang404yan.platypus.core.settings.LauncherSettings

/**
 * 首次启动时该拦住什么、该提示什么。
 *
 * 两条通道的规矩不一样，而且这个差别是产品决定、不是实现细节：
 * - **正式版**（1.0.0 起）要求先登录一个买过 Minecraft 的正版账号，验证一次就够。
 *   它发布的是编好的应用、源码不随包走；这种情况下允许离线进游戏，等于给盗版留一条
 *   明面上的入口。验证放在登录里做（[io.github.huang404yan.platypus.core.auth.MicrosoftAuthClient.authenticate]
 *   的 `GET /entitlements/mcstore`），通过了才算数，之后不再重复问。
 * - **孵化版**（0.x-preview.N）就是开源版，谁都能自己编一份、把这几行判断改掉。
 *   对着一份能随手改的源码强制登录没有意义，反而把「想先看看界面长什么样」的人挡在门外。
 *   所以它只弹一次窗，然后放行。
 *
 * **发出去的孵化版是空壳**（[Endpoints.shell]，构建开关 `-Pplatypus.shell`）：包里不带任何
 * 下载与搜索服务地址。这种包的弹窗不能再讲「不强制正版、离线也能玩」—— 那句话是拿一份
 * 有完整服务端的构建说的；对着一个连版本清单都拉不下来的包讲它，用户照着做只会撞空。
 * 空壳包的弹窗改成讲「这个包需要你填一个镜像根地址」，而这件事在「设置 → 下载」里做。
 *
 * **验证为什么按账号记、而不是按机器记**：只记「这台机器走过引导没有」的话，换个微软
 * 账号再开程序照样放行 —— 那验证就是形同虚设。现在认的是「当前选中的这个微软账号，
 * 自己过没过所有权校验」。
 *
 * 判定全是纯函数：读设置、看有没有账号、给一段中文，不碰网络也不碰界面。
 */
object FirstRun {

    /** 首次启动引导走完的时刻（毫秒）。0 表示还没走过。 */
    const val NOT_DONE: Long = 0L

    /**
     * 这台机器上是不是还没走过首次启动引导。
     *
     * 只看设置里的那一格，不看游戏目录在不在、也不看有没有账号 —— 「装完第一次打开」
     * 与「换了台机器」是两件事，后者属于用户自己的判断，不该由我们代劳。
     */
    fun needed(settings: LauncherSettings): Boolean = settings.firstRunAt <= NOT_DONE

    /**
     * 算出这次的引导状态。
     *
     * [signedIn] 表示当前是否选着一个微软账号（accounts.json 里有 kind=microsoft 的记录，
     * 且设置里选中了它）。[accountOwnsGame] 表示那个账号本身过没过所有权校验
     *（[io.github.huang404yan.platypus.core.auth.StoredAccount.ownsGame]）。
     * [accountsLoaded] 为假时一律当「还不知道」，此时正式版**不**拦 ——
     * 读账号是磁盘操作，第一帧还没读出来，按「没有账号」处理会让每个正式版用户先看到一堵墙，
     * 半秒后它又自己消失。
     */
    fun state(
        settings: LauncherSettings,
        edition: Edition = Edition.current(),
        signedIn: Boolean = false,
        accountsLoaded: Boolean = false,
        accountOwnsGame: Boolean = false,
        shell: Boolean = Endpoints.shell,
    ): FirstRunState {
        // 包自带 ID（正式版由构建方注入）时这里恒为 null：用户不需要填任何东西。
        // 只有一份都没有时才给出「还没有填 Client ID」那句话。
        val clientProblem = ClientIds.missingProblem(settings.microsoftClientId)
        return FirstRunState(
            needed = needed(settings),
            edition = edition,
            signedIn = signedIn,
            accountsLoaded = accountsLoaded,
            clientIdReady = clientProblem == null,
            clientProblem = clientProblem,
            activeAccountUuid = settings.activeAccountUuid,
            accountOwnsGame = accountOwnsGame,
            shell = shell,
        )
    }

    /** 走完引导时要写进设置的时间戳；调用方拿它去 copy 一份新设置。 */
    fun doneAt(now: Long): Long = if (now > 0L) now else 1L
}

/**
 * 一次引导的完整状态。
 *
 * 「要不要拦」与「为什么拦」都在这里算好，界面只负责画：正式版与孵化版的标题、正文、
 * 按钮、能不能关，全由这几个布尔决定，界面里不再写第二份 if。
 */
data class FirstRunState(
    val needed: Boolean,
    val edition: Edition,
    val signedIn: Boolean,
    val accountsLoaded: Boolean,
    val clientIdReady: Boolean,
    val clientProblem: String?,
    /** 设置里当前选中的账号 UUID；空串表示走离线模式。 */
    val activeAccountUuid: String = "",
    /** 当前选中的那个账号自己过没过所有权校验。 */
    val accountOwnsGame: Boolean = false,
    /**
     * 这是不是空壳包（构建时开 -Pplatypus.shell，见 [Endpoints.shell]）。
     *
     * 只影响文案：空壳包里没有任何下载与搜索服务地址，那套「不强制正版、离线也能玩」
     * 的话对它是误导 —— 用户照着做完发现下载页还是空的。空壳包改讲「去填一个镜像根地址」。
     */
    val shell: Boolean = false,
) {
    /** 这一版要不要正版账号。 */
    val requiresSignIn: Boolean get() = edition == Edition.FORMAL

    /**
     * 现在这一刻是否拦住用户。
     *
     * 只有正式版、且只在**首次引导还没走过**时拦，而且必须读完账号、确实不合格才拦。
     * 不合格有两种：根本没选中微软账号（离线或外置登录），或者选中的那个没拥有游戏。
     *
     * **验证一次就够**：走过引导（firstRunAt 有了）之后一律放行，换号、退登改用离线
     * 都不再追问 —— 这是 PCL-CE、HMCL 的做法，也是这台机器上「验过」的含义。
     * 想再加强也不该在启动链上加卡口，该在登录那一步加强（见 [MicrosoftAuthClient] 的持有校验）。
     */
    val blocked: Boolean
        get() = requiresSignIn && needed && accountsLoaded && (!signedIn || !accountOwnsGame)

    /** 这个弹窗此刻该不该出现：要么是首次引导，要么是正式版被拦住。 */
    val show: Boolean get() = needed || blocked

    /** 能不能直接关掉继续用。正式版不行。 */
    val dismissible: Boolean get() = !requiresSignIn

    /** 弹窗标题。 */
    val title: String
        get() = when {
            requiresSignIn && blocked && signedIn -> "这个账号不能进正式版"
            requiresSignIn && blocked -> "先登录一个正版账号"
            requiresSignIn -> "欢迎使用鸭嘴兽启动器"
            else -> "欢迎使用鸭嘴兽·孵化版"
        }

    /**
     * 正文。
     *
     * 正式版讲清为什么不让跳过；有完整服务端的孵化版讲清要玩正版得先做什么；
     * 空壳包（[shell]）既不用正版也不用申请 ID，它缺的是服务地址，所以只讲那一件事。
     */
    val body: String
        get() = when {
            requiresSignIn && blocked && signedIn ->
                "这个微软账号没有 Minecraft 的持有记录。要么没买过（Xbox Game Pass 到期也算），" +
                    "要么游戏买在了另一个微软账号上。换一个买过游戏的账号登录，或者退出登录改用别的身份。"
            requiresSignIn && blocked ->
                "正式版要求用正版账号进游戏。验证只做一次：登录时启动器会向微软确认这个账号" +
                    "确实拥有 Minecraft，通过之后一直记着，之后打开不会再问。" +
                    "点下面的「去登录」，在「设置 → 账号」里填一个应用 ID，再用微软账号登录。"
            requiresSignIn -> "这一版要求用正版账号进游戏。验证只做一次，之后打开不会再问。"
            // 空壳包先讲：它既不用正版、也不用申请应用 ID，缺的是服务地址。
            // 把孵化版那段「不强制正版、离线也能玩」照抄进空壳包是误导 —— 用户照做之后
            // 打开下载页还是一片空白，会当成坏了。
            shell ->
                "这个包是孵化版的空壳：包里不带任何下载与搜索服务地址，版本清单、加载器、" +
                    "模组搜索都得先有个地址才拉得下来。" +
                    "去「设置 → 下载 → 服务地址」填一个镜像根（http:// 或 https:// 开头），" +
                    "填完立刻生效，以后一直用。没填之前，界面照旧能看、本机已有的实例照旧能改，" +
                    "只是下载页与模组搜索是空的 —— 那不是坏了。"
            else ->
                "孵化版不要求正版账号，离线也能进游戏。想用正版账号的话，得先自己申请一个" +
                    "微软应用 ID（微软不发放通用的，随安装包带出去的 ID 等于所有下载者共用一份别人的注册）。" +
                    "申请免费、大约三分钟，步骤在「设置 → 账号」第一张卡里，之后一直用。"
        }

    /**
     * 当前这个状态已经够进游戏了。
     *
     * 孵化版永远够；正式版要选着一个过得了所有权校验的微软账号才算。
     * 已经合格就不该再把人往登录页赶 —— 老版本升上来的用户（设置里 firstRunAt 还没写、
     * 但账号早登录过）正是这种情形。
     */
    val canProceed: Boolean get() = !requiresSignIn || (signedIn && accountOwnsGame)

    /**
     * 次要按钮上的字；null 表示不显示次要按钮。
     *
     * 空壳包不说「先随便看看」：这个包里本来就没什么可看的（下载与搜索都是空的），
     * 那句话会让人以为关掉就完事了。改成「稍后去填」，把它指回设置页。
     */
    val secondaryLabel: String?
        get() = when {
            !dismissible -> null
            shell -> "稍后去填"
            else -> "先随便看看"
        }

    /** 主按钮上的字。 */
    val primaryLabel: String
        get() = when {
            requiresSignIn && canProceed -> "开始使用"
            requiresSignIn -> "去登录"
            else -> "知道了"
        }
}
