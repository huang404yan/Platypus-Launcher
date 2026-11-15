package io.github.huang404yan.platypus.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/**
 * 把窗口标题栏切换成深色。
 *
 * 为什么需要这个：Compose Desktop 没有跨平台的深色标题栏开关，标题栏是操作系统画的。
 * 深色主题（自然原色，深潭青 #1A2B2C）配一条系统默认的浅灰白标题栏，
 * 接缝处非常扎眼 —— 这是已知没做到的那一条。
 *
 * Windows 上唯一的入口是 DWM 的 `DwmSetWindowAttribute`，JDK 没有对等 API，
 * 所以这里借 JNA 直接调（只在 app-desktop 里用，core / ui 不该知道宿主是什么系统）。
 *
 * 失败一律静默：拿不到 HWND（非 Windows、窗口还没显示）或系统不认这个属性，
 * 结果只是标题栏保持系统配色，不影响启动 —— 不值得为一条装饰性差异让整个界面崩掉。
 */
internal object WindowTheme {

    /** DwmSetWindowAttribute 的第二个参数。Windows 11 / Win10 20H1+ 是 20，更早的是 19。 */
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY = 19

    // 兜底/主力路径：SWP_FRAMECHANGED 会让系统按新属性重算一遍非客户区。
    // 代价是切换瞬间标题文字会先变色、底色后变，看着像「标题文字被选中了」，
    // 所以调用时机放在内容帧之后（见 Main.kt），让观感上内容先、标题栏紧随。
    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_FRAMECHANGED = 0x0020

    /**
     * 让系统重画整个非客户区。
     *
     * lParam 传 -1（不是 0）才是「整个非客户区都重绘」，传 0 只更新激活状态、不重画。
     * 这是 SWP_FRAMECHANGED 没效果时的最后一招，实测比 RedrawWindow 管用：
     * 后者只把边框标脏，DWM 画的标题栏不认 WM_NCPAINT，等不到那次重画。
     */
    private const val WM_NCACTIVATE = 0x0086

    /**
     * 上一次真的设成功的那一对 (hwnd, dark)。
     *
     * 每次设属性都会触发一次非客户区重画，而主题变化会连带重组 —— 同一个值被
     * 重复设置几次就在切换瞬间多出几次闪动。切换路径与启动路径都会调到这里，
     * 所以必须有去重。只在 UI 线程调用，不需要加锁。
     */
    private var lastHwnd: Pointer? = null
    private var lastDark: Boolean? = null

    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(
            hwnd: Pointer?,
            attribute: Int,
            value: IntByReference,
            size: Int,
        ): Int

        fun DwmGetWindowAttribute(
            hwnd: Pointer?,
            attribute: Int,
            value: IntByReference,
            size: Int,
        ): Int

