package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.server.ServerProperties
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * 服务端占的那个 TCP 端口。
 *
 * 为什么单独一个文件：这件事横跨三处 —— 服务端自己从 `server.properties` 读它、
 * 我们点「启动」之前要先探一次、跑起来之后还要从日志里认领「地址已被占用」。
 * 三处各写一遍「端口是多少」，迟早会有一处写歪（比如 properties 里写的是 25566，
 * 界面却照着 25565 去查）。
 *
 * 这里的函数都是只读的：**不改 server.properties、不占端口**（探完立刻关）。
 */
object ServerPorts {

    /** 原版服务端不写 server-port 时用的端口。 */
    const val DEFAULT_PORT = 25565

    const val PROPERTIES_NAME = ServerProperties.FILE_NAME

    /** 与 [ServerProperties] 那边用的是同一个键名。 */
    private const val PORT_KEY = "server-port"

    fun propertiesFile(dir: File): File = File(dir, PROPERTIES_NAME)

    /**
     * 服务端实际会用的端口。
     *
     * 文件不存在（还没开过服）、没有 server-port 行、值不是合法端口时都退回 [DEFAULT_PORT]；
     * 原版服务端自己也是这么退的，我们跟着退才不会出现「界面说 25566、它开在 25565」。
     */
    fun portOf(dir: File): Int = parsePort(readProperties(dir)) ?: DEFAULT_PORT

    /** 读 properties 文本；读不出来当空串，交给 [portOf] 退回默认值。 */
    fun readProperties(dir: File): String =
        runCatching { propertiesFile(dir).takeIf { it.isFile }?.readText(Charsets.UTF_8) }
            .getOrNull()
            .orEmpty()

    /**
     * 从 properties 文本里取 server-port。
     *
     * 认 `server-port=25565` 与 `server-port = 25565` 两种写法，`#` 开头的注释行不算；
     * 值超出 1..65535 或不是整数时返回 null（服务端自己也会忽略这种值）。
     */
    fun parsePort(properties: String): Int? {
        // 走 ServerProperties 那份解析器，不在这里再写一遍逐行切分：
        // 同一个文件的两种解析方式迟早会分叉（比如一边认 "!" 注释、另一边不认）。
        val raw = ServerProperties.parse(properties).raw(PORT_KEY)?.trim()?.toIntOrNull() ?: return null
        return raw.takeIf { it in 1..65535 }
    }

    /**
     * 这个端口现在能不能绑。
     *
     * 做法就是真的 bind 一次再关掉：绑得上就说明没人听。必须显式关掉 SO_REUSEADDR，
     * 否则 Windows 上（Java 给 ServerSocket 默认打开它）即使别人正在听也会绑成功，
     * 结果是「预检说空闲、服务端照样起不来」—— 比不检查更糟。
     */
    fun isFree(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(port), 1)
        }
        true
    }.getOrDefault(false)

    /** 占着某个端口的进程。用来把「25565 被占了」变成「被 java.exe（pid 1234）占了」。 */
    data class PortOwner(val port: Int, val pid: Long, val name: String = "")

    /**
     * 谁在听这个端口。
     *
     * 只在 Windows 上查（`netstat -ano`）；查不到、超时、别的平台一律返回空 ——
     * 提示里就只说「被别的程序占用」，不猜。调用方不要把它当必需信息。
     */
    fun owners(port: Int): List<PortOwner> {
        if (!isWindows()) return emptyList()
        val text = runCatching {
            val process = ProcessBuilder("netstat", "-ano").redirectErrorStream(true).start()
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return emptyList()
            }
            process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(WHITESPACE)
            if (parts.size < 5) return@mapNotNull null
            if (!parts[0].equals("TCP", ignoreCase = true)) return@mapNotNull null
            if (!parts[3].equals("LISTENING", ignoreCase = true)) return@mapNotNull null
            if (!parts[1].endsWith(":" + port)) return@mapNotNull null
            val pid = parts[4].toLongOrNull() ?: return@mapNotNull null
            PortOwner(port = port, pid = pid, name = processName(pid))
        }.distinctBy { it.pid }.toList()
    }

    /** 进程名叫什么；拿不到就空串（提示里只写 pid，不编）。 */
    private fun processName(pid: Long): String = runCatching {
        ProcessHandle.of(pid).map { handle ->
            val command = handle.info().command().orElse("")
            val fallback = handle.info().commandLine().orElse("")
            val source = command.ifBlank { fallback }
            source.substringAfterLast('\\').substringAfterLast('/')
        }.orElse("")
    }.getOrDefault("")

    /**
     * 启动之前就发现端口被占时的说明。
     *
     * 直接挡住不让启动，而不是放过去让服务端自己崩：那样用户在界面上看到的是
     * 「日志刷了两行然后变回已停止」，而真实原因（那句英文）藏在中间，很容易漏。
     */
    fun occupiedHint(dir: File, port: Int): String {
        val people = owners(port).joinToString("、") { owner ->
            val name = owner.name.ifBlank { "某个程序" }
            name + "（pid " + owner.pid + "）"
        }
        val who = if (people.isBlank()) "" else "现在占着它的是 " + people + "。"
        return port.toString() + " 端口已经被别的程序占着，服务端绑不上，启动会立刻退出。" + who +
            "要改的是 " + propertiesFile(dir).absolutePath + " 里的 server-port 那一行，" +
            "换一个没人用的端口再点启动。另一种可能是你原来那个服务端还开着 —— 那就先把它关掉，不用改端口。"
    }

    /**
     * 这行日志是不是「绑不上端口」。
     *
     * 四种写法都要认，因为不同加载器报的不一样：原版是 `**** FAILED TO BIND TO PORT!`
     * 加 `Perhaps a server is already running on that port?`，Paper 与插件混着的时候
     * 经常只剩 `java.net.BindException: Address already in use`。
     */
    fun looksLikeBindFailure(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("failed to bind to port") ||
            lower.contains("address already in use") ||
            lower.contains("already running on that port") ||
            lower.contains("bind failed")
    }

    /**
     * 日志里已经出现绑定失败时的说明。
     *
     * 与 [occupiedHint] 分开是因为这一条是**事后**认领：预检说端口空闲、服务端自己却绑不上，
     * 常见于「读到的 properties 跟服务端读的不是同一份」（用户刚手改过，或者服务端从别的
     * 工作目录起）。这时候不该再断言端口被谁占，只给下一步。
     */
    fun bindFailureHint(dir: File, port: Int): String =
        "服务端在绑定 " + port + " 端口时失败了（日志里那句 FAILED TO BIND TO PORT / " +
            "Address already in use 就是这个意思）。最常见的原因是这个端口已经有别的程序在听，" +
            "包括你自己已经开着的另一个服务端。改 " + propertiesFile(dir).absolutePath +
            " 里的 server-port 换一个端口是最快的办法；如果你确定没别人占，重启一次电脑也能清掉。"

    private val WHITESPACE = Regex("\\s+")

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
