package io.github.huang404yan.platypus.core.settings

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * 以单个 JSON 文件保存设置，写法和 [io.github.huang404yan.platypus.core.instance.JsonInstanceRepository] 一致：
 * 临时文件 + 原子替换，读失败一律退回默认值而不抛。
 *
 * 与实例索引放在同一层目录（<gameRoot>/platypus/），用户想手工改或整个带走都方便。
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

    override suspend fun save(settings: LauncherSettings): LauncherSettings = mutex.withLock {
        val stored = LauncherSettings.normalize(settings).copy(updatedAt = System.currentTimeMillis())
        write(stored)
        stored
    }

    private fun read(): LauncherSettings {
        if (!file.isFile) return LauncherSettings()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (text.isBlank()) return LauncherSettings()
        return runCatching {
            LauncherSettings.normalize(json.decodeFromString(LauncherSettings.serializer(), text))
        }.getOrElse { LauncherSettings() }
    }

    private fun write(settings: LauncherSettings) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val text = json.encodeToString(LauncherSettings.serializer(), settings)
        val temp = File(parent ?: file.absoluteFile.parentFile, file.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            // Windows 上目标存在时 renameTo 会失败，退一步「覆盖写」，不比原子更差。
            file.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    companion object {
        /** 默认位置：<gameRoot>/platypus/settings.json，与 instances.json 同级。 */
        fun defaultFile(gameRoot: File): File = File(File(gameRoot, "platypus"), "settings.json")
    }
}
