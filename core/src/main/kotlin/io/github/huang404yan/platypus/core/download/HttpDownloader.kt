package io.github.huang404yan.platypus.core.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 基于 HttpURLConnection 的下载器。
 *
 * 选择 HttpURLConnection 而不是 OkHttp：Android 与桌面 JVM 都有，不引第三方依赖，
 * 而下载器的瓶颈在网络不在 HTTP 客户端。
 *
 * 行为约定：
 * - 先写 `<target>.part`，校验通过后才改名成 `<target>`，中途失败不会留下半个**目标**文件。
 * - **断点续传**：`.part` 里已有内容时发 `Range: bytes=<len>-`，拿到 206 就接着写；
 *   服务器忽略 Range 直接回 200 时自动回到从头写（不会把两段拼错）。
 * - **保留半成品的分寸**：网络异常、提前 EOF、HTTP 错误都**保留** `.part`，下次自动接着下；
 *   只有内容确定坏了（写多了、sha1 不符、416 明确拒绝、续传起点对不上）才删掉重来。
 * - `sha1` 非空时强校验；为空时只按 `size` 校验。续传时摘要会先补喂已有的字节，
 *   所以校验始终针对**整个文件**，不会只校验后半截。
 * - 目标文件已存在且校验通过则直接跳过，并顺手清掉同名的 `.part`。
 * - `mirrors` 在 `url` 失败后依次尝试，每个地址最多试 `retries + 1` 次。
 */
class HttpDownloader(
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val retries: Int = DEFAULT_RETRIES,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : Downloader {

    override suspend fun download(
        items: List<DownloadItem>,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadReport = coroutineScope {
        if (items.isEmpty()) return@coroutineScope DownloadReport(succeeded = 0, failures = emptyList())

        val totalFiles = items.size
        val totalBytes = items.sumOf { if (it.size > 0) it.size else 0L }
        val completedFiles = AtomicInteger(0)
        val completedBytes = AtomicLong(0)
        // 其中「接着上次没下完的部分」有多少：只是给界面一个「真的在续传」的凭据
        val resumedFiles = AtomicInteger(0)
        val resumedBytes = AtomicLong(0)
        val failures = Collections.synchronizedList(mutableListOf<DownloadFailure>())
        val lock = Any()
        var lastEmitAt = 0L

        fun emit(force: Boolean) {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                if (!force && now - lastEmitAt < EMIT_INTERVAL_MS) return
                lastEmitAt = now
                onProgress(
                    DownloadProgress(
                        completedFiles = completedFiles.get(),
                        totalFiles = totalFiles,
                        completedBytes = completedBytes.get(),
                        totalBytes = totalBytes,
                        resumedFiles = resumedFiles.get(),
                        resumedBytes = resumedBytes.get(),
                    ),
                )
            }
        }

        val gate = Semaphore(parallelism)
        emit(force = true)

        items.map { item ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    if (isUpToDate(item)) {
                        completedBytes.addAndGet(existingBytes(item))
                        completedFiles.incrementAndGet()
                        emit(force = true)
                        return@withPermit
                    }
                    val outcome = fetch(item) { delta ->
                        completedBytes.addAndGet(delta)
                        emit(force = false)
                    }
                    val reason = outcome.reason
                    if (reason == null) {
                        completedFiles.incrementAndGet()
                        if (outcome.resumedBytes > 0) {
                            resumedFiles.incrementAndGet()
                            resumedBytes.addAndGet(outcome.resumedBytes)
                        }
                    } else {
                        failures.add(DownloadFailure(item, reason))
                    }
                    emit(force = true)
                }
            }
        }.awaitAll()

        emit(force = true)
        DownloadReport(succeeded = completedFiles.get(), failures = failures.toList())
    }

    /** 目标已存在且能通过校验：省掉这次下载。顺手把没用的半成品清掉。 */
    private fun isUpToDate(item: DownloadItem): Boolean {
        val target = File(item.target)
        if (!target.isFile) return false
        val sha1 = item.sha1
        val ok = if (!sha1.isNullOrBlank()) {
            sha1Of(target).equals(sha1, ignoreCase = true)
        } else {
            item.size >= 0 && target.length() == item.size
        }
        if (ok) File(target.path + PART_SUFFIX).takeIf { it.isFile }?.delete()
        return ok
    }

    private fun existingBytes(item: DownloadItem): Long {
        val length = File(item.target).length()
        return if (length > 0) length else item.size.coerceAtLeast(0L)
    }

    /** 单个条目的下载结果：reason 为 null 表示成功，resumedBytes 表示这次有多少字节是接着上次下的。 */
    private data class FetchOutcome(val reason: String?, val resumedBytes: Long = 0L) {
        val ok: Boolean get() = reason == null
    }

    /**
     * 下载单个条目。
     *
     * [onBytes] 之外的副作用只有磁盘上的 `<target>.part` 与最终的目标文件。
     */
    private fun fetch(item: DownloadItem, onBytes: (Long) -> Unit): FetchOutcome {
        val target = File(item.target)
        target.parentFile?.let { parent ->
            try {
                // Files.createDirectories 是幂等的：并发下载时另一个线程刚把同名目录建好，
                // 这里也照样返回成功。不能用 "!isDirectory && !mkdirs()" —— mkdirs 在目录
                // 已存在时返回 false，会被误判成失败（t5 实测 assets/objects/6f 偶发失败）。
                Files.createDirectories(parent.toPath())
            } catch (e: Exception) {
                return FetchOutcome("无法创建目录：${parent.path}（${e.message ?: e.javaClass.simpleName}）")
            }
        }

        val candidates = buildList {
            if (item.url.isNotBlank()) add(item.url)
            addAll(item.mirrors.filter { it.isNotBlank() && it != item.url })
        }.distinct()
        if (candidates.isEmpty()) return FetchOutcome("没有可用的下载地址")

        var lastReason = "下载失败"
        for (candidate in candidates) {
            repeat(retries + 1) { attempt ->
                val outcome = attemptOnce(candidate, item, target, onBytes)
                if (outcome.ok) return outcome
                lastReason = if (attempt < retries) "${outcome.reason}（重试中）" else outcome.reason ?: "下载失败"
            }
        }
        return FetchOutcome(lastReason)
    }

    private fun attemptOnce(
        url: String,
        item: DownloadItem,
        target: File,
        onBytes: (Long) -> Unit,
    ): FetchOutcome {
        val part = File(target.path + PART_SUFFIX)

        // 上一轮留下的 .part：能续就续，坏了就清。
        var base = if (part.isFile) part.length() else 0L
        if (base > 0 && item.size >= 0) {
            if (base > item.size) {
                // 比目标还大，只能是坏的重下
                part.delete()
                base = 0
            } else if (base == item.size) {
                // 其实已经下完了，只是没来得及转正
                if (partMatches(part, item)) {
                    onBytes(item.size)
                    return FetchOutcome(promote(part, target), resumedBytes = item.size)
                }
                part.delete()
                base = 0
            }
        }

        var connection: HttpURLConnection? = null
        // 本次尝试报给上层的字节数，失败时按这个数原样退回
        var charged = 0L
        var received = 0L
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                // gzip 会让 Range 的字节偏移对不上，明示只要原始字节
                setRequestProperty("Accept-Encoding", "identity")
                if (base > 0) setRequestProperty("Range", "bytes=$base-")
            }
            connection = conn

            val code = conn.responseCode
            if (code !in 200..299) {
                return FetchOutcome(
                    when (code) {
                        HTTP_RANGE_NOT_SATISFIABLE -> {
                            part.delete()
                            "服务器拒绝了断点（HTTP 416），已清掉半成品，将从零重下"
                        }
                        else -> "HTTP $code"
                    },
                )
            }

            val append = code == HttpURLConnection.HTTP_PARTIAL && base > 0
            if (append) {
                val start = conn.getHeaderField("Content-Range")
                    ?.substringAfter("bytes ", "")
                    ?.substringBefore("-")
                    ?.trim()
                    ?.toLongOrNull()
                if (start != null && start != base) {
                    part.delete()
                    return FetchOutcome("续传起点对不上（要 $base，服务器给 $start），已清掉半成品")
                }
            } else if (base > 0) {
                // 没给 206：服务器要发整份，之前的半成品作废，从头写
                base = 0
            }

            val digest = MessageDigest.getInstance("SHA-1")
            if (append) {
                // 续传要拿到整个文件的 sha1，得先把磁盘上已有的字节补喂进摘要
                val fed = digestFile(part, digest)
                if (fed != base) {
                    part.delete()
                    return FetchOutcome("半成品读不全（期望 $base 字节，读到 $fed），已清掉")
                }
                charged += base
                // 续传时已落盘的字节也要报进度，否则进度条会突然从 0 跳到一半
                onBytes(base)
            }

            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        received += read
                        charged += read
                        onBytes(read.toLong())
                    }
                    output.fd.sync()
                }
            }

            val total = base + received
            if (item.size >= 0 && total != item.size) {
                if (total < item.size) {
                    // 提前 EOF：连接断了，但已经拿到的字节是好的，留着下次续
                    return FetchOutcome(
                        rollback(onBytes, charged, "下载中断：已收到 $total / ${item.size} 字节"),
                    )
                }
                part.delete()
                return FetchOutcome(rollback(onBytes, charged, "大小不符：期望 ${item.size}，实际 $total"))
            }

            val actualSha1 = digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            if (!item.sha1.isNullOrBlank() && !actualSha1.equals(item.sha1, ignoreCase = true)) {
                part.delete()
                return FetchOutcome(
                    rollback(onBytes, charged, "sha1 校验失败：期望 ${item.sha1}，实际 $actualSha1"),
                )
            }

            val promoteError = promote(part, target)
            if (promoteError != null) return FetchOutcome(rollback(onBytes, charged, promoteError))
            FetchOutcome(reason = null, resumedBytes = base)
        } catch (e: Exception) {
            // 网络类异常一律保留 .part：下次同一个文件会自动续上
            FetchOutcome(rollback(onBytes, charged, "下载失败：${e.message ?: e::class.simpleName}"))
        } finally {
            connection?.disconnect()
        }
    }

    /** 把 `.part` 转正成目标文件。成功返回 null，失败返回中文原因。 */
    private fun promote(part: File, target: File): String? {
        if (target.exists() && !target.delete()) {
            return "无法覆盖已有文件：${target.path}"
        }
        if (part.renameTo(target)) return null
        return try {
            part.copyTo(target, overwrite = true)
            part.delete()
            null
        } catch (e: Exception) {
            "写入目标文件失败：${e.message ?: e::class.simpleName}"
        }
    }

    private fun partMatches(part: File, item: DownloadItem): Boolean {
        if (item.size >= 0 && part.length() != item.size) return false
        return item.sha1.isNullOrBlank() || sha1Of(part).equals(item.sha1, ignoreCase = true)
    }

    /** 失败时把已经报给上层的字节数退回去，避免进度条虚高。 */
    private fun rollback(onBytes: (Long) -> Unit, charged: Long, reason: String): String {
        if (charged > 0) onBytes(-charged)
        return reason
    }

    /** 把文件内容喂进 [digest]，返回读到的字节数。 */
    private fun digestFile(file: File, digest: MessageDigest): Long {
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                total += read
            }
        }
        return total
    }

    private fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digestFile(file, digest)
        return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    companion object {
        const val DEFAULT_PARALLELISM: Int = 16
        const val DEFAULT_RETRIES: Int = 1

        private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MS = 60_000
        private const val EMIT_INTERVAL_MS = 80L
        private const val BUFFER_SIZE = 64 * 1024
        private const val PART_SUFFIX = ".part"

        /** HttpURLConnection 没有这个常量，416 自己写。 */
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        private const val DEFAULT_USER_AGENT =
            "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"
    }
}
