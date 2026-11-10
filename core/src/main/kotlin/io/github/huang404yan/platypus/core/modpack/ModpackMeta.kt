package io.github.huang404yan.platypus.core.modpack

import io.github.huang404yan.platypus.core.instance.LoaderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 整合包格式。 */
enum class ModpackKind(val label: String) {
    /** Modrinth 的 .mrpack：zip 里有 `modrinth.index.json`，每个文件由 CDN 下发。 */
    MRPACK("Modrinth（.mrpack）"),

    /** MCBBS v2：zip 里有 `mcbbs.packmeta`，兜底读 CurseForge 风格的 `manifest.json`。 */
    MCBBS_V2("MCBBS v2"),

    /** 认不出来（或者认得但本启动器还没实现，例如 Prism 的 `mmc-pack.json`）。 */
    UNKNOWN("未知格式"),
}

/**
 * 整合包里一个要从网上下载的文件。
 *
 * 字段刻意对齐 [io.github.huang404yan.platypus.core.download.DownloadItem]：
 * `path` 是相对实例目录的相对路径，`url` 是主地址，`mirrors` 是备用地址。
 * 这样导入流程把它转成下载任务时不用再猜一遍坐标系。
 */
data class ModpackFile(
    /** 相对实例目录的路径，已归一化成斜杠分隔（例如 `mods/foo.jar`）。 */
    val path: String,
    val url: String,
    val sha1: String? = null,
    /** 体积；-1 表示包没写，进度条只能按文件数算。 */
    val size: Long = -1L,
    val mirrors: List<String> = emptyList(),
) {
    /** 界面显示用的一级目录（`mods`、`config`…），没有目录时为空串。 */
    val topDir: String get() = if ('/' in path) path.substringBefore('/') else ""
}

/**
 * 解析出来的整合包清单 —— 与「怎么装」无关，只描述「包里声明了什么」。
 *
 * 刻意不碰磁盘、不联网：[parseMrpack] / [parseMcbbs] 的入参是 zip 里那几段文本，
 * 因此可以在 core 的单测里直接跑，不需要造 zip、也不需要起桩服务器。
 */
data class ModpackManifest(
    val kind: ModpackKind,
    /** 包名；取不到时退回文件名（由调用方补）。 */
    val name: String,
    /** 包自身的版本号，没有就是空串。 */
    val versionId: String,
    /** 目标 Minecraft 版本，例如 `1.20.1`。 */
    val gameVersion: String,
    val loader: LoaderKind = LoaderKind.VANILLA,
    val loaderVersion: String? = null,
    /** 需要下载的文件；MCBBS 包不含游戏文件，所以是空列表。 */
    val files: List<ModpackFile> = emptyList(),
    /**
     * 需要解压进实例目录的前缀，**按顺序覆盖**（后面的盖前面的）。
     *
     * mrpack 是 `overrides/` + `client-overrides/`（官方要求 client 侧后盖）；
     * MCBBS 只有一个 `overrides/`。
     */
    val overrideDirs: List<String> = emptyList(),
    val extraJvmArgs: List<String> = emptyList(),
    val extraGameArgs: List<String> = emptyList(),
    /** 不影响导入、但用户该知道的事（跳过几个文件、哪个版本号没见过…）。 */
    val warnings: List<String> = emptyList(),
) {
    /** 界面上一句话概括：`1.20.1 + Fabric`。 */
    val summary: String
        get() = buildString {
            append(gameVersion)
            if (loader != LoaderKind.VANILLA) {
                append(" + ")
                append(loader.name.lowercase().replaceFirstChar { it.uppercase() })
                loaderVersion?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
            }
        }
}

/** 解析结果：要么得到清单，要么得到一句人话的原因（不抛异常）。 */
sealed interface ModpackParse<out T> {
    data class Ok<T>(val value: T) : ModpackParse<T>
    data class Failure(val reason: String) : ModpackParse<Nothing>
}

fun <T> ModpackParse<T>.getOrNull(): T? = (this as? ModpackParse.Ok)?.value
fun <T> ModpackParse<T>.reasonOrNull(): String? = (this as? ModpackParse.Failure)?.reason

/** zip 里识别出来的包布局：什么格式、标记文件在第几层（有的包整个套在一个顶层文件夹里）。 */
data class ModpackLayout(val kind: ModpackKind, val basePrefix: String = "") {
    /** 拼出包内某个相对路径的完整 entry 名。 */
    fun entry(name: String): String = basePrefix + name
}

