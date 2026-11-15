package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.auth.OfflineProfile
import io.github.huang404yan.platypus.core.install.InstallStep
import io.github.huang404yan.platypus.core.install.InstallStepException
import io.github.huang404yan.platypus.core.install.LoaderChoice
import io.github.huang404yan.platypus.core.install.OneClickInstaller
import io.github.huang404yan.platypus.core.install.QuiltInstallException
import io.github.huang404yan.platypus.core.install.QuiltInstaller
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.launch.GameHandle
import io.github.huang404yan.platypus.core.launch.LaunchDiagnosis
import io.github.huang404yan.platypus.core.launch.LaunchEvent
import io.github.huang404yan.platypus.core.launch.LaunchRequest
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
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
 * Quilt 一键装的无头验收驱动：干净目录 → 原版 → Quilt → 补 Java → 登记 → 启动进主菜单。
 *
 * 走的就是 DownloadsPage 勾了「顺带装 Quilt」之后的同一条 [OneClickInstaller.install]，
 * 只是没有鼠标点击。它要证明的是 [QuiltInstaller] 拉下来的 profile json 真能起得来 ——
 * 单测只能证明解析对，证明不了 org.quiltmc 的库一个不缺。
 *
 * 参数：<游戏目录> [游戏版本=1.20.1] [loader 版本=空即最新正式版] [离线用户名=Dev]
 *       [等主菜单毫秒=300000] [skipOrig=false]
 *
 * skipOrig=true 时假定原版已装好，直接走 [QuiltInstaller] 那条缝，用来单独验证
 * 「原版已存在时只补加载器」这一段；缺原版 jar 退出码 5，缺 Java 17 退出码 6。
 *
 * 退出码：0 通过 / 2 装加载器失败 / 3 意外异常或没有实例 / 4 没进主菜单。
 */
