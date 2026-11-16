package io.github.huang404yan.platypus.core.instance

/**
 * 「默认内存」到底是哪个数：用户拖的那条滑条写下来的数，还是按物理内存现算的数。
 *
 * 为什么要有「自动」：启动器是发给别人的，2048 MB 在自己机器上够用，在 8 GB 的
 * 机器上装了 200 个模组必然爆，在 64 GB 的机器上又纯属浪费。写死一个数等于把
 * 「你是几个人、用多大内存」这个问题甩给安装量最大的那批人。PCL 也是这个思路，
 * 它的内存滑条刻度是按物理内存总量分档算出来的。
 *
 * 比例取物理内存的四成：剩下的六成要装系统自己、显存映射、浏览器、IDE，以及
 * 启动器本体这个 jpackage 运行时。按经验 16 GB 机器给 6 GB 左右是模组整合包能
 * 稳住的区间的下沿，再少会「加载到一半 OutOfMemoryError」，再多就开始换页。
 */
object MemoryDefaults {

    /** 自动值占物理内存的比例（百分比）。 */
    const val AUTO_PERCENT: Int = 40

    /**
     * 按物理内存算出来的默认内存（MB）。
     *
     * @param totalMb 本机物理内存 MB；0 表示探不到（安卓旧机型、或运行时被裁掉
     *   jdk.management 模块）—— 这时退回 [Instance.DEFAULT_MEMORY_MB]，也就是老行为。
     *
     * 结果按 128 MB 取整、夹在「最小 1024」与「物理内存减 1 GB」之间：低于 1 GB 连原版
     * 1.21 都跑不动；高于物理内存减 1 GB 就是把系统一起换页。
     */
    fun autoMb(totalMb: Int): Int {
        if (totalMb <= 0) return Instance.DEFAULT_MEMORY_MB
        val raw = totalMb.toLong() * AUTO_PERCENT / 100L
        val snapped = ((raw / MEMORY_ALIGN_MB) * MEMORY_ALIGN_MB).toInt()
        val ceiling = (totalMb - 1024).coerceAtLeast(Instance.MIN_MEMORY_MB)
        return snapped.coerceIn(1024, Instance.MAX_MEMORY_MB).coerceAtMost(ceiling)
    }

    /**
     * 生效的默认内存：自动就是 [autoMb]，否则用设置里存的那个数。
     *
     * 所有读 LauncherSettings.defaultMemoryMb 的地方都该走这里 —— 直接读字段会让
     * 「自动」在那些地方静默失效（用户换了台机器，新建的实例还在用老数）。
     */
    fun resolve(storedMb: Int, auto: Boolean, totalMb: Int): Int =
        if (auto) autoMb(totalMb) else Instance.clampMemoryMb(storedMb)

    private const val MEMORY_ALIGN_MB: Long = 128L
}
