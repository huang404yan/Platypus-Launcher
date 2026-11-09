package io.github.huang404yan.platypus.core.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteChecksumsTest {

    private lateinit var server: HttpServer
    private val hits = AtomicInteger(0)

    /** 每个可下载路径对应的正文字节。 */
    private val bodies: Map<String, ByteArray> = mapOf(
        "/lib/a.jar" to ByteArray(1024) { (it % 97).toByte() },
        "/lib/b.jar" to ByteArray(2048) { (it % 89).toByte() },
    )

    private val sha1s: Map<String, String> = bodies.mapValues { (_, bytes) -> sha1(bytes) }

    /** 关掉 HEAD 支持，逼它走 Range 探测。 */
    private var headSupported = true

    /** 关掉 Range 支持，模拟只回整份的服务器。 */
    private var rangeSupported = true

    /** 非空时，这些路径的 .sha1 返回 404。 */
    private val missingSha1 = mutableSetOf<String>()

    /** 非空时，.sha1 里带文件名后缀（pom 与 sha1 同目录时部分仓库会这么写）。 */
    private var sha1WithFileName = false

    private val checksums = RemoteChecksums(connectTimeoutMs = 5000, readTimeoutMs = 5000)

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:" + server.address.port + path

    private fun handle(exchange: HttpExchange) {
        hits.incrementAndGet()
        val path = exchange.requestURI.path
        val method = exchange.requestMethod

        if (path.endsWith(RemoteChecksums.SHA1_SUFFIX)) {
            val artifact = path.removeSuffix(RemoteChecksums.SHA1_SUFFIX)
            val sum = sha1s[artifact]
            if (sum == null || artifact in missingSha1) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return
            }
            val body = (if (sha1WithFileName) sum + "  " + artifact.substringAfterLast('/') else sum)
                .toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            return
        }

        val body = bodies[path]
        if (body == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        if (method == "HEAD") {
            if (!headSupported) {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return
            }
            exchange.responseHeaders.add("Content-Length", body.size.toString())
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
            return
        }

        val range = exchange.requestHeaders.getFirst("Range")
        if (rangeSupported && range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").trim()
            val start = spec.substringBefore('-').toLong()
            val end = spec.substringAfter('-').toLong()
            val slice = body.copyOfRange(start.toInt(), (end + 1).toInt().coerceAtMost(body.size))
            exchange.responseHeaders.add("Content-Range", "bytes " + start + "-" + end + "/" + body.size)
            exchange.sendResponseHeaders(206, slice.size.toLong())
            exchange.responseBody.use { it.write(slice) }
            return
        }
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    @Test
    fun `sha1 附属文件能拉下来并解析`() {
        assertEquals(sha1s["/lib/a.jar"], checksums.sha1Of(url("/lib/a.jar")))
    }

    @Test
    fun `附属文件带文件名时也能解析出 sha1`() {
        sha1WithFileName = true
        assertEquals(sha1s["/lib/a.jar"], checksums.sha1Of(url("/lib/a.jar")))
    }

    @Test
    fun `没有附属文件时返回 null 不抛异常`() {
        missingSha1 += "/lib/a.jar"
        assertNull(checksums.sha1Of(url("/lib/a.jar")))
    }

    @Test
    fun `HEAD 能给体积`() {
        assertEquals(1024L, checksums.sizeOf(url("/lib/a.jar")))
    }

    @Test
    fun `HEAD 被拒时退化成 Range 探测体积`() {
        headSupported = false
        assertEquals(2048L, checksums.sizeOf(url("/lib/b.jar")))
    }

    @Test
    fun `HEAD 与 Range 都不认时按整份 Content-Length 算体积`() {
        headSupported = false
        rangeSupported = false
        assertEquals(1024L, checksums.sizeOf(url("/lib/a.jar")))
    }

    @Test
    fun `不存在的构件返回 -1`() {
        assertEquals(-1L, checksums.sizeOf(url("/lib/none.jar")))
    }

    @Test
    fun `只给缺的条目发请求 已带 sha1 与 size 的不动`() = runBlocking {
        val items = listOf(
            DownloadItem(url = url("/lib/a.jar"), target = "/tmp/a.jar", sha1 = null, size = -1),
            DownloadItem(url = url("/lib/b.jar"), target = "/tmp/b.jar", sha1 = "已有", size = 2048),
        )
        val before = hits.get()
        val result = checksums.enrichWithStats(items)
        assertEquals(sha1s["/lib/a.jar"], result.items[0].sha1)
        assertEquals(1024L, result.items[0].size)
        // 第二个条目一个请求都不该发
        assertEquals("已有", result.items[1].sha1)
        assertEquals(2048L, result.items[1].size)
        // 第一个条目：一次 sha1 + 一次 HEAD
        assertEquals(2, hits.get() - before)
        assertEquals(1, result.filled)
        assertEquals(0, result.stillMissing)
    }

    @Test
    fun `补不到时保留原值并计入 stillMissing`() = runBlocking {
        missingSha1 += "/lib/a.jar"
        val items = listOf(DownloadItem(url = url("/lib/a.jar"), target = "/tmp/a.jar"))
        val result = checksums.enrichWithStats(items)
        assertNull(result.items[0].sha1)
        assertEquals(1024L, result.items[0].size)
        assertEquals(1, result.stillMissing)
        // size 补上了也算一次补全：filled 数的是「这一项至少拿到一样校验信息」，
        // 与 stillMissing（还缺任何一样）不是互补关系。
        assertEquals(1, result.filled)
    }

    @Test
    fun `parseSha1 只认 40 位十六进制`() {
        val good = "b73203963ea70aee0c38dc58de1f6d16a766605b"
        assertEquals(good, RemoteChecksums.parseSha1(good))
        assertEquals(good, RemoteChecksums.parseSha1("  " + good + "  forge-1.20.1-47.4.0-installer.jar"))
        assertNull(RemoteChecksums.parseSha1(""))
        assertNull(RemoteChecksums.parseSha1("abc"))
        assertNull(RemoteChecksums.parseSha1("z".repeat(40)))
        assertNull(RemoteChecksums.parseSha1("b73203963ea70aee0c38dc58de1f6d16a766605")) // 39 位
    }

    private fun sha1(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        return digest.digest(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    @Test
    fun `空列表不炸`() = runBlocking {
        val result = checksums.enrichWithStats(emptyList())
        assertTrue(result.items.isEmpty())
        assertEquals(0, result.filled)
        assertEquals(0, result.stillMissing)
    }
}
