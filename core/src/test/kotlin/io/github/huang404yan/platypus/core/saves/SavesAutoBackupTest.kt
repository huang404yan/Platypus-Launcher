package io.github.huang404yan.platypus.core.saves

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 自动备份的「该不该备、备了之后清哪几份」。全程离线，全部在临时目录里做。 */
class SavesAutoBackupTest {
    private val root: File = Files.createTempDirectory("platypus-autobackup-test").toFile()
    private val gameRoot: File = File(root, "game")
    private val gameDir: File = File(gameRoot, "minecraft")

    @AfterTest
    fun tearDown() {
        runCatching { root.deleteRecursively() }
    }

    @Test
    fun `默认策略下新世界第一次就该备份`() {
        writeWorld("world", "我的世界", mapOf("region/r.0.0.mca" to "abc"))
        val due = SavesAutoBackup.dueWorlds(Saves.scan(gameDir), emptyList())
        assertEquals(1, due.size)
        assertEquals("world", due.single().name)
    }

    @Test
    fun `刚备份过的世界不再备份`() {
        writeWorld("world", "我的世界", mapOf("level.dat" to "x"))
        val now = 1_700_000_000_000L
        val recent = fakeBackup("world", now - 60L * 60L * 1000L)
        val due = SavesAutoBackup.dueWorlds(Saves.scan(gameDir), listOf(recent), now = now)
        assertTrue(due.isEmpty(), due.map { it.name }.toString())
    }

    @Test
    fun `隔够了小时数就重新备份`() {
        writeWorld("world", "我的世界", mapOf("level.dat" to "x"))
        val now = 1_700_000_000_000L
        val old = fakeBackup("world", now - 25L * 60L * 60L * 1000L)
        val due = SavesAutoBackup.dueWorlds(Saves.scan(gameDir), listOf(old), now = now)
        assertEquals(1, due.size)
    }

    @Test
    fun `间隔可以按策略改`() {
        writeWorld("world", "我的世界", mapOf("level.dat" to "x"))
        val now = 1_700_000_000_000L
        val twoHoursAgo = fakeBackup("world", now - 2L * 60L * 60L * 1000L)
        val tight = AutoBackupPolicy.of(enabled = true, intervalHours = 1, keep = 5)
        assertEquals(1, SavesAutoBackup.dueWorlds(Saves.scan(gameDir), listOf(twoHoursAgo), tight, now).size)
        val loose = AutoBackupPolicy.of(enabled = true, intervalHours = 6, keep = 5)
        assertTrue(SavesAutoBackup.dueWorlds(Saves.scan(gameDir), listOf(twoHoursAgo), loose, now).isEmpty())
    }

    @Test
    fun `关掉自动备份就一个都不备`() {
        writeWorld("world", "我的世界", mapOf("level.dat" to "x"))
        val off = AutoBackupPolicy.of(enabled = false, intervalHours = 24, keep = 5)
        assertTrue(SavesAutoBackup.dueWorlds(Saves.scan(gameDir), emptyList(), off).isEmpty())
        assertFalse(SavesAutoBackup.run(gameDir, gameRoot, off).hasWork)
    }

    @Test
    fun `读不出元数据的世界与空目录都不备`() {
        val saves = File(gameDir, "saves")
        // 复制一半的目录：没有 level.dat
        File(saves, "half-copied/region").mkdirs()
        File(saves, "half-copied/region/r.0.0.mca").writeText("x")
        // 空目录
        File(saves, "empty").mkdirs()
        writeWorld("good", "好的世界", mapOf("level.dat" to "x"))
        val due = SavesAutoBackup.dueWorlds(Saves.scan(gameDir), emptyList())
        assertEquals(listOf("good"), due.map { it.name })
    }

    @Test
    fun `时钟被调回过去时仍然备份`() {
        writeWorld("world", "我的世界", mapOf("level.dat" to "x"))
        val now = 1_700_000_000_000L
        // 备份时间在「现在」之后：说明系统时钟往回拨过，宁可多备一份也不要永远不备。
        val future = fakeBackup("world", now + 3L * 24L * 60L * 60L * 1000L)
        assertEquals(1, SavesAutoBackup.dueWorlds(Saves.scan(gameDir), listOf(future), now = now).size)
    }

