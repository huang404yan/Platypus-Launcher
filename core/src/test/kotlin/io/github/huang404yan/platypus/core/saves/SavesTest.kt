package io.github.huang404yan.platypus.core.saves

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 存档的列、备份、还原、删。全程离线，全部在临时目录里做。 */
class SavesTest {
    private val root: File = Files.createTempDirectory("platypus-saves-test").toFile()
    private val gameRoot: File = File(root, "game")

    @AfterTest
    fun tearDown() {
        runCatching { root.deleteRecursively() }
    }

    @Test
    fun `没有存档目录时返回不存在而不是报错`() {
        val snapshot = Saves.scan(File(gameRoot, "minecraft"))
        assertFalse(snapshot.exists)
        assertTrue(snapshot.worlds.isEmpty())
    }

    @Test
    fun `扫出世界名、大小与最近游玩时间`() {
        val saves = savesDir()
        writeWorld(File(saves, "world-a"), "A 世界", lastPlayed = 1_000L, extra = "region/r.0.0.mca" to "x".repeat(10))
        writeWorld(File(saves, "world-b"), "B 世界", lastPlayed = 2_000L)
        val snapshot = Saves.scan(File(gameRoot, "minecraft"))
        assertEquals(2, snapshot.worlds.size)
        // 最近玩过的排前面：找存档时几乎总是找刚刚那个
        assertEquals("B 世界", snapshot.worlds[0].displayName)
        assertEquals("A 世界", snapshot.worlds[1].displayName)
        assertTrue(snapshot.worlds[1].sizeBytes > snapshot.worlds[0].sizeBytes - 1)
    }

    @Test
    fun `没有元数据文件的世界照样列出来但标记成坏的`() {
        val saves = savesDir()
        File(saves, "half-copied").mkdirs()
        File(saves, "half-copied/region").mkdirs()
        File(saves, "half-copied/region/r.0.0.mca").writeText("x")
        val snapshot = Saves.scan(File(gameRoot, "minecraft"))
        assertEquals(1, snapshot.worlds.size)
        assertTrue(snapshot.worlds[0].broken)
        assertEquals("half-copied", snapshot.worlds[0].displayName)
    }

    @Test
    fun `隐藏目录不列进世界列表`() {
        val saves = savesDir()
        writeWorld(File(saves, "real"), "真的")
        File(saves, ".trash").mkdirs()
        assertEquals(1, Saves.scan(File(gameRoot, "minecraft")).worlds.size)
    }

    @Test
    fun `备份出来的 zip 是世界的相对路径而不是套一层目录`() {
        val saves = savesDir()
        writeWorld(File(saves, "world"), "备份我", extra = "region/r.0.0.mca" to "abc")
        val world = Saves.scan(File(gameRoot, "minecraft")).worlds.single()
        val backup = Saves.backup(world, gameRoot)
        assertTrue(backup.zip.isFile)
        val names = ZipFile(backup.zip).use { zip -> zip.entries().asSequence().map { it.name }.toList() }.sorted()
        // 解出来就是能直接塞回 saves/ 的世界：不套 <世界名>/ 这一层
        assertTrue(names.contains(LevelDat.FILE_NAME), names.toString())
        assertTrue(names.contains("region/r.0.0.mca"), names.toString())
        assertTrue(names.none { it.startsWith("world/") }, names.toString())
    }

    @Test
    fun `备份文件名带世界名与时间戳`() {
        val saves = savesDir()
        writeWorld(File(saves, "my world"), "名字里有空格")
        val world = Saves.scan(File(gameRoot, "minecraft")).worlds.single()
        val backup = Saves.backup(world, gameRoot)
        assertTrue(backup.zip.name.startsWith("my world-"), backup.zip.name)
        assertTrue(backup.zip.name.endsWith(".zip"), backup.zip.name)
        assertTrue(backup.zip.name.length > "my world-19700101-000000.zip".length - 1, backup.zip.name)
    }

    @Test
    fun `同一秒备份两次不会覆盖前一份`() {
        val saves = savesDir()
        writeWorld(File(saves, "world"), "连点两次")
        val world = Saves.scan(File(gameRoot, "minecraft")).worlds.single()
        val first = Saves.backup(world, gameRoot)
        val second = Saves.backup(world, gameRoot)
        assertTrue(first.zip.isFile)
        assertTrue(second.zip.isFile)
        assertFalse(first.zip.absolutePath == second.zip.absolutePath)
    }

    @Test
    fun `备份列表按时间从新到旧`() {
        val saves = savesDir()
        writeWorld(File(saves, "world"), "列备份")
        val world = Saves.scan(File(gameRoot, "minecraft")).worlds.single()
        val first = Saves.backup(world, gameRoot)
        Thread.sleep(1100) // 文件名精确到秒
        val second = Saves.backup(world, gameRoot)
        val backups = Saves.backups(gameRoot)
        assertEquals(2, backups.size)
        assertEquals(second.zip.name, backups[0].zip.name)
        assertEquals(first.zip.name, backups[1].zip.name)
        assertTrue(backups[0].createdAt >= backups[1].createdAt)
    }

