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
 * 接缝处非常扎眼 —— 这也是 docs/42 里写明「还没做到」的那一条。
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

    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_FRAMECHANGED = 0x0020

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
    fun applyDarkTitleBar(window: Window, dark: Boolean): Boolean {
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
        val value = IntByReference(if (dark) 1 else 0)
        // 先试新编号，失败再试旧编号：Win10 20H1 之前只认 19，Win11 认 20。
        val codes = mutableListOf<Int>()
        for (attribute in intArrayOf(DWMWA_USE_IMMERSIVE_DARK_MODE, DWMWA_USE_IMMERSIVE_DARK_MODE_LEGACY)) {
            val hr = api.DwmSetWindowAttribute(hwnd, attribute, value, 4)
            if (hr == 0) {
                // 标题栏由 DWM 画，属性设上了它也不一定马上重画 —— 实测不重画就一直白着。
                // SWP_FRAMECHANGED 让系统按新属性重算一遍非客户区。
                user32?.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_FRAMECHANGED)
                // 回读一次：设上了还不算数，得系统真的记住了才算数。
                // 注意 PrintWindow 截图看不到这个效果 —— 它重画标题栏时不走 DWM 合成器，
                // 永远画出系统默认的浅色那条。要看效果只能人眼在桌面上看。
                val readback = IntByReference(-1)
                api.DwmGetWindowAttribute(hwnd, attribute, readback, 4)
                return readback.value == value.value || readback.value == -1
            }
            codes += hr
        }
        println("[ui] titlebar: 系统不认深色标题栏（hwnd=" + Pointer.nativeValue(hwnd) + "，返回码 " + codes + "）")
        return false
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
