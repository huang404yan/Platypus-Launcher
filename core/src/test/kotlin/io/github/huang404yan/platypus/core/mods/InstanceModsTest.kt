package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.LoaderKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 实例隔离之后「哪个目录才是这个实例的 mods/」这一层。
 *
 * 重点不是扫描逻辑（那是 ModScannerTest 的事），而是三件事：
 * 1. 同一个 gameRoot 下两个实例的 mods/ 互不可见；
 * 2. 隔离前留在 gameRoot/mods 里的模组能被识别成「旧模组」而不是凭空消失；
 * 3. 搬旧模组是复制、不覆盖、源文件还在。
 */
class InstanceModsTest {

    private fun tempRoot(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    private fun jar(dir: File, name: String): File {
        val file = File(dir, name)
        if (!dir.isDirectory) dir.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write(
                """
                {"schemaVersion":1,"id":"demo","version":"1.0.0","name":"Demo"}
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }
        return file
    }

    /** 造 <root>/versions/<id> 并把实例返回。 */
    private fun instance(root: File, id: String): Instance {
        val dir = File(File(root, "versions"), id)
        dir.mkdirs()
        return Instance(
            id = id,
            name = id,
            gameVersion = "1.20.1",
            loader = LoaderKind.FABRIC,
            directory = dir.absolutePath,
        )
    }

    @Test
    fun `实例的 mods 目录是实例目录下的 mods`() {
        val root = tempRoot("platypus-imods-dir")
        try {
            val inst = instance(root, "1.20.1-fabric")
            val expected = File(File(File(root, "versions"), "1.20.1-fabric"), "mods")
            assertEquals(expected.absolutePath, InstanceMods.modsDirOf(inst).absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `两个实例的 mods 目录不同`() {
        val root = tempRoot("platypus-imods-two")
        try {
            val a = InstanceMods.modsDirOf(instance(root, "a"))
            val b = InstanceMods.modsDirOf(instance(root, "b"))
            assertTrue(a.absolutePath != b.absolutePath, "两个实例不应共用 mods：" + a.path)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `扫描只看得到自己实例的模组`() {
        val root = tempRoot("platypus-imods-isolated")
        try {
            val a = instance(root, "a")
            val b = instance(root, "b")
            jar(File(File(a.directory), "mods"), "only-a.jar")
            jar(File(File(b.directory), "mods"), "only-b.jar")
            val scanA = InstanceMods.scan(a)
            val scanB = InstanceMods.scan(b)
            assertEquals(listOf("only-a.jar"), scanA.scan.mods.map { it.fileName })
            assertEquals(listOf("only-b.jar"), scanB.scan.mods.map { it.fileName })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `共享 mods 里的旧模组会被认出来`() {
        val root = tempRoot("platypus-imods-legacy")
        try {
            val inst = instance(root, "1.20.1")
            val shared = File(root, "mods")
            jar(shared, "old-sodium.jar")
            val snapshot = InstanceMods.scan(inst)
            assertTrue(snapshot.scan.totalCount == 0, "实例自己还没装东西")
            assertTrue(snapshot.hasLegacy)
            assertEquals(1, snapshot.legacyCount)
            assertEquals(shared.absolutePath, snapshot.legacyModsDir.absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `没有共享 mods 时不会误报旧模组`() {
        val root = tempRoot("platypus-imods-nolegacy")
        try {
            val inst = instance(root, "1.20.1")
            val snapshot = InstanceMods.scan(inst)
            assertFalse(snapshot.hasLegacy)
            assertEquals(0, snapshot.legacyCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `目录不在 versions 下时共享目录与实例目录重合`() {
        val root = tempRoot("platypus-imods-flat")
        try {
            // 直接把一个自包含目录当实例用（导入出来的目录可能长这样）
            val flat = File(root, "packed")
            flat.mkdirs()
            val inst = Instance(id = "packed", name = "packed", gameVersion = "1.20.1", directory = flat.absolutePath)
            val snapshot = InstanceMods.scan(inst)
            assertEquals(InstanceMods.modsDirOf(inst).absolutePath, snapshot.modsDir.absolutePath)
            assertNull(snapshot.legacyScan, "重合时不该再扫一遍，否则界面会让人把模组搬给自己")
            assertFalse(snapshot.hasLegacy)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `搬旧模组是复制，源文件还在`() {
        val root = tempRoot("platypus-imods-adopt")
        try {
            val inst = instance(root, "1.20.1")
            val shared = File(root, "mods")
            val source = jar(shared, "old.jar")
            val result = InstanceMods.adoptLegacy(inst)
            assertEquals(listOf("old.jar"), result.copied)
            assertTrue(result.ok)
            assertTrue(File(InstanceMods.modsDirOf(inst), "old.jar").isFile)
            assertTrue(source.isFile, "共享目录里的原件不该被删掉")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `搬旧模组会带上停用的模组`() {
        val root = tempRoot("platypus-imods-adopt-off")
        try {
            val inst = instance(root, "1.20.1")
            val shared = File(root, "mods")
            jar(shared, "on.jar")
            jar(shared, "off.jar.disabled")
            val result = InstanceMods.adoptLegacy(inst)
            assertEquals(listOf("on.jar", "off.jar.disabled"), result.copied)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `同名文件默认不覆盖`() {
        val root = tempRoot("platypus-imods-skip")
        try {
            val inst = instance(root, "1.20.1")
            val shared = File(root, "mods")
            jar(shared, "sodium.jar")
            val own = File(InstanceMods.modsDirOf(inst), "sodium.jar")
            own.parentFile.mkdirs()
            own.writeText("installed-by-launcher")
            val result = InstanceMods.adoptLegacy(inst)
            assertTrue(result.copied.isEmpty())
            assertEquals(listOf("sodium.jar"), result.skipped)
            assertEquals("installed-by-launcher", own.readText(), "不许悄悄覆盖")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `显式要求时可以覆盖`() {
        val root = tempRoot("platypus-imods-overwrite")
        try {
            val inst = instance(root, "1.20.1")
            val shared = File(root, "mods")
            jar(shared, "sodium.jar")
            val own = File(InstanceMods.modsDirOf(inst), "sodium.jar")
            own.parentFile.mkdirs()
            own.writeText("stale")
            val result = InstanceMods.adoptLegacy(inst, overwrite = true)
            assertEquals(listOf("sodium.jar"), result.copied)
            assertTrue(own.length() > "stale".length.toLong(), "覆盖后应该是真的 jar")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `关掉隔离后 mods 目录回到游戏根目录`() {
        val root = tempRoot("platypus-imods-shared")
        try {
            val inst = instance(root, "1.20.1").copy(isolated = false)
            assertEquals(ModScanner.modsDir(root).absolutePath, InstanceMods.modsDirOf(inst).absolutePath)
            // 游戏根目录下那份模组就是它自己的，按需扫描要能看见
            jar(File(root, "mods"), "flat.jar")
            val snapshot = InstanceMods.scan(inst)
            assertEquals(listOf("flat.jar"), snapshot.scan.mods.map { it.fileName })
            assertNull(snapshot.legacyScan, "自己就是那份共享目录时不该再提示「有旧模组要搬」")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `共享目录里没有 mods 时搬会报错`() {
        val root = tempRoot("platypus-imods-nothing")
        try {
            val inst = instance(root, "1.20.1")
            val error = runCatching { InstanceMods.adoptLegacy(inst) }.exceptionOrNull()
            assertTrue(error is IOException, "应当抛 IOException，实际：" + error)
        } finally {
            root.deleteRecursively()
        }
    }
}