    @Test
    fun `跑一轮会把该备的世界打成 zip`() {
        writeWorld("world", "我的世界", mapOf("level.dat" to "x", "region/r.0.0.mca" to "abc"))
        val result = SavesAutoBackup.run(gameDir, gameRoot)
        assertEquals(1, result.backedUp.size)
        assertTrue(result.backedUp.single().zip.isFile)
        assertEquals("world", result.backedUp.single().name)
        assertTrue(result.failed.isEmpty(), result.failed.toString())
        // 备份目录就落在 platypus/saves-backup，与手动备份、删除时留的那份同一个地方
        assertEquals(Saves.backupDir(gameRoot), result.backedUp.single().zip.parentFile)
    }

    @Test
    fun `只清理本轮成功备份过的那个世界的旧备份`() {
        writeWorld("a", "世界甲", mapOf("level.dat" to "x"))
        writeWorld("b", "世界乙", mapOf("level.dat" to "x"))
        // 先给世界乙备一份（时间就是现在），这样它这一轮没到时间；
        // 再补几份更早的，全部都不该被碰。
        val worldB = Saves.scan(gameDir).worlds.first { it.name == "b" }
        val fresh = Saves.backup(worldB, gameRoot)
        val dir = Saves.backupDir(gameRoot)
        val oldForB = (0 until 8).map { index ->
            val zip = File(dir, "b-2020010" + (index + 1) + "-000000.zip")
            zip.writeText("old")
            zip
        }
        val policy = AutoBackupPolicy.of(enabled = true, intervalHours = 24, keep = 2)
        val result = SavesAutoBackup.run(gameDir, gameRoot, policy)
        assertEquals(listOf("a"), result.backedUp.map { it.name })
        assertTrue(fresh.zip.isFile, "世界乙的最新备份应当还在")
        assertTrue(oldForB.all { it.isFile }, "世界乙的旧备份不该被清理")
    }

    @Test
    fun `同一个世界备份多了按 keep 从最旧的开始删`() {
        writeWorld("world", "我的世界", mapOf("level.dat" to "x"))
        val dir = Saves.backupDir(gameRoot)
        dir.mkdirs()
        val olds = (1..4).map { day ->
            val zip = File(dir, "world-2026110" + day + "-000000.zip")
            zip.writeText("old")
            zip
        }
        val policy = AutoBackupPolicy.of(enabled = true, intervalHours = 1, keep = 2)
        val result = SavesAutoBackup.run(gameDir, gameRoot, policy)
        assertEquals(1, result.backedUp.size)
        // 盘上一共五份（旧的四份 + 新的这份），留 2 份 → 从最旧的开始删掉三份
        assertEquals(3, result.removed.size, result.removed.map { it.zip.name }.toString())
        assertTrue(olds.last().isFile, "最新的那份旧备份不该被删")
    }

    @Test
    fun `备份目录被同名文件占住时只报失败不抛出去`() {
        writeWorld("world", "我的世界", mapOf("level.dat" to "x"))
        // platypus 本该是目录，这里故意占成一个文件：建不出备份目录
        File(gameRoot, "platypus").writeText("不是目录")
        val result = SavesAutoBackup.run(gameDir, gameRoot)
        assertTrue(result.backedUp.isEmpty())
        assertEquals(1, result.failed.size)
        assertTrue(result.failed.containsKey("world"))
    }

    @Test
    fun `结果里说清了备份了几份、清理了几份`() {
        val result = AutoBackupResult(
            backedUp = listOf(fakeBackup("a", 1L), fakeBackup("b", 2L)),
            removed = listOf(fakeBackup("a", 3L)),
        )
        assertTrue(result.hasWork)
        assertEquals("备份了 2 个世界，清理了 1 份旧备份", result.summary())
        assertEquals("", AutoBackupResult().summary())
    }

    private fun writeWorld(name: String, levelName: String, files: Map<String, String>) {
        val dir = File(File(gameDir, "saves"), name)
        dir.mkdirs()
        LevelDatWriter.write(File(dir, LevelDat.FILE_NAME), levelName, lastPlayed = 1_000L)
        for ((path, text) in files) {
            if (path == LevelDat.FILE_NAME) continue
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
    }

    /** 只在判断逻辑里用得上的假备份：盘上有没有这个 zip 不重要。 */
    private fun fakeBackup(name: String, createdAt: Long): WorldBackup = WorldBackup(
        zip = File(Saves.backupDir(gameRoot), name + "-20261115-000000.zip"),
        name = name,
        createdAt = createdAt,
        sizeBytes = 0L,
    )
}