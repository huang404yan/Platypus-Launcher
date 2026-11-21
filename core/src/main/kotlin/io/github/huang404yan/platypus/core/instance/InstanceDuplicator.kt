package io.github.huang404yan.platypus.core.instance

import io.github.huang404yan.platypus.core.version.VersionJsonFormat
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 把一个已存在的版本目录复制成另一个名字的新版本。
 *
 * 用途：往一个「同游戏版本、同加载器、同加载器版本」已经装过的游戏目录里再导入一次整合包时，
 * 绝不能落回原来那个目录 —— 那样两个包会挤在同一套 mods/、saves/ 里，后导入的要么覆盖前面的
 * 文件，要么装出一堆互相打架的模组。做法是复制出一份新的版本目录，只带版本壳（json 与
 * client jar），不带任何游戏数据，让新实例从一开始就是干净的。
 *
 * 只复制顶层文件，不复制任何子目录：mods / saves / config / resourcepacks / logs /
 * crash-reports / natives 全是实例自己的数据，跟着复制就等于把两个实例的数据混在一起。
 * 版本壳要的就只有 <id>.json 与 <id>.jar 这两样（Forge/NeoForge 的 json 里那段
 * -DignoreList 参数用的是目录名，所以 jar 必须跟着一起改名）。
 */
object InstanceDuplicator {

    /** 复制出来的版本 id 后缀分隔符。用下划线而不是短横线：四个加载器的 id 反解正则都不吃下划线。 */
    const val SUFFIX_SEPARATOR = "_"

    private val CopySuffix = Regex("""_\d+$""")

    /** 这个版本目录能不能复制（有 <id>.json 才算一个版本）。 */
    fun canDuplicate(gameRoot: File, versionId: String): Boolean {
        val dir = versionDir(gameRoot, versionId)
        return File(dir, versionId + ".json").isFile
    }

    /**
     * 给 [baseId] 找一个还没被占用的版本 id。
     *
     * 「占用」按磁盘上的目录算，不按实例登记算 —— 一个只扫描到、没登记的目录要是被占了名，
     * 覆盖上去同样会毁掉别人的东西。
     */
    fun nextFreeId(gameRoot: File, baseId: String): String {
        val trimmed = baseId.trim().ifBlank { "instance" }
        if (!versionDir(gameRoot, trimmed).exists()) return trimmed
        // 已经是 xxx_2 这种形态时就沿着同一个基名继续往上试，避免复制出来的复制品叫 xxx_2_2。
        val base = stripCopySuffix(trimmed)
        var n = 2
        while (true) {
            val candidate = base + SUFFIX_SEPARATOR + n
            if (!versionDir(gameRoot, candidate).exists()) return candidate
            n += 1
            if (n > 999) return base + SUFFIX_SEPARATOR + System.currentTimeMillis()
        }
    }

    /** 去掉复制出来时加的 _2 / _3 后缀。不是这种形态就原样返回。 */
    fun stripCopySuffix(versionId: String): String = versionId.replace(CopySuffix, "")

    /** 这个 id 是不是复制出来时起的名字（带 _N 后缀）。 */
    fun hasCopySuffix(versionId: String): Boolean = CopySuffix.containsMatchIn(versionId)

    /** 复制出来时用的第几个序号，不是复制品返回 null。 */
    fun copyIndex(versionId: String): Int? =
        CopySuffix.find(versionId)?.value?.removePrefix(SUFFIX_SEPARATOR)?.toIntOrNull()

    /**
     * 把 versions/<sourceVersionId> 复制成 versions/<targetVersionId>，返回新目录。
     *
     * 只拷顶层文件、且只拷版本壳：<源 id>.json 改名成 <目标 id>.json 并把 json 里的 id
     * 字段改写，<源 id>.jar 改名成 <目标 id>.jar，其余顶层文件原样带走。
     *
     * @throws IllegalStateException 源目录不是版本目录、目标目录已存在、或两边是同一个目录。
     */
    fun duplicate(gameRoot: File, sourceVersionId: String, targetVersionId: String): File {
        require(sourceVersionId.isNotBlank()) { "源版本 id 不能为空" }
        require(targetVersionId.isNotBlank()) { "目标版本 id 不能为空" }
        require(sourceVersionId != targetVersionId) { "源版本与目标版本不能同名：" + sourceVersionId }
        val sourceDir = versionDir(gameRoot, sourceVersionId)
        val targetDir = versionDir(gameRoot, targetVersionId)
        check(File(sourceDir, sourceVersionId + ".json").isFile) {
            "源目录不是版本目录（缺少 " + sourceVersionId + ".json）：" + sourceDir.absolutePath
        }
        check(!targetDir.exists()) { "目标目录已经存在，不能覆盖：" + targetDir.absolutePath }

        check(targetDir.mkdirs() || targetDir.isDirectory) { "建不出目标目录：" + targetDir.absolutePath }

        val files = sourceDir.listFiles { f -> f.isFile }.orEmpty()
        for (file in files) {
            // 装到一半的标记、以及各种日志，都不属于版本壳。
            if (file.name == InstallMark.FILE_NAME) continue
            if (file.name.endsWith(".log")) continue
            val targetName = renameFor(file.name, sourceVersionId, targetVersionId)
            val target = File(targetDir, targetName)
            if (isOurVersionJson(file.name, sourceVersionId)) {
                target.writeText(reidJson(file.readText(), targetVersionId), Charsets.UTF_8)
            } else {
                file.copyTo(target, overwrite = false)
            }
        }
        return targetDir
    }

    /** 版本目录只认 <id>.json 与 <id>.jar；别的顶层文件（如 options.txt）照样带过去不改名。 */
    private fun renameFor(name: String, sourceId: String, targetId: String): String {
        if (name == sourceId + ".json" || name == sourceId + ".jar") {
            return targetId + name.removePrefix(sourceId)
        }
        return name
    }

    private fun isOurVersionJson(name: String, sourceId: String): Boolean = name == sourceId + ".json"

    /**
     * 把版本 json 里的 id 换成新的版本 id。
     *
     * 不改的话游戏会在日志里报成旧 id，启动器的版本列表也可能按 json 里的 id 去找目录从而找错。
     * 其余字段（inheritsFrom、libraries、mainClass、arguments 里的版本名占位符）都是按
     * 目录名或父版本解析的，复制后自然对得上，不需要动。
     */
    private fun reidJson(text: String, targetVersionId: String): String {
        val obj = runCatching { VersionJsonFormat.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return text
        val patched = JsonObject(obj.toMutableMap().apply { put("id", JsonPrimitive(targetVersionId)) })
        return VersionJsonFormat.encodeToString(JsonObject.serializer(), patched)
    }

    private fun versionDir(gameRoot: File, versionId: String): File =
        File(File(gameRoot, "versions"), versionId)
}
