package io.github.huang404yan.platypus.desktop

import java.awt.Font
import java.awt.Robot
import java.awt.event.KeyEvent
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * 无头验收：托盘图标装得上、卸得掉、菜单文字不变方块、左边不空一大块。
 *
 * 为什么需要这个：单测只能证明 trayEnabled 这一格存得住，「点 × 会不会真退出」
 * 那条链路在 Main.kt 的 Composable 里，测不到。但链路的关键一环是
 * [TrayController] —— 开关关掉时必须调 dispose 把图标卸掉，否则用户关了开关、
 * 点了 ×，窗口还是被收进托盘，等于开关没生效。这一环是纯 AWT 调用，能无头验。
 *
 * 另外钉住三件容易回归但肉眼看不出的事：
 *  1. 菜单必须是 Swing 的 JPopupMenu。AWT 的 PopupMenu 在 Windows 上不吃自定义
 *     字体，中文全变方块 □（用户 2026-11-22 的截图），换字体怎么试都没用。
 *  2. 每一项都得挂上能显示中文的字体 —— 只挂菜单本体、不挂菜单项时，渲染走的是
 *     菜单项自己那份。
 *  3. 菜单项左边那几道缩进必须压掉。Windows 外观默认留 49 像素（勾选槽 + 箭头槽），
 *     用户看到的就是「文字偏右、左边一大块空」。
 *
 * 退出码：0 通过；1 断言失败；2 这台机器没有托盘可用（跳过，不算失败）。
 *
 * opened / exited 两个计数现在没有断言用到 —— 菜单里的项与图标都要真被点到才会触发，
 * 无头环境点不了，留着是为了让 [TrayController] 的构造参数语义一目了然。
 */
