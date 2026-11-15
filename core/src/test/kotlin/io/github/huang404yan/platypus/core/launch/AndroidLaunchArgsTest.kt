package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.version.OsFamily
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 这一层不碰进程、不碰 JNI，只校验「参数拼得对不对」与「自检判据对不对」。
 * 真机能不能进游戏是另一回事。
 */
class AndroidLaunchArgsTest {

    private val root: File = Files.createTempDirectory("platypus-android-args").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun dir(name: String): File = File(root, name).also { it.mkdirs() }

    /** 一个「什么都齐」的环境：目录都在，JRE 有 release 与 libjvm.so，渲染库也解出来了。 */
    private fun readyEnv(renderer: AndroidRenderer = AndroidRenderer.DEFAULT): AndroidLaunchEnv {
        val gameDir = dir("minecraft")
        val javaHome = dir("java")
        File(javaHome, "release").writeText("JAVA_VERSION=\"17.0.9\"")
        File(javaHome, "lib").mkdirs()
        File(javaHome, "lib/libjvm.so").writeText("x")
        val nativeDir = dir("natives")
        File(nativeDir, renderer.libraryName).writeText("x")
        val tmpDir = dir("tmp")
        return AndroidLaunchEnv(
            gameDir = gameDir,
            javaHome = javaHome,
            nativeDir = nativeDir,
            tmpDir = tmpDir,
            androidRelease = "14",
        )
    }

    private fun argsOf(env: AndroidLaunchEnv, renderer: AndroidRenderer = AndroidRenderer.DEFAULT): List<String> =
        AndroidLaunchArgs.of(env, renderer)

    private fun valueOf(args: List<String>, key: String): String? =
        args.firstOrNull { it.startsWith("-D" + key + "=") }?.substringAfter('=')

    @Test
    fun `十五个必备参数一条不少`() {
        val env = readyEnv()
        val args = argsOf(env)
        val expected = listOf(
            "java.home",
            "java.io.tmpdir",
            "jna.boot.library.path",
            "user.home",
            "os.name",
            "os.version",
            "org.lwjgl.vulkan.libname",
            "org.lwjgl.opengl.libname",
            "org.lwjgl.freetype.libname",
            "glfwstub.initEgl",
            "jdk.lang.Process.launchMechanism",
            "log4j2.formatMsgNoLookups",
            "fml.earlyprogresswindow",
            "loader.disable_forked_guis",
        )
        for (key in expected) {
            assertTrue(valueOf(args, key) != null, "缺了 -D" + key)
        }
        // 六个值型参数要指到真实路径上，写错了不会报错、只会在启动时找不到东西。
        assertEquals(env.javaHome.absolutePath, valueOf(args, "java.home"))
        assertEquals(env.tmpDir.absolutePath, valueOf(args, "java.io.tmpdir"))
        assertEquals(env.nativeDir.absolutePath, valueOf(args, "jna.boot.library.path"))
        assertEquals(env.gameDir.absolutePath, valueOf(args, "user.home"))
        assertEquals(AndroidLaunchArgs.OS_NAME, valueOf(args, "os.name"))
        assertEquals("Android-14", valueOf(args, "os.version"))
    }

    @Test
    fun `系统名必须伪装成 Linux`() {
        // 这一条是安卓能跑起来的前提：很多库见 Android 会走 android.* 分支。
        assertEquals("Linux", AndroidLaunchArgs.OS_NAME)
        assertEquals("Linux", valueOf(argsOf(readyEnv()), "os.name"))
    }

