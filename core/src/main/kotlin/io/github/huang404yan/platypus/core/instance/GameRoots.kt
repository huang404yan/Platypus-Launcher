package io.github.huang404yan.platypus.core.instance

import java.io.File

/**
 * 安卓端把游戏目录放在哪儿。
 *
 * 以前默认写死 `/sdcard/.minecraft`，与别的启动器（PojavLauncher、FCL）共用同一个
 * 隐藏目录。三个后果：① 两个启动器互相踩同一份 `versions/`；② 以点开头的目录在
 * 文件管理器里默认不显示，想拷个存档出来得先开「显示隐藏文件」；③ 用户分不清
 * 「这个文件夹是鸭嘴兽的」还是「这是游戏本身的」。
 *
 * 现在默认落在 `/sdcard/Platypus/game`：一层看得见的顶层目录，卸载启动器不会删它
 * （外部存储不随应用卸载清空），世界因此不跟着启动器一起消失。这一点是刻意保留的 ——
 * 存档是用户的，不是启动器的。
 *
 * **老目录不静默搬家。** 已经在 `/sdcard/.minecraft` 里攒了实例、存档、模组的人，
 * 启动器一升级就把他的游戏目录换掉，等于让他以为全丢了。所以：老目录存在就继续用，
 * 新目录只在「什么都没装过」时启用。想合并到新目录由用户自己决定，见设置页那一条。
 */
object GameRoots {

    /** 启动器在外部存储上的顶层目录名。与包名解耦：改名不该动用户的文件夹。 */
    const val ANDROID_HOME = "Platypus"

    /** 顶层目录下放游戏数据的那一层。将来 java/、logs/ 之类也归在这一层下面。 */
    const val GAME_SUBDIR = "game"

    /** 启动器自己的顶层目录。 */
    fun androidHome(storageRoot: File): File = File(storageRoot, ANDROID_HOME)

    /** 新装用户默认用这个目录。 */
    fun androidDefaultGame(storageRoot: File): File = File(androidHome(storageRoot), GAME_SUBDIR)

    /**
     * 老版本或别的启动器留下的游戏目录，按「离得近的先看」排。
     *
     * 前两个是鸭嘴兽以前写死的；后两个是 PojavLauncher 的位置 —— 用户从它迁过来时，
     * 直接指过去就能接着玩，不必先拿文件管理器搬一遍。
     */
    fun androidLegacyGameDirs(storageRoot: File): List<File> = listOf(
        File(storageRoot, ".minecraft"),
        File(storageRoot, "minecraft"),
        File(storageRoot, "games/PojavLauncher/.minecraft"),
        File(storageRoot, "Android/data/net.kdt.pojavlaunch/files/.minecraft"),
    )

    /**
     * 选一个游戏目录：老目录里有一个存在就沿用最近的，一个都没有就用新目录。
     *
     * [exists] 可注入，单测里不必真建目录。传的是「这个路径像个已存在的游戏目录」这一判断，
     * 默认就是 `isDirectory`。
     */
    fun androidGameDir(storageRoot: File, exists: (File) -> Boolean = { it.isDirectory }): File =
        androidLegacyGameDirs(storageRoot).firstOrNull(exists) ?: androidDefaultGame(storageRoot)

    /**
     * 这个目录是不是我们自己那套（`<storage>/Platypus/game`）。
     *
     * 界面用它决定要不要提「老目录还在，要不要合并过来」这类话。
     */
    fun isOurAndroidDir(storageRoot: File, gameDir: File): Boolean =
        gameDir.absoluteFile.normalize() == androidDefaultGame(storageRoot).absoluteFile.normalize()

    /**
     * 用户手里这个游戏目录属于哪种来源，用来在设置页说一句实话。
     *
     * 不猜「谁建的这个目录」，只看路径形状：认得出就报认得出的，认不出就说认不出。
     */
    enum class AndroidSource(val label: String) {
        OUR_HOME("鸭嘴兽自己的目录"),
        LEGACY_DOT_MINECRAFT("老位置 .minecraft"),
        LEGACY_MINECRAFT("老位置 minecraft"),
        POJAV("PojavLauncher 的目录"),
        CUSTOM("自定义路径"),
    }

    fun androidSourceOf(storageRoot: File, gameDir: File): AndroidSource {
        val path = gameDir.absoluteFile.normalize()
        if (path == androidDefaultGame(storageRoot).absoluteFile.normalize()) return AndroidSource.OUR_HOME
        val legacy = androidLegacyGameDirs(storageRoot).map { it.absoluteFile.normalize() }
        return when (path) {
            legacy.getOrNull(0) -> AndroidSource.LEGACY_DOT_MINECRAFT
            legacy.getOrNull(1) -> AndroidSource.LEGACY_MINECRAFT
            legacy.getOrNull(2), legacy.getOrNull(3) -> AndroidSource.POJAV
            else -> AndroidSource.CUSTOM
        }
    }
}
