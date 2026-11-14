package io.github.huang404yan.platypus.core.packs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 光影包、资源包还是数据包。
 *
 * 三者在磁盘上只差一个目录名，读元数据的方式却不完全一样：资源包与数据包都有 `pack.mcmeta`
 * （里面写着显示名与 pack_format），光影包没有统一清单，认它的办法是看 zip 里有没有
 * `shaders/`。这层差异藏在 [PackScanner] 里，界面上几个标签页走同一套代码。
 *
 * 数据包为什么能跟资源包共用扫描器：原版把数据包放进存档的 `datapacks/`，装进游戏目录的
 * `datapacks/` 也能被所有存档看到（1.16 起），两者的 zip 结构与 `pack.mcmeta` 完全一致。
 *
 * [dirName] 是游戏自己认的目录名，写死成常量，不让调用点各写各的。
 */
enum class PackKind(val dirName: String, val label: String, val projectType: String) {
    /** Iris / OptiFine 的光影，落在 `shaderpacks/`。 */
    SHADER("shaderpacks", "光影", "shader"),

    /** 原版资源包，落在 `resourcepacks/`。 */
    RESOURCE_PACK("resourcepacks", "资源包", "resourcepack"),

    /** 数据包：改合成表、世界生成、战利品表那类，落在 `datapacks/`。 */
    DATAPACK("datapacks", "数据包", "datapack"),
}

/**
 * 一个光影包或资源包。
 *
 * 元数据读不到不算错误 —— 用户从群里拿到的 zip 常常没有 pack.mcmeta，游戏也可能照样认，
 * 所以只把原因写进 [problem]，条目照样列出来，让人自己决定删不删。
 */
data class PackEntry(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    /** 是不是目录形态的包（游戏也认没压缩的文件夹）。 */
    val isDirectory: Boolean = false,
    /** 资源包从 pack.mcmeta 的 description 取；光影没有清单，退回文件名。 */
    val displayName: String? = null,
    /** 资源包的 pack_format（1.20.1 是 15）；光影为 null。 */
    val packFormat: Int? = null,
    /** 结尾是 .zip（或目录）才算启用；.zip.disabled 之类是用户手动停用的。 */
    val enabled: Boolean = true,
    /** 读不出元数据、或者文件看着不对时的说明；正常包为 null。 */
    val problem: String? = null,
) {
    /** 界面上优先显示人类可读的名字。 */
    val title: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: fileName.let { if (it.endsWith(".zip", ignoreCase = true)) it.dropLast(4) else it }
}

data class PackScanResult(
    val dir: File,
    val kind: PackKind,
    val exists: Boolean,
    /** 已启用的（.zip 或目录）。 */
    val packs: List<PackEntry> = emptyList(),
    /** 被用户手动停用的（.zip.disabled 等），只列不加载。 */
    val disabled: List<PackEntry> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val totalCount: Int get() = packs.size + disabled.size
}

/**
 * 扫描 `<实例目录>/shaderpacks`、`resourcepacks` 或 `datapacks`。
 *
 * 目录位置和 mods/ 一样：隔离之后游戏是被 `--gameDir <实例目录>` 起来的，
 * 所以光影、资源包与数据包也要放在实例目录下，每个实例一份，互不影响。
 *
 * 只读、不写盘、不联网。为什么要打开 zip 而不是只看文件名：光影包的文件名
 * 常常是 `ComplementaryReimagined_r5.9.3.zip` 这种，用户分不清哪个是哪个；
 * 资源包更是连显示名都写在 pack.mcmeta 里（多半还带 § 颜色码）。
 */
object PackScanner {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 停用的尾巴，顺序先长后短，避免 `.zip` 被提前剥掉。 */
    private val DISABLED_SUFFIXES = listOf(".zip.disabled", ".zip.bak", ".disabled")

    /** 包目录的位置：`<实例目录>/<shaderpacks|resourcepacks>`。 */
    fun dirOf(kind: PackKind, instanceDir: File): File = File(instanceDir, kind.dirName)

    fun isDisabledName(fileName: String): Boolean =
        DISABLED_SUFFIXES.any { fileName.endsWith(it, ignoreCase = true) }

    fun scan(kind: PackKind, instanceDir: File): PackScanResult {
        val dir = dirOf(kind, instanceDir)
        if (!dir.isDirectory) {
            return PackScanResult(dir = dir, kind = kind, exists = false)
        }
        val warnings = mutableListOf<String>()
        val enabled = mutableListOf<PackEntry>()
        val disabled = mutableListOf<PackEntry>()

        val files = dir.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        for (file in files) {
            val name = file.name
            val isDisabled = isDisabledName(name)
            // 停用名的尾巴把 .zip 顶掉了，光看 endsWith(".zip") 会把它们当成噪声跳过
            val isZip = file.isFile && (name.endsWith(".zip", ignoreCase = true) || isDisabled)
            val isDir = file.isDirectory
            if (!isZip && !isDir) {
                // 说明.txt / .DS_Store 这类不列，免得清单被噪声淹没
                if (!name.startsWith(".") && !name.endsWith(".txt", ignoreCase = true)) {
                    warnings += "跳过 " + name + "：不是 .zip，也不是文件夹"
                }
                continue
            }
            val entry = describe(kind, file, enabled = !isDisabled)
            if (isDisabled) disabled += entry else enabled += entry
        }
        return PackScanResult(dir, kind, exists = true, packs = enabled, disabled = disabled, warnings = warnings)
    }

    /** 读一个包的信息。任何异常都折成 [PackEntry.problem]，绝不抛。 */
    fun describe(kind: PackKind, file: File, enabled: Boolean = true): PackEntry {
        val base = PackEntry(
            fileName = file.name,
            path = file.absolutePath,
            sizeBytes = if (file.isDirectory) directorySize(file) else runCatching { file.length() }.getOrDefault(0L),
            isDirectory = file.isDirectory,
            enabled = enabled,
        )
        val meta = runCatching { readMeta(kind, file) }.getOrNull()
            ?: return base.copy(problem = problemFor(kind, file))
        return base.copy(displayName = meta.displayName, packFormat = meta.packFormat)
    }

    private data class Meta(val displayName: String?, val packFormat: Int?)

    /**
     * 资源包读 pack.mcmeta；光影看有没有 shaders/。
     *
     * 两种包都可能是文件夹形态（用户自己解压的），所以这里对 [File] 分两条路走，
     * 但对外只暴露一个 [Meta]。
     */
    private fun readMeta(kind: PackKind, file: File): Meta? = when (kind) {
        PackKind.RESOURCE_PACK, PackKind.DATAPACK -> readPackMcmeta(file)
        PackKind.SHADER -> if (hasShadersDir(file)) Meta(null, null) else null
    }

    private fun readPackMcmeta(file: File): Meta? {
        val text = if (file.isDirectory) {
            val meta = File(file, "pack.mcmeta")
            if (!meta.isFile) null else meta.readText(Charsets.UTF_8)
        } else {
            ZipFile(file).use { zip ->
                zip.getEntry("pack.mcmeta")?.let { entry ->
                    zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            }
        } ?: return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val pack = root["pack"] as? JsonObject
        val description = pack?.let { stringOf(it, "description") }?.let { stripFormatting(it) }
        // 新版格式把范围写在 supported_formats 里，pack_format 可能缺失；取不到就退回下界
        val format = pack?.let { intOf(it, "pack_format") }
            ?: (pack?.get("supported_formats") as? JsonObject)?.let { intOf(it, "min_inclusive") }
        return Meta(description, format)
    }

    private fun hasShadersDir(file: File): Boolean {
        if (file.isDirectory) return File(file, "shaders").isDirectory
        return ZipFile(file).use { zip ->
            // 只认根目录的 shaders/：Iris 就是这么找的，套了一层文件夹的包游戏也不认
            zip.getEntry("shaders/") != null ||
                zip.entries().asSequence().any { it.name.startsWith("shaders/") }
        }
    }

    private fun problemFor(kind: PackKind, file: File): String = when (kind) {
        PackKind.SHADER -> "zip 里没有 shaders/ 目录（游戏的「光影」页面多半不认它）"
        PackKind.RESOURCE_PACK -> "zip 里没有 pack.mcmeta（游戏不认这个资源包）"
        PackKind.DATAPACK -> "zip 里没有 pack.mcmeta（游戏不认这个数据包）"
    }

    /**
     * 去掉 § 颜色码。
     *
     * 资源包的 description 基本都是 `§a■ 1.11.2 BASE§8\n■ By FreshLX` 这种，直接显示会
     * 在前端变成方块；这里只做文本清理，不改文件。
     */
    fun stripFormatting(text: String): String =
        text.replace(Regex("§."), "")
            .replace(Regex("[\r\n]+"), " ")
            .replace("\u00a0", " ")
            .trim()

    private fun stringOf(root: JsonObject, key: String): String? =
        (root[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun intOf(root: JsonObject, key: String): Int? =
        (root[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

    private fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }
}
