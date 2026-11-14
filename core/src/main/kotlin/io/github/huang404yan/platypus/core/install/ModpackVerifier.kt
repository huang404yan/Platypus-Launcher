package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.mods.ModUpdateChecker
import io.github.huang404yan.platypus.core.modpack.ModpackFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 一个没有落好的包内文件。
 *
 * [reason] 是给用户看的一句话（中文、带具体数字），[path] 是清单里的相对路径，
 * 这样用户能自己进目录去看，也能拿去搜。
 */
data class ModpackFileIssue(
    val path: String,
    val reason: String,
) {
    /** 只有文件名，塞进提示行时比整条路径好读。 */
    val fileName: String get() = path.substringAfterLast('/')
}

/**
 * 导入结束前的磁盘核对。
 *
 * 为什么下载器已经算过 sha1 了还要再核一遍：下载器保证的是「这个文件下完那一刻是对的」，
 * 而整合包导入是一个跨好几分钟、上百个文件的流程，中途可能出现进程被杀、任务被取消、
 * 别的安装器往同一目录写、杀毒软件把 jar 删掉或截断。那些情况下
 * [io.github.huang404yan.platypus.core.download.DownloadReport] 早就返回过了，
 * 界面拿着旧结论说「导入完成」，用户进游戏才发现少了东西 —— 这正是要堵的窟窿。
 *
 * 代价控制：清单里带 fileSize 的文件（Modrinth 的 .mrpack 基本全带）只 stat 一下比大小，
 * 不读盘算摘要；只有大小未知的那些才退回算 sha1。这样一个 77 文件 / 650 MB 的包
 * 核对只要几毫秒，而不是把 650 MB 重读一遍。
 */
object ModpackVerifier {

    /** 与 HttpDownloader 保持一致：未完成的下载留在 .part 里。 */
    const val PART_SUFFIX = ".part"

    /** 提示里最多列几个文件名，剩下的折成「等 N 个」。 */
    const val MAX_LISTED = 5

    /**
     * 按清单核对 [instanceDir] 下的文件，返回所有对不上的项（空列表 = 全齐）。
     *
     * 不抛异常：单个文件读失败也算一条 issue，因为这一趟的目的就是「把问题收集全」，
     * 中途抛出会让用户每次只看到一个毛病。
     */
    suspend fun verify(
        instanceDir: File,
        files: List<ModpackFile>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ModpackFileIssue> = withContext(Dispatchers.IO) {
        val issues = mutableListOf<ModpackFileIssue>()
        val total = files.size
        for ((index, file) in files.withIndex()) {
            onProgress(index, total)
            val target = File(instanceDir, file.path)
            if (!target.isFile) {
                // 目标是空的但半成品还在：这不是「包没写这个文件」，是「下到一半断了」。
                val part = File(target.path + PART_SUFFIX)
                issues += if (part.isFile) {
                    ModpackFileIssue(
                        file.path,
                        "只下到一半（" + formatBytes(part.length()) + " / " + sizeText(file.size) + "），还没落到最终位置",
                    )
                } else {
                    ModpackFileIssue(file.path, "文件不存在")
                }
                continue
            }
            val actual = target.length()
            if (file.size > 0L && actual != file.size) {
                issues += ModpackFileIssue(
                    file.path,
                    "大小不符（期望 " + formatBytes(file.size) + "，实际 " + formatBytes(actual) + "）",
                )
                continue
            }
            // 大小未知时没有别的证据可用，只能读一遍算 sha1。
            if (file.size <= 0L && !file.sha1.isNullOrBlank()) {
                val digest = runCatching { ModUpdateChecker.sha1Of(target) }.getOrNull()
                if (digest == null) {
                    issues += ModpackFileIssue(file.path, "读不了这个文件")
                } else if (!digest.equals(file.sha1, ignoreCase = true)) {
                    issues += ModpackFileIssue(file.path, "内容与清单不一致（sha1 不符）")
                }
            }
        }
        onProgress(total, total)
        issues
    }

    /**
     * 把核对结果拼成一句能给用户看的话，例如：
     * 「核对包内文件时有 1 个不对：Cobblemon-fabric-1.8.4+1.21.1.jar（只下到一半（51.6 MB / 140.0 MB），还没落到最终位置）」。
     *
     * 全部通过时返回空串，调用方据此决定要不要弹东西。
     */
    fun summarize(issues: List<ModpackFileIssue>): String {
        if (issues.isEmpty()) return ""
        val head = "核对包内文件时有 " + issues.size + " 个不对："
        val listed = issues.take(MAX_LISTED).joinToString("、") { it.fileName + "（" + it.reason + "）" }
        val rest = if (issues.size > MAX_LISTED) "等 " + issues.size + " 个" else ""
        val hasPartial = issues.any { it.reason.contains("一半") }
        val tail = if (hasPartial) "。半成品会在下次导入同一个包时自动续传。" else ""
        return head + listed + rest + tail
    }

    private fun sizeText(size: Long): String = if (size > 0L) formatBytes(size) else "大小未知"

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "未知"
        if (bytes < 1024L) return bytes.toString() + " B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex])
    }
}
