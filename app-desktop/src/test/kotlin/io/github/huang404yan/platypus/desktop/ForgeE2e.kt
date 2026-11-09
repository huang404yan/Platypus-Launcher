package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.auth.OfflineProfile
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
import io.github.huang404yan.platypus.ui.download.ForgeInstallException
import io.github.huang404yan.platypus.ui.download.ForgeInstaller
import io.github.huang404yan.platypus.ui.download.InstallStep
import io.github.huang404yan.platypus.ui.download.InstallStepException
import io.github.huang404yan.platypus.ui.download.LoaderChoice
import io.github.huang404yan.platypus.ui.download.OneClickInstaller
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Forge 一键装的无头验收驱动：干净目录 → 原版 → 补 Java → 官方安装器 → 登记 → 启动进主菜单。
 *
 * 走的就是 DownloadsPage 勾了「顺带装 Forge」之后的同一条 [OneClickInstaller.install]，
 * 只是没有鼠标点击。Forge 与 Fabric 的差别在这里能看出来：Forge 要起一个真实的
 * java 子进程跑 installer，所以这一步会慢（1.20.1 实测约 3 分钟）。
 *
 * 参数：<游戏目录> [游戏版本=1.20.1] [forge 版本=空即推荐版] [离线用户名=Dev]
 *       [等主菜单毫秒=300000] [skipOrig=false]
 *
 * skipOrig=true 时假定原版已装好，直接走 [ForgeInstaller] 那条缝，用来单独验证
 * 「原版已存在时只补加载器」这一段；缺原版 jar 退出码 5，缺 Java 退出码 6。
 */
fun main(args: Array<String>) {
    val gameDir = File(args.getOrElse(0) { error("缺少参数：游戏目录") })
    val gameVersion = args.getOrElse(1) { "1.20.1" }
    val requestedForge = args.getOrElse(2) { "" }
    val username = args.getOrElse(3) { "Dev" }
    val menuTimeoutMs = args.getOrElse(4) { "300000" }.toLong()
    val skipOrig = args.getOrElse(5) { "false" }.toBoolean()

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val logDir = File("dist/_e2e-logs").apply { mkdirs() }
    val report = File(logDir, "forge-e2e-" + gameVersion + "-" + stamp + ".log")
    val gameLog = File(logDir, "forge-game-" + gameVersion + "-" + stamp + ".log")

    fun say(line: String) {
        val text = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] " + line
        println(text)
        report.appendText(text + System.lineSeparator())
    }

    say("gameDir=" + gameDir.absolutePath)
    say("gameVersion=" + gameVersion + " forge=" + requestedForge.ifBlank { "(recommended)" } +
        " user=" + username + " skipOrig=" + skipOrig)
    say("emptyDir=" + (gameDir.listFiles()?.isEmpty() ?: true))

    // 与 E2eInstall 同样的理由：本机 %APPDATA% 下已有 java-runtime-beta(17)，
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
                    loader = LoaderChoice.Forge(requestedForge.ifBlank { null }),
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
            // NoClassDefFoundError 的 message 只有类名，真正原因在 cause 里（类初始化失败时会套一层
            // ExceptionInInitializerError）。这里把 cause 链和栈都打出来，否则日志看不出所以然。
            say("INSTALL FAILED (unexpected): " + e)
            var cause: Throwable? = e.cause
            var depth = 0
            while (cause != null && depth < 5) {
                say("  caused by: " + cause)
                cause = cause.cause
                depth++
            }
            e.stackTrace.take(16).forEach { say("  at " + it) }
            // 顺带把 classpath 里所有 core 条目打出来：跨模块 NoClassDefFound 多半是 classpath 上
            // 有第二份旧 core（jar 与 classes 目录同时存在时，先命中的那份说了算）。
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
        val f = outcome.forge
        if (f == null) {
            say("警告：本次没有产出 Forge 安装结果")
        } else {
            say("  forge versionId=" + f.versionId + " version=" + f.forgeVersion +
                " json=" + f.jsonFile.absolutePath)
            say("  forge installer=" + f.installerFile.name + " bytes=" + f.downloadedBytes +
                " exit=" + f.installerExitCode + " ms=" + f.elapsedMs)
            say("  forge log=" + f.logFile.absolutePath)
        }
    } else {
        // 只补加载器：验「原版已经在了」这条分支，也顺带单独跑一遍 ForgeInstaller。
        val vanillaJar = File(gameDir, "versions/" + gameVersion + "/" + gameVersion + ".jar")
        if (!vanillaJar.isFile) {
            say("SKIP-ORIG 需要已有的原版客户端：" + vanillaJar.absolutePath + " 不存在")
            exitProcess(5)
        }
        val detected = runBlocking { javaService.detect() }
        // Forge installer 自己也是个 java 程序：用游戏要求的那个主版本跑它最稳。
        val required = runCatching {
            val json = File(gameDir, "versions/" + gameVersion + "/" + gameVersion + ".json")
            io.github.huang404yan.platypus.core.version.VersionJsonFormat.decodeFromString(
                io.github.huang404yan.platypus.core.version.VersionJson.serializer(),
                json.readText(Charsets.UTF_8),
            ).requiredJavaMajor
        }.getOrDefault(17)
        val javaForInstall = detected.findExactMajor(required) ?: run {
            say("本机没有 Java " + required + "（探测到 " + detected.size + " 个运行时），" +
                "skipOrig 模式不自带补 Java；请先跑一次完整模式")
            exitProcess(6)
        }
        val forge = try {
            runBlocking {
                ForgeInstaller.install(
                    gameRoot = gameDir,
                    vanillaVersion = gameVersion,
                    javaPath = javaForInstall.path,
                    forgeVersion = requestedForge.ifBlank { null },
                ) { p ->
                    say("  FORGE " + p.step.name + " " + p.detail)
                }
            }
        } catch (e: ForgeInstallException) {
            say("FORGE FAILED at " + e.step.name + ": " + e.message)
            exitProcess(2)
        }
        say("FORGE OK in " + forge.elapsedMs + " ms; versionId=" + forge.versionId +
            " version=" + forge.forgeVersion + " exit=" + forge.installerExitCode +
            " log=" + forge.logFile.absolutePath)

        java = javaForInstall
        instance = runBlocking {
            ForgeInstaller.register(
                instances = repository,
                gameRoot = gameDir,
                result = forge,
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
    if (target.loader != LoaderKind.FORGE) {
        say("警告：实例 loader 不是 FORGE，而是 " + target.loader)
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
                        if (loaderLines.size < 25 &&
                            (lower.contains("forge") || lower.contains("fml") ||
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
        // 主菜单出来后再等 3 秒让界面渲染完，然后主动收尾，避免留下孤儿进程。
        delay(3_000)
        runCatching { handle?.stop() }
        delay(2_000)
    }

    val launchMs = if (menuAt != 0L) menuAt - launchAt else -1L
    say("FORGE LOG LINES (" + loaderLines.size + ")")
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
