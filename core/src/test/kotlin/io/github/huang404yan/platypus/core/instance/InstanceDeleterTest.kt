package io.github.huang404yan.platypus.core.instance

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 实例删除的回归测试。
 *
 * 这里每一条都是「删错了就找不回来」的分支：越界删除、共用目录、存档丢失、
 * 删到一半失败后目录再也回不去。所以断言写得很细，不只断言「返回 false」。
 */
class InstanceDeleterTest {

    private val roots = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        roots.forEach { root ->
            // 测试里会故意造只读文件，先放开再删，否则临时目录会留在磁盘上。
            runCatching { root.walkTopDown().forEach { if (it.isFile) it.setWritable(true) } }
            root.deleteRecursively()
        }
        roots.clear()
    }

    private fun tempRoot(): File {
        val root = Files.createTempDirectory("platypus-delete").toFile()
        roots += root
        File(root, "versions").mkdirs()
        return root
    }

    /** 造一个版本目录：<root>/versions/<id>/ 下放 client.jar、可选 saves/ 与 mods/。 */
    private fun makeVersion(
        root: File,
        id: String,
        jarBytes: Int = 32,
        saves: List<String> = emptyList(),
        mods: Boolean = false,
    ): File {
        val dir = File(File(root, "versions"), id)
        dir.mkdirs()
        File(dir, id + ".json").writeText("""{ "id": "$id" }""")
        File(dir, "client.jar").writeBytes(ByteArray(jarBytes) { 7 })
        if (mods) {
            File(dir, "mods").mkdirs()
            File(File(dir, "mods"), "a.jar").writeBytes(ByteArray(10) { 1 })
        }
        saves.forEach { name ->
            val world = File(File(dir, "saves"), name)
            world.mkdirs()
            File(world, "level.dat").writeBytes(ByteArray(16) { 2 })
        }
        return dir
    }

    @Test
    fun `inspect 统计出文件数与字节数，delete 后目录真的没了`() {
        val root = tempRoot()
        val dir = makeVersion(root, "1.20.1", jarBytes = 32, mods = true)

        val plan = InstanceDeleter.inspect(root, "1.20.1")

        assertTrue(plan.deletable, plan.refusalReason)
        assertEquals(dir.canonicalFile, plan.versionDir.canonicalFile)
        // 1.20.1.json + client.jar（32）+ mods/a.jar（10），json 长度按实际文件算
        val expectedBytes = File(dir, "1.20.1.json").length() + 32L + 10L
        assertEquals(3, plan.fileCount)
        assertEquals(expectedBytes, plan.totalBytes)
        assertEquals(32L, File(dir, "client.jar").length())
        assertTrue(plan.hasMods)
        assertFalse(plan.hasSaves)
        assertTrue(plan.sharedWith.isEmpty())

        val outcome = InstanceDeleter.delete(plan)

        assertTrue(outcome.ok, outcome.summary)
        assertEquals(3, outcome.deletedFiles)
        assertEquals(plan.totalBytes, outcome.freedBytes)
        assertFalse(dir.exists(), "目录必须被彻底删掉")
        assertFalse(plan.versionDir.exists())
    }

    @Test
    fun `版本目录不存在时拒绝，并且什么都不删`() {
        val root = tempRoot()

        val plan = InstanceDeleter.inspect(root, "9.9.9")

        assertEquals(DeletionRefusal.VERSION_DIR_MISSING, plan.refusal)
        assertFalse(plan.deletable)
        assertTrue(plan.refusalReason.contains("不存在"), plan.refusalReason)

        val outcome = InstanceDeleter.delete(plan)
        assertFalse(outcome.ok)
        assertNotNull(outcome.failure)
        assertTrue(File(root, "versions").isDirectory)
    }

    @Test
    fun `路径越界时拒绝，哪怕那个目录真的存在`() {
        val root = tempRoot()
        // versions/../outside 真的建出来：如果只判「存在」就会把游戏目录外面的东西删掉。
        val outside = File(root, "outside")
        outside.mkdirs()
        File(outside, "precious.txt").writeText("别删我")

        val plan = InstanceDeleter.inspect(root, "../outside")

        assertEquals(DeletionRefusal.OUTSIDE_GAME_ROOT, plan.refusal)
        assertFalse(plan.deletable)
        assertTrue(plan.refusalReason.contains("拒绝删除"), plan.refusalReason)

        val outcome = InstanceDeleter.delete(plan)
        assertFalse(outcome.ok)
        assertTrue(File(outside, "precious.txt").isFile, "越界目标的文件不能被动过")
    }

    @Test
    fun `绝对路径形式的 id 也不许删`() {
        val root = tempRoot()
        val outside = File(root, "elsewhere")
        outside.mkdirs()
        File(outside, "keep.txt").writeText("留着")

        val plan = InstanceDeleter.inspect(root, outside.absolutePath)

        // File(versions, 绝对路径) 在各平台上拼出来的东西不一样；不管落在「越界」还是
        // 「不存在」哪一支，结论都必须是不能删，而且目标没被碰过。
        assertFalse(plan.deletable)
        assertTrue(File(outside, "keep.txt").isFile)
    }

    @Test
    fun `两个实例登记同一个目录时拒绝删除`() {
        val root = tempRoot()
        val dir = makeVersion(root, "1.20.1")
        val index = JsonInstanceRepository.defaultIndexFile(root)

        val a = Instance(id = "1.20.1", name = "原版", gameVersion = "1.20.1", directory = dir.absolutePath, registered = true)
        val b = Instance(id = "1.20.1-copy", name = "副本", gameVersion = "1.20.1", directory = dir.absolutePath, registered = true)
        // 直接写文件，避免在单测里引入协程：snapshot() 读的就是这份 JSON。
        writeIndex(index, listOf(a, b))

        val plan = InstanceDeleter.inspect(root, "1.20.1", index)

        assertEquals(DeletionRefusal.SHARED_BY_INSTANCES, plan.refusal)
        assertEquals(1, plan.sharedWith.size, plan.sharedWith.joinToString())
        assertTrue(plan.sharedWith.first().contains("副本"), plan.sharedWith.joinToString())
        assertTrue(plan.versionDir.isDirectory, "inspect 只读，不许动磁盘")

        val outcome = InstanceDeleter.delete(plan)
        assertFalse(outcome.ok)
        assertTrue(dir.isDirectory, "共用目录必须原样留着")
        assertTrue(File(dir, "client.jar").isFile)
    }

    @Test
    fun `自己那一条同名登记不算共用`() {
        val root = tempRoot()
        val dir = makeVersion(root, "1.20.1")
        val index = JsonInstanceRepository.defaultIndexFile(root)
        writeIndex(
            index,
            listOf(Instance(id = "1.20.1", name = "原版", gameVersion = "1.20.1", directory = dir.absolutePath, registered = true)),
        )

        val plan = InstanceDeleter.inspect(root, "1.20.1", index)

        assertTrue(plan.deletable, plan.refusalReason)
        assertTrue(plan.sharedWith.isEmpty())
    }

    @Test
    fun `勾选连存档一起删时目录整个消失`() {
        val root = tempRoot()
        val dir = makeVersion(root, "1.20.1", saves = listOf("world-a"))

        val plan = InstanceDeleter.inspect(root, "1.20.1")
        assertTrue(plan.hasSaves)
        assertEquals(1, plan.savesFileCount)
        assertTrue(plan.savesBytes > 0L)

        val outcome = InstanceDeleter.delete(plan, includeSaves = true)

        assertTrue(outcome.ok, outcome.summary)
        assertFalse(dir.exists())
        assertNull(outcome.savesMovedTo)
        assertFalse(outcome.savesKept)
    }

    @Test
    fun `默认保留存档：搬进 platypus 的 saves-backup 并说明位置`() {
        val root = tempRoot()
        val dir = makeVersion(root, "1.20.1", saves = listOf("world-a"))

        val plan = InstanceDeleter.inspect(root, "1.20.1")
        val outcome = InstanceDeleter.delete(plan, includeSaves = false)

        assertTrue(outcome.ok, outcome.summary)
        assertFalse(dir.exists(), "版本目录要删掉，否则「删了」就成了假话")
        assertTrue(outcome.savesKept)
        val moved = outcome.savesMovedTo
        assertNotNull(moved, "保留存档时一定要告诉用户搬去哪了")
        assertTrue(moved.isDirectory)
        // 存档内容一字节不少：world-a/level.dat 16 字节。
        val level = File(File(moved, "world-a"), "level.dat")
        assertTrue(level.isFile, level.absolutePath)
        assertEquals(16L, level.length())
        assertTrue(moved.absolutePath.replace('\\', '/').contains("/platypus/saves-backup/"), moved.absolutePath)
    }

    @Test
    fun `删到一半遇到只读文件失败时把目录名改回来`() {
        val root = tempRoot()
        val dir = makeVersion(root, "1.20.1", jarBytes = 8)
        val locked = File(dir, "locked.dat")
        locked.writeBytes(ByteArray(4) { 3 })
        locked.setReadOnly()

        // 先确认这台机器上只读文件真的删不掉；删得掉（某些文件系统/权限）就跳过这条。
        val probe = File(dir, "probe.lock")
        probe.writeBytes(ByteArray(1) { 9 })
        probe.setReadOnly()
        val readonlyBlocks = !probe.delete()
        probe.setWritable(true)
        probe.delete()
        if (!readonlyBlocks) {
            locked.setWritable(true)
            println("SKIP 当前文件系统不阻止删除只读文件，无法构造中途失败")
            return
        }

        val plan = InstanceDeleter.inspect(root, "1.20.1")
        val outcome = InstanceDeleter.delete(plan, includeSaves = true)

        assertFalse(outcome.ok, "只读文件必须让删除以失败收场，不能假装成功")
        assertTrue(outcome.rolledBack, outcome.summary)
        // 回滚后原路径还在，用户还能进去手工处理。
        assertTrue(dir.isDirectory, "回滚后目录必须回到原来的名字")
        assertTrue(File(dir, "locked.dat").isFile)
        assertNotNull(outcome.failure)
        assertTrue(outcome.failure.path.endsWith("locked.dat"), outcome.failure.path)
        // 失败时不能让用户以为目录还在原地——要么改回原名，要么明确给出现名。
        assertNotNull(outcome.renamedTo)

        // 清场：不放开只读会让 AfterTest 删不掉临时目录。
        locked.setWritable(true)
    }

    @Test
    fun `moveToRecycleBin 只改名不递归删`() {
        val root = tempRoot()
        val dir = makeVersion(root, "1.20.1")

        val plan = InstanceDeleter.inspect(root, "1.20.1")
        val outcome = InstanceDeleter.delete(plan, moveToRecycleBin = true)

        assertTrue(outcome.ok, outcome.summary)
        assertFalse(dir.exists(), "原名必须消失")
        val renamed = outcome.renamedTo
        assertNotNull(renamed)
        assertTrue(renamed.isDirectory)
        assertTrue(renamed.name.startsWith("1.20.1.deleting-"), renamed.name)
        assertEquals(0, outcome.deletedFiles)
    }

    @Test
    fun `formatBytes 给出人能读的大小`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("1.50 GB", formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    /** 直接落一份 instances.json，字段与 JsonInstanceRepository 写出来的一致。 */
    private fun writeIndex(index: File, instances: List<Instance>) {
        index.parentFile.mkdirs()
        val text = instances.joinToString(",", "[", "]") { instance ->
            """
            {
              "id": "${instance.id}",
              "name": "${instance.name}",
              "gameVersion": "${instance.gameVersion}",
              "directory": "${instance.directory.replace("\\", "\\\\")}",
              "registered": true
            }
            """.trimIndent()
        }
        index.writeText(text, Charsets.UTF_8)
    }
}
