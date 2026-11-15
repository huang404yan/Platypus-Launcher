package io.github.huang404yan.platypus.core.mods

import java.io.File
import java.io.IOException

/**
 * CurseForge 认的那种文件指纹：MurmurHash2（32 位，种子 1），但**先去掉空白字节**。
 *
 * 三点都和直觉不一样，改之前先看清楚：
 *
 * 1. **要先把 0x09 / 0x0A / 0x0D / 0x20 全部删掉再算**。CurseForge 这么干是为了让换了行尾
 *    （CRLF 与 LF）或末尾多个换行的同一个文件算出同一个指纹。我们用同样的规则，否则同一个
 *    jar 在我们这边算出来的值和服务端库里存的根本对不上，反查必然一条都命不中。
 * 2. **长度是删完空白之后的字节数**，不是文件大小。种子与长度一起决定初值，用文件大小会算错。
 * 3. **结果是 32 位无符号数**，Kotlin 的 Int 装不下大于 2^31 的值，所以对外一律用 Long
 *    （见 [unsigned]）；写进 JSON 与请求体时必须是这个无符号值。
 *
 * 算法本身逐行对照 PCL 的 `LocalResourceFile.CurseForgeHash` 实现（VB.NET），包括它那个
 * 先乘 M 再异或右移 24 再乘 M 的 k 处理顺序 —— 这是 MurmurHash2 的标准写法，不是 PCL 自创。
 */
object CurseForgeFingerprint {

    /** MurmurHash2 用的种子。CurseForge 固定用 1（不是 0，换成 0 会全盘对不上）。 */
    const val SEED: Int = 1

    /** MurmurHash2 的魔数 0x5BD1E995。 */
    private const val M: Int = 0x5BD1E995.toInt()

    /** 计算前要丢掉的字节：制表符、换行、回车、空格。 */
    private fun ignored(value: Int): Boolean = value == 9 || value == 10 || value == 13 || value == 32

    /** 算一段字节的指纹，返回无符号值。 */
    fun of(bytes: ByteArray): Long = unsigned(hashOf(withoutWhitespace(bytes)))

    /** 算一个文件的指纹。读不动抛 IOException，message 里带文件名。 */
    fun of(file: File): Long {
        val length = try {
            countStripped(file)
        } catch (e: IOException) {
            throw IOException("读不了 " + file.name + "：" + (e.message ?: e.javaClass.simpleName), e)
        }
        val data = try {
            readStripped(file, length)
        } catch (e: IOException) {
            throw IOException("读不了 " + file.name + "：" + (e.message ?: e.javaClass.simpleName), e)
        }
        return unsigned(hashOf(data))
    }

    /** 把 32 位有符号结果转成对外用的无符号值。 */
    fun unsigned(hash: Int): Long = hash.toLong() and 0xFFFFFFFFL

    /** 去掉空白字节。单测与 `of(ByteArray)` 共用，别在两处各写一份。 */
    internal fun withoutWhitespace(bytes: ByteArray): ByteArray {
        var length = 0
        for (b in bytes) if (!ignored(b.toInt() and 0xFF)) length++
        if (length == bytes.size) return bytes
        val out = ByteArray(length)
        var index = 0
        for (b in bytes) {
            val value = b.toInt() and 0xFF
            if (!ignored(value)) {
                out[index] = b
                index++
            }
        }
        return out
    }

    /** 已经是「去掉空白之后」的字节，直接算。 */
    private fun hashOf(data: ByteArray): Int {
        val length = data.size
        var h = SEED xor length
        var i = 0
        while (length - i >= 4) {
            var k = (data[i].toInt() and 0xFF) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                ((data[i + 2].toInt() and 0xFF) shl 16) or
                ((data[i + 3].toInt() and 0xFF) shl 24)
            k *= M
            k = k xor (k ushr 24)
            k *= M
            h *= M
            h = h xor k
            i += 4
        }
        when (length - i) {
            3 -> {
                h = h xor ((data[i].toInt() and 0xFF) or
                    ((data[i + 1].toInt() and 0xFF) shl 8) or
                    ((data[i + 2].toInt() and 0xFF) shl 16))
                h *= M
            }
            2 -> {
                h = h xor ((data[i].toInt() and 0xFF) or
                    ((data[i + 1].toInt() and 0xFF) shl 8))
                h *= M
            }
            1 -> {
                h = h xor (data[i].toInt() and 0xFF)
                h *= M
            }
        }
        h = h xor (h ushr 13)
        h *= M
        h = h xor (h ushr 15)
        return h
    }

    /** 第一遍：数出「去掉空白之后」有多少字节（MurmurHash2 的初值要用长度）。 */
    private fun countStripped(file: File): Long {
        var count = 0L
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                for (i in 0 until read) {
                    if (!ignored(buffer[i].toInt() and 0xFF)) count++
                }
            }
        }
        return count
    }

    /** 第二遍：按第一遍数出来的长度读成数组。两次读盘换来不吃内存。 */
    private fun readStripped(file: File, length: Long): ByteArray {
        if (length > Int.MAX_VALUE) throw IOException(file.name + " 太大（" + length + " 字节），算不了指纹")
        val out = ByteArray(length.toInt())
        var index = 0
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                for (i in 0 until read) {
                    val value = buffer[i].toInt() and 0xFF
                    if (ignored(value)) continue
                    if (index >= out.size) break
                    out[index] = buffer[i]
                    index++
                }
            }
        }
        return out
    }
}