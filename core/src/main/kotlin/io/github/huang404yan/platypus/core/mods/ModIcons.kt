package io.github.huang404yan.platypus.core.mods

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 模组图标的抓取与缓存（联网拉 Modrinth 的 icon_url、或从本地 jar 里抽 icon.png）。
 *
 * 为什么单独一个类：图标是「有一张更好看，没有也不能出错」的东西 —— 断网、作者没传图、
 * jar 里没图标都是常态。把它和 ModScanner / ModInstaller 分开，调用方用 runCatching 包一层就完事，
 * 不会因为一张图失败把整页模组清单带下去。
 *
 * 缓存落盘在系统临时目录（[defaultCacheDir]）：图标是纯粹的派生数据，重建零成本，
 * 不值得塞进游戏目录或用户文档里跟着备份走。键是 URL / jar 内容的摘要，所以换了链接自然失效。
 */
object ModIcons {

    /** 单张图的上限，超过就当成坏数据丢掉 —— 正常图标是 64~512 像素的 png，几十 KB。 */
    const val MAX_BYTES: Long = 2L * 1024L * 1024L

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 15000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val imageSuffixes = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif")

    /** 默认缓存目录：<tmpdir>/platypus-mod-icons/。 */
    fun defaultCacheDir(): File =
        File(File(System.getProperty("java.io.tmpdir") ?: ".", "platypus-mod-icons"), "icons")

    /** 网络图标的缓存文件名：URL 的 sha1，扩展名无所谓 —— 界面解码靠内容不靠后缀。 */
    fun cachedFileForUrl(cacheDir: File, url: String): File =
        File(cacheDir, sha1Hex(url) + ".img")

    /** 缓存目录可能还没建过（第一次装模组、或者临时目录被清过），写之前补一下。 */
    private fun ensureDir(cacheDir: File): Boolean =
        cacheDir.isDirectory || cacheDir.mkdirs()

    /**
     * 取一张网络图标：命中缓存直接返回，否则下载后落盘。
     * 失败（断网、404、不是图）返回 null，由调用方画占位图。
     */
    fun fetch(url: String, cacheDir: File): File? {
        if (url.isBlank()) return null
        if (!ensureDir(cacheDir)) return null
        val target = cachedFileForUrl(cacheDir, url)
        if (target.isFile && target.length() > 0L) return target
        val bytes = runCatching { download(url) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return writeAtomically(target, bytes)
    }

    /**
     * 从一个模组 jar 里抽图标。
     *
     * 顺序照着生态里的实际写法来：fabric.mod.json 的 icon 字段（有的模块把它写成对象 map）→
     * 根目录 icon.png → assets/<modId>/icon.png → assets/<modId>/textures/icon.png。
     * 都没有就返回 null —— Forge 的 mods.toml 没有图标约定，很多老模组确实一张图都没有。
     */
    fun fromJar(jar: File, modId: String?, cacheDir: File): File? {
        if (!jar.isFile) return null
        if (!ensureDir(cacheDir)) return null
        val target = File(
            cacheDir,
            sha1Hex(jar.absolutePath + "|" + jar.length() + "|" + jar.lastModified()) + ".img",
        )
        if (target.isFile && target.length() > 0L) return target
        val bytes = runCatching { iconBytesOf(jar, modId) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return writeAtomically(target, bytes)
    }

    private fun iconBytesOf(jar: File, modId: String?): ByteArray? {
        ZipFile(jar).use { zip ->
            val declared = runCatching { declaredIconPath(zip) }.getOrNull()
            val candidates = buildList {
                declared?.let { add(it) }
                add("icon.png")
                modId?.takeIf { it.isNotBlank() }?.let {
                    add("assets/" + it + "/icon.png")
                    add("assets/" + it + "/textures/icon.png")
                    add("assets/" + it + "/textures/gui/icon.png")
                }
            }
            for (path in candidates) {
                val entry = zip.getEntry(path) ?: continue
                if (entry.isDirectory) continue
                if (entry.size > MAX_BYTES) continue
                return zip.getInputStream(entry).use { it.readBytes() }
            }
            // fabric.mod.json 写了路径但拼错、或者只写了目录名时，退一步按 assets/*/icon.png 找
            zip.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.endsWith("/icon.png") && it.size <= MAX_BYTES }
                ?.let { return zip.getInputStream(it).use { stream -> stream.readBytes() } }
        }
        return null
    }

    private fun declaredIconPath(zip: ZipFile): String? {
        val entry = zip.getEntry("fabric.mod.json") ?: return null
        val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return when (val icon = root["icon"]) {
            is JsonPrimitive -> icon.content.takeIf { it.isNotBlank() }
            is JsonObject -> icon.values.filterIsInstance<JsonPrimitive>()
                .map { it.content }
                .firstOrNull { it.isNotBlank() }
            else -> null
        }?.takeIf { path -> imageSuffixes.any { path.endsWith(it, ignoreCase = true) } }
    }

    private fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ModrinthMeta.DEFAULT_USER_AGENT)
            setRequestProperty("Accept", "image/png,image/jpeg,image/webp,image/gif,*/*")
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP " + connection.responseCode)
            val stream = connection.inputStream
            val bytes = stream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) throw IllegalStateException("图标太大")
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    /** 先写 .part 再改名：中途被杀掉不会留下半张图片让下次当成缓存命中。 */
    private fun writeAtomically(target: File, bytes: ByteArray): File? = runCatching {
        val dir = target.parentFile
        if (dir != null && !dir.isDirectory) dir.mkdirs()
        val temp = File(target.absolutePath + ".part")
        temp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target
    }.getOrNull()

    private fun sha1Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            builder.append("0123456789abcdef"[value ushr 4])
            builder.append("0123456789abcdef"[value and 0x0F])
        }
        return builder.toString()
    }
}
