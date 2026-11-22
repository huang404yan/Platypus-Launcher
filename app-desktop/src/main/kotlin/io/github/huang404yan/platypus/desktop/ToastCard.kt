package io.github.huang404yan.platypus.desktop

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 桌面提示卡：屏幕右下角一张小白卡，图标 + 标题 + 一两行正文。
 *
 * 为什么不用系统通知（TrayIcon.displayMessage，底层是 Shell_NotifyIcon 的 NIF_INFO）：
 *
 *  1. **图标是坏的**。2026-11-22 实测：Windows 在 150% 缩放下，JDK 生成通知图标用的
 *     1bpp 掩码按整幅连续打包、不按扫描行对齐（sun.awt.windows.WTrayIconPeer 里
 *     TRAY_ICON_MASK_SIZE 恒为 32 字节，24x24 需要 72 字节），结果图标被横切成一条条
 *     白缝。换图片、换尺寸、setImageAutoSize、DPI 参数全试过，只有把整个进程按 100%
 *     缩放才不坏 —— 那不是我们能替用户选的。
 *  2. **停留时间不可控**。NIF_INFO 的停留时长由系统「轻松使用」设置决定，程序传的
 *     超时值在 Vista 之后被忽略（用户 2026-11-22 的反馈：「显示没问题，就是时间太短」）。
 *
 * 自己画一张卡，这两个问题一起没了：像素是我们画的，停多久是我们定的，鼠标移上去
 * 还会暂停倒计时。代价是不进 Windows 通知中心 —— 这两类提示本来就是「当下瞟一眼」，
 * 不需要历史记录。
 *
 * 卡片刻意不抢焦点（[JWindow.focusableWindowState] 为 false）：用户可能正在别的程序
 * 里打字，一张提示卡把光标抢走是最讨人厌的行为。
 */
