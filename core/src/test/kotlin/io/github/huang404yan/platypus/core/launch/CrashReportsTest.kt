package io.github.huang404yan.platypus.core.launch

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 崩溃报告读取与模组类归因：都只读盘/读字符串，不起进程、不联网。 */
class CrashReportsTest {

    private fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `没有 crash-reports 目录时返回 null`() {
        val dir = tempDir("platypus-nocrash")
        assertNull(CrashReports.latest(dir))
        assertNull(CrashReports.read(dir))
    }

    @Test
    fun `取最新的一份并按修改时间而不是文件名`() {
        val dir = tempDir("platypus-crashpick")
        val reports = File(dir, "crash-reports")
        // 名字故意反着来：按文件名排会选中旧的，按 mtime 排才选新的。
        val older = File(reports, "crash-2026-11-09_23.59.59-client.txt")
        val newer = File(reports, "crash-2026-11-08_00.00.01-client.txt")
        write(older, "Description: old\n")
        write(newer, "Description: new\n")
        newer.setLastModified(2_000_000_000_000L)
        older.setLastModified(1_000_000_000_000L)
        // 非 .txt 混进去，不该被选中。
        write(File(reports, "notes.md"), "Description: nope\n")

        assertEquals(newer.name, CrashReports.latest(dir)?.name)
        assertEquals("new", CrashReports.read(dir)?.description)
    }

    @Test
    fun `能挑出 Description 与第一个异常`() {
        val dir = tempDir("platypus-crashparse")
        // 内容是 1.20.1 真实崩溃报告的前半截形状。
        write(
            File(dir, "crash-reports/crash-2026-11-10_12.00.00-client.txt"),
            """
            ---- Minecraft Crash Report ----
            // I bet Cylons wouldn't have this problem.

            Time: 2026-11-10 12:00:00
            Description: Rendering screen

            java.lang.NullPointerException: Cannot invoke "net.minecraft.client.gui.screens.Screen.render(...)"
            	at net.minecraft.client.Minecraft.render(Minecraft.java:1000)
            	at net.minecraft.client.main.Main.main(Main.java:200)

            A detailed walkthrough of the error, its code path and all known details is as follows:
            """.trimIndent(),
        )
        val report = assertNotNull(CrashReports.read(dir))
        assertEquals("Rendering screen", report.description)
        assertTrue(report.exception.startsWith("java.lang.NullPointerException"))
        assertTrue(report.bytes > 0)
        assertEquals("crash-2026-11-10_12.00.00-client.txt", report.fileName)
    }

    @Test
    fun `没有 Description 行时不编造内容`() {
        assertEquals("", CrashReports.pickDescription("just any text\n"))
        assertEquals("", CrashReports.pickException("just any text\n\tat foo(Bar.java:1)"))
        // Caused by 也算第一个异常：有些报告外层只有一句 FormattedException，信息全在 Caused by。
        assertTrue(
            CrashReports.pickException("Caused by: java.lang.IllegalStateException: boom").startsWith("Caused by: java.lang.IllegalStateException"),
        )
    }

    // ── 归因：模组冲突 / 模组损坏 ───────────────────────────────────────

    @Test
    fun `认得出 Fabric 的不兼容模组与缺前置`() {
        // 原文来自真机实测（用户贴的 AppleSkin 缺 fabric-api 那份日志）。
        val lines = listOf(
            "[12:00:00] [main/INFO]: Loading Minecraft 1.20.1 with Fabric Loader 0.20.1",
            "[12:00:01] [main/ERROR]: Incompatible mods found!",
            "net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!",
            "\t- Mod 'AppleSkin' (appleskin) 2.5.2+mc1.20.1 requires any version of fabric-api, which is missing!",
        )
        val diagnosis = assertNotNull(LaunchDiagnosis.diagnose(lines, exitCode = 1))
        assertTrue(diagnosis.contains("模组冲突或缺依赖"), diagnosis)
    }

    @Test
    fun `认得出 NeoForge 的依赖缺失文案`() {
        val lines = listOf(
            "[12:00:00] [main/ERROR]: Missing or unsupported mandatory dependencies:",
            "\tMod ID: 'create', Requested by: 'jei', Expected range: '[1.0,)', Actual version: '[MISSING]'",
        )
        val diagnosis = assertNotNull(LaunchDiagnosis.diagnose(lines, exitCode = 1))
        assertTrue(diagnosis.contains("模组冲突或缺依赖"), diagnosis)
    }

    @Test
    fun `模组 jar 损坏要能指出来`() {
        val lines = listOf(
            "[12:00:00] [main/ERROR]: Failed to load mod file mods/broken.jar",
            "java.util.zip.ZipException: zip END header not found",
            "\tat java.base/java.util.zip.ZipFile\$Source.findEND(ZipFile.java:1470)",
        )
        val diagnosis = assertNotNull(LaunchDiagnosis.diagnose(lines, exitCode = 1))
        assertTrue(diagnosis.contains("模组文件损坏"), diagnosis)
    }

    @Test
    fun `资源包 zip 坏了不该归成模组损坏`() {
        // 同一个 ZipException 也可能来自资源包：没有 mods 字样时不能乱扣帽子。
        val lines = listOf(
            "java.util.zip.ZipException: zip END header not found",
            "\tat net.minecraft.server.packs.FilePackResources.<init>(FilePackResources.java:40)",
        )
        val diagnosis = LaunchDiagnosis.diagnose(lines, exitCode = 1).orEmpty()
        assertTrue(!diagnosis.contains("模组文件损坏"), diagnosis)
        assertTrue(diagnosis.contains("退出码 1"), diagnosis)
    }

    @Test
    fun `模组冲突规则优先于后面的连带异常`() {
        // 真实日志里缺前置之后会跟一串 NoClassDefFoundError / NoSuchMethodError，
        // 这些是连带噪声，不能被当成「加载器库不完整」。
        val lines = listOf(
            "[12:00:01] [main/ERROR]: Incompatible mods found!",
            "\t- Mod 'AppleSkin' (appleskin) 2.5.2+mc1.20.1 requires any version of fabric-api, which is missing!",
            "java.lang.NoClassDefFoundError: cpw/mods/modlauncher/Launcher",
            "GradleWorkerMain",
        )
        val diagnosis = assertNotNull(LaunchDiagnosis.diagnose(lines, exitCode = 1))
        assertTrue(diagnosis.contains("模组冲突或缺依赖"), diagnosis)
    }

    @Test
    fun `进过主菜单的模组报错仍是噪声`() {
        val lines = listOf(
            "[12:00:00] [Render thread/INFO]: Setting user: Dev",
            "[12:00:10] [Render thread/WARN]: Incompatible mods found",
        )
        // 模组规则不设 skipWhenReachedMenu：进了主菜单再崩，模组冲突依然是有效归因，
        // 只有「离线 401」那种必然噪声才该被压掉。
        val diagnosis = LaunchDiagnosis.diagnose(lines, exitCode = 1).orEmpty()
        assertTrue(diagnosis.contains("模组冲突或缺依赖"), diagnosis)
    }
}