    @Test
    fun `还原回一个同名世界时默认拒绝并说清楚`() {
        val saves = savesDir()
        writeWorld(File(saves, "world"), "原世界")
        val world = Saves.scan(File(gameRoot, "minecraft")).worlds.single()
        val backup = Saves.backup(world, gameRoot)
        val result = Saves.restore(backup.zip, File(gameRoot, "minecraft"))
        assertFalse(result.ok)
        assertTrue(result.message.contains("已经有一个"), result.message)
    }

    @Test
    fun `换个名字就能还原出第二份世界`() {
        val saves = savesDir()
        writeWorld(File(saves, "world"), "原世界")
        val world = Saves.scan(File(gameRoot, "minecraft")).worlds.single()
        val backup = Saves.backup(world, gameRoot)
        val result = Saves.restore(backup.zip, File(gameRoot, "minecraft"), name = "world-copy")
        assertTrue(result.ok, result.message)
        val names = Saves.scan(File(gameRoot, "minecraft")).worlds.map { it.name }
        assertEquals(listOf("world", "world-copy").sorted(), names.sorted())
        assertEquals(LevelDat.FILE_NAME, File(saves, "world-copy/" + LevelDat.FILE_NAME).name)
    }

    @Test
    fun `明确要求覆盖时才替换掉同名世界`() {
        val saves = savesDir()
        writeWorld(File(saves, "world"), "旧内容")
        val world = Saves.scan(File(gameRoot, "minecraft")).worlds.single()
        val backup = Saves.backup(world, gameRoot)
        // 改掉原世界，再还原覆盖
        File(saves, "world/extra.txt").writeText("新加的文件")
        val result = Saves.restore(backup.zip, File(gameRoot, "minecraft"), overwrite = true)
        assertTrue(result.ok, result.message)
        // 还原是整份替换，不是合并：备份之后新加的文件不该留下
        assertFalse(File(saves, "world/extra.txt").exists())
        assertEquals("旧内容", LevelDat.read(File(saves, "world"))?.levelName)
    }

    @Test
    fun `还原外部 zip 时路径穿越的条目被跳过`() {
        val zip = File(root, "evil.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("../escaped.txt"))
            out.write("x".toByteArray())
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("level.dat"))
            out.write("not-nbt".toByteArray())
            out.closeEntry()
        }
        val gameDir = File(gameRoot, "minecraft")
        val result = Saves.restore(zip, gameDir, name = "from-evil")
        assertTrue(result.ok, result.message)
        assertFalse(File(root, "escaped.txt").exists())
    }

    @Test
    fun `删世界会先留一份备份`() {
        val saves = savesDir()
        writeWorld(File(saves, "doomed"), "要被删的")
        val world = Saves.scan(File(gameRoot, "minecraft")).worlds.single()
        val backup = Saves.delete(world, gameRoot)
        assertFalse(world.dir.exists())
        assertTrue(backup.zip.isFile)
        assertEquals(1, Saves.backups(gameRoot).size)
        // 备份还能还原回来
        val result = Saves.restore(backup.zip, File(gameRoot, "minecraft"), name = "doomed")
        assertTrue(result.ok, result.message)
        assertNotNull(LevelDat.read(File(saves, "doomed")))
    }

    @Test
    fun `空存档目录备份会被拒绝而不是写出一个空包`() {
        val saves = savesDir()
        File(saves, "empty-world").mkdirs()
        val world = Saves.scan(File(gameRoot, "minecraft")).worlds.single()
        val error = kotlin.runCatching { Saves.backup(world, gameRoot) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(Saves.backups(gameRoot).isEmpty())
    }

    @Test
    fun `世界名里的非法字符会被换成下划线`() {
        assertEquals("a_b", Saves.safeFileName("a:b"))
        assertEquals("world", Saves.safeFileName("   "))
        assertEquals("正常名字", Saves.safeFileName("正常名字"))
    }

    // ---- 造数据 ----

    private fun savesDir(): File {
        val dir = File(File(gameRoot, "minecraft"), Saves.SAVES_DIR_NAME)
        dir.mkdirs()
        return dir
    }

    private fun writeWorld(dir: File, levelName: String, lastPlayed: Long = 0L, extra: Pair<String, String>? = null) {
        dir.mkdirs()
        LevelDatWriter.write(File(dir, LevelDat.FILE_NAME), levelName, lastPlayed)
        File(dir, "level.dat_old").writeBytes(byteArrayOf(1, 2, 3))
        extra?.let { (path, text) ->
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
    }
}
