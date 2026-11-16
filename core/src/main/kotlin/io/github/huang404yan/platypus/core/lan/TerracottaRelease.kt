package io.github.huang404yan.platypus.core.lan

import io.github.huang404yan.platypus.core.Endpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 官方发行版里那个能用的包：哪一个版本、文件名、下载地址、大小、校验值。
 *
 * 为什么要有这么一层：内置的 [Terracotta.VERSION] 是一块写死的常量，官方一旦升级，
 * 我们不动代码就永远只能给别人装老版本。改成「启动时问一句 GitHub 上最新的 tag 是哪个」，
 * 版本号就自己跟着走了 —— 用户点「安装 / 更新陶瓦」拿到的总是最新版。
 *
 * [sha256] 来自 GitHub API 的 `digest` 字段（形如 `sha256:07eb...`）。GitHub 从 2025 年起
 * 对 release 资产直接给这个值，就不必自己算一遍再写死进仓库。老 release 可能没有这一项，
 * 那么它是 null，交给调用方决定怎么兜底。
 */
data class TerracottaRelease(
    /** 官方 tag，带 v 前缀，例如 `v0.4.2`。 */
    val tag: String,
    /** 去掉 v 的版本号，例如 `0.4.2`。 */
    val version: String,
    /** 资产文件名，例如 `terracotta-0.4.2-windows-x86_64-pkg.tar.gz`。 */
    val assetName: String,
    /** 下载地址。优先用 API 给的 browser_download_url，没有就按官方规则拼一条。 */
    val downloadUrl: String,
    /** 资产字节数。API 没给时是 -1。 */
    val sizeBytes: Long,
    /** 官方给的 SHA-256（64 位小写十六进制）；没有就是 null。 */
    val sha256: String?,
) {
    /** 这个版本是不是正好是内置的那一版 —— 是的话可以用内置 SHA-512 做更严的校验。 */
    val isBuiltinVersion: Boolean get() = version == Terracotta.VERSION
}

/**
 * 查官方最新发行版。
 *
 * 这里只有「查」，下载与校验在 [TerracottaInstaller]。分成两半是为了：
 * ① JSON 解析可以拿固定样本离线测，不用联网；
 * ② 查失败时调用方可以安静地退回内置版本，而不是把整个安装流程判死。
 */
object TerracottaReleases {

    const val OWNER: String = "burningtnt"
    const val REPO: String = "Terracotta"

    /** 我们只会用 windows-x86_64 这个包；macOS / Linux 包在同一个 release 里，挑的时候要看名字。 */
    private const val ASSET_SUFFIX: String = "-windows-x86_64-pkg.tar.gz"

    private const val CONNECT_TIMEOUT_MS: Int = 10_000
    private const val READ_TIMEOUT_MS: Int = 15_000

    private val JSON = Json { ignoreUnknownKeys = true }

    /** `terracotta-0.4.2-windows-x86_64-pkg.tar.gz` 这种。 */
    fun assetNameOf(version: String): String = "terracotta-" + version + ASSET_SUFFIX

    /** 官方 release 里资产的地址规则。API 没给 browser_download_url 时按这个拼。 */
    fun officialUrl(tag: String, assetName: String): String =
        "https://github.com/" + OWNER + "/" + REPO + "/releases/download/" + tag + "/" + assetName

    /**
     * 查最新版的接口地址。
     *
     * 走 [Endpoints.gitHubApi] 而不是写死 api.github.com：空壳包里这个根会被换成一个镜像，
     * 直接写死等于在空壳包里偷偷发一个不该发的请求。根为空（空壳没填）时返回空串，调用方据此跳过。
     */
    fun latestApiUrl(): String {
        val root = Endpoints.gitHubApi().trimEnd('/')
        if (root.isEmpty()) return ""
        return root + "/repos/" + OWNER + "/" + REPO + "/releases/latest"
    }

    /**
     * 解析 `/releases/latest` 的响应。
     *
     * 解析不出来一律抛 [IOException] 并把原因写清楚 —— 静默返回一个「默认版本」会让
     * 界面显示「正在下载 0.4.2」而实际上传了个谁也不知道的东西。
     */
    fun parse(json: String): TerracottaRelease {
        val root = runCatching { JSON.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: throw IOException("GitHub 返回的不是一个 JSON 对象，可能被中间层改过。")
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (tag.isEmpty()) throw IOException("GitHub 的返回里没有 tag_name。")
        val version = tag.removePrefix("v").removePrefix("V")
        if (version.isEmpty()) throw IOException("GitHub 给的 tag 认不出版本号：" + tag)

        val assets = root["assets"] as? JsonArray ?: JsonArray(emptyList())
        val asset = assets.asSequence()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { obj ->
                val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                name.endsWith(ASSET_SUFFIX, ignoreCase = true)
            }
            ?: throw IOException("发行版 " + tag + " 里没有 windows-x86_64 的包，可能这次只发了一部分平台。")

        val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: officialUrl(tag, name)
        val size = asset["size"]?.jsonPrimitive?.longOrNull ?: -1L
        val sha256 = parseDigest(asset["digest"]?.jsonPrimitive?.contentOrNull)
        return TerracottaRelease(
            tag = tag,
            version = version,
            assetName = name,
            downloadUrl = url,
            sizeBytes = size,
            sha256 = sha256,
        )
    }

    /**
     * 把 API 的 `sha256:xxxx` 拆出 64 位小写十六进制；格式不对返回 null。
     *
     * 返回 null 与「没有 digest」是一个意思：调用方会退到别的校验方式，
     * 而不是拿着一个残缺的值去比 —— 那样必然不等，报错还看不明白。
     */
    internal fun parseDigest(digest: String?): String? {
        val raw = digest?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val value = raw.substringAfter(':', raw).trim().lowercase()
        if (value.length != 64) return null
        if (!value.all { it in "0123456789abcdef" }) return null
        if (!raw.lowercase().startsWith("sha256:")) return null
        return value
    }

    /**
     * 联网查最新版。空壳包没填镜像根时直接抛 —— 由 [TerracottaInstaller] 退回内置版本。
     */
    suspend fun fetchLatest(): TerracottaRelease = withContext(Dispatchers.IO) {
        val url = latestApiUrl()
        if (url.isBlank()) {
            throw IOException("这个包没配 GitHub 地址，跳过在线检查。")
        }
        val text = ((URL(url).openConnection()) as HttpURLConnection).run {
            try {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
                when (responseCode) {
                    200 -> inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    403, 429 -> throw IOException(
                        "GitHub 把这个 IP 的请求限流了（" + responseCode + "），过一会儿再试。",
                    )
                    404 -> throw IOException("GitHub 上找不到 Terracotta 的发布页（404）。")
                    else -> throw IOException("GitHub 返回 " + responseCode + "。")
                }
            } finally {
                disconnect()
            }
        }
        parse(text)
    }

    private const val USER_AGENT: String = "Platypus-Launcher"
}
