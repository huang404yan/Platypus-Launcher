package io.github.huang404yan.platypus.core.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.URI

/**
 * 在一条地址上实测一次：拿到多少字节、花了多少毫秒。
 *
 * 记的是「这一次」而不是长期成绩：镜像会按 IP 限速，早上与晚间的结果能差十倍，
 * 把历史平均存下来反而会骗人。用户点一次测速看一次，就够用了。
 */
data class ProbeResult(
    val url: String,
    val millis: Long,
    val bytes: Long,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null

    /** 每秒多少字节。没读到东西或没花时间时为 0，界面据此不画速度。 */
    val bytesPerSecond: Long
        get() = if (!ok || bytes <= 0L) 0L else bytes * 1000L / millis.coerceAtLeast(1L)
}

/**
 * 下载源测速。
 *
 * 为什么需要它：默认源是「优先国内镜像」，但不是每台机器、每个时段镜像都快 ——
 * 实测过镜像对本机 IP 短时限速之后，同一地址能从 8 秒掉到 200 毫秒。用户感觉「下载慢」
 * 时，能自己点一下看当前哪条路真的快，比在设置里盲试两个选项有用。
 *
 * 只读一小段：取 [DEFAULT_BYTES] 就断开。整份清单也就几百 KB，读全了既慢又没必要 ——
 * 要比较的是「这两条路的响应速度」，不是「这个文件能不能下完」。
 */
object SourceProbe {

    /** 每条地址读这么多字节就够判断快慢了。 */
    const val DEFAULT_BYTES: Int = 256 * 1024

    /** 单条地址的超时。测速是给人看的，卡住十几秒不如早说「这条不通」。 */
    const val DEFAULT_TIMEOUT_MS: Int = 6_000

    private const val USER_AGENT =
        "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"

    private const val READ_BUFFER = 16 * 1024

    /**
     * 并发测这几条地址。
     *
     * 并发而不是依次测：依次测的话第一条卡满 6 秒超时，后面几条还没开始，用户以为点了没反应。
     * 返回顺序与传入顺序一致 —— 界面按「官方源 / 镜像」两行摆，不能因为谁先返回就换位置。
     */
    suspend fun probe(
        urls: List<String>,
        bytes: Int = DEFAULT_BYTES,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): List<ProbeResult> = coroutineScope {
        urls.filter { it.isNotBlank() }.distinct()
            .map { url -> async(Dispatchers.IO) { probeOne(url, bytes, timeoutMs) } }
            .awaitAll()
    }

    /** 这几条里最快的一条；一条都没测成都返回 null。 */
    fun fastest(results: List<ProbeResult>): ProbeResult? =
        results.filter { it.ok && it.bytesPerSecond > 0L }.maxByOrNull { it.bytesPerSecond }

    /**
     * 按实测结果推荐一个源偏好。
     *
     * 最快的那条是镜像就选「优先国内镜像」，是官方就选「官方源优先」——
     * 正好对应设置里那两个选项，用户点一下就能把结论落成设置，不用自己对着数字想该点哪个。
     */
    fun suggestedSource(results: List<ProbeResult>): DownloadSource? {
        val best = fastest(results) ?: return null
        return if (DownloadSources.isMirror(best.url)) DownloadSource.MIRROR else DownloadSource.AUTO
    }

    /** 一行话：这条地址测出了什么。 */
    fun describe(result: ProbeResult): String {
        if (!result.ok) return "没测成：" + (result.error ?: "未知原因")
        if (result.bytes <= 0L) return "连上了但没读到数据"
        return formatBytes(result.bytes) + " / " + formatMillis(result.millis) +
            "（约 " + formatBytes(result.bytesPerSecond) + "/秒）"
    }

    private fun probeOne(url: String, bytes: Int, timeoutMs: Int): ProbeResult {
        val startedAt = System.nanoTime()
        fun elapsed(): Long = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
        var connection: HttpURLConnection? = null
        return try {
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                // gzip 会让 Range 的偏移对不上，也把「读了多少字节」算歪
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=0-" + (bytes - 1))
            }
            connection = conn
            val code = conn.responseCode
            if (code !in 200..299) {
                ProbeResult(url, elapsed(), 0L, "HTTP " + code)
            } else {
                var got = 0L
                conn.inputStream.use { input ->
                    val buffer = ByteArray(READ_BUFFER)
                    while (got < bytes) {
                        val want = minOf(buffer.size.toLong(), bytes - got).toInt()
                        val read = input.read(buffer, 0, want)
                        if (read < 0) break
                        got += read
                    }
                }
                ProbeResult(url, elapsed(), got)
            }
        } catch (e: Exception) {
            ProbeResult(url, elapsed(), 0L, e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> bytes.toString() + " B"
        bytes < 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    private fun formatMillis(millis: Long): String =
        if (millis < 1000L) millis.toString() + " 毫秒" else String.format(java.util.Locale.ROOT, "%.1f 秒", millis / 1000.0)
}