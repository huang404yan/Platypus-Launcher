package io.github.huang404yan.platypus.core.lan

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.runtime.TarXz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * 陶瓦的下载、校验与解包。
 *
 * 为什么不把二进制打进安装包：Terracotta 是 AGPL-3.0，而鸭嘴兽是 Apache-2.0 且公开发布产物。
 * 把它的二进制塞进我们的分发包会触发 AGPL 的传染条款（整个分发物都得按 AGPL 走）。
 * PCL 自编版敢内置是因为它「纯自用、不分发」。所以这里的做法是：用户点一下，我们替他下载。
 * 用户拿到的是自己下的副本，我们只是调用者，不随包分发。
 *
 * 下载完必须核对 SHA-512：这是一个即将被执行的文件，不核对等于把「中间人换了个 exe」变成
 * 「用户电脑上跑了个陌生程序」。官方源与镜像的一致性是实测过的（2026-11-15）。
 */
object TerracottaInstaller {

    /** 下载进度。step 是给人看的一句话。 */
    data class Progress(val step: String, val done: Long = 0L, val total: Long = 0L) {
        val fraction: Float get() = if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * 确保 [targetDir] 里有一份能用的陶瓦，返回 exe 文件。
     *
     * 已经装好就直接返回，不联网。装的过程中先写 .part，校验通过才改名 ——
     * 中途断网不会留下一个「看着装了、其实只下了半截」的 exe。
     */
    suspend fun ensure(
        targetDir: File,
        onProgress: (Progress) -> Unit = {},
        force: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        if (!force) {
            val existing = Terracotta.exeOf(targetDir)
            if (existing != null) return@withContext existing
        }

        targetDir.mkdirs()
        if (!targetDir.isDirectory) {
            throw IOException("装陶瓦的目录建不出来：" + targetDir.absolutePath)
        }

        val archive = File(targetDir, Terracotta.packageName() + ".part")
        try {
            download(archive, onProgress)
            onProgress(Progress("正在校验下载到的文件…"))
            val actual = sha512Of(archive)
            if (!actual.equals(Terracotta.PACKAGE_SHA512, ignoreCase = true)) {
                throw IOException(
                    "下载到的陶瓦校验不对（可能是网络中间层改了内容），已删掉。请再点一次安装。",
                )
            }
            onProgress(Progress("正在解压…"))
            unpack(archive, targetDir)
        } finally {
            runCatching { archive.delete() }
            // 顺手清掉解压时可能残留的 .part / 临时文件
            targetDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".part") }
                ?.forEach { runCatching { it.delete() } }
        }

        val installed = Terracotta.exeOf(targetDir)
            ?: throw IOException("解压完了但目录里没有 terracotta 开头的 exe：" + targetDir.absolutePath)
        onProgress(Progress("装好了", 1L, 1L))
        installed
    }

    /** 依次试几个源。全都失败时抛最后一个错误，并说清试过哪些。 */
    private suspend fun download(target: File, onProgress: (Progress) -> Unit) {
        val urls = Terracotta.downloadUrls()
        val failures = mutableListOf<String>()
        for (url in urls) {
            try {
                onProgress(Progress("正在下载陶瓦（" + hostOf(url) + "）…"))
                val report = HttpDownloader(parallelism = 1, retries = 1).download(
                    listOf(DownloadItem(url = url, target = target.absolutePath)),
                ) { progress ->
                    onProgress(
                        Progress(
                            step = "正在下载陶瓦（" + hostOf(url) + "）…",
                            done = progress.completedBytes,
                            total = progress.totalBytes,
                        ),
                    )
                }
                if (report.ok && target.isFile && target.length() > 0L) return
                val reason = report.failures.firstOrNull()?.reason ?: "下载完是空文件"
                failures += hostOf(url) + "：" + reason
            } catch (e: Exception) {
                failures += hostOf(url) + "：" + (e.message ?: e.javaClass.simpleName)
                runCatching { target.delete() }
            }
        }
        throw IOException(
            "陶瓦下载失败，几个源都试过了：" + failures.joinToString("；") +
                "。也可以自己下载 " + Terracotta.packageName() + " 解压到 " + target.parentFile?.absolutePath,
        )
    }

    /** 解 tar.gz 到 [targetDir]。tar 解析复用安卓 JRE 那套（路径穿越、可执行位都处理过了）。 */
    private fun unpack(archive: File, targetDir: File) {
        archive.inputStream().buffered().use { raw ->
            GZIPInputStream(raw).use { gz -> TarXz.extractTar(gz, targetDir) }
        }
    }

    /** 算文件的 SHA-512（小写十六进制）。 */
    fun sha512Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    /** 只取主机名，报错时不必把整条 URL 甩给用户。 */
    private fun hostOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringBefore('/').ifBlank { url }
    }
}