package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.auth.OfflineProfile
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.launch.GameHandle
import io.github.huang404yan.platypus.core.launch.LaunchDiagnosis
import io.github.huang404yan.platypus.core.launch.LaunchEvent
import io.github.huang404yan.platypus.core.launch.LaunchRequest
import io.github.huang404yan.platypus.core.loader.NeoForgeMeta
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.runtime.findExactMajor
import io.github.huang404yan.platypus.desktop.runtime.DesktopGameRuntime
import io.github.huang404yan.platypus.desktop.runtime.DesktopJavaRuntimeService
import io.github.huang404yan.platypus.core.install.InstallStep
import io.github.huang404yan.platypus.core.install.InstallStepException
import io.github.huang404yan.platypus.core.install.LoaderChoice
import io.github.huang404yan.platypus.core.install.NeoForgeInstallException
import io.github.huang404yan.platypus.core.install.NeoForgeInstaller
import io.github.huang404yan.platypus.core.install.OneClickInstaller
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * NeoForge 一键装的无头验收驱动。
 *
 * 与 [ForgeE2e] 同一条路（[OneClickInstaller.install] + LoaderChoice.NeoForge），
 * 只是多验两件 NeoForge 特有的事：
 * 1. installer jar 内 inheritsFrom 与请求的原版号一致（版本号→MC 版本的推导有没有错）；
 * 2. 产出的版本 json 的 mainClass 是 BootstrapLauncher、inheritsFrom 是原版号。
 *
 * 参数：<游戏目录> [游戏版本=1.21.1] [neoforge 版本=空即按 MC 版本挑] [离线用户名=Dev]
 *       [等主菜单毫秒=300000] [skipOrig=false]
 *
 * skipOrig=true 时假定原版已装好，直接走 [NeoForgeInstaller] 那一段；
 * 缺原版 jar 退出码 5，缺 Java 退出码 6。
 */
fun main(args: Array<String>) {
    val gameDir = File(args.getOrElse(0) { error("缺少参数：游戏目录") })
    val gameVersion = args.getOrElse(1) { "1.21.1" }
    val requestedNeoForge = args.getOrElse(2) { "" }
    val username = args.getOrElse(3) { "Dev" }
    val menuTimeoutMs = args.getOrElse(4) { "300000" }.toLong()
    val skipOrig = args.getOrElse(5) { "false" }.toBoolean()

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val logDir = File("dist/_e2e-logs").apply { mkdirs() }
    val report = File(logDir, "neoforge-e2e-" + gameVersion + "-" + stamp + ".log")
    val gameLog = File(logDir, "neoforge-game-" + gameVersion + "-" + stamp + ".log")

    fun say(line: String) {
        val text = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] " + line
        println(text)
        report.appendText(text + System.lineSeparator())
    }

    say("gameDir=" + gameDir.absolutePath)
    say("gameVersion=" + gameVersion + " neoforge=" + requestedNeoForge.ifBlank { "(按 MC 版本挑)" } +
        " user=" + username + " skipOrig=" + skipOrig)
    say("emptyDir=" + (gameDir.listFiles()?.isEmpty() ?: true))

    // 与其它 e2e 一致：关掉全局扫描，逼「本机没有精确主版本 → 自动补 Java」这条分支真跑。
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
                    loader = LoaderChoice.NeoForge(requestedNeoForge.ifBlank { null }),
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
            var cause: Throwable? = e.cause
            var depth = 0
            while (cause != null && depth < 5) {
                say("  caused by: " + cause)
                cause = cause.cause
                depth++
            }
            e.stackTrace.take(16).forEach { say("  at " + it) }
            val cp = System.getProperty("java.class.path") ?: ""
            cp.split(File.pathSeparatorChar).filter { it.contains("core") }.forEach { say("  cp: " + it) }
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
        val nf = outcome.neoforge
        if (nf == null) {
            say("警告：本次没有产出 NeoForge 安装结果")
        } else {
            say("  neoforge versionId=" + nf.versionId + " version=" + nf.neoforgeVersion +
                " json=" + nf.jsonFile.absolutePath)
            say("  neoforge installer=" + nf.installerFile.name + " bytes=" + nf.downloadedBytes +
                " exit=" + nf.installerExitCode + " ms=" + nf.elapsedMs)
            say("  neoforge inheritsFrom=" + nf.versionJson.inheritsFrom +
                " mainClass=" + nf.versionJson.mainClass)
            say("  neoforge clientJar=" + (nf.clientJarFile?.absolutePath ?: "(缺失)"))
            say("  neoforge log=" + nf.logFile.absolutePath)
            if (nf.versionJson.inheritsFrom != gameVersion) {
                say("警告：产出的 inheritsFrom=" + nf.versionJson.inheritsFrom + " 与请求的 " + gameVersion + " 不一致")
            }
        }
    } else {
        // 只补加载器：验「原版已经在了」这条分支，也顺带单独跑一遍 NeoForgeInstaller。
        val vanillaJar = File(gameDir, "versions/" + gameVersion + "/" + gameVersion + ".jar")
        if (!vanillaJar.isFile) {
            say("SKIP-ORIG 需要已有的原版客户端：" + vanillaJar.absolutePath + " 不存在")
            exitProcess(5)
        }
        val detected = runBlocking { javaService.detect() }
        val required = runCatching {
            val json = File(gameDir, "versions/" + gameVersion + "/" + gameVersion + ".json")
            io.github.huang404yan.platypus.core.version.VersionJsonFormat.decodeFromString(
                io.github.huang404yan.platypus.core.version.VersionJson.serializer(),
                json.readText(Charsets.UTF_8),
            ).requiredJavaMajor
        }.getOrDefault(21)
        val javaForInstall = detected.findExactMajor(required) ?: run {
            say("本机没有 Java " + required + "（探测到 " + detected.size + " 个运行时），" +
                "skipOrig 模式不自带补 Java；请先跑一次完整模式")
            exitProcess(6)
        }
        val nf = try {
            runBlocking {
                NeoForgeInstaller.install(
                    gameRoot = gameDir,
                    vanillaVersion = gameVersion,
                    javaPath = javaForInstall.path,
                    neoforgeVersion = requestedNeoForge.ifBlank { null },
                ) { p ->
                    say("  NEOFORGE " + p.step.name + " " + p.detail)
                }
            }
        } catch (e: NeoForgeInstallException) {
            say("NEOFORGE FAILED at " + e.step.name + ": " + e.message)
            exitProcess(2)
        }
        say("NEOFORGE OK in " + nf.elapsedMs + " ms; versionId=" + nf.versionId +
            " version=" + nf.neoforgeVersion + " exit=" + nf.installerExitCode +
            " log=" + nf.logFile.absolutePath)

        java = javaForInstall
        instance = runBlocking {
            NeoForgeInstaller.register(
                instances = repository,
                gameRoot = gameDir,
                result = nf,
                gameVersion = gameVersion,
                javaPath = javaForInstall.path,
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
    if (target.loader != LoaderKind.NEOFORGE) {
        say("警告：实例 loader 不是 NEOFORGE，而是 " + target.loader)
    }
    // 版本目录里的 jar 必须存在：NeoForge 的 launch 参数里 -DignoreList= 假定它在那，
    // 缺了会在启动阶段抛 ResolutionException。这里提前断言，失败原因更清楚。
    val expectedJar = File(gameDir, "versions/" + target.id + "/" + target.id + ".jar")
    say("VERSION JAR exists=" + expectedJar.isFile + " bytes=" + expectedJar.length() +
        " path=" + expectedJar.absolutePath)

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
                        if (loaderLines.size < 25 &&
                            (lower.contains("neoforge") || lower.contains("fml") ||
                                lower.contains("bootstraplauncher") || lower.contains("modlauncher"))
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
        delay(3_000)
        runCatching { handle?.stop() }
        delay(2_000)
    }

    val launchMs = if (menuAt != 0L) menuAt - launchAt else -1L
    say("NEOFORGE LOG LINES (" + loaderLines.size + ")")
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
