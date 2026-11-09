package io.github.huang404yan.platypus.core.instance

import io.github.huang404yan.platypus.core.launch.LaunchArguments
import java.io.File

/**
 * 一次启动用到的目录集合。
 *
 * P1 之前这里只有 `DesktopGameRuntime` 内部才知道，而且是错的：`--gameDir` 被指回
 * `<gameRoot>`，于是所有实例共用同一份 `saves/`、`mods/`、`options.txt`。
 * 现在把它搬进 core，桌面端与安卓端用同一套算法，也能单测。
 *
 * @param versionDir 版本目录 `<gameRoot>/versions/<id>`，也是实例的自包含目录
 * @param gameDir 传给 `--gameDir` 的目录。隔离后等于 [versionDir]：
 *   存档、模组、配置、日志、截图全部落在这个实例自己下面
 * @param gameRoot 含 `versions/`、`libraries/`、`assets/` 的那一层，多个实例共享
 * @param librariesDir 库目录（共享，放在 gameRoot 下）
 * @param assetsDir 资源目录（共享，放在 gameRoot 下）
 * @param nativesDir 原生库解压目录（按实例分，见 [LaunchArguments.nativesDirFor]）
 */
data class InstanceLayout(
    val versionDir: File,
    val gameDir: File,
    val gameRoot: File,
    val librariesDir: File,
    val assetsDir: File,
    val nativesDir: File,
)

/**
 * 由实例信息推算目录布局。
 *
 * 唯一的真源是 [Instance.directory]（`<gameRoot>/versions/<id>`）。不上「实例表里另存一个
 * gameDir」的路：多一个字段就多一处可能不一致，而 `versions/<id>` 已经是既成事实。
 */
object InstanceLayouts {

    /** 标准布局下 `versions` 这一层的名字。 */
    const val VERSIONS_DIR_NAME = "versions"

    fun of(instance: Instance): InstanceLayout = of(File(instance.directory.trim().trim('"')))

    fun of(versionDir: File): InstanceLayout {
        val dir = versionDir.absoluteFile
        val root = gameRootOf(dir)
        return InstanceLayout(
            versionDir = dir,
            // P1 的核心：--gameDir 指向实例自己的目录，而不是游戏根目录。
            gameDir = dir,
            gameRoot = root,
            librariesDir = File(root, "libraries"),
            assetsDir = File(root, "assets"),
            nativesDir = LaunchArguments.nativesDirFor(dir, dir.name),
        )
    }

    /**
     * 从版本目录反推游戏根目录。
     *
     * 只有父目录确实叫 `versions` 时才往上走两级；否则退回目录自身 —— 宁可把
     * libraries/assets 指到实例目录下（会找不到、启动时报缺库），也不要凭空猜一层
     * 跑到别人的游戏目录去。
     */
    fun gameRootOf(versionDir: File): File {
        val versions = versionDir.parentFile ?: return versionDir
        if (!versions.name.equals(VERSIONS_DIR_NAME, ignoreCase = true)) return versionDir
        return versions.parentFile ?: versionDir
    }

    /** 实例之间会互相干扰的那些目录（隔离后应该各自一份）。 */
    fun perInstanceDirs(versionDir: File): List<File> = listOf(
        "mods", "saves", "config", "resourcepacks", "shaderpacks", "screenshots", "logs", "crash-reports",
    ).map { File(versionDir, it) }
}
