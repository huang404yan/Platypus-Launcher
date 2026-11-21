package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.io.IOException

/**
 * 把整份游戏目录复制到另一个位置。
 *
 * 为什么不直接 `rename`：安卓外部存储上的 `/sdcard` 是 FUSE 挂载，跨目录的
 * `rename` 在多数机型上直接报 `EXDEV`；就算同卷能改名，改完之后某个进程还开着
 * 里面的文件也会失败。复制再加「原件留着」对用户最安全：**复制是加法，出错了
 * 原件还在**。
 *
 * 为什么不删原件：搬家这类操作失手一次就是几个 G 的存档没了。复制完让用户自己
 * 用文件管理器确认新位置能进游戏、再去删旧的 —— 界面只负责把话说明白。
 *
 * 取舍：**不覆盖**。目标里已经有同名文件就跳过并计入 [Result.skipped]。跨越两次
 * 「搬家」的情况下目标可能已经有一半内容，跳过比覆盖安全 —— 覆盖等于把用户后来
 * 在新位置攒的东西清掉。
 */
object GameRootCopier {

    /** 目录递归上限。正常游戏目录十几层就到顶，超过这个数只可能是符号链接成环。 */
    private const val MAX_DEPTH = 32

    /**
     * 复制结果。
     *
     * [skipped] 是没复制的条目数：目标已有同名文件、源里的符号链接（不跟过去）、同一目录被链接绕回来第二次。
     * [failures] 只记「读了但写不进去」的条目，路径 + 一句原因；空表示全成功。
     * 中途不抛异常：一个文件没权限不该让剩下几百个文件都不复制。
     */
    data class Result(
        val files: Int = 0,
        val bytes: Long = 0,
        val skipped: Int = 0,
        val failures: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = failures.isEmpty()
    }

    /**
     * 把 [from] 整棵树复制进 [to]。
     *
     * [onProgress] 每复制完一个文件回调一次（文件数, 累计字节），界面用它刷一行进度。
     * 直接在调用线程跑，调用方自己切到 IO 线程。
     */
    fun copy(from: File, to: File, onProgress: (Int, Long) -> Unit = { _, _ -> }): Result {
        if (!from.isDirectory) return Result(failures = listOf(from.path + " 不是目录"))
        val source = from.absoluteFile.normalize()
        val target = to.absoluteFile.normalize()
        if (source == target) return Result(failures = listOf("源和目标是同一个目录"))
        // 目标在源里面会让遍历边复制边长大，最后把复制出来的东西再复制一遍。
        if (target.path.startsWith(source.path + File.separator)) {
            return Result(failures = listOf("目标目录不能放在源目录里面"))
        }

        var files = 0
        var bytes = 0L
        var skipped = 0
        val failures = mutableListOf<String>()

        val stack = ArrayDeque<Triple<File, File, Int>>()
        stack.addLast(Triple(source, target, 0))
        val visited = HashSet<String>()
        while (stack.isNotEmpty()) {
            val (srcDir, dstDir, depth) = stack.removeLast()
            // 符号链接指回上层时目录树会成环。两道闸：认过的规范路径防同一层被反复展开，
            // 深度上限防极端嵌套 —— 成环时深度涨得比文件数慢得多，只靠它要跑很久。
            if (!visited.add(canonicalOf(srcDir))) {
                skipped++
                continue
            }
            if (depth > MAX_DEPTH) {
                failures += srcDir.path + "：目录层级超过 " + MAX_DEPTH + " 层，跳过"
                continue
            }
            if (!dstDir.exists() && !dstDir.mkdirs()) {
                failures += dstDir.path + "：建不了目录"
                continue
            }
            val children = srcDir.listFiles()
            if (children == null) {
                failures += srcDir.path + "：读不了目录"
                continue
            }
            for (child in children) {
                val dst = File(dstDir, child.name)
                // 符号链接可能指向目录树之外，跟过去等于把别处的东西也搬来一份。
                // 先判链接再判目录：`isDirectory` 会顺着链接走，判出来的「目录」其实是别人家的。
                if (isLink(child)) {
                    skipped++
                    continue
                }
                if (child.isDirectory) {
                    stack.addLast(Triple(child, dst, depth + 1))
                    continue
                }
                if (dst.exists()) {
                    skipped++
                    continue
                }
                try {
                    child.copyTo(dst, overwrite = false)
                    files++
                    bytes += child.length()
                    onProgress(files, bytes)
                } catch (e: IOException) {
                    failures += child.path + "：" + (e.message ?: e.javaClass.simpleName)
                } catch (e: SecurityException) {
                    failures += child.path + "：没有权限"
                }
            }
        }
        return Result(files, bytes, skipped, failures)
    }

    private fun isLink(file: File): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private fun canonicalOf(dir: File): String =
        runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
}
