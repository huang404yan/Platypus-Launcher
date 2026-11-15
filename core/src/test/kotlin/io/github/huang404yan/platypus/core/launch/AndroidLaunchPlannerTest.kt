package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.auth.Session
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
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
 * [AndroidLaunchPlanner] 的测试：验算「安卓这一层拼出来的东西对不对」。
 *
 * 真机能不能起来是另一回事（要 JNI 与图形后端），但
 * 「参数少了哪一条」「-Xmx 有没有被漏过去」「按哪个平台挑库」全都能在这里验完。
 */
class AndroidLaunchPlannerTest {

    private val root: File = Files.createTempDirectory("platypus-android-plan").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun dir(name: String): File = File(root, name).also { it.mkdirs() }

    private fun session() = Session(
        username = "Steve",
        uuid = "0123456789abcdef0123456789abcdef",
        accessToken = "token-abc",
    )

    /** 占位符先写成 @D@，用时换成真正的美元符 —— 直接写会被 Kotlin 当模板求值。 */
    private val vanillaJson = """
        {
          "id": "1.20.1",
          "mainClass": "net.minecraft.client.main.Main",
          "assets": "5",
          "assetIndex": { "id": "5" },
          "libraries": [],
          "arguments": {
            "jvm": ["-Djava.library.path=@D@{natives_directory}", "-cp", "@D@{classpath}"],
            "game": ["--username", "@D@{auth_player_name}", "--version", "@D@{version_name}"]
          }
        }
    """.replace("@D@", "$")

    private fun versionDir(id: String, withJar: Boolean = true): File {
        val dir = File(File(root, "versions"), id)
        dir.mkdirs()
        File(dir, id + ".json").writeText(vanillaJson.replace("1.20.1", id), Charsets.UTF_8)
        if (withJar) File(dir, id + ".jar").writeText("jar", Charsets.UTF_8)
        return dir
    }

    private fun instance(dir: File, extraJvmArgs: List<String> = emptyList(), memoryMb: Int = 4096) = Instance(
        id = "1",
        name = "测试实例",
        gameVersion = "1.20.1",
        directory = dir.absolutePath,
        memoryMb = memoryMb,
        extraJvmArgs = extraJvmArgs,
    )

    private fun request(instance: Instance, jvmArgs: List<String> = emptyList()) = LaunchRequest(
        instance = instance,
        java = JavaRuntime(path = File(root, "java/bin/java").absolutePath, majorVersion = 17),
        session = session(),
        jvmArgs = jvmArgs,
    )

    /** 一个「自检全过」的配置：所有目录都在，JRE 齐全，渲染库也解出来了。 */
    private fun readyConfig(renderer: AndroidRenderer = AndroidRenderer.DEFAULT): AndroidLaunchConfig {
        // javaHome 是从 request.java.path 往上两级推的，所以 JRE 必须落在 <root>/java 下。
        val javaHome = dir("java")
        File(javaHome, "bin").mkdirs()
        File(javaHome, "release").writeText("JAVA_VERSION=\"17.0.9\"")
        File(javaHome, "lib").mkdirs()
        File(javaHome, "lib/libjvm.so").writeText("x")
        File(javaHome, "bin/java").writeText("x")
        val nativeDir = dir("natives")
        File(nativeDir, renderer.libraryName).writeText("x")
        val tmpDir = dir("tmp")
        return AndroidLaunchConfig(
            abi = "arm64-v8a",
            androidRelease = "14",
            renderer = renderer,
            tmpDir = tmpDir,
            nativeDir = nativeDir,
        )
    }

    @Test
    fun `环境齐了时候能拼出完整参数表`() {
        val version = versionDir("1.20.1")
        val config = readyConfig()
        val prep = AndroidLaunchPlanner.prepare(request(instance(version)), config)
        assertTrue(prep.ready, prep.summary)
        assertTrue(prep.blocked.isEmpty())
        val plan = assertNotNull(prep.plan)
        assertEquals("net.minecraft.client.main.Main", plan.command.mainClass)
        // 安卓那十几条在前，版本 json 拼出来的在后。
        assertTrue(prep.fullCommand.first().startsWith("-Djava.home="), prep.fullCommand.take(3).toString())
        assertTrue(prep.fullCommand.contains("net.minecraft.client.main.Main"))
        assertEquals(
            AndroidLaunchArgs.of(prep.env, config.renderer).size + plan.command.toArgumentList().size,
            prep.fullCommand.size,
        )
    }

    @Test
    fun `按安卓的口径挑库：classpath 分隔符是冒号`() {
        val version = versionDir("1.20.1")
        val prep = AndroidLaunchPlanner.prepare(request(instance(version)), readyConfig())
        val plan = assertNotNull(prep.plan)
        assertEquals(":", plan.context.classpathSeparator)
        assertEquals("android", plan.context.os.id)
        assertEquals("arm64-v8a", plan.context.arch)
    }

    @Test
    fun `内存参数由实例统一给只有一条`() {
        val version = versionDir("1.20.1")
        // 实例里手写一条 -Xmx4096M：必须被丢掉，只留启动器自己那条 -Xmx6144M。
        val prep = AndroidLaunchPlanner.prepare(
            request(instance(version, extraJvmArgs = listOf("-Xmx4096M"), memoryMb = 6144)),
            readyConfig(),
        )
        val plan = assertNotNull(prep.plan)
        val xmx = plan.command.jvmArgs.filter { it.startsWith("-Xmx") }
        assertEquals(listOf("-Xmx6144M"), xmx)
        assertEquals(1, prep.sanitized.dropped.size)
        assertEquals("-Xmx4096M", prep.sanitized.dropped[0].arg)
        assertTrue(prep.notes.any { it.contains("-Xmx4096M") }, prep.notes.toString())
    }

    @Test
    fun `请求里临时给的参数也会被筛`() {
        val version = versionDir("1.20.1")
        val prep = AndroidLaunchPlanner.prepare(
            request(instance(version), jvmArgs = listOf("-d64", "-Dmy.flag=1")),
            readyConfig(),
        )
        assertEquals(listOf("-Dmy.flag=1"), prep.sanitized.kept)
        assertTrue(prep.sanitized.dropped.any { it.arg == "-d64" })
        val plan = assertNotNull(prep.plan)
        assertTrue(plan.command.jvmArgs.contains("-Dmy.flag=1"), plan.command.jvmArgs.toString())
    }

    @Test
    fun `java 的 home 是从可执行文件往上两级推的`() {
        val version = versionDir("1.20.1")
        val config = readyConfig()
        val prep = AndroidLaunchPlanner.prepare(request(instance(version)), config)
        // request 里给的是 <root>/java/bin/java → home 应是 <root>/java
        assertEquals(File(root, "java").absolutePath, prep.env.javaHome.absolutePath)
        assertTrue(prep.fullCommand.contains("-Djava.home=" + File(root, "java").absolutePath))
    }

    @Test
    fun `版本文件不在时说清楚而不是拼一张跑不起来的表`() {
        val version = File(File(root, "versions"), "1.20.1")
        version.mkdirs()
        val prep = AndroidLaunchPlanner.prepare(request(instance(version)), readyConfig())
        assertNull(prep.plan)
        assertTrue(prep.fullCommand.isEmpty())
        assertFalse(prep.ready)
        assertTrue(prep.summary.contains("版本文件读不出来"), prep.summary)
        assertTrue(prep.notes.any { it.contains("1.20.1.json") }, prep.notes.toString())
    }

    @Test
    fun `没装 Java 时自检挡住且不拼参数表`() {
        val version = versionDir("1.20.1")
        val config = readyConfig()
        val prep = AndroidLaunchPlanner.prepare(
            request(instance(version)).copy(java = JavaRuntime(path = File(root, "nope/bin/java").absolutePath, majorVersion = 17)),
            config,
        )
        assertEquals(1, prep.blocked.size)
        assertEquals("Java 运行时", prep.blocked[0].label)
        assertFalse(prep.ready)
        assertTrue(prep.summary.contains("Java 运行时"), prep.summary)
        // 自检没过也要给出计划：调用方要先装 Java 再回来，不该连计划都拿不到。
        assertNotNull(prep.plan)
    }

    @Test
    fun `渲染库没解出来时挡住启动`() {
        val version = versionDir("1.20.1")
        val config = readyConfig()
        File(config.nativeDir, config.renderer.libraryName).delete()
        val prep = AndroidLaunchPlanner.prepare(request(instance(version)), config)
        assertEquals(1, prep.blocked.size)
        assertEquals("渲染库文件", prep.blocked[0].label)
        assertFalse(prep.ready)
    }

    @Test
    fun `换后端时参数表里那一条跟着换`() {
        val version = versionDir("1.20.1")
        val config = readyConfig(AndroidRenderer.ZINK)
        val prep = AndroidLaunchPlanner.prepare(request(instance(version)), config)
        assertTrue(prep.fullCommand.contains("-Dorg.lwjgl.opengl.libname=libOSMesa.so"), prep.fullCommand.take(10).toString())
        assertTrue(prep.ready, prep.summary)
    }

    @Test
    fun `直连降级的话会带到 notes 里`() {
        val version = versionDir("1.16.5")
        val prep = AndroidLaunchPlanner.prepare(
            request(instance(version)).copy(quickPlayServer = "127.0.0.1:25565"),
            readyConfig(),
        )
        assertTrue(prep.notes.any { it.contains("--server/--port") }, prep.notes.toString())
        val plan = assertNotNull(prep.plan)
        assertTrue(plan.command.gameArgs.contains("127.0.0.1"), plan.command.gameArgs.toString())
    }
}
