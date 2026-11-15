package io.github.huang404yan.platypus.core.server

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/** RCON 出错时抛它，message 一律是能直接给用户看的中文。 */
class RconException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 一条 RCON 包。
 *
 * 线路格式（全部小端）：长度 4 字节 = 下面三项之和；然后是请求号 4 字节、类型 4 字节、
 * 正文（UTF-8），最后两个 0x00 收尾。长度里已经把这两个 0 算进去了。
 *
 * 类型只有三种会出现在 Minecraft 上：3 = 登录，2 = 命令（也是登录的回执），0 = 命令的输出。
 */
data class RconPacket(val id: Int, val type: Int, val payload: String) {
    companion object {
        const val TYPE_RESPONSE = 0
        const val TYPE_COMMAND = 2
        const val TYPE_LOGIN = 3

        /** 密码错时服务端回的请求号。 */
        const val AUTH_FAILED_ID = -1

        /** 正文上限。超过它多半说明连上的不是 RCON，而是别的什么端口。 */
        const val MAX_PAYLOAD_BYTES = 64 * 1024
    }
}

/**
 * 从 server.properties 读出来的 RCON 设置。
 *
 * 三个键分别是 enable-rcon / rcon.port / rcon.password。原版默认不开，端口 25575，密码空。
 */
data class RconSettings(
    val enabled: Boolean = false,
    val port: Int = RconClient.DEFAULT_PORT,
    val password: String = "",
) {
    /** 现在能不能连。三个条件缺一个都连不上。 */
    val usable: Boolean get() = problem == null

    /** 不能用时给一句人话，能用时为 null。 */
    val problem: String?
        get() = when {
            !enabled -> "RCON 没开：server.properties 里 enable-rcon 还是 false。"
            password.isBlank() -> "RCON 开了但没设密码，服务端不会接受任何连接。"
            port !in 1..65535 -> "RCON 端口 " + port + " 不是一个合法端口。"
            else -> null
        }
}

/**
 * RCON 客户端。
 *
 * 为什么要它：现在控制台发命令是往服务端进程的 stdin 里写，这只有「我们自己起的那个进程」
 * 才有。用户双击 start.bat 起的、或者用别的启动器起的服务端，我们拿不到它的 stdin；
 * 而 RCON 是一条标准 TCP 通道，只要服务端开了，谁起的都能连。
 *
 * 只做阻塞 IO、不引协程：调用方本来就在 Dispatchers.IO 里跑，套一层 suspend 只会多一层。
 * 用完要 [close]，建议用 use { }。
 */
class RconClient(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val password: String,
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MS,
    private val replyTimeoutMillis: Int = REPLY_TIMEOUT_MS,
    private val continuationTimeoutMillis: Int = CONTINUATION_TIMEOUT_MS,
) : AutoCloseable {

    private var socket: Socket? = null
    private var nextRequestId: Int = 1

    val connected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    /**
     * 连上并登录。成功返回自己（方便写成 use { }），失败抛 [RconException] 且不留下半个连接。
     */
    fun connect(): RconClient {
        val client = Socket()
        try {
            client.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            client.soTimeout = replyTimeoutMillis
            // 命令要立刻发出去，攒包会让「输入回车到看见回执」的延迟多几十毫秒。
            client.tcpNoDelay = true
        } catch (e: IOException) {
            runCatching { client.close() }
            throw RconException("连不上 RCON（" + host + ":" + port + "）：" + reasonOf(e), e)
        }
        socket = client
        val reply = try {
            exchange(RconPacket(takeId(), RconPacket.TYPE_LOGIN, password))
        } catch (e: Exception) {
            close()
            throw e as? RconException ?: RconException("RCON 登录时连接断了：" + reasonOf(e), e)
        }
        if (reply.id == RconPacket.AUTH_FAILED_ID) {
            close()
            throw RconException("RCON 密码不对（服务端拒绝了登录）。去 server.properties 里核对 rcon.password。")
        }
        if (reply.type != RconPacket.TYPE_COMMAND) {
            close()
            throw RconException("RCON 登录后收到的不是认证回执（type=" + reply.type + "），这个端口可能不是 RCON。")
        }
        return this
    }

    /**
     * 发一条命令，返回服务端的输出（多行已用换行拼接）。
     *
     * 空命令返回空串，不碰网络 —— 界面上回车发出一个空的输入框不该产生一次连接。
     */
    fun command(text: String): String {
        val body = text.trim()
        if (body.isEmpty()) return ""
        val client = socket ?: throw RconException("RCON 还没连上。")
        val first = try {
            exchange(RconPacket(takeId(), RconPacket.TYPE_COMMAND, body))
        } catch (e: SocketTimeoutException) {
            throw RconException("服务端 " + replyTimeoutMillis + " 毫秒没有回应。这条命令可能没被执行。", e)
        } catch (e: IOException) {
            throw RconException("发命令时连接断了：" + reasonOf(e), e)
        }
        if (first.type != RconPacket.TYPE_RESPONSE) {
            throw RconException("服务端对命令回了一个意料之外的包（type=" + first.type + "）。")
        }
        val builder = StringBuilder(first.payload)
        // 长回执会被拆成好几条。这里把「还收得到」的都拼上，用一段短超时判断收完了。
        client.soTimeout = continuationTimeoutMillis
        try {
            while (true) {
                val next = try { readPacket() } catch (e: SocketTimeoutException) { break }
                if (next.type != RconPacket.TYPE_RESPONSE) break
                if (next.payload.isEmpty()) break
                if (builder.isNotEmpty()) builder.append(System.lineSeparator())
                builder.append(next.payload)
            }
        } catch (e: IOException) {
            // 已经拿到一部分了，把已有的交出去比丢一个异常有用。
        } finally {
            runCatching { client.soTimeout = replyTimeoutMillis }
        }
        return builder.toString()
    }

    override fun close() {
        val client = socket
        socket = null
        runCatching { client?.close() }
    }

    private fun takeId(): Int {
        // 请求号只要同一次连接里不重复就行；溢出回绕也无所谓。
        val id = nextRequestId
        nextRequestId = if (nextRequestId == Int.MAX_VALUE) 1 else nextRequestId + 1
        return id
    }

    private fun exchange(packet: RconPacket): RconPacket {
        writePacket(packet)
        return readPacket()
    }

    private fun writePacket(packet: RconPacket) {
        val client = socket ?: throw RconException("RCON 还没连上。")
        val payload = packet.payload.toByteArray(Charsets.UTF_8)
        if (payload.size > RconPacket.MAX_PAYLOAD_BYTES) {
            throw RconException("这条命令太长（" + payload.size + " 字节），RCON 一包最多 " + RconPacket.MAX_PAYLOAD_BYTES + " 字节。")
        }
        val length = 4 + 4 + payload.size + 2
        val out = client.getOutputStream()
        out.write(littleBytes(length))
        out.write(littleBytes(packet.id))
        out.write(littleBytes(packet.type))
        out.write(payload)
        out.write(0)
        out.write(0)
        out.flush()
    }

    private fun readPacket(): RconPacket {
        val client = socket ?: throw RconException("RCON 还没连上。")
        val input = client.getInputStream()
        val length = readLittleInt(input)
        if (length < 10 || length > RconPacket.MAX_PAYLOAD_BYTES) {
            throw RconException("RCON 返回的包长度不对（" + length + "），这个地址多半不是 RCON 服务。")
        }
        val body = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val n = input.read(body, filled, length - filled)
            if (n < 0) throw RconException("RCON 连接被对端关了（包读到一半就没了）。")
            filled += n
        }
        val id = littleIntAt(body, 0)
        val type = littleIntAt(body, 4)
        // 末尾两个字节是终止符，不算正文。
        val payload = String(body, 8, length - 10, Charsets.UTF_8)
        return RconPacket(id, type, payload)
    }

    companion object {
        /** RCON 只该在回环上用。放到外网上等于把管理员权限挂到公网。 */
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 25575
        const val CONNECT_TIMEOUT_MS = 3_000
        const val REPLY_TIMEOUT_MS = 5_000

        /** 判断「还有没有后续包」用的短超时。设长了每条命令都要白等这么久。 */
        const val CONTINUATION_TIMEOUT_MS = 300

        const val ENABLE_KEY = "enable-rcon"
        const val PORT_KEY = "rcon.port"
        const val PASSWORD_KEY = "rcon.password"

        fun settingsOf(view: ServerPropertiesView): RconSettings = RconSettings(
            enabled = view.boolean(ENABLE_KEY, false),
            port = view.raw(PORT_KEY)?.trim()?.toIntOrNull() ?: DEFAULT_PORT,
            password = view.string(PASSWORD_KEY, ""),
        )

        fun settingsOf(dir: File): RconSettings = settingsOf(ServerProperties.read(dir))

        /**
         * 建议一个密码。
         *
         * 不用容易看错的字符（0/O、1/I），用户可能要照着屏幕手抄到另一台机器上。
         */
        fun suggestPassword(length: Int = 16): String {
            val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
            val random = SecureRandom()
            val builder = StringBuilder(length)
            repeat(length) { builder.append(alphabet[random.nextInt(alphabet.length)]) }
            return builder.toString()
        }

        /** 把异常翻译成一句原因，别把整串类名丢给用户。 */
        internal fun reasonOf(error: Throwable): String = when (error) {
            is SocketTimeoutException -> "连接超时"
            is IOException -> error.message ?: "网络错误"
            else -> error.message ?: error::class.simpleName.orEmpty()
        }

        private fun littleBytes(value: Int): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )

        private fun littleIntAt(body: ByteArray, offset: Int): Int {
            val b0 = body[offset].toInt() and 0xFF
            val b1 = body[offset + 1].toInt() and 0xFF
            val b2 = body[offset + 2].toInt() and 0xFF
            val b3 = body[offset + 3].toInt() and 0xFF
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        private fun readLittleInt(input: InputStream): Int {
            val b0 = input.read()
            if (b0 < 0) throw RconException("RCON 连接被对端关了。")
            val b1 = input.read()
            val b2 = input.read()
            val b3 = input.read()
            if (b1 < 0 || b2 < 0 || b3 < 0) throw RconException("RCON 连接被对端关了（包读到一半）。")
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
    }
}
