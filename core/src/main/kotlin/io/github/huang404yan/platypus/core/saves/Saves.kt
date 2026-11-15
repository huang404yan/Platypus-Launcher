package io.github.huang404yan.platypus.core.saves

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 一个存档（世界）。
 *
 * [dir] 是 `saves/` 下面那一层目录；[name] 是目录名，也是游戏用来认世界的键。
 * [info] 读 `level.dat` 得到，读不到就是 null —— 这时照样列出来，
 * 显示名退回目录名（[displayName] 已经处理了）。
 */
data class WorldEntry(
    val dir: File,
    val name: String,
    val sizeBytes: Long = 0L,
    val fileCount: Int = 0,
    val lastModified: Long = 0L,
    val info: LevelDat.Info? = null,
    /** 有 `icon.png` 时界面上能画缩略图；没有就用占位。 */
    val hasIcon: Boolean = false,
    /** 目录里完全没有 `level.dat`（不是世界，或者只是复制了一半）。 */
    val broken: Boolean = false,
) {
    /** 界面上显示的世界名：优先 `level.dat` 里的 LevelName。 */
    val displayName: String
        get() = info?.levelName?.takeIf { it.isNotBlank() } ?: name

    /** 图标文件，只有 [hasIcon] 为真时才有。 */
    val iconFile: File? get() = if (hasIcon) File(dir, "icon.png") else null
}

/** 一次扫描的结果。 */
data class SavesSnapshot(
    val savesDir: File,
    val exists: Boolean,
    val worlds: List<WorldEntry> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val totalBytes: Long get() = worlds.sumOf { it.sizeBytes }
}

/**
 * 一份自动/手动备份。
 *
 * 备份是 **一个 zip 文件**而不是目录：单个文件好拷、好发、好删，
 * 用户把它丢进群里也不会因为多出几十层的子目录把自己绕晕。
 */
data class WorldBackup(
    val zip: File,
    /** 备份时的世界目录名（还原时默认用它）。 */
    val name: String,
    /** 备份时间，取文件名里的时间戳。 */
    val createdAt: Long,
    val sizeBytes: Long,
)

/** 还原结果。[ok] 为假时 [message] 一定不是空串。 */
data class RestoreResult(
    val ok: Boolean = false,
    val target: File? = null,
    val message: String = "",
    val files: Int = 0,
)

/**
 * 存档管理：列、备份、还原、删。
 *
 * 只碰 `<游戏目录>/saves/` 与 `<游戏根目录>/platypus/saves-backup/` 两处，
 * 不读 `level.dat` 以外的任何游戏文件，也不联网。
 *
 * 为什么备份走 zip 而不是复制目录：复制一份几百兆的世界要等十几秒，
 * 而且用户手滑点两次就会在盘上留下两个同名目录；zip 只有一个文件、有名字带时间戳，
 * 删起来也不会误删到正在用的世界。
 */
object Saves {
    /** 游戏自己认的目录名。 */
    const val SAVES_DIR_NAME = "saves"

    /** 备份放在 `<gameRoot>/platypus/saves-backup/`，与删实例时搬走存档的位置一致。 */
    const val BACKUP_DIR_NAME = "saves-backup"

    /** 一个世界的文件数上限：有人把整个硬盘 junction 进 saves/ 时别把界面拖死。 */
    private const val MAX_FILES_PER_WORLD = 20_000

    /** 单次备份最多打这么多文件，超了就在结果里说清楚（而不是悄悄截断）。 */
    private const val MAX_BACKUP_FILES = 50_000

    // 允许结尾多一个 -2：同一秒点两次备份时不覆盖，第二份会带序号。
    private val BACKUP_NAME = Regex("^(.+)-(\\d{8}-\\d{6})(?:-(\\d+))?\\.zip$")

    fun savesDir(gameDir: File): File = File(gameDir, SAVES_DIR_NAME)

    /** 备份目录：跟着游戏根目录走，换实例也能看到同一批备份。 */
    fun backupDir(gameRoot: File): File = File(File(gameRoot, "platypus"), BACKUP_DIR_NAME)

