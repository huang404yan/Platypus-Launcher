package io.github.huang404yan.platypus.core.edition

import io.github.huang404yan.platypus.core.auth.ClientIds
import io.github.huang404yan.platypus.core.settings.LauncherSettings

/**
 * 首次启动时该拦住什么、该提示什么。
 *
 * 两条通道的规矩不一样，而且这个差别是产品决定、不是实现细节：
 * - **正式版**（1.0.0 起）要求先登录一个正版账号。它发布的是编好的应用、源码不随包走；
 *   这种情况下允许离线进游戏，等于给盗版留一条明面上的入口。
 * - **孵化版**（0.x-preview.N）就是开源版，谁都能自己编一份、把这几行判断改掉。
 *   对着一份能随手改的源码强制登录没有意义，反而把「想先看看界面长什么样」的人挡在门外。
 *   所以它只弹一次窗，讲清「要玩正版得自己申请一个应用 ID」与怎么申请，然后放行。
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
     * [signedIn] 表示当前是否已经选着一个正版账号（accounts.json 里有 kind=microsoft 的记录，
     * 且设置里选中了它）。[accountsLoaded] 为假时一律当「还不知道」，此时正式版**不**拦 ——
     * 读账号是磁盘操作，第一帧还没读出来，按「没有账号」处理会让每个正式版用户先看到一堵墙，
     * 半秒后它又自己消失。
     */
    fun state(
        settings: LauncherSettings,
        edition: Edition = Edition.current(),
        signedIn: Boolean = false,
        accountsLoaded: Boolean = false,
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
) {
    /** 这一版要不要正版账号。 */
    val requiresSignIn: Boolean get() = edition == Edition.FORMAL

    /** 现在这一刻是否拦住用户：只有正式版会拦，且必须读完了账号、确实没有才拦。 */
    val blocked: Boolean
        get() = needed && requiresSignIn && accountsLoaded && !signedIn

    /** 能不能直接关掉继续用。正式版不行。 */
    val dismissible: Boolean get() = !requiresSignIn

    /** 弹窗标题。 */
    val title: String
        get() = when {
            requiresSignIn && blocked -> "先登录一个正版账号"
            requiresSignIn -> "欢迎使用鸭嘴兽启动器"
            else -> "欢迎使用鸭嘴兽·孵化版"
        }

    /** 正文：正式版讲清为什么不让跳过，孵化版讲清要玩正版得先做什么。 */
    val body: String
        get() = when {
            requiresSignIn && blocked ->
                "正式版要求用正版账号进游戏：登录一次之后一直记着，之后打开不会再问。" +
                    "点下面的「去登录」，在「设置 → 账号」里填一个应用 ID，再用微软账号登录。"
            requiresSignIn -> "这一版要求用正版账号进游戏。登录一次之后一直记着，之后打开不会再问。"
            else ->
                "孵化版不要求正版账号，离线也能进游戏。想用正版账号的话，得先自己申请一个" +
                    "微软应用 ID（微软不发放通用的，随安装包带出去的 ID 等于所有下载者共用一份别人的注册）。" +
                    "申请免费、大约三分钟，步骤在「设置 → 账号」第一张卡里，之后一直用。"
        }

    /** 次要按钮上的字；null 表示不显示次要按钮。 */
    val secondaryLabel: String? get() = if (dismissible) "先随便看看" else null

    /** 主按钮上的字。 */
    val primaryLabel: String get() = if (requiresSignIn) "去登录" else "知道了"
}