    @Test
    fun `换渲染后端只换 opengl 那一条`() {
        val env = readyEnv()
        val gl4es = argsOf(env, AndroidRenderer.GL4ES)
        val zink = argsOf(env, AndroidRenderer.ZINK)
        val ltw = argsOf(env, AndroidRenderer.LTW)
        assertEquals("libgl4es_114.so", valueOf(gl4es, "org.lwjgl.opengl.libname"))
        assertEquals("libOSMesa.so", valueOf(zink, "org.lwjgl.opengl.libname"))
        assertEquals("libltw.so", valueOf(ltw, "org.lwjgl.opengl.libname"))
        // 除了这一条，三份参数应该完全一样（顺序与内容都比）。
        assertEquals(
            gl4es.filterNot { it.contains("org.lwjgl.opengl.libname") },
            zink.filterNot { it.contains("org.lwjgl.opengl.libname") },
        )
        assertEquals(gl4es.size, zink.size)
    }

    @Test
    fun `三十二位机器上选不了 Zink`() {
        assertTrue(AndroidRenderer.GL4ES.supportedOn("armeabi-v7a"))
        assertFalse(AndroidRenderer.ZINK.supportedOn("armeabi-v7a"))
        assertFalse(AndroidRenderer.ZINK.supportedOn("x86"))
        assertTrue(AndroidRenderer.ZINK.supportedOn("arm64-v8a"))
        assertTrue(AndroidRenderer.ZINK.supportedOn("x86_64"))
        assertEquals(
            listOf(AndroidRenderer.GL4ES, AndroidRenderer.LTW),
            AndroidRenderer.supportedOn("armeabi-v7a"),
        )
    }

    @Test
    fun `认不出的后端 id 退回 GL4ES`() {
        assertEquals(AndroidRenderer.GL4ES, AndroidRenderer.of(""))
        assertEquals(AndroidRenderer.GL4ES, AndroidRenderer.of("vulkan"))
        assertEquals(AndroidRenderer.ZINK, AndroidRenderer.of("  ZINK "))
        assertEquals(AndroidRenderer.GL4ES, AndroidRenderer.DEFAULT)
    }

    @Test
    fun `没有 resolv 文件时不传那一条`() {
        val env = readyEnv()
        assertTrue(valueOf(argsOf(env), "ext.net.resolvPath") == null)
        val resolv = File(dir("net"), "resolv.conf")
        resolv.writeText("nameserver 8.8.8.8")
        assertEquals(
            resolv.absolutePath,
            valueOf(argsOf(env.copy(resolvConf = resolv)), "ext.net.resolvPath"),
        )
    }

    @Test
    fun `系统版本为空时只写 Android`() {
        val env = readyEnv().copy(androidRelease = "  ")
        assertEquals("Android", valueOf(argsOf(env), "os.version"))
    }

    @Test
    fun `开启 Forge 的 fork 方式是必须的`() {
        // 安卓没有 jspawnhelper，默认 POSIX_SPAWN 直接失败且日志里什么都没有。
        assertEquals("FORK", valueOf(argsOf(readyEnv()), "jdk.lang.Process.launchMechanism"))
    }

    @Test
    fun `内存参数会被丢掉并说清为什么`() {
        val result = AndroidLaunchArgs.sanitize(listOf("-Xmx4096M", "-Xms512M", "-XX:+UseG1GC"))
        assertEquals(listOf("-XX:+UseG1GC"), result.kept)
        assertEquals(2, result.dropped.size)
        assertEquals("-Xmx4096M", result.dropped[0].arg)
        assertTrue(result.dropped[0].reason.contains("最大内存"))
        assertTrue(result.dropped[1].reason.contains("最大内存"))
    }

    @Test
    fun `位数选择与关 JIT 也会被丢掉`() {
        val result = AndroidLaunchArgs.sanitize(listOf("-d64", "-Xint", "-Da=b"))
        assertEquals(listOf("-Da=b"), result.kept)
        assertEquals(2, result.dropped.size)
        assertTrue(result.dropped[0].reason.contains("32/64"))
        assertTrue(result.dropped[1].reason.contains("JIT"))
    }

