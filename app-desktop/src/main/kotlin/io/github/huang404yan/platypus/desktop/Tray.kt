package io.github.huang404yan.platypus.desktop

import java.awt.AWTException
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Image
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * 托盘菜单用的字体。
 *
 * 为什么不是「给 AWT 菜单设个中文字体」：那条路 2026-11-22 实测走不通。
 * `java.awt.PopupMenu` 的 `setFont` 在 Windows 上只改了 Java 侧的模型 ——
 * `getFont()` 能回读出 Dialog，屏幕上渲染出来的仍是方块 □。换 `Microsoft YaHei UI`
 * `SimSun` 甚至不设字体，截图逐个看过，全是方块。根因在
 * `sun.awt.windows.WMenuItemPeer._setFont` 这个 native 调用并没有把字体交给 Win32
 * 的原生菜单，AWT 弹菜单在 Windows 上就是固定用系统菜单字体（Segoe UI，没有汉字）。
 *
 * 同一台机器上换成 Swing 的 `JPopupMenu` + `JMenuItem` 再挂同一个字体，中文立刻正常。
 * 所以菜单改由 Swing 画，这个常量只服务于 Swing 那一套。
 *
 * 用 Java 的逻辑字体 Dialog 而不是「微软雅黑」：没装那套字体的机器上照样回方块，
 * 而 Dialog 是 JRE 自带的逻辑名，永远存在，按字符挑能显示的物理字体
 * （本机实测对中文 `canDisplayUpTo` 返回 -1）。
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
 *  2. **不引第三方托盘库**，图标本体仍用 AWT 的 [TrayIcon]（那是唯一的口子）。
 *     菜单换成 Swing 的 [JPopupMenu]，原因见 [TRAY_MENU_FONT] —— AWT 那条路中文必方块。
 *  3. **图标资源读不到就不装**。宁可没有托盘，也不要一个空白格子在通知区里。
 *
 * 菜单弹出方式跟着换：`TrayIcon` 的两参构造不带原生菜单，右键由我们自己在
 * [MouseAdapter] 里弹 Swing 菜单（需要一块 1×1 的透明 [JWindow] 当 invoker，
 * 否则轻量弹层会被窗口裁掉）。左键单击不抢 —— 双击打开启动器，与之前的说明一致。
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
    /** 菜单只建一次，反复弹复用同一份（菜单项的回调都指着这个实例）。 */
    private var menu: JPopupMenu? = null
    /** 弹菜单要的 invoker：一块 1×1 的透明无装饰窗口，摆在鼠标位置。 */
    private var invoker: JWindow? = null

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

        // 两参构造：不把 AWT 菜单交给系统，右键由下面自己弹 Swing 菜单。
        val trayIcon = TrayIcon(image, tooltip)
        // 让系统按通知区实际大小缩放：各家 DPI 不一样，写死尺寸在 150% 缩放下会糊。
        trayIcon.isImageAutoSize = true
        trayIcon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // 双击恢复窗口。右键留给菜单：Windows 上右键托盘图标会先到 pressed
                // 再到 released，两处都判一次 isPopupTrigger，哪个平台在哪个阶段报都接得住。
                if (e.button == MouseEvent.BUTTON1 && e.clickCount >= 2) onOpen()
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showMenu()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showMenu()
            }
        })

        return try {
            SystemTray.getSystemTray().add(trayIcon)
            icon = trayIcon
            menu = menu ?: buildMenu()
            println("[tray] 已装上托盘图标")
            true
        } catch (e: AWTException) {
            // 通知区被别的程序占满、或者系统策略不允许。不是错误，退回无托盘。
            println("[tray] 装不上：" + e.message)
            false
        }
    }

    /**
     * 建右键菜单。
     *
     * 为什么非 Swing 不可：见 [TRAY_MENU_FONT] 的 KDoc —— AWT 的 [java.awt.PopupMenu]
     * 在 Windows 上不接受自定义字体，中文必然是方块。这里对每一项都显式设字体：
     * 只给菜单设、不给菜单项设时，Swing 渲染用的是菜单项自己那份（会退回系统默认字族）。
     *
     * `setDefaultLightWeightPopupEnabled(false)` 是静态开关，作用在进程内所有 JPopupMenu 上，
     * 让菜单走重型弹层（自己一个原生窗口，不受任何父窗口裁剪）。托盘菜单只有 1×1 的
     * invoker，非重型的话菜单会被裁成看不见。
     *
     * 提成 internal 是为了让 [TraySmoke] 能直接检查字体与菜单项，不必真去点通知区。
     */
    internal fun buildMenu(): JPopupMenu {
        JPopupMenu.setDefaultLightWeightPopupEnabled(false)
        val popup = JPopupMenu()
        popup.font = TRAY_MENU_FONT

        val open = JMenuItem("打开鸭嘴兽")
        open.font = TRAY_MENU_FONT
        open.addActionListener { onOpen() }
        popup.add(open)

        popup.addSeparator()

        val exit = JMenuItem("退出")
        exit.font = TRAY_MENU_FONT
        exit.addActionListener { onExit() }
        popup.add(exit)

        // 菜单收起时把 invoker 一起藏掉，否则那个 1×1 的窗口会一直挂在鼠标最后停的位置。
        popup.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                invoker?.isVisible = false
            }

            override fun popupMenuCanceled(e: PopupMenuEvent) {
                invoker?.isVisible = false
            }
        })
        return popup
    }

    /** 在鼠标位置弹出右键菜单。只从托盘图标的事件里调，已经在 EDT 上。 */
    private fun showMenu() {
        val popup = menu ?: return
        val where = MouseInfo.getPointerInfo()?.location ?: return
        SwingUtilities.invokeLater {
            val holder = ensureInvoker()
            holder.setLocation(where)
            holder.isVisible = true
            popup.show(holder, 0, 0)
        }
    }

    /** 菜单的 owners：1×1 透明无装饰窗口，摆在鼠标处当锚点。 */
    private fun ensureInvoker(): JWindow {
        invoker?.let { return it }
        val holder = JWindow()
        holder.size = Dimension(1, 1)
        // 透明背景不是所有系统都支持（没有合成管理器时会抛），失败就留默认背景 ——
        // 反正只有 1×1 像素，看不出差别。
        runCatching { holder.background = Color(0, 0, 0, 0) }
        holder.isAlwaysOnTop = true
        invoker = holder
        return holder
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
        runCatching { invoker?.dispose() }
        invoker = null
    }
}
