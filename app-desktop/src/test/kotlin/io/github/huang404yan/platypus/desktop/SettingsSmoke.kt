package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * 设置持久化的无头冒烟：验证「界面保存 → 落盘 → 重新装配后读回」真的成立。
 *
 * 只由 `gradle :app-desktop:settingsSmoke` 调用，不进发行包。
 * 走的是 [DesktopServices.create] 这个唯一装配点，因此能证明设置文件的位置、
 * 反序列化、默认值兜底在真实进程里都对，而不只是 core 单测里的 JsonSettingsRepository。
 *
 * 参数：<临时目录>
 */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { error("缺少参数：临时目录") })
    var exit = 0

    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS " else "FAIL ") + name + if (detail.isBlank()) "" else " :: " + detail)
        if (!ok) exit = 1
    }

    dir.mkdirs()
    val expected = File(File(dir, "platypus"), "settings.json")

    // ① 全新目录：读到的必须是默认值，而且不能凭空建文件
    runBlocking {
        val fresh = DesktopServices.create(dir).settings
        val loaded = fresh.load()
        check("fresh defaults username", loaded.offlineUsername == "Dev", loaded.offlineUsername)
        check("fresh defaults memory", loaded.defaultMemoryMb == Instance.DEFAULT_MEMORY_MB, loaded.defaultMemoryMb.toString())
        check("fresh load does not create file", !expected.isFile, expected.absolutePath)

        // ② 保存后落盘位置正确
        fresh.save(
            io.github.huang404yan.platypus.core.settings.LauncherSettings(
                offlineUsername = "Steve",
                defaultGameRoot = dir.absolutePath,
                defaultMemoryMb = 4096,
            ),
        )
        check("save writes <gameRoot>/platypus/settings.json", expected.isFile, expected.absolutePath)
        check("repository path matches", (fresh as JsonSettingsRepository).path == expected.absolutePath, fresh.path)

        // ③ 重新装配（模拟关掉程序再打开）后能读回
        val reopened = DesktopServices.create(dir).settings.load()
        check("reopen username", reopened.offlineUsername == "Steve", reopened.offlineUsername)
        check("reopen gameRoot", reopened.defaultGameRoot == dir.absolutePath, reopened.defaultGameRoot)
        check("reopen memory", reopened.defaultMemoryMb == 4096, reopened.defaultMemoryMb.toString())
        check("reopen updatedAt set", reopened.updatedAt > 0L, reopened.updatedAt.toString())

        // ④ 规范化在装配层也生效：非法名字回默认、内存夹到范围内
        val normalized = DesktopServices.create(dir).settings.save(
            io.github.huang404yan.platypus.core.settings.LauncherSettings(
                offlineUsername = "   ",
                defaultMemoryMb = 999999,
            ),
        )
        check("normalize blank username", normalized.offlineUsername == "Dev", normalized.offlineUsername)
        check("normalize memory clamp", normalized.defaultMemoryMb == Instance.MAX_MEMORY_MB, normalized.defaultMemoryMb.toString())

        // ⑤ 文件被写坏时不能让程序打不开
        expected.writeText("{ 这不是 json")
        val survived = DesktopServices.create(dir).settings.load()
        check("corrupted file falls back", survived.offlineUsername == "Dev", survived.offlineUsername)
    }

    println(if (exit == 0) "SETTINGS SMOKE OK" else "SETTINGS SMOKE FAILED")
    exitProcess(exit)
}
