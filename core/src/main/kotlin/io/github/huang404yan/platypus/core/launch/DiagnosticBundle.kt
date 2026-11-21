package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * 打一个「诊断包」：把排查时要看的文件收进一个 zip，用户直接发过来就行。
 *
 * 为什么不是让用户自己找文件：崩溃报告在 `crash-reports/`、日志在 `logs/`、设置在同级的
 * `platypus/` 里，三处分散；而「哪个版本的目录」这件事只有启动器自己知道。让用户按说明
 * 一个个找过去，一半人找到第三个就放弃了。
 *
 * **包里不放令牌。** [LauncherSettings] 里有微软应用 ID 与 CurseForge key、`accounts.json`
 * 里有 refresh_token，这些一个都不能出去 —— 报告贴到公开 issue 上就等于泄露。
 * 所以设置是重新序列化的脱敏副本，账号只写「名字 + 类型」，原始 accounts.json 根本不读。
 *
 * 路径里的用户名也换掉（`<home>`）：路径泄漏的最常见后果是被认出是谁，
 * 而排查问题时这一层信息完全没用。
 */
object DiagnosticBundle {

    /** zip 里最多放这么多份日志，按修改时间从新到旧取。 */
    private const val MAX_LOG_FILES = 4

    /** 单个文本文件最多读这么多字节（从末尾读，日志与崩溃报告的重点都在尾部）。 */
    private const val MAX_TEXT_BYTES = 512L * 1024L

