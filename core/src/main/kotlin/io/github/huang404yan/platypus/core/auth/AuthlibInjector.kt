package io.github.huang404yan.platypus.core.auth

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.download.DownloadPreference
import io.github.huang404yan.platypus.core.mods.ModUpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * authlib-injector 的一次发布（官方 latest.json 的内容）。
 *
 * [sha256] 是官方给的 jar 校验值。**空串表示上游没给**（个别镜像不返 checksums），
 * 这时候仍然要下，只是没法强校验 —— 由调用方在提示里说明。
 */
data class AuthlibInjectorBuild(
    val version: String,
    val buildNumber: Long = 0L,
    val downloadUrl: String,
    val sha256: String = "",
    val releaseTime: String = "",
)

/**
 * authlib-injector：让原版客户端认外置登录服务的 javaagent。
 *
 * 它做的事很简单也很难替代：游戏里的 authlib（负责「我是谁」的那一块）只认 Mojang 的
 * Yggdrasil，而 authlib-injector 在类加载期把 authentication host 改写成你的服务端地址。
 * 所以外置登录不是「我们自己发 HTTP 就完了」，**必须把这个 jar 挂进 JVM**：
 *
 *     java -javaagent:authlib-injector.jar=<认证服务器地址> -Dauthlibinjector.yggdrasil.prefetched=<base64> ...
 *
 * 少了这一行，游戏照样用正版验证去连那个私服，进不去。
 *
 * 为什么自己下而不是打进安装包：它是 GPL 分发的第三方 jar，跟着我们的安装包走既有许可
 * 上的麻烦，也失去独立升级的能力；这个 jar 只有几十 KB，第一次用外置登录时下一份即可。
 */
object AuthlibInjector {

    /** 官方发布入口。 */
    const val LATEST_URL = "https://authlib-injector.yushi.moe/artifact/latest.json"

    /** BMCLAPI 的镜像。实测与本文件的 sha256 完全一致（2026-11-15 核对）。 */
    const val LATEST_URL_MIRROR = "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/latest.json"

    /** jar 文件名。故意不带版本号：启动参数里写死了路径，换版本时不该改参数。 */
    const val JAR_NAME = "authlib-injector.jar"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** jar 落在哪：<gameRoot>/platypus/authlib-injector/authlib-injector.jar。 */
    fun jarOf(gameRoot: File): File = File(File(File(gameRoot, "platypus"), "authlib-injector"), JAR_NAME)

    /** 已经下好了吗（只判文件在不在，校验交给 [verify]）。 */
    fun isInstalled(jar: File): Boolean = jar.isFile && jar.length() > 0

    /** 解析 latest.json。 */
    fun parseBuild(text: String): AuthlibInjectorBuild {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw YggdrasilException("authlib-injector 的版本信息不是 JSON（前 120 个字：" + text.take(120) + "）。", it)
        }
        val url = root.str("download_url")
            ?: throw YggdrasilException("authlib-injector 的版本信息里没有 download_url。")
        return AuthlibInjectorBuild(
            version = root.str("version").orEmpty(),
            buildNumber = runCatching { root["build_number"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L,
            downloadUrl = url,
            sha256 = runCatching {
                root["checksums"]?.jsonObject?.str("sha256")
            }.getOrNull().orEmpty(),
            releaseTime = root.str("release_time").orEmpty(),
        )
    }

    /** 拉最新版本信息；官方源失败时退到镜像。 */
    suspend fun latest(): AuthlibInjectorBuild = withContext(Dispatchers.IO) {
        val urls = mutableListOf(LATEST_URL, LATEST_URL_MIRROR)
        var last: Exception? = null
        for (url in urls) {
            try {
                return@withContext parseBuild(fetchText(url))
            } catch (e: Exception) {
                last = e
            }
        }
        throw YggdrasilException(
            "拉不到 authlib-injector 的版本信息（官方源与 BMCLAPI 镜像都没通）：" +
                (last?.message ?: "未知原因"),
            last,
        )
    }

    /**
     * 准备 jar：没有就下，有就校验。
     *
     * 校验失败会删掉重下一次 —— 半截文件留在盘上，每次启动都会失败，而用户看到的
     * 只是「游戏起不来」，查不出是这个 jar 坏了。
     */
    suspend fun ensure(jar: File, onProgress: (String) -> Unit = {}): File = withContext(Dispatchers.IO) {
        if (isInstalled(jar) && verify(jar, "")) {
            onProgress("authlib-injector 已经在了")
            return@withContext jar
        }
        onProgress("正在查 authlib-injector 的版本")
        val build = latest()
        val expected = build.sha256
        if (isInstalled(jar) && verify(jar, expected)) {
            onProgress("authlib-injector " + build.version + " 已经在了")
            return@withContext jar
        }
        onProgress(
            "正在下载 authlib-injector" + (if (build.version.isBlank()) "" else " " + build.version),
        )
        if (jar.exists() && !jar.delete()) {
            throw IOException("旧的 authlib-injector 删不掉（可能还被别的进程占着）：" + jar.absolutePath)
        }
        val parent = jar.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("建不了目录：" + parent.absolutePath)
        }
        val report = HttpDownloader(parallelism = 1).download(
            listOf(
                DownloadItem(
                    url = build.downloadUrl,
                    target = jar.absolutePath,
                    mirrors = listOf(mirrorOf(build.downloadUrl)),
                ),
            ),
        ) { progress ->
            if (progress.totalBytes > 0) {
                val percent = (progress.completedBytes * 100 / progress.totalBytes).toInt()
                onProgress("正在下载 authlib-injector（" + percent + "%）")
            }
        }
        if (!report.ok) {
            throw YggdrasilException(
                "下载 authlib-injector 失败：" + (report.failures.firstOrNull()?.reason ?: "未知原因"),
            )
        }
        if (!verify(jar, expected)) {
            jar.delete()
            throw YggdrasilException(
                "authlib-injector 下载完了但校验不对（可能是网络中间层改了内容），已删掉重试一次。",
            )
        }
        onProgress("authlib-injector 准备好了")
        jar
    }

    /** 校验 jar。期望值为空时只判「不是空文件」。 */
    fun verify(jar: File, expectedSha256: String): Boolean {
        if (!jar.isFile || jar.length() <= 0) return false
        if (expectedSha256.isBlank()) return true
        val actual = runCatching { sha256Of(jar) }.getOrNull() ?: return false
        return actual.equals(expectedSha256, ignoreCase = true)
    }

    /**
     * 组装挂载 authlib-injector 用的 JVM 参数。
     *
     * 三个参数缺一不可：
     * 1. `-javaagent:<jar>=<apiRoot>` —— 把认证地址改写成这个服务端。**必须是 http(s) 的
     *    完整地址**，不能带结尾斜杠（authlib-injector 会自己拼 /authserver/...）。
     * 2. `-Dauthlibinjector.yggdrasil.prefetched=<base64>` —— 把服务端元数据直接喂给它，
     *    省掉启动时的一次请求。没有元数据（比如离线摸不到）就不加，游戏自己拉。
     * 3. `-Dauthlibinjector.side=client` —— 明确当前是客户端。不写时它靠猜，
     *    在服务端环境下会猜错。
     *
     * 路径里带空格要加引号：Windows 上「我的 游戏」这种目录很常见，不加引号 java 会把
     * 空格后面的部分当成另一个参数。
     */
    fun jvmArgs(jar: File, apiRoot: String, metadata: YggdrasilMetadata? = null): List<String> {
        val root = apiRoot.trim().trimEnd('/')
        if (root.isEmpty()) return emptyList()
        val args = mutableListOf<String>()
        val jarPath = jar.absolutePath
        args += if (jarPath.contains(' ')) {
            "-javaagent:\"" + jarPath + "\"=" + root
        } else {
            "-javaagent:" + jarPath + "=" + root
        }
        val prefetched = metadata?.prefetched.orEmpty()
        if (prefetched.isNotBlank()) {
            args += "-Dauthlibinjector.yggdrasil.prefetched=" + prefetched
        }
        args += "-Dauthlibinjector.side=client"
        return args
    }

    /** 主地址的镜像写法；不是官方地址就返回空串。 */
    private fun mirrorOf(url: String): String = when {
        url.startsWith("https://authlib-injector.yushi.moe/") ->
            LATEST_URL_MIRROR.substringBefore("/artifact/latest.json") + url.removePrefix("https://authlib-injector.yushi.moe")
        else -> ""
    }

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", YggdrasilClient.USER_AGENT)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP " + status)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return ModUpdateChecker.hex(digest.digest())
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }.getOrNull()
}
