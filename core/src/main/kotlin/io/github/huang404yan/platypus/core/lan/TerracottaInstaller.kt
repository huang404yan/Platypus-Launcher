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
 * 下载完必须核对摘要：这是一个即将被执行的文件，不核对等于把「中间人换了个 exe」变成
 * 「用户电脑上跑了个陌生程序」。优先用内置的 SHA-512（只对内置那一版有效），
 * 在线查到新版时用 GitHub API 给的 SHA-256。
 */
object TerracottaInstaller {

    /** 记录已装版本的小文件，挨着 exe 放。没有它就只能当「装过但不知道是哪个版本」。 */
    private const val VERSION_FILE: String = ".installed-version"

    /** 下载进度。step 是给人看的一句话。 */
    data class Progress(val step: String, val done: Long = 0L, val total: Long = 0L) {
        val fraction: Float get() = if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * 确保 [targetDir] 里有一份能用的陶瓦，返回 exe 文件。
     *
     * 已经装好就直接返回，不联网 —— 除非 [force] 或 [checkUpdate] 为真。
     * [checkUpdate] 给界面那个「安装 / 更新陶瓦」按钮用：它先去问一句官方最新是哪个版本，
     * 版本一样就什么都不做，不一样才重下。
     *
     * 装的过程中先写 .part，校验通过才解包 —— 中途断网不会留下一个
     * 「看着装了、其实只下了半截」的 exe。
     */
    suspend fun ensure(
        targetDir: File,
        onProgress: (Progress) -> Unit = {},
        force: Boolean = false,
        checkUpdate: Boolean = false,
        latest: suspend () -> TerracottaRelease = { TerracottaReleases.fetchLatest() },
    ): File = withContext(Dispatchers.IO) {
        val existing = if (force) null else Terracotta.exeOf(targetDir)
        if (existing != null && !checkUpdate) return@withContext existing

        targetDir.mkdirs()
        if (!targetDir.isDirectory) {
            throw IOException("装陶瓦的目录建不出来：" + targetDir.absolutePath)
        }

        onProgress(Progress("正在查官方最新版本…"))
        val release = resolveRelease(latest, onProgress)
        val installedVersion = readInstalledVersion(targetDir)
        if (existing != null) {
            if (installedVersion == release.version) {
                onProgress(Progress("已经是最新版本（" + release.version + "）", 1L, 1L))
                return@withContext existing
            }
            onProgress(
                Progress(
                    "已装的是 " + (installedVersion ?: "未知版本") + "，官方最新是 " + release.version + "，开始更新…",
                ),
            )
        }

        val archive = File(targetDir, release.assetName + ".part")
        try {
            download(archive, release, onProgress)
            onProgress(Progress("正在校验下载到的文件…"))
            verify(archive, release, onProgress)
            onProgress(Progress("正在解压…"))
            unpack(archive, targetDir)
            writeInstalledVersion(targetDir, release.version)
        } finally {
            runCatching { archive.delete() }
            // 顺手清掉解压时可能残留的 .part / 临时文件
            targetDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".part") }
                ?.forEach { runCatching { it.delete() } }
        }

        val installed = Terracotta.exeOf(targetDir)
            ?: throw IOException("解压完了但目录里没有 terracotta 开头的 exe：" + targetDir.absolutePath)
        onProgress(Progress("装好了（" + release.version + "）", 1L, 1L))
        installed
    }

    /**
     * 问一句官方最新，失败就退回内置的那一版。
     *
     * 为什么不上抛：查版本这件事挂了（限流、断网、空壳包没配地址）不该让「装陶瓦」整体失败 ——
     * 内置版本是实测能用的一份，退回去至少还能玩。进度里会说明退回了。
     */
    private suspend fun resolveRelease(
        latest: suspend () -> TerracottaRelease,
        onProgress: (Progress) -> Unit,
    ): TerracottaRelease {
        val builtin = builtinRelease()
        val fetched = try {
            latest()
        } catch (e: Exception) {
            onProgress(
                Progress(
                    "没查到官方最新版（" + (e.message ?: e.javaClass.simpleName) +
                        "），改用内置的 " + builtin.version + "。",
                ),
            )
            null
        }
        return fetched ?: builtin
    }

    /** 内置常量描述的那一版：没网时也能装，SHA-512 是写死在代码里的。 */
    fun builtinRelease(): TerracottaRelease = TerracottaRelease(
        tag = "v" + Terracotta.VERSION,
        version = Terracotta.VERSION,
        assetName = TerracottaReleases.assetNameOf(Terracotta.VERSION),
        downloadUrl = TerracottaReleases.officialUrl(
            "v" + Terracotta.VERSION,
            TerracottaReleases.assetNameOf(Terracotta.VERSION),
        ),
        sizeBytes = -1L,
        sha256 = null,
    )

    /**
     * 该校验哪种摘要：版本号等于内置的就用 SHA-512（那份值是我们实测过的，最可信），
     * 否则用 API 给的 SHA-256。两个都没有时只检查「不是空文件」，并在进度里说清楚。
     */
    private fun verify(archive: File, release: TerracottaRelease, onProgress: (Progress) -> Unit) {
        if (release.isBuiltinVersion) {
            val actual = sha512Of(archive)
            if (!actual.equals(Terracotta.PACKAGE_SHA512, ignoreCase = true)) {
                throw IOException(
                    "下载到的陶瓦校验不对（可能是网络中间层改了内容），已删掉。请再点一次安装。",
                )
            }
            return
        }
        val expected = release.sha256
        if (expected == null) {
            onProgress(Progress("官方这个版本没给 sha256，跳过摘要校验（渠道仍要求是 https 官方地址）。"))
            return
        }
        val actual = sha256Of(archive)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IOException(
                "下载到的陶瓦校验不对（可能是网络中间层改了内容），已删掉。请再点一次安装。",
            )
        }
    }

    /** 依次试几个源。全都失败时抛最后一个错误，并说清试过哪些。 */
    private suspend fun download(target: File, release: TerracottaRelease, onProgress: (Progress) -> Unit) {
        val urls = listOf(release.downloadUrl) + Terracotta.downloadUrls(release.version)
        val failures = mutableListOf<String>()
        for (url in urls.distinct()) {
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
                "。也可以自己下载 " + release.assetName + " 解压到 " + target.parentFile?.absolutePath,
        )
    }

    /** 解 tar.gz 到 [targetDir]。tar 解析复用安卓 JRE 那套（路径穿越、可执行位都处理过了）。 */
    private fun unpack(archive: File, targetDir: File) {
        archive.inputStream().buffered().use { raw ->
            GZIPInputStream(raw).use { gz -> TarXz.extractTar(gz, targetDir) }
        }
    }

    /** 目录里记着的已装版本；没装过或文件坏了返回 null。 */
    fun readInstalledVersion(dir: File): String? = runCatching {
        val file = File(dir, VERSION_FILE)
        if (!file.isFile) return@runCatching null
        file.readText(Charsets.UTF_8).trim().ifBlank { null }
    }.getOrNull()

    private fun writeInstalledVersion(dir: File, version: String) {
        runCatching { File(dir, VERSION_FILE).writeText(version + "\n", Charsets.UTF_8) }
    }

    /** 算文件的 SHA-512（小写十六进制）。 */
    fun sha512Of(file: File): String = digestOf(file, "SHA-512")

    /** 算文件的 SHA-256（小写十六进制）。 */
    fun sha256Of(file: File): String = digestOf(file, "SHA-256")

    private fun digestOf(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
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