    /**
     * 扫一遍存档目录。
     *
     * 目录不存在不是错误（新装的游戏就没有），返回 [SavesSnapshot.exists] = false 的空表。
     * 单个世界读不出来也只记一条 warning，不影响别的世界。
     */
    fun scan(gameDir: File): SavesSnapshot {
        val dir = savesDir(gameDir)
        if (!dir.isDirectory) return SavesSnapshot(savesDir = dir, exists = false)
        val warnings = mutableListOf<String>()
        val worlds = ArrayList<WorldEntry>()
        val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
        for (child in children) {
            if (!child.isDirectory) continue
            if (child.name.startsWith(".")) continue // 回收站之类的隐藏目录不列
            runCatching {
                worlds += worldOf(child)
            }.onFailure { warnings += child.name + "：读不出来（" + (it.message ?: it.javaClass.simpleName) + "）" }
        }
        return SavesSnapshot(
            savesDir = dir,
            exists = true,
            // 最近玩过的排前面：找存档时找的几乎总是刚刚那个。
            worlds = worlds.sortedWith(
                compareByDescending<WorldEntry> { it.info?.lastPlayed ?: 0L }
                    .thenByDescending { it.lastModified }
                    .thenBy { it.name.lowercase() },
            ),
            warnings = warnings,
        )
    }

    private fun worldOf(dir: File): WorldEntry {
        var files = 0
        var bytes = 0L
        var lastModified = 0L
        runCatching {
            dir.walkTopDown()
                .onEnter { it == dir || !it.name.startsWith(".") }
                .forEach { file ->
                    if (files >= MAX_FILES_PER_WORLD) return@forEach
                    if (file.isFile) {
                        files++
                        bytes += runCatching { file.length() }.getOrDefault(0L)
                        val stamp = runCatching { file.lastModified() }.getOrDefault(0L)
                        if (stamp > lastModified) lastModified = stamp
                    }
                }
        }
        val info = LevelDat.read(dir)
        return WorldEntry(
            dir = dir,
            name = dir.name,
            sizeBytes = bytes,
            fileCount = files,
            lastModified = lastModified,
            info = info,
            hasIcon = File(dir, "icon.png").isFile,
            broken = info == null,
        )
    }

    /** 读备份目录里的所有 zip，最近的排前面。目录不存在返回空表。 */
    fun backups(gameRoot: File): List<WorldBackup> {
        val dir = backupDir(gameRoot)
        if (!dir.isDirectory) return emptyList()
        return runCatching { dir.listFiles() }.getOrNull().orEmpty()
            .filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            .mapNotNull { backupOf(it) }
            .sortedByDescending { it.createdAt }
    }

    private fun backupOf(zip: File): WorldBackup? {
        val base = zip.name.removeSuffix(".zip").removeSuffix(".ZIP")
        val match = BACKUP_NAME.matchEntire(zip.name) ?: return null
        val name = match.groupValues[1]
        val stamp = parseStamp(match.groupValues[2])
        return WorldBackup(
            zip = zip,
            name = name.ifBlank { base },
            createdAt = stamp,
            sizeBytes = runCatching { zip.length() }.getOrDefault(0L),
        )
    }

    private fun parseStamp(raw: String): Long {
        // 20261115-143012
        val parts = raw.split("-")
        if (parts.size != 2) return 0L
        val date = parts[0]
        val time = parts[1]
        if (date.length != 8 || time.length != 6) return 0L
        return runCatching {
            val calendar = java.util.Calendar.getInstance()
            calendar.clear()
            calendar.set(
                date.substring(0, 4).toInt(),
                date.substring(4, 6).toInt() - 1,
                date.substring(6, 8).toInt(),
                time.substring(0, 2).toInt(),
                time.substring(2, 4).toInt(),
                time.substring(4, 6).toInt(),
            )
            calendar.timeInMillis
        }.getOrDefault(0L)
    }

