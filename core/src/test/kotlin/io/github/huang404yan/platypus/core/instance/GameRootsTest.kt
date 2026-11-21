package io.github.huang404yan.platypus.core.instance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 安卓默认游戏目录的选法。
 *
 * 这里全部用假的 storage 根，不碰真实外部存储：判断只看路径形状，
 * 所以注入一个「哪些路径算存在」的函数就够了。
 */
class GameRootsTest {

    private val storage = File("/storage/emulated/0")
    private val home = File(storage, "Platypus")
    private val ours = File(home, "game")
    private val legacy = File(storage, ".minecraft")

    @Test
    fun `什么都没装过时用启动器自己的顶层目录`() {
        val picked = GameRoots.androidGameDir(storage) { false }
        assertEquals(ours, picked)
        assertEquals("/storage/emulated/0/Platypus/game", picked.path.replace('\\', '/'))
    }

    @Test
    fun `老目录里已有东西时沿用老目录，不静默搬家`() {
        val picked = GameRoots.androidGameDir(storage) { it == legacy }
        assertEquals(legacy, picked)
    }

    @Test
    fun `多个老目录都在时按离得近的顺序取第一个`() {
        val pojav = File(storage, "games/PojavLauncher/.minecraft")
        val picked = GameRoots.androidGameDir(storage) { it == legacy || it == pojav }
        assertEquals(legacy, picked)
    }

    @Test
    fun `认得出自己的目录与两种老位置`() {
        assertEquals(GameRoots.AndroidSource.OUR_HOME, GameRoots.androidSourceOf(storage, ours))
        assertEquals(GameRoots.AndroidSource.LEGACY_DOT_MINECRAFT, GameRoots.androidSourceOf(storage, legacy))
        assertEquals(
            GameRoots.AndroidSource.POJAV,
            GameRoots.androidSourceOf(storage, File(storage, "games/PojavLauncher/.minecraft")),
        )
    }

    @Test
    fun `别的路径一律报自定义，不猜是谁建的`() {
        val custom = File(storage, "下载/我的世界")
        assertEquals(GameRoots.AndroidSource.CUSTOM, GameRoots.androidSourceOf(storage, custom))
    }

    @Test
    fun `尾部有无斜杠都认成同一个目录`() {
        assertTrue(GameRoots.isOurAndroidDir(storage, File(ours.path + "/")))
        assertTrue(GameRoots.isOurAndroidDir(storage, File(ours.path + "/./")))
    }
}
