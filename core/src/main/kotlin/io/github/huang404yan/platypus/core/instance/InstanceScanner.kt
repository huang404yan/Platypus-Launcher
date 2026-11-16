package io.github.huang404yan.platypus.core.instance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/** 一次目录扫描的结果。 */
data class ScanResult(
    val root: File,
    val isGameDirectory: Boolean,
    val instances: List<Instance>,
    val warnings: List<String> = emptyList(),
    /**
     * 只被别的版本当作 inheritsFrom 用的原版目录（装加载器时顺带建出来的那些）。
     *
     * 它们仍然在磁盘上、删不得也动不得，只是不当作独立实例列出来。
     * 界面上要解释「为什么装一个 Forge 只多出一条」时可以拿它说话。
     */
    val libraryVersions: List<String> = emptyList(),
)

/**
 * 扫描一个 Minecraft 游戏目录，把 versions/ 下的每个版本识别成一个实例。
 *
 * 只读、不写盘、不联网。用 java.io.File 而不是 java.nio.Path，Android 上不需要额外适配层。
 */
object InstanceScanner {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 判断目录看起来是不是一个 .minecraft 目录。 */
    fun isGameDirectory(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return File(dir, "versions").isDirectory ||
            File(dir, "libraries").isDirectory ||
            File(dir, "assets").isDirectory
    }

    fun scan(root: File): ScanResult {
        if (!root.isDirectory) {
            return ScanResult(root, false, emptyList(), listOf("目录不存在：${root.path}"))
        }
        if (!isGameDirectory(root)) {
            return ScanResult(root, false, emptyList(), listOf("不是游戏目录（缺少 versions/、libraries/ 或 assets/）：${root.path}"))
        }

        val warnings = mutableListOf<String>()
        val versionsDir = File(root, "versions")
        val parsed = mutableListOf<ParsedVersion>()

        if (!versionsDir.isDirectory) {
            warnings += "没有 versions/ 目录，也没有可用的版本"
        } else {
            val dirs = versionsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
            for (dir in dirs) {
                val id = dir.name
                val jsonFile = File(dir, "$id.json")
                if (!jsonFile.isFile) {
                    warnings += "跳过 $id：缺少 $id.json"
                    continue
                }
                val element = runCatching { json.parseToJsonElement(jsonFile.readText()).jsonObject }.getOrNull()
                if (element == null) {
                    warnings += "跳过 $id：$id.json 解析失败"
                    continue
                }
                parsed += ParsedVersion(dir, id, element)
            }
        }

        // 装加载器必然会把原版目录建出来（Forge 的 installer 要拿它当父版本），
        // 但那条原版不该在列表里和带加载器的那条并排出现 —— 用户会以为装重了。
        // 判定只认「原版 + 没有任何 inheritsFrom + 恰好被别的版本继承」三件事同时成立，
        // 少一样就照常当独立实例（自己就是加载器版本、或者根本没人继承它）。
        val inheritedTargets = parsed.mapNotNull { it.inheritsFrom }.toSet()
        val libraryVersions = mutableListOf<String>()
        val instances = mutableListOf<Instance>()
        for (version in parsed) {
            val pureVanilla = version.inheritsFrom == null && version.loader == LoaderKind.VANILLA
            if (pureVanilla && version.id in inheritedTargets) {
                libraryVersions += version.id
                continue
            }
            instances += buildInstance(root, version.dir, version.id, version.json, version.loader, version.loaderVersion)
        }

        return ScanResult(
            root = root,
            isGameDirectory = true,
            instances = instances.sortedBy { it.name.lowercase() },
            warnings = warnings,
            libraryVersions = libraryVersions.sorted(),
        )
    }

