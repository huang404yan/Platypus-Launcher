package io.github.huang404yan.platypus.core.packs

import java.io.File
import java.io.IOException

/**
 * 光影 / 资源包的启用、停用、删除。
 *
 * 机制和模组一样是**改文件名**：游戏只认看得见的 `.zip`，所以停用 = 改成
 * `x.zip.disabled`。不用配置文件记状态的理由见 [io.github.huang404yan.platypus.core.mods.ModFileOps]：
 * 用户在资源管理器里也看得见、也能自己改回来。
 *
 * 为什么不复用 [io.github.huang404yan.platypus.core.mods.ModFileOps]：那边把 `.jar` 写死在
 * 名字拼装里（`x.jar` ↔ `x.jar.disabled`），拿它改 `x.zip` 会变成 `x.zip.jar`。
 * 两边的规则几乎一样但后缀不同，复制一份比加参数更不容易改错。
 */
object PackFileOps {

    /** 认得的「停用」尾巴，顺序有意义：先长后短，避免 `.zip` 被提前剥掉。 */
    private val DISABLED_SUFFIXES = listOf(".zip.disabled", ".zip.bak", ".disabled")

    /** 这个文件名是不是被停用的。与 [PackScanner.isDisabledName] 保持一致。 */
    fun isDisabledName(fileName: String): Boolean =
        DISABLED_SUFFIXES.any { fileName.endsWith(it, ignoreCase = true) }

    /**
     * 启用 / 停用。返回改好之后的新文件。
     *
     * 文件夹形态的包（用户自己解压出来的）不支持停用：游戏对目录只看名字不看后缀，
     * 改名成 `x.disabled` 依然是个目录，照样会被列出。与其假装停用了，不如说清原因。
     */
    fun setEnabled(packDir: File, fileName: String, enabled: Boolean): File {
        val source = File(packDir, fileName)
        if (!source.exists()) throw IOException("文件不在了：" + source.absolutePath)
        if (source.isDirectory) {
            throw IOException("文件夹形态的包不支持直接停用（改名后游戏照样认这个目录）。要停用它，把整个文件夹移出 " + packDir.name + "/ 或先压缩成 .zip。")
        }
        val targetName = if (enabled) enabledName(fileName) else disabledName(fileName)
        if (targetName == fileName) return source
        val target = File(packDir, targetName)
        if (target.exists()) {
            throw IOException("已经有同名文件 " + targetName + "，不覆盖。先把它处理掉再试。")
        }
        if (!source.renameTo(target)) {
            throw IOException("改名失败（文件可能被游戏占用）：" + fileName + " → " + targetName)
        }
        return target
    }

    /** 删一个包。删不掉就抛错，让界面说实话。 */
    fun delete(packDir: File, fileName: String): Boolean {
        val target = File(packDir, fileName)
        if (!target.exists()) return false
        if (!target.delete()) throw IOException("删不掉（文件可能被游戏占用）：" + target.absolutePath)
        return true
    }

    /** `x.zip.disabled` → `x.zip`；认不出尾巴时原样返回。 */
    fun enabledName(fileName: String): String {
        for (suffix in DISABLED_SUFFIXES) {
            if (fileName.endsWith(suffix, ignoreCase = true)) {
                val base = fileName.dropLast(suffix.length)
                // .disabled 这种尾巴本身就代替了 .zip，补回来才加载得到
                return if (base.endsWith(".zip", ignoreCase = true)) base else base + ".zip"
            }
        }
        return fileName
    }

    /** `x.zip` → `x.zip.disabled`；已经带停用尾巴或不是 .zip 时原样返回。 */
    fun disabledName(fileName: String): String {
        if (isDisabledName(fileName)) return fileName
        if (!fileName.endsWith(".zip", ignoreCase = true)) return fileName
        return fileName + ".disabled"
    }
}
