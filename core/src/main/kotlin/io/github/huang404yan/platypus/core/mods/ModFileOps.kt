package io.github.huang404yan.platypus.core.mods

import java.io.File
import java.io.IOException

/**
 * 改文件名与删文件这两件「用户以前得自己去资源管理器做的事」。
 *
 * 启停机制沿用 Minecraft 生态的老规矩：**看得见 `.jar` 才加载，改名就不加载**。
 * 停用 = 把 `x.jar` 改成 `x.jar.disabled`；启用 = 改回来。这里不发明新机制，
 * 因为用户从别的启动器搬过来的 mods 目录里本来就全是这种命名。
 *
 * 为什么放在 core：安卓端也要用同一套规则，`File.renameTo` 两端都有。
 */
object ModFileOps {

    /** 认得的「停用」尾巴，顺序有意义：先长后短，避免 `.jar` 被提前剥掉。 */
    private val DISABLED_SUFFIXES = listOf(".jar.disabled", ".jar.bak", ".disabled")

    /** 这个文件名是不是被停用的。与 [ModScanner] 的判定保持一致。 */
    fun isDisabledName(fileName: String): Boolean =
        DISABLED_SUFFIXES.any { fileName.endsWith(it, ignoreCase = true) }

    /**
     * 启用 / 停用。返回改好之后的新文件。
     *
     * 只改名字、不移动目录、不碰内容：改错了再点一次就改回来。目标名已存在时抛错，
     * 不覆盖 —— 覆盖会让一个模组无声消失。
     */
    fun setEnabled(modsDir: File, fileName: String, enabled: Boolean): File {
        val source = File(modsDir, fileName)
        if (!source.isFile) throw IOException("文件不在了：" + source.absolutePath)
        val targetName = if (enabled) enabledName(fileName) else disabledName(fileName)
        if (targetName == fileName) return source
        val target = File(modsDir, targetName)
        if (target.exists()) {
            throw IOException("已经有同名文件 " + targetName + "，不覆盖。先把它处理掉再试。")
        }
        if (!source.renameTo(target)) {
            throw IOException("改名失败（文件可能被游戏占用）：" + fileName + " → " + targetName)
        }
        return target
    }

    /** 删一个模组。删不掉就抛错，让界面说实话，而不是「看着删了其实没删」。 */
    fun delete(modsDir: File, fileName: String): Boolean {
        val target = File(modsDir, fileName)
        if (!target.exists()) return false
        if (!target.delete()) throw IOException("删不掉（文件可能被游戏占用）：" + target.absolutePath)
        return true
    }

    /** `x.jar.disabled` → `x.jar`；认不出尾巴时原样返回。 */
    fun enabledName(fileName: String): String {
        for (suffix in DISABLED_SUFFIXES) {
            if (fileName.endsWith(suffix, ignoreCase = true)) {
                val base = fileName.dropLast(suffix.length)
                // .disabled 这种尾巴本身就代替了 .jar，补回来才加载得到
                return if (base.endsWith(".jar", ignoreCase = true)) base else base + ".jar"
            }
        }
        return fileName
    }

    /** `x.jar` → `x.jar.disabled`；已经带停用尾巴或不是 .jar 时原样返回。 */
    fun disabledName(fileName: String): String {
        if (isDisabledName(fileName)) return fileName
        if (!fileName.endsWith(".jar", ignoreCase = true)) return fileName
        return fileName + ".disabled"
    }
}
