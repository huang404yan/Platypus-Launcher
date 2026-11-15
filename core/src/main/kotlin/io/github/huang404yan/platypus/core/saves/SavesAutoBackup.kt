package io.github.huang404yan.platypus.core.saves

import java.io.File
import java.io.IOException

/**
 * 自动备份的策略。
 *
 * 三件事：开不开、隔多久一次、每个世界留几份。
 *
 * [keep] 是「每个世界」而不是「总共」：有人只玩一个世界、有人开一堆，
 * 按总数限制会让后者永远轮不到备份。
 */
data class AutoBackupPolicy(
    val enabled: Boolean = true,
    val intervalHours: Int = DEFAULT_INTERVAL_HOURS,
    val keep: Int = DEFAULT_KEEP,
) {
    companion object {
        const val DEFAULT_INTERVAL_HOURS = 24
        const val DEFAULT_KEEP = 5

        /** 间隔的合法区间：小于 1 小时等于每次启动都备份，大于一周等于没有。 */
        val INTERVAL_RANGE = 1..168

        /** 保留份数的合法区间。 */
        val KEEP_RANGE = 1..50

        val DEFAULT = AutoBackupPolicy()

        /** 把外部来的数字收敛到合法区间；[enabled] 原样传。 */
        fun of(enabled: Boolean, intervalHours: Int, keep: Int): AutoBackupPolicy = AutoBackupPolicy(
            enabled = enabled,
            intervalHours = intervalHours.coerceIn(INTERVAL_RANGE.first, INTERVAL_RANGE.last),
            keep = keep.coerceIn(KEEP_RANGE.first, KEEP_RANGE.last),
        )

        /**
         * 从界面上的两个文本框拼策略。
         *
         * 填了非数字就退回默认值而不是抛：设置页在用户还只敲了一半的时候也会调它
         * （比如切到别的二级项顺手存一次），为一个空框弹红字没有意义 —— 真正的
         * 校验在点「保存」那一步做，那里会明确说「要填整数」。
         */
        fun parse(enabled: Boolean, hoursText: String, keepText: String): AutoBackupPolicy = of(
            enabled = enabled,
            intervalHours = hoursText.trim().toIntOrNull() ?: DEFAULT_INTERVAL_HOURS,
            keep = keepText.trim().toIntOrNull() ?: DEFAULT_KEEP,
        )
    }
}

/** 一次自动备份做了什么。 */
data class AutoBackupResult(
    /** 这次真的打出来的备份。 */
    val backedUp: List<WorldBackup> = emptyList(),
    /** 按 [AutoBackupPolicy.keep] 删掉的旧备份（只有本轮成功备份过的世界才会被清理）。 */
    val removed: List<WorldBackup> = emptyList(),
    /** 没到时间、所以跳过的世界名。 */
    val skipped: List<String> = emptyList(),
    /** 备份失败的世界名 → 原因。一个失败不影响别的世界。 */
    val failed: Map<String, String> = emptyMap(),
) {
    val hasWork: Boolean get() = backedUp.isNotEmpty() || removed.isNotEmpty()

    /** 给界面用的一句话；什么都没做时是空串。 */
    fun summary(): String {
        if (!hasWork && failed.isEmpty()) return ""
        val parts = ArrayList<String>()
        if (backedUp.isNotEmpty()) parts += "备份了 " + backedUp.size + " 个世界"
        if (removed.isNotEmpty()) parts += "清理了 " + removed.size + " 份旧备份"
        if (failed.isNotEmpty()) parts += failed.size.toString() + " 个备份失败"
        return parts.joinToString("，")
    }
}

/**
 * 存档自动备份。
 *
 * 只做判断与执行，不碰界面、不持有状态：谁触发（启动游戏前、进存档页时）由调用方决定。
 *
 * 三个刻意的决定：
 *
 * 1. **按世界各自的「上次备份时间」判断，不是按全局的「上次运行时间」**。新开的世界
 *    第一次进游戏就该备一份；玩了一百小时的老世界不该因为另一个世界刚备份过就被跳过。
 * 2. **只清理本轮成功备份过的那个世界的旧备份**。没到时间的世界一份都不动 ——
 *    否则用户关掉自动备份再打开，会发现老备份被清了。
 * 3. **失败的写入不删旧备份**。新备份没成的时候删旧的，等于把唯一的退路也拿走了。
 */
object SavesAutoBackup {

    /**
     * 挑出这次该备份的世界。
     *
     * 跳过三类：目录里读不出 `level.dat` 的（复制一半的目录，备份它只是把垃圾存起来）、
     * 文件数为 0 的（空目录）、距上次备份还不到 [AutoBackupPolicy.intervalHours] 小时的。
     */
    fun dueWorlds(
        snapshot: SavesSnapshot,
        backups: List<WorldBackup>,
        policy: AutoBackupPolicy = AutoBackupPolicy.DEFAULT,
        now: Long = System.currentTimeMillis(),
    ): List<WorldEntry> {
        if (!policy.enabled) return emptyList()
        val lastOf = lastBackupByWorld(backups)
        val threshold = policy.intervalHours.toLong() * 60L * 60L * 1000L
        return snapshot.worlds.filter { world ->
            if (world.broken) return@filter false
            if (world.fileCount <= 0) return@filter false
            val last = lastOf[world.name]?.createdAt ?: 0L
            // 时钟被调回去（last 在未来）也算该备份：宁可多备一份，不要永远不备。
            last == 0L || last > now || now - last >= threshold
        }
    }

    /** 每个世界最近一份备份（键是世界目录名）。 */
    fun lastBackupByWorld(backups: List<WorldBackup>): Map<String, WorldBackup> {
        val out = LinkedHashMap<String, WorldBackup>()
        for (backup in backups.sortedByDescending { it.createdAt }) {
            if (!out.containsKey(backup.name)) out[backup.name] = backup
        }
        return out
    }

    /**
     * 某个世界应该删掉的旧备份：按时间倒序，第 [keep] 份之后的全在结果里。
     *
     * 只算这个世界的，别的不动；[keep] 小于 1 时按 1 处理（最新那份永远留着）。
     */
    fun excessBackups(backups: List<WorldBackup>, worldName: String, keep: Int): List<WorldBackup> {
        val safeKeep = keep.coerceAtLeast(1)
        return backups.filter { it.name == worldName }
            .sortedByDescending { it.createdAt }
            .drop(safeKeep)
    }

    /**
     * 跑一轮：该备的备、备完清理该世界超额的旧备份。
     *
     * @param onProgress 世界名与「第几个 / 共几个世界」，给界面显示用。
     */
    fun run(
        gameDir: File,
        gameRoot: File,
        policy: AutoBackupPolicy = AutoBackupPolicy.DEFAULT,
        now: Long = System.currentTimeMillis(),
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): AutoBackupResult {
        if (!policy.enabled) return AutoBackupResult()
        val snapshot = Saves.scan(gameDir)
        if (!snapshot.exists) return AutoBackupResult()
        var backups = Saves.backups(gameRoot)
        val due = dueWorlds(snapshot, backups, policy, now)
        if (due.isEmpty()) {
            return AutoBackupResult(skipped = snapshot.worlds.map { it.name })
        }

        val backedUp = ArrayList<WorldBackup>()
        val removed = ArrayList<WorldBackup>()
        val failed = LinkedHashMap<String, String>()
        due.forEachIndexed { index, world ->
            onProgress(world.displayName, index + 1, due.size)
            val made = try {
                Saves.backup(world, gameRoot)
            } catch (e: IOException) {
                failed[world.name] = e.message ?: e.javaClass.simpleName
                null
            } catch (e: RuntimeException) {
                failed[world.name] = e.message ?: e.javaClass.simpleName
                null
            }
            if (made == null) return@forEachIndexed
            backedUp += made
            backups = backups + made
            // 新备份成立之后才清理：失败时那份旧的可能是唯一的退路。
            for (stale in excessBackups(backups, world.name, policy.keep)) {
                if (runCatching { stale.zip.delete() }.getOrDefault(false)) {
                    removed += stale
                    backups = backups.filterNot { it.zip == stale.zip }
                }
            }
        }
        val dueNames = due.map { it.name }.toSet()
        return AutoBackupResult(
            backedUp = backedUp,
            removed = removed,
            skipped = snapshot.worlds.map { it.name }.filterNot { it in dueNames },
            failed = failed,
        )
    }
}