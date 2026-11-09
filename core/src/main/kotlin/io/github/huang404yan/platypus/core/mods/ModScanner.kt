package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.instance.LoaderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * mods/ 目录里的一个 jar。
 *
 * 元数据（[modId] / [displayName] / [version]）是从 jar 里读出来的，读不到不算错误 ——
 * 有些模组只带 Forge 的 mods.toml、有些是打包用的聚合包，认不出来也不能拒绝加载，
 * 所以读不到时只把原因写进 [problem]，条目照样列出来。
 */
data class ModEntry(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val modId: String? = null,
    val displayName: String? = null,
    val version: String? = null,
    val loader: LoaderKind? = null,
    /** 结尾是 .jar 才算启用；.jar.disabled / .jar.bak 之类是用户手动停用的，列出来但不启用。 */
    val enabled: Boolean = true,
    /** 读不出元数据、或者文件看着不对时的说明；正常模组为 null。 */
    val problem: String? = null,
) {
    /** 界面上优先显示人类可读的名字。 */
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: modId?.takeIf { it.isNotBlank() } ?: fileName
}

data class ModScanResult(
    val dir: File,
    val exists: Boolean,
    /** 已启用的模组（.jar 结尾）。 */
    val mods: List<ModEntry> = emptyList(),
    /** 被用户手动停用的（.jar.disabled 等），只列不装。 */
    val disabled: List<ModEntry> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val totalCount: Int get() = mods.size + disabled.size
}

/**
 * 扫描 <游戏目录>/mods。
 *
 * 只读、不写盘、不联网。为什么读 jar 里的元数据而不是只看文件名：用户从 CurseForge 下下来的
 * 文件名经常是 sodium-fabric-0.5.13+mc1.20.1.jar 这种，看名字猜不出显示名和版本；
 * 而 jar 里的 fabric.mod.json / mods.toml 是模组自己声明的，最准。
 */
object ModScanner {

    const val MODS_DIR_NAME = "mods"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** mods 目录的位置。注意它在游戏目录下，不在 versions/<id>/ 下 —— 所有实例共享。 */
    fun modsDir(gameDir: File): File = File(gameDir, MODS_DIR_NAME)

    fun scan(gameDir: File): ModScanResult {
        val dir = modsDir(gameDir)
        if (!dir.isDirectory) {
            return ModScanResult(dir = dir, exists = false)
        }
        val warnings = mutableListOf<String>()
        val enabled = mutableListOf<ModEntry>()
        val disabled = mutableListOf<ModEntry>()

        val files = dir.listFiles { f -> f.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()
        for (file in files) {
            val name = file.name
            val isJar = name.endsWith(".jar", ignoreCase = true)
            val isDisabledJar = name.endsWith(".jar.disabled", ignoreCase = true) ||
                name.endsWith(".jar.bak", ignoreCase = true) ||
                name.endsWith(".disabled", ignoreCase = true)
            if (!isJar && !isDisabledJar) {
                // 别的文件（说明.txt、.DS_Store）不列，免得列表被噪声淹没
                if (!name.startsWith(".") && !name.endsWith(".txt", ignoreCase = true)) {
                    warnings += "跳过 " + name + "：不是 .jar"
                }
                continue
            }
            val entry = describe(file, enabled = isJar)
            if (isJar) enabled += entry else disabled += entry
        }
        return ModScanResult(dir, exists = true, mods = enabled, disabled = disabled, warnings = warnings)
    }

    /** 读一个 jar 的元数据。任何异常都折成 [ModEntry.problem]，绝不抛。 */
    fun describe(file: File, enabled: Boolean = true): ModEntry {
        val base = ModEntry(
            fileName = file.name,
            path = file.absolutePath,
            sizeBytes = runCatching { file.length() }.getOrDefault(0L),
            enabled = enabled,
        )
        val meta = runCatching { readMetadata(file) }.getOrNull()
            ?: return base.copy(problem = "读不出模组信息（jar 可能损坏，或不是模组）")
        return base.copy(
            modId = meta.modId,
            displayName = meta.displayName,
            version = meta.version,
            loader = meta.loader,
        )
    }

    private data class Metadata(
        val modId: String?,
        val displayName: String?,
        val version: String?,
        val loader: LoaderKind?,
    )

    /**
     * 依次尝试三种声明文件，先到先得：
     * 1. fabric.mod.json —— Fabric / Quilt
     * 2. META-INF/neoforge.mods.toml —— NeoForge 1.20.5+
     * 3. META-INF/mods.toml —— Forge
     */
    private fun readMetadata(file: File): Metadata? {
        ZipFile(file).use { zip ->
            zip.getEntry("fabric.mod.json")?.let { entry ->
                val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (root != null) {
                    return Metadata(
                        modId = stringOf(root, "id"),
                        displayName = stringOf(root, "name"),
                        version = stringOf(root, "version"),
                        loader = LoaderKind.FABRIC,
                    )
                }
            }
            for ((path, loader) in listOf(
                "META-INF/neoforge.mods.toml" to LoaderKind.NEOFORGE,
                "META-INF/mods.toml" to LoaderKind.FORGE,
            )) {
                zip.getEntry(path)?.let { entry ->
                    val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val parsed = parseToml(text, loader)
                    if (parsed != null) return parsed
                }
            }
        }
        return null
    }

    private fun stringOf(root: JsonObject, key: String): String? =
        (root[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    /**
     * 只认 mods.toml 里第一段 [[mods]] 的三个键，不实现完整 TOML。
     *
     * 够用的理由：我们要的只是「显示什么名字、哪个 id、什么版本」，而这三个键在 Forge 生态里
     * 是固定的写法；真碰上占位符（例如 file.jarVersion 写法）就原样留着，比猜错强。
     */
    private fun parseToml(text: String, loader: LoaderKind): Metadata? {
        val section = text.substringAfter("[[mods]]", "")
        if (section.isBlank()) return null
        // 只取第一个 [[mods]] 段：从它开始到下一个 [[ 之前
        val first = section.substringBefore("\n[[")
        fun value(key: String): String? =
            Regex("(?m)^\\s*" + key + "\\s*=\\s*\"([^\"]*)\"").find(first)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        val modId = value("modId") ?: return null
        return Metadata(
            modId = modId,
            displayName = value("displayName"),
            version = value("version"),
            loader = loader,
        )
    }
}