    @Test
    fun `大页与处理器数也丢掉`() {
        val result = AndroidLaunchArgs.sanitize(
            listOf("-XX:+UseLargePages", "-XX:ActiveProcessorCount=4", "-Dfml.ignoreInvalidMinecraftCertificates=true"),
        )
        assertEquals(listOf("-Dfml.ignoreInvalidMinecraftCertificates=true"), result.kept)
        assertEquals(2, result.dropped.size)
        assertTrue(result.dropped.all { it.reason.isNotBlank() })
    }

    @Test
    fun `空串参数被跳过而不是当成被丢掉的`() {
        val result = AndroidLaunchArgs.sanitize(listOf("", "   ", "-Da=b"))
        assertEquals(listOf("-Da=b"), result.kept)
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun `环境齐了时候自检六条全过`() {
        val checkList = AndroidLaunchArgs.checks(readyEnv(), abi = "arm64-v8a")
        assertEquals(6, checkList.size)
        assertTrue(AndroidLaunchArgs.blockers(checkList).isEmpty())
        assertEquals(
            listOf("游戏目录", "Java 运行时", "图形后端", "渲染库文件", "临时目录", "原生库目录"),
            checkList.map { it.label },
        )
    }

    @Test
    fun `没装 Java 与解包断在一半的说法不一样`() {
        val env = readyEnv()
        val missing = AndroidLaunchArgs.blockers(AndroidLaunchArgs.checks(env.copy(javaHome = File(root, "nope"))))
        assertEquals(1, missing.size)
        assertTrue(missing[0].detail.startsWith("没装："))

        // 目录在、release 没了：这是解包断在一半的典型样子，不能报「没装」。
        val half = dir("halfjava")
        File(half, "bin").mkdirs()
        val broken = AndroidLaunchArgs.blockers(AndroidLaunchArgs.checks(env.copy(javaHome = half)))
        assertEquals(1, broken.size)
        assertTrue(broken[0].detail.contains("release"))
    }

    @Test
    fun `缺 libjvm 的运行时也被挡下`() {
        val env = readyEnv()
        File(env.javaHome, "lib/libjvm.so").delete()
        val blocked = AndroidLaunchArgs.blockers(AndroidLaunchArgs.checks(env))
        assertEquals(1, blocked.size)
        assertEquals("Java 运行时", blocked[0].label)
        assertTrue(blocked[0].detail.contains("libjvm.so"))
    }

    @Test
    fun `渲染库还没解出来时会说是哪个文件`() {
        val env = readyEnv(AndroidRenderer.ZINK)
        File(env.nativeDir, AndroidRenderer.ZINK.libraryName).delete()
        val blocked = AndroidLaunchArgs.blockers(AndroidLaunchArgs.checks(env, AndroidRenderer.ZINK))
        assertEquals(1, blocked.size)
        assertEquals("渲染库文件", blocked[0].label)
        assertTrue(blocked[0].detail.contains("libOSMesa.so"))
    }

    @Test
    fun `三十二位机器选 Zink 时自检会拦下来`() {
        val env = readyEnv(AndroidRenderer.ZINK)
        val blocked = AndroidLaunchArgs.blockers(
            AndroidLaunchArgs.checks(env, AndroidRenderer.ZINK, abi = "armeabi-v7a"),
        )
        assertEquals(1, blocked.size)
        assertEquals("图形后端", blocked[0].label)
        assertTrue(blocked[0].detail.contains("换 GL4ES"))
    }

    @Test
    fun `安卓的规则名是 linux`() {
        // 这是整个安卓兼容层的前提：version json 的 rules 里从来只有 windows/osx/linux，
        // 拿 android 去比会一份 natives 都挑不出来。
        assertEquals("android", OsFamily.ANDROID.id)
        assertEquals("linux", OsFamily.ANDROID.ruleName)
        assertEquals(OsFamily.ANDROID, AndroidLaunchArgs.osFamily())
        assertEquals("linux", OsFamily.LINUX.ruleName)
        assertEquals("windows", OsFamily.WINDOWS.ruleName)
    }
}