internal class ToastCard(
    /** 用户点了卡片。一般就是「把窗口叫回来」。 */
    private val onActivate: () -> Unit,
) {

    private var window: JWindow? = null
    private var lifecycle: Timer? = null

    /** 现在有没有一张卡在屏幕上。只给 [TraySmoke] 断言用，产品代码不读它。 */
    internal val showing: Boolean get() = window?.isVisible == true

    /** 当前那张卡的尺寸。同上，只给冒烟断言用。 */
    internal val cardSize: Dimension? get() = window?.size

    /**
     * 弹一张卡。已经有一张在显示时，旧的那张先收掉。
     *
     * @param title 粗体标题，一行。
     * @param body 正文，按可用宽度折行，最多 [MAX_BODY_LINES] 行，放不下的用省略号收尾。
     * @param image 左边那张小图，一般是托盘图标；传 null 时文字靠左、不画图。
     * @param holdMs 停留时长（淡入结束后算起）。鼠标停在卡上时倒计时暂停。
     */
    fun show(title: String, body: String, image: Image?, holdMs: Int = HOLD_MS) {
        SwingUtilities.invokeLater {
            dismissNow()
            val panel = CardPanel(title, body, image)
            val card = JWindow()
            card.isAlwaysOnTop = true
            // 不抢焦点：一张提示卡把用户正在打的字顶掉，比不弹还糟。
            card.focusableWindowState = false
            runCatching { card.background = Color(0, 0, 0, 0) }
            card.contentPane = panel
            card.setSize(panel.preferredSize)
            card.setLocation(bottomRight(panel.preferredSize))
            // 透明特效不被支持时（极少数老驱动）就当没有淡入，直接显示。
            runCatching { card.opacity = 0f }
            val listener = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onActivate()
                    dismissNow()
                }
            }
            card.addMouseListener(listener)
            // 鼠标事件落到面板上时不会再冒泡给窗口，所以两边都挂。
            panel.addMouseListener(listener)
            card.isVisible = true
            window = card
            startLifecycle(card, panel, holdMs)
        }
    }

    /** 立刻收起（换一张新卡，或程序要退出时）。可以从任意线程调。 */
    fun dismiss() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { dismissNow() }
            return
        }
        dismissNow()
    }

    private fun dismissNow() {
        lifecycle?.stop()
        lifecycle = null
        window?.let {
            runCatching { it.isVisible = false }
            runCatching { it.dispose() }
        }
        window = null
    }

    /**
     * 淡入 -> 停留 -> 淡出。
     *
     * 倒计时只在「停留」阶段推进，鼠标停在卡片上时暂停 —— 用户把指针移过来的意图
     * 就是要看清，这时候让它消失等于白弹。
     */
    private fun startLifecycle(card: JWindow, panel: CardPanel, holdMs: Int) {
        var age = 0L
        var paused = false
        val hover = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                paused = true
            }

            override fun mouseExited(e: MouseEvent) {
                paused = false
            }
        }
        panel.addMouseListener(hover)
        card.addMouseListener(hover)
        val timer = Timer(TICK_MS) { ev ->
            if (!paused) age += TICK_MS
            val alpha = when {
                age < FADE_IN -> age.toFloat() / FADE_IN
                age < FADE_IN + holdMs -> 1f
                else -> 1f - (age - FADE_IN - holdMs).toFloat() / FADE_OUT
            }
            runCatching { card.opacity = alpha.coerceIn(0f, 1f) }
            if (age >= FADE_IN + holdMs + FADE_OUT) {
                (ev.source as Timer).stop()
                dismissNow()
            }
        }
        lifecycle = timer
        timer.start()
    }

    /** 右下角，躲开任务栏（多显示器时按默认屏幕算，和托盘图标同屏）。 */
    private fun bottomRight(size: Dimension): Point {
        val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        val gc = device.defaultConfiguration
        val bounds = gc.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return Point(
            bounds.x + bounds.width - size.width - MARGIN,
            bounds.y + bounds.height - insets.bottom - size.height - MARGIN,
        )
    }

    /**
     * 卡片本体。自己画而不是拼 Swing 控件：圆角、阴影、内边距都要精确到像素，
     * 用控件拼出来的边距各外观不同（托盘菜单缩进那一类问题就是这么来的）。
     */
    private class CardPanel(
        private val title: String,
        private val bodyText: String,
        private val image: Image?,
    ) : JPanel() {

        private val titleMetrics: FontMetrics = getFontMetrics(TITLE_FONT)
        private val bodyMetrics: FontMetrics = getFontMetrics(BODY_FONT)
        private val lines: List<String> = wrap(bodyText, bodyMetrics, textWidth(), MAX_BODY_LINES)

        init {
            isOpaque = false
        }

        /** 文字区宽度：总宽减两侧内边距，再减掉图标与它右边那道间距。 */
        private fun textWidth(): Int =
            CARD_WIDTH - SHADOW - PAD * 2 - (if (image == null) 0 else ICON_SIZE + ICON_GAP)

        override fun getPreferredSize(): Dimension {
            val textBlock = TITLE_TOP + titleMetrics.height + TITLE_GAP +
                bodyMetrics.height * maxOf(1, lines.size)
            val content = maxOf(ICON_SIZE + 4, textBlock)
            return Dimension(CARD_WIDTH, content + PAD * 2 + SHADOW)
        }

        override fun paintComponent(g0: Graphics) {
            val g = g0.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val cardW = (width - SHADOW).toFloat()
            val cardH = (height - SHADOW).toFloat()

            // 阴影：几层半透明圆角往右下错开叠出来，比一张模糊图省事，也够看。
            for (i in 1..SHADOW) {
                g.color = SHADOW_COLOR
                g.fill(
                    RoundRectangle2D.Float(
                        i * 0.6f,
                        i * 0.85f,
                        cardW - i * 0.6f + 0.5f,
                        cardH - i * 0.85f + 0.5f,
                        CORNER,
                        CORNER,
                    ),
                )
            }

            g.color = CARD_BG
            g.fill(RoundRectangle2D.Float(0f, 0f, cardW, cardH, CORNER, CORNER))
            g.color = CARD_BORDER
            g.draw(RoundRectangle2D.Float(0.5f, 0.5f, cardW - 1f, cardH - 1f, CORNER, CORNER))

            if (image != null) {
                g.drawImage(image, PAD, PAD + 2, ICON_SIZE, ICON_SIZE, null)
            }

            val textX = PAD + if (image == null) 0 else ICON_SIZE + ICON_GAP
            var baseline = PAD + TITLE_TOP + titleMetrics.ascent
            g.font = TITLE_FONT
            g.color = TEXT_TITLE
            g.drawString(title, textX, baseline)
            baseline += titleMetrics.descent + TITLE_GAP
            g.font = BODY_FONT
            g.color = TEXT_BODY
            for (line in lines) {
                baseline += bodyMetrics.ascent
                g.drawString(line, textX, baseline)
                baseline += bodyMetrics.descent
            }
            g.dispose()
        }
    }

    internal companion object {
        /**
         * 卡片总宽。**别叫 WIDTH**：JPanel 继承了 java.awt.image.ImageObserver，
         * 里面有个 WIDTH = 1，子类里写 WIDTH 会先解析到它，卡片就被挤成 1 像素宽。
         */
        const val CARD_WIDTH = 388
        const val SHADOW = 8
        const val PAD = 14
        const val ICON_SIZE = 34
        const val ICON_GAP = 12
        const val TITLE_TOP = 4
        const val TITLE_GAP = 6
        const val MAX_BODY_LINES = 2
        const val MARGIN = 12
        const val CORNER = 12f

        const val FADE_IN = 200L
        const val FADE_OUT = 260L
        const val TICK_MS = 16
        const val HOLD_MS = 8000

        val CARD_BG = Color(250, 250, 252)
        val CARD_BORDER = Color(228, 228, 234)
        val SHADOW_COLOR = Color(0, 0, 0, 5)
        val TEXT_TITLE = Color(28, 28, 32)
        val TEXT_BODY = Color(72, 72, 80)
        val TITLE_FONT = Font(Font.DIALOG, Font.BOLD, 13)
        val BODY_FONT = Font(Font.DIALOG, Font.PLAIN, 12)

        /**
         * 按像素宽度折行，最多 maxLines 行，放不下时最后一行补省略号。
         *
         * 中文没有空格可断，所以逐字符累加；英文单词会被从中间切开，但这两条提示
         * 基本是中文，不值得为此写一个断词器。
         */
        fun wrap(text: String, metrics: FontMetrics, maxWidth: Int, maxLines: Int): List<String> {
            if (text.isEmpty() || maxLines <= 0) return emptyList()
            val out = ArrayList<String>()
            val line = StringBuilder()
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                i++
                if (ch == '\n') {
                    out.add(line.toString())
                    line.setLength(0)
                    if (out.size == maxLines) break
                    continue
                }
                line.append(ch)
                if (line.length > 1 && metrics.stringWidth(line.toString()) > maxWidth) {
                    line.setLength(line.length - 1)
                    i--
                    out.add(line.toString())
                    line.setLength(0)
                    if (out.size == maxLines) break
                }
            }
            if (out.size < maxLines) {
                if (line.isNotEmpty()) out.add(line.toString())
            } else if (i < text.length || line.isNotEmpty()) {
                var last = out[maxLines - 1]
                while (last.isNotEmpty() && metrics.stringWidth(last + "…") > maxWidth) {
                    last = last.dropLast(1)
                }
                out[maxLines - 1] = last + "…"
            }
            return out
        }
    }
}
