package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.auth.OfflineProfile
import io.github.huang404yan.platypus.core.mods.ModInstaller
import io.github.huang404yan.platypus.core.mods.ModScanner
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
import io.github.huang404yan.platypus.ui.download.FabricInstallException
import io.github.huang404yan.platypus.ui.download.FabricInstaller
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
 * Fabric 一键装的无头验收驱动：干净目录 → 原版 → Fabric → 补 Java → 登记 → 启动进主菜单。
 *
 * 与 [E2eInstall] 唯一的区别是这里把 [LoaderChoice.Fabric] 交给同一个
 * [OneClickInstaller.install]——也就是 DownloadsPage 勾了「顺带装 Fabric」之后走的同一条路。
 *
 * 参数：<游戏目录> [游戏版本=1.20.1] [loader 版本=空即最新 stable] [离线用户名=Dev]
 *       [等主菜单毫秒=300000] [skipOrig=false] [装个模组=空即不装]
 *
 * 最后那个参数用来把「装 Fabric → 装模组 → 一起启动」串成一条链路：装完加载器后
 * 从 Modrinth 真下一个模组进**实例目录**的 mods/（隔离后实例目录才是游戏工作目录），
 * 再启动，日志里要能看到这个模组的痕迹才算加载过。
 *
 * skipOrig=true 时假定原版已装好，直接走 [FabricInstaller] 那条缝，用来单独验证
 * 「原版已存在时只补加载器」这一段；缺原版 jar 退出码 5，缺 Java 17 退出码 6。
 */
