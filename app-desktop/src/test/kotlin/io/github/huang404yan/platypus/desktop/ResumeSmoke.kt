package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadProgress
import io.github.huang404yan.platypus.core.download.HttpDownloader
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * 断点续传的真实网络冒烟（开发期用，需要联网）。
 *
 * 单测里跑的是本地 HttpServer，能覆盖 206 / 200 / 416 / 提前 EOF 五类响应，但**不能证明
 * 真实 CDN 真的配合**。「下 600 MB 时拔网线」没法自动化，能自动化的是这一层：
 *
 *  1. 全新下载真实文件，结果与 Mojang 给的 sha1 一致；
 *  2. 把前 10 MB 先落成 .part，再跑一次 —— 必须报告「续传过」，且最终 sha1 仍一致；
 *  3. .part 内容坏掉（长度对、字节错）时要能自愈。
 *
 * 用的都是 Mojang 官方 CDN 上**不可变**的文件（版本 json 里写死的 sha1），
 * 所以断言可以写死，不会因为上游更新而假红。
 *
 * 只由 gradle :app-desktop:resumeSmoke 调用，不进发行包。
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { error("缺少参数：临时目录") })
    dir.mkdirs()
    var exit = 0

    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS " else "FAIL ") + name + (if (detail.isBlank()) "" else " :: " + detail))
        if (!ok) exit = 1
    }

    fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    /** 从真实 CDN 只取前 count 字节，用来伪造一个「上次下到一半」的半成品。 */
    fun fetchHead(url: String, count: Int): ByteArray {
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Range", "bytes=0-" + (count - 1))
        }
        try {
            check("前置：CDN 支持 Range（HTTP " + conn.responseCode + "）", conn.responseCode == 206)
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    val downloader = HttpDownloader()

    // ── 用例 1：全新下载 23 MB 的 client.jar ────────────────────────────────
    val clientUrl = "https://piston-data.mojang.com/v1/objects/0c3ec587af28e5a785c0b4a7b8a30f9a8f78f838/client.jar"
    val clientSha1 = "0c3ec587af28e5a785c0b4a7b8a30f9a8f78f838"
    val clientSize = 23_028_853L

    val fresh = File(dir, "fresh/client.jar")
    fresh.delete()
    File(fresh.path + ".part").delete()

    runBlocking {
        val report = downloader.download(
            listOf(DownloadItem(clientUrl, fresh.absolutePath, sha1 = clientSha1, size = clientSize)),
        ) { }
        check("全新下载成功", report.ok, "succeeded=" + report.succeeded)
        check("全新下载大小正确", fresh.length() == clientSize, "actual=" + fresh.length())
        check("全新下载 sha1 匹配", sha1(fresh) == clientSha1)
        check("全新下载没留下 .part", !File(fresh.path + ".part").exists())
    }

    // ── 用例 2：预置前 10 MB 再下载，必须走续传 ─────────────────────────────
    val resumed = File(dir, "resumed/client.jar")
    resumed.delete()
    val resumedPart = File(resumed.path + ".part")
    resumedPart.parentFile?.mkdirs()
    resumedPart.delete()

    val headBytes = fetchHead(clientUrl, 10 * 1024 * 1024)
    check("前置：取到 10 MB 头部", headBytes.size == 10 * 1024 * 1024, "actual=" + headBytes.size)
    resumedPart.writeBytes(headBytes)

    var resumedFiles = 0
    var resumedBytes = 0L
    runBlocking {
        val started = System.currentTimeMillis()
        val report = downloader.download(
            listOf(DownloadItem(clientUrl, resumed.absolutePath, sha1 = clientSha1, size = clientSize)),
        ) { snapshot: DownloadProgress ->
            if (snapshot.resumedFiles > resumedFiles) resumedFiles = snapshot.resumedFiles
            if (snapshot.resumedBytes > resumedBytes) resumedBytes = snapshot.resumedBytes
        }
        val elapsed = System.currentTimeMillis() - started
        check("续传下载成功", report.ok, "succeeded=" + report.succeeded)
        check("续传确实生效（resumedFiles > 0）", resumedFiles > 0, "resumedFiles=" + resumedFiles)
        check("续传字节数是预置的那 10 MB", resumedBytes == 10L * 1024 * 1024, "resumedBytes=" + resumedBytes)
        check("续传后 sha1 仍匹配（整文件校验）", sha1(resumed) == clientSha1)
        check("续传没留下 .part", !resumedPart.exists())
        check("续传耗时可接受（< 90s）", elapsed < 90_000, "elapsed=" + elapsed + " ms")
    }

    // ── 用例 3：半成品内容坏掉（长度对、字节全错）必须被 sha1 抓着重下 ─────
    //
    // 这里必须给 sha1。只给 size 的话，续传会把垃圾字节当成好数据接着往后拼，
    // 长度一样对、sha1 却不符，属于「不报错的坏文件」—— 那才是真危险。
    val smallUrl = "https://piston-meta.mojang.com/v1/packages/ada52cc672270e8dc69e912b2ba0ea9c141ed664/5.json"
    val smallSha1 = "ada52cc672270e8dc69e912b2ba0ea9c141ed664"
    val smallSize = 413_067L
    val smallTarget = File(dir, "corrupt/asset-index.json")
    smallTarget.delete()
    val corruptPart = File(smallTarget.path + ".part")
    corruptPart.parentFile?.mkdirs()
    corruptPart.writeBytes(ByteArray(20_000) { 7 })

    runBlocking {
        val report = downloader.download(
            listOf(DownloadItem(smallUrl, smallTarget.absolutePath, sha1 = smallSha1, size = smallSize)),
        ) { }
        check("坏半成品能被 sha1 抓着重下", report.ok, "succeeded=" + report.succeeded)
        check("重下后大小正确", smallTarget.length() == smallSize, "actual=" + smallTarget.length())
        check("重下后 sha1 匹配", sha1(smallTarget) == smallSha1)
        check("重下后没留下 .part", !corruptPart.exists())
    }

    println(if (exit == 0) "RESUME SMOKE OK" else "RESUME SMOKE FAILED")
    exitProcess(exit)
}
