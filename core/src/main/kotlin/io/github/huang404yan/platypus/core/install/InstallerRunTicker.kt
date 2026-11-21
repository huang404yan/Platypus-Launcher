package io.github.huang404yan.platypus.core.install

/**
 * 把安装器那几千上万行日志压成「每秒一条」的进度。
 *
 * Forge / NeoForge 的官方 installer 是外部 java 子进程，不报总进度，只能数它打了多少行。
 * 但一分钟几百行的输出如果逐行推给界面，等于每秒几百次状态更新与重组：界面一直在抖，
 * 而且每条 detail 都换一遍、根本读不过来（实测 Forge 47.4.28 装一次 29777 行 / 116 秒）。
 *
 * 这里做两件事：**按时间节流，但行数一笔不漏地数**；**把最后一行留着**，
 * 好在进度条上看到 installer 此刻在干什么。日志本体由 [InstallerProcess] 全量落盘，
 * 要查细节看那个文件 —— 不要指望在进度条上读日志。
 *
 * 时间源可以注入，单测才能用假时钟把「一秒」走完而不用真的 sleep。
 */
internal class InstallerRunTicker(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val startedAt: Long = clock()

    /** 上一次真正推给界面的时刻；还没推过时是 [UNSET]。 */
    private var lastPushAt: Long = UNSET

    /** 收过多少行。取消后重来会新建一个 ticker，所以不用清零。 */
    var lines: Int = 0
        private set

    /** 最后收到的那一行（已 trim）。 */
    private var lastLine: String = ""

    /**
     * 收到一行日志。轮到该推了返回当前快照，否则返回 null（调用方什么都不用做）。
     *
     * 第一行一定返回：不然跑起来头一秒界面没有任何变化，用户又要怀疑没开始。
     */
    fun onLine(line: String): Snapshot? {
        lines += 1
        lastLine = line
        val now = clock()
        // 先判 lines > 1 再相减：lastPushAt 初值是 Long.MIN_VALUE，减它会溢出。
        if (lines > 1 && now - lastPushAt < intervalMillis) return null
        lastPushAt = now
        val seconds = ((now - startedAt) / 1000L).toInt().coerceAtLeast(0)
        return Snapshot(lines = lines, seconds = seconds, lastLine = lastLine)
    }

    /** 一次可以推给界面的内容。 */
    data class Snapshot(val lines: Int, val seconds: Int, val lastLine: String) {

        /** 进度条上的那行字。行数与秒数的措辞与改造前一致，用户认得。 */
        val summary: String
            get() {
                val base = "已跑 " + lines + " 行 · 已用 " + seconds + " 秒"
                return if (lastLine.isBlank()) base else base + " · " + lastLine.take(LAST_LINE_CHARS)
            }

        companion object {

            /**
             * 一行日志截到这么长：状态行只有一行的高度，再长就被窗口边缘切掉，
             * 白白多一次重排却看不到字。
             */
            const val LAST_LINE_CHARS: Int = 96
        }
    }

    companion object {

        /** 一秒一跳：再快就是刷屏，再慢会让人以为卡住。 */
        const val DEFAULT_INTERVAL_MILLIS: Long = 1000L

        private const val UNSET: Long = Long.MIN_VALUE
    }
}
