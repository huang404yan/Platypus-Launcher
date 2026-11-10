package io.github.huang404yan.platypus.core.modpack

import io.github.huang404yan.platypus.core.instance.LoaderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModpackMetaTest {

    private fun ok(result: ModpackParse<ModpackManifest>): ModpackManifest {
        assertTrue(result is ModpackParse.Ok, "本该解析成功，实际是 " + result)
        return result.value
    }

    private fun failure(result: ModpackParse<ModpackManifest>): String {
        assertTrue(result is ModpackParse.Failure, "本该失败，实际是 " + result)
        return result.reason
    }

    // ---------- 格式识别 ----------

    @Test
    fun `根目录有 modrinth 索引文件就认成 mrpack`() {
        val layout = ModpackMeta.detect(listOf("modrinth.index.json", "overrides/config/a.toml"))
        assertEquals(ModpackKind.MRPACK, layout.kind)
        assertEquals("", layout.basePrefix)
    }

    @Test
    fun `整个包套在顶层文件夹里也能认出 mrpack 并记住前缀`() {
        val layout = ModpackMeta.detect(listOf("MyPack/modrinth.index.json", "MyPack/overrides/x.txt"))
        assertEquals(ModpackKind.MRPACK, layout.kind)
        assertEquals("MyPack/", layout.basePrefix)
        // 前缀能拼出完整 entry 名，导入时靠它去找文件
        assertEquals("MyPack/modrinth.index.json", layout.entry("modrinth.index.json"))
    }

    @Test
    fun `mcbbs 包描述优先于 manifest 清单`() {
        val layout = ModpackMeta.detect(listOf("mcbbs.packmeta", "manifest.json", "overrides/x.txt"))
        assertEquals(ModpackKind.MCBBS_V2, layout.kind)
    }

    @Test
    fun `只有 manifest 清单没有 overrides 时不认成整合包`() {
        // 普通 jar 里也会带 manifest.json，单凭它认包会把模组当整合包
        assertEquals(ModpackKind.UNKNOWN, ModpackMeta.detect(listOf("manifest.json")).kind)
        // 配上 overrides/ 才认
        assertEquals(
            ModpackKind.MCBBS_V2,
            ModpackMeta.detect(listOf("manifest.json", "overrides/config/a.txt")).kind,
        )
    }

    @Test
    fun `mmc 包描述认得出来但标成未知格式`() {
        val layout = ModpackMeta.detect(listOf("mmc-pack.json", "instance.cfg"))
        // 认出来是为了给用户一句准确的话，而不是笼统的「不是整合包」
        assertEquals(ModpackKind.UNKNOWN, layout.kind)
    }

    // ---------- mrpack ----------

    private val mrpackJson = """
        {
          "formatVersion": 1,
          "game": "minecraft",
          "versionId": "1.0.0",
          "name": "Fabulously Optimized",
          "files": [
            {
              "path": "mods/sodium.jar",
              "hashes": {"sha1": "ABCDEF0123456789ABCDEF0123456789ABCDEF01", "sha512": "x"},
              "downloads": ["https://cdn.modrinth.com/data/a/versions/1/sodium.jar"],
              "fileSize": 1234
            },
            {
              "path": "mods/serveronly.jar",
              "hashes": {"sha1": "1111111111111111111111111111111111111111"},
              "env": {"client": "unsupported", "server": "required"},
              "downloads": ["https://cdn.modrinth.com/data/b/versions/1/serveronly.jar"]
            },
            {
              "path": "mods/multi.jar",
              "hashes": {"sha1": "2222222222222222222222222222222222222222"},
              "downloads": ["https://cdn.modrinth.com/data/c/m.jar", "https://github.com/c/m.jar"]
            }
          ],
          "dependencies": {"minecraft": "1.20.1", "fabric-loader": "0.15.11"}
        }
    """.trimIndent()

    @Test
    fun `解析 mrpack 的基本字段与加载器`() {
        val manifest = ok(ModpackMeta.parseMrpack(mrpackJson))
        assertEquals(ModpackKind.MRPACK, manifest.kind)
        assertEquals("Fabulously Optimized", manifest.name)
        assertEquals("1.0.0", manifest.versionId)
        assertEquals("1.20.1", manifest.gameVersion)
        assertEquals(LoaderKind.FABRIC, manifest.loader)
        assertEquals("0.15.11", manifest.loaderVersion)
        // 客户端只取 overrides 与 client-overrides，server-overrides 不装
        assertEquals(listOf("overrides", "client-overrides"), manifest.overrideDirs)
        assertEquals("1.20.1 + Fabric 0.15.11", manifest.summary)
    }

    @Test
    fun `解析 mrpack 的文件表并把备用地址放进 mirrors`() {
        val manifest = ok(ModpackMeta.parseMrpack(mrpackJson))
        // serveronly 被 env.client=unsupported 过滤掉，所以是 2 个
        assertEquals(2, manifest.files.size)
        val sodium = manifest.files.first { it.path == "mods/sodium.jar" }
        assertEquals("https://cdn.modrinth.com/data/a/versions/1/sodium.jar", sodium.url)
        assertEquals(1234L, sodium.size)
        // sha1 统一小写，方便后面直接比对下载结果
        assertEquals("abcdef0123456789abcdef0123456789abcdef01", sodium.sha1)
        assertEquals("mods", sodium.topDir)
        val multi = manifest.files.first { it.path == "mods/multi.jar" }
        assertEquals(listOf("https://github.com/c/m.jar"), multi.mirrors)
    }

    @Test
    fun `mrpack 缺 minecraft 依赖时直接失败`() {
        val reason = failure(
            ModpackMeta.parseMrpack(
                """{"formatVersion":1,"game":"minecraft","name":"x","files":[],"dependencies":{"fabric-loader":"0.15"}}""",
            ),
        )
        assertTrue(reason.contains("dependencies.minecraft"), reason)
    }

    @Test
    fun `mrpack 不是 JSON 时失败`() {
        assertTrue(failure(ModpackMeta.parseMrpack("not json")).contains("不是合法 JSON"))
    }

    @Test
    fun `mrpack 的 quilt 包明确拒绝`() {
        val reason = failure(
            ModpackMeta.parseMrpack(
                """{"formatVersion":1,"game":"minecraft","dependencies":{"minecraft":"1.20.1","quilt-loader":"0.23.1"}}""",
            ),
        )
        assertTrue(reason.contains("Quilt"), reason)
    }

    @Test
    fun `mrpack 遇到不认识的加载器键只警告不失败`() {
        val manifest = ok(
            ModpackMeta.parseMrpack(
                """{"formatVersion":1,"game":"minecraft","name":"n","dependencies":{"minecraft":"1.20.1","liteloader":"1.12"}}""",
            ),
        )
        assertEquals(LoaderKind.OTHER, manifest.loader)
        assertEquals("", manifest.versionId)
        assertTrue(manifest.warnings.any { it.contains("liteloader") }, manifest.warnings.toString())
    }

    @Test
    fun `mrpack 路径越界的文件被丢掉并记警告`() {
        val manifest = ok(
            ModpackMeta.parseMrpack(
                """
                {"formatVersion":1,"game":"minecraft","name":"n","dependencies":{"minecraft":"1.20.1"},
                 "files":[
                   {"path":"../../evil.jar","hashes":{"sha1":"3333333333333333333333333333333333333333"},"downloads":["https://a/b"]},
                   {"path":"C:/evil.jar","hashes":{"sha1":"4444444444444444444444444444444444444444"},"downloads":["https://a/c"]},
                   {"path":"mods/ok.jar","hashes":{"sha1":"5555555555555555555555555555555555555555"},"downloads":["https://a/d"]}
                 ]}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("mods/ok.jar"), manifest.files.map { it.path })
        assertTrue(manifest.warnings.any { it.contains("越界") }, manifest.warnings.toString())
    }

    // ---------- MCBBS v2 ----------

    @Test
    fun `解析 MCBBS 包的游戏版本与 Forge`() {
        val manifest = ok(
            ModpackMeta.parseMcbbs(
                """
                {"name":"水星整合包","version":"2.1.0",
                 "addons":[{"id":"game","version":"1.20.1"},{"id":"forge","version":"47.2.0"}]}
                """.trimIndent(),
            ),
        )
        assertEquals(ModpackKind.MCBBS_V2, manifest.kind)
        assertEquals("水星整合包", manifest.name)
        assertEquals("2.1.0", manifest.versionId)
        assertEquals("1.20.1", manifest.gameVersion)
        assertEquals(LoaderKind.FORGE, manifest.loader)
        assertEquals("47.2.0", manifest.loaderVersion)
        // MCBBS 包不含游戏文件，只声明 addons
        assertTrue(manifest.files.isEmpty())
        assertEquals(listOf("overrides"), manifest.overrideDirs)
    }

    @Test
    fun `MCBBS 包缺 game 时失败`() {
        val reason = failure(
            ModpackMeta.parseMcbbs("""{"name":"x","addons":[{"id":"forge","version":"47.2.0"}]}"""),
        )
        assertTrue(reason.contains("游戏版本信息"), reason)
    }

    @Test
    fun `MCBBS 包带 quilt 时明确拒绝`() {
        val reason = failure(
            ModpackMeta.parseMcbbs(
                """{"name":"x","addons":[{"id":"game","version":"1.20.1"},{"id":"quilt","version":"0.23"}]}""",
            ),
        )
        assertTrue(reason.contains("Quilt"), reason)
    }

    @Test
    fun `MCBBS 没包描述时退回 manifest 清单`() {
        val manifest = ok(
            ModpackMeta.parseMcbbs(
                packmetaText = null,
                manifestText = """{"name":"老包","addons":[{"id":"game","version":"1.12.2"}]}""",
            ),
        )
        assertEquals("老包", manifest.name)
        assertEquals("1.12.2", manifest.gameVersion)
        assertTrue(manifest.warnings.any { it.contains("manifest.json") }, manifest.warnings.toString())
    }

    @Test
    fun `MCBBS 的 launchInfo 参数被读出来`() {
        val manifest = ok(
            ModpackMeta.parseMcbbs(
                """
                {"name":"x","addons":[{"id":"game","version":"1.20.1"}],
                 "launchInfo":{"javaArgument":["-XX:+UseG1GC","-Xmx8G"],"launchArgument":["-Dfoo=bar"]}}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("-XX:+UseG1GC", "-Xmx8G"), manifest.extraJvmArgs)
        assertEquals(listOf("-Dfoo=bar"), manifest.extraGameArgs)
    }

    @Test
    fun `MCBBS 的未知组件记警告但不失败`() {
        val manifest = ok(
            ModpackMeta.parseMcbbs(
                """{"name":"x","addons":[{"id":"game","version":"1.20.1"},{"id":"optifine","version":"HD_U_I6"}]}""",
            ),
        )
        assertTrue(manifest.warnings.any { it.contains("OptiFine") }, manifest.warnings.toString())
    }

    @Test
    fun `两段文本都读不出来时失败`() {
        assertTrue(failure(ModpackMeta.parseMcbbs("坏", "也坏")).contains("读不出来"))
    }

    // ---------- 路径安全 ----------

    @Test
    fun `安全路径归一化反斜杠与点段`() {
        assertEquals("mods/a.jar", ModpackMeta.safeRelativePath("mods\\a.jar"))
        assertEquals("mods/a.jar", ModpackMeta.safeRelativePath("./mods/a.jar"))
        assertEquals("mods/a.jar", ModpackMeta.safeRelativePath("mods//a.jar"))
    }

    @Test
    fun `路径穿越与盘符一律拒绝`() {
        assertNull(ModpackMeta.safeRelativePath("../a.jar"))
        assertNull(ModpackMeta.safeRelativePath("mods/../../a.jar"))
        assertNull(ModpackMeta.safeRelativePath("C:/evil.jar"))
        assertNull(ModpackMeta.safeRelativePath("C:\\evil.jar"))
        assertNull(ModpackMeta.safeRelativePath("/etc/passwd"))
    }

    @Test
    fun `名字里含两点但整段不是两点时放行`() {
        // 按文档字面「不能含 ..」去拦会把 ..hidden.jar 这种真文件名误杀
        assertEquals("mods/..hidden.jar", ModpackMeta.safeRelativePath("mods/..hidden.jar"))
        assertEquals("mods/a..b.jar", ModpackMeta.safeRelativePath("mods/a..b.jar"))
    }

    @Test
    fun `空路径与纯点段拒绝`() {
        assertNull(ModpackMeta.safeRelativePath(""))
        assertNull(ModpackMeta.safeRelativePath("   "))
        assertNull(ModpackMeta.safeRelativePath("./"))
        assertNull(ModpackMeta.safeRelativePath("."))
    }
}
