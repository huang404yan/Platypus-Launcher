package io.github.huang404yan.platypus.core.saves

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * 造一份能被我方 [LevelDat] 读出来的 `level.dat`，只给测试用。
 *
 * 没放进去的是方块数据之类 —— 存档管理只读元数据，造一个真实世界的 NBT 对这里没有意义。
 */
internal object LevelDatWriter {
    fun write(file: File, levelName: String, lastPlayed: Long, gameType: Int = 0) {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeByte(10) // 根 TAG_Compound
        data.writeUTF("")
        // 原版把所有东西放在 Data 下面
        data.writeByte(10)
        data.writeUTF("Data")
        putString(data, "LevelName", levelName)
        putLong(data, "LastPlayed", lastPlayed)
        putInt(data, "GameType", gameType)
        putByte(data, "hardcore", 0)
        putByte(data, "allowCommands", 0)
        data.writeByte(0) // Data 结束
        data.writeByte(0) // 根结束
        data.flush()
        file.parentFile?.mkdirs()
        GZIPOutputStream(file.outputStream()).use { it.write(out.toByteArray()) }
    }

    private fun putString(data: DataOutputStream, name: String, value: String) {
        data.writeByte(8)
        data.writeUTF(name)
        data.writeUTF(value)
    }

    private fun putLong(data: DataOutputStream, name: String, value: Long) {
        data.writeByte(4)
        data.writeUTF(name)
        data.writeLong(value)
    }

    private fun putInt(data: DataOutputStream, name: String, value: Int) {
        data.writeByte(3)
        data.writeUTF(name)
        data.writeInt(value)
    }

    private fun putByte(data: DataOutputStream, name: String, value: Int) {
        data.writeByte(1)
        data.writeUTF(name)
        data.writeByte(value)
    }
}