fun main(args: Array<String>) {
    val gameDir = File(args.getOrElse(0) { error("缺少参数：游戏目录") })
    val gameVersion = args.getOrElse(1) { "1.20.1" }
    val requestedLoader = args.getOrElse(2) { "" }
    val username = args.getOrElse(3) { "Dev" }
    val menuTimeoutMs = args.getOrElse(4) { "300000" }.toLong()
    val skipOrig = args.getOrElse(5) { "false" }.toBoolean()

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val logDir = File("dist/_e2e-logs").apply { mkdirs() }
    val report = File(logDir, "quilt-e2e-" + gameVersion + "-" + stamp + ".log")
    val gameLog = File(logDir, "quilt-game-" + gameVersion + "-" + stamp + ".log")

    fun say(line: String) {
        val text = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] " + line
        println(text)
        report.appendText(text + System.lineSeparator())
    }

    say("gameDir=" + gameDir.absolutePath)
    say("gameVersion=" + gameVersion + " loader=" + requestedLoader.ifBlank { "(latest release)" } +
        " user=" + username + " skipOrig=" + skipOrig)

    // 与 E2eInstall / FabricE2e 同样的理由：本机 %APPDATA% 下已有 java-runtime-beta(17)，
    // 开着全局扫描会让「自动补 Java」永远走不到，测出来的通过是假的。
    val javaService = DesktopJavaRuntimeService(gameRoot = gameDir, scanGlobalLaunchers = false)
    val repository = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(gameDir))
    val startedAt = System.currentTimeMillis()

    var instance: Instance? = null
    var java: JavaRuntime? = null

    if (!skipOrig) {
        var lastStep: InstallStep? = null
        val outcome = try {
            runBlocking {
                OneClickInstaller.install(
                    gameDir = gameDir,
                    versionId = gameVersion,
                    javaRuntimes = javaService,
                    instances = repository,
                    loader = LoaderChoice.Quilt(requestedLoader.ifBlank { null }),
                    onProgress = { p ->
                        if (p.step != lastStep) {
                            lastStep = p.step
                            say("STEP " + p.step.name + " " + p.step.label)
                        }
                        val pct = if (p.total > 0) " " + (p.fraction * 100).toInt() + "%" else ""
                        say("  " + p.step.name + " " + p.detail + pct)
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

        instance = outcome.instance
        java = outcome.java

        say("INSTALL OK in " + (System.currentTimeMillis() - startedAt) + " ms; javaInstalled=" + outcome.javaInstalled)
        say("  instance id=" + outcome.instance.id + " name=" + outcome.instance.name +
            " gameVersion=" + outcome.instance.gameVersion +
            " loader=" + outcome.instance.loader + " loaderVersion=" + outcome.instance.loaderVersion)
        say("  directory=" + outcome.instance.directory + " memoryMb=" + outcome.instance.memoryMb)
        say("  java=" + outcome.java.path + " major=" + outcome.java.majorVersion +
            " vendor=" + outcome.java.vendor + " managed=" + outcome.java.managed)
        say("  vanilla downloaded=" + outcome.downloadReport.succeeded +
            " failures=" + outcome.downloadReport.failures.size)
        val q = outcome.quilt
        if (q == null) {
            say("没有产出 Quilt 安装结果")
            exitProcess(2)
        } else {
            say("  quilt versionId=" + q.versionId + " loader=" + q.loaderVersion +
                " json=" + q.jsonFile.absolutePath)
            say("  quilt libs=" + q.libraryFiles + " bytes=" + q.downloadedBytes +
                " ms=" + q.elapsedMs + " failures=" + q.downloadReport.failures.size)
            // 库缺 sha1 是 Quilt profile 的常态（实测 14 个库一个都没带），补上了多少要如实记下来
            say("  quilt checksum filled=" + q.checksumFilled + " missing=" + q.checksumMissing)
            if (!q.jsonFile.isFile) {
                say("QUILT FAILED 版本 json 没落盘：" + q.jsonFile.absolutePath)
                exitProcess(3)
            }
        }
    } else {
        // 只补加载器：验「原版已经在了」这条分支，也顺带单独跑一遍 QuiltInstaller。
        val vanillaJar = File(gameDir, "versions/" + gameVersion + "/" + gameVersion + ".jar")
        if (!vanillaJar.isFile) {
            say("SKIP-ORIG 需要已有的原版客户端：" + vanillaJar.absolutePath + " 不存在")
            exitProcess(5)
        }
        val quilt = try {
            runBlocking {
                QuiltInstaller.install(
                    gameRoot = gameDir,
                    vanillaVersion = gameVersion,
                    loaderVersion = requestedLoader.ifBlank { null },
                ) { p ->
                    say("  QUILT " + p.step.name + " " + p.detail)
                }
            }
        } catch (e: QuiltInstallException) {
            say("QUILT FAILED at " + e.step.name + ": " + e.message)
            exitProcess(2)
        }
        say("QUILT OK in " + quilt.elapsedMs + " ms; versionId=" + quilt.versionId +
            " libs=" + quilt.libraryFiles + " bytes=" + quilt.downloadedBytes +
            " checksumFilled=" + quilt.checksumFilled + " missing=" + quilt.checksumMissing)

        // installedLoaders 也要顺带验一次：它能认出刚装的那个目录，说明反解 id 与扫描都成立
        val detectedQuilt = QuiltInstaller.installedLoaders(gameDir, gameVersion)
        say("QUILT SCAN count=" + detectedQuilt.size)
        detectedQuilt.forEach { say("  " + it.versionId + " loader=" + it.loaderVersion) }

        val detected = runBlocking { javaService.detect() }
        val j17 = detected.findExactMajor(17) ?: run {
            say("本机没有 Java 17（探测到 " + detected.size + " 个运行时），skipOrig 模式不自带补 Java；" +
                "请先跑一次完整模式")
            exitProcess(6)
        }
        java = j17
        instance = runBlocking {
            QuiltInstaller.register(
                instances = repository,
                gameRoot = gameDir,
                versionId = quilt.versionId,
                loaderVersion = quilt.loaderVersion,
                gameVersion = gameVersion,
                javaPath = j17.path,
                memoryMb = Instance.DEFAULT_MEMORY_MB,
            )
        }
        say("REGISTERED " + instance!!.id + " loader=" + instance!!.loader +
            " loaderVersion=" + instance!!.loaderVersion)
    }

    val registered: List<Instance> = runBlocking { repository.list() }
    say("REGISTERED count=" + registered.size)
    registered.forEach {
        say("  " + it.id + " loader=" + it.loader + " loaderVersion=" + it.loaderVersion +
            " memoryMb=" + it.memoryMb + " registered=" + it.registered + " directory=" + it.directory)
    }

    val target = instance ?: run {
        say("没有实例可启动")
        exitProcess(3)
    }
    val useJava = java ?: run {
        say("没有可用的 Java 运行时")
        exitProcess(3)
    }
    if (target.loader != LoaderKind.QUILT) {
        say("实例 loader 不是 QUILT，而是 " + target.loader)
        exitProcess(3)
    }

    // ── 启动（与 LaunchPage.launchGame 的组装一致） ──────────────────────────
    say("LAUNCH start")
    val launchAt = System.currentTimeMillis()
    var menuAt = 0L
    var exitCode: Int? = null
    var handle: GameHandle? = null
    val loaderLines = mutableListOf<String>()

    runBlocking {
        withTimeoutOrNull(menuTimeoutMs) {
            val started = DesktopGameRuntime().launch(
                LaunchRequest(
                    instance = target,
                    java = useJava,
                    session = OfflineProfile.session(username),
                ),
            ) { event ->
                when (event) {
                    is LaunchEvent.Stage -> say("  stage " + event.name)
                    is LaunchEvent.Started -> say("  started pid=" + event.pid)
                    is LaunchEvent.LogLine -> {
                        gameLog.appendText(event.text + System.lineSeparator())
                        val lower = event.text.lowercase()
                        // Quilt 的启动类叫 KnotClient，日志里会出现 quilt / knot / mixin 三种字样
                        if (loaderLines.size < 20 &&
                            (lower.contains("quilt") || lower.contains("knot") ||
                                lower.contains("mixin") || lower.contains("fabric"))
                        ) {
                            loaderLines.add(event.text.take(300))
                        }
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
        // 主菜单出来后再等 3 秒让界面渲染完，然后主动收尾，避免留下孤儿进程。
        delay(3_000)
        runCatching { handle?.stop() }
        delay(2_000)
    }

    val launchMs = if (menuAt != 0L) menuAt - launchAt else -1L

    say("QUILT LOG LINES (" + loaderLines.size + ")")
    loaderLines.forEach { say("  | " + it) }
    say("RESULT menuReached=" + (menuAt != 0L) + " launchMs=" + launchMs + " exitCode=" + exitCode)
    say("TOTAL ms=" + (System.currentTimeMillis() - startedAt))
    say("report=" + report.absolutePath)
    say("gameLog=" + gameLog.absolutePath)

    if (menuAt == 0L) {
        say("--- game log tail (60) ---")
        gameLog.takeIf { it.isFile }?.readLines()?.takeLast(60)?.forEach { say("  | " + it) }
        exitProcess(4)
    }
    exitProcess(0)
}