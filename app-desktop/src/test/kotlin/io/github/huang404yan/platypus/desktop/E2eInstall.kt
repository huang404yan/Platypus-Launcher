package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.auth.OfflineProfile
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.launch.GameHandle
import io.github.huang404yan.platypus.core.launch.LaunchDiagnosis
import io.github.huang404yan.platypus.core.launch.LaunchEvent
import io.github.huang404yan.platypus.core.launch.LaunchRequest
import io.github.huang404yan.platypus.desktop.runtime.DesktopGameRuntime
import io.github.huang404yan.platypus.desktop.runtime.DesktopJavaRuntimeService
import io.github.huang404yan.platypus.ui.download.InstallStep
import io.github.huang404yan.platypus.ui.download.InstallStepException
import io.github.huang404yan.platypus.ui.download.OneClickInstaller
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * 开发期无头验收驱动：在一个真实目录上走一遍「下载 → 补 Java → 登记实例 → 启动进主菜单」。
 *
 * 放在 test 源集，不进发行包；只由 `gradle :app-desktop:e2eInstall` 调用。
 * 关键点：它调的 [OneClickInstaller.install] 与 DownloadsPage「开始安装」按钮是同一个函数，
 * [LaunchRequest] 的组装方式与 LaunchPage.launchGame 一致，唯一区别是没有鼠标点击。
 *
 * 参数：<游戏目录> [版本号=1.20.1] [离线用户名=Dev] [等主菜单超时毫秒=300000]
 */
fun main(args: Array<String>) {
    val gameDir = File(args.getOrElse(0) { error("缺少参数：游戏目录") })
    val versionId = args.getOrElse(1) { "1.20.1" }
    val username = args.getOrElse(2) { "Dev" }
    val menuTimeoutMs = args.getOrElse(3) { "300000" }.toLong()

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val logDir = File("dist/_e2e-logs").apply { mkdirs() }
    val report = File(logDir, "e2e-" + versionId + "-" + stamp + ".log")
    val gameLog = File(logDir, "game-" + versionId + "-" + stamp + ".log")

    fun say(line: String) {
        val text = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] " + line
        println(text)
        report.appendText(text + System.lineSeparator())
    }

    say("gameDir=" + gameDir.absolutePath)
    say("version=" + versionId + " user=" + username)
    say("emptyDir=" + (gameDir.listFiles()?.isEmpty() ?: true))

    // scanGlobalLaunchers=false：本机 %APPDATA%/.minecraft/runtime 下已经有 java-runtime-beta(17)，
    // 开着它探测会直接命中，自动下载那条路就永远不会被执行，测出来的"通过"是假的。
    val javaService = DesktopJavaRuntimeService(gameRoot = gameDir, scanGlobalLaunchers = false)
    val repository = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(gameDir))
    val startedAt = System.currentTimeMillis()
    var lastStep: InstallStep? = null

    val outcome = try {
        runBlocking {
            OneClickInstaller.install(
                gameDir = gameDir,
                versionId = versionId,
                javaRuntimes = javaService,
                instances = repository,
                onProgress = { p ->
                    if (p.step != lastStep) {
                        lastStep = p.step
                        say("STEP " + p.step.name + " " + p.step.label)
                    }
                    if (p.step == InstallStep.DOWNLOADING || p.step == InstallStep.JAVA_INSTALL) {
                        say("  " + p.step.name + " " + p.detail + " " + (p.fraction * 100).toInt() + "%")
                    } else {
                        say("  " + p.step.name + " " + p.detail)
                    }
                },
            )
        }
    } catch (e: InstallStepException) {
        say("INSTALL FAILED at step " + e.step.name + ": " + e.message)
        e.stackTrace.take(10).forEach { say("  at " + it) }
        exitProcess(2)
    } catch (e: Throwable) {
        say("INSTALL FAILED (unexpected): " + e)
        exitProcess(3)
    }

    say("INSTALL OK in " + (System.currentTimeMillis() - startedAt) + " ms; javaInstalled=" + outcome.javaInstalled)
    say("  instance id=" + outcome.instance.id + " name=" + outcome.instance.name + " gameVersion=" + outcome.instance.gameVersion)
    say("  directory=" + outcome.instance.directory + " memoryMb=" + outcome.instance.memoryMb)
    say("  java=" + outcome.java.path + " major=" + outcome.java.majorVersion + " vendor=" + outcome.java.vendor + " managed=" + outcome.java.managed)
    say("  downloaded=" + outcome.downloadReport.succeeded + " failures=" + outcome.downloadReport.failures.size)

    val registered: List<Instance> = runBlocking { repository.list() }
    say("REGISTERED count=" + registered.size)
    registered.forEach {
        say("  " + it.id + " memoryMb=" + it.memoryMb + " registered=" + it.registered + " directory=" + it.directory)
    }

    // ── 启动（与 LaunchPage.launchGame 的组装一致） ──────────────────────────
    say("LAUNCH start")
    val launchAt = System.currentTimeMillis()
    var menuAt = 0L
    var exitCode: Int? = null
    var handle: GameHandle? = null

    runBlocking {
        withTimeoutOrNull(menuTimeoutMs) {
            val started = DesktopGameRuntime().launch(
                LaunchRequest(
                    instance = outcome.instance,
                    java = outcome.java,
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
                            say("  MENU REACHED +" + (menuAt - launchAt) + " ms: " + event.text.take(200))
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
        // 主菜单出现后再等 3 秒，让界面真正渲染完，然后主动收尾，避免留下孤儿进程。
        delay(3_000)
        runCatching { handle?.stop() }
        delay(2_000)
    }

    val launchMs = if (menuAt != 0L) menuAt - launchAt else -1L
    say("RESULT menuReached=" + (menuAt != 0L) + " launchMs=" + launchMs + " exitCode=" + exitCode)
    say("TOTAL ms=" + (System.currentTimeMillis() - startedAt))
    say("report=" + report.absolutePath)
    say("gameLog=" + gameLog.absolutePath)

    if (menuAt == 0L) {
        say("--- game log tail (40) ---")
        gameLog.takeIf { it.isFile }?.readLines()?.takeLast(40)?.forEach { say("  | " + it) }
        exitProcess(4)
    }
    exitProcess(0)
}
