package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.auth.Session
import io.github.huang404yan.platypus.core.version.OsFamily
import io.github.huang404yan.platypus.core.version.parseVersionJson
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchArgumentsTest {

    /** 占位符的起始记号。直接写 "$" + "{" 才能避开 Kotlin 的字符串模板。 */
    private val placeholderOpen = "$" + "{"

    /** json 里的占位符先写成 @D@，运行时再换成真正的美元符。 */
    private fun version(json: String) = parseVersionJson(json.replace("@D@", "$"))

    private fun placeholder(key: String) = placeholderOpen + key + "}"

    private fun slashPath(file: File) = file.path.replace('\\', '/')

    private fun context(
        classpath: List<File> = listOf(File("/mc/libraries/a.jar"), File("/mc/versions/1.20.1/1.20.1.jar")),
    ) = LaunchContext(
        gameDir = File("/mc"),
        assetsDir = File("/mc/assets"),
        librariesDir = File("/mc/libraries"),
        nativesDir = File("/mc/natives/1.20.1"),
        classpath = classpath,
        session = Session(username = "Steve", uuid = "0123456789abcdef0123456789abcdef", accessToken = "0"),
        versionName = "1.20.1",
        memoryMb = 4096,
        os = OsFamily.WINDOWS,
        arch = "amd64",
    )

    private val vanilla = version(
        """
        {
          "id": "1.20.1",
          "mainClass": "net.minecraft.client.main.Main",
          "assets": "5",
          "assetIndex": { "id": "5" },
          "libraries": [],
          "arguments": {
            "jvm": ["-Djava.library.path=@D@{natives_directory}", "-cp", "@D@{classpath}"],
            "game": [
              "--username", "@D@{auth_player_name}",
              "--version", "@D@{version_name}",
              "--gameDir", "@D@{game_directory}",
              "--assetsDir", "@D@{assets_root}",
              "--assetIndex", "@D@{assets_index_name}"
            ]
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `build 拼出内存参数、主类与游戏参数`() {
        val command = LaunchArguments.build(vanilla, context())
        assertEquals("net.minecraft.client.main.Main", command.mainClass)
        assertEquals("-Xmx4096M", command.jvmArgs.first())
        assertTrue(command.jvmArgs.contains("-Djava.library.path=" + File("/mc/natives/1.20.1").path))
    }

    @Test
    fun `游戏参数里的占位符全部被替换`() {
        val command = LaunchArguments.build(vanilla, context())
        val args = command.gameArgs
        assertEquals("--username", args[0])
        assertEquals("Steve", args[1])
        assertEquals("1.20.1", args[3])
        assertEquals(File("/mc").path, args[5])
        assertEquals(File("/mc/assets").path, args[7])
        assertEquals("5", args[9])
        // 任何一个漏替换的占位符都会让游戏静默起不来，这里守住整张参数表。
        assertFalse(args.any { it.contains(placeholderOpen) })
        assertFalse(command.jvmArgs.any { it.contains(placeholderOpen) })
    }

    @Test
    fun `命令行顺序是 jvm 参数、主类、游戏参数`() {
        val list = LaunchArguments.build(vanilla, context()).toArgumentList()
        val mainIndex = list.indexOf("net.minecraft.client.main.Main")
        assertTrue(mainIndex > 0)
        assertTrue(list.indexOf("--username") > mainIndex)
        assertEquals("-Xmx4096M", list[0])
    }

    @Test
    fun `classpath 里各级 jar 用平台分隔符连起来`() {
        val expected = File("/mc/libraries/a.jar").path + ";" + File("/mc/versions/1.20.1/1.20.1.jar").path
        assertTrue(LaunchArguments.build(vanilla, context()).jvmArgs.contains(expected))
    }

    @Test
    fun `rules 不匹配的参数整段丢掉`() {
        val meta = version(
            """
            {
              "id": "1.20.1",
              "mainClass": "net.minecraft.client.main.Main",
              "arguments": {
                "jvm": [
                  { "rules": [{ "action": "allow", "os": { "name": "osx" } }], "value": "-XstartOnFirstThread" },
                  { "rules": [{ "action": "allow", "os": { "name": "windows" } }], "value": ["-XX:+UseG1GC"] }
                ],
                "game": []
              }
            }
            """.trimIndent(),
        )
        val jvm = LaunchArguments.build(meta, context()).jvmArgs
        assertFalse(jvm.contains("-XstartOnFirstThread"))
        assertTrue(jvm.contains("-XX:+UseG1GC"))
    }

    @Test
    fun `features 未开启时对应参数不出现`() {
        val meta = version(
            """
            {
              "id": "1.20.1",
              "mainClass": "net.minecraft.client.main.Main",
              "arguments": {
                "jvm": [],
                "game": [
                  { "rules": [{ "action": "allow", "features": { "is_demo_user": true } }], "value": "--demo" },
                  "--version", "1.20.1"
                ]
              }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("--version", "1.20.1"), LaunchArguments.build(meta, context()).gameArgs)
    }

    @Test
    fun `日志编码参数两条都在，且排在 -Xmx 之后`() {
        // 中文 Windows 上游戏默认按 GBK 写 stdout，读侧按 UTF-8 解就全是 U+FFFD。
        // JDK 18+ 认 stdout.encoding、Java 17 及更早只认 file.encoding，两个都得传。
        val jvm = LaunchArguments.build(vanilla, context()).jvmArgs
        assertTrue(jvm.contains("-Dstdout.encoding=UTF-8"))
        assertTrue(jvm.contains("-Dfile.encoding=UTF-8"))
        assertTrue(jvm.indexOf("-Dstdout.encoding=UTF-8") > 0)
    }

    @Test
    fun `没有 jvm 参数的老版本也有日志编码参数`() {
        // 1.5.2 这类 json 里没有 arguments.jvm，走的是自己补 -cp 的分支，
        // 编码参数在那条分支之前加上，所以照样出现。
        val meta = version(
            """
            {
              "id": "1.5.2",
              "mainClass": "net.minecraft.client.Minecraft",
              "minecraftArguments": "--username @D@{auth_player_name}"
            }
            """.trimIndent(),
        )
        val jvm = LaunchArguments.build(meta, context()).jvmArgs
        assertTrue(jvm.contains("-Dstdout.encoding=UTF-8"))
        assertTrue(jvm.contains("-Dfile.encoding=UTF-8"))
    }

    @Test
    fun `用户仍能用额外参数把编码改回去`() {
        // JVM 对重复的 -D 是后者覆盖前者，所以放在 json 与 extraJvmArgs 之前才不会挡住用户。
        val command = LaunchArguments.build(vanilla, context().copy(extraJvmArgs = listOf("-Dfile.encoding=GBK")))
        assertEquals("-Dfile.encoding=GBK", command.jvmArgs.last())
        assertTrue(command.jvmArgs.count { it.startsWith("-Dfile.encoding=") } == 2)
    }

    @Test
    fun `额外 jvm 参数与游戏参数追加在最后`() {
        val command = LaunchArguments.build(
            vanilla,
            context().copy(extraJvmArgs = listOf("-Dfoo=bar"), extraGameArgs = listOf("--width", "1280")),
        )
        assertEquals("-Dfoo=bar", command.jvmArgs.last())
        assertEquals(listOf("--width", "1280"), command.gameArgs.takeLast(2))
    }

    @Test
    fun `没有 jvm 参数的老版本自动补 library path 与 classpath`() {
        val meta = version(
            """
            {
              "id": "1.5.2",
              "mainClass": "net.minecraft.client.Minecraft",
              "assets": "legacy",
              "minecraftArguments": "--username @D@{auth_player_name} --gameDir @D@{game_directory}"
            }
            """.trimIndent(),
        )
        val command = LaunchArguments.build(meta, context())
        assertTrue(command.jvmArgs.contains("-Djava.library.path=" + File("/mc/natives/1.20.1").path))
        assertTrue(command.jvmArgs.contains("-cp"))
        assertEquals(listOf("--username", "Steve", "--gameDir", File("/mc").path), command.gameArgs)
    }

    @Test
    fun `classpath 分隔符按平台切换`() {
        assertTrue(LaunchArguments.build(vanilla, context()).jvmArgs.any { it.contains(";") })
        val linux = LaunchArguments.build(vanilla, context().copy(os = OsFamily.LINUX))
        assertTrue(linux.jvmArgs.any { it.contains(":") && !it.contains(";") })
    }

    @Test
    fun `substitute 不动未知占位符`() {
        assertEquals("a-1", LaunchArguments.substitute("a-" + placeholder("x"), mapOf("x" to "1")))
        val unknown = "a-" + placeholder("y")
        assertEquals(unknown, LaunchArguments.substitute(unknown, mapOf("x" to "1")))
    }

    @Test
    fun `资源索引名优先用 assetIndex 的 id`() {
        assertEquals("5", LaunchArguments.assetIndexName(vanilla))
        assertEquals("legacy", LaunchArguments.assetIndexName(version("""{ "id": "1.5.2", "assets": "legacy" }""")))
    }

    @Test
    fun `natives 目录按版本隔离`() {
        assertEquals(
            File(File("/mc", "natives"), "1.20.1").path,
            LaunchArguments.nativesDirFor(File("/mc"), "1.20.1").path,
        )
    }

    @Test
    fun `老版本的 natives jar 不进 classpath 但会被解压`() {
        val meta = version(
            """
            {
              "id": "1.16.5",
              "mainClass": "net.minecraft.client.main.Main",
              "libraries": [
                {
                  "name": "org.lwjgl:lwjgl:3.2.2",
                  "downloads": {
                    "artifact": { "path": "org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2.jar" },
                    "classifiers": { "natives-windows": { "path": "org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2-natives-windows.jar" } }
                  },
                  "natives": { "windows": "natives-windows" },
                  "extract": { "exclude": ["META-INF/"] }
                }
              ]
            }
            """.trimIndent(),
        )
        val ctx = context()
        // 带 natives 字段的库整体不进 classpath，只把 classifier 那一个 jar 解压。
        assertEquals(0, LaunchArguments.classpathLibraries(meta, ctx).size)
        val natives = LaunchArguments.nativeArchives(meta, ctx)
        assertEquals(1, natives.size)
        assertTrue(slashPath(natives[0].jar).endsWith("org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2-natives-windows.jar"))
        assertEquals(listOf("META-INF/"), natives[0].exclude)
    }

    @Test
    fun `1_19 之后平铺的 natives 反而要进 classpath`() {
        val meta = version(
            """
            {
              "id": "1.20.1",
              "mainClass": "net.minecraft.client.main.Main",
              "libraries": [
                {
                  "name": "org.lwjgl:lwjgl:3.3.1:natives-windows",
                  "downloads": { "artifact": { "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar" } }
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(1, LaunchArguments.classpathLibraries(meta, context()).size)
        assertEquals(0, LaunchArguments.nativeArchives(meta, context()).size)
    }

    @Test
    fun `没有 downloads 字段时用 maven 坐标推路径`() {
        val meta = version(
            """
            {
              "id": "1.20.1",
              "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
              "libraries": [ { "name": "net.minecraftforge:forge:47.4.28" } ]
            }
            """.trimIndent(),
        )
        val files = LaunchArguments.classpathLibraries(meta, context())
        assertEquals(1, files.size)
        assertTrue(slashPath(files[0]).endsWith("net/minecraftforge/forge/47.4.28/forge-47.4.28.jar"))
    }

    @Test
    fun `parseRules 解析 action os 与 features`() {
        val element = Json.parseToJsonElement(
            """
            [
              { "action": "allow" },
              { "action": "disallow", "os": { "name": "osx" }, "features": { "has_custom_resolution": true } }
            ]
            """.trimIndent(),
        )
        val rules = LaunchArguments.parseRules(element)
        assertEquals(2, rules.size)
        assertEquals("allow", rules[0].action)
        assertEquals("disallow", rules[1].action)
        assertEquals("osx", rules[1].os?.name)
        assertEquals(mapOf("has_custom_resolution" to true), rules[1].features)
    }

    @Test
    fun `非数组的 rules 返回空列表`() {
        assertTrue(LaunchArguments.parseRules(Json.parseToJsonElement("""{ "action": "allow" }""")).isEmpty())
    }

    /** 1.20.1 的 game 段里真实存在的那条直接连接规则。 */
    private val quickPlayVersion = version(
        """
        {
          "id": "1.20.1",
          "mainClass": "net.minecraft.client.main.Main",
          "arguments": {
            "jvm": [],
            "game": [
              { "rules": [{ "action": "allow", "features": { "is_quick_play_multiplayer": true } }],
                "value": ["--quickPlayMultiplayer", "@D@{quickPlayMultiplayer}"] },
              "--version", "1.20.1"
            ]
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `填了直连地址并打开特性开关才会有 quickPlayMultiplayer`() {
        val command = LaunchArguments.build(
            quickPlayVersion,
            context().copy(features = LaunchArguments.quickPlayFeatures(), quickPlayMultiplayer = "192.168.1.10:54321"),
        )
        val i = command.gameArgs.indexOf("--quickPlayMultiplayer")
        assertTrue(i >= 0)
        assertEquals("192.168.1.10:54321", command.gameArgs[i + 1])
    }

    @Test
    fun `不打开特性开关时直连参数整段不出现`() {
        // 只填占位符是不够的：这条参数被 rules 挡着，特性不打开根本不会被拼进来。
        val command = LaunchArguments.build(
            quickPlayVersion,
            context().copy(quickPlayMultiplayer = "192.168.1.10:54321"),
        )
        assertFalse(command.gameArgs.contains("--quickPlayMultiplayer"))
        assertFalse(command.gameArgs.any { it.contains("192.168.1.10") })
    }

    @Test
    fun `1_20 之前的版本文件不认直接连接`() {
        assertTrue(LaunchArguments.supportsQuickPlay(quickPlayVersion))
        assertFalse(LaunchArguments.supportsQuickPlay(vanilla))
        assertFalse(
            LaunchArguments.supportsQuickPlay(
                version("""{ "id": "1.12.2", "minecraftArguments": "--username @D@{auth_player_name}" }"""),
            ),
        )
    }

    @Test
    fun `rules 里 feature 为假时直连参数同样不出现`() {
        val meta = version(
            """
            {
              "id": "1.20.1",
              "mainClass": "net.minecraft.client.main.Main",
              "arguments": {
                "jvm": [],
                "game": [
                  { "rules": [{ "action": "allow", "features": { "is_quick_play_multiplayer": false } }],
                    "value": ["--quickPlayMultiplayer", "@D@{quickPlayMultiplayer}"] }
                ]
              }
            }
            """.trimIndent(),
        )
        val command = LaunchArguments.build(
            meta,
            context().copy(features = LaunchArguments.quickPlayFeatures(), quickPlayMultiplayer = "1.2.3.4:25565"),
        )
        assertFalse(command.gameArgs.contains("--quickPlayMultiplayer"))
    }
}
