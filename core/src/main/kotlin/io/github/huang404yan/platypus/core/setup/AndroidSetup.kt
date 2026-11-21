package io.github.huang404yan.platypus.core.setup

import io.github.huang404yan.platypus.core.settings.LauncherSettings

/**
 * 安卓端首次打开时要先把哪几件事准备好。
 *
 * 手机上跟电脑不一样：电脑上游戏目录里有一份 java.exe 就能开始，手机上得由启动器
 * 自己下运行时（应用私有目录、不可执行外部存储）、自己下图形转译库（AGP 不打包的
 * `.so`），否则第一个实例建出来也点不动。这三件事分散在「设置 → Java」「启动 → 图形后端」
 * 「关于 → 检查更新」三个地方，第一次打开的人不会自己去找。
 *
 * 所以有一页引导，把三件事摆在一起、各自带一个按钮。这一层只放**纯判断**：走没走过、
 * 还差几件、一句话怎么讲。文件在不在、ABI 支不支持，都是界面那边读盘得出的，
 * 这里不碰文件系统，好写单测也好在桌面端复用同一套文案。
 */
object AndroidSetup {

    /** [LauncherSettings.androidGuideAt] 里表示「还没走过」的值。 */
    const val NOT_DONE: Long = 0L

    /**
     * 这台设备是不是还没走过安卓的初次引导。
     *
     * 只看设置里的那一格。游戏目录换掉会再走一遍 —— 与 [io.github.huang404yan.platypus.core.edition.FirstRun]
     * 同一个性质，因为设置文件是跟着游戏目录走的。
     */
    fun needed(settings: LauncherSettings): Boolean = settings.androidGuideAt <= NOT_DONE

    /** 走完引导时要写进设置的时间戳。与 [io.github.huang404yan.platypus.core.edition.FirstRun.doneAt] 同义。 */
    fun doneAt(now: Long): Long = if (now > 0L) now else 1L

    /**
     * 按「哪几件已经就绪」算出现在该说什么。
     *
     * [ready] 由界面传进来（它才知道盘上有没有那些文件）。未列出的项一律当没准备好 ——
     * 少报一件的代价是多走一步引导，多报一件的代价是用户点「开始使用」之后才发现起不来。
     */
    fun progress(ready: Collection<SetupItem>): SetupProgress {
        val done = ready.toSet()
        return SetupProgress(items = SetupItem.entries, ready = done)
    }
}

/**
 * 引导页上的三件事。
 *
 * 顺序就是界面上的顺序：先有 Java（没有它连启动参数都拼不出来），再有图形后端
 * （没有它游戏窗口挂不上去），最后是检查更新（前两件都好了，才值得看一眼要不要新版）。
 *
 * 存 id 字符串而不是序号：这一层以后加第四项（比如解压原版 natives）时，
 * 界面里记着的那一份不会错位。
 */
enum class SetupItem(val id: String, val label: String) {
    /** 应用私有目录里的 JRE（见 [io.github.huang404yan.platypus.core.runtime.AndroidJavaCatalog]）。 */
    JAVA("java", "Java 运行时"),

    /** 下载到实例 natives 目录里的 GL4ES / Zink 那几份 `.so`。 */
    GRAPHICS("graphics", "图形后端"),

    /** 只是查一次有没有新版；查到也不下载 —— 安卓上装 apk 得用户自己点。 */
    UPDATE("update", "检查更新"),
}

/**
 * 一次引导的进度快照。
 *
 * 「还差几件、差的是哪几件、能不能点开始」全在这里算好，界面里不再写第二份 if ——
 * 与 [io.github.huang404yan.platypus.core.edition.FirstRunState] 同一个理由。
 */
data class SetupProgress(
    /** 全部三件，顺序固定。 */
    val items: List<SetupItem>,
    /** 已经就绪的那些。 */
    val ready: Set<SetupItem>,
) {
    /** 还没好的那些，按 [items] 的顺序。 */
    val remaining: List<SetupItem> get() = items.filterNot { it in ready }

    /** 三件都好了。**只有这个为真才让进主界面**的按钮可点。 */
    val allReady: Boolean get() = remaining.isEmpty()

    /** 已就绪的件数，给「2/3」这种进度用。 */
    val doneCount: Int get() = items.count { it in ready }

    /** 标题下面那一行。 */
    val headline: String
        get() = when {
            allReady -> "都准备好了，点下面的按钮进去。"
            doneCount == 0 -> "第一次用，先把三件事办好；下载会自己接着上次没下完的部分继续。"
            else -> "还有 " + remaining.size + " 件没好：" + remaining.joinToString("、") { it.label } +
                "。没好的也能先进去，只是那一项在主界面里还会提醒一次。"
        }

    /** 底部主按钮的字；三件没齐时仍然能点，但要让人看清「现在进去会缺东西」。 */
    val primaryLabel: String get() = if (allReady) "开始使用" else "先进去看看"
}

