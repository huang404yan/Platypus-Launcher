package io.github.huang404yan.platypus.desktop

import java.awt.AWTException
import java.awt.Font
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * 托盘菜单用的字体。
 *
 * AWT 的弹出菜单默认用系统菜单字体（Windows 上是 Segoe UI），那个字体没有汉字，
 * 中文会全变成方块 □ —— 用户 2026-11-22 的截图就是这样。改挂 Java 的逻辑字体
 * Dialog：它由 JRE 按字符去挑能显示的物理字体，本机实测对中文 canDisplayUpTo 返回 -1。
 *
 * 不写死「微软雅黑」这类具体字族：没装那套字体的机器上照样回方块，
 * 而 Dialog 是 JRE 自带的逻辑名，永远存在。
 *
 * 提成常量是为了让 [TraySmoke] 能直接对这个字体断言，不再靠肉眼截屏。
 */
internal val TRAY_MENU_FONT: Font = Font(Font.DIALOG, Font.PLAIN, 12)

/**
 * 托盘图标：把窗口收起来之后，用户还能找回启动器，游戏也继续跑。
 *
 * 为什么要它：这一版支持同时开几个版本，用户中途去干别的、把窗口关掉是常事。
 * 没有托盘的话，关掉窗口＝结束程序，正在跑的游戏就变成「谁也管不着的 java」——
 * 用户下次想停它只能去任务管理器。有了托盘，关窗口只是收起来。
 *
 * 三条约束：
 *  1. **只在系统支持时装**。SystemTray.isSupported 为假（少数精简系统、某些远程会话）
 *     就让 [install] 返回 false，调用方退回「关窗口就是退出」的原行为，不假装支持。
 *  2. **只用 AWT 自带件**，不引第三方托盘库。菜单就三项，PopupMenu 够用。
 *  3. **图标资源读不到就不装**。宁可没有托盘，也不要一个空白格子在通知区里。
 *
 * 线程：AWT 的托盘事件在事件派发线程（EDT）上回调，所以内部一切操作都在 EDT 上发生；
 * [install] 与 [dispose] 可以从任意线程调，它们自己转一手。
 *
 * 装不装由 `LauncherSettings.trayEnabled` 决定，**默认不装**（2026-11-22 用户口径：
 * 「关了就是彻底关闭」）。这里只在被要求装的时候才装，装不上的两条路径（系统不支持、
 * 图标读不出）都返回 false，调用方据此把「×」退回真退出。
 */
internal class TrayController(
    /** 用户点了托盘图标（双击）或菜单里的「打开鸭嘴兽」。 */
    private val onOpen: () -> Unit,
    /** 用户点了菜单里的「退出」。这一步之后进程该结束了。 */
    private val onExit: () -> Unit,
) {

    private var icon: TrayIcon? = null
    /** 每次收起窗口弹一次气泡就够了；弹多了是骚扰。 */
    private var hintShown = false

    /** 装上了没有。false 时调用方必须按「没有托盘」处理，不能调 [hideHint]。 */
    val installed: Boolean get() = icon != null

    /**
     * 装托盘图标。返回是否装上。
     *
     * @param iconBytes 托盘用的图（png 原始字节）。传 null 或解不出图都算失败。
     * @param tooltip 鼠标悬停时的提示文字。
     */
    fun install(iconBytes: ByteArray?, tooltip: String): Boolean {
        if (icon != null) return true
        if (!SystemTray.isSupported()) {
            println("[tray] 系统不支持托盘，跳过")
            return false
        }
        val image: Image = iconBytes
            ?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
            ?: run {
                println("[tray] 图标资源读不出来，跳过")
                return false
            }

        val menu = PopupMenu()
        menu.font = TRAY_MENU_FONT
        val open = MenuItem("打开鸭嘴兽")
        open.font = TRAY_MENU_FONT
        open.addActionListener { onOpen() }
        menu.add(open)
        menu.addSeparator()
        val exit = MenuItem("退出")
        exit.font = TRAY_MENU_FONT
        exit.addActionListener { onExit() }
        menu.add(exit)

        val trayIcon = TrayIcon(image, tooltip, menu)
        // 让系统按通知区实际大小缩放：各家 DPI 不一样，写死尺寸在 150% 缩放下会糊。
        trayIcon.isImageAutoSize = true
        trayIcon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // 双击恢复窗口。单击留给「弹出菜单」—— Windows 上单击图标本来就出菜单，
                // 再抢单击会让菜单一闪而过。
                if (e.button == MouseEvent.BUTTON1 && e.clickCount >= 2) onOpen()
            }
        })

        return try {
            SystemTray.getSystemTray().add(trayIcon)
            icon = trayIcon
            println("[tray] 已装上托盘图标")
            true
        } catch (e: AWTException) {
            // 通知区被别的程序占满、或者系统策略不允许。不是错误，退回无托盘。
            println("[tray] 装不上：" + e.message)
            false
        }
    }

    /**
     * 窗口刚收起来时弹一句「还在后台」，只弹第一次。
     *
     * 为什么只弹一次：用户第一次收起窗口时确实不知道自己去了哪；但只要他知道过一次，
     * 之后每次收起都弹就是噪音了。
     */
    fun hintHidden(gameCount: Int) {
        val trayIcon = icon ?: return
        if (hintShown) return
        hintShown = true
        val body = if (gameCount > 0) {
            "还有 " + gameCount + " 个版本在后台跑。双击图标打开启动器，右键图标可以彻底退出。"
        } else {
            "启动器收到这里了。双击图标打开，右键图标可以彻底退出。"
        }
        runCatching { trayIcon.displayMessage("鸭嘴兽启动器", body, TrayIcon.MessageType.NONE) }
    }

    /**
     * 窗口收起时，把界面上发生的完成提示同步成系统气泡。
     *
     * 窗口藏起来之后界面上那张小卡没人看得见 —— 那正是用户最需要被叫一声的时刻
     * （装完一关窗口走人，气泡会告诉他可以回来启动）。窗口还在前台时不弹，
     * 界面上那张卡已经把话说完了，再来一个系统气泡是双重打扰。
     */
    fun mirror(title: String, body: String) {
        val trayIcon = icon ?: return
        runCatching { trayIcon.displayMessage(title, body, TrayIcon.MessageType.NONE) }
    }

    /** 退出前把图标摘掉，否则鼠标划过通知区会留一个幽灵格子。 */
    fun dispose() {
        val trayIcon = icon ?: return
        icon = null
        runCatching { SystemTray.getSystemTray().remove(trayIcon) }
    }
}
