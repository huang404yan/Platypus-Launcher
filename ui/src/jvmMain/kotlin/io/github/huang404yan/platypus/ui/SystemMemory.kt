package io.github.huang404yan.platypus.ui

import java.lang.management.ManagementFactory

/**
 * 本机物理内存（MB）。
 *
 * 为什么需要它：内存滑条的上限不能只写死 32768 —— 一台 8 GB 的机器上把 -Xmx 拉到
 * 24 GB，Java 不会报错，它只是一路换页，最后连窗口都点不动。PCL 也是按物理内存
 * 分档算滑条上限的（PageSetupLaunch.xaml.vb:154-162）。
 *
 * 走 [com.sun.management.OperatingSystemMXBean]：它在 JDK 的 jdk.management 模块里，
 * 桌面端由 jpackage 打出来的运行时默认带；Android 上没有这个类，那里的类型判断会
 * 落到 else 分支 —— 两侧都由 runCatching 兜成 0，调用方把 0 当「不知道」。
 */
internal object SystemMemory {
    /** 探到的物理内存 MB；0 = 不知道，调用方退到 [io.github.huang404yan.platypus.core.instance.Instance.MAX_MEMORY_MB]。 */
    val totalMb: Int by lazy { detect() }

    private fun detect(): Int = runCatching {
        val bean = ManagementFactory.getOperatingSystemMXBean()
        val bytes = if (bean is com.sun.management.OperatingSystemMXBean) bean.totalMemorySize else 0L
        if (bytes <= 0L) 0 else (bytes / (1024L * 1024L)).toInt()
    }.getOrDefault(0)
}
