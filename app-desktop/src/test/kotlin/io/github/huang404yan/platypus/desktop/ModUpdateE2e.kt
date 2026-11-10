package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.mods.ModFileOps
import io.github.huang404yan.platypus.core.mods.ModInstallProgress
import io.github.huang404yan.platypus.core.mods.ModInstaller
import io.github.huang404yan.platypus.core.mods.ModScanner
import io.github.huang404yan.platypus.core.mods.ModUpdateChecker
import io.github.huang404yan.platypus.core.mods.ModUpdateStatus
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 无头验收 P2：把「启用 / 停用 / 检查更新 / 删除」这一圈真跑一遍。
 *
 *   <实例目录> [游戏版本=1.21.1] [加载器=neoforge] [模组=iris]
 *
 * 挑 iris 而不是随便一个模组，是因为它有硬前置 Sodium（Modrinth 的 AANobbMI）：
 * 装完能顺手验证「缺前置」这条链路，而不是拿一个没有依赖的模组假装验过了。
 *
 * 全程只看磁盘与 Modrinth 的公开响应，不碰界面。退出码：
 *   0 全部通过 / 2 装模组失败 / 3 元数据对不上 / 4 启用停用没换名 / 5 删除没删掉 / 6 检查更新没结论
 */
fun main(args: Array<String>): Unit = runBlocking {
    val instanceDir = File(args.getOrNull(0) ?: error("缺少实例目录参数"))
        .let { if (it.isAbsolute) it else File(System.getProperty("user.dir"), it.path) }
    val gameVersion = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "1.21.1"
    val loader = parseLoader(args.getOrNull(2))
    val slug = args.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "iris"

    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
    val logDir = File(System.getProperty("user.dir"), "dist/_e2e-logs").apply { mkdirs() }
    val log = File(logDir, "mod-update-e2e-" + slug + "-" + stamp + ".log")
    val out = java.io.PrintWriter(log.writer(Charsets.UTF_8), true)

    var failures = 0
    fun say(line: String) {
        val text = "[" + DateTimeFormatter.ofPattern("HH:mm:ss.SSS").format(LocalDateTime.now()) + "] " + line
        println(text)
        out.println(text)
        out.flush()
    }
    fun check(ok: Boolean, what: String, detail: String) {
        if (ok) {
            say("PASS " + what + "：" + detail)
        } else {
            failures++
            say("FAIL " + what + "：" + detail)
        }
    }

    try {
        val modsDir = ModScanner.modsDir(instanceDir)
        say("MOD UPDATE E2E start; instance=" + instanceDir.absolutePath + " mods=" + modsDir.absolutePath +
            " gv=" + gameVersion + " loader=" + loader + " slug=" + slug)
        modsDir.mkdirs()

        // 从干净状态开始，免得上一轮的残留把「装上了」证成假象
        modsDir.listFiles()?.forEach { it.delete() }

        say("INSTALL " + slug)
        val installed = try {
            ModInstaller.install(instanceDir, slug, gameVersion, loader) { p: ModInstallProgress ->
                if (p.fraction > 0f && (p.fraction * 100).toInt() % 25 == 0) say("  " + p.step.label + " " + (p.fraction * 100).toInt() + "%")
            }
        } catch (e: Exception) {
            say("RESULT 装模组失败：" + (e.message ?: e.toString()))
            return@runBlocking exitWith(out, 2)
        }
        say("INSTALLED " + installed.file.name + " version=" + installed.version +
            " bytes=" + installed.downloadedBytes + " requiredDeps=" + installed.requiredDependencies)
        check(
            installed.file.isFile,
            "模组文件落盘",
            installed.file.absolutePath + " (" + installed.file.length() + " B)",
        )
        check(
            installed.requiredDependencies.isNotEmpty(),
            "缺前置被如实报出来",
            if (installed.requiredDependencies.isEmpty()) "这次没有前置，验不到这条" else installed.requiredDependencies.toString(),
        )

        // 扫描器要能认出它（元数据读得出来）
        var scan = ModScanner.scan(instanceDir)
        val entry = scan.mods.firstOrNull { it.fileName == installed.file.name }
        check(entry != null, "扫描认得出这个模组", entry?.let { it.title + " / " + it.loader } ?: "没扫到")
        if (entry == null) return@runBlocking exitWith(out, 3)

        say("CHECK UPDATES")
        val updates = try {
            ModUpdateChecker.check(modsDir, gameVersion, loader) { say("  " + it) }
        } catch (e: Exception) {
            say("RESULT 检查更新失败：" + (e.message ?: e.toString()))
            return@runBlocking exitWith(out, 6)
        }
        updates.forEach {
            say("  " + it.fileName + " -> " + it.status + " current=" + it.currentVersion +
                " latest=" + it.latestVersion + " title=" + it.title)
        }
        val mine = updates.firstOrNull { it.fileName == installed.file.name }
        check(mine != null, "检查更新认得出这个文件", mine?.status?.name ?: "没进结果集")
        check(
            mine != null && mine.status != ModUpdateStatus.NOT_ON_MODRINTH,
            "这个文件在 Modrinth 上认得出来",
            "status=" + (mine?.status?.name ?: "null") + " version=" + (mine?.currentVersion ?: ""),
        )
        // 刚装的是 pickBest 挑的最新 release，所以应当就是「已是最新」
        check(
            mine != null && mine.status == ModUpdateStatus.UP_TO_DATE,
            "刚装的就是最新版",
            "status=" + (mine?.status?.name ?: "null") + " current=" + (mine?.currentVersion ?: "") +
                " latest=" + (mine?.latestVersion ?: ""),
        )

        say("DISABLE")
        val disabled = ModFileOps.setEnabled(modsDir, installed.file.name, false)
        check(!disabled.path.endsWith(".jar"), "停用后名字不带 .jar 结尾", disabled.name)
        scan = ModScanner.scan(instanceDir)
        check(
            scan.mods.none { it.fileName == installed.file.name } && scan.disabled.any { it.fileName == disabled.name },
            "停用后落到 disabled 分组",
            "启用 " + scan.mods.size + " / 停用 " + scan.disabled.size,
        )

        say("ENABLE")
        val enabled = ModFileOps.setEnabled(modsDir, disabled.name, true)
        check(enabled.name == installed.file.name, "启用后名字回到原样", enabled.name)
        scan = ModScanner.scan(instanceDir)
        check(
            scan.mods.any { it.fileName == installed.file.name },
            "启用后回到启用分组",
            "启用 " + scan.mods.size + " / 停用 " + scan.disabled.size,
        )

        say("DELETE")
        val removed = ModFileOps.delete(modsDir, installed.file.name)
        check(removed && !enabled.exists(), "删除真的删掉了", enabled.absolutePath)

        // 收尾：把这一轮碰过的文件清干净，目录保持原样
        modsDir.listFiles()?.forEach { it.delete() }
        say("CLEANED " + modsDir.absolutePath)
        say("RESULT failures=" + failures)
        out.close()
        if (failures > 0) kotlin.system.exitProcess(4) else kotlin.system.exitProcess(0)
    } catch (e: Exception) {
        say("RESULT 意外失败：" + e)
        out.close()
        kotlin.system.exitProcess(1)
    }
}

private fun exitWith(out: java.io.PrintWriter, code: Int): Unit {
    out.close()
    kotlin.system.exitProcess(code)
}

private fun parseLoader(raw: String?): LoaderKind = when (raw?.trim()?.lowercase()) {
    "fabric" -> LoaderKind.FABRIC
    "forge" -> LoaderKind.FORGE
    "neoforge" -> LoaderKind.NEOFORGE
    "quilt" -> LoaderKind.QUILT
    else -> LoaderKind.NEOFORGE
}
