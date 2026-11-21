package io.github.huang404yan.platypus.core.instance

import java.io.File

/**
 * 一次目录统计的结果。
 *
 * @param truncated 命中 [MAX_FILES] 上限提前停下。这时数字是「至少这么多」，
 *   界面要写「超过 …」而不是当成精确值 —— 玩家的整合包目录动辄十几万个文件，
 *   为了一个「占多大」的显示去完整走一遍，百宝箱点下去就得转半分钟。
 */
internal data class TreeStats(
    val files: Int,
    val bytes: Long,
    val truncated: Boolean = false,
)

/** 单次统计最多数这么多文件。与 [InstanceDetailsLoader.MAX_FILES_PER_DIR] 取同一个量级。 */
internal const val MAX_TREE_FILES: Int = 200_000

/**
 * 数一个路径占了多少字节。
 *
 * 传进来的是文件时直接返回 1 个文件、自身长度；是目录时递归（不进隐藏目录以外的特殊处理 ——
 * 这里要的是真实占用，.mixin.out 之类恰恰是藏在点开头的）。读不了的子目录跳过，
 * 不抛异常：统计是只读的辅助功能，没权限的目录不该让整页报错。
 */
internal fun measureTree(path: File, maxFiles: Int = MAX_TREE_FILES): TreeStats {
    if (!path.exists()) return TreeStats(0, 0L)
    if (path.isFile) return TreeStats(1, runCatching { path.length() }.getOrDefault(0L))

    var files = 0
    var bytes = 0L
    var truncated = false
    runCatching {
        val walker = path.walkTopDown()
        for (entry in walker) {
            if (entry.isFile) {
                files++
                bytes += runCatching { entry.length() }.getOrDefault(0L)
                if (files >= maxFiles) {
                    truncated = true
                    break
                }
            }
        }
    }
    return TreeStats(files, bytes, truncated)
}
