package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticBundleTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("platypus-diag").toFile()

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    private fun readEntries(zip: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipFile(zip).use { file ->
            file.entries().asSequence().forEach { entry ->
                out[entry.name] = file.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun `脱敏副本清空应用身份而不动别的设置`() {
        val original = LauncherSettings(
            offlineUsername = "Steve",
            microsoftClientId = "00000000-1111-2222-3333-444444444444",
            curseForgeApiKey = "secret-key",
        )
        val safe = original.redacted()
        assertEquals("", safe.microsoftClientId, "微软应用 ID 必须清空，它是别人的注册身份")
        assertEquals("", safe.curseForgeApiKey, "CurseForge key 是私人的")
        assertEquals("Steve", safe.offlineUsername, "其余设置要留着，否则报告里看不出用的什么配置")
    }

    @Test
    fun `文件名带时间戳且是 zip`() {
        val name = DiagnosticBundle.suggestedName(0L)
        assertTrue(name.startsWith("platypus-diag-"), name)
        assertTrue(name.endsWith(".zip"), name)
    }

    @Test
    fun `打出来的包里有诊断文本与脱敏设置`() {
        val gameRoot = File(temp, "game").apply { mkdirs() }
        val target = File(temp, "out.zip")
        val facts = DiagnosticFacts(
            gameRoot = gameRoot,
            settings = LauncherSettings(offlineUsername = "Steve", microsoftClientId = "abc", curseForgeApiKey = "k"),
            diagnosis = listOf("启动器：鸭嘴兽", "找到的 Java：一个都没有"),
            javaRuntimes = listOf(JavaRuntime("/opt/java", 17, "Eclipse", "x86_64", 64, false)),
        )
        val result = DiagnosticBundle.write(target, facts)
        val entries = readEntries(target)

        assertTrue(result.entries >= 4, "至少要写进 README、system、diagnosis、settings 四份，实际 " + result.entries)
        assertEquals(target.length(), result.bytes)
        assertTrue("README.txt" in entries)
        assertTrue(entries.getValue("diagnosis.txt").contains("鸭嘴兽"))
        assertTrue(entries.getValue("system.txt").contains("/opt/java"), "探到的 Java 要进包，否则没法判断是不是版本不对")
        val settingsText = entries.getValue("settings-redacted.json")
        assertFalse(settingsText.contains("abc"), "应用 ID 不能出现在包里")
        assertFalse(settingsText.contains("secret"), "CurseForge key 不能出现在包里")
        assertTrue(settingsText.contains("Steve"))
    }

    @Test
    fun `不收账号文件，令牌绝不进包`() {
        val gameRoot = File(temp, "game").apply { mkdirs() }
        val indexDir = File(gameRoot, "platypus").apply { mkdirs() }
        File(indexDir, "accounts.json").writeText(
            "[{\"name\":\"Steve\",\"refreshToken\":\"TOKEN-SHOULD-NOT-LEAK\"}]",
            Charsets.UTF_8,
        )
        val target = File(temp, "out.zip")
        DiagnosticBundle.write(target, DiagnosticFacts(gameRoot = gameRoot, settings = LauncherSettings()))
        val entries = readEntries(target)
        assertFalse("accounts.json" in entries.keys.map { it.substringAfterLast("/") }, "原始账号文件不能进包")
        assertTrue(entries.values.none { it.contains("TOKEN-SHOULD-NOT-LEAK") }, "任何一个条目里都不该出现刷新令牌")
    }

    @Test
    fun `实例索引原样收进来`() {
        val gameRoot = File(temp, "game").apply { mkdirs() }
        val indexDir = File(gameRoot, "platypus").apply { mkdirs() }
        File(indexDir, "instances.json").writeText("[{\"name\":\"1.20.1\"}]", Charsets.UTF_8)
        val target = File(temp, "out.zip")
        DiagnosticBundle.write(target, DiagnosticFacts(gameRoot = gameRoot, settings = LauncherSettings()))
        assertTrue(readEntries(target).getValue("instances.json").contains("1.20.1"))
    }

    @Test
    fun `收实例日志时只取最新四份`() {
        val gameRoot = File(temp, "game").apply { mkdirs() }
        val instanceDir = File(temp, "instance").apply { mkdirs() }
        val logs = File(instanceDir, "logs").apply { mkdirs() }
        repeat(6) { i ->
            val file = File(logs, "run-" + i + ".log")
            file.writeText("第 " + i + " 份", Charsets.UTF_8)
            file.setLastModified(1_000_000L + i * 1000L)
        }
        val target = File(temp, "out.zip")
        val instance = Instance(id = "a", name = "1.20.1", gameVersion = "1.20.1", directory = instanceDir.absolutePath)
        DiagnosticBundle.write(target, DiagnosticFacts(gameRoot = gameRoot, settings = LauncherSettings(), instance = instance))
        val names = readEntries(target).keys.filter { it.startsWith("logs/") }
        assertEquals(4, names.size, "日志最多四份，多了包会很大：" + names)
        assertTrue(names.any { it.endsWith("run-5.log") }, "应该取最新的那几份")
    }

    @Test
    fun `只收最近一份崩溃报告`() {
        val gameRoot = File(temp, "game").apply { mkdirs() }
        val instanceDir = File(temp, "instance").apply { mkdirs() }
        val crashes = File(instanceDir, "crash-reports").apply { mkdirs() }
        File(crashes, "crash-old.txt").apply { writeText("旧", Charsets.UTF_8); setLastModified(1_000L) }
        File(crashes, "crash-new.txt").apply { writeText("新", Charsets.UTF_8); setLastModified(9_000L) }
        val target = File(temp, "out.zip")
        val instance = Instance(id = "a", name = "1.20.1", gameVersion = "1.20.1", directory = instanceDir.absolutePath)
        DiagnosticBundle.write(target, DiagnosticFacts(gameRoot = gameRoot, settings = LauncherSettings(), instance = instance))
        val keys = readEntries(target).keys.filter { it.startsWith("crash-reports/") }
        assertEquals(listOf("crash-reports/crash-new.txt"), keys)
    }

    @Test
    fun `路径里的用户名换成占位符`() {
        val gameRoot = File(temp, "game").apply { mkdirs() }
        val home = System.getProperty("user.home").orEmpty()
        val target = File(temp, "out.zip")
        DiagnosticBundle.write(
            target,
            DiagnosticFacts(gameRoot = gameRoot, settings = LauncherSettings(), diagnosis = listOf("配置在 " + home + "/x")),
        )
        val text = readEntries(target).getValue("diagnosis.txt")
        assertTrue(text.contains("<home>"), text)
        assertFalse(text.contains(home), "本机用户名不该出现在要发出去的包里")
    }

    @Test
    fun `超长日志只留末尾一段`() {
        val gameRoot = File(temp, "game").apply { mkdirs() }
        val instanceDir = File(temp, "instance").apply { mkdirs() }
        val logs = File(instanceDir, "logs").apply { mkdirs() }
        val big = File(logs, "big.log")
        big.writeText("开头标记" + "x".repeat(700 * 1024) + "结尾标记", Charsets.UTF_8)
        val target = File(temp, "out.zip")
        val instance = Instance(id = "a", name = "1.20.1", gameVersion = "1.20.1", directory = instanceDir.absolutePath)
        DiagnosticBundle.write(target, DiagnosticFacts(gameRoot = gameRoot, settings = LauncherSettings(), instance = instance))
        val text = readEntries(target).getValue("logs/0-big.log")
        assertTrue(text.contains("已省略"), "截断要写明，否则看的人以为日志就这么短")
        assertTrue(text.contains("结尾标记"), "末尾那截才是崩溃现场")
    }
}
