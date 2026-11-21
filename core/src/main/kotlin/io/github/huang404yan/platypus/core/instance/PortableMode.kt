package io.github.huang404yan.platypus.core.instance

import java.io.File

/**
 * 便携模式：启动器自己的数据放启动器目录里，不往系统用户目录写。
 *
 * 判据有两条，满足一条就算：启动器目录下有一个 [MARKER_FILE]（空文件即可），
 * 或者环境变量 [ENV_NAME] 是真值。**不做编译期开关** —— 一份包拷到 U 盘就该便携、
 * 拷回装机目录就该按常规写用户目录，这是「放在哪儿」的事，不是「这一版是哪种」的事；
 * 编译期定死的话，同一份包在两种用法之间只能挑一种。
 *
 * 为什么不「解压出来就自动便携」：那样把 zip 解到桌面试一下的人，数据会落在桌面目录里，
 * 删掉解压目录就一起没了；而装在 Program Files 下的那批又根本建不出目录。
 * 让一个显式文件来表态，谁都清楚自己现在是哪一种。
 */
object PortableMode {

    /** 启动器目录下出现这个文件就算便携模式；内容不读，空文件即可。 */
    const val MARKER_FILE: String = "portable.txt"

    /** 环境变量：临时试一次便携模式用，不必真去建文件。 */
    const val ENV_NAME: String = "PLATYPUS_PORTABLE"

    /** 便携模式下的数据子目录名：<启动器目录>/data。 */
    const val DATA_DIR_NAME: String = "data"

    /** 标记文件将来会在哪（界面上显示路径用，不保证已经存在）。 */
    fun markerIn(launcherDir: File): File = File(launcherDir, MARKER_FILE)

    /**
     * 环境变量的值算不算「打开」。
     *
     * 认 1 / true / yes / on 四种写法（不分大小写、忽略两侧空白），其余一律不算 ——
     * 把它设成 0 或 false 的人意思是关，不能因为「非空字符串」就反着理解。
     */
    fun isTruthy(raw: String?): Boolean {
        val value = raw?.trim()?.lowercase().orEmpty()
        return value == "1" || value == "true" || value == "yes" || value == "on"
    }

    /**
     * 这次运行算不算便携模式。
     *
     * @param launcherDir 启动器自己的目录。拿不到就没地方放 data/，一律算没开 ——
     *   宁可退回常规位置，也不能把「便携模式」四个字写在界面上却没地方存。
     * @param env 默认读真实环境变量，单测显式传。
     */
    fun isEnabled(launcherDir: File?, env: String? = System.getenv(ENV_NAME)): Boolean {
        val base = launcherDir ?: return false
        if (isTruthy(env)) return true
        return runCatching { markerIn(base).isFile }.getOrDefault(false)
    }

    /** 便携模式的数据目录（不判断是否启用）：<启动器目录>/data。 */
    fun dataDirIn(launcherDir: File): File = File(launcherDir, DATA_DIR_NAME)

    /**
     * 要用的数据目录；不是便携模式、或者目录建不出来时返回 null。
     *
     * 建不出来的场合（只读介质、装在 Program Files 下没有写权限）不能硬当便携模式用 ——
     * 设置存不下去，用户会看到「明明保存了，下次打开又回默认值」。返回 null 让调用方
     * 退回常规位置，至少能用。
     */
    fun dataDir(launcherDir: File?, env: String? = System.getenv(ENV_NAME)): File? {
        if (!isEnabled(launcherDir, env)) return null
        val base = launcherDir ?: return null
        val dir = dataDirIn(base)
        val usable = runCatching { dir.isDirectory || dir.mkdirs() }.getOrDefault(false)
        return if (usable) dir else null
    }
}
