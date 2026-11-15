package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.auth.Session
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.version.OsFamily
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LaunchPlanner] 的测试：只验算「拼出来的东西对不对」，不起进程、不联网。
 *
 * 这一层值得单测，是因为启动失败有一半原因出在准备阶段（版本 json 读错位置、
 * classpath 少了 client jar、直连降级没说一声），而这些全都能用一张命令行验完。
 */
class LaunchPlannerTest {

    private val root: File = Files.createTempDirectory("platypus-plan-test").toFile()

    private fun session() = Session(
        username = "Steve",
        uuid = "0123456789abcdef0123456789abcdef",
        accessToken = "token-abc",
    )

    /** 造 <root>/versions/<id>/<id>.json 与 <id>.jar。 */
    private fun versionDir(id: String, json: String, withJar: Boolean = true): File {
        val dir = File(File(root, "versions"), id)
        dir.mkdirs()
        File(dir, id + ".json").writeText(json.trimIndent(), Charsets.UTF_8)
        if (withJar) File(dir, id + ".jar").writeText("jar", Charsets.UTF_8)
        return dir
    }

    private fun instance(dir: File, memoryMb: Int = 2048, extraJvmArgs: List<String> = emptyList()) = Instance(
        id = "1",
        name = "测试实例",
        gameVersion = "1.20.1",
        directory = dir.absolutePath,
        memoryMb = memoryMb,
        extraJvmArgs = extraJvmArgs,
    )

    private fun request(instance: Instance, quickPlay: String = "") = LaunchRequest(
        instance = instance,
        java = io.github.huang404yan.platypus.core.runtime.JavaRuntime(path = "/jdk/bin/java", majorVersion = 17),
        session = session(),
        quickPlayServer = quickPlay,
    )

    /** json 里的占位符先写成 @D@，用时再换成真正的美元符 —— 直接写会在 Kotlin 里被当模板求值。 */
    private val vanillaJson = """
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
              "--gameDir", "@D@{game_directory}"
            ]
          }
        }
    """.replace("@D@", "$")

    @Test
    fun `拼出的命令行有主类、内存与用户名`() {
        val dir = versionDir("1.20.1", vanillaJson)
        val plan = LaunchPlanner.plan(request(instance(dir, memoryMb = 4096)), os = OsFamily.WINDOWS, arch = "amd64")
        assertEquals("net.minecraft.client.main.Main", plan.command.mainClass)
        assertTrue(plan.command.jvmArgs.contains("-Xmx4096M"), plan.command.jvmArgs.toString())
        assertTrue(plan.command.gameArgs.contains("Steve"), plan.command.gameArgs.toString())
        assertTrue(plan.command.gameArgs.contains("1.20.1"), plan.command.gameArgs.toString())
    }

    @Test
    fun `classpath 末位是客户端 jar`() {
        val dir = versionDir("1.20.1", vanillaJson)
        val plan = LaunchPlanner.plan(request(instance(dir)), os = OsFamily.WINDOWS, arch = "amd64")
        assertEquals(dir.name, plan.layout.versionDir.name)
        assertNotNull(plan.clientJar)
        assertEquals(File(dir, "1.20.1.jar").absolutePath, plan.clientJar!!.absolutePath)
        assertTrue(plan.context.classpath.any { it.name == "1.20.1.jar" }, plan.context.classpath.toString())
    }

    @Test
    fun `版本文件不存在时直接说清是哪个路径`() {
        val dir = File(File(root, "versions"), "1.20.1")
        dir.mkdirs()
        val error = runCatching { LaunchPlanner.plan(request(instance(dir))) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("版本文件不存在"), error.message!!)
        assertTrue(error.message!!.contains("1.20.1.json"), error.message!!)
    }

    @Test
    fun `继承父版本时主类与库都合并进来`() {
        versionDir(
            "1.20.1",
            """
            {
              "id": "1.20.1",
              "mainClass": "net.minecraft.client.main.Main",
              "assets": "5",
              "libraries": [],
              "arguments": { "game": ["--username", "@D@{auth_player_name}"] }
            }
            """.replace("@D@", "$"),
        )
        val childDir = versionDir(
            "1.20.1-forge",
            """
            {
              "id": "1.20.1-forge",
              "inheritsFrom": "1.20.1",
              "libraries": [],
              "arguments": { "jvm": ["-Dforge.launch.marker=1"] }
            }
            """,
            withJar = false,
        )
        val plan = LaunchPlanner.plan(request(instance(childDir)), os = OsFamily.WINDOWS, arch = "amd64")
        assertEquals("net.minecraft.client.main.Main", plan.command.mainClass)
        assertTrue(plan.command.jvmArgs.contains("-Dforge.launch.marker=1"), plan.command.jvmArgs.toString())
        // 子版本自己没有 jar，应当退回父版本目录里那个
        assertEquals("1.20.1.jar", plan.clientJar!!.name)
        assertTrue(plan.notes.any { it.contains("父版本") }, plan.notes.toString())
    }

    @Test
    fun `关掉隔离时游戏目录是根目录`() {
        val dir = versionDir("1.20.1", vanillaJson)
        val isolated = LaunchPlanner.plan(request(instance(dir)), os = OsFamily.WINDOWS, arch = "amd64")
        val shared = LaunchPlanner.plan(
            request(instance(dir).copy(isolated = false)),
            os = OsFamily.WINDOWS,
            arch = "amd64",
        )
        assertEquals(dir.absolutePath, isolated.context.gameDir.absolutePath)
        assertEquals(root.absolutePath, shared.context.gameDir.absolutePath)
        assertEquals(InstanceLayouts.of(instance(dir)).gameRoot.absolutePath, shared.context.gameDir.absolutePath)
    }

    @Test
    fun `实例额外 JVM 参数排在 json 参数之后`() {
        val dir = versionDir("1.20.1", vanillaJson)
        val plan = LaunchPlanner.plan(
            request(instance(dir, extraJvmArgs = listOf("-Dmy.flag=1"))),
            os = OsFamily.WINDOWS,
            arch = "amd64",
        )
        val jsonLast = plan.command.jvmArgs.indexOfLast { it == "-cp" || it.startsWith("-Djava.library.path") }
        val mine = plan.command.jvmArgs.indexOf("-Dmy.flag=1")
        assertTrue(mine > jsonLast, plan.command.jvmArgs.toString())
    }

    @Test
    fun `直连降级会留一句话`() {
        val dir = versionDir("1.16.5", vanillaJson.replace("1.20.1", "1.16.5"))
        val plan = LaunchPlanner.plan(
            request(instance(dir), quickPlay = "127.0.0.1:25565"),
            os = OsFamily.WINDOWS,
            arch = "amd64",
        )
        assertTrue(plan.notes.any { it.contains("--server/--port") }, plan.notes.toString())
        assertTrue(plan.command.gameArgs.contains("127.0.0.1"), plan.command.gameArgs.toString())
    }

    @Test
    fun `目标平台可以按调用方指定`() {
        val dir = versionDir("1.20.1", vanillaJson)
        val windows = LaunchPlanner.plan(request(instance(dir)), os = OsFamily.WINDOWS, arch = "amd64")
        val android = LaunchPlanner.plan(request(instance(dir)), os = OsFamily.LINUX, arch = "aarch64")
        assertEquals(";", windows.context.classpathSeparator)
        assertEquals(":", android.context.classpathSeparator)
        assertEquals(android.context.classpath, windows.context.classpath)
    }

    @Test
    fun `没有 quickPlay 的老版本不打开 feature`() {
        val dir = versionDir("1.16.5", vanillaJson.replace("1.20.1", "1.16.5"))
        val plan = LaunchPlanner.plan(request(instance(dir), quickPlay = "127.0.0.1:25565"), os = OsFamily.WINDOWS, arch = "amd64")
        assertTrue(plan.context.features.isEmpty(), plan.context.features.toString())
    }

    @Test
    fun `写命令行时把令牌抹掉`() {
        val dir = versionDir("1.20.1", vanillaJson)
        val plan = LaunchPlanner.plan(request(instance(dir)), os = OsFamily.WINDOWS, arch = "amd64")
        val file = LaunchPlanner.dumpCommandLine(
            plan.layout.gameDir,
            plan.layout.versionDir.name,
            listOf("/jdk/bin/java", "--accessToken", "token-abc"),
            session(),
        )
        assertNotNull(file)
        try {
            val text = file!!.readText(Charsets.UTF_8)
            assertTrue(text.contains("***"), text)
            assertTrue(!text.contains("token-abc"), text)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `历史遗留的实例目录解析不出来时不留半成品`() {
        val missing = File(root, "not-a-version-dir")
        missing.mkdirs()
        assertNull(runCatching { LaunchPlanner.plan(request(instance(missing))) }.getOrNull())
    }
}
