package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 三种「不能删」的情况。
 *
 * 都做成明确的枚举而不是一句人话字符串：界面要据此决定按钮是不是灰的，
 * 测试也要断言「到底是哪一种拒绝」，字符串比对太脆。
 */
enum class DeletionRefusal {
    /** 版本目录不存在（多半是已经被删过一次，或者 id 打错了）。 */
    VERSION_DIR_MISSING,

    /** 目录跑到 <gameRoot>/versions/ 之外了。宁可拒绝，也不能删别人的目录。 */
    OUTSIDE_GAME_ROOT,

    /** instances.json 里还有另一条记录指着同一个目录，删了会连累那个实例。 */
    SHARED_BY_INSTANCES,
}

/**
 * 删除一个版本目录之前算出来的「将要发生什么」。
 *
 * 单独做成数据类，是为了让界面能在动手之前把「删哪个绝对路径、多少文件、多大、
 * 存档留不留」摆给用户看；也让 inspect 与 delete 之间的判断能被单测逐项覆盖。
 *
 * @param versionDir 目标目录的绝对路径（已 canonical 化，避免 ../ 之类的花招）
 * @param refusal 不能删的原因；null 表示可以删
 * @param refusalReason 给界面直接显示的中文原因
 * @param fileCount 目录下普通文件个数（不含目录本身）
 * @param totalBytes 目录下所有普通文件的字节数
 * @param savesFileCount / savesBytes 存档目录单独统计一份，界面要显示「保留的存档有多大」
 * @param sharedWith 共用同一目录的其他实例（id + 目录），只在不共用时为空
 */
data class DeletionPlan(
    val gameRoot: File,
    val instanceId: String,
    val versionDir: File,
    val refusal: DeletionRefusal? = null,
    val refusalReason: String = "",
    val fileCount: Int = 0,
    val totalBytes: Long = 0L,
    val hasMods: Boolean = false,
    val hasSaves: Boolean = false,
    val savesFileCount: Int = 0,
    val savesBytes: Long = 0L,
    val hasResourcePacks: Boolean = false,
    val sharedWith: List<String> = emptyList(),
) {
    /** 能不能删；界面据此禁用「删除」按钮。 */
    val deletable: Boolean get() = refusal == null
}

/** 删除失败时指出「哪个文件 / 什么错」，不能只说一句「删除失败」。 */
data class DeleteFailure(
    val path: String,
    val message: String,
)

/** 一次删除的结果。失败时也要把「已经改成了什么名字」告诉用户，否则找不回来。 */
data class DeleteOutcome(
    val ok: Boolean,
    val instanceId: String,
    val versionDir: File,
    /** 现在磁盘上的目录名：成功且真的删掉了是 null；回滚后是原路径；回收站模式是改名后的路径。 */
    val renamedTo: File? = null,
    val deletedFiles: Int = 0,
    val freedBytes: Long = 0L,
    /** 存档是否被保留（includeSaves=false 且确实有存档时为 true）。 */
    val savesKept: Boolean = false,
    /** 存档被挪到了哪里；没有存档或连存档一起删时为 null。 */
    val savesMovedTo: File? = null,
    val failure: DeleteFailure? = null,
    /** 失败之后是否成功把目录名改了回来。 */
    val rolledBack: Boolean = false,
) {
    /** 给界面/日志用的一句总结。 */
    val summary: String
        get() = when {
            ok && renamedTo != null -> "已把 " + versionDir.name + " 改名为 " + renamedTo.name + "，没有递归删除。"
            ok -> "已删除 " + versionDir.name + "：" + deletedFiles + " 个文件，释放 " + formatBytes(freedBytes) + "。"
            else -> "删除失败：" + (failure?.let { it.path + " — " + it.message } ?: "未知错误") +
                if (rolledBack) "（目录已改回原名）" else "（目录保持改名状态：" + (renamedTo?.absolutePath ?: versionDir.absolutePath) + "）"
        }
}