    /** 打出来文件名里的时间戳格式，与日志导出一致。 */
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** 建议的文件名：`platypus-diag-20261121-143000.zip`。 */
    fun suggestedName(now: Long = System.currentTimeMillis()): String {
        val time = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), java.time.ZoneId.systemDefault())
        return "platypus-diag-" + time.format(STAMP) + ".zip"
    }

    /**
     * 打包。目标文件已存在就覆盖。
     *
     * @param target 写到哪
     * @param facts 要收进来的东西，见 [DiagnosticFacts]
     * @return 实际写了多少个条目、多少字节
     * @throws java.io.IOException 写不了（目录不存在、没权限）时抛给调用方去提示
     */
    fun write(target: File, facts: DiagnosticFacts): DiagnosticBundleResult {
        val parent = target.absoluteFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw java.io.IOException("建不了目录：" + parent.absolutePath)
        }
        var entries = 0
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            fun putText(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(redact(text, facts.home).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                entries++
            }

            fun putFile(name: String, file: File) {
                val text = readTail(file) ?: return
                zip.putNextEntry(ZipEntry(name))
                zip.write(redact(text, facts.home).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                entries++
            }

            putText("README.txt", readme())
            putText("system.txt", systemText(facts))
            putText("diagnosis.txt", facts.diagnosis.joinToString(System.lineSeparator()))
            putText("settings-redacted.json", json.encodeToString(LauncherSettings.serializer(), facts.settings.redacted()))
            if (facts.accountLines.isNotEmpty()) {
                putText("accounts.txt", facts.accountLines.joinToString(System.lineSeparator()))
            }

            // 实例索引原样收：里面只有目录与名称，没有令牌，收原件才能看出用户手改过什么。
            val index = JsonInstanceRepository.defaultIndexFile(facts.gameRoot)
            if (index.isFile) putFile("instances.json", index)

            // 内存里那一份当前会话日志（界面持有着），比磁盘上的更新，先放进来。
            if (facts.sessionLogs.isNotEmpty()) {
                putText("logs/in-session.log", facts.sessionLogs.joinToString(System.lineSeparator()))
            }

            val instance = facts.instance
            if (instance != null) {
                val dir = File(instance.directory)
                // 版本 json：实例目录里以目录名命名的那一份，启动参数、库列表全在里面。
                val versionJson = File(dir, dir.name + ".json")
                if (versionJson.isFile) putFile("instance/" + versionJson.name, versionJson)

                val logs = File(dir, "logs")
                    .listFiles { f -> f.isFile && f.name.endsWith(".log", ignoreCase = true) }
                    .orEmpty()
                    .sortedByDescending { it.lastModified() }
                    .take(MAX_LOG_FILES)
                logs.forEachIndexed { i, file -> putFile("logs/" + i + "-" + file.name, file) }

                val crash = CrashReports.latest(dir)
                if (crash != null) putFile("crash-reports/" + crash.name, crash)
            }
        }
        return DiagnosticBundleResult(target, entries, target.length())
    }

    private fun readme(): String = buildString {
        appendLine("鸭嘴兽启动器诊断包")
        appendLine("生成时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        appendLine("启动器版本：" + Brand.version)
        appendLine()
        appendLine("里面有什么：")
        appendLine("  diagnosis.txt            —— 界面「诊断」页那段文字，最省事的是先看这个")
        appendLine("  system.txt               —— 系统、Java、游戏目录")
        appendLine("  settings-redacted.json   —— 设置，微软应用 ID 与 CurseForge key 已清空")
        appendLine("  instances.json           —— 实例登记（只有目录与名称）")
        appendLine("  logs/                    —— 最近几份启动日志")
        appendLine("  crash-reports/           —— 最近一份崩溃报告（有的话）")
        appendLine("  instance/                —— 那个实例的版本 json")
        appendLine()
        appendLine("已经删掉的东西：账号令牌、微软应用 ID、CurseForge key、路径里的用户名（换成了 <home>）。")
        appendLine("发出去之前自己扫一眼还是值得的：目录名有时会带别人的昵称。")
    }

    private fun systemText(facts: DiagnosticFacts): String = buildString {
        appendLine("启动器：" + Brand.version + (if (Brand.isPreview) "（孵化版）" else ""))
        appendLine("系统：" + System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        appendLine("系统版本：" + System.getProperty("os.version"))
        appendLine("自带的 Java：" + System.getProperty("java.version") + " · " + System.getProperty("java.vendor"))
        appendLine("处理器核数：" + Runtime.getRuntime().availableProcessors())
        appendLine("可用内存（JVM 视角）：" + Runtime.getRuntime().maxMemory() / 1024L / 1024L + " MB")
        appendLine("游戏目录：" + facts.gameRoot.absolutePath)
        appendLine("实例：" + (facts.instance?.let { it.name + " · " + it.directory } ?: "没有选中实例"))
        appendLine()
        appendLine("探到的 Java：")
        if (facts.javaRuntimes.isEmpty()) {
            appendLine("  一个都没有")
        } else {
            facts.javaRuntimes.forEach { runtime ->
                appendLine(
                    "  " + runtime.majorVersion + " · " + runtime.path +
                        (runtime.vendor?.let { " · " + it } ?: "") +
                        (if (runtime.managed) " · 启动器托管" else ""),
                )
            }
        }
    }

    /** 从末尾读一段，够排查用；整份几十 MB 的日志塞进包里没有意义。读不出来返回 null。 */
    private fun readTail(file: File): String? = runCatching {
        val length = file.length()
        if (length <= MAX_TEXT_BYTES) {
            file.readText(Charsets.UTF_8)
        } else {
            val start = length - MAX_TEXT_BYTES
            val text = java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(MAX_TEXT_BYTES.toInt())
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
            "……（前面 " + (start / 1024) + " KB 已省略）" + System.lineSeparator() + text
        }
    }.getOrNull()

    /** 把本机用户目录换成 `<home>`，别让路径说出这是谁。两种分隔符都换一遍。 */
    private fun redact(text: String, home: String): String {
        if (home.isBlank()) return text
        return text
            .replace(home.replace(File.separatorChar, '/'), "<home>")
            .replace(home, "<home>")
    }
}

/**
 * 打一个诊断包要用到的事实。
 *
 * 全部由界面侧收集后传进来，不让这个包自己去读设置 / 探测 Java：
 * 界面已经把那些东西读好了，这里再读一遍只会在两边不一致时更难查。
 */
data class DiagnosticFacts(
    val gameRoot: File,
    val settings: LauncherSettings,
    val instance: Instance? = null,
    val javaRuntimes: List<JavaRuntime> = emptyList(),
    /** 账号摘要，形如「Steve（微软）」。**只写名字与类型**，不要塞令牌。 */
    val accountLines: List<String> = emptyList(),
    /** 界面「诊断」页那段能直接贴走的文字，原样进包。 */
    val diagnosis: List<String> = emptyList(),
    /** 当前会话还没落盘的日志（界面内存里那一份），有就放进包。 */
    val sessionLogs: List<String> = emptyList(),
) {
    /** 用户目录，用来把路径里的用户名换掉。 */
    val home: String get() = System.getProperty("user.home").orEmpty()
}

/** 打包结果。[entries] 是写进去的条目数，界面用它说一句「收了 N 个文件」。 */
data class DiagnosticBundleResult(val file: File, val entries: Int, val bytes: Long)

/**
 * 脱敏副本：微软应用 ID 与 CurseForge key 清空。
 *
 * 单独抽出来是为了能被单测覆盖 —— 这两个字段是「漏一个就等于把别人的应用身份
 * 发给全世界」，必须有一条断言钉住。
 */
internal fun LauncherSettings.redacted(): LauncherSettings = copy(
    microsoftClientId = "",
    curseForgeApiKey = "",
)