fun main(args: Array<String>) {
    val gameDir = File(args.getOrElse(0) { error("缺少参数：游戏目录") })
    val gameVersion = args.getOrElse(1) { "1.20.1" }
    val requestedLoader = args.getOrElse(2) { "" }
    val username = args.getOrElse(3) { "Dev" }
    val menuTimeoutMs = args.getOrElse(4) { "300000" }.toLong()
    val skipOrig = args.getOrElse(5) { "false" }.toBoolean()
    val installMod = args.getOrElse(6) { "" }.trim()

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val logDir = File("dist/_e2e-logs").apply { mkdirs() }
    val report = File(logDir, "fabric-e2e-" + gameVersion + "-" + stamp + ".log")
    val gameLog = File(logDir, "fabric-game-" + gameVersion + "-" + stamp + ".log")

    fun say(line: String) {
        val text = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] " + line
        println(text)
        report.appendText(text + System.lineSeparator())
    }

    say("gameDir=" + gameDir.absolutePath)
    say("gameVersion=" + gameVersion + " loader=" + requestedLoader.ifBlank { "(latest stable)" } +
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
                    loader = LoaderChoice.Fabric(requestedLoader.ifBlank { null }),
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
        val f = outcome.fabric
        if (f == null) {
            say("警告：本次没有产出 Fabric 安装结果")
        } else {
            say("  fabric versionId=" + f.versionId + " loader=" + f.loaderVersion +
                " json=" + f.jsonFile.absolutePath)
            say("  fabric libs=" + f.libraryFiles + " bytes=" + f.downloadedBytes +
                " ms=" + f.elapsedMs + " failures=" + f.downloadReport.failures.size)
        }
    } else {
        // 只补加载器：验「原版已经在了」这条分支，也顺带单独跑一遍 FabricInstaller。
        val vanillaJar = File(gameDir, "versions/" + gameVersion + "/" + gameVersion + ".jar")
        if (!vanillaJar.isFile) {
            say("SKIP-ORIG 需要已有的原版客户端：" + vanillaJar.absolutePath + " 不存在")
            exitProcess(5)
        }
        val fabric = try {
            runBlocking {
                FabricInstaller.install(
                    gameRoot = gameDir,
                    vanillaVersion = gameVersion,
                    loaderVersion = requestedLoader.ifBlank { null },
                ) { p ->
                    say("  FABRIC " + p.step.name + " " + p.detail)
                }
            }
        } catch (e: FabricInstallException) {
            say("FABRIC FAILED at " + e.step.name + ": " + e.message)
            exitProcess(2)
        }
        say("FABRIC OK in " + fabric.elapsedMs + " ms; versionId=" + fabric.versionId +
            " libs=" + fabric.libraryFiles + " bytes=" + fabric.downloadedBytes)

        val detected = runBlocking { javaService.detect() }
        val j17 = detected.findExactMajor(17) ?: run {
            say("本机没有 Java 17（探测到 " + detected.size + " 个运行时），skipOrig 模式不自带补 Java；" +
                "请先跑一次完整模式")
            exitProcess(6)
        }
        java = j17
        instance = runBlocking {
            FabricInstaller.register(
                instances = repository,
                gameRoot = gameDir,
                versionId = fabric.versionId,
                loaderVersion = fabric.loaderVersion,
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
    if (target.loader != LoaderKind.FABRIC) {
        say("警告：实例 loader 不是 FABRIC，而是 " + target.loader)
    }

    // ── 装一个模组（可选）：把「装 Fabric → 装模组 → 一起启动」串成一条链路 ──
    // 注意装进的是**实例目录**而不是 gameDir：实例隔离之后，实例目录才是游戏的工作目录，
    // ModsPage 走的也是 InstanceMods / instance.directory，两边必须一致，否则装了看不见。
    var modJar: File? = null
    if (installMod.isNotBlank()) {
        val instanceDir = File(target.directory.trim().trim('"'))
        say("MODS install " + installMod + " into " + instanceDir.absolutePath + "/mods")
        try {
            val modResult = runBlocking {
                ModInstaller.install(
                    gameDir = instanceDir,
                    slug = installMod,
                    gameVersion = target.gameVersion,
                    loader = LoaderKind.FABRIC,
                ) { p ->
                    say("  MOD " + p.step.name + " " + p.detail)
                }
            }
            modJar = modResult.file
            say("MODS OK " + modResult.file.name + " bytes=" + modResult.downloadedBytes +
                " version=" + modResult.version + " sha1Checked=" + (modResult.entry.problem == null))
            if (modResult.requiredDependencies.isNotEmpty()) {
                say("  MODS required dependencies not installed: " +
                    modResult.requiredDependencies.joinToString(", "))
            }
            val modScan = ModScanner.scan(instanceDir)
            say("MODS SCAN enabled=" + modScan.mods.size + " disabled=" + modScan.disabled.size +
                " warnings=" + modScan.warnings.size)
            (modScan.mods + modScan.disabled).forEach {
                say("  " + it.fileName + " / id=" + (it.modId ?: "-") + " / loader=" +
                    (it.loader?.name ?: "-") + " / problem=" + (it.problem ?: "-"))
            }
            if ((modScan.mods + modScan.disabled).none { it.fileName == modResult.file.name }) {
                say("MODS FAILED 装完扫不到 " + modResult.file.name)
                exitProcess(7)
            }
        } catch (e: Throwable) {
            say("MODS FAILED: " + e)
            e.stackTrace.take(8).forEach { say("  at " + it) }
            exitProcess(7)
        }
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
                        if (loaderLines.size < 20 &&
                            (lower.contains("fabric") || lower.contains("mixin") || lower.contains("knot"))
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

    // 模组是否真被加载：Fabric 启动时会把已加载的模组 id 打进日志（"Loading 1 mods:" 之类）。
    // 只做如实记录，不拿它当失败的判据——有的模组一句话都不打，静默加载也算成功。
    if (modJar != null) {
        val lines = runCatching { gameLog.readLines() }.getOrDefault(emptyList())
        val slug = installMod.lowercase()
        val hits = lines.filter {
            val l = it.lowercase()
            l.contains(slug) || l.contains("loading ") && l.contains("mods")
        }
        say("MODS LOG HITS (" + hits.size + ")")
        hits.take(10).forEach { say("  | " + it.take(200)) }
        val proved = lines.any {
            val l = it.lowercase()
            l.contains(slug) || (l.contains("loading") && l.contains("mods"))
        }
        say("MODS LOADED proved=" + proved)
    }

    say("FABRIC LOG LINES (" + loaderLines.size + ")")
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
