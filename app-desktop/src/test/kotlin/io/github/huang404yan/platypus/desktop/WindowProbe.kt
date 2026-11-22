package io.github.huang404yan.platypus.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import io.github.huang404yan.platypus.ui.App
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * 真窗口探针：开一个真窗口跑同一棵树，用来判断「无头探针崩掉」是环境问题还是产品问题。
 *
 * 背景：无头探针（[FrameProbe]）在实例一多的目录上偶发
 * `IllegalArgumentException: LayoutNode N not found in RectList`，
 * 栈落在 Compose 自己的 RectManager 与 detach/measure 里。那套探针是
 * `ImageComposeScene` + `Dispatchers.Unconfined`，自己在一个循环里推帧 ——
 * 与真程序（AWT 事件循环 + skiko 帧时钟）不是同一条路径。
 *
 * 所以这里不量帧时间（真窗口量不准，也没必要），只回答一件事：同样的操作在真窗口里
 * 会不会崩。不崩 = 那是探针环境的竞态；崩了 = 产品侧要查。
 *
 * 只由 gradle :app-desktop:windowProbe 调用，不进发行包。
 *
 * 参数：<游戏目录> [轮数]
 */
private const val ROUNDS_DEFAULT = 20
private const val PAGE_DWELL_MS = 400L

private val failures = AtomicInteger(0)

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { error("missing argument: game dir") })
    dir.mkdirs()
    val rounds = args.getOrNull(1)?.toIntOrNull() ?: ROUNDS_DEFAULT

    // 后台线程里抛的异常（包括 AWT 事件线程派发时的异常）不会被 main 的 try 抓住，
    // 这里统一记一笔，避免「跑完了但中间崩过」被当成通过。
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        failures.incrementAndGet()
        println("UNCAUGHT in " + thread.name + ": " + error)
        error.printStackTrace()
    }

    println("gameDir = " + dir.absolutePath)
    println("rounds = " + rounds)

    val services = DesktopServices.create(dir)
    // ComposePanel 只许在 EDT 上建，从别的线程建会直接抛
    // IllegalStateException: ComposePanel should be created inside AWT Event Dispatch Thread。
    var panel: ComposePanel? = null
    var frame: JFrame? = null
    SwingUtilities.invokeAndWait {
        val p = ComposePanel()
        p.setContent {
            App(initialGameRoot = dir.absolutePath, backend = services)
        }
        val f = JFrame("window probe")
        f.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        f.contentPane.add(p)
        f.size = Dimension(1280, 820)
        f.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                p.dispose()
            }
        })
        panel = p
        frame = f
        f.isVisible = true
        f.toFront()
    }
    val ui = panel ?: error("panel not created")
    val window = frame ?: error("frame not created")

    try {
        // 首屏要组装 + 类加载，给足时间；这段不影响「会不会崩」的判定。
        Thread.sleep(2500)

        val robot = Robot()
        val bounds = window.bounds
        val captureRect = Rectangle(bounds.x, bounds.y, bounds.width, bounds.height)
        val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()

        var changed = 0
        var lastHash = 0
        val keys = listOf(KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5, KeyEvent.VK_1)
        for (round in 1..rounds) {
            val key = keys[(round - 1) % keys.size]
            // 真窗口里按键要派发到 EDT 才会走真实的分发器链。
            SwingUtilities.invokeLater {
                kfm.dispatchEvent(
                    KeyEvent(
                        window,
                        KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),
                        KeyEvent.CTRL_DOWN_MASK,
                        key,
                        KeyEvent.CHAR_UNDEFINED,
                    ),
                )
            }
            Thread.sleep(PAGE_DWELL_MS)
            val image = robot.createScreenCapture(captureRect)
            val hash = image.hashCode()
            if (round == 1) lastHash = hash else if (hash != lastHash) changed++
            lastHash = hash
            println("round " + round + " key=" + key + " hash=" + hash)
        }

        println("screen changed in " + changed + "/" + (rounds - 1) + " rounds")
        println("uncaught exceptions = " + failures.get())
    } catch (e: Throwable) {
        failures.incrementAndGet()
        println("MAIN FAILED: " + e)
        e.printStackTrace()
    } finally {
        runCatching {
            SwingUtilities.invokeAndWait {
                ui.dispose()
                window.isVisible = false
                window.dispose()
            }
        }
    }

    val exit = if (failures.get() == 0) 0 else 1
    println(if (exit == 0) "WINDOW PROBE OK" else "WINDOW PROBE FAILED")
    exitProcess(exit)
}
