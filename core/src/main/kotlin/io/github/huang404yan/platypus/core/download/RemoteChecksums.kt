package io.github.huang404yan.platypus.core.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 maven 仓库补全校验信息（sha1 + 体积）。
 *
 * 为什么需要这一步：Fabric 的 profile json 里，intermediary 与 fabric-loader 这两个库
 * 既没有 sha1 也没有 size（其余 6 个库都有）。少了它们会连着坏三件事：
 *   1. 下载器只能「下完就当成功」，包下坏了也发现不了；
 *   2. 进度条的分母少算这两个库（实测约 2.5 MB），百分比总是偏大；
 *   3. 目标文件已存在时无法判断能不能跳过——每次安装都会把这两个库重下一遍。
 *
 * 通道用 maven 的标准附属文件：任意构件 URL 后面加 ".sha1" 就是校验和（纯文本 40 位
 * 十六进制，有的仓库会带上文件名，所以按空白切第一段）。体积用 HEAD 读 Content-Length；
 * HEAD 被拒或没给长度时，退化成 "Range: bytes=0-0" 从 Content-Range 里取总长。
 *
 * 所有方法都不抛异常：拿不到就返回 null / -1，让下载器按原来的宽松逻辑走。
 * 补不到校验信息最多是少一层保险，不应该让整个安装失败。
 */
class RemoteChecksums(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {

    /** 拉 "<url>.sha1"；拿不到或不是合法 sha1 返回 null。 */
    fun sha1Of(url: String): String? = runCatching {
        val text = getText(url.trimEnd('/') + SHA1_SUFFIX) ?: return@runCatching null
        parseSha1(text)
    }.getOrNull()

    /** 探测远端文件体积；探测不出来返回 -1。 */
    fun sizeOf(url: String): Long = runCatching {
        headContentLength(url) ?: probeSizeByRange(url) ?: -1L
    }.getOrDefault(-1L)

    /**
     * 给缺项补 sha1 / size，已有的不覆盖。
     *
     * 只对真正缺的条目发请求：Mojang 的库自带 sha1 与 size，一个请求都不发。
     * 各条目的探测互不依赖，并发跑；单个条目失败只是留着原值。
     */
    suspend fun enrich(items: List<DownloadItem>): List<DownloadItem> = coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                val needSha1 = item.sha1.isNullOrBlank()
                val needSize = item.size < 0
                if (!needSha1 && !needSize) return@async item
                val sha1 = if (needSha1) sha1Of(item.url) else item.sha1
                val size = if (needSize) sizeOf(item.url) else item.size
                if (sha1 == item.sha1 && size == item.size) item else item.copy(sha1 = sha1, size = size)
            }
        }.awaitAll()
    }

    /**
     * 批量补全并回报「补上了几个」：UI 拿这些数字决定要不要显示一行说明。
     * stillMissing 是补完仍缺校验的条目数，0 表示全部条目都会强校验且能判重跳过。
     */
    suspend fun enrichWithStats(items: List<DownloadItem>): EnrichResult {
        val enriched = enrich(items)
        val filled = enriched.indices.count { i ->
            (items[i].sha1.isNullOrBlank() && !enriched[i].sha1.isNullOrBlank()) ||
                (items[i].size < 0 && enriched[i].size >= 0)
        }
        val stillMissing = enriched.count { it.sha1.isNullOrBlank() || it.size < 0 }
        return EnrichResult(items = enriched, filled = filled, stillMissing = stillMissing)
    }

    private fun getText(url: String): String? {
        val conn = open(url, "GET")
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun headContentLength(url: String): Long? {
        val conn = open(url, "HEAD")
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 用一字节 Range 探总长。
     *
     * 服务器认 Range 时回 206 + "Content-Range: bytes 0-0/总长"；
     * 不认 Range 时回 200 + 整份 Content-Length，两个都对，取哪个都能算出总长。
     * 这里只读响应头，读完立刻 disconnect，不会把整份文件拉下来。
     */
    private fun probeSizeByRange(url: String): Long? {
        val conn = open(url, "GET").apply {
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("Accept-Encoding", "identity")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val contentRange = conn.getHeaderField("Content-Range")
            if (contentRange != null) {
                val total = contentRange.substringAfterLast('/', "").trim().toLongOrNull()
                if (total != null && total > 0) return total
            }
            conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }

    companion object {
        const val SHA1_SUFFIX: String = ".sha1"

        private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MS = 20_000
        private const val DEFAULT_USER_AGENT =
            "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"

        /** 附属文件里可能只有 sha1，也可能跟着文件名；只认第一个像 sha1 的 token。 */
        fun parseSha1(text: String): String? {
            val token = text.trim().split(WHITESPACE).firstOrNull()?.lowercase() ?: return null
            if (token.length != 40) return null
            return token.takeIf { it.all(::isHex) }
        }

        private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'

        private val WHITESPACE = Regex("\\s+")
    }
}

/** [RemoteChecksums.enrichWithStats] 的结果。 */
data class EnrichResult(
    val items: List<DownloadItem>,
    /** 补上 sha1 或 size 的条目数。 */
    val filled: Int,
    /** 补完仍缺校验的条目数（0 表示全部条目都会强校验 + 能判重跳过）。 */
    val stillMissing: Int,
)
