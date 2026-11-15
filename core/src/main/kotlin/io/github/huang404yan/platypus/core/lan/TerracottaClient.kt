package io.github.huang404yan.platypus.core.lan

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists

/**
 * 跟陶瓦守护进程说话的那一层：起进程、找端口、发 HTTP。
 *
 * 三条与 PCL 自编版对齐的硬约束（都是实测踩出来的）：
 *  1. 端口不是固定的：terracotta.exe --hmcl <文件> 启动后，把 {"port":N} 写进那个文件，
 *     必须轮询等它出现（实测 0.1 秒，但冷启动可能要几秒）。父进程会立刻退出把活交给子进程，
 *     所以不能拿父进程存活当判据。
 *  2. 本地回环必须禁代理：系统代理开着时 127.0.0.1 也会被劫持，表现为 502。
 *  3. 陶瓦是状态机：上一次失败会停在 exception，此时再发 /state/guesting 会被拒。
 *     所以每次切状态前先发 /state/ide 拉回空闲。
 *
 * 这个类不做线程调度，调用方自己去 IO 线程跑 —— 每次请求最长几秒，压界面线程会卡。
 */
class TerracottaClient(
    private val dir: File,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
    private val startTimeoutMillis: Long = DEFAULT_START_TIMEOUT_MS,
) {

    /** exe 的路径；没装返回 null。 */
    val exe: File? get() = Terracotta.exeOf(dir)

    /** 装没装。 */
    val installed: Boolean get() = exe != null

    /** 上一次成功连上的端口，-1 表示还没有。 */
    @Volatile
    private var port: Int = -1

    /**
     * 找一个正在跑的守护进程，**不启动新进程**。没有返回 -1。
     *
     * 锁文件里可能是别的启动器（HMCL）起的，本机回环能通就直接复用 ——
     * 用户同时开着两个启动器时，各起一个守护进程会互相抢端口。
     */
    fun runningPort(): Int {
        if (port > 0 && alive(port)) return port
        val locked = lockFilePort()
        if (locked > 0 && alive(locked)) {
            port = locked
            return locked
        }
        return -1
    }

    /**
     * 确保守护进程在跑（必要时启动），返回端口；起不来返回 -1。
     *
     * 启动方式与陶瓦的 HMCL 集成契约一致：给一个临时文件路径，它把端口写进去。
     */
    fun ensure(): Int {
        val running = runningPort()
        if (running > 0) return running
        val executable = exe ?: return -1

        val portFile = try {
            createTempFile(prefix = "platypus-terracotta-", suffix = ".json")
        } catch (e: IOException) {
            return -1
        }
        try {
            val process = ProcessBuilder(
                executable.absolutePath,
                "--hmcl",
                portFile.toAbsolutePath().toString(),
            )
                .directory(executable.parentFile ?: dir)
                .redirectErrorStream(true)
                .start()
            // 父进程立刻退出是正常的，这里只负责别让输出管道堆满。
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }

            val deadline = System.currentTimeMillis() + startTimeoutMillis
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_MILLIS)
                val text = runCatching { Files.readString(portFile) }.getOrNull()
                if (text.isNullOrBlank()) continue
                val parsed = Terracotta.parsePort(text) ?: continue
                if (alive(parsed)) {
                    port = parsed
                    return parsed
                }
            }
            return -1
        } catch (e: IOException) {
            return -1
        } finally {
            runCatching { portFile.deleteIfExists() }
        }
    }

    /**
     * 读一次状态。端口没有且 [autoStart] 为假时直接返回空状态 ——
     * 界面每秒轮询一次，不能因为轮询就把进程拉起来。
     */
    fun state(autoStart: Boolean = false): TerracottaState {
        val target = if (autoStart) ensure() else runningPort()
        if (target <= 0) return TerracottaState()
        val text = try {
            httpGet(target, "/state", timeoutMillis)
        } catch (e: IOException) {
            port = -1
            return TerracottaState()
        }
        return Terracotta.parseState(text)
    }

    /**
     * 创建房间。返回给用户看的一句话。
     *
     * 会先读一次当前状态：如果已经有一个房间（可能是 HMCL 起的，也可能是上次没复位的），
     * 下面的 /state/scanning 会重建房间并换码 —— 这件事必须明说，
     * 否则用户以为复用了原码，把旧码发给朋友后对方连不上，而两边都不报错。
     */
    fun host(roomCode: String = ""): String {
        val target = ensure()
        if (target <= 0) return notInstalledText()

        val before = state(false)
        val oldRoom = if (before.ok && before.stage == TerracottaStages.HOST_OK) before.room else ""

        ensureIdle(target)

        val query = if (roomCode.isBlank()) "" else "?room=" + encode(roomCode.trim())
        try {
            httpGet(target, "/state/scanning" + query, LONG_TIMEOUT_MS)
        } catch (e: IOException) {
            return "启动建房失败：" + (e.message ?: "连不上陶瓦")
        }

        val after = state(false)
        if (oldRoom.isNotBlank() && !oldRoom.equals(after.room, ignoreCase = true)) {
            if (after.room.isBlank()) {
                return "已接管原有房间，旧码 " + oldRoom + " 已失效，正在生成新的房间码，稍等几秒。"
            }
            return "已接管原有房间，房间码已更换为 " + after.room + "；旧码 " + oldRoom +
                " 已失效，请把新码发给朋友。"
        }
        if (after.stage == TerracottaStages.HOST_SCANNING) {
            return "已开始等待：请切到游戏，按 Esc →「对局域网开放」→ 点「开始」，房间码随后出现。"
        }
        return "已发出建房请求，当前状态：" + Terracotta.stageText(after.stage)
    }

    /** 加入房间。房间码不正时直接返回提示，不发请求。 */
    fun join(rawCode: String): String {
        val code = Terracotta.normalizeCode(rawCode)
        if (code.isBlank()) return Terracotta.codeFormatProblem()
        if (!Terracotta.checkCode(code)) return Terracotta.codeChecksumProblem()

        val target = ensure()
        if (target <= 0) return notInstalledText()
        ensureIdle(target)

        try {
            httpGet(target, "/state/guesting?room=" + encode(code), LONG_TIMEOUT_MS)
        } catch (e: IOException) {
            return "加入失败：" + (e.message ?: "连不上陶瓦")
        }
        return "正在加入房间 " + code + "，连上后进游戏「多人游戏」双击「陶瓦联机大厅」即可。"
    }

    /** 复位到空闲（取消建房 / 取消加入）。不启动新进程。 */
    fun reset() {
        val target = runningPort()
        if (target <= 0) return
        runCatching { httpGet(target, "/state/ide", RESET_TIMEOUT_MS) }
    }

    /**
     * 切状态前先把守护进程拉回空闲。复位失败不阻断主流程：
     * 目标状态请求本身会返回错误，交给上层提示。
     */
    fun ensureIdle(target: Int) {
        if (target <= 0) return
        runCatching { httpGet(target, "/state/ide", RESET_TIMEOUT_MS) }
    }

    /**
     * 关闭守护进程。它会直接 exit、不回响应，连接被重置属正常，所以异常吞掉。
     * 关掉之后房间码必然作废，调用方要同步清空界面上的码。
     */
    fun shutdown() {
        val target = runningPort()
        if (target <= 0) return
        runCatching { httpGet(target, "/panic?peaceful=true", RESET_TIMEOUT_MS) }
        port = -1
    }

    /** 还没装时的说法。 */
    fun notInstalledText(): String =
        "还没装陶瓦联机：点「安装 / 更新陶瓦」自动下载（约 8 MB）。"

    // ── 内部 ───────────────────────────────────────────────────────────────────

    /** 探活：该端口上是否有陶瓦在应答。/meta 会回一段含 version 的 JSON。 */
    private fun alive(target: Int): Boolean {
        if (target <= 0) return false
        return try {
            httpGet(target, "/meta", PROBE_TIMEOUT_MS).contains("version")
        } catch (e: IOException) {
            false
        }
    }

    /**
     * 读全局锁文件里的端口（前两字节，大端）。文件不存在或读不到返回 -1。
     * 必须用共享读打开：守护进程正持有这个文件。
     */
    private fun lockFilePort(): Int {
        val file = File(File(System.getProperty("java.io.tmpdir") ?: ".", "terracotta"), "terracotta.lock")
        if (!file.isFile) return -1
        return try {
            RandomAccessFile(file, "r").use { handle ->
                val first = handle.read()
                val second = handle.read()
                if (first < 0 || second < 0) -1 else (first shl 8) or second
            }
        } catch (e: IOException) {
            -1
        }
    }

    /**
     * 发一个 GET。**显式禁用代理**：本地回环走系统代理会被劫持成 502，
     * 而用户开代理是常事（这台机器上的下载器都带代理）。
     */
    private fun httpGet(target: Int, path: String, timeout: Int): String {
        val url = URL("http://127.0.0.1:" + target + path)
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                throw IOException(
                    "陶瓦返回 " + code + (if (body.isNullOrBlank()) "" else "：" + body.take(200)),
                )
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** 房间码里可能带 / 与空格，必须转义再放进查询串。 */
    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    companion object {
        /** 普通请求（/state）的超时。轮询每秒一次，不能等太久。 */
        const val DEFAULT_TIMEOUT_MS: Int = 4_000

        /** 建房 / 加入这类会触发打洞的请求，陶瓦自己也要等一会儿。 */
        const val LONG_TIMEOUT_MS: Int = 5_000

        /** 复位 / 探活这类小请求。 */
        const val RESET_TIMEOUT_MS: Int = 2_500

        /** 探活。/meta 是纯本地计算，很快就回。 */
        const val PROBE_TIMEOUT_MS: Int = 1_500

        /** 等端口文件的上限。冷启动实测 0.1 秒，给 20 秒留足杀毒软件扫描的时间。 */
        const val DEFAULT_START_TIMEOUT_MS: Long = 20_000L

        /** 等端口文件的轮询间隔。 */
        private const val POLL_MILLIS: Long = 200L
    }
}