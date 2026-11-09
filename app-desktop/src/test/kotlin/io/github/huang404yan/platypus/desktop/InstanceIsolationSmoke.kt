package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.auth.OfflineProfile
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.launch.GameHandle
import io.github.huang404yan.platypus.core.launch.LaunchDiagnosis
import io.github.huang404yan.platypus.core.launch.LaunchEvent
import io.github.huang404yan.platypus.core.launch.LaunchRequest
import io.github.huang404yan.platypus.core.launch.VersionResolver
import io.github.huang404yan.platypus.core.runtime.findExactMajor
import io.github.huang404yan.platypus.desktop.runtime.DesktopGameRuntime
import io.github.huang404yan.platypus.desktop.runtime.DesktopJavaRuntimeService
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * 实例隔离的无头验收（开发期用）。
 *
 * 要证的就一件事：**游戏真的把存档、模组、日志写进实例自己的目录**，
 * 而不是像 P1 之前那样统统写回 <游戏目录>/ 让所有实例共用。
 *
 * 判据全部落在磁盘上，不看日志自述：
 * 1. 启动前给 <游戏目录>/logs/latest.log 记一个修改时间；
 * 2. 启动一个实例、进主菜单、停掉；
 * 3. 断言 <版本目录>/logs/latest.log 存在且有内容；
 * 4. 断言 <游戏目录>/logs/latest.log 的修改时间没变（没被这个实例写过）。
 *
 * 参数：<游戏目录> [实例 id=自动挑非原版] [离线用户名=Dev] [等主菜单毫秒=300000]
 * 退出码：0 通过 / 2 找不到实例 / 3 没 Java / 4 没进主菜单 / 5 隔离断言失败。
 */
fun main(args: Array<String>) {
    val gameDir = File(args.getOrElse(0) { error("缺少参数：游戏目录") })
    val wantedId = args.getOrElse(1) { "" }
    val username = args.getOrElse(2) { "Dev" }
    val menuTimeoutMs = args.getOrElse(3) { "300000" }.toLong()

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val logDir = File("dist/_e2e-logs").apply { mkdirs() }
    val report = File(logDir, "isolation-smoke-" + stamp + ".log")
    val gameLog = File(logDir, "isolation-game-" + stamp + ".log")

    fun say(line: String) {
        val text = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] " + line
        println(text)
        report.appendText(text + System.lineSeparator())
    }

    val scan = InstanceScanner.scan(gameDir)
    say("gameDir=" + gameDir.absolutePath + " isGameDir=" + scan.isGameDirectory +
        " instances=" + scan.instances.size)
    scan.instances.forEach {
        say("  " + it.id + " loader=" + it.loader + " gameVersion=" + it.gameVersion +
            " dir=" + it.directory)
    }
    val instance: Instance = (if (wantedId.isNotBlank()) {
        scan.instances.firstOrNull { it.id == wantedId }
    } else {
        scan.instances.sortedByDescending { it.loader != LoaderKind.VANILLA }.firstOrNull()
    }) ?: run {
        say("找不到可用实例" + (if (wantedId.isBlank()) "" else "：" + wantedId))
        exitProcess(2)
    }

    val layout = InstanceLayouts.of(instance)
    say("INSTANCE " + instance.id + " loader=" + instance.loader)
    say("  versionDir=" + layout.versionDir.absolutePath)
    say("  gameDir   =" + layout.gameDir.absolutePath + "   ← 启动时 --gameDir 指这里")
    say("  gameRoot  =" + layout.gameRoot.absolutePath)
    say("  libraries=" + layout.librariesDir.absolutePath + " (exists=" + layout.librariesDir.isDirectory + ")")
    say("  assets   =" + layout.assetsDir.absolutePath + " (exists=" + layout.assetsDir.isDirectory + ")")
    say("  natives  =" + layout.nativesDir.absolutePath)

    // 启动前记下共享 logs/latest.log 的修改时间，用来证明这个实例没往那儿写。
    val sharedLog = File(gameDir, "logs/latest.log")
    val sharedLogBefore = if (sharedLog.isFile) sharedLog.lastModified() else -1L
    val sharedSavesBefore = File(gameDir, "saves").listFiles()?.size ?: -1
    say("BEFORE sharedLog=" + sharedLog.absolutePath + " mtime=" + sharedLogBefore +
        " sharedSaves=" + sharedSavesBefore +
        " versionLogsExists=" + File(layout.gameDir, "logs").isDirectory)

    val javaService = DesktopJavaRuntimeService(gameRoot = gameDir, scanGlobalLaunchers = true)
    val detected = runBlocking { javaService.detect() }
    val required = instance.gameVersion
    say("JAVA detected=" + detected.size +
        " (" + detected.joinToString(", ") { it.majorVersion.toString() + "@" + it.path } + ")")

    // 必须沿 inheritsFrom 合并后才知道要哪个 Java：NeoForge 的版本 json 自己不带 javaVersion，
    // 直接读它那个文件会退回默认的 Java 8，然后满机找不到 Java 8。
    val requiredMajor = runCatching {
        VersionResolver.load(layout.gameRoot, layout.versionDir.name)?.version?.javaVersion?.majorVersion
    }.getOrNull()?.takeIf { it > 0 } ?: 17
    say("JAVA required major=" + requiredMajor)

    val javaRuntime = instance.javaPath?.takeIf { it.isNotBlank() }?.let { path ->
        detected.firstOrNull { File(it.path).absolutePath == File(path).absolutePath }
    } ?: detected.findExactMajor(requiredMajor) ?: run {
        say("本机没有这个实例能用的 Java（要求 Java " + requiredMajor + "）")
        exitProcess(3)
    }
    say("JAVA using " + javaRuntime.path + " major=" + javaRuntime.majorVersion)

    var menuAt = 0L
    var exitCode: Int? = null
    var handle: GameHandle? = null
    val launchAt = System.currentTimeMillis()

    runBlocking {
        withTimeoutOrNull(menuTimeoutMs) {
            val started = DesktopGameRuntime().launch(
                LaunchRequest(
                    instance = instance,
                    java = javaRuntime,
                    session = OfflineProfile.session(username),
                ),
            ) { event ->
                when (event) {
                    is LaunchEvent.Stage -> say("  stage " + event.name)
                    is LaunchEvent.Started -> say("  started pid=" + event.pid)
                    is LaunchEvent.LogLine -> {
                        gameLog.appendText(event.text + System.lineSeparator())
                        if (menuAt == 0L && LaunchDiagnosis.reachedMenu(listOf(event.text))) {
                            menuAt = System.currentTimeMillis()
                            say("  MENU REACHED +" + (menuAt - launchAt) + " ms: " + event.text.take(160))
                        }
                    }
                    is LaunchEvent.Exited -> {
                        exitCode = event.code
                        say("  exited code=" + event.code + " diagnosis=" + event.diagnosis)
                    }
                }
            }
            handle = started
            while (menuAt == 0L && exitCode == null && started.isAlive()) delay(500)
        }
        // 多等一会儿：options.txt / saves 之类是进世界或退出时才写的，主菜单阶段只保证 logs 落地。
        delay(4_000)
        runCatching { handle?.stop() }
        delay(2_000)
    }

    val launchMs = if (menuAt != 0L) menuAt - launchAt else -1L
    say("RESULT menuReached=" + (menuAt != 0L) + " launchMs=" + launchMs + " exitCode=" + exitCode)

    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        say((if (ok) "PASS " else "FAIL ") + name + (if (detail.isBlank()) "" else " :: " + detail))
        if (!ok) failures++
    }

    val versionLogs = File(layout.gameDir, "logs")
    val versionLatest = File(versionLogs, "latest.log")
    check("实例目录里有 logs/latest.log", versionLatest.isFile, versionLatest.absolutePath)
    if (versionLatest.isFile) {
        check("latest.log 非空", versionLatest.length() > 0, versionLatest.length().toString() + " B")
        val content = runCatching { versionLatest.readText(Charsets.UTF_8) }.getOrDefault("")
        check("latest.log 有进主菜单的证据",
            content.contains("Setting user:", ignoreCase = true) ||
                content.contains("Narrator", ignoreCase = true) ||
                content.contains("Backend library", ignoreCase = true),
            "前 200 字符：" + content.take(200).replace("\n", " "))
    }

    val sharedLogAfter = if (sharedLog.isFile) sharedLog.lastModified() else -1L
    check("共享 <游戏目录>/logs/latest.log 没被这个实例改写",
        sharedLogAfter == sharedLogBefore,
        "before=" + sharedLogBefore + " after=" + sharedLogAfter)

    val sharedSavesAfter = File(gameDir, "saves").listFiles()?.size ?: -1
    check("共享 <游戏目录>/saves/ 没多出东西",
        sharedSavesAfter == sharedSavesBefore,
        "before=" + sharedSavesBefore + " after=" + sharedSavesAfter)

    // 这个实例自己的目录结构：logs 一定有；mods 由加载器建；options.txt 退出时才写，不强求。
    say("AFTER 实例目录内容:")
    layout.gameDir.listFiles()?.sortedBy { it.name }?.forEach {
        say("  " + (if (it.isDirectory) "d " else "f ") + it.name +
            (if (it.isFile) "  " + it.length() + " B" else ""))
    }

    if (menuAt == 0L) {
        say("没有进主菜单，先看日志尾巴：")
        gameLog.takeIf { it.isFile }?.readLines()?.takeLast(40)?.forEach { say("  | " + it) }
    }

    say("report=" + report.absolutePath)
    say("gameLog=" + gameLog.absolutePath)
    say("failures=" + failures)
    exitProcess(
        when {
            menuAt == 0L -> 4
            failures > 0 -> 5
            else -> 0
        },
    )
}
