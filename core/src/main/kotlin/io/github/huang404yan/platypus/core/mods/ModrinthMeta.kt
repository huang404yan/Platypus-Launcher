package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.instance.LoaderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 搜索结果里的一条模组。 */
data class ModProject(
    val slug: String,
    val title: String,
    val description: String = "",
    val author: String? = null,
    val downloads: Long = 0L,
    val projectId: String = "",
) {
    /** 界面上「250,402,653 次下载」这种。 */
    val downloadsLabel: String get() = ModrinthMeta.formatCount(downloads)
}

/** 某个模组针对一个游戏版本的一个具体文件。 */
data class ModVersionFile(
    val projectSlug: String,
    val versionNumber: String,
    val fileName: String,
    val url: String,
    val sha1: String? = null,
    val size: Long = -1L,
    val releaseType: String = "release",
    /** 依赖的其它 project_id —— 只用于提示「还要装前置」，不自动装。 */
    val requiredDependencies: List<String> = emptyList(),
)

sealed interface ModrinthParse<out T> {
    data class Ok<T>(val value: T) : ModrinthParse<T>
    data class Failure(val reason: String) : ModrinthParse<Nothing>
}

fun <T> ModrinthParse<T>.getOrNull(): T? = (this as? ModrinthParse.Ok)?.value
fun <T> ModrinthParse<T>.reasonOrNull(): String? = (this as? ModrinthParse.Failure)?.reason

/**
 * Modrinth API 的 URL 构造与响应解析。
 *
 * 只做纯字符串/JSON 的事，不发请求 —— 这样解析逻辑能在 core 的单测里跑，
 * 不用联网也不用起桩服务器。发请求的那层在 core/mods/ModInstaller.kt 的 fetchText。
 *
 * 2026-11-10 备注：原计划的 ui/download/ModrinthClient.kt 没有单独建，请求逻辑并进了 ModInstaller。
 *
 * 为什么不自己爬 CurseForge：CurseForge 的 API 需要 key，而且它要求第三方应用
 * 不得绕过广告下载；Modrinth 官方文档明确允许第三方启动器直接调 API 与 CDN，
 * 对开源启动器是唯一省事且合规的路子。
 */
object ModrinthMeta {

    const val BASE_URL: String = "https://api.modrinth.com/v2"

    /** Modrinth 要求带能标识调用方的 User-Agent；用户配置里会换成带仓库地址的。 */
    const val DEFAULT_USER_AGENT: String = "PlatypusLauncher/0.1 (https://github.com/huang404yan/Platypus-Launcher)"

    /** 一次搜索最多取多少条。界面是滚动列表，40 条够翻一阵子，也不至于拖慢首屏。 */
    const val DEFAULT_SEARCH_LIMIT: Int = 20

    /** 加载器在 Modrinth 的 facet 名。VANILLA 没有加载器，只能不加 facet。 */
    fun loaderName(loader: LoaderKind): String? = when (loader) {
        LoaderKind.FABRIC -> "fabric"
        LoaderKind.QUILT -> "quilt"
        LoaderKind.FORGE -> "forge"
        LoaderKind.NEOFORGE -> "neoforge"
        else -> null
    }

    /**
     * 搜索结果 URL。
     *
     * facets 是 JSON 数组的数组，每一层是 AND、层内是 OR —— 这是 Modrinth 的约定，
     * 所以「mod + 加载器 + 版本」要分成三层写，写进一层会变成「或」的关系。
     */
    fun searchUrl(
        query: String,
        gameVersion: String? = null,
        loader: LoaderKind = LoaderKind.FABRIC,
        limit: Int = DEFAULT_SEARCH_LIMIT,
        offset: Int = 0,
    ): String {
        val facets = mutableListOf<String>()
        facets += "[\"project_type:mod\"]"
        loaderName(loader)?.let { facets += "[\"categories:" + it + "\"]" }
        gameVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { facets += "[\"versions:" + it + "\"]" }
        val sb = StringBuilder(BASE_URL)
        sb.append("/search?query=").append(encode(query))
        sb.append("&limit=").append(limit.coerceIn(1, 100))
        sb.append("&offset=").append(offset.coerceAtLeast(0))
        sb.append("&index=relevance")
        if (facets.isNotEmpty()) {
            sb.append("&facets=").append(encode("[" + facets.joinToString(",") + "]"))
        }
        return sb.toString()
    }

