package io.github.huang404yan.platypus.desktop

import java.awt.Font
import kotlin.system.exitProcess

/**
 * 无头验收：托盘开关能不能真的「装上 / 卸掉」，菜单文字会不会变方块。
 *
 * 为什么需要这个：单测只能证明 trayEnabled 这一格存得住，「点 × 会不会真退出」
 * 那条链路在 Main.kt 的 Composable 里，测不到。但链路的关键一环是
 * [TrayController] —— 开关关掉时必须调 dispose 把图标卸掉，否则用户关了开关、
 * 点了 ×，窗口还是被收进托盘，等于开关没生效。这一环是纯 AWT 调用，能无头验。
 *
 * 另外钉住字体：AWT 弹出菜单默认字族在 Windows 上没有汉字，中文全变方块 □
 *（用户 2026-11-22 的截图）。这条只能用 canDisplayUpTo 断言，肉眼看截图看不出回归。
 *
 * 退出码：0 通过；1 断言失败；2 这台机器没有托盘可用（跳过，不算失败）。
 *
 * opened / exited 两个计数现在没有断言用到 —— 菜单里的项要真被点到才会触发，
 * 无头环境点不了菜单，留着是为了让 [TrayController] 的构造参数语义一目了然。
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

    if (!java.awt.SystemTray.isSupported()) {
        // 没有通知区的机器（精简系统、部分远程会话）本来就装不上，
        // 产品在那种机器上退回「关窗口就是退出」，这里同样不算失败。
        println("SKIP 这台机器没有托盘，剩下的断言跳过")
        println("TRAY SMOKE SKIPPED")
        exitProcess(2)
    }

    var opened = 0
    var exited = 0
    val controller = TrayController(onOpen = { opened++ }, onExit = { exited++ })

    // ② 没装之前：installed 必须是 false。调用方靠它决定「× 是退出还是收起」。
    check("没装之前 installed=false", !controller.installed)

    // ③ 用一张真 png 装上（这才是发行时走的那条路：图标资源读得到）。
    val png: ByteArray? = TrayController::class.java.getResourceAsStream("/icon/platypus-baby.png")
        ?.use { it.readBytes() }
    if (png == null) {
        println("FAIL 读不到 /icon/platypus-baby.png，装配路径与发行包不一致")
        println("TRAY SMOKE FAILED")
        exitProcess(1)
    }
    check("装上托盘图标", controller.install(png, "鸭嘴兽启动器"), "png=" + png.size + "B")
    check("装上之后 installed=true", controller.installed)

    // ④ 再装一次不该重复添加（install 幂等，否则会剩两个图标格子）。
    check("重复 install 仍然成功", controller.install(png, "鸭嘴兽启动器"))
    check("重复 install 之后 installed 仍为 true", controller.installed)

    // ⑤ 开关关掉那条路：dispose 之后 installed 必须回到 false。
    //    这是「关了就是彻底关闭」的判据 —— 图标摘不掉，× 就还会被收起来。
    controller.dispose()
    check("dispose 之后 installed=false", !controller.installed)

    // ⑥ 再打开能装回来（开关来回拨两次不能坏）。
    check("关掉之后能重新装上", controller.install(png, "鸭嘴兽启动器"))
    check("重新装上之后 installed=true", controller.installed)

    // ⑦ 气泡与镜像不该在没图标时炸：dispose 之后这两条要静默返回。
    controller.dispose()
    controller.hintHidden(1)
    controller.mirror("标题", "正文")
    check("没有图标时弹气泡不抛异常", true)

    println(if (exit == 0) "TRAY SMOKE OK" else "TRAY SMOKE FAILED")
    exitProcess(exit)
}
