package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.io.IOException

/**
 * 把「版本隔离」期间攒在实例目录里的东西，合并回游戏根目录。
 *
 * 为什么需要它：隔离开着时，游戏读写的是 `<gameRoot>/versions/<id>/`，存档、模组、
 * 配置全在那里。用户一旦关掉隔离，游戏的 `--gameDir` 换成 `<gameRoot>/`，
 * 它就只看得见根目录下那一份 —— 实例目录里那份好好的，游戏却当它不存在。
 * 界面只提示一句「现在读写 <gameRoot> 下那一份」是不够的：对用户来说体验就是
 * **存档没了**。
 *
 * 两条刻意的取舍：
 *
 * 1. **复制，不移动。** 合并完实例目录里那份原件仍然留着。删掉容易，找回来难：
 *    用户关掉隔离试两天、又想开回去时，原件是唯一能让操作变成「无损失」的东西。
 *    代价是磁盘上多一份 —— 这件事由界面在合并前说清楚，让用户自己决定。
 * 2. **默认不覆盖同名文件。** 根目录里可能已经有同名的存档（别的实例写下的）。
 *    同名就跳过并记进结果，由用户挑留哪个。悄悄覆盖等于替用户做了个他看不见的决定。
 */
object InstanceMigration {

    /** 一个子目录（mods、saves……）的合并结果。 */
    data class EntryResult(
        val name: String,
        val copied: Int = 0,
        val skipped: Int = 0,
        val failed: Map<String, String> = emptyMap(),
    ) {
        val ok: Boolean get() = failed.isEmpty()
    }

    /**
     * 整次合并的结果。
     *
     * [entries] 里只会有「实例目录下确实有东西」的那几项；空目录不产生条目，
     * 免得结果里一长串 0，让人以为出了很多事。
     */
    data class MergeResult(
        val fromDir: File,
        val toDir: File,
        val entries: List<EntryResult> = emptyList(),
    ) {
        val copied: Int get() = entries.sumOf { it.copied }
        val skipped: Int get() = entries.sumOf { it.skipped }
        val failed: Int get() = entries.sumOf { it.failed.size }
        val total: Int get() = copied + skipped + failed
        val ok: Boolean get() = failed == 0
        /** 有东西要合并：为 false 时界面不该提这件事。 */
        val hasWork: Boolean get() = entries.isNotEmpty()
    }

    /**
     * 实例目录下有哪些每实例目录是有内容的（合并前先问一句，别让用户点盲盒）。
     *
     * 只报目录名，不统计文件数：这里的结果是给「要不要合并」用的，
     * 数完文件数再数一遍是重复劳动，真正合并时 [merge] 会给出准确数字。
     */
    fun pending(versionDir: File): List<String> =
        InstanceLayouts.perInstanceDirs(versionDir.absoluteFile)
            .filter { dir -> dir.isDirectory && dir.listFiles()?.isNotEmpty() == true }
            .map { it.name }

    /**
     * 把实例目录里的每实例目录合并进游戏根目录。
     *
     * @param overwrite 同名文件是否覆盖。默认 false：根目录里已有的同名档保留，
     *   实例目录里的那份记进 [EntryResult.skipped]，让用户自己挑。
     * @throws IOException 目录不是 `versions/<id>` 形状（推不出游戏根目录）时。
     */
    fun merge(versionDir: File, overwrite: Boolean = false): MergeResult {
        val from = versionDir.absoluteFile
        val root = InstanceLayouts.gameRootOf(from)
        // 非标准布局下 gameRootOf 会退回目录自身，此时「合并回根目录」等于往自己身上拷。
        // 与其做个循环复制的空动作，不如明确告诉调用方这里没有根目录可合。
        if (sameDir(root, from)) {
            throw IOException("这个目录不是 versions/<id> 的形状，推不出游戏根目录：" + from.absolutePath)
        }
        val entries = mutableListOf<EntryResult>()
        for (name in pending(from)) {
            val source = File(from, name)
            val target = File(root, name)
            val result = mergeOne(name, source, target, overwrite)
            if (result.copied + result.skipped + result.failed.size > 0) entries += result
        }
        return MergeResult(fromDir = from, toDir = root, entries = entries)
    }

    private fun mergeOne(name: String, source: File, target: File, overwrite: Boolean): EntryResult {
        if (!target.isDirectory && !target.mkdirs()) {
            return EntryResult(name = name, failed = mapOf(name to ("建不了目录：" + target.absolutePath)))
        }
        var copied = 0
        var skipped = 0
        val failed = linkedMapOf<String, String>()
        // 逐条走一遍而不是 copyRecursively：要按「目标是否已存在」分别计数，
        // 还得在失败时记下具体是哪个文件 —— copyRecursively 只会给一个布尔值。
        for (child in source.listFiles().orEmpty()) {
            when (val outcome = copyTree(child, File(target, child.name), overwrite)) {
                CopyOutcome.COPIED -> copied++
                CopyOutcome.SKIPPED -> skipped++
                is CopyOutcome.Failed -> failed[outcome.path] = outcome.reason
            }
        }
        return EntryResult(name = name, copied = copied, skipped = skipped, failed = failed)
    }

    private sealed interface CopyOutcome {
        data object COPIED : CopyOutcome
        data object SKIPPED : CopyOutcome
        data class Failed(val path: String, val reason: String) : CopyOutcome
    }

    /** 递归复制一个文件或目录；目录逐层往里走，目录本身已存在就直接用。 */
    private fun copyTree(source: File, target: File, overwrite: Boolean): CopyOutcome {
        if (source.isDirectory) {
            if (!target.isDirectory && !target.mkdirs() && !target.isDirectory) {
                return CopyOutcome.Failed(target.absolutePath, "建不了目录")
            }
            var copied = 0
            var skipped = 0
            var firstFailure: CopyOutcome.Failed? = null
            for (child in source.listFiles().orEmpty()) {
                when (val outcome = copyTree(child, File(target, child.name), overwrite)) {
                    CopyOutcome.COPIED -> copied++
                    CopyOutcome.SKIPPED -> skipped++
                    is CopyOutcome.Failed -> if (firstFailure == null) firstFailure = outcome
                }
            }
            // 目录级的结论：搬进去至少一个文件才算「复制过」，否则要么全跳过要么全失败。
            return when {
                firstFailure != null -> firstFailure
                copied > 0 -> CopyOutcome.COPIED
                skipped > 0 -> CopyOutcome.SKIPPED
                else -> CopyOutcome.SKIPPED
            }
        }
        if (target.exists() && !overwrite) return CopyOutcome.SKIPPED
        return try {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            CopyOutcome.COPIED
        } catch (e: Exception) {
            CopyOutcome.Failed(target.absolutePath, e.message ?: e::class.simpleName ?: "复制失败")
        }
    }

    private fun sameDir(a: File, b: File): Boolean =
        runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(a.absolutePath == b.absolutePath)
}