    /** 某个模组在指定游戏版本 + 加载器下的全部文件（Modrinth 默认按发布时间倒序）。 */
    fun versionsUrl(slug: String, gameVersion: String?, loader: LoaderKind): String {
        val sb = StringBuilder(BASE_URL)
        sb.append("/project/").append(encode(slug.trim())).append("/version")
        val params = mutableListOf<String>()
        gameVersion?.trim()?.takeIf { it.isNotEmpty() }?.let {
            params += "game_versions=" + encode("[\"" + it + "\"]")
        }
        loaderName(loader)?.let { params += "loaders=" + encode("[\"" + it + "\"]") }
        if (params.isNotEmpty()) sb.append("?").append(params.joinToString("&"))
        return sb.toString()
    }

    /**
     * 「这些文件是哪个模组的哪个版本」——检查更新用的批量查询。
     *
     * Modrinth 收 `{"hashes": [...], "algorithm": "sha1"}`，返回一个以 sha1 为键的对象。
     * 一次能问多少：官方文档没写死，实践中一两百个没问题；界面按 100 个一批发。
     */
    fun versionFilesUrl(): String = BASE_URL + "/version_files"

    /** 用 project_id 批量换 slug / 标题（缺依赖与检查更新都要把 id 变成人话）。 */
    fun projectsUrl(ids: List<String>): String {
        val clean = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return BASE_URL + "/projects?ids=" + encode("[" + clean.joinToString(",") { "\"" + it + "\"" } + "]")
    }

    /** 拿某个模组最新版本的查询串要带游戏版本与加载器，否则会挑到给别的版本编译的构建。 */
    fun latestVersionsUrl(slug: String, gameVersion: String?, loader: LoaderKind): String =
        versionsUrl(slug, gameVersion, loader)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseSearch(text: String): ModrinthParse<List<ModProject>> {
        if (text.isBlank()) return ModrinthParse.Failure("搜索结果是空的")
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ModrinthParse.Failure("搜索结果不是合法 JSON（可能是接口改版或被墙的提示页）")
        val hits = root["hits"] as? JsonArray
            ?: return ModrinthParse.Failure("搜索结果里没有 hits 字段")
        val projects = hits.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val slug = stringOf(obj, "slug") ?: return@mapNotNull null
            ModProject(
                slug = slug,
                title = stringOf(obj, "title") ?: slug,
                description = stringOf(obj, "description").orEmpty(),
                author = stringOf(obj, "author"),
                downloads = longOf(obj, "downloads") ?: 0L,
                projectId = stringOf(obj, "project_id").orEmpty(),
            )
        }
        return ModrinthParse.Ok(projects)
    }

    fun parseVersions(text: String, projectSlug: String): ModrinthParse<List<ModVersionFile>> {
        if (text.isBlank()) return ModrinthParse.Failure("版本列表是空的")
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
            ?: return ModrinthParse.Failure("版本列表不是合法 JSON")
        val versions = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val files = (obj["files"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            // 一个版本可能挂多个文件（比如 sources jar），只取 primary，没有就取第一个
            val file = files.firstOrNull { boolOf(it, "primary") == true } ?: files.firstOrNull()
                ?: return@mapNotNull null
            val fileName = stringOf(file, "filename") ?: return@mapNotNull null
            val url = stringOf(file, "url") ?: return@mapNotNull null
            val hashes = file["hashes"] as? JsonObject
            val deps = (obj["dependencies"] as? JsonArray)?.mapNotNull { dep ->
                val depObj = dep as? JsonObject ?: return@mapNotNull null
                if (stringOf(depObj, "dependency_type") != "required") return@mapNotNull null
                stringOf(depObj, "project_id")
            }.orEmpty()
            ModVersionFile(
                projectSlug = projectSlug,
                versionNumber = stringOf(obj, "version_number") ?: fileName,
                fileName = fileName,
                url = url,
                sha1 = hashes?.let { stringOf(it, "sha1") },
                size = longOf(file, "size") ?: -1L,
                releaseType = stringOf(obj, "version_type") ?: "release",
                requiredDependencies = deps,
            )
        }
        return ModrinthParse.Ok(versions)
    }


    /**
     * 解析 /version_files 的响应：以 sha1 为键的对象，缺的键就是 Modrinth 不认识这个文件。
     *
     * 一个 sha1 可能对上多个版本（不同加载器打成一样的包），这里取第一个 —— 检查更新只是
     * 「有没有更新的」，不需要列出全部命中。
     */
    fun parseVersionFiles(text: String): ModrinthParse<Map<String, ModVersionLookup>> {
        if (text.isBlank()) return ModrinthParse.Failure("反查结果是空的")
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ModrinthParse.Failure("反查结果不是合法 JSON")
        val result = mutableMapOf<String, ModVersionLookup>()
        for ((hash, element) in root) {
            val obj = element as? JsonObject ?: continue
            val projectId = stringOf(obj, "project_id") ?: continue
            val files = (obj["files"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val file = files.firstOrNull { boolOf(it, "primary") == true } ?: files.firstOrNull()
            result[hash.lowercase()] = ModVersionLookup(
                sha1 = hash.lowercase(),
                projectId = projectId,
                versionNumber = stringOf(obj, "version_number") ?: "",
                fileName = file?.let { stringOf(it, "filename") }.orEmpty(),
                gameVersions = stringListOf(obj, "game_versions"),
                loaders = stringListOf(obj, "loaders"),
            )
        }
        return ModrinthParse.Ok(result)
    }

    /** 解析 /projects?ids=[...]：返回 project_id → 项目。只有检查更新与缺依赖清单会用到。 */
    fun parseProjects(text: String): ModrinthParse<Map<String, ModProject>> {
        if (text.isBlank()) return ModrinthParse.Failure("项目列表是空的")
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
            ?: return ModrinthParse.Failure("项目列表不是合法 JSON")
        val result = mutableMapOf<String, ModProject>()
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val slug = stringOf(obj, "slug") ?: continue
            val id = stringOf(obj, "id") ?: stringOf(obj, "project_id") ?: continue
            result[id] = ModProject(
                slug = slug,
                title = stringOf(obj, "title") ?: slug,
                description = stringOf(obj, "description").orEmpty(),
                author = null,
                downloads = longOf(obj, "downloads") ?: 0L,
                projectId = id,
            )
        }
        return ModrinthParse.Ok(result)
    }

    private fun stringListOf(obj: JsonObject, key: String): List<String> =
        (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    /**
     * 从版本列表里挑一个该装的：优先正式版，其次 beta，最后 alpha。
     * Modrinth 返回顺序是新的在前，所以同类里取第一个即可。
     */
    fun pickBest(versions: List<ModVersionFile>): ModVersionFile? =
        versions.firstOrNull { it.releaseType == "release" }
            ?: versions.firstOrNull { it.releaseType == "beta" }
            ?: versions.firstOrNull()

    private fun stringOf(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun longOf(obj: JsonObject, key: String): Long? =
        (obj[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun boolOf(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    /**
     * 最小化的查询串编码。
     *
     * 不用 URLEncoder 的理由：它会把空格编成 +，而 Modrinth 的 facets 里空格无意义、
     * 中文搜索词编成 %XX 才是对的；这里手工只保留 RFC 3986 的非保留字符，其余全部 %XX。
     */
    fun encode(text: String): String {
        val sb = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt() and 0xFF
            val c = ch.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[ch shr 4]).append(HEX[ch and 0x0F])
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    /** 250402653 → 2.5 亿 / 250,402,653 太长，列表里放不下；万以下原样、万以上走中文单位。 */
    fun formatCount(value: Long): String = when {
        value >= 100_000_000L -> trimZero(value / 10_000_000L / 10.0) + " 亿"
        value >= 10_000L -> trimZero(value / 1_000L / 10.0) + " 万"
        else -> value.toString()
    }

    private fun trimZero(value: Double): String {
        val rounded = (value * 10).toLong() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }
}

/**
 * 一个「按 sha1 反查出来的版本」：本地这个文件在 Modrinth 上是哪个模组的哪个版本。
 *
 * 比 [ModVersionFile] 多了 [projectId] —— 检查更新时要靠它去问「这个模组最新是什么版本」。
 */
data class ModVersionLookup(
    val sha1: String,
    val projectId: String,
    val versionNumber: String,
    val fileName: String,
    val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
)

