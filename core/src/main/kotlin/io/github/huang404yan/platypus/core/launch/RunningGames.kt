package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.instanceKey

/**
 * 启动器这轮运行里还在跑的游戏。
 *
 * 为什么要有这么一份东西：启动页的 `LaunchPhase` 是那一页自己的状态，页面一换就没了。可「某个版本正在跑」是一件**跟页面无关的事实**：
 * 用户切去下载页装个模组、再切回来，进程还在，界面却显示成「没在跑」—— 于是他再点一次启动，
 * 盘上就多了一个 java 进程，共用同一份存档。PCL 的版本列表、Prism 的实例页都显示运行状态，
 * 道理就在这里。
 *
 * 记录的口径是「从这个启动器起来的进程」，不是「盘上所有 java」：用户自己在别处起的游戏
 * 不在这里，也就不会被这里的「结束游戏」误杀。
 *
 * [onChange] 用来通知界面刷新：它本身不是 Compose 的 state，界面要自己挂一个计数器。
 * 回调在调用方线程上同步执行，只该做「标脏」这种极短的事。
 */
class RunningGames(private val onChange: () -> Unit = {}) {

    /** 一条正在跑的记录。 */
    data class Entry(
        /** 与 [instanceKey] 同一口径，用来把界面上的实例对回这条记录。 */
        val key: String,
        val name: String,
        val gameVersion: String,
        val directory: String,
        val pid: Long,
        val startedAt: Long,
        val handle: GameHandle,
    )

    private val lock = Any()
    /** 用 LinkedHashMap 保「先起的在前」，界面上列出来时顺序不会每帧乱跳。 */
    private val entries = LinkedHashMap<String, Entry>()

    /** 记一条。同一个 [Entry.key] 再记一次等于替换（重启同一个版本）。 */
    fun put(entry: Entry) {
        synchronized(lock) { entries[entry.key] = entry }
        onChange()
    }

    /** 进程自己退出、用户点停止、或启动失败时调用。不存在就什么都不做。 */
    fun remove(key: String) {
        val removed = synchronized(lock) { entries.remove(key) }
        if (removed != null) onChange()
    }

    /**
     * 把已经不活了的记录摘掉，返回摘掉的那些。
     *
     * 正常路径是进程退出时发的那条 [LaunchEvent.Exited] 自己来摘，但事件会丢：
     * 用户在外面把 java 杀了、启动器崩过一次、或者事件在别的线程上没送到。
     * 事件一丢，界面就永远显示「运行中」，而按钮已经变成「结束这个」—— 用户点下去
     * 只会得到「进程早没了」的静默失败。所以每次重扫 / 切回启动页时对一次账。
     *
     * [GameHandle.isAlive] 抛异常时按「还活着」算：宁可多显示一行运行中，
     * 也不能因为一次探测失败就把正在跑的进程从表里抹掉（那样同实例就能被起第二份）。
     */
    fun pruneDead(): List<Entry> {
        val dead = synchronized(lock) {
            val gone = entries.values.filter { entry -> runCatching { entry.handle.isAlive() }.getOrDefault(true).not() }
            gone.forEach { entries.remove(it.key) }
            gone
        }
        if (dead.isNotEmpty()) onChange()
        return dead
    }

    fun get(key: String): Entry? = synchronized(lock) { entries[key] }

    fun isRunning(key: String): Boolean = synchronized(lock) { entries.containsKey(key) }

    /** 当前在跑的全部记录，按「先起的在前」。 */
    fun all(): List<Entry> = synchronized(lock) { entries.values.toList() }

    val size: Int get() = synchronized(lock) { entries.size }

    /** 从一条实例算出记录用的键，与 [instanceKey] 同口径。 */
    companion object {
        fun keyOf(instance: Instance): String = instanceKey(instance)
    }
}