        /** 等 DWM 把当前这一帧真的合成完。切换深浅色后调一次，两边就不会错开。 */
        fun DwmFlush(): Int
    }

    /** EnumWindows 的回调签名：返回 TRUE（非 0）继续枚举。 */
    private interface WndEnumProc : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: Pointer?, lParam: Pointer?): Boolean
    }

    private interface User32Api : StdCallLibrary {
        fun EnumWindows(callback: WndEnumProc, lParam: Pointer?): Int
        fun GetWindowThreadProcessId(hwnd: Pointer?, pid: IntByReference): Int
        fun IsWindowVisible(hwnd: Pointer?): Int
        fun GetWindowTextLengthW(hwnd: Pointer?): Int
        fun GetWindowTextW(hwnd: Pointer?, buffer: CharArray, maxCount: Int): Int
        fun SetWindowPos(
            hwnd: Pointer?,
            insertAfter: Pointer?,
            x: Int,
            y: Int,
            cx: Int,
            cy: Int,
            flags: Int,
        ): Int

        // 必须写 SendMessageW：user32.dll 里没有无后缀的 SendMessage 导出，
        // 只有 SendMessageA / SendMessageW。JNA 不会自动补后缀（除非给 Native.load
        // 传 W32APIOptions），写裸名字会抛 UnsatisfiedLinkError：
        // 「Error looking up function 'SendMessage': 找不到指定的程序。」
        // WPARAM / LPARAM 在 64 位下是指针宽度，用 Long 传，Int 会被截断。
        fun SendMessageW(hwnd: Pointer?, msg: Int, wParam: Long, lParam: Long): Int
    }

    private val dwm: DwmApi? by lazy {
        runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull()
    }

    private val user32: User32Api? by lazy {
        runCatching { Native.load("user32", User32Api::class.java) }.getOrNull()
    }

    /**
     * @param dark true 深色标题栏，false 浅色。
     * @return 真的设置成功了吗（失败原因不抛，调用方多半只想打一行日志）。
     */
    fun applyDarkTitleBar(window: Window, dark: Boolean): Boolean =
        // 任何一步抛异常都不许冒到界面上：这里只负责给标题栏换个颜色，
        // 最坏结果是保持系统配色。2026-11-14 就因为 JNA 找不到 SendMessage，
        // Compose 直接把一个「Error looking up function」的框弹在了窗口正中央。
        runCatching { setDarkTitleBar(window, dark) }
            .getOrElse {
                println("[ui] titlebar: 调用失败 " + (it.message ?: it.javaClass.simpleName))
                false
            }

    private fun setDarkTitleBar(window: Window, dark: Boolean): Boolean {
        val api = dwm
        if (api == null) {
            println("[ui] titlebar: dwmapi 加载失败")
            return false
        }
        val hwnd = hwndOf(window)
        if (hwnd == null) {
            // 第一次调用常常赶在窗口 peer 建好之前，Main 那边会隔一帧重试，所以这里不出声。
            return false
        }
        // 已经是这个状态就别再设一遍：每次设都会触发一次非客户区重画，
        // 而切换主题会连带重组，重复调用就在切换瞬间多出几次闪动。
        if (hwnd == lastHwnd && dark == lastDark) return true
        val value = IntByReference(if (dark) 1 else 0)
        // 两个编号都设，不能设成功一个就返回。
        //
        // Win11 与 Win10 20H1+ 认 20，更早的系统只认 19。部分 Win10 机器上属性 20
        // 能把标题栏设成深色，**却接受不了设回浅色**（返回 0 但界面不变）——
        // 2026-11-14 实测：切到「自然原色」标题栏变黑，切回「特工蓝橙」仍是黑，
        // 只有重启程序才恢复。所以两个编号都写一遍，让 19 兜住这种情况。
        val codes = mutableListOf<Int>()
        var anyOk = false
        for (attribute in intArrayOf(DWMWA_USE_IMMERSIVE_DARK_MODE, DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY)) {
            val hr = api.DwmSetWindowAttribute(hwnd, attribute, value, 4)
            if (hr == 0) anyOk = true else codes += hr
        }
        if (!anyOk) {
            println("[ui] titlebar: 系统不认深色标题栏（hwnd=" + Pointer.nativeValue(hwnd) + "，返回码 " + codes + "）")
            return false
        }
        // 标题栏是 DWM 合成器画的，不吃 WM_NCPAINT —— RedrawWindow 对它无效，
        // 只能把边框标成「脏」而永远等不到那次重画。真正管用的是让系统按新属性
        // 重算一遍非客户区（WM_NCCALCSIZE），再补一次 WM_NCACTIVATE 要求整体重绘。
        user32?.SetWindowPos(
            hwnd, null, 0, 0, 0, 0,
            SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_FRAMECHANGED,
        )
        user32?.SendMessageW(hwnd, WM_NCACTIVATE, 1L, -1L)
        // 文字重画了，底色由 DWM 合成器画，要等下一次合成才跟上 —— 表现是
        // 「字变了、背景得等一会」。DwmFlush 阻塞到这一帧真的合成完，把等待消掉。
        api.DwmFlush()
        // 回读一次：设上了还不算数，得系统真的记住了才算数。
        // 注意 PrintWindow 截图看不到这个效果 —— 它重画标题栏时不走 DWM 合成器。
        val readback = IntByReference(-1)
        api.DwmGetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, readback, 4)
        val ok = readback.value == value.value || readback.value == -1
        if (ok) {
            lastHwnd = hwnd
            lastDark = dark
        }
        return ok
    }

    /**
     * 找这个 AWT 窗口对应的顶层 HWND。
     *
     * 不用 `WComponentPeer.getHWnd()` 那条反射路线：它是 JDK 内部实现类，JDK 17 起模块系统
     * 默认封着（实测 `InaccessibleObjectException` 被吞掉后只剩 null，表现为「永远拿不到 HWND」）。
     * 改成问系统：EnumWindows 列出所有顶层窗口，挑进程号等于当前 JVM 且可见的那些，
     * 再按 AWT 那边的窗口标题认人（同一个 JVM 里还可能有对话框、输入法候选窗）。
     * 标题也对不上时才退回第一个 —— EnumWindows 的顺序按 Z 序从上到下，主窗口通常在最前。
     */
    private fun hwndOf(window: Window): Pointer? {
        val api = user32 ?: return null
        val target = ProcessHandle.current().pid().toInt()
        // 记下「HWND → 标题」，多个候选时靠 AWT 那边的标题认人。
        val candidates = mutableListOf<Pair<Pointer, String>>()
        runCatching {
            api.EnumWindows(
                object : WndEnumProc {
                    override fun callback(hwnd: Pointer?, lParam: Pointer?): Boolean {
                        if (hwnd == null) return true
                        val pid = IntByReference()
                        api.GetWindowThreadProcessId(hwnd, pid)
                        if (pid.value == target && api.IsWindowVisible(hwnd) != 0) {
                            candidates += hwnd to titleOf(api, hwnd)
                        }
                        return true
                    }
                },
                null,
            )
        }.getOrNull()
        if (candidates.isEmpty()) return null
        val wanted = (window as? java.awt.Frame)?.title.orEmpty()
        // 先按标题精确找：同一个 JVM 里还可能有输入法候选窗、提示窗，按标题认最稳。
        candidates.firstOrNull { it.second == wanted && wanted.isNotBlank() }?.let { return it.first }
        // 认不出来就退而求其次，取 EnumWindows 的第一个 —— 它的顺序是按 Z 序从上到下，
        // 主窗口通常被窗口管理器提到最前。
        return candidates.first().first
    }

    private fun titleOf(api: User32Api, hwnd: Pointer): String {
        val length = api.GetWindowTextLengthW(hwnd)
        if (length <= 0) return ""
        val buffer = CharArray(length + 1)
        val written = api.GetWindowTextW(hwnd, buffer, buffer.size)
        return if (written <= 0) "" else String(buffer, 0, written)
    }
}
