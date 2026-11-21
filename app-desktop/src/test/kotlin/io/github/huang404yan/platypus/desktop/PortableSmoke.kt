package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.instance.PortableMode
import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * 无头验收：便携模式下，启动器数据必须落在 <启动器目录>/data 下，而不是游戏目录里。
 *
 * 单测只能证明 [PortableMode] 那几条纯函数认得标记文件；「装配点有没有把 homeDir 真的
 * 传下去」只有走一遍 [DesktopServices.create] 才能证明 —— 漏传的表现是「文件在便携模式
 * 下仍然写进了游戏目录」，单测完全看不见。所以这里验四件事：
 *
 *  1. 造出 portable.txt 后 [PortableMode.dataDir] 给出 <启动器目录>/data；
 *  2. 装配出来的设置仓库路径在 data/platypus/settings.json；
 *  3. 保存一次后文件真的在那个位置，游戏目录下的 platypus/ 里没有它；
 *  4. 重新装配（模拟重开程序）能读回同一条设置，且后端报出的便携目录非空。
 *
 * 参数：<临时根目录>（下面建 launcher/ 与 game/ 两个子目录）
 *
 * 退出码：0 通过；4 断言失败。
 */
fun main(args: Array<String>) {
    val root = File(args.getOrElse(0) { error("缺少参数：临时根目录") }).absoluteFile
    val launcherDir = File(root, "launcher").apply { mkdirs() }
    val gameDir = File(root, "game").apply { mkdirs() }
    var exit = 0

    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS " else "FAIL ") + name + if (detail.isBlank()) "" else " :: " + detail)
        if (!ok) exit = 1
    }

    println("launcherDir=" + launcherDir.absolutePath)
    println("gameDir=" + gameDir.absolutePath)

    // ① 先验「没标记文件就不便携」，否则后面的断言可能只是碰巧成立。
    check("没有 portable.txt 时不便携", PortableMode.dataDir(launcherDir, env = null) == null)

    PortableMode.markerIn(launcherDir).writeText("")
    val portableHome = PortableMode.dataDir(launcherDir, env = null)
    check(
        "有 portable.txt 就给出 <启动器目录>/data",
        portableHome != null && portableHome.path == File(launcherDir, "data").path,
        portableHome?.absolutePath.orEmpty(),
    )

    val expectedSettings = File(File(portableHome!!, "platypus"), "settings.json")
    val forbiddenSettings = File(File(gameDir, "platypus"), "settings.json")

    runBlocking {
        // ② 装配后的路径：设置必须在便携目录下。
        val services = DesktopServices.create(
            gameRoot = gameDir,
            instanceIndex = null,
            launcherDir = launcherDir,
            homeDir = portableHome,
        )
        check("后端报出便携目录", services.portableDataDir != null, services.portableDataDir?.absolutePath.orEmpty())
        val settingsPath = (services.settings as JsonSettingsRepository).path
        check(
            "设置仓库指向 data/platypus/settings.json",
            File(settingsPath).path == expectedSettings.path,
            settingsPath,
        )

        // ③ 存一次：文件落在便携目录，游戏目录里不该冒出同名文件。
        services.settings.save(LauncherSettings(offlineUsername = "PortableSteve"))
        check("保存后文件真的在便携目录", expectedSettings.isFile, expectedSettings.absolutePath)
        check("游戏目录下没有被写一份", !forbiddenSettings.exists(), forbiddenSettings.absolutePath)

        // ④ 重新装配后读回（模拟关掉程序再打开）。
        val reopened = DesktopServices.create(
            gameRoot = gameDir,
            instanceIndex = null,
            launcherDir = launcherDir,
            homeDir = PortableMode.dataDir(launcherDir, env = null),
        ).settings.load()
        check("重开后读回用户名", reopened.offlineUsername == "PortableSteve", reopened.offlineUsername)

        // ⑤ 不传 homeDir 时必须回到老位置 —— 证明「便携」是显式选择，不是装配点默认。
        val plain = DesktopServices.create(gameRoot = gameDir, instanceIndex = null, launcherDir = launcherDir)
        val plainPath = (plain.settings as JsonSettingsRepository).path
        check(
            "不传 homeDir 时仍写 <游戏目录>/platypus/settings.json",
            File(plainPath).path == forbiddenSettings.path,
            plainPath,
        )
        check("常规模式后端不报便携目录", plain.portableDataDir == null)
    }

    println(if (exit == 0) "PORTABLE SMOKE OK" else "PORTABLE SMOKE FAILED")
    exitProcess(exit)
}
