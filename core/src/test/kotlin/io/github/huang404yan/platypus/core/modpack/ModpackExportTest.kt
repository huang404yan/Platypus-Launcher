package io.github.huang404yan.platypus.core.modpack

import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.mods.ModUpdateChecker
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModpackExportTest {

    private val root: File = Files.createTempDirectory("platypus-export-test").toFile()

    @AfterTest
    fun tearDown() {
        runCatching { root.deleteRecursively() }
    }

    /** 实例目录（隔离模式下 gameDir 就是它）：测试里的文件都往这里写。 */
    private val instanceDir: File get() = File(root, "versions/test-1.20.1")

    private fun write(relative: String, text: String = "x"): File {
        val file = File(instanceDir, relative)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    private fun instance(
        loader: LoaderKind = LoaderKind.FABRIC,
        loaderVersion: String? = "0.16.0",
        isolated: Boolean = true,
    ) = Instance(
        id = "test-1.20.1",
        name = "测试整合包",
        gameVersion = "1.20.1",
        loader = loader,
        loaderVersion = loaderVersion,
        directory = File(root, "versions/test-1.20.1").absolutePath,
        isolated = isolated,
    )

    private fun gameDirOf(instance: Instance) = File(instance.directory)

    private fun entriesOf(archive: File): List<String> =
        ZipFile(archive).use { zip -> zip.entries().toList().map { it.name }.sorted() }

    private fun textOf(archive: File, name: String): String? = ZipFile(archive).use { zip ->
        val entry = zip.getEntry(name) ?: return@use null
        zip.getInputStream(entry).reader(Charsets.UTF_8).readText()
    }

    // ---- 收集 ----

    @Test
    fun `默认只收集模组配置资源包与光影，不收存档`() {
        write("mods/sodium.jar")
        write("config/sodium-options.json", "{}")
        write("resourcepacks/pack.zip")
        write("shaderpacks/bsl.zip")
        write("saves/我的世界/level.dat")
        val entries = ModpackExport.collect(gameDirOf(instance()), ModpackExportOptions())
        assertEquals(
            listOf("config/sodium-options.json", "mods/sodium.jar", "resourcepacks/pack.zip", "shaderpacks/bsl.zip"),
            entries.map { it.path },
        )
    }

    @Test
    fun `勾上存档才会把世界打进去`() {
        write("mods/sodium.jar")
        write("saves/我的世界/level.dat")
        val options = ModpackExportOptions(
            categories = ModpackExportOptions.DEFAULT_CATEGORIES + ModpackExportCategory.SAVES,
        )
        val entries = ModpackExport.collect(gameDirOf(instance()), options)
        assertTrue(entries.any { it.path == "saves/我的世界/level.dat" }, entries.map { it.path }.toString())
    }

    @Test
    fun `隐藏文件与隐藏目录不收进包里`() {
        write("mods/sodium.jar")
        write("mods/.gitkeep")
        write("config/.cache/temp.txt")
        val entries = ModpackExport.collect(gameDirOf(instance()), ModpackExportOptions())
        assertEquals(listOf("mods/sodium.jar"), entries.map { it.path })
    }

    @Test
    fun `目录不存在时收集结果为空而不抛异常`() {
        val entries = ModpackExport.collect(File(root, "根本没有这个目录"), ModpackExportOptions())
        assertTrue(entries.isEmpty())
    }

    // ---- MCBBS ----

    @Test
    fun `MCBBS 包只写两件元数据且文件实体在 overrides 里`() = runBlocking {
        write("mods/sodium.jar", "sodium")
        val target = File(root, "out/pack.zip")
        val result = ModpackExport.export(instance(), target, ModpackExportOptions(version = "2.0.0"))
        val names = entriesOf(target)
        assertTrue(names.contains(ModpackExport.PACKMETA_NAME), names.toString())
        assertTrue(names.contains(ModpackExport.MANIFEST_NAME), names.toString())
        // 三件元数据共存会让解析方选错格式，所以 MCBBS 模式下不许写 modrinth.index.json
        assertFalse(names.contains(ModpackExport.INDEX_NAME), names.toString())
        assertTrue(names.contains("overrides/mods/sodium.jar"), names.toString())
        assertEquals(1, result.packaged)
        assertEquals(0, result.hosted)
        assertEquals("sodium".toByteArray().size.toLong(), result.bytes)
    }

    @Test
    fun `MCBBS 的 addons 用短名而 manifest 用短名加版本`() = runBlocking {
        write("mods/sodium.jar")
        val target = File(root, "pack.zip")
        ModpackExport.export(instance(LoaderKind.FORGE, "47.2.0"), target, ModpackExportOptions())
        val packmeta = textOf(target, ModpackExport.PACKMETA_NAME).orEmpty()
        assertTrue(packmeta.contains("\"id\": \"game\""), packmeta)
        assertTrue(packmeta.contains("\"id\": \"forge\""), packmeta)
        assertTrue(packmeta.contains("\"version\": \"47.2.0\""), packmeta)
        // net.minecraftforge 这类 patchId 会让导入方找不到加载器
        assertFalse(packmeta.contains("net.minecraftforge"), packmeta)
        val manifest = textOf(target, ModpackExport.MANIFEST_NAME).orEmpty()
        assertTrue(manifest.contains("forge-47.2.0"), manifest)
    }

    @Test
    fun `原版实例的包里只有 game 一项组件`() = runBlocking {
        write("mods/sodium.jar")
        val target = File(root, "vanilla.zip")
        ModpackExport.export(instance(LoaderKind.VANILLA, null), target, ModpackExportOptions())
        val packmeta = textOf(target, ModpackExport.PACKMETA_NAME).orEmpty()
        assertTrue(packmeta.contains("\"id\": \"game\""), packmeta)
        assertFalse(packmeta.contains("\"id\": \"forge\""), packmeta)
        assertFalse(packmeta.contains("\"id\": \"fabric\""), packmeta)
    }

    // ---- CurseForge ----

    @Test
    fun `CurseForge 包只写 manifest 不写 mcbbs 描述`() = runBlocking {
        write("mods/sodium.jar", "sodium")
        write("config/a.json", "{}")
        val target = File(root, "cf/pack.zip")
        val result = ModpackExport.export(
            instance(LoaderKind.FORGE, "47.2.0"),
            target,
            ModpackExportOptions(version = "3.1.0", format = ModpackExportFormat.CURSE),
        )
        val names = entriesOf(target)
        assertTrue(names.contains(ModpackExport.MANIFEST_NAME), names.toString())
        // 对 CurseForge 侧来说 mcbbs.packmeta 是个陌生文件，不许混进去
        assertFalse(names.contains(ModpackExport.PACKMETA_NAME), names.toString())
        assertFalse(names.contains(ModpackExport.INDEX_NAME), names.toString())
        assertTrue(names.contains("overrides/mods/sodium.jar"), names.toString())
        assertTrue(names.contains("overrides/config/a.json"), names.toString())
        // 元数据只有一份 manifest.json，所以打包计数是文件数 + 1
        assertEquals(2, result.packaged)
        assertEquals(3, result.entries)
        val manifest = textOf(target, ModpackExport.MANIFEST_NAME).orEmpty()
        assertTrue(manifest.contains("minecraftModpack"), manifest)
        assertTrue(manifest.contains("forge-47.2.0"), manifest)
        assertTrue(manifest.contains("3.1.0"), manifest)
    }

    @Test
    fun `导出的 CurseForge 包能被自己的解析器读回来`() = runBlocking {
        write("mods/sodium.jar")
        val target = File(root, "cf/pack.zip")
        ModpackExport.export(
            instance(LoaderKind.NEOFORGE, "21.1.80"),
            target,
            ModpackExportOptions(name = "水星包", version = "1.4.0", format = ModpackExportFormat.CURSE),
        )
        val names = entriesOf(target)
        // detect 认 CurseForge 包靠的就是 manifest.json + overrides/
        val layout = ModpackMeta.detect(names)
        assertEquals(ModpackKind.MCBBS_V2, layout.kind)
        val parsed = ModpackMeta.parseMcbbs(
            packmetaText = null,
            manifestText = textOf(target, ModpackExport.MANIFEST_NAME),
        )
        assertTrue(parsed is ModpackParse.Ok, parsed.toString())
        val manifest = parsed.value
        assertEquals("水星包", manifest.name)
        assertEquals("1.20.1", manifest.gameVersion)
        assertEquals(LoaderKind.NEOFORGE, manifest.loader)
        assertEquals("21.1.80", manifest.loaderVersion)
        assertEquals(listOf("overrides"), manifest.overrideDirs)
        // 必须告诉用户这是按 CurseForge 字段读的，而不是包写错了
        assertTrue(manifest.warnings.any { it.contains("CurseForge") }, manifest.warnings.toString())
    }

    @Test
    fun `对上号的文件把 ID 写进清单但实体仍打进包里`() = runBlocking {
        val jar = write("mods/sodium.jar", "sodium content")
        val fingerprint = io.github.huang404yan.platypus.core.mods.CurseForgeFingerprint.of(jar)
        var asked: List<Long> = emptyList()
        val lookup = ModpackExport.CurseLookup { idList ->
            asked = idList
            mapOf(fingerprint to (394468 to 5123456))
        }
        val target = File(root, "cf/ids.zip")
        val result = ModpackExport.export(
            instance(LoaderKind.FORGE, "47.2.0"),
            target,
            ModpackExportOptions(version = "2.0.0", format = ModpackExportFormat.CURSE),
            curseLookup = lookup,
        )
        assertEquals(listOf(fingerprint), asked)
        assertEquals(1, result.curseFiles)
        val manifest = textOf(target, ModpackExport.MANIFEST_NAME).orEmpty()
        assertTrue(manifest.contains("\"projectID\": 394468"), manifest)
        assertTrue(manifest.contains("\"fileID\": 5123456"), manifest)
        assertTrue(manifest.contains("\"required\": true"), manifest)
        // 实体还在：省掉它的话这个包自己都装不回来
        assertTrue(entriesOf(target).contains("overrides/mods/sodium.jar"), entriesOf(target).toString())
        assertEquals(1, result.packaged)
        assertTrue(result.warnings.any { it.contains("CurseForge") }, result.warnings.toString())
    }

    @Test
    fun `没对上号的文件不进 files 列表`() = runBlocking {
        write("mods/sodium.jar", "sodium content")
        write("mods/other.jar", "other content")
        val lookup = ModpackExport.CurseLookup { emptyMap() }
        val target = File(root, "cf/none.zip")
        val result = ModpackExport.export(
            instance(),
            target,
            ModpackExportOptions(format = ModpackExportFormat.CURSE),
            curseLookup = lookup,
        )
        assertEquals(0, result.curseFiles)
        assertEquals(2, result.packaged)
        val manifest = textOf(target, ModpackExport.MANIFEST_NAME).orEmpty()
        assertFalse(manifest.contains("projectID"), manifest)
    }

    @Test
    fun `核对 CurseForge 失败时整包照打并留一句警告`() = runBlocking {
        write("mods/sodium.jar")
        val lookup = ModpackExport.CurseLookup { throw java.io.IOException("连不上") }
        val target = File(root, "cf/fail.zip")
        val result = ModpackExport.export(
            instance(),
            target,
            ModpackExportOptions(format = ModpackExportFormat.CURSE),
            curseLookup = lookup,
        )
        assertEquals(0, result.curseFiles)
        assertEquals(1, result.packaged)
        assertTrue(result.warnings.any { it.contains("向 CurseForge 核对文件失败") }, result.warnings.toString())
        assertTrue(entriesOf(target).contains("overrides/mods/sodium.jar"), entriesOf(target).toString())
    }

    @Test
    fun `MCBBS 包的清单里不写 files 列表`() = runBlocking {
        write("mods/sodium.jar")
        val lookup = ModpackExport.CurseLookup { mapOf(123L to (1 to 2)) }
        val target = File(root, "cf/mcbbs.zip")
        ModpackExport.export(
            instance(),
            target,
            ModpackExportOptions(format = ModpackExportFormat.MCBBS),
            curseLookup = lookup,
        )
        val manifest = textOf(target, ModpackExport.MANIFEST_NAME).orEmpty()
        assertFalse(manifest.contains("projectID"), manifest)
    }

    // ---- Modrinth ----

    @Test
    fun `Modrinth 包的 dependencies 用 fabric-loader 这个键`() = runBlocking {
        write("mods/sodium.jar")
        val target = File(root, "pack.mrpack")
        ModpackExport.export(instance(), target, ModpackExportOptions(format = ModpackExportFormat.MRPACK))
        val index = textOf(target, ModpackExport.INDEX_NAME).orEmpty()
        assertTrue(index.contains("\"fabric-loader\""), index)
        assertTrue(index.contains("\"formatVersion\": 1"), index)
        assertTrue(index.contains("\"game\": \"minecraft\""), index)
        assertFalse(entriesOf(target).contains(ModpackExport.PACKMETA_NAME))
    }

    @Test
    fun `认出来的文件只留下载地址不打进包里`() = runBlocking {
        val jar = write("mods/sodium.jar", "sodium-bytes")
        val sha1 = ModUpdateChecker.sha1Of(jar)
        val target = File(root, "hosted.mrpack")
        val result = ModpackExport.export(
            instance = instance(),
            target = target,
            options = ModpackExportOptions(format = ModpackExportFormat.MRPACK),
            hostedLookup = ModpackExport.HostedLookup { hashes ->
                if (hashes.contains(sha1)) mapOf(sha1 to listOf("https://cdn.modrinth.com/x.jar")) else emptyMap()
            },
        )
        assertEquals(1, result.hosted)
        assertEquals(0, result.packaged)
        val index = textOf(target, ModpackExport.INDEX_NAME).orEmpty()
        assertTrue(index.contains("https://cdn.modrinth.com/x.jar"), index)
        assertTrue(index.contains(sha1), index)
        // 打进包里的实体文件应该是零个
        assertFalse(entriesOf(target).contains("overrides/mods/sodium.jar"))
    }

    @Test
    fun `核对网络失败时全部打进包里并留一句警告`() = runBlocking {
        write("mods/sodium.jar", "sodium-bytes")
        val target = File(root, "offline.mrpack")
        val result = ModpackExport.export(
            instance = instance(),
            target = target,
            options = ModpackExportOptions(format = ModpackExportFormat.MRPACK),
            hostedLookup = ModpackExport.HostedLookup { throw java.io.IOException("连不上 Modrinth") },
        )
        assertEquals(1, result.packaged)
        assertEquals(0, result.hosted)
        assertTrue(result.warnings.any { it.contains("连不上 Modrinth") }, result.warnings.toString())
        assertTrue(entriesOf(target).contains("overrides/mods/sodium.jar"))
    }

    @Test
    fun `下载地址里 Modrinth 自己的排最后`() = runBlocking {
        val jar = write("mods/sodium.jar", "sodium-bytes")
        val sha1 = ModUpdateChecker.sha1Of(jar)
        val target = File(root, "order.mrpack")
        ModpackExport.export(
            instance = instance(),
            target = target,
            options = ModpackExportOptions(format = ModpackExportFormat.MRPACK),
            hostedLookup = ModpackExport.HostedLookup { hashes ->
                if (hashes.contains(sha1)) {
                    mapOf(sha1 to listOf("https://cdn.modrinth.com/x.jar", "https://mirror.example/x.jar"))
                } else {
                    emptyMap()
                }
            },
        )
        val index = textOf(target, ModpackExport.INDEX_NAME).orEmpty()
        assertTrue(index.indexOf("mirror.example") < index.indexOf("cdn.modrinth.com"), index)
    }

    // ---- 闭环：导出的包能被自己的解析器读回来 ----

    @Test
    fun `导出的 MCBBS 包能被自己的解析器读回来`() = runBlocking {
        write("mods/sodium.jar")
        val target = File(root, "roundtrip.zip")
        ModpackExport.export(
            instance(LoaderKind.NEOFORGE, "21.1.77"),
            target,
            ModpackExportOptions(name = "往回读", version = "3.1.4"),
        )
        val layout = ModpackMeta.detect(entriesOf(target))
        assertEquals(ModpackKind.MCBBS_V2, layout.kind)
        val manifest = ModpackMeta.parseMcbbs(
            textOf(target, ModpackExport.PACKMETA_NAME),
            textOf(target, ModpackExport.MANIFEST_NAME),
        ).getOrNull()
        assertNotNull(manifest)
        assertEquals("往回读", manifest.name)
        assertEquals("3.1.4", manifest.versionId)
        assertEquals("1.20.1", manifest.gameVersion)
        assertEquals(LoaderKind.NEOFORGE, manifest.loader)
        assertEquals("21.1.77", manifest.loaderVersion)
    }

    @Test
    fun `导出的 Modrinth 包能被自己的解析器读回来`() = runBlocking {
        write("mods/sodium.jar", "sodium-bytes")
        val jar = File(root, "versions/test-1.20.1/mods/sodium.jar")
        val sha1 = ModUpdateChecker.sha1Of(jar)
        val target = File(root, "roundtrip.mrpack")
        ModpackExport.export(
            instance = instance(),
            target = target,
            options = ModpackExportOptions(name = "回来", format = ModpackExportFormat.MRPACK),
            hostedLookup = ModpackExport.HostedLookup { mapOf(sha1 to listOf("https://cdn.modrinth.com/x.jar")) },
        )
        val layout = ModpackMeta.detect(entriesOf(target))
        assertEquals(ModpackKind.MRPACK, layout.kind)
        val manifest = ModpackMeta.parseMrpack(textOf(target, ModpackExport.INDEX_NAME).orEmpty()).getOrNull()
        assertNotNull(manifest)
        assertEquals("回来", manifest.name)
        assertEquals("1.20.1", manifest.gameVersion)
        assertEquals(LoaderKind.FABRIC, manifest.loader)
        assertEquals(1, manifest.files.size)
        assertEquals("mods/sodium.jar", manifest.files.first().path)
        assertEquals(sha1, manifest.files.first().sha1)
    }

    // ---- 边界 ----

    @Test
    fun `没有可导出文件时不写包而是说清原因`() = runBlocking {
        val target = File(root, "empty.zip")
        val error = kotlin.runCatching {
            ModpackExport.export(instance(), target, ModpackExportOptions())
        }.exceptionOrNull()
        assertTrue(error is ModpackExportException, error?.toString().orEmpty())
        assertFalse(target.exists(), "失败时不该留下半个包")
    }

    @Test
    fun `目标文件已存在时覆盖掉而不是报错`() = runBlocking {
        write("mods/sodium.jar")
        val target = File(root, "overwrite.zip")
        target.writeText("旧内容")
        ModpackExport.export(instance(), target, ModpackExportOptions())
        assertTrue(entriesOf(target).contains(ModpackExport.PACKMETA_NAME))
    }

    @Test
    fun `加载器版本缺失时留一句警告`() = runBlocking {
        write("mods/sodium.jar")
        val target = File(root, "noversion.zip")
        val result = ModpackExport.export(instance(LoaderKind.FORGE, null), target, ModpackExportOptions())
        assertTrue(result.warnings.any { it.contains("加载器版本") }, result.warnings.toString())
    }

    @Test
    fun `没装模组的实例导出来会被拒绝并说明白`() = runBlocking {
        val target = File(root, "nothing.zip")
        val error = kotlin.runCatching {
            ModpackExport.export(instance(), target, ModpackExportOptions())
        }.exceptionOrNull()
        val message = (error as? ModpackExportException)?.message.orEmpty()
        assertTrue(message.contains("没有可导出的文件"), message)
    }
}
