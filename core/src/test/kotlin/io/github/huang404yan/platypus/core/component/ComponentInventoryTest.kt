package io.github.huang404yan.platypus.core.component

import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.mods.ModEntry
import io.github.huang404yan.platypus.core.mods.ModScanResult
import io.github.huang404yan.platypus.core.packs.PackEntry
import io.github.huang404yan.platypus.core.packs.PackKind
import io.github.huang404yan.platypus.core.packs.PackScanResult
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComponentInventoryTest {

    private val temp: File = Files.createTempDirectory("platypus-component").toFile()

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    private fun modEntry(
        name: String,
        enabled: Boolean = true,
        displayName: String? = "Sodium",
        modId: String? = "sodium",
    ) = ModEntry(
        fileName = name,
        path = File(temp, name).absolutePath,
        sizeBytes = 1234L,
        modId = modId,
        displayName = displayName,
        version = "0.5.13",
        loader = LoaderKind.FABRIC,
        enabled = enabled,
    )

    private fun packEntry(name: String, kind: PackKind, enabled: Boolean = true) = PackEntry(
        fileName = name,
        path = File(temp, name).absolutePath,
        sizeBytes = 4321L,
        displayName = null,
        packFormat = if (kind == PackKind.SHADER) null else 15,
        enabled = enabled,
    )

    @Test
    fun `模组折出来的标题用显示名，不是文件名`() {
        val result = ModScanResult(
            dir = temp,
            exists = true,
            mods = listOf(modEntry("sodium-fabric-0.5.13.jar")),
        )
        val inv = ComponentInventories.ofMods(result)
        assertEquals(ComponentKind.MOD, inv.kind)
        assertEquals(1, inv.enabled.size)
        assertEquals("Sodium", inv.enabled[0].title)
        assertEquals("sodium-fabric-0.5.13.jar", inv.enabled[0].fileName)
        assertEquals("0.5.13", inv.enabled[0].version)
    }

    @Test
    fun `折出来的 id 是绝对路径，停用改名后不变`() {
        // 停用会改文件名。拿文件名当列表键，一停用整条就被当成另一个条目。
        val enabledEntry = modEntry("x.jar")
        val disabledEntry = modEntry("x.jar.disabled", enabled = false)
        val a = ComponentInventories.ofMods(ModScanResult(temp, true, mods = listOf(enabledEntry))).enabled[0]
        val b = ComponentInventories.ofMods(ModScanResult(temp, true, disabled = listOf(disabledEntry))).disabled[0]
        assertEquals(a.id, b.id)
        assertTrue(a.enabled)
        assertFalse(b.enabled)
    }

    @Test
    fun `停用的模组在 detail 里写清楚不会加载`() {
        val inv = ComponentInventories.ofMods(
            ModScanResult(temp, true, disabled = listOf(modEntry("x.jar.disabled", enabled = false))),
        )
        assertTrue(inv.disabled[0].detail.any { it.contains("停用中") })
    }

    @Test
    fun `读不出元数据的模组把原因带出来，而不是丢掉`() {
        val broken = modEntry("broken.jar", displayName = null, modId = null).copy(
            problem = "读不出模组信息（jar 可能损坏，或不是模组）",
        )
        val inv = ComponentInventories.ofMods(ModScanResult(temp, true, mods = listOf(broken)))
        assertEquals(1, inv.enabled.size)
        assertEquals("broken.jar", inv.enabled[0].title)
        assertTrue(inv.enabled[0].problem!!.contains("读不出模组信息"))
    }

    @Test
    fun `三种包分别折成三种类型`() {
        val resource = ComponentInventories.ofPacks(
            PackScanResult(temp, PackKind.RESOURCE_PACK, true, packs = listOf(packEntry("a.zip", PackKind.RESOURCE_PACK))),
        )
        val shader = ComponentInventories.ofPacks(
            PackScanResult(temp, PackKind.SHADER, true, packs = listOf(packEntry("b.zip", PackKind.SHADER))),
        )
        val datapack = ComponentInventories.ofPacks(
            PackScanResult(temp, PackKind.DATAPACK, true, packs = listOf(packEntry("c.zip", PackKind.DATAPACK))),
        )
        assertEquals(ComponentKind.RESOURCE_PACK, resource.kind)
        assertEquals(ComponentKind.SHADER, shader.kind)
        assertEquals(ComponentKind.DATAPACK, datapack.kind)
    }

    @Test
    fun `包的标题在没写显示名时退回文件名并剥掉扩展名`() {
        val inv = ComponentInventories.ofPacks(
            PackScanResult(temp, PackKind.SHADER, true, packs = listOf(packEntry("Complementary.zip", PackKind.SHADER))),
        )
        assertEquals("Complementary", inv.enabled[0].title)
        // 光影没有 pack_format，别写个空的出来
        assertNull(inv.enabled[0].detail.firstOrNull { it.startsWith("pack_format") })
    }

    @Test
    fun `all 先启用后停用，总数含两边`() {
        val inv = ModScanResult(
            dir = temp,
            exists = true,
            mods = listOf(modEntry("a.jar"), modEntry("b.jar")),
            disabled = listOf(modEntry("c.jar.disabled", enabled = false)),
        ).let(ComponentInventories::ofMods)
        assertEquals(3, inv.totalCount)
        assertEquals(listOf("a.jar", "b.jar", "c.jar.disabled"), inv.all.map { it.fileName })
    }

    @Test
    fun `目录位置按类型算`() {
        val dir = File(temp, "instance")
        assertEquals(File(dir, "mods"), ComponentKind.MOD.dirOf(dir))
        assertEquals(File(dir, "resourcepacks"), ComponentKind.RESOURCE_PACK.dirOf(dir))
        assertEquals(File(dir, "shaderpacks"), ComponentKind.SHADER.dirOf(dir))
        assertEquals(File(dir, "datapacks"), ComponentKind.DATAPACK.dirOf(dir))
    }

    @Test
    fun `给 zip 停用改的是 zip 后缀，不是 jar`() {
        // 拿模组那套规则去改 x.zip 会变成 x.zip.jar：游戏照样加载，看着「停用了但没停」。
        val packDir = File(temp, "shaderpacks").apply { mkdirs() }
        val zip = File(packDir, "bsl.zip").apply { writeText("x") }
        val component = Component(
            id = zip.absolutePath,
            path = zip.absolutePath,
            fileName = "bsl.zip",
            kind = ComponentKind.SHADER,
            title = "bsl",
            sizeBytes = 1L,
            enabled = true,
        )
        val renamed = ComponentOps.setEnabled(component, enabled = false)
        assertEquals("bsl.zip.disabled", renamed.name)
        assertTrue(renamed.isFile)
    }

    @Test
    fun `给 jar 停用走模组那一套`() {
        val modsDir = File(temp, "mods").apply { mkdirs() }
        val jar = File(modsDir, "sodium.jar").apply { writeText("x") }
        val component = Component(
            id = jar.absolutePath,
            path = jar.absolutePath,
            fileName = "sodium.jar",
            kind = ComponentKind.MOD,
            title = "sodium",
            sizeBytes = 1L,
            enabled = true,
        )
        assertEquals("sodium.jar.disabled", ComponentOps.setEnabled(component, enabled = false).name)
    }

    @Test
    fun `删除按类型派发，删掉了返回真、再删一次返回假`() {
        val packDir = File(temp, "resourcepacks").apply { mkdirs() }
        val zip = File(packDir, "pack.zip").apply { writeText("x") }
        val component = Component(
            id = zip.absolutePath,
            path = zip.absolutePath,
            fileName = "pack.zip",
            kind = ComponentKind.RESOURCE_PACK,
            title = "pack",
            sizeBytes = 1L,
            enabled = true,
        )
        assertTrue(ComponentOps.delete(component))
        assertFalse(zip.exists())
        assertFalse(ComponentOps.delete(component))
    }
}
