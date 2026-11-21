package io.github.huang404yan.platypus.core.instance

import java.io.File

/**
 * 一类可以放心删掉的垃圾。
 *
 * @param label 界面上显示的名字（「游戏日志」这类），不是目录名
 * @param path 实际目录或文件
 * @param exists 这次扫描时它到底在不在。不在的也留在清单里，
 *   界面才好解释「为什么没什么可清」—— 空着一整页会让人以为功能坏了
 */
data class JunkTarget(
    val label: String,
    val path: File,
    val exists: Boolean,
    val files: Int,
    val bytes: Long,
    val truncated: Boolean = false,
)

/**
 * 一次「清理游戏垃圾」的扫描结果。
 *
 * 只收 logs、crash-reports、.mixin.out 三类 —— 它们都是游戏每次启动会自己重写的产物，
 * 删掉不影响存档、模组与配置。模组缓存、日志里提到的 jar 之类**不在这里**：
 * 那些东西删了可能要重新下载，不该由「一键清理」替用户决定。
 */
data class JunkReport(
    val instanceId: String,
    val instanceName: String,
    val gameRoot: File,
    val targets: List<JunkTarget>,
) {
    val totalFiles: Int get() = targets.sumOf { it.files }
    val totalBytes: Long get() = targets.sumOf { it.bytes }
    val truncated: Boolean get() = targets.any { it.truncated }

    /** 一个能删的都没有。界面上要区分「扫过，空的」与「还没扫」。 */
    val isEmpty: Boolean get() = totalFiles == 0
}

/**
 * 清理结果。
 *
 * @param failures 删不掉的文件（被游戏进程占着最常见）。只留前几条，
 *   几百条都列出来反而盖住了「被谁占着」这个真正有用的信息
 */
data class JunkCleanOutcome(
    val deletedFiles: Int,
    val freedBytes: Long,
    val failedFiles: Int,
    val failures: List<String>,
) {
    val ok: Boolean get() = failedFiles == 0
}

/**
 * 找出并删掉「删了也不会怎样」的游戏垃圾。
 *
 * 判据是「游戏下次启动会自己重写」：logs 目录、crash-reports 目录、.mixin.out 文件。
 * PCL 的「清理游戏垃圾」也是这三样（custom/Modules/ModCustom.vb 的 RubbishClearImpl），
 * 形态可以照搬，但落点得按我们的目录布局算两处：隔离时 --gameDir 指进版本目录，
 * 不隔离时指回游戏根目录，日志跟着 --gameDir 走，所以两个位置都可能长出来。
 *
 * 所有函数都是同步阻塞的，调用方自己上 Dispatchers.IO。
 */
object JunkCleaner {

    /** 相对 gameDir 的垃圾位置。.mixin.out 可能是文件也可能是目录，两种都收。 */
    private val JUNK_NAMES: List<Pair<String, String>> = listOf(
        "logs" to "游戏日志",
        "crash-reports" to "崩溃报告",
        ".mixin.out" to "Mixin 输出",
    )

    /**
     * 扫一遍这个实例的垃圾。
     *
     * gameDir 与 versionDir 相同时（默认隔离）只算一份，不重复统计同一批文件 ——
     * 否则总大小会凭空翻倍，用户会以为清理能腾出两倍空间。
     */
    fun scan(instance: Instance): JunkReport {
        val layout = InstanceLayouts.of(instance)
        val dirs = linkedSetOf(layout.gameDir.absoluteFile, layout.versionDir.absoluteFile)

        val targets = mutableListOf<JunkTarget>()
        for (dir in dirs) {
            for ((name, label) in JUNK_NAMES) {
                val path = File(dir, name)
                // 越界防线：layout 由 instance.directory 推出来，理论上不会跑出去，
                // 但清理是删除动作，宁可多一次判断。用 canonical 比，防 ../ 与符号链接。
                if (!InstanceDeleter.isInside(path, layout.versionDir) &&
                    !InstanceDeleter.isInside(path, layout.gameRoot)
                ) {
                    continue
                }
                val stats = measureTree(path)
                targets += JunkTarget(
                    label = label,
                    path = path,
                    exists = path.exists(),
                    files = stats.files,
                    bytes = stats.bytes,
                    truncated = stats.truncated,
                )
            }
        }
        return JunkReport(
            instanceId = instance.id,
            instanceName = instance.name.ifBlank { instance.id },
            gameRoot = layout.gameRoot,
            targets = targets,
        )
    }

    /**
     * 按扫描结果删。
     *
     * **只删文件，不删那三个顶层目录本身**：目录留着，游戏下次启动照常往里写；
     * 删了目录还得处理「父目录不存在」之类的边角。.mixin.out 本身就是个文件，
     * 那种情况直接删文件。
     *
     * 不在这里重扫一遍：扫描与清理之间只隔用户点一下确认，而多扫一次等于把整个
     * 目录再走一遍，几十万文件时那一下会明显卡住。数字口径统一取「实际删掉的」。
     */
    fun clean(report: JunkReport): JunkCleanOutcome {
        var deleted = 0
        var freed = 0L
        var failed = 0
        val failures = mutableListOf<String>()

        for (target in report.targets) {
            val path = target.path
            if (!path.exists()) continue
            if (path.isFile) {
                val size = runCatching { path.length() }.getOrDefault(0L)
                if (runCatching { path.delete() }.getOrDefault(false)) {
                    deleted++
                    freed += size
                } else {
                    failed++
                    if (failures.size < 5) failures += path.absolutePath
                }
                continue
            }
            // walkBottomUp：先删叶子文件，最后才轮到（空）子目录。顶层目录本身保留。
            path.walkBottomUp().forEach { entry ->
                if (entry == path) return@forEach
                if (entry.isFile) {
                    val size = runCatching { entry.length() }.getOrDefault(0L)
                    if (runCatching { entry.delete() }.getOrDefault(false)) {
                        deleted++
                        freed += size
                    } else {
                        failed++
                        if (failures.size < 5) failures += entry.absolutePath
                    }
                } else {
                    // 子目录删不掉不算失败（里面还有删不掉的文件时它本来就删不掉），
                    // 但尽力删一次：日志目录里的空子目录留着没意义。
                    runCatching { entry.delete() }
                }
            }
        }
        return JunkCleanOutcome(
            deletedFiles = deleted,
            freedBytes = freed,
            failedFiles = failed,
            failures = failures,
        )
    }
}
