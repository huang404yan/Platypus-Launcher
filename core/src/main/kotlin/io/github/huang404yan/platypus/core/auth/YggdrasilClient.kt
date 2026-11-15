package io.github.huang404yan.platypus.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** 外置登录（Yggdrasil 协议）出错时抛这个，message 一律是能直接给人看的中文。 */
class YggdrasilException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 外置登录服务里的一个角色。
 *
 * [id] 是服务端给的 UUID。和微软那边不一样，**这里可能不带横线**，也可能不是标准 UUID
 * （有些自建服务端用自定义字符串），所以显示与启动参数要各走各的：显示统一成带横线，
 * 启动时去掉横线。不是 32 位十六进制的就原样保留 —— 硬套 UUID 格式只会把名字改坏。
 */
data class YggdrasilProfile(val id: String, val name: String) {
    /** 启动参数里要的 32 位写法。 */
    val compactId: String get() = MicrosoftAuth.compactUuid(id)

    /** 界面上显示的带横线写法。 */
    val prettyId: String get() = MicrosoftAuth.normalizeUuid(id)
}

/**
 * 一次外置登录换回来的凭据。
 *
 * [clientToken] 是客户端自己挑的一个随机串，服务端拿它来认「同一个启动器」。它没有过期时间，
 * 和 accessToken 一起存下来；下次 refresh 时必须带上同一个，不然服务端会把旧会话踢掉。
 */
data class YggdrasilSession(
    val accessToken: String,
    val clientToken: String,
    val profile: YggdrasilProfile,
    val availableProfiles: List<YggdrasilProfile> = emptyList(),
) {
    /**
     * 变成启动用的 [Session]。
     *
     * userType 用 "mojang" 而不是默认的 "msa"：authlib-injector 把外置登录伪装成 Mojang
     * 官方 Yggdrasil，游戏里那条 user_type 也会被带到服务端校验里；写 "msa" 会让一部分
     * 服务端把这次登录当成微软账号而去找正版档案。
     */
    fun toSession(): Session = Session(profile.name, profile.compactId, accessToken, USER_TYPE_MOJANG)

    companion object {
        const val USER_TYPE_MOJANG = "mojang"
    }
}

/**
 * 认证服务器的元数据（GET <apiRoot>/ 的响应）。
 *
 * [raw] 是原始响应正文，一行都不能改：[prefetched] 会把它整段 Base64 之后通过
 * -Dauthlibinjector.yggdrasil.prefetched 传给游戏，authlib-injector 用它校验签名 ——
 * 我们自己重新序列化一遍（哪怕字段一样、顺序变了）签名就对不上，游戏会直接拒绝启动。
 */
data class YggdrasilMetadata(
    val apiRoot: String,
    val serverName: String = "",
    val implementationName: String = "",
    val skinDomains: List<String> = emptyList(),
    val signaturePublicKey: String = "",
    val raw: String = "",
) {
    /** 界面上叫得出口的名字；服务端没报就用域名。 */
    val displayName: String get() = serverName.ifBlank { apiRoot }

    /** 预取参数的值；拿不到原文时给空串（那就让游戏自己去拉一次）。 */
    val prefetched: String
        get() = if (raw.isBlank()) "" else Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
}

/**
 * 外置登录（Yggdrasil）协议客户端，也就是 authlib-injector 那一套。
 *
 * **它只管协议**：登录、续期、校验、拉元数据。让游戏真正用上这套凭据是另一件事 ——
 * 要把 authlib-injector 这个 javaagent 挂到启动参数里（见 [AuthlibInjector]）。
 *
 * 与微软那套的关系：两者产出同一个 [Session]，启动链路对此一无所知。区别只在
 * 外置登录还要多带两个 JVM 参数，这一步由调用方决定，本类不掺和。
 *
 * 错误一律翻成人话再抛 [YggdrasilException]：服务端返回的 errorMessage 是英文，
 * 而且很多自建服务端返回的是 "Invalid credentials. Invalid username or password."
 * 这种既像密码错又像账号不存在的话，直接丢给用户等于没说。
 */
class YggdrasilClient(
    rawApiRoot: String,
    private val userAgent: String = USER_AGENT,
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MS,
) {

    /** 归一化之后的认证服务器根地址，不带结尾斜杠、不带 /authserver 之类后缀。 */
    val apiRoot: String = requireApiRoot(rawApiRoot)

    /**
     * 拉元数据。
     *
     * 不是必须的：登录、续期都不依赖它。它有两个用处 —— 界面上显示「你要登录的是
     * 某某服」而不是一串网址；以及拿到预取原文，让游戏启动时少一次网络请求。
     */
    suspend fun metadata(): YggdrasilMetadata = withContext(Dispatchers.IO) {
        val text = request("GET", apiRoot + "/", null) { status, body ->
            describeStatus(status, body, "读认证服务器信息")
        }
        parseMetadata(apiRoot, text)
    }

    /**
     * 用账号密码登录。
     *
     * [clientToken] 留空就由本类现生成一个；已有会话要沿用同一个（否则服务端会多算一个客户端）。
     * 密码不会被保存在任何地方 —— 登录成功后留下的只有 accessToken 与 clientToken。
     */
    suspend fun authenticate(
        username: String,
        password: String,
        clientToken: String = "",
    ): YggdrasilSession = withContext(Dispatchers.IO) {
        val name = username.trim()
        if (name.isEmpty()) throw YggdrasilException("要填用户名（或邮箱），不能空着。")
        if (password.isEmpty()) throw YggdrasilException("要填密码，不能空着。")
        val body = buildJsonObject {
            put("agent", buildJsonObject {
                put("name", "Minecraft")
                put("version", 1)
            })
            put("username", name)
            put("password", password)
            put("clientToken", clientToken.ifBlank { newClientToken() })
            put("requestUser", true)
        }.toString()
        val text = request("POST", apiRoot + AUTHENTICATE_PATH, body) { status, response ->
            describeLoginFailure(status, response)
        }
        parseSession(text, emptyToken = "登录成功，但服务端没有返回 accessToken。")
    }

    /**
     * 拿 accessToken 换一份新的。
     *
     * accessToken 是有期限的（多数服务端给 15 天），过期之后只能重新 refresh；
     * refresh 也失败（改过密码、管理员踢下线）就必须让用户重新输密码。
     */
    suspend fun refresh(accessToken: String, clientToken: String): YggdrasilSession = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) throw YggdrasilException("没有可以续期的令牌，需要重新登录。")
        val body = buildJsonObject {
            put("accessToken", accessToken)
            if (clientToken.isNotBlank()) put("clientToken", clientToken)
            put("requestUser", true)
        }.toString()
        val text = request("POST", apiRoot + REFRESH_PATH, body) { status, response ->
            if (status == 403 || status == 401) {
                "登录状态已经失效（服务端不认这枚令牌了），去「设置 → 账号」的外置登录里重新输一次密码。"
            } else {
                describeStatus(status, response, "续期登录状态")
            }
        }
        parseSession(text, emptyToken = "续期成功，但服务端没有返回 accessToken。")
    }

    /** 令牌还有效吗。有效返回 true，无效返回 false；网络问题照常抛异常（不能当成失效）。 */
    suspend fun validate(accessToken: String, clientToken: String = ""): Boolean = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext false
        val body = buildJsonObject {
            put("accessToken", accessToken)
            if (clientToken.isNotBlank()) put("clientToken", clientToken)
        }.toString()
        try {
            request("POST", apiRoot + VALIDATE_PATH, body) { status, _ ->
                describeStatus(status, "", "校验登录状态")
            }
            true
        } catch (e: TokenRejected) {
            false
        }
    }

    /** 主动作废令牌（退出登录时用）。失败不抛：服务端不认就算了，本地照样删。 */
    suspend fun invalidate(accessToken: String, clientToken: String = "") {
        if (accessToken.isBlank()) return
        runCatching {
            withContext(Dispatchers.IO) {
                val body = buildJsonObject {
                    put("accessToken", accessToken)
                    if (clientToken.isNotBlank()) put("clientToken", clientToken)
                }.toString()
                request("POST", apiRoot + INVALIDATE_PATH, body) { status, response ->
                    describeStatus(status, response, "作废登录状态")
                }
            }
        }
    }

    /** 令牌被服务端否掉时抛的私有异常，只给 [validate] 用。 */
    private class TokenRejected : RuntimeException()

    private fun request(
        method: String,
        url: String,
        body: String?,
        onFailure: (Int, String) -> String,
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                if (status == 401 || status == 403) throw TokenRejected()
                throw YggdrasilException(onFailure(status, text))
            }
            return text
        } catch (e: TokenRejected) {
            throw e
        } catch (e: YggdrasilException) {
            throw e
        } catch (e: IOException) {
            throw YggdrasilException(
                "连不上认证服务器（" + (e.message ?: e.javaClass.simpleName) + "），检查网络或代理。",
                e,
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val AUTHENTICATE_PATH = "/authserver/authenticate"
        const val REFRESH_PATH = "/authserver/refresh"
        const val VALIDATE_PATH = "/authserver/validate"
        const val INVALIDATE_PATH = "/authserver/invalidate"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * 把用户填的地址归一成认证服务器根。
         *
         * 各家教程里让人填的地址五花八门：「https://littleskin.cn/api/yggdrasil」
         * 「https://xxx/api/yggdrasil/authserver」「https://xxx/」都有人贴。协议里所有
         * 接口都是相对根路径拼的，后面多一段 /authserver 就会拼成 .../authserver/authserver/authenticate ——
         * 所以这两段要剥掉。**只剥一次**：有的服务端根路径本身就叫 authserver。
         */
        fun normalizeApiRoot(raw: String): String {
            var text = raw.trim().trim('"').trim()
            if (text.isEmpty()) return ""
            while (text.endsWith("/")) text = text.substring(0, text.length - 1)
            for (suffix in listOf("/authserver", "/sessionserver", "/api/sessions", "/minecraftservices")) {
                if (text.endsWith(suffix, ignoreCase = true) && text.length > suffix.length) {
                    text = text.substring(0, text.length - suffix.length).trimEnd('/')
                    break
                }
            }
            return text
        }

        /** 地址能不能用；不能用时给一句人话，能用返回 null。 */
        fun apiRootProblem(raw: String): String? {
            val root = normalizeApiRoot(raw)
            if (root.isEmpty()) return "要填认证服务器地址，比如 https://littleskin.cn/api/yggdrasil"
            if (!root.startsWith("http://") && !root.startsWith("https://")) {
                return "地址要以 http:// 或 https:// 开头。"
            }
            // 端口要先切掉：localhost:8080 的域名部分就是 localhost，带着端口会看成「没有点」。
            val host = root.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
            if (host.isBlank() || !host.contains('.') && host != "localhost") {
                return "地址里看不出域名（" + host + "）。"
            }
            return null
        }

        private fun requireApiRoot(raw: String): String {
            val problem = apiRootProblem(raw)
            if (problem != null) throw YggdrasilException(problem)
            return normalizeApiRoot(raw)
        }

        /** 解析 <apiRoot>/ 的响应。不是 JSON 就抛，字段缺了照样返回（元数据本来就是可选的）。 */
        fun parseMetadata(apiRoot: String, text: String): YggdrasilMetadata {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw YggdrasilException(
                    "认证服务器的响应不是 JSON（前 120 个字：" + text.take(120).replace("\n", " ") + "）。",
                    it,
                )
            }
            val meta = runCatching { root["meta"]?.jsonObject }.getOrNull()
            // 有的服务端会在顶层再报一次自己的根路径，以它为准（用户填的可能是镜像地址）。
            val declared = root.str("apiRoot")
            return YggdrasilMetadata(
                apiRoot = declared?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: apiRoot,
                serverName = meta?.str("serverName").orEmpty(),
                implementationName = meta?.str("implementationName").orEmpty(),
                skinDomains = runCatching {
                    root["skinDomains"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                }.getOrNull().orEmpty(),
                signaturePublicKey = root.str("signaturePublickey").orEmpty(),
                raw = text,
            )
        }

        /** 解析 authenticate / refresh 的响应。 */
        fun parseSession(text: String, emptyToken: String = "服务端没有返回 accessToken。"): YggdrasilSession {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
                throw YggdrasilException("认证服务器的响应不是 JSON（前 120 个字：" + text.take(120) + "）。", it)
            }
            val accessToken = root.str("accessToken").orEmpty()
            if (accessToken.isBlank()) throw YggdrasilException(emptyToken)
            val profiles = runCatching {
                root["availableProfiles"]?.jsonArray?.mapNotNull { profileOf(it as? JsonObject) }
            }.getOrNull().orEmpty()
            // selectedProfile 才是这次要用的角色。有些服务端只给 availableProfiles，
            // 那就取第一个 —— 总比报「没有角色」让用户以为登录失败强。
            val selected = runCatching { root["selectedProfile"]?.jsonObject?.let { profileOf(it) } }.getOrNull()
                ?: profiles.firstOrNull()
                ?: throw YggdrasilException(
                    "登录成功了，但这个账号在服务端没有任何游戏角色。先去服务端网站建一个角色再回来。",
                )
            return YggdrasilSession(
                accessToken = accessToken,
                clientToken = root.str("clientToken").orEmpty(),
                profile = selected,
                availableProfiles = profiles,
            )
        }

        private fun profileOf(obj: JsonObject?): YggdrasilProfile? {
            if (obj == null) return null
            val id = obj.str("id").orEmpty()
            val name = obj.str("name").orEmpty()
            if (id.isBlank() || name.isBlank()) return null
            return YggdrasilProfile(id, name)
        }

        /** 登录失败：403 是用户名或密码不对（很多服务端对「没这个账号」也回 403）。 */
        fun describeLoginFailure(status: Int, body: String): String {
            val detail = errorMessageOf(body)
            return when (status) {
                400 -> "服务端不接受这次请求（400）" + withDetail(detail) + "。多半是用户名里有非法字符。"
                401, 403 -> "用户名或密码不对" + withDetail(detail) + "。注意有些服务端要求用邮箱而不是游戏名。"
                404 -> "这个地址上没有认证服务（404）。检查地址末尾是不是漏了 /api/yggdrasil。"
                429 -> "请求太频繁，等一分钟再试。"
                in 500..599 -> "认证服务器自己出错了（" + status + "），过一会儿再试。"
                else -> "登录失败（" + status + "）" + withDetail(detail) + "。"
            }
        }

        /** 其它接口失败时的通用说法。 */
        fun describeStatus(status: Int, body: String, action: String): String {
            val detail = errorMessageOf(body)
            return when (status) {
                401, 403 -> action + "失败：服务端不认这枚令牌（" + status + "）" + withDetail(detail) +
                    "。令牌可能过期或被服务端作废了，回上一页重新登录一次。"
                404 -> action + "失败：这个地址上没有对应的接口（404），检查认证服务器地址。"
                429 -> action + "失败：请求太频繁，等一分钟再试。"
                in 500..599 -> action + "失败：认证服务器自己出错了（" + status + "）。"
                else -> action + "失败（" + status + "）" + withDetail(detail) + "。"
            }
        }

        /** 服务端自己写的那句英文说明，原样附在中文后面当参考。 */
        private fun errorMessageOf(body: String): String = runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            obj.str("errorMessage") ?: obj.str("error")
        }.getOrNull().orEmpty()

        private fun withDetail(detail: String): String =
            if (detail.isBlank()) "" else "（服务端说：" + detail + "）"

        private fun newClientToken(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val hex = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            return hex.toString()
        }

        private const val HEX = "0123456789abcdef"

        private fun JsonObject.str(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }.getOrNull()
    }
}