/**
 * mrpack / MCBBS v2 的格式解析。
 *
 * 两种格式的规格来源：
 * - .mrpack：Modrinth 官方文档 `support.modrinth.com/en/articles/8802351`（formatVersion = 1）
 * - MCBBS v2：PCL 的 `Modules/Minecraft/ModModpack.vb` 里 `InstallPackMCBBS` 的读法
 *
 * 两条共同的硬要求写在实现里：
 * 1. **路径绝不能越出实例目录** —— 官方文档专门警告过 `..`、盘符、绝对路径三种写法；
 * 2. 认不出的东西只记 warning，不因为「有个版本号没见过」就把整个包拒掉。
 */
object ModpackMeta {

    const val MRPACK_INDEX: String = "modrinth.index.json"
    const val MCBBS_META: String = "mcbbs.packmeta"
    const val MCBBS_MANIFEST: String = "manifest.json"
    const val MMC_PACK: String = "mmc-pack.json"

    const val OVERRIDES_DIR: String = "overrides"
    const val CLIENT_OVERRIDES_DIR: String = "client-overrides"

    /** 本启动器实际验证过的 mrpack 版本；遇到更高版本只警告，仍按 1 解析。 */
    const val SUPPORTED_MRPACK_FORMAT: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** `C:` / `C:/` 这种盘符写法：解到 Windows 上会跑到别的盘去。 */
    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")

    /**
     * 识别 zip 里装的是哪种整合包。
     *
     * 只看 entry 名，不读内容 —— 这样调用方可以先判格式、再去读那一个文件，
     * 不用把整个 zip 解到临时目录。
     *
     * 判定顺序照 PCL：`mcbbs.packmeta` 优先于 `manifest.json`（CurseForge 包的
     * manifest.json 里字段名不一样，MCBBS 自己也会写一份 manifest.json 给老启动器兜底）。
     */
    fun detect(entryNames: Collection<String>): ModpackLayout {
        var mcbbsPrefix: String? = null
        var manifestPrefix: String? = null
        var hasPrism = false
        var basePrefix = ""

        for (raw in entryNames) {
            val name = raw.replace('\\', '/').trimStart('/')
            if (name.isEmpty() || name.endsWith("/")) continue
            val base = name.substringBeforeLast('/', "")
            val prefix = if (base.isEmpty()) "" else base + "/"
            when (name.substringAfterLast('/')) {
                MRPACK_INDEX -> return ModpackLayout(ModpackKind.MRPACK, prefix)
                MCBBS_META -> if (mcbbsPrefix == null) mcbbsPrefix = prefix
                MCBBS_MANIFEST -> if (manifestPrefix == null) manifestPrefix = prefix
                MMC_PACK -> hasPrism = true
            }
        }

        mcbbsPrefix?.let { return ModpackLayout(ModpackKind.MCBBS_V2, it) }
        if (hasPrism) return ModpackLayout(ModpackKind.UNKNOWN, basePrefix)

        // manifest.json 单凭自己是弱信号（普通 jar 里也有），必须同时看到 overrides/ 才认。
        manifestPrefix?.let { prefix ->
            val hasOverrides = entryNames.any {
                it.replace('\\', '/').trimStart('/').startsWith(prefix + OVERRIDES_DIR + "/")
            }
            if (hasOverrides) return ModpackLayout(ModpackKind.MCBBS_V2, prefix)
        }
        return ModpackLayout(ModpackKind.UNKNOWN, basePrefix)
    }

