package io.github.huang404yan.platypus.core.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpDownloaderTest {

    private lateinit var server: HttpServer
    private lateinit var tmpDir: File
    private val hits = AtomicInteger(0)

    private val bodies: Map<String, ByteArray> = mapOf(
        "/a.txt" to "alpha-content".toByteArray(),
        "/b.txt" to ByteArray(200 * 1024) { (it % 251).toByte() },
        "/c.txt" to "gamma".toByteArray(),
    )

    private val downloader = HttpDownloader(parallelism = 4, retries = 0)

    /** rangeSupport = false 时模拟「服务器不认 Range，永远回整份」的老式源。 */
    private var rangeSupport = true

    /** 非空时，处理该路径会先返回这么多字节就断开连接，模拟下载到一半断网。 */
    private val cutOffAfter = mutableMapOf<String, Int>()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        tmpDir = kotlin.io.path.createTempDirectory("platypus-dl-").toFile()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        tmpDir.deleteRecursively()
    }

    private fun handle(exchange: HttpExchange) {
        hits.incrementAndGet()
        val path = exchange.requestURI.path
        val body = when {
            bodies.containsKey(path) -> bodies.getValue(path)
            path.startsWith("/mirror/") && bodies.containsKey(path.removePrefix("/mirror")) ->
                bodies.getValue(path.removePrefix("/mirror"))
            else -> null
        }
        if (body == null) {
            respond(exchange, 404, "not found".toByteArray())
            return
        }
        respondRangeAware(exchange, path, body)
    }

    private fun respond(exchange: HttpExchange, code: Int, body: ByteArray) {
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    /** 认 Range 的源：带 `Range: bytes=<n>-` 就回 206 + Content-Range，否则回整份 200。 */
    private fun respondRangeAware(exchange: HttpExchange, path: String, body: ByteArray) {
        val range = exchange.requestHeaders.getFirst("Range")
        val start = if (rangeSupport && range != null && range.startsWith("bytes=")) {
            range.removePrefix("bytes=").substringBefore("-").trim().toLongOrNull()
        } else {
            null
        }

        if (start != null) {
            if (start >= body.size) {
                exchange.responseHeaders.add("Content-Range", "bytes */${body.size}")
                respond(exchange, 416, ByteArray(0))
                return
            }
            val slice = body.copyOfRange(start.toInt(), body.size)
            exchange.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
            send(exchange, 206, slice, path)
            return
        }
        send(exchange, 200, body, path)
    }

    /** 走 chunked 发送，这样 [cutOffAfter] 截断连接时客户端会看到「提前 EOF」而不是「长度不符」。 */
    private fun send(exchange: HttpExchange, code: Int, body: ByteArray, path: String) {
        val cut = cutOffAfter.remove(path)
        if (cut == null) {
            respond(exchange, code, body)
            return
        }
        val partial = body.copyOfRange(0, minOf(cut, body.size))
        exchange.sendResponseHeaders(code, 0) // 0 = chunked，长度由连接关闭决定
        try {
            exchange.responseBody.use { it.write(partial) }
        } catch (_: Exception) {
            // 客户端可能已经先断开，测试不关心
        }
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun target(name: String) = File(tmpDir, name).path

    @Test
    fun `downloads files and reports success`() = runBlocking {
        val items = listOf(
            DownloadItem(url("/a.txt"), target("a.txt"), sha1 = sha1(bodies.getValue("/a.txt")), size = bodies.getValue("/a.txt").size.toLong()),
            DownloadItem(url("/b.txt"), target("b.txt"), sha1 = sha1(bodies.getValue("/b.txt")), size = bodies.getValue("/b.txt").size.toLong()),
            DownloadItem(url("/c.txt"), target("c.txt"), size = bodies.getValue("/c.txt").size.toLong()),
        )

        var lastProgress: DownloadProgress? = null
        val report = downloader.download(items) { lastProgress = it }

        assertTrue(report.ok, "不应有失败：${report.failures}")
        assertEquals(3, report.succeeded)
        assertEquals("alpha-content", File(target("a.txt")).readText())
        assertTrue(File(target("b.txt")).readBytes().contentEquals(bodies.getValue("/b.txt")))
        assertEquals("gamma", File(target("c.txt")).readText())
        assertFalse(File(target("a.txt") + ".part").exists(), "不应残留 .part")
        assertEquals(3, lastProgress?.completedFiles)
        assertEquals(3, lastProgress?.totalFiles)
    }

    @Test
    fun `skips file that already matches sha1`() = runBlocking {
        val bytes = bodies.getValue("/a.txt")
        val file = File(target("a.txt")).apply { writeBytes(bytes) }
        val before = hits.get()

        val report = downloader.download(
            listOf(DownloadItem(url("/a.txt"), file.path, sha1 = sha1(bytes), size = bytes.size.toLong())),
        )

        assertTrue(report.ok)
        assertEquals(1, report.succeeded)
        assertEquals(before, hits.get(), "已存在的文件不应再发请求")
    }

    @Test
    fun `reports http error`() = runBlocking {
        val report = downloader.download(listOf(DownloadItem(url("/missing.txt"), target("missing.txt"))))

        assertFalse(report.ok)
        assertEquals(1, report.failures.size)
        assertTrue(report.failures.first().reason.contains("404"), report.failures.first().reason)
        assertFalse(File(target("missing.txt")).exists())
    }

    @Test
    fun `rejects wrong sha1 and leaves no target`() = runBlocking {
        val file = File(target("a.txt"))
        val report = downloader.download(
            listOf(DownloadItem(url("/a.txt"), file.path, sha1 = "0000000000000000000000000000000000000000")),
        )

        assertFalse(report.ok)
        assertTrue(report.failures.first().reason.contains("sha1"), report.failures.first().reason)
        assertFalse(file.exists(), "校验失败不应落盘")
        assertFalse(File(file.path + ".part").exists(), "不应残留 .part")
    }

    @Test
    fun `falls back to mirror when primary url fails`() = runBlocking {
        val bytes = bodies.getValue("/a.txt")
        val report = downloader.download(
            listOf(
                DownloadItem(
                    url = url("/missing.txt"),
                    target = target("mirrored.txt"),
                    sha1 = sha1(bytes),
                    size = bytes.size.toLong(),
                    mirrors = listOf(url("/mirror/a.txt")),
                ),
            ),
        )

        assertTrue(report.ok, "镜像应成功：${report.failures}")
        assertEquals("alpha-content", File(target("mirrored.txt")).readText())
    }

    @Test
    fun `并发下载到同一个不存在的目录不会把后到的线程判成失败`() = runBlocking {
        val bytes = bodies.getValue("/a.txt")
        // 同一个深层目录下的 16 个文件：目录一开始不存在，谁先到谁建。
        val deep = File(tmpDir, "assets/objects/6f")
        val items = (0 until 16).map { index ->
            DownloadItem(
                url = url("/a.txt"),
                target = File(deep, "file-$index.bin").path,
                sha1 = sha1(bytes),
                size = bytes.size.toLong(),
            )
        }

        val report = downloader.download(items) {}

        assertTrue(report.ok, "并发建目录不应失败：${report.failures}")
        assertEquals(16, report.succeeded)
        assertTrue(deep.isDirectory)
        assertEquals(16, deep.listFiles()?.size ?: 0)
    }

    @Test
    fun `目标目录已经存在时不会报无法创建目录`() = runBlocking {
        val bytes = bodies.getValue("/a.txt")
        val deep = File(tmpDir, "assets/objects/6f").apply { mkdirs() }
        val report = downloader.download(
            listOf(
                DownloadItem(
                    url = url("/a.txt"),
                    target = File(deep, "already-there.bin").path,
                    sha1 = sha1(bytes),
                    size = bytes.size.toLong(),
                ),
            ),
        )

        assertTrue(report.ok, "目录已存在应视为成功：${report.failures}")
        assertEquals("alpha-content", File(deep, "already-there.bin").readText())
    }

    @Test
    fun `续传：已有半成品时只请求剩下的部分`() = runBlocking {
        val bytes = bodies.getValue("/b.txt")
        val part = File(target("b.txt") + ".part")
        // 先塞进前 100 KB，模拟上一次下到一半
        part.writeBytes(bytes.copyOfRange(0, 100 * 1024))
        val before = hits.get()

        var progress = 0L
        val report = downloader.download(
            listOf(DownloadItem(url("/b.txt"), target("b.txt"), sha1 = sha1(bytes), size = bytes.size.toLong())),
        ) { progress = it.completedBytes }

        assertTrue(report.ok, "续传应成功：${report.failures}")
        assertTrue(File(target("b.txt")).readBytes().contentEquals(bytes), "续传结果必须与原文一致")
        assertFalse(part.exists(), "转正后不应残留 .part")
        assertEquals(before + 1, hits.get(), "续传应该只发一次 GET（取剩余部分），不是整份重下")
        assertEquals(bytes.size.toLong(), progress, "进度应报完整大小")
    }

    @Test
    fun `续传：服务器不认 Range 时自动从头重下且结果正确`() = runBlocking {
        rangeSupport = false
        val bytes = bodies.getValue("/b.txt")
        File(target("b.txt") + ".part").writeBytes(bytes.copyOfRange(0, 100 * 1024))

        val report = downloader.download(
            listOf(DownloadItem(url("/b.txt"), target("b.txt"), sha1 = sha1(bytes), size = bytes.size.toLong())),
        )

        assertTrue(report.ok, "服务器忽略 Range 时也必须成功：${report.failures}")
        assertTrue(
            File(target("b.txt")).readBytes().contentEquals(bytes),
            "忽略 Range 时不能把整份内容接到半成品后面，那会变成两倍的错误文件",
        )
    }

    @Test
    fun `续传：半成品比目标还大时清掉重下`() = runBlocking {
        val bytes = bodies.getValue("/a.txt")
        File(target("a.txt") + ".part").writeBytes(bytes + ByteArray(64) { 1 })

        val report = downloader.download(
            listOf(DownloadItem(url("/a.txt"), target("a.txt"), sha1 = sha1(bytes), size = bytes.size.toLong())),
        )

        assertTrue(report.ok, report.failures.toString())
        assertEquals("alpha-content", File(target("a.txt")).readText())
    }

    @Test
    fun `续传：416 时清掉半成品并从零成功`() = runBlocking {
        val bytes = bodies.getValue("/a.txt")
        // 服务器认为文件只有这么长，而 .part 声称已下满
        File(target("a.txt") + ".part").writeBytes(ByteArray(bytes.size) { 9 })

        val report = downloader.download(
            listOf(DownloadItem(url("/a.txt"), target("a.txt"), sha1 = sha1(bytes), size = bytes.size.toLong())),
        )

        assertTrue(report.ok, report.failures.toString())
        assertEquals("alpha-content", File(target("a.txt")).readText())
    }

    @Test
    fun `断网后保留半成品，第二次调用能接着下完`() = runBlocking {
        val bytes = bodies.getValue("/b.txt")
        // 第一次只发 80 KB 就断开
        cutOffAfter["/b.txt"] = 80 * 1024

        val first = downloader.download(
            listOf(DownloadItem(url("/b.txt"), target("b.txt"), sha1 = sha1(bytes), size = bytes.size.toLong())),
        )
        assertFalse(first.ok, "这一轮应该失败")
        assertFalse(File(target("b.txt")).exists(), "失败时不应留下目标文件")
        val partAfterFirst = File(target("b.txt") + ".part")
        assertTrue(partAfterFirst.isFile, "断网应保留 .part 供下次续传")
        val kept = partAfterFirst.length()
        assertTrue(kept in 1..bytes.size.toLong(), "半成品大小应在 (0, 总长] 之间，实际 $kept")

        // 第二次：网络恢复，应当只补剩下的部分
        val before = hits.get()
        val second = downloader.download(
            listOf(DownloadItem(url("/b.txt"), target("b.txt"), sha1 = sha1(bytes), size = bytes.size.toLong())),
        )
        assertTrue(second.ok, "续传应成功：${second.failures}")
        assertTrue(File(target("b.txt")).readBytes().contentEquals(bytes), "续传后内容必须完整正确")
        assertTrue(hits.get() - before <= 2, "第二次只应发一次请求（最多两次，含重定向）")
    }

    @Test
    fun `目标文件已存在时顺手清掉同名 part 文件`() = runBlocking {
        val bytes = bodies.getValue("/a.txt")
        File(target("a.txt")).writeBytes(bytes)
        val part = File(target("a.txt") + ".part").apply { writeBytes(ByteArray(10)) }

        val report = downloader.download(
            listOf(DownloadItem(url("/a.txt"), target("a.txt"), sha1 = sha1(bytes), size = bytes.size.toLong())),
        )

        assertTrue(report.ok)
        assertFalse(part.exists(), "目标已完成时 .part 是垃圾，应清掉")
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
