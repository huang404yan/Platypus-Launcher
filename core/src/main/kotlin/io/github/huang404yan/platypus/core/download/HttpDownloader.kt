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
 * - 先写 `<target>.part`，校验通过后才改名成 `<target>`，中途失败不会留下半个目标文件。
 * - `sha1` 非空时强校验；为空时只按 `size` 校验。
 * - 目标文件已存在且校验通过则直接跳过（断点重下时省流量）。
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
                    val reason = fetch(item) { delta ->
                        completedBytes.addAndGet(delta)
                        emit(force = false)
                    }
                    if (reason == null) {
                        completedFiles.incrementAndGet()
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

    /** 目标已存在且能通过校验：省掉这次下载。 */
    private fun isUpToDate(item: DownloadItem): Boolean {
        val target = File(item.target)
        if (!target.isFile) return false
        val sha1 = item.sha1
        if (!sha1.isNullOrBlank()) return sha1Of(target).equals(sha1, ignoreCase = true)
        return item.size >= 0 && target.length() == item.size
    }

    private fun existingBytes(item: DownloadItem): Long {
        val length = File(item.target).length()
        return if (length > 0) length else item.size.coerceAtLeast(0L)
    }

    /**
     * 下载单个条目，成功返回 null，失败返回中文原因。
     *
     * [onBytes] 之外的副作用只有磁盘上的 `<target>.part` 与最终的目标文件。
     */
    private fun fetch(item: DownloadItem, onBytes: (Long) -> Unit): String? {
        val target = File(item.target)
        target.parentFile?.let { parent ->
            try {
                // Files.createDirectories 是幂等的：并发下载时另一个线程刚把同名目录建好，
                // 这里也照样返回成功。不能用 "!isDirectory && !mkdirs()" —— mkdirs 在目录
                // 已存在时返回 false，会被误判成失败（t5 实测 assets/objects/6f 偶发失败）。
                Files.createDirectories(parent.toPath())
            } catch (e: Exception) {
                return "无法创建目录：${parent.path}（${e.message ?: e.javaClass.simpleName}）"
            }
        }

        val candidates = buildList {
            if (item.url.isNotBlank()) add(item.url)
            addAll(item.mirrors.filter { it.isNotBlank() && it != item.url })
        }.distinct()
        if (candidates.isEmpty()) return "没有可用的下载地址"

        var lastReason = "下载失败"
        for (candidate in candidates) {
            repeat(retries + 1) { attempt ->
                val result = attemptOnce(candidate, item, target, onBytes)
                if (result == null) return null
                lastReason = if (attempt < retries) "$result（重试中）" else result
            }
        }
        return lastReason
    }

    private fun attemptOnce(
        url: String,
        item: DownloadItem,
        target: File,
        onBytes: (Long) -> Unit,
    ): String? {
        val part = File(target.path + PART_SUFFIX)
        part.delete()
        var connection: HttpURLConnection? = null
        var received = 0L
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
            }
            val code = connection.responseCode
            if (code !in 200..299) return "HTTP $code"

            val digest = MessageDigest.getInstance("SHA-1")
            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        received += read
                        onBytes(read.toLong())
                    }
                    output.fd.sync()
                }
            }

            if (item.size >= 0 && received != item.size) {
                return rollback(onBytes, received, "大小不符：期望 ${item.size}，实际 $received")
            }
            val actualSha1 = digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            if (!item.sha1.isNullOrBlank() && !actualSha1.equals(item.sha1, ignoreCase = true)) {
                return rollback(
                    onBytes,
                    received,
                    "sha1 校验失败：期望 ${item.sha1}，实际 $actualSha1",
                )
            }

            if (target.exists() && !target.delete()) {
                return rollback(onBytes, received, "无法覆盖已有文件：${target.path}")
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            null
        } catch (e: Exception) {
            rollback(onBytes, received, "下载失败：${e.message ?: e::class.simpleName}")
        } finally {
            connection?.disconnect()
            if (part.exists()) part.delete()
        }
    }

    /** 失败时把已经报给上层的字节数退回去，避免进度条虚高。 */
    private fun rollback(onBytes: (Long) -> Unit, received: Long, reason: String): String {
        if (received > 0) onBytes(-received)
        return reason
    }

    private fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
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

        private const val DEFAULT_USER_AGENT =
            "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"
    }
}
