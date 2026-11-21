package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.instanceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「哪个版本正在跑」这张表。
 *
 * 界面刷新靠 [RunningGames] 的回调，所以每条用例同时盯着「表里的内容对不对」和
 * 「该通知的时候有没有通知」—— 少通知一次，界面就会一直显示成没在跑。
 */
class RunningGamesTest {

    private class FakeHandle(
        override val pid: Long? = 1L,
        /** 直接指定「还活着吗」，用来模拟「进程在外面被杀了，但事件没送到」。 */
        var alive: Boolean = true,
        /** 模拟探测本身失败（例如句柄已失效）。 */
        var probeThrows: Boolean = false,
    ) : GameHandle {
        var stopped = false
        override fun isAlive(): Boolean {
            if (probeThrows) throw IllegalStateException("handle gone")
            return alive && !stopped
        }
        override suspend fun stop(graceMillis: Long): Boolean {
            stopped = true
            return true
        }
    }

    private fun instance(id: String, dir: String = "/mc/versions/" + id) = Instance(
        id = id,
        name = id,
        gameVersion = "1.20.1",
        directory = dir,
    )

    private fun entry(instance: Instance, pid: Long = 1L) = RunningGames.Entry(
        key = RunningGames.keyOf(instance),
        name = instance.name,
        gameVersion = instance.gameVersion,
        directory = instance.directory,
        pid = pid,
        startedAt = 0L,
        handle = FakeHandle(pid),
    )

    @Test
    fun `记一条就能按实例键查回来`() {
        val games = RunningGames()
        val a = instance("a")
        games.put(entry(a, pid = 100))
        assertEquals(100L, games.get(RunningGames.keyOf(a))?.pid)
        assertTrue(games.isRunning(instanceKey(a)))
        assertEquals(1, games.size)
    }

    @Test
    fun `同一个实例再起一次是替换不是新增`() {
        val games = RunningGames()
        val a = instance("a")
        games.put(entry(a, pid = 100))
        games.put(entry(a, pid = 200))
        assertEquals(1, games.size)
        assertEquals(200L, games.get(RunningGames.keyOf(a))?.pid)
    }

    @Test
    fun `移除后查不到 也不报错`() {
        val games = RunningGames()
        val a = instance("a")
        games.put(entry(a))
        games.remove(RunningGames.keyOf(a))
        assertNull(games.get(RunningGames.keyOf(a)))
        assertFalse(games.isRunning(RunningGames.keyOf(a)))
        // 再删一次：进程退出与用户点停止可能都走到这条路上，不能因为「已经没了」而抛。
        games.remove(RunningGames.keyOf(a))
        assertEquals(0, games.size)
    }

    @Test
    fun `all 保持先起的在前`() {
        val games = RunningGames()
        val a = instance("a")
        val b = instance("b")
        val c = instance("c")
        games.put(entry(a))
        games.put(entry(c))
        games.put(entry(b))
        assertEquals(listOf("a", "c", "b"), games.all().map { it.name })
    }

    @Test
    fun `增删都会通知 空删不通知`() {
        var notified = 0
        val games = RunningGames { notified++ }
        val a = instance("a")
        games.put(entry(a))
        assertEquals(1, notified)
        games.remove(RunningGames.keyOf(a))
        assertEquals(2, notified)
        // 删一条不存在的：什么都没变，界面不该白重组一次。
        games.remove("不存在")
        assertEquals(2, notified)
    }

    @Test
    fun `进程已经死了的记录会被摘掉 活着的留着`() {
        val games = RunningGames()
        val alive = entry(instance("alive"))
        val dead = entry(instance("dead")).let {
            // 换一个已经不活的句柄：模拟「外面把 java 杀了，Exited 没送到」。
            it.copy(handle = FakeHandle(alive = false))
        }
        games.put(alive)
        games.put(dead)
        val pruned = games.pruneDead()
        assertEquals(listOf("dead"), pruned.map { it.name })
        assertEquals(1, games.size)
        assertTrue(games.isRunning(RunningGames.keyOf(instance("alive"))))
        assertFalse(games.isRunning(RunningGames.keyOf(instance("dead"))))
    }

    @Test
    fun `对账时一条都没死就不通知界面`() {
        var notified = 0
        val games = RunningGames { notified++ }
        games.put(entry(instance("a")))
        assertEquals(1, notified)
        assertEquals(emptyList(), games.pruneDead())
        assertEquals(1, notified)
    }

    @Test
    fun `探测失败按还活着算 不能把正在跑的抹掉`() {
        val games = RunningGames()
        val shaky = entry(instance("shaky")).copy(handle = FakeHandle(probeThrows = true))
        games.put(shaky)
        assertEquals(emptyList(), games.pruneDead())
        // 抹掉的后果是同实例能被起第二份，共用存档目录 —— 比多显示一行「运行中」严重得多。
        assertEquals(1, games.size)
    }

    @Test
    fun `同名不同目录是两个不同的实例`() {
        val games = RunningGames()
        val one = instance("same", dir = "/mc1/versions/same")
        val two = instance("same", dir = "/mc2/versions/same")
        games.put(entry(one, pid = 1))
        games.put(entry(two, pid = 2))
        assertEquals(2, games.size)
        assertEquals(1L, games.get(RunningGames.keyOf(one))?.pid)
        assertEquals(2L, games.get(RunningGames.keyOf(two))?.pid)
    }
}
