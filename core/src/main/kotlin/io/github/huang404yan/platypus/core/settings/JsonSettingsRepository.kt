package io.github.huang404yan.platypus.core.settings

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * 以单个 JSON 文件保存设置，写法和 [io.github.huang404yan.platypus.core.instance.JsonInstanceRepository] 一致：
 * 临时文件 + 原子替换，读失败一律退回默认值而不抛。
 *
 * 与实例索引放在同一层目录（<gameRoot>/platypus/），用户想手工改或整个带走都方便。
 *
 * **写盘时护住用户自己填的那几格。** 应用 ID、服务地址、CurseForge Key 三样，
 * 程序自己永远变不出新值，只有用户手动填得出来。而写设置的地方不止一个：
 * 配置页点保存、账号页切账号、换配色、换界面模式、换下载源……任何一处拿着一份
 * 不完整的设置写一次盘，这三格就会永久变成空串，用户得重新去申请、重新找地址。
 * 所以 [save] 落盘前先读一眼盘上那份：传进来的为空、盘上有值，就留盘上的。
 * 用户真要清空时传 keepUserFilled = false（界面据此区分，见 [SettingsRepository.save]）。
 *
 * 另外两件配套的事：盘上那份解析不出来时，先把损坏原文另存一份 .broken-<时间戳>
 * 再写新的（否则用户手改坏一个逗号，重启一次设置就全没了）；每次真正改变内容的
 * 覆盖写之前把上一版留成 .bak，给「被别的调用点冲掉了」留一条回退路。
 */
class JsonSettingsRepository(private val file: File) : SettingsRepository {

    private val mutex = Mutex()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** 设置文件路径（界面上用来告诉用户「存在哪」）。 */
    val path: String get() = file.absolutePath

    override suspend fun load(): LauncherSettings = mutex.withLock { read() }

    /**
     * 写设置，默认护住用户手填的凭据三格。
     *
     * 只走这一个入口：其余地方即便误用一份新建的 LauncherSettings 来写盘，
     * 也不会把应用 ID 或服务地址冲成空串。
     */
    override suspend fun save(settings: LauncherSettings): LauncherSettings =
        save(settings, keepUserFilled = true)

    override suspend fun save(settings: LauncherSettings, keepUserFilled: Boolean): LauncherSettings =
        mutex.withLock {
            val incoming = LauncherSettings.normalize(settings)
            val stored = readStored()
            val merged = when {
                !keepUserFilled -> incoming
                stored.settings != null -> incoming.keepingUserFilled(stored.settings)
                stored.damagedText != null -> incoming.keepingUserFilled(salvageCredentials(stored.damagedText))
                else -> incoming
            }
            val finalSettings = merged.copy(updatedAt = System.currentTimeMillis())
            // 损坏的那份先留档：用户手改坏一个逗号，不该连原来的内容一起没了。
            stored.damagedText?.let { keepDamaged(it) }
            // 内容真的变了才留 .bak。updatedAt 每次都是新值，比较前先把它抹掉，
            // 否则「点一下换配色」这种重复落盘也会一直刷同一份备份。
            val unchanged = stored.settings
                ?.let { it.copy(updatedAt = 0L) == finalSettings.copy(updatedAt = 0L) } == true
            write(finalSettings, keepPrevious = !unchanged)
            finalSettings
        }

    private fun read(): LauncherSettings = readStored().settings ?: LauncherSettings()

    /**
     * 读盘上那一份。
     *
     * 三种结果分开记，是因为调用方的处置不同：解析成功要拿来保底，解析失败要留档，
     * 文件不存在则什么都不用做 —— 原来这三者都退化成「返回默认值」，于是「文件坏了」
     * 与「本来就没有」在调用方看来一模一样。
     */
    private class Stored(val settings: LauncherSettings?, val damagedText: String?)

    private fun readStored(): Stored {
        if (!file.isFile) return Stored(null, null)
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (text.isBlank()) return Stored(null, null)
        val parsed = runCatching {
            LauncherSettings.normalize(json.decodeFromString(LauncherSettings.serializer(), text))
        }.getOrNull()
        return if (parsed != null) Stored(parsed, null) else Stored(null, text)
    }

    private fun LauncherSettings.keepingUserFilled(stored: LauncherSettings): LauncherSettings = copy(
        microsoftClientId = microsoftClientId.ifBlank { stored.microsoftClientId },
        serverBase = serverBase.ifBlank { stored.serverBase },
        curseForgeApiKey = curseForgeApiKey.ifBlank { stored.curseForgeApiKey },
    )

    /**
     * 从一段解析不出 JSON 的文字里拣回凭据三格。
     *
     * 整份 JSON 解析失败最常见的成因是用户手改坏了一处（少个逗号、多个括号），
     * 其余字段其实都还在。这里只认「键: 字符串」这一种形态，拣不到就返回默认值。
     * 反转义只处理反斜杠与引号两种 —— 这三格是地址、UUID、Key，不会出现别的转义。
     */
    private fun salvageCredentials(text: String): LauncherSettings {
        fun pick(key: String): String {
            val pattern = "\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
            val match = Regex(pattern).find(text) ?: return ""
            return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").trim()
        }
        return LauncherSettings(
            microsoftClientId = pick("microsoftClientId"),
            serverBase = pick("serverBase"),
            curseForgeApiKey = pick("curseForgeApiKey"),
        )
    }

    private fun keepDamaged(text: String) {
        val parent = file.absoluteFile.parentFile ?: return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val target = File(parent, file.name + ".broken-" + stamp)
        // 同一秒内连着写两次只留第一份：内容一样，多存几份只是给目录添乱。
        if (target.exists()) return
        runCatching { target.writeText(text, Charsets.UTF_8) }
    }

    /**
     * 落盘。
     *
     * @param keepPrevious 覆盖前把盘上那一版留成 .bak，给「被别的调用点冲掉了」留一条回退路。
     *   由 [save] 判断这次是不是真的改了内容 —— 界面上一堆「点一下立刻落盘」的按钮
     *   （换配色、切模式）会连着写好几次同样的内容，每次都留一份等于给目录添乱。
     */
    private fun write(settings: LauncherSettings, keepPrevious: Boolean) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val text = json.encodeToString(LauncherSettings.serializer(), settings)
        val temp = File(parent ?: file.absoluteFile.parentFile, file.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (keepPrevious) {
            val previous = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            if (previous != null && previous != text) {
                runCatching { File(file.absolutePath + BACKUP_SUFFIX).writeText(previous, Charsets.UTF_8) }
            }
        }
        if (!temp.renameTo(file)) {
            // Windows 上目标存在时 renameTo 会失败，退一步「覆盖写」，不比原子更差。
            file.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    companion object {
        private const val BACKUP_SUFFIX = ".bak"

        /** 默认位置：<gameRoot>/platypus/settings.json，与 instances.json 同级。 */
        fun defaultFile(gameRoot: File): File = File(File(gameRoot, "platypus"), "settings.json")
    }
}