    /**
     * 把一个世界打成 zip 放进备份目录。
     *
     * zip 里的路径 **相对世界目录**，所以解出来就是一份能直接塞回 `saves/` 的世界 ——
     * 不套一层 `<世界名>/`，那样还原时还得再剥一层，也容易被人手工解开后多套一层。
     *
     * 同名备份不覆盖：文件名带秒级时间戳，同一秒点两次会加 `-2`。
     */
    fun backup(world: WorldEntry, gameRoot: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): WorldBackup {
        val files = collectFiles(world.dir)
        if (files.isEmpty()) throw java.io.IOException("这个存档目录里没有文件：" + world.dir.absolutePath)
        if (files.size > MAX_BACKUP_FILES) {
            throw java.io.IOException("这个存档有 " + files.size + " 个文件，超过单次备份上限 " + MAX_BACKUP_FILES + "。先手动清一清再备份。")
        }
        val dir = backupDir(gameRoot)
        if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("建不出备份目录：" + dir.absolutePath)
        val stamp = stampText()
        var target = File(dir, safeFileName(world.name) + "-" + stamp + ".zip")
        var index = 2
        while (target.exists()) {
            target = File(dir, safeFileName(world.name) + "-" + stamp + "-" + index + ".zip")
            index++
        }
        val temp = File(dir, target.name + ".part")
        var written = 0
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                for (file in files) {
                    val relative = relativeOf(world.dir, file) ?: continue
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    written++
                    onProgress(written, files.size)
                }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            if (temp.exists()) runCatching { temp.delete() }
        }
        return WorldBackup(
            zip = target,
            name = world.name,
            createdAt = System.currentTimeMillis(),
            sizeBytes = runCatching { target.length() }.getOrDefault(0L),
        )
    }

    /**
     * 把一个备份 zip 还原成 `saves/<name>`。
     *
     * @param name 目标世界目录名；空串时用 zip 文件名里那个名字。
     * @param overwrite 目标已存在时是否覆盖。默认 false —— 覆盖是删除的一种，
     *   要做也得由界面先问过用户。
     */
    fun restore(
        zip: File,
        gameDir: File,
        name: String = "",
        overwrite: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): RestoreResult {
        if (!zip.isFile) return RestoreResult(message = "找不到这个备份文件：" + zip.absolutePath)
        val saves = savesDir(gameDir)
        if (!saves.isDirectory && !saves.mkdirs()) {
            return RestoreResult(message = "建不出存档目录：" + saves.absolutePath)
        }
        val wanted = name.ifBlank { backupOf(zip)?.name.orEmpty() }.trim()
        if (wanted.isBlank()) return RestoreResult(message = "不知道该还原成哪个世界名。")
        val safe = safeFileName(wanted)
        if (safe != wanted) return RestoreResult(message = "世界名里有不能用在目录名里的字符：" + wanted)
        val target = File(saves, safe)
        if (target.exists() && !overwrite) {
            return RestoreResult(
                ok = false,
                target = target,
                message = "存档目录里已经有一个叫「" + safe + "」的世界了。换个名字，或者先把它备份掉再覆盖。",
            )
        }

        val entries = ArrayList<Pair<ZipEntry, File>>()
        var total = 0
        try {
            ZipFile(zip).use { archive ->
                for (entry in archive.entries()) {
                    if (entry.isDirectory) continue
                    val relative = safeZipPath(entry.name) ?: continue
                    entries += entry to relative
                    total++
                }
            }
        } catch (e: Exception) {
            return RestoreResult(message = "这个 zip 打不开：" + (e.message ?: e.javaClass.simpleName))
        }
        if (entries.isEmpty()) return RestoreResult(message = "这个 zip 是空的。")

        val staging = File(saves, ".restoring-" + System.currentTimeMillis())
        var done = 0
        try {
            ZipFile(zip).use { archive ->
                for ((entry, relative) in entries) {
                    val out = File(staging, relative.path)
                    if (!out.canonicalPath.startsWith(staging.canonicalPath)) continue
                    out.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input -> out.outputStream().buffered().use { input.copyTo(it) } }
                    done++
                    onProgress(done, total)
                }
            }
            if (target.exists()) {
                // 走到这里说明调用方明确要求覆盖（overwrite = true）。
                val trash = File(saves, "." + safe + ".replaced-" + System.currentTimeMillis())
                if (!target.renameTo(trash)) throw java.io.IOException("旧的「" + safe + "」搬不开，可能被游戏占着")
                if (!staging.renameTo(target)) {
                    runCatching { trash.renameTo(target) }
                    throw java.io.IOException("新的「" + safe + "」放不进去")
                }
                runCatching { trash.deleteRecursively() }
            } else if (!staging.renameTo(target)) {
                throw java.io.IOException("放不进存档目录：" + target.absolutePath)
            }
        } catch (e: Exception) {
            runCatching { staging.deleteRecursively() }
            return RestoreResult(ok = false, target = target, message = "还原失败：" + (e.message ?: e.javaClass.simpleName))
        }
        return RestoreResult(ok = true, target = target, message = "已还原到 " + target.absolutePath, files = done)
    }

    /**
     * 删一个世界。
     *
     * **先备份再删**，且备份失败就整个中止 —— 删存档是不可撤销的操作，
     * 而备份只要一两秒。返回的备份给界面显示「删了，可以在这里找回来」。
     */
    fun delete(world: WorldEntry, gameRoot: File): WorldBackup {
        val backup = backup(world, gameRoot)
        world.dir.deleteRecursively()
        if (world.dir.exists()) {
            throw java.io.IOException("删不干净：" + world.dir.absolutePath + "（可能被游戏或资源管理器占着）")
        }
        return backup
    }

    /** 导入一个外部 zip 当存档（用户从群里下了一份世界）。 */
    fun importZip(zip: File, gameDir: File, name: String = "", overwrite: Boolean = false): RestoreResult =
        restore(zip, gameDir, name = name, overwrite = overwrite)

    private fun collectFiles(dir: File): List<File> {
        val files = ArrayList<File>()
        runCatching {
            dir.walkTopDown()
                .onEnter { it == dir || !it.name.startsWith(".") }
                .forEach { if (it.isFile) files += it }
        }
        return files
    }

    /** 相对路径，一律用 `/` —— zip 里写反斜杠的话，别的工具解出来是一个名字奇怪的文件。 */
    private fun relativeOf(root: File, file: File): String? {
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: root.absolutePath
        val path = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
        if (!path.startsWith(rootPath + File.separator)) return null
        return path.substring(rootPath.length + 1).replace(File.separatorChar, '/')
    }

    /** zip 条目路径的兜底：拦掉 `..`、绝对路径与盘符。 */
    private fun safeZipPath(raw: String): File? {
        val normalized = raw.replace('\\', '/').trimStart('/')
        if (normalized.isBlank()) return null
        if (normalized.contains(':')) return null
        val parts = normalized.split('/').filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return File(parts.joinToString(File.separator))
    }

    private fun stampText(): String {
        val now = java.util.Calendar.getInstance()
        fun two(value: Int) = if (value < 10) "0" + value else value.toString()
        return now.get(java.util.Calendar.YEAR).toString() + two(now.get(java.util.Calendar.MONTH) + 1) +
            two(now.get(java.util.Calendar.DAY_OF_MONTH)) + "-" +
            two(now.get(java.util.Calendar.HOUR_OF_DAY)) + two(now.get(java.util.Calendar.MINUTE)) +
            two(now.get(java.util.Calendar.SECOND))
    }

    /** 世界名当文件名用：把 Windows 不允许的字符换掉，别把空格之外的都扔掉。 */
    fun safeFileName(raw: String): String {
        val cleaned = raw.trim().map { ch ->
            when {
                ch == '\\' || ch == '/' || ch == ':' || ch == '*' || ch == '?' ||
                    ch == '"' || ch == '<' || ch == '>' || ch == '|' -> '_'
                ch.code < 32 -> '_'
                else -> ch
            }
        }.joinToString("")
        return cleaned.trim().trimEnd('.').ifBlank { "world" }
    }
}
