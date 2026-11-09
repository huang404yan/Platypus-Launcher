package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.version.parseVersionJson
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** natives 解压、继承链合并、退出归因三块的纯逻辑测试：不起进程、不联网。 */
class LaunchPipelineTest {

    private fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()

    /** 造一个只含若干条目的 zip，用来验证解压规则。 */
    private fun zip(file: File, entries: Map<String, String>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    // ── NativesExtractor ────────────────────────────────────────────────

    @Test
    fun `解压 natives 时会跳过 META-INF 与目录条目`() {
        val root = tempDir("platypus-natives-test")
        try {
            val jar = File(root, "lwjgl-natives-windows.jar")
            zip(
                jar,
                mapOf(
                    "lwjgl.dll" to "dll",
                    "glfw.dll" to "glfw",
                    "META-INF/MANIFEST.MF" to "manifest",
                    "META-INF/SIG.RSA" to "sig",
                ),
            )
            val target = File(root, "natives")
            val result = NativesExtractor.extract(listOf(NativeArchive(jar, emptyList())), target)
            assertEquals(2, result.extracted)
            assertTrue(File(target, "lwjgl.dll").isFile)
            assertTrue(File(target, "glfw.dll").isFile)
            assertFalse(File(target, "META-INF").exists())
            assertTrue(result.warnings.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `每个库自己的 extract_exclude 也会生效`() {
        val root = tempDir("platypus-natives-exclude-test")
        try {
            val jar = File(root, "custom.jar")
            zip(jar, mapOf("keep.dll" to "keep", "assets/icon.png" to "png"))
            val target = File(root, "natives")
            val result = NativesExtractor.extract(listOf(NativeArchive(jar, listOf("assets/"))), target)
            assertEquals(1, result.extracted)
            assertTrue(File(target, "keep.dll").isFile)
            assertFalse(File(target, "assets").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `缺失的 natives jar 只记告警不抛异常`() {
        val root = tempDir("platypus-natives-missing-test")
        try {
            val result = NativesExtractor.extract(
                listOf(NativeArchive(File(root, "nope.jar"), emptyList())),
                File(root, "natives"),
            )
            assertEquals(0, result.extracted)
            assertEquals(0, result.archives)
            assertEquals(1, result.warnings.size)
            assertTrue(result.warnings[0].contains("nope.jar"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `条目名归一化与越界条目判断`() {
        assertEquals("a\\b".replace('\\', '/'), NativesExtractor.normalizeEntryName("/a\\b"))
        assertEquals("a/b.dll", NativesExtractor.normalizeEntryName("./a/b.dll"))
        assertTrue(NativesExtractor.shouldSkip("META-INF/x", NativesExtractor.DEFAULT_EXCLUDE))
        assertFalse(NativesExtractor.shouldSkip("lwjgl.dll", NativesExtractor.DEFAULT_EXCLUDE))
        assertTrue(NativesExtractor.shouldSkip("dir/", emptyList()))
    }

    // ── VersionResolver（继承链合并）─────────────────────────────────────

    private fun json(text: String) = parseVersionJson(text.trimIndent())

    @Test
    fun `合并继承链时父版本补上主类与库`() {
        val parent = json(
            """
            {
              "id": "1.20.1",
              "mainClass": "net.minecraft.client.main.Main",
              "assets": "5",
              "libraries": [{ "name": "com.mojang:authlib:5.0.58" }],
              "arguments": {
                "jvm": ["-Djava.library.path=natives"],
                "game": ["--username", "Steve"]
              }
            }
            """,
        )
        val child = json(
            """
            {
              "id": "1.20.1-forge-47.4.28",
              "inheritsFrom": "1.20.1",
              "libraries": [{ "name": "net.minecraftforge:forge:47.4.28" }],
              "arguments": { "game": ["--launchTarget", "forgeclient"] }
            }
            """,
        )
        val merged = VersionResolver.merge(child, parent)
        assertEquals("net.minecraft.client.main.Main", merged.mainClass)
        assertEquals("5", merged.assets)
        assertNull(merged.inheritsFrom)
        assertEquals(2, merged.libraries.size)
        assertEquals("com.mojang:authlib:5.0.58", merged.libraries[0].name)
        assertEquals("net.minecraftforge:forge:47.4.28", merged.libraries[1].name)
        val gameArgs = merged.arguments?.game.orEmpty()
        // 父版本 2 个 + 子版本 2 个，父在前。
        assertEquals(4, gameArgs.size)
        assertTrue(gameArgs[0].toString().contains("username"))
        assertTrue(gameArgs[3].toString().contains("forgeclient"))
    }

    @Test
    fun `resolve 按由近到远的祖先顺序折叠`() {
        val grand = json("""{ "id": "1.20.1", "mainClass": "net.minecraft.client.main.Main", "assets": "5" }""")
        val parent = json("""{ "id": "mid", "inheritsFrom": "1.20.1", "assets": "6" }""")
        val child = json("""{ "id": "leaf", "inheritsFrom": "mid", "mainClass": "custom.Main" }""")
        val merged = VersionResolver.resolve(child, listOf(parent, grand))
        assertEquals("custom.Main", merged.mainClass)
        assertEquals("6", merged.assets)
    }

    // ── LaunchDiagnosis ─────────────────────────────────────────────────

    @Test
    fun `能从日志里认出 Java 版本太低`() {
        val lines = listOf(
            "Starting Minecraft",
            "Exception in thread \"main\" java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version",
        )
        val diagnosis = LaunchDiagnosis.diagnose(lines, exitCode = 1)
        assertNotNull(diagnosis)
        assertTrue(diagnosis.contains("Java 版本太低"))
    }

    @Test
    fun `natives 缺失与内存不足各有各的说法`() {
        val natives = LaunchDiagnosis.diagnose(
            listOf("java.lang.UnsatisfiedLinkError: no lwjgl in java.library.path"),
            exitCode = 1,
        )
        assertTrue(natives.orEmpty().contains("原生库"))
        val oom = LaunchDiagnosis.diagnose(listOf("java.lang.OutOfMemoryError: Java heap space"), exitCode = 1)
        assertTrue(oom.orEmpty().contains("内存不足"))
    }

    @Test
    fun `正常退出且没有已知原因时不编造解释`() {
        assertNull(LaunchDiagnosis.diagnose(listOf("Stopping!"), exitCode = 0))
        val fallback = LaunchDiagnosis.diagnose(listOf("Stopping!"), exitCode = 3)
        assertTrue(fallback.orEmpty().contains("3"))
    }

    @Test
    fun `归因只看日志尾部`() {
        val lines = listOf("java.lang.OutOfMemoryError: Java heap space") +
            (1..300).map { "later line " + it }
        // 早期告警已经滚动出尾部窗口，不该再被当成原因。
        assertNull(LaunchDiagnosis.diagnose(lines, exitCode = 0, tailLines = 50))
    }

    @Test
    fun `离线 401 堆栈在进过主菜单后不算失败原因`() {
        val lines = listOf(
            "[10:35:13] [Render thread/ERROR]: Failed to verify authentication",
            "com.mojang.authlib.exceptions.InvalidCredentialsException: Status: 401",
            "\tat com.mojang.authlib.yggdrasil.YggdrasilUserApiService.fetchProperties(YggdrasilUserApiService.java:156)",
            "[10:35:13] [Render thread/INFO]: Setting user: Dev",
            "[10:35:13] [Render thread/INFO]: Backend library: LWJGL version 3.3.1 build 7",
            "[10:35:16] [Render thread/INFO]: Sound engine started",
        )
        // 进过主菜单：401 堆栈只是离线模式的噪声，退出原因应归到「玩过之后正常退出」。
        val afterMenu = LaunchDiagnosis.diagnose(lines, exitCode = 1)
        assertNotNull(afterMenu)
        assertFalse(afterMenu.contains("登录凭据无效"))
        assertTrue(afterMenu.contains("进入主菜单后退出"))

        // 没进主菜单就退出：同样的堆栈就是真正的原因。
        val failed = lines.take(3)
        assertTrue(
            LaunchDiagnosis.diagnose(failed, exitCode = 1).orEmpty().contains("登录凭据无效"),
        )
    }

    @Test
    fun `进过主菜单的日志能被认出来`() {
        assertTrue(LaunchDiagnosis.reachedMenu(listOf("[Render thread/INFO]: Setting user: Steve")))
        assertFalse(LaunchDiagnosis.reachedMenu(listOf("[Render thread/INFO]: Backend library: LWJGL version 3.3.1")))
    }
}