fun main() {
    var exit = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isBlank()) "" else " :: " + detail))
        if (!ok) exit = 1
    }

    // ① 字体先验：托盘菜单与气泡里的中文必须每一格都能显示。
    //    用菜单与气泡里真实出现过的字，不是随便挑两个。
    val sample = "打开鸭嘴兽退出收进通知区彻底"
    val font = TRAY_MENU_FONT
    check(
        "托盘菜单字体能显示中文",
        font.canDisplayUpTo(sample) == -1,
        "font=" + font.family + " " + font.name + " canDisplayUpTo=" + font.canDisplayUpTo(sample),
    )
    check("托盘菜单字体不是写死的物理字族", font.name == Font.DIALOG, font.name)

    // ② 图标资源：必须是专门给托盘做的透明底小图，不能退回窗口那两张不透明原画。
    //    原画是整块深青绿背景，缩到 16 像素就是一坨暗方块（用户说的「图标还是有些问题」）。
    for (name in listOf("/icon/tray-baby.png", "/icon/tray-adult.png")) {
        val bytes = TrayController::class.java.getResourceAsStream(name)?.use { it.readBytes() }
        if (bytes == null) {
            check("读得到 " + name, false, "getResourceAsStream 返回 null")
            continue
        }
        val img = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        check("读得到 " + name, img != null, bytes.size.toString() + "B")
        if (img != null) {
            check(
                name + " 是带透明通道的 " + img.width + "x" + img.height,
                img.colorModel.hasAlpha() && img.width == 32 && img.height == 32,
                "hasAlpha=" + img.colorModel.hasAlpha() + " size=" + img.width + "x" + img.height,
            )
        }
    }

    var opened = 0
    var exited = 0
    val controller = TrayController(onOpen = { opened++ }, onExit = { exited++ })

    // ③ 菜单本身：必须是 Swing 的 JPopupMenu，且每一项都挂着能显示中文的字体。
    //    这一组不需要通知区，任何机器上都能跑完 —— 方块 □ 的回归正是发生在这里。
    val menu = controller.buildMenu()
    check("菜单是 Swing 的 JPopupMenu（AWT 那套中文必方块）", menu is JPopupMenu, menu.javaClass.name)
    check("菜单本体字体能显示中文", menu.font.canDisplayUpTo(sample) == -1, menu.font.name)
    val items = menu.components.filterIsInstance<JMenuItem>()
    check("菜单里有两个可点的项", items.size == 2, "找到 " + items.size + " 个：" + items.joinToString("/") { it.text })
    check(
        "两个菜单项的字体都能显示中文",
        items.isNotEmpty() && items.all { it.font.canDisplayUpTo(sample) == -1 },
        items.joinToString("/") { it.text + "=" + it.font.name },
    )
    check(
        "菜单项文字就是用户看到的那两句",
        items.map { it.text } == listOf("打开鸭嘴兽", "退出"),
        items.joinToString("/") { it.text },
    )

    // ④ 左边不能留一大块空：菜单项宽度减去文字宽度，剩下的两侧留白。
    //    Windows 外观不压缩进时这一项是 49（勾选槽 22 + 间隔 9 + 文本偏移），
    //    压缩后是 15 上下。用 30 当闸门：真回归到 49 一定挂。
    val first = items.firstOrNull()
    if (first != null) {
        val textWidth = first.getFontMetrics(first.font).stringWidth(first.text)
        val leftover = first.preferredSize.width - textWidth
        check("菜单项左侧缩进已压掉", leftover in 0..30, "leftover=" + leftover + " itemW=" + first.preferredSize.width + " textW=" + textWidth)
    }

    if (!java.awt.SystemTray.isSupported()) {
        // 没有通知区的机器（精简系统、部分远程会话）本来就装不上，
        // 产品在那种机器上退回「关窗口就是退出」，这里同样不算失败。
        println("SKIP 这台机器没有托盘，剩下的断言跳过")
        println("TRAY SMOKE SKIPPED")
        exitProcess(2)
    }

    // ⑤ 没装之前：installed 必须是 false。调用方靠它决定「× 是退出还是收起」。
    check("没装之前 installed=false", !controller.installed)

    // ⑥ 用发行时那张透明底 png 装上（这才是线上走的那条路）。
    val png: ByteArray? = TrayController::class.java.getResourceAsStream("/icon/tray-baby.png")
        ?.use { it.readBytes() }
    if (png == null) {
        println("FAIL 读不到 /icon/tray-baby.png，装配路径与发行包不一致")
        println("TRAY SMOKE FAILED")
        exitProcess(1)
    }
    check("装上托盘图标", controller.install(png, "鸭嘴兽启动器"), "png=" + png.size + "B")
    check("装上之后 installed=true", controller.installed)

    // ⑦ 再装一次不该重复添加（install 幂等，否则会剩两个图标格子）。
    check("重复 install 仍然成功", controller.install(png, "鸭嘴兽启动器"))
    check("重复 install 之后 installed 仍为 true", controller.installed)

    // ⑧ 开关关掉那条路：dispose 之后 installed 必须回到 false。
    //    这是「关了就是彻底关闭」的判据 —— 图标摘不掉，× 就还会被收起来。
    controller.dispose()
    check("dispose 之后 installed=false", !controller.installed)

    // ⑨ 再打开能装回来（开关来回拨两次不能坏）。
    check("关掉之后能重新装上", controller.install(png, "鸭嘴兽启动器"))
    check("重新装上之后 installed=true", controller.installed)

    // ⑩ 真弹一次菜单：能弹出来、Esc 能收。
    //    这两条在旧版（1×1 JWindow 当锚点）是不成立的 —— Esc 与点别处都关不掉，
    //    只有点中菜单项才收（2026-11-22 Robot 实测 escClosed=false）。换成有主的
    //    JDialog 当锚点后才成立，所以必须钉住，别让谁把它改回去。
    if (java.awt.GraphicsEnvironment.isHeadless()) {
        println("SKIP 无头环境，真弹菜单与 Esc 这两条跳过")
    } else {
        controller.showMenu()
        val shown = waitFor(3000) { menuVisible(controller) }
        check("右键菜单能真的弹出来", shown)
        if (shown) {
            val size = menuSize(controller)
            check("弹出后的菜单不是 0 尺寸", size != null && size.width > 40 && size.height > 20, size.toString())
            val closed = pressEsc() && waitFor(3000) { !menuVisible(controller) }
            check("按 Esc 能收起菜单（锚点必须是有主的 JDialog）", closed)
        }
    }

    // ⑪ 气泡与镜像不该在没图标时炸：dispose 之后这两条要静默返回。
    controller.dispose()
    controller.hintHidden(1)
    controller.mirror("标题", "正文")
    check("没有图标时弹气泡不抛异常", true)

    println(if (exit == 0) "TRAY SMOKE OK" else "TRAY SMOKE FAILED")
    exitProcess(exit)
}

/** 菜单是不是正显示着。Swing 的状态只能在 EDT 上读。 */
private fun menuVisible(controller: TrayController): Boolean {
    var visible = false
    SwingUtilities.invokeAndWait { visible = controller.currentMenu?.isVisible == true }
    return visible
}

/** 弹出后菜单的首选尺寸。断言「不是 0 尺寸」用，防止重型弹层被裁没。 */
private fun menuSize(controller: TrayController): java.awt.Dimension? {
    var size: java.awt.Dimension? = null
    SwingUtilities.invokeAndWait { size = controller.currentMenu?.preferredSize }
    return size
}

/** 按一下 Esc。Robot 动作会真的发给当前前台窗口，所以不能放在无头环境里跑。 */
private fun pressEsc(): Boolean = runCatching {
    val robot = Robot()
    robot.keyPress(KeyEvent.VK_ESCAPE)
    robot.keyRelease(KeyEvent.VK_ESCAPE)
    true
}.getOrDefault(false)

/** 轮询等某个条件成立，最多等 timeoutMs。GUI 事件异步，等不到就是真没发生。 */
private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (cond()) return true
        Thread.sleep(50)
    }
    return cond()
}