    /**
     * 解析 `modrinth.index.json`。
     *
     * @param text 文件全文（UTF-8）
     * @param fallbackName 包里没写 name 时用什么当名字（一般是文件名）
     */
    fun parseMrpack(text: String, fallbackName: String = "未命名整合包"): ModpackParse<ModpackManifest> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            return ModpackParse.Failure("modrinth.index.json 不是合法 JSON：" + (it.message ?: "解析失败"))
        }
        val warnings = mutableListOf<String>()

        val game = root.str("game")
        if (game != null && game != "minecraft") {
            return ModpackParse.Failure("这个包是给「" + game + "」的，不是 Minecraft 整合包")
        }

        val format = root.int("formatVersion")
        if (format != null && format != SUPPORTED_MRPACK_FORMAT) {
            warnings += "包的 formatVersion 是 " + format + "，本启动器只验证过 " +
                SUPPORTED_MRPACK_FORMAT + "，仍按 " + SUPPORTED_MRPACK_FORMAT + " 读"
        }

        val deps = root["dependencies"] as? JsonObject ?: JsonObject(emptyMap())
        val minecraft = deps.str("minecraft")
        if (minecraft.isNullOrBlank()) {
            // 没有 gameVersion 就没法装原版，这一条不能宽容。
            return ModpackParse.Failure("包里没声明 dependencies.minecraft，无法确定要装哪个游戏版本")
        }

        var loader = LoaderKind.VANILLA
        var loaderVersion: String? = null
        for ((key, value) in deps) {
            val version = (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            when (key) {
                "minecraft" -> Unit
                "forge" -> { loader = LoaderKind.FORGE; loaderVersion = version }
                "neoforge" -> { loader = LoaderKind.NEOFORGE; loaderVersion = version }
                "fabric-loader" -> { loader = LoaderKind.FABRIC; loaderVersion = version }
                "quilt-loader" -> { loader = LoaderKind.QUILT; loaderVersion = version }
                // 官方文档明确说 key 可能新增，旧启动器不该因为多一个键就失败。
                else -> {
                    warnings += "包声明了本启动器不认识的加载器「" + key + "」，已忽略"
                    if (loader == LoaderKind.VANILLA) loader = LoaderKind.OTHER
                }
            }
        }
        if (loader == LoaderKind.QUILT) {
            // 与 PCL 一致：Quilt 的安装链没做，先明确拒绝而不是装出个跑不起来的实例。
            return ModpackParse.Failure("暂不支持 Quilt 整合包（包里的 quilt-loader " + (loaderVersion ?: "") + "）")
        }

        val files = mutableListOf<ModpackFile>()
        var skippedByEnv = 0
        var skippedByPath = 0
        var missingHash = 0
        (root["files"] as? kotlinx.serialization.json.JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val rawPath = obj.str("path") ?: return@forEach
            val safePath = safeRelativePath(rawPath)
            if (safePath == null) {
                skippedByPath++
                warnings += "跳过越界路径：" + rawPath
                return@forEach
            }
            // env.client == "unsupported" 表示「客户端不需要装这个文件」（比如纯服务端的插件）。
            val envClient = (obj["env"] as? JsonObject)?.str("client")
            if (envClient != null && envClient.equals("unsupported", ignoreCase = true)) {
                skippedByEnv++
                return@forEach
            }
            val downloads = (obj["downloads"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.startsWith("http", true) } }
                ?.distinct()
                .orEmpty()
            if (downloads.isEmpty()) {
                warnings += "跳过没有下载地址的文件：" + safePath
                return@forEach
            }
            val hashes = obj["hashes"] as? JsonObject
            val sha1 = hashes?.str("sha1")?.lowercase()?.takeIf { it.length == 40 }
            if (sha1 == null) missingHash++
            files += ModpackFile(
                path = safePath,
                url = downloads.first(),
                sha1 = sha1,
                size = obj.long("fileSize") ?: -1L,
                mirrors = downloads.drop(1),
            )
        }

        if (skippedByEnv > 0) warnings += skippedByEnv.toString() + " 个文件标注为客户端不需要，已跳过"
        if (missingHash > 0) warnings += missingHash.toString() + " 个文件没有 sha1，下载后无法校验完整性"
        if (skippedByPath > 0) warnings += skippedByPath.toString() + " 个文件的路径越出了实例目录，已丢弃"

        return ModpackParse.Ok(
            ModpackManifest(
                kind = ModpackKind.MRPACK,
                name = root.str("name")?.takeIf { it.isNotBlank() } ?: fallbackName,
                versionId = root.str("versionId").orEmpty(),
                gameVersion = minecraft,
                loader = loader,
                loaderVersion = loaderVersion,
                files = files,
                // 客户端只取这两层，server-overrides 是给服务端准备的。
                overrideDirs = listOf(OVERRIDES_DIR, CLIENT_OVERRIDES_DIR),
                warnings = warnings,
            ),
        )
    }

    /**
     * 解析 MCBBS v2 的 `mcbbs.packmeta`，读不出来再兜底 CurseForge 风格的 `manifest.json`。
     *
     * 与 mrpack 最大的区别：**包里不带游戏文件**，只用 `addons` 声明「要 1.20.1 + Forge 47.2.0」，
     * 具体文件由启动器现装。所以这里 [ModpackManifest.files] 恒为空。
     */
    fun parseMcbbs(
        packmetaText: String?,
        manifestText: String? = null,
        fallbackName: String = "未命名整合包",
    ): ModpackParse<ModpackManifest> {
        val warnings = mutableListOf<String>()
        var root: JsonObject? = null
        var usedManifest = false

        packmetaText?.takeIf { it.isNotBlank() }?.let { text ->
            root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (root == null) warnings += "mcbbs.packmeta 不是合法 JSON，改读 manifest.json"
        }
        if (root == null) {
            manifestText?.takeIf { it.isNotBlank() }?.let { text ->
                root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                usedManifest = root != null
                if (root == null) warnings += "manifest.json 也不是合法 JSON"
            }
        }
        val meta = root ?: return ModpackParse.Failure("整合包里的 mcbbs.packmeta / manifest.json 都读不出来")

        val addons = mutableMapOf<String, String>()
        (meta["addons"] as? kotlinx.serialization.json.JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val id = obj.str("id") ?: return@forEach
            addons[id.lowercase()] = obj.str("version").orEmpty()
        }
        val gameVersion = addons["game"]
        if (gameVersion.isNullOrBlank()) {
            // PCL 同样在这里拒绝：「该 MCBBS 整合包未提供游戏版本信息，无法安装！」
            return ModpackParse.Failure("该整合包未提供游戏版本信息（addons 里缺 game），无法安装")
        }
        if (addons.containsKey("quilt")) {
            return ModpackParse.Failure("暂不支持 Quilt 整合包（包里声明了 quilt " + addons["quilt"] + "）")
        }

        var loader = LoaderKind.VANILLA
        var loaderVersion: String? = null
        addons["forge"]?.takeIf { it.isNotBlank() }?.let { loader = LoaderKind.FORGE; loaderVersion = it }
        addons["neoforge"]?.takeIf { it.isNotBlank() }?.let { loader = LoaderKind.NEOFORGE; loaderVersion = it }
        addons["fabric"]?.takeIf { it.isNotBlank() }?.let { loader = LoaderKind.FABRIC; loaderVersion = it }
        if (addons.containsKey("optifine")) {
            warnings += "包声明了 OptiFine " + addons["optifine"] + "，本启动器暂不支持，已忽略（不影响启动，只是没高清修复）"
        }
        for ((id, _) in addons) {
            if (id !in KNOWN_ADDONS) warnings += "包声明了不认识的组件「" + id + "」，已忽略"
        }
        if (usedManifest) warnings += "包只有 manifest.json（没有 mcbbs.packmeta），按 MCBBS 字段读"

        val launchInfo = meta["launchInfo"] as? JsonObject
        return ModpackParse.Ok(
            ModpackManifest(
                kind = ModpackKind.MCBBS_V2,
                name = meta.str("name")?.takeIf { it.isNotBlank() } ?: fallbackName,
                versionId = meta.str("version").orEmpty(),
                gameVersion = gameVersion,
                loader = loader,
                loaderVersion = loaderVersion,
                files = emptyList(),
                overrideDirs = listOf(OVERRIDES_DIR),
                extraJvmArgs = stringList(launchInfo?.get("javaArgument")),
                extraGameArgs = stringList(launchInfo?.get("launchArgument")),
                warnings = warnings,
            ),
        )
    }

    private val KNOWN_ADDONS = setOf("game", "forge", "neoforge", "fabric", "quilt", "optifine", "liteloader")

    /**
     * 把包里写的相对路径变成安全的、斜杠分隔的相对路径；不合法返回 null。
     *
     * 拒绝的三类（前两类是 mrpack 官方文档点名要求防的）：
     * 1. 盘符开头（`C:evil`）—— 解到 Windows 上会落到实例目录之外；
     * 2. 绝对路径（`/etc/x`）；
     * 3. 任一段是 `..` —— 往上跳几层就能写别人的目录。
     *
     * 注意判断的是「整段等于 `..`」，不是「包含 `..`」：`..hidden.jar` 是合法文件名，
     * 按文档字面「不能含 ..」去拦会把真模组误杀。
     */
    fun safeRelativePath(path: String): String? {
        val unified = path.replace('\\', '/').trim()
        if (unified.isEmpty()) return null
        if (unified.startsWith("/")) return null
        if (DRIVE_PREFIX.containsMatchIn(unified)) return null
        if (unified.any { it == '\u0000' }) return null
        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null
        // 段首段尾的空白/点在 Windows 上会被静默裁掉（"a. " 变成 "a"），可能撞到已有目录。
        if (segments.any { it.trimEnd('.', ' ').isEmpty() }) return null
        return segments.joinToString("/")
    }

    private fun stringList(element: kotlinx.serialization.json.JsonElement?): List<String> =
        (element as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
            .orEmpty()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()
}
