package io.github.huang404yan.platypus.core.instance

import java.io.File

/**
 * 空间分析里的一行。
 *
 * @param path 这一行统计的路径。散装文件那行的 path 是游戏根目录本身，删它没有意义，
 *   界面上那行只显示数字、不给「打开目录」
 */
data class SpaceItem(
    val label: String,
    val path: File,
    val files: Int,
    val bytes: Long,
    val truncated: Boolean = false,
)

/**
 * 一次空间分析的快照。**只读**：这个功能一个字节都不删，
 * 删东西是「清理游戏垃圾」那条路的事，两者的后果差太远，不放在同一个按钮后面。
 *
 * @param hiddenCount 按大小排在前 [SpaceAnalyzer.TOP_ITEMS] 项之外还剩几项
 * @param totalBytes 上列各项之和。**不是整个磁盘占用** —— 只算游戏根目录下一层
 *   与启动器数据目录，不含用户自己散在别处的整合包解压目录
 */
data class SpaceReport(
    val gameRoot: File,
    val items: List<SpaceItem>,
    val hiddenCount: Int,
    val totalBytes: Long,
    val truncated: Boolean = false,
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * 算「谁占了我的盘」。
 *
 * 形态照 PCL 的「空间分析」（custom/Modules/ModCustom.vb 的 SpaceAnalyzeImpl）：
 * 游戏根目录下**只取一层**子目录，各自递归算大小，外加根目录下的散装文件，
 * 按大小降序只列前几项 —— 这一页要回答的是「哪一块占得多」，
 * 不是「每一个文件叫什么」；层数一深，数字就淹进几百行里没人看。
 *
 * 为什么不把 versions 下每个实例单独列：实例数量可以上百，列出来正好把
 * libraries/assets 这两个真正的大头挤出屏幕。想知道单个实例多大，
 * 版本详情页里那一排目录大小就是答案。
 */
object SpaceAnalyzer {

    /** 默认列前几项。与 PCL 一致取 6。 */
    const val TOP_ITEMS: Int = 6

    /**
     * @param gameRoot 含 versions/libraries/assets 的那一层
     * @param dataDir 启动器自己的数据目录（便携模式在启动器目录下，否则在用户目录）。
     *   传 null 或与 gameRoot 相同时不单独列 —— 它在游戏根目录里已经算过一次了
     * @param top 列前几项，单测里可以调小
     */
    fun analyze(
        gameRoot: File,
        dataDir: File? = null,
        top: Int = TOP_ITEMS,
    ): SpaceReport {
        val root = runCatching { gameRoot.canonicalFile }.getOrDefault(gameRoot.absoluteFile)
        val raw = mutableListOf<SpaceItem>()
        var truncated = false

        val children = runCatching { root.listFiles() }.getOrNull().orEmpty()
        var looseFiles = 0
        var looseBytes = 0L
        for (child in children) {
            if (child.isFile) {
                looseFiles++
                looseBytes += runCatching { child.length() }.getOrDefault(0L)
                continue
            }
            if (!child.isDirectory) continue
            val stats = measureTree(child)
            if (stats.truncated) truncated = true
            // 一个文件都没有的空壳目录不列：这一页是「谁占了盘」，0 B 的行只会把
            // 真正的几行往下挤，而空目录又常常成片出现（每个版本都自带一份空的 logs）。
            if (stats.files == 0) continue
            raw += SpaceItem(child.name, child, stats.files, stats.bytes, stats.truncated)
        }
        if (looseFiles > 0) {
            raw += SpaceItem(LOOSE_LABEL, root, looseFiles, looseBytes)
        }

        // 启动器数据目录另有落点时（便携模式、或用户把 gameRoot 指到别处）单独列一行：
        // 存档备份躺在那里，占用可能是几个 GB，不列出来用户永远想不到去清。
        val data = dataDir?.let { runCatching { it.canonicalFile }.getOrNull() }
        if (data != null && data.isDirectory && !isSameOrInside(data, root)) {
            val stats = measureTree(data)
            if (stats.truncated) truncated = true
            if (stats.files > 0) raw += SpaceItem(LAUNCHER_DATA_LABEL, data, stats.files, stats.bytes, stats.truncated)
        }

        val sorted = raw.sortedWith(compareByDescending<SpaceItem> { it.bytes }.thenBy { it.label.lowercase() })
        val shown = sorted.take(top.coerceAtLeast(1))
        return SpaceReport(
            gameRoot = root,
            items = shown,
            hiddenCount = (sorted.size - shown.size).coerceAtLeast(0),
            totalBytes = sorted.sumOf { it.bytes },
            truncated = truncated,
        )
    }

    const val LOOSE_LABEL: String = "（散装文件）"
    const val LAUNCHER_DATA_LABEL: String = "启动器数据"

    private fun isSameOrInside(child: File, parent: File): Boolean =
        InstanceDeleter.isInside(child, parent)
}
