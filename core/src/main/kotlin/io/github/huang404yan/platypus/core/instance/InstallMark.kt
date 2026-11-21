package io.github.huang404yan.platypus.core.instance

import java.io.File

/**
 * 「正在装」标记：安放一个空文件进版本目录，扫描时见到就把这一条藏起来。
 *
 * 为什么需要它：装一个带加载器的实例，磁盘上会先出现半成品 ——
 * 解析版本元数据那一步就把 `versions/<id>/<id>.json` 写了，加载器又把
 * `versions/<加载器 id>/<加载器 id>.json` 写下去，之后才轮到下载文件。
 * 扫描器只看 json 在不在，于是装到一半的目录会先一步出现在实例列表里，
 * 看起来像「还没装完就冒出来一条」。
 *
 * 标记只表达「这个目录正在被写」，装成功、失败、取消之后都由安装方撤掉；
 * 万一进程被强杀留下残留，重装同一版本会重新标记，装完照常撤掉。
 * 标记文件本身不进版本 json，也不影响游戏启动。
 */
object InstallMark {

    /** 标记文件名。以点开头，和 Minecraft 自己的文件区分开。 */
    const val FILE_NAME = ".platypus-installing"

    /**
     * 标记的有效期。装着装着启动器被强杀，标记会留在磁盘上，那个目录就再也不显示——
     * 一次安装最多十几分钟，超过这个时长还留着的标记一定是残留，直接当没有。
     */
    const val STALE_MILLIS: Long = 6L * 60L * 60L * 1000L

    fun fileIn(versionDir: File): File = File(versionDir, FILE_NAME)

    fun isMarked(versionDir: File): Boolean {
        val file = fileIn(versionDir)
        if (!file.isFile) return false
        val age = System.currentTimeMillis() - file.lastModified()
        if (age > STALE_MILLIS) {
            // 残留标记：顺手删掉，下次扫描就不会再来问一遍。
            runCatching { file.delete() }
            return false
        }
        return true
    }

    /** 打标记；失败（比如目录不可写）不抛异常 —— 少一层保护不该让安装整个失败。 */
    fun mark(versionDir: File) {
        runCatching {
            versionDir.mkdirs()
            fileIn(versionDir).writeText("", Charsets.UTF_8)
        }
    }

    /** 撤标记；同样不抛异常。 */
    fun clear(versionDir: File) {
        runCatching { fileIn(versionDir).delete() }
    }
}
