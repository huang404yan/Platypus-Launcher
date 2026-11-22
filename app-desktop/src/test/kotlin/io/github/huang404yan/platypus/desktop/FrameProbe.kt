package io.github.huang404yan.platypus.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.huang404yan.platypus.ui.App
import java.awt.Canvas
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.io.File
import kotlin.system.exitProcess

/**
 * 帧时间探针：无头渲染整棵界面树，量「切一页要多久才画得出来」。
 *
 * 为什么用 [ImageComposeScene] 而不是开窗口：
 *  - 不需要交互式桌面会话，无显示器也能跑；
 *  - 量到的是「组合 + 布局 + 绘制」这一整段，正是界面卡不卡的那一段，
 *    不受窗口管理器与垂直同步干扰，数字可重复。
 * 量不到的是「提交到显示器」那一段，所以这里的数字是**乐观下限**：
 * 真实观感只会比它更差，不会更好。
 *
 * 切页用真的 Ctrl+1..5 走 [KeyboardFocusManager] 派发 —— 与用户按键同一条路径，
 * 因此顺带验了快捷键分发器在无窗口环境里确实装上了。
 *
 * 三个阶段分开跑，因为三者量到的东西不一样：
 *  冷启动 —— 开程序到看见首屏，含全量类加载；
 *  冷进页 —— 本次运行第一次切到某页，含那一页自己的类加载与首次图片解码；
 *  热进页 —— 页已经进过了，再切过去只付页面本身的重组与布局代价。
 * 优化要盯着热的那个数：冷的那个靠预建与预热抹平，改代码用处不大。
 *
 * -DframePrebuild=true 先把每页走一遍再量冷进页：用来验证「启动时预建」值不值得。
 *
 * -DframePasses=N 连跑 N 遍（每遍新建一个场景），用来判断「冷进页那一百多毫秒
 * 是一次性成本（类加载 / 首次 JIT）还是每次都要付。第一遍是真正的冷，第二遍起
 * 类已经加载完，两者之差就是一次性成本。
 *
 * 只由 gradle :app-desktop:frameProbe 调用，不进发行包。
 *
 * 参数：<临时游戏目录>
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { error("缺少参数：临时目录") })
    dir.mkdirs()
    val services = DesktopServices.create(dir)
    val passes = System.getProperty("framePasses")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    repeat(passes) { index ->
        println()
        println("=== pass " + (index + 1) + " of " + passes + " ===")
        measurePass(dir, services)
    }
    exitProcess(0)
}

private fun measurePass(dir: File, services: DesktopServices) {
    val scene = ImageComposeScene(
        width = WIDTH,
        height = HEIGHT,
        density = Density(1f),
        coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined,
    ) {
        App(initialGameRoot = dir.absolutePath, backend = services)
    }

    var clockNanos = 0L
    /** 渲染一帧，返回耗时（微秒）。时间按 60Hz 往前走，动画才会真的跑完。 */
    fun frame(): Long {
        clockNanos += FRAME_NANOS
        val t0 = System.nanoTime()
        scene.render(clockNanos)
        return (System.nanoTime() - t0) / 1000L
    }

    val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val canvas = Canvas()
    // 页名用英文：控制台是 GBK，中文一律乱码，日志就没法 grep 了。
    val pages = listOf(
        KeyEvent.VK_2 to "download",
        KeyEvent.VK_3 to "link",
        KeyEvent.VK_4 to "settings",
        KeyEvent.VK_5 to "more",
        KeyEvent.VK_1 to "launch",
    )

    // ① 冷启动：第一帧就是「开程序到看见首屏」。
    val cold = frame()
    println("cold-start-frame " + ms(cold))

    // 冷启动那一帧之后先空跑几帧：首屏刚组合完时立刻切页，Compose 1.12.2 里
    // RectManager 偶发 "LayoutNode N not found in RectList"（探针实测两次一次）。
    // 空跑几帧让它稳定下来再切，不改变「第一次进这一页」这个语义。
    repeat(STABILIZE_FRAMES) { frame() }

    // ①.5 预建（-DframePrebuild=true 才跑）：先把每页走一遍再量「冷进页」。
    //     量出来的 cold 列就从「本次运行第一次进这一页」变成「预建之后第一次进」，
    //     两次运行一减，就是预建能省下的那部分 —— 这是判断「值不值得在启动时
    //     偷偷把页面组一遍」的唯一依据，靠猜没意义。
    if (System.getProperty("framePrebuild") == "true") {
        val t0 = System.nanoTime()
        for ((key, _) in pages) {
            press(kfm, canvas, key)
            repeat(3) { frame() }
        }
        for ((key, _) in pages) {
            press(kfm, canvas, awayFrom(key))
            repeat(3) { frame() }
        }
        println("prebuild " + ms((System.nanoTime() - t0) / 1000L) + " (walked all pages once)")
    }

    // ② 冷进页：本次运行第一次切到每页。每页只量一次，量完就离开。
    val coldFirst = LinkedHashMap<String, Long>()
    for ((key, name) in pages) {
        press(kfm, canvas, awayFrom(key))
        repeat(2) { frame() }
        press(kfm, canvas, key)
        coldFirst[name] = frame()
        repeat(SETTLE_FRAMES) { frame() }
    }

    // ③ 预热。原来只空跑 60 帧，那只能热到首屏那一条路；切页要走的是另一条
    //    （AnimatedContent 换新子树 + 各页自己的 LaunchedEffect），没走过的话
    //    第一次走的代价会落进下一段的第一轮，把中位数往上拽。
    repeat(WARMUP_FRAMES) { frame() }
    repeat(WARM_WALK) {
        for ((key, _) in pages) {
            press(kfm, canvas, awayFrom(key))
            repeat(2) { frame() }
            press(kfm, canvas, key)
            repeat(4) { frame() }
        }
    }

    // ④ 热进页：每页量 ROUNDS 轮，取中位数，另外记最小与最大。
    //    只报中位数会把抖动藏起来 —— 同一份代码连跑两次差 20ms 的情况真实存在
    //    （后台 IO 线程与 JIT 都在抢），所以把区间一起打出来，看结论前先看区间宽不宽。
    val warmFirst = LinkedHashMap<String, LongArray>()
    val warmSecond = LinkedHashMap<String, LongArray>()
    for ((key, name) in pages) {
        val firsts = LongArray(ROUNDS)
        val seconds = LongArray(ROUNDS)
        repeat(ROUNDS) { round ->
            // 先离开目标页，保证下一轮的「切过去」是真的从别的页切回来。
            press(kfm, canvas, awayFrom(key))
            // 离开之后多给几帧：实例一多，旧页拆掉整棵子树要走的节点也多，切太快时
            // Compose 1.12.2 的 RectManager 会偶发 "LayoutNode N not found in RectList"
            // （只在 15 个实例的目录上复现过，1 个实例时没崩过）。
            repeat(AWAY_FRAMES) { frame() }
            press(kfm, canvas, key)
            firsts[round] = frame()
            seconds[round] = frame()
            // 把切页动画走完，下一轮才是从静止状态起步。
            repeat(SETTLE_FRAMES) { frame() }
        }
        warmFirst[name] = firsts
        warmSecond[name] = seconds
    }
    // ⑤ 聚焦阶段：反复切回最慢的那一页，让 JFR 采样集中落在它的首帧上。
    //    只有加了 -PframeJfr 才跑：不加录音时这段纯属空转，白等十几秒。
    if (System.getProperty("frameFocus") == "true") {
        val focusKey = KeyEvent.VK_2
        repeat(FOCUS_ROUNDS) {
            press(kfm, canvas, KeyEvent.VK_1)
            repeat(2) { frame() }
            press(kfm, canvas, focusKey)
            frame()
            repeat(SETTLE_FRAMES) { frame() }
        }
    }
    scene.close()

    // 控制台是 GBK，中文 println 会变成乱码，所以输出一律 ASCII；说明放在注释里。
    println("page-switch first-frame cost (compose + layout + draw):")
    println("  page        cold       warm    [min-max]      next-frame   frames(60Hz)")
    for ((_, name) in pages) {
        val cold = coldFirst.getValue(name)
        val firsts = warmFirst.getValue(name).copyOfRange(0, ROUNDS)
        val warm = median(firsts)
        val next = median(warmSecond.getValue(name))
        println(
            "  " + name.padEnd(10) + ms(cold) + "  " + ms(warm) +
                "  [" + String.format("%5.1f", firsts.min() / 1000.0) + "-" +
                String.format("%5.1f", firsts.max() / 1000.0) + "]  " +
                ms(next) + "   " + String.format("%5.1f", warm.toDouble() / FRAME_NANOS * 1000.0),
        )
    }
    println("one frame = 16.7ms; over 1 frame is a visible hiccup, over 5 frames is a stutter.")
    println("warm is the median of " + ROUNDS + " rounds; the bracket is min-max, read it before trusting the median.")
}

private const val WIDTH = 1280
private const val HEIGHT = 820
private const val FRAME_NANOS = 16_666_667L
private const val WARMUP_FRAMES = 60

/** 冷启动之后先空跑几帧再开始切页，避开首屏刚组合完时的布局竞态。 */
private const val STABILIZE_FRAMES = 10

/** 每次「先离开目标页」之后空跑几帧，等旧页拆完再切过去（见热进页那段注释）。 */
private const val AWAY_FRAMES = 6
private const val ROUNDS = 9
/** 切页动画最长 Motion.BASE=180ms，按 60Hz 走 12 帧足够；多留一些余量。 */
private const val SETTLE_FRAMES = 20

/** 预热时整套页走几遍。一遍不够：第二遍时 JIT 才会按真实热度重编。 */
private const val WARM_WALK = 3

/** 聚焦阶段重复切页的次数：够 JFR 采到几百个样本，又不至于让探针跑上一分钟。 */
private const val FOCUS_ROUNDS = 150

/** 离开 [key] 那一页时按哪个键：启动页是 VK_1，从它出发就换成下载页。 */
private fun awayFrom(key: Int): Int = if (key == KeyEvent.VK_1) KeyEvent.VK_2 else KeyEvent.VK_1

private fun press(kfm: KeyboardFocusManager, canvas: Canvas, keyCode: Int) {
    kfm.dispatchEvent(
        KeyEvent(
            canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
            KeyEvent.CTRL_DOWN_MASK, keyCode, KeyEvent.CHAR_UNDEFINED,
        ),
    )
}

private fun median(values: LongArray): Long = values.sorted()[values.size / 2]

private fun ms(micros: Long): String = String.format("%6.1f ms", micros / 1000.0)
