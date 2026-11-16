package io.github.huang404yan.platypus.core.update

import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.Endpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 一个发布包里能下载的东西。 */
data class ReleaseAsset(
    val name: String,
    val size: Long = 0L,
    val downloadUrl: String,
) {
    /** 小写扩展名（不含点）。 */
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** GitHub 上的一个 Release。 */
data class ReleaseInfo(
    val tagName: String,
    val name: String = "",
    val body: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val publishedAt: String = "",
    val htmlUrl: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
) {
    /** tag 去掉前缀 v 之后的版本号。 */
    val version: String get() = tagName.trim().removePrefix("v").removePrefix("V")

    /** 这个包里第一个扩展名匹配的资产。 */
    fun assetWith(extension: String): ReleaseAsset? {
        val wanted = extension.trim().removePrefix(".").lowercase()
        return assets.firstOrNull { it.extension == wanted }
    }
}

/** 一次检查的结果。 */
data class UpdateCheckResult(
    val currentVersion: String,
    /** 比自己新的那个发布；没有更新的就是 null。 */
    val latest: ReleaseInfo? = null,
    /** 候选里最新的一个（可能等于当前版本，也可能没有）。 */
    val newestSeen: ReleaseInfo? = null,
) {
    val hasUpdate: Boolean get() = latest != null
}

/**
 * 检查 GitHub 上有没有比自己新的版本。
 *
 * **只检查、不自动下载替换。** 自己替换正在运行的 exe 要处理文件占用、签名、
 * 半途失败回滚，失败了用户手里就是一个打不开的启动器 —— 这属于「宁可让他手动点两次，
 * 也不要替他冒险」的事。所以这里只回答「有没有新版、是哪一版、说明写在哪」，
 * 真正的安装交给用户点那个链接。
 *
 * 通道规则：孵化版（版本号带 -preview）才看 Pre-release，正式版不看。
 * 反过来把 Pre-release 推给正式版用户，等于让稳定版的人天天收到实验包提醒。
 */
object UpdateChecker {

    const val REPOSITORY: String = "huang404yan/Platypus-Launcher"

    /**
     * 一次拿 30 条：一个通道一年也发不到这么多，够挑最新了。
     *
     * 空壳包里是用户填的镜像根 + 同一段路径，没填就是空串 —— 检查更新会直接失败，
     * 但那只影响「有没有新版」的提示，不影响启动与游戏。
     */
    val RELEASES_URL: String
        get() = Endpoints.join(
            Endpoints.gitHubApi(),
            "/repos/" + REPOSITORY + "/releases?per_page=30",
        )

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private val USER_AGENT: String = "PlatypusLauncher/0.1 (+" + Brand.HOMEPAGE + ")"

    /**
     * 拉一个仓库的全部 Release 并挑出比 [currentVersion] 新的那个。
     *
     * 网络错误直接抛 IOException，由调用方决定怎么显示 —— 这里不吞异常，
     * 因为「检查失败」和「已经是最新」是两件必须分清的事。
     */
    suspend fun check(
        currentVersion: String = Brand.version,
        url: String = RELEASES_URL,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val releases = parseReleases(fetch(url))
        val newest = pickNewest(releases, currentVersion)
        UpdateCheckResult(
            currentVersion = currentVersion,
            latest = newest?.takeIf { compareVersions(it.version, currentVersion) > 0 },
            newestSeen = newest,
        )
    }

    /** GitHub 的 Release 数组。字段名与 API 一致，别的字段一律忽略。 */
    fun parseReleases(text: String): List<ReleaseInfo> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return root.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ReleaseInfo(
                tagName = tag,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                body = obj["body"]?.jsonPrimitive?.contentOrNull ?: "",
                prerelease = obj["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false,
                draft = obj["draft"]?.jsonPrimitive?.booleanOrNull ?: false,
                publishedAt = obj["published_at"]?.jsonPrimitive?.contentOrNull ?: "",
                htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: "",
                assets = (obj["assets"] as? JsonArray)?.mapNotNull { item ->
                    val asset = item as? JsonObject ?: return@mapNotNull null
                    val assetName = asset["name"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val download = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    ReleaseAsset(
                        name = assetName,
                        size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                        downloadUrl = download,
                    )
                }.orEmpty(),
            )
        }
    }

    /**
     * 从一堆发布里挑出这个通道上最新的一个。
     *
     * 跳过草稿（那是还没发出去的）；[includePrerelease] 为 false 时也跳过 Pre-release。
     * 版本号认不出来的（比如 tag 写的是日期）直接跳过，比错认成「有新版」强。
     */
    fun pickNewest(
        releases: List<ReleaseInfo>,
        currentVersion: String,
        includePrerelease: Boolean = currentVersion.contains("-preview"),
    ): ReleaseInfo? = releases
        .asSequence()
        .filter { !it.draft }
        .filter { includePrerelease || !it.prerelease }
        .filter { it.version.firstOrNull()?.isDigit() == true }
        .maxWithOrNull { left, right -> compareVersions(left.version, right.version) }

    /**
     * 比较两个版本号：负数表示 [left] 更旧，0 表示相同，正数表示更新。
     *
     * 规则按 0.x-preview.N 这套实际在用的号来：先比点分的数字段，位数不够的补 0；
     * 数字段一样时，**没有预发布后缀的更大**（0.8.0 > 0.8.0-preview.20）；
     * 两边都有后缀就按 token 逐段比，都是数字按数值比，否则按字符串比；
     * 一边 token 用完了算更小（preview < preview.1）。
     */
    fun compareVersions(left: String, right: String): Int {
        val a = splitVersion(left)
        val b = splitVersion(right)
        val numberCount = maxOf(a.numbers.size, b.numbers.size)
        for (index in 0 until numberCount) {
            val diff = a.numbers.getOrElse(index) { 0 }.compareTo(b.numbers.getOrElse(index) { 0 })
            if (diff != 0) return diff
        }
        val leftTail = a.tail
        val rightTail = b.tail
        if (leftTail.isEmpty() && rightTail.isEmpty()) return 0
        // 正式版比同一号的预发布版新。
        if (leftTail.isEmpty()) return 1
        if (rightTail.isEmpty()) return -1
        val tailCount = minOf(leftTail.size, rightTail.size)
        for (index in 0 until tailCount) {
            val diff = compareToken(leftTail[index], rightTail[index])
            if (diff != 0) return diff
        }
        return leftTail.size.compareTo(rightTail.size)
    }

    private fun compareToken(left: String, right: String): Int {
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            left.compareTo(right)
        }
    }

    private data class VersionParts(val numbers: List<Int>, val tail: List<String>)

    private fun splitVersion(raw: String): VersionParts {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return VersionParts(emptyList(), emptyList())
        val core = cleaned.substringBefore('-')
        val rest = if (cleaned.contains('-')) cleaned.substringAfter('-') else ""
        val numbers = core.split('.').map { it.toIntOrNull() ?: 0 }
        val tail = if (rest.isBlank()) {
            emptyList()
        } else {
            rest.split('.', '-', '_').filter { it.isNotBlank() }
        }
        return VersionParts(numbers, tail)
    }

    private fun fetch(url: String): String {
        if (url.isBlank()) throw IOException(Endpoints.SHELL_HINT)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw IOException("GitHub 上没有这个仓库的发布页（404）。")
            }
            if (code == 403 || code == 429) {
                throw IOException("GitHub 把这个 IP 的请求限流了（" + code + "），过一会儿再试。")
            }
            if (code !in 200..299) throw IOException("GitHub 返回 " + code + "。")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }
}