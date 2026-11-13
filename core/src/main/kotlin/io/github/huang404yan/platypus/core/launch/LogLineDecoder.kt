package io.github.huang404yan.platypus.core.launch

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 把游戏 stdout 的**字节**翻译成一行文字。
 *
 * 为什么要问编码这件麻烦事：游戏进程在中文 Windows 上按系统代码页（GBK）写 stdout，
 * 而启动器一直按 UTF-8 读，于是日志里所有中文都变成 U+FFFD（那串「����」）。
 * 这个损失发生在**解码**那一刻：GBK 的字节被 UTF-8 解码器判成非法序列，直接替换成
 * 替换符，原始字节拿不回来。所以在读侧按 UTF-8 强行解是错的。
 *
 * 两条路一起上，谁先立住用谁：
 * 1. 启动参数里传 `-Dstdout.encoding=UTF-8` / `-Dfile.encoding=UTF-8`（见 [LaunchArguments.chineseLogArgs]），
 *    让新一点的 JVM 干脆按 UTF-8 输出；
 * 2. 万一 JVM 不认这两个属性（或者模组自己往 stdout 写 GBK 字节），这里按「先严格 UTF-8、
 *    失败再 GBK」的顺序兜底。
 *
 * 为什么用**严格**解码试而不是直接看有没有 U+FFFD：替换符出现时已经丢字节了，用 REPORT 模式
 * 抛异常才能拿到「这行不是 UTF-8」这个事实，然后整行重解一次。
 */
object LogLineDecoder {

    /** 中文 Windows 的系统代码页。JDK 与安卓都自带，取不到就退回平台默认。 */
    private val gbk: Charset? = runCatching { Charset.forName("GBK") }.getOrNull()

    /**
     * 解一行。[length] 允许小于 [bytes] 长度（复用读缓冲时用），默认整块都是有效字节。
     *
     * 顺序：严格 UTF-8 → 严格 GBK → 宽松 UTF-8（放替换符，至少别抛异常把日志读断了）。
     */
    fun decode(bytes: ByteArray, length: Int = bytes.size): String {
        if (length <= 0) return ""
        val slice = if (length == bytes.size) bytes else bytes.copyOf(length)
        strictDecode(slice, Charsets.UTF_8)?.let { return it }
        if (gbk != null) strictDecode(slice, gbk)?.let { return it }
        return String(slice, Charsets.UTF_8)
    }

    /** 这一行「像不像 GBK 中文」，只用于测试与诊断，不参与上面的决策。 */
    fun looksLikeGbk(bytes: ByteArray): Boolean =
        strictDecode(bytes, Charsets.UTF_8) == null && gbk?.let { strictDecode(bytes, it) != null } == true

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}

/**
 * 按行读字节流，交给 [LogLineDecoder] 解码。
 *
 * 为什么不用 `BufferedReader(process.inputStream.reader(Charsets.UTF_8)).readLine()`：
 * 那样解码发生在读到字符之前，编码判断的余地就没了。这里自己切行，切完再决定用哪种编码解。
 *
 * 只认 `\n` 与 `\r\n`：游戏输出不会有孤立的 \r（那是老 Mac 的换行，Minecraft 从来不用）。
 */
class LogLineReader(private val input: InputStream) : Closeable {

    private companion object {
        /** 单行上限。原版启动器不设限，但一个卡死的模组能让一行涨到几百 MB。 */
        const val MAX_LINE_BYTES = 64 * 1024
    }

    private val buffer = ByteArray(8192)
    private var position = 0
    private var limit = 0

    /** 读一行；流结束返回 null。行尾的 \n 与 \r 会被去掉。 */
    fun readLine(): String? {
        val line = ByteArrayOutputStream(128)
        while (true) {
            if (position >= limit) {
                limit = input.read(buffer)
                position = 0
                if (limit <= 0) {
                    // 流结束时最后一行可能没有换行符，有内容就交出去。
                    return if (line.size() > 0) LogLineDecoder.decode(line.toByteArray()) else null
                }
            }
            val byte = buffer[position++].toInt() and 0xFF
            if (byte == '\n'.code) {
                val bytes = line.toByteArray()
                val trimmed = if (bytes.isNotEmpty() && bytes[bytes.size - 1].toInt() == '\r'.code) {
                    bytes.copyOf(bytes.size - 1)
                } else {
                    bytes
                }
                return LogLineDecoder.decode(trimmed)
            }
            if (line.size() < MAX_LINE_BYTES) line.write(byte)
        }
    }

    override fun close() {
        runCatching { input.close() }
    }
}