/** 字节数转成便于阅读的 MB/GB；界面提示用。 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MB", mb)
    return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
}

/**
 * 删除一个已登记实例对应的版本目录。
 *
 * 为什么不用回收站库：项目是纯 Kotlin + 零第三方依赖，Windows 的回收站要走 COM
 * （JNA/JNI），Linux 要走 XDG trash 规范，两条路都给 APK 与桌面端加一堆平台代码。
 * 这里退一步用「同卷改名 + 递归删」：改名是原子的、失败也无损，改名之后再删，
 * 中途出错还能把名字改回来，用户至少能找回目录。
 *
 * 所有磁盘操作都是同步阻塞的，调用方（界面）负责放到 Dispatchers.IO。
 */
object InstanceDeleter {

    /** 默认的实例索引位置，与 [JsonInstanceRepository.defaultIndexFile] 保持一致。 */
    fun defaultIndexFile(gameRoot: File): File = JsonInstanceRepository.defaultIndexFile(gameRoot)

    /**
     * 算出删除计划，不写盘。
     *
     * @param indexFile 实例索引；默认 <gameRoot>/platypus/instances.json，测试可以换成别的文件
     */
    fun inspect(
        gameRoot: File,
        instanceId: String,
        indexFile: File = defaultIndexFile(gameRoot),
        knownInstances: List<Instance>? = null,
    ): DeletionPlan {
        val id = instanceId.trim()
        val versionsRoot = File(gameRoot, "versions")
        val raw = File(versionsRoot, id)

        // ① 越界：id 里带分隔符、或 canonical 之后不在 versions/ 下面，一律拒绝。
        //    先于「存在性」判断，否则 ../ 指向的目录恰好存在时会被当成正常目标。
        if (id.isEmpty() || !isInside(raw, versionsRoot)) {
            return DeletionPlan(
                gameRoot = gameRoot,
                instanceId = id,
                versionDir = raw.absoluteFile,
                refusal = DeletionRefusal.OUTSIDE_GAME_ROOT,
                refusalReason = "目标不在 " + versionsRoot.absolutePath + " 之内，拒绝删除：" + raw.absolutePath,
            )
        }

        val dir = canonical(raw)

        // ② 目录不存在
        if (!dir.isDirectory) {
            return DeletionPlan(
                gameRoot = gameRoot,
                instanceId = id,
                versionDir = dir,
                refusal = DeletionRefusal.VERSION_DIR_MISSING,
                refusalReason = "版本目录不存在：" + dir.absolutePath,
            )
        }

        // ③ 多实例共用：instances.json 里还有别的记录指着同一目录。
        //    用 instanceKey 的同一套归一化（反斜杠/大小写）比较，避免 Windows 上 A:/x 与 a:\x 被当成两处。
        val others = sharedRecords(indexFile, id, dir, knownInstances)

        val files = measure(dir)
        val savesDir = File(dir, "saves")
        val saves = if (savesDir.isDirectory) measure(savesDir) else 0 to 0L

        val refusal = if (others.isEmpty()) null else DeletionRefusal.SHARED_BY_INSTANCES
        return DeletionPlan(
            gameRoot = gameRoot,
            instanceId = id,
            versionDir = dir,
            refusal = refusal,
            refusalReason = if (others.isEmpty()) "" else
                "同一目录还被另外 " + others.size + " 个实例登记着：" + others.joinToString("；") +
                    "。先移除那些登记再删。",
            fileCount = files.first,
            totalBytes = files.second,
            hasMods = File(dir, "mods").isDirectory,
            hasSaves = savesDir.isDirectory,
            savesFileCount = saves.first,
            savesBytes = saves.second,
            hasResourcePacks = File(dir, "resourcepacks").isDirectory,
            sharedWith = others,
        )
    }

    /**
     * 执行删除。
     *
     * @param includeSaves 是否连 saves/ 一起删。默认 false：存档是用户唯一不能重新下载的东西。
     * @param moveToRecycleBin true 时只把目录改名为 <dir>.deleting-<时间戳>，不递归删除（本地回收站）。
     *   之所以保留这个参数：以后界面要出「移到回收站」按钮时，不必再改一次实现。
     */
    fun delete(
        plan: DeletionPlan,
        includeSaves: Boolean = false,
        moveToRecycleBin: Boolean = false,
    ): DeleteOutcome {
        val base = DeleteOutcome(ok = false, instanceId = plan.instanceId, versionDir = plan.versionDir)

        // 不可删的计划直接拒绝，绝不「尽力而为」。
        if (!plan.deletable) {
            return base.copy(failure = DeleteFailure(plan.versionDir.absolutePath, plan.refusalReason))
        }

        val dir = plan.versionDir
        val stamp = timestamp()
        val renamed = File(dir.parentFile, dir.name + ".deleting-" + stamp)

        // ① 先把存档搬出去。搬移走 rename（同卷瞬时、不复制），失败就什么都不动。
        var savesMovedTo: File? = null
        val savesDir = File(dir, "saves")
        if (!includeSaves && savesDir.isDirectory) {
            val backupRoot = File(File(plan.gameRoot, "platypus"), "saves-backup")
            val target = File(backupRoot, safeName(plan.instanceId) + "-" + stamp)
            val moved = runCatching {
                if (!backupRoot.isDirectory) backupRoot.mkdirs()
                savesDir.renameTo(target)
            }.getOrDefault(false)
            if (!moved) {
                return base.copy(
                    failure = DeleteFailure(savesDir.absolutePath, "存档目录搬移失败（可能被占用）"),
                    savesKept = true,
                )
            }
            savesMovedTo = target
        }

        // ② 改名成 .deleting-<时间戳>：这一步失败说明目录被占用，先别动别的。
        if (!safeRename(dir, renamed)) {
            savesMovedTo?.let { runCatching { it.renameTo(savesDir) } }
            return base.copy(
                failure = DeleteFailure(dir.absolutePath, "目录改名失败（很可能有进程占用）"),
                savesKept = savesMovedTo != null,
            )
        }

        if (moveToRecycleBin) {
            return DeleteOutcome(
                ok = true,
                instanceId = plan.instanceId,
                versionDir = dir,
                renamedTo = renamed,
                savesKept = savesMovedTo != null,
                savesMovedTo = savesMovedTo,
            )
        }

        // ③ 递归删。必须用 walkBottomUp：先删文件再删目录，否则目录非空删不掉。
        val stats = deleteTree(renamed)
        if (stats.failures.isNotEmpty()) {
            // 回滚：名字改回去，存档放回原位，用户还能手工处理剩下的文件。
            val back = safeRename(renamed, dir)
            var restoredSaves: File? = null
            if (back && savesMovedTo != null) {
                if (savesMovedTo.renameTo(File(dir, "saves"))) restoredSaves = savesMovedTo
            }
            return DeleteOutcome(
                ok = false,
                instanceId = plan.instanceId,
                versionDir = dir,
                renamedTo = if (back) dir else renamed,
                deletedFiles = stats.files,
                freedBytes = stats.bytes,
                savesKept = restoredSaves != null,
                savesMovedTo = restoredSaves,
                failure = stats.failures.first(),
                rolledBack = back,
            )
        }

        // ④ 目录本身如果还残留（不该有），也算失败，不能报「删干净了」。
        if (renamed.exists()) {
            return DeleteOutcome(
                ok = false,
                instanceId = plan.instanceId,
                versionDir = dir,
                renamedTo = renamed,
                deletedFiles = stats.files,
                freedBytes = stats.bytes,
                failure = DeleteFailure(renamed.absolutePath, "文件已删完但目录仍然存在"),
                savesKept = savesMovedTo != null,
                savesMovedTo = savesMovedTo,
            )
        }

        return DeleteOutcome(
            ok = true,
            instanceId = plan.instanceId,
            versionDir = dir,
            deletedFiles = stats.files,
            freedBytes = stats.bytes,
            savesKept = savesMovedTo != null,
            savesMovedTo = savesMovedTo,
        )
    }

    /** 删除统计：删了多少、释放多少、哪几个删不掉。 */
    private class TreeStats(val files: Int, val bytes: Long, val failures: List<DeleteFailure>)

    private fun deleteTree(root: File): TreeStats {
        var files = 0
        var bytes = 0L
        val failures = mutableListOf<DeleteFailure>()
        // walkBottomUp：叶子在前、根在后。这是官方推荐的删除遍历顺序。
        root.walkBottomUp().forEach { entry ->
            val length = if (entry.isFile) entry.length() else 0L
            if (!entry.delete()) {
                // 只记第一条：后面的多半是「父目录非空」的连带失败，全列出来反而看不清根因。
                if (failures.isEmpty()) {
                    failures += DeleteFailure(entry.absolutePath, if (entry.exists()) "删除被拒绝（只读或被占用）" else "文件已不存在")
                }
            } else if (length > 0L) {
                files++
                bytes += length
            } else if (entry.isFile) {
                files++
            }
        }
        return TreeStats(files, bytes, failures)
    }

    /** 统计一个目录下普通文件的个数与总字节。 */
    private fun measure(dir: File): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        runCatching {
            dir.walkTopDown().forEach {
                if (it.isFile) {
                    files++
                    bytes += it.length()
                }
            }
        }
        return files to bytes
    }

    /**
     * 除自己之外、目录与 [dir] 相同的记录。
     *
     * [knownInstances] 非空时直接用内存里的那份，不读盘：界面手上已经有 list() 的结果，
     * 而 inspect 是同步函数，调不了 suspend 的 list()。冒烟与单测则传 indexFile 走读盘。
     */
    private fun sharedRecords(
        indexFile: File,
        instanceId: String,
        dir: File,
        knownInstances: List<Instance>?,
    ): List<String> {
        val all = knownInstances
            ?: runCatching { JsonInstanceRepository(indexFile).snapshot() }.getOrDefault(emptyList())
        return all.filter { it.id != instanceId }
            .filter { samePath(File(it.directory), dir) }
            .map { it.name.ifBlank { it.id } + "（" + it.id + "）" }
    }

    /** 归一化路径后判断是不是同一个目录。Windows 大小写不敏感，统一 lowercase 比。 */
    private fun samePath(a: File, b: File): Boolean =
        canonical(a).path.equals(canonical(b).path, ignoreCase = true)

    /** child 是否在 parent 之内（含相等）。父级比较一律先 canonical，防 ../ 与符号链接绕过。 */
    internal fun isInside(child: File, parent: File): Boolean {
        val p = canonical(parent).path.trimEnd(File.separatorChar, '/')
        val c = canonical(child).path
        return c.equals(p, ignoreCase = true) ||
            c.startsWith(p + File.separator, ignoreCase = true) ||
            c.startsWith(p + "/", ignoreCase = true)
    }

    private fun canonical(f: File): File = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)

    /** 改名；目标已存在时先加序号，避免 Windows 上 renameTo 直接失败。 */
    private fun safeRename(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        var n = 1
        while (n <= 9) {
            val candidate = File(to.parentFile, to.name + "-" + n)
            if (!candidate.exists() && from.renameTo(candidate)) return true
            n++
        }
        return false
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    /** 版本 id 里可能出现路径分隔符之外的怪字符，兜底换成下划线再拿去当目录名。 */
    private fun safeName(raw: String): String {
        val cleaned = raw.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '_'
        }.joinToString("")
        return cleaned.ifBlank { "instance" }
    }
}
