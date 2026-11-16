package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.Endpoints
import io.github.huang404yan.platypus.core.modpack.ModpackExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 拿本地文件的 CurseForge 指纹去问「这是哪个项目的哪个文件」，问到就带回 (projectID, fileID)。
 *
 * 导出的 CurseForge 包靠它对上号：manifest.json 的 files 里只写 ID，导入方自己去下。
 * 和 Modrinth 那条路有一个根本区别 —— **CurseForge 的 API 要 Key**，而 Modrinth 不要。
 * 所以这里没有内置 Key，Key 由用户自己填在「设置 → 下载」里；没填就整项功能关闭。
 * 内置 Key 会随源码一起公开，轻则被刷爆额度，重则整个应用被封。
 *
 * 一条都没命中是正常结果（本地改过的 jar、自己编译的、非 CurseForge 来源），返回空表；
 * 网络或接口出错则抛 IOException。Key 不对（403）单独说一句，别让用户以为是网络问题。
 */
object CurseForgeLookup {

    /** Minecraft 在 CurseForge 里的游戏 ID，接口路径里写死这一个数。 */
    const val GAME_ID: Int = 432

    /**
     * 指纹反查接口。空壳发布包里是用户填的镜像根 + /v1/fingerprints/432，没填就是空串。
     *
     * GAME_ID 写死在路径里（CurseForge 只认 432 = Minecraft）；Kotlin 的 const 拼不了
     * 取值器，所以这里写成 val + getter，路径末尾仍与 GAME_ID 保持一致。
     */
    val FINGERPRINTS_URL: String
        get() = Endpoints.join(Endpoints.curseForge(), "/v1/fingerprints/" + GAME_ID)

    /** CurseForge 用这个头收 Key，名字不是 Authorization。 */
    const val API_KEY_HEADER: String = "x-api-key"

    /** 一次问多少个指纹。接口本身没写上限，一批 100 与检查更新那边保持同一个量级。 */
    const val LOOKUP_BATCH: Int = 100

    private const val CONNECT_TIMEOUT_MS: Int = 15_000
    private const val READ_TIMEOUT_MS: Int = 30_000
    private const val USER_AGENT: String =
        "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * 指纹 → (projectID, fileID)。
     *
     * 指纹是无符号 32 位，所以一律用 Long 装（Kotlin 的 Int 装不下 40 亿那种值）。
     */
    suspend fun lookup(apiKey: String, fingerprints: List<Long>): Map<Long, Pair<Int, Int>> =
        withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            if (key.isEmpty()) throw IOException("没有填 CurseForge 的 API Key，查不了文件归属。")
            val clean = fingerprints.filter { it > 0L }.distinct()
            if (clean.isEmpty()) return@withContext emptyMap()
            val out = mutableMapOf<Long, Pair<Int, Int>>()
            for (batch in clean.chunked(LOOKUP_BATCH)) {
                out += parseMatches(post(key, requestBody(batch)))
            }
            out
        }

    /** 给导出层用的适配器。网络错统一包成 IOException，导出那边只看这一种。 */
    fun asCurseLookup(apiKey: String): ModpackExport.CurseLookup =
        ModpackExport.CurseLookup { fingerprints ->
            runCatching { lookup(apiKey, fingerprints) }.getOrElse { error ->
                throw IOException(error.message ?: "连不上 CurseForge", error)
            }
        }

    /**
     * 请求体。指纹是数字，不能加引号 —— 加了 CurseForge 会当成字符串全部忽略，
     * 返回一个空列表，看起来像「一个都没认出来」。
     */
    fun requestBody(fingerprints: List<Long>): String =
        "{\"fingerprints\":[" + fingerprints.joinToString(",") + "]}"

    /**
     * 解析 exactMatches。
     *
     * 三个字段名是权威的（PCL 的源码里也是这么读的）：modId 是项目 ID、id 是文件 ID、
     * file.fileFingerprint 是我们送进去的那个指纹（用它把结果对回本地文件）。
     * 少任何一个字段的条目直接跳过 —— 拼不出 files[] 里的一项，留着只会让导入方困惑。
     */
    fun parseMatches(text: String): Map<Long, Pair<Int, Int>> {
        val root = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        val data = root["data"] as? JsonObject ?: return emptyMap()
        val matches = data["exactMatches"] as? JsonArray ?: return emptyMap()
        val out = linkedMapOf<Long, Pair<Int, Int>>()
        for (element in matches) {
            val item = element as? JsonObject ?: continue
            val projectId = (item["modId"] as? JsonPrimitive)?.intOrNull ?: continue
            val fileId = (item["id"] as? JsonPrimitive)?.intOrNull ?: continue
            val file = item["file"] as? JsonObject
            val fingerprint = (file?.get("fileFingerprint") as? JsonPrimitive)?.longOrNull ?: continue
            if (projectId <= 0 || fileId <= 0 || fingerprint <= 0L) continue
            out[fingerprint] = projectId to fileId
        }
        return out
    }

    /** 填 Key 那一格的问题提示。返回 null 表示能用了。 */
    fun apiKeyProblem(raw: String): String? {
        val key = raw.trim()
        if (key.isEmpty()) return null
        if (key.length < 16) return "这个 Key 太短了，CurseForge 的 Key 一般有几十个字符。"
        if (key.any { it.isWhitespace() }) return "Key 里不能有空格或换行，复制的时候多带了一段。"
        return null
    }

    private fun post(apiKey: String, body: String): String {
        val url = FINGERPRINTS_URL
        if (url.isEmpty()) throw IOException(Endpoints.SHELL_HINT)
        val text = body.toByteArray(StandardCharsets.UTF_8)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(API_KEY_HEADER, apiKey)
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.outputStream.use { it.write(text) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
            when {
                status in 200..299 -> return response
                status == 403 -> throw IOException(
                    "CurseForge 拒绝了这个 API Key（403）。去「设置 → 下载」里核对一下，" +
                        "或者去 CurseForge 开发者后台重新生成一个。",
                )
                status == 429 -> throw IOException("CurseForge 限流了（429），等一会儿再导出。")
                status >= 500 -> throw IOException("CurseForge 自己出错了（" + status + "），过一会儿再试。")
                else -> throw IOException(
                    "CurseForge 说这次查询有问题（" + status + "）：" + response.take(200),
                )
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("连不上 CurseForge：" + (e.message ?: e::class.simpleName), e)
        } finally {
            connection.disconnect()
        }
    }
}