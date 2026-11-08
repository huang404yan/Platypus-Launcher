package io.github.huang0yan4.platypus.core.download

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
        when {
            bodies.containsKey(path) -> respond(exchange, 200, bodies.getValue(path))
            path.startsWith("/mirror/") && bodies.containsKey(path.removePrefix("/mirror")) ->
                respond(exchange, 200, bodies.getValue(path.removePrefix("/mirror")))
            else -> respond(exchange, 404, "not found".toByteArray())
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, body: ByteArray) {
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
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

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
