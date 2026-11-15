package io.github.huang404yan.platypus.core.saves

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * 读存档目录里的 `level.dat`。
 *
 * 为什么自己写一个 NBT reader（三十来行）而不是引依赖：整份存档管理只用到
 * 这一个文件、这几个字段，而 `level.dat` 是 gzip 压的 NBT，结构稳定了十几年。
 * 引一个 NBT 库要多带一份依赖进安卓包，代价比收益大。
 *
 * **失败一律返回 null，不抛异常。** 目录里那份 `level.dat` 可能是玩家从网上下的、
 * 被半途写坏的、甚至根本不是存档；界面上要照常把这个世界列出来（让人能备份、能删），
 * 只是显示名退回目录名 —— 而不是整个列表因为一个坏文件打不开。
 *
 * 字段名对照原版：`Data.LevelName`、`Data.LastPlayed`（毫秒）、`Data.GameType`（0 生存
 * / 1 创造 / 2 冒险 / 3 旁观）、`Data.hardcore`、`Data.allowCommands`、`Data.Version.Name`、
 * `Data.generatorName`。`hardcore` 与 `allowCommands` 是小写开头，这是原版自己起的名字，
 * 不是笔误。
 */
object LevelDat {
    /** 存档的元数据文件就在世界目录根上。 */
    const val FILE_NAME = "level.dat"

    /**
     * 从 `level.dat` 读出来的几项。
     *
     * 全是「读不到就用默认值」的形态：[gameType] 是可空的，因为老存档里可能没有这个键，
     * 而 0（生存）是合法值，不能拿 0 当「没读到」。
     */
    data class Info(
        val levelName: String = "",
        val lastPlayed: Long = 0L,
        val sizeOnDisk: Long = 0L,
        val gameType: Int? = null,
        val hardcore: Boolean = false,
        val allowCommands: Boolean = false,
        val versionName: String = "",
        val generator: String = "",
    )

    /** 读一个世界目录；没有 `level.dat` 或读不动时返回 null。 */
    fun read(worldDir: File): Info? {
        val file = File(worldDir, FILE_NAME)
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size < 3) return null
            // 极少数工具写出来的 level.dat 是未压缩的裸 NBT；gzip 头是 1F 8B，认一下不费事。
            val payload = if (bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            } else {
                bytes
            }
            val root = parse(payload) ?: return null
            val data = root["Data"] as? Map<*, *> ?: return null
            Info(
                levelName = (data["LevelName"] as? String).orEmpty(),
                lastPlayed = (data["LastPlayed"] as? Long) ?: 0L,
                sizeOnDisk = (data["SizeOnDisk"] as? Long) ?: 0L,
                gameType = data["GameType"] as? Int,
                hardcore = (data["hardcore"] as? Byte)?.toInt() == 1,
                allowCommands = (data["allowCommands"] as? Byte)?.toInt() == 1,
                versionName = ((data["Version"] as? Map<*, *>)?.get("Name") as? String).orEmpty(),
                generator = (data["generatorName"] as? String).orEmpty(),
            )
        }.getOrNull()
    }

    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12

    private fun parse(bytes: ByteArray): Map<String, Any?>? {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readUnsignedByte() != TAG_COMPOUND) return null
            input.readUTF() // 根标签的名字，原版写空串
            return readCompound(input)
        }
    }

    private fun readCompound(input: DataInputStream): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            val type = input.readUnsignedByte()
            if (type == TAG_END) return map
            val name = input.readUTF()
            map[name] = readPayload(input, type)
        }
    }

    private fun readPayload(input: DataInputStream, type: Int): Any? = when (type) {
        TAG_BYTE -> input.readByte()
        TAG_SHORT -> input.readShort()
        TAG_INT -> input.readInt()
        TAG_LONG -> input.readLong()
        TAG_FLOAT -> input.readFloat()
        TAG_DOUBLE -> input.readDouble()
        TAG_BYTE_ARRAY -> {
            val size = readLength(input)
            ByteArray(size).also { input.readFully(it) }
        }
        TAG_STRING -> input.readUTF()
        TAG_LIST -> {
            val elementType = input.readUnsignedByte()
            val size = readLength(input)
            val list = ArrayList<Any?>(size)
            repeat(size) { list.add(readPayload(input, elementType)) }
            list
        }
        TAG_COMPOUND -> readCompound(input)
        TAG_INT_ARRAY -> {
            val size = readLength(input)
            IntArray(size).also { array -> for (i in 0 until size) array[i] = input.readInt() }
        }
        TAG_LONG_ARRAY -> {
            val size = readLength(input)
            LongArray(size).also { array -> for (i in 0 until size) array[i] = input.readLong() }
        }
        else -> throw IllegalStateException("不认识的 NBT 标签类型：" + type)
    }

    /**
     * 长度字段要挡一下：坏文件里读到的长度可能是负的或几百兆，
     * 直接 new 数组会 OOM，而不是老老实实返回 null。
     */
    private fun readLength(input: DataInputStream): Int {
        val size = input.readInt()
        require(size >= 0 && size <= input.available()) { "NBT 长度字段不合理：" + size }
        return size
    }
}