    /** scan 的中间产物：一个解析过 json 的版本目录。 */
    private class ParsedVersion(val dir: File, val id: String, val json: JsonObject) {
        val inheritsFrom: String? =
            (json["inheritsFrom"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val loader: LoaderKind
        val loaderVersion: String?

        init {
            val detected = detectLoader(id)
            loader = detected.first
            loaderVersion = detected.second
        }
    }

    private fun buildInstance(
        root: File,
        versionDir: File,
        id: String,
        json: JsonObject,
        loader: LoaderKind,
        loaderVersion: String?,
    ): Instance {
        val inheritsFrom = (json["inheritsFrom"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        // PCL 等启动器会写「合并后」的版本 json（没有 inheritsFrom），通常内嵌 <mc>-<loader> 的版本号。
        // 有 inheritsFrom 就直接用它；没有就从 id 前缀里取，取不到才退回整个 id。
        val gameVersion = inheritsFrom ?: gameVersionFromId(id) ?: id
        return Instance(
            id = id,
            name = id,
            gameVersion = gameVersion,
            loader = loader,
            loaderVersion = loaderVersion,
            directory = versionDir.absolutePath,
        )
    }

    /**
     * 从版本 id 判断加载器。
     * 常见形态：
     *  1.20.1-forge-47.2.0
     *  fabric-loader-0.15.11-1.20.1
     *  quilt-loader-0.23.1-1.20.1
     *  neoforge-20.4.237（游戏版本靠 inheritsFrom）
     *  1.20.1-OptiFine_HD_U_I6
     */
    fun detectLoader(versionId: String): Pair<LoaderKind, String?> {
        val lower = versionId.lowercase()
        return when {
            lower.contains("neoforge") -> LoaderKind.NEOFORGE to matchVersion(versionId, "neoforge[-_]([0-9][0-9A-Za-z.\\-]*)")
            // quilt-loader-0.23.1-1.20.1（Quilt 官方 profile 的 id）与
            // 1.20.1-quilt-0.24.0（本启动器自己起的名字）两种都认，原因同 Fabric。
            lower.contains("quilt") -> LoaderKind.QUILT to (
                matchVersion(versionId, "quilt[-_]loader[-_]([0-9][0-9.]*)")
                    ?: matchVersion(versionId, "quilt[-_]([0-9][0-9.]*)$")
                )
            // fabric-loader-0.16.14-1.20.1（Fabric 官方 profile 的 id）与
            // 1.20.1-fabric-0.16.14（本启动器自己起的名字）两种都认。
            lower.contains("fabric") -> LoaderKind.FABRIC to (
                matchVersion(versionId, "fabric[-_]loader[-_]([0-9][0-9.]*)")
                    ?: matchVersion(versionId, "fabric[-_]([0-9][0-9.]*)$")
                )
            lower.contains("forge") -> LoaderKind.FORGE to matchVersion(versionId, "forge[-_]([0-9][0-9A-Za-z.\\-]*)")
            lower.contains("optifine") -> LoaderKind.OPTIFINE to null
            else -> LoaderKind.VANILLA to null
        }
    }

    /** 1.20.1-Forge_47.4.28 → 1.20.1；fabric-loader-0.15.11-1.20.1 → 1.20.1；取不到返回 null。 */
    fun gameVersionFromId(versionId: String): String? {
        GameVersionRegex.find(versionId)?.let { return it.groupValues[1] }
        // fabric-loader-0.15.11-1.20.1 / quilt-loader-0.23.1-1.20.1：游戏版本在结尾
        return TrailingGameVersionRegex.find(versionId)?.groupValues?.get(1)
    }

    private fun matchVersion(text: String, pattern: String): String? {
        return Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trimEnd('-')?.ifBlank { null }
    }

    /** 版本 id 开头的游戏版本号：1.20.1、1.21、0.30 之类。 */
    private val GameVersionRegex = Regex("""^(\d+\.\d+(?:\.\d+)?)""")
    private val TrailingGameVersionRegex = Regex("""-(\d+\.\d+(?:\.\d+)?)$""")
}
