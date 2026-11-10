package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.auth.OfflineProfile
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.launch.GameHandle
import io.github.huang404yan.platypus.core.launch.LaunchDiagnosis
import io.github.huang404yan.platypus.core.launch.LaunchEvent
import io.github.huang404yan.platypus.core.launch.LaunchRequest
import io.github.huang404yan.platypus.core.modpack.ModpackKind
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.desktop.runtime.DesktopGameRuntime
import io.github.huang404yan.platypus.desktop.runtime.DesktopJavaRuntimeService
import io.github.huang404yan.platypus.core.install.ModpackImportException
import io.github.huang404yan.platypus.core.install.ModpackImporter
import io.github.huang404yan.platypus.core.install.ModpackStep
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * 整合包导入的无头验收：现场造一个真 .mrpack → 导入 → 启动进主菜单。
 *
 * 包是本地生成的（不依赖别人上传的包会不会哪天被删），但包里指向的文件是真的：
 * AppleSkin 与它依赖的 fabric-api，都是 Modrinth 上 1.20.1 fabric 版的，
 * url / sha1 / size 全部取自 API 实测。这样同时验到四件事：解析器认得出格式、
 * 下载器真的按 sha1 校验并落盘、多文件包能一次下齐、导入完的实例能被日常启动链路拉起来。
 *
 * 为什么必须带 fabric-api：AppleSkin 的 fabric.mod.json 里写着 depends fabric-api，
 * 只装 AppleSkin 时 Fabric loader 会在进世界前抛
 * "Incompatible mods found! ... 模组 'AppleSkin' (appleskin) 2.5.2+mc1.20.1 需要 fabric-api 的 任意版本"。
 * 这不是导入器的锅（真实整合包的 files[] 里会带依赖），但验收包漏带就会假失败一次。
 *
 * 参数：<游戏目录> [整合包目录=dist/_modpack-e2e] [等主菜单毫秒=300000]
 *
 * 退出码：0 通过；2 解析失败；3 导入失败；4 产物校验失败；5 没能进主菜单。
 */
fun main(args: Array<String>) {
    val gameDir = File(args.getOrElse(0) { "dist/_modpack-e2e" }).absoluteFile
    val packDir = File(args.getOrElse(1) { "dist/_modpack-e2e-packs" }).absoluteFile
    val menuTimeoutMs = args.getOrElse(2) { "300000" }.toLong()

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val logDir = File("dist/_e2e-logs").apply { mkdirs() }
    val report = File(logDir, "modpack-e2e-" + stamp + ".log")
    val gameLog = File(logDir, "modpack-game-" + stamp + ".log")

    fun say(line: String) {
        val text = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] " + line
        println(text)
        report.appendText(text + System.lineSeparator())
    }

    say("gameDir=" + gameDir.absolutePath)
    say("packDir=" + packDir.absolutePath)
    gameDir.mkdirs()
    packDir.mkdirs()

    // ── 1. 造包 ─────────────────────────────────────────────────────────────
    val archive = File(packDir, "appleskin-demo.mrpack")
    val modUrl = "https://cdn.modrinth.com/data/EsAfCjCV/versions/N5XeV21r/" +
        "appleskin-fabric-mc1.20.1-2.5.2.jar"
    val modSha1 = "f859bb13130788ae2dc2e76a9c80c89e4bbbef5f"
    val modSize = 1097240L
    // AppleSkin 依赖 fabric-api，包不带它会在 Fabric loader 加载阶段直接崩（见文件头注释）。
    val apiUrl = "https://cdn.modrinth.com/data/P7dR8mSH/versions/rvI2dfzR/" +
        "fabric-api-0.92.12%2B1.20.1.jar"
    val apiSha1 = "3e9cdd3e2f827ca9a259df9eb8e31949437b6bd4"
    val apiSize = 2137232L
    val apiName = "fabric-api-0.92.12+1.20.1.jar"
    val index = """
        {
          "formatVersion": 1,
          "game": "minecraft",
          "versionId": "1.0.0",
          "name": "鸭嘴兽导入验收包",
          "summary": "两个模组（含依赖）+ 两层 overrides",
          "files": [
            {
              "path": "mods/appleskin-fabric-mc1.20.1-2.5.2.jar",
              "hashes": {
                "sha1": "$modSha1",
                "sha512": "712ca0f86050ab3c6dbdbe62c5d9fabe77837d70529d1cd686f915a5f09d3a4f71ad4d3866984fbdded63ae2eb1c1ceedfb3386253510ee3a54857f77677e4f4"
              },
              "env": { "client": "required", "server": "required" },
              "downloads": [ "$modUrl" ],
              "fileSize": $modSize
            },
            {
              "path": "mods/$apiName",
              "hashes": {
                "sha1": "$apiSha1",
                "sha512": "de9da2344043b3e9ef9fb7dbfff5f2577b06000c4f73f2a37959a80123141169084cece19133bd299b45feaaa4648bdaca2ea085684888cc8c55cc9134a477ca"
              },
              "env": { "client": "required", "server": "required" },
              "downloads": [ "$apiUrl" ],
              "fileSize": $apiSize
            }
          ],
          "dependencies": {
            "minecraft": "1.20.1",
            "fabric-loader": "0.20.1"
          }
        }
    """.trimIndent()

    ZipOutputStream(archive.outputStream().buffered()).use { zip ->
        fun put(name: String, text: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        put("modrinth.index.json", index)
        put("overrides/config/platypus-test.cfg", "base-layer=true" + System.lineSeparator())
        // 同一条路径在 client-overrides 里再放一份：按格式规定 client 层最后覆盖，
        // 装完读到的必须是这一份，否则说明层序错了。
        put("client-overrides/config/platypus-test.cfg", "client-layer-wins=true" + System.lineSeparator())
        put("server-overrides/config/should-not-exist.cfg", "server only" + System.lineSeparator())
    }
    say("archive=" + archive.absolutePath + " bytes=" + archive.length())

    // ── 2. 只读预览 ─────────────────────────────────────────────────────────
    val preview = try {
        runBlocking { ModpackImporter.inspect(archive) }
    } catch (e: ModpackImportException) {
        say("INSPECT FAILED at " + e.step.name + ": " + e.message)
        exitProcess(2)
    }
    say("INSPECT kind=" + preview.layout.kind + " name=" + preview.manifest.name +
        " game=" + preview.manifest.gameVersion + " loader=" + preview.manifest.loader +
        " loaderVersion=" + preview.manifest.loaderVersion +
        " files=" + preview.fileCount + " bytes=" + preview.totalDownloadBytes)
    say("INSPECT overrideDirs=" + preview.manifest.overrideDirs)
    preview.manifest.warnings.forEach { say("  warning: " + it) }

    var previewOk = true
    if (preview.layout.kind != ModpackKind.MRPACK) {
        say("断言失败：格式应为 MRPACK，实际 " + preview.layout.kind); previewOk = false
    }
    if (preview.manifest.gameVersion != "1.20.1") {
        say("断言失败：gameVersion 应为 1.20.1，实际 " + preview.manifest.gameVersion); previewOk = false
    }
    if (preview.fileCount != 2) {
        say("断言失败：fileCount 应为 2，实际 " + preview.fileCount); previewOk = false
    }
    if (preview.totalDownloadBytes != modSize + apiSize) {
        say("断言失败：totalDownloadBytes 应为 " + (modSize + apiSize) +
            "，实际 " + preview.totalDownloadBytes); previewOk = false
    }
    if (!previewOk) exitProcess(2)
    say("INSPECT OK")

    // ── 3. 导入 ─────────────────────────────────────────────────────────────
    // 与 FabricE2e 同理：关掉全局扫描，才能验到「自动补 Java」这条分支真的会跑。
    val javaService = DesktopJavaRuntimeService(gameRoot = gameDir, scanGlobalLaunchers = false)
    val repository: InstanceRepository =
        JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(gameDir))
    val startedAt = System.currentTimeMillis()
    var lastStep: ModpackStep? = null

    val outcome = try {
        runBlocking {
            ModpackImporter.import(
                archive = archive,
                gameDir = gameDir,
                javaRuntimes = javaService,
                instances = repository,
                memoryMb = Instance.DEFAULT_MEMORY_MB,
            ) { p ->
                if (p.step != lastStep) {
                    lastStep = p.step
                    say("STEP " + p.step.name + " " + p.step.label)
                }
                val pct = if (p.total > 0) " " + (p.fraction * 100).toInt() + "%" else ""
                say("  " + p.step.name + " " + p.detail + pct)
            }
        }
    } catch (e: ModpackImportException) {
        say("IMPORT FAILED at " + e.step.name + ": " + e.message)
        e.stackTrace.take(8).forEach { say("  at " + it) }
        exitProcess(3)
    } catch (e: Throwable) {
        say("IMPORT FAILED (unexpected): " + e)
        exitProcess(3)
    }

    val elapsed = System.currentTimeMillis() - startedAt
    say("IMPORT OK in " + elapsed + " ms")
    say("  instance id=" + outcome.instance.id + " name=" + outcome.instance.name +
        " gameVersion=" + outcome.instance.gameVersion +
        " loader=" + outcome.instance.loader + " loaderVersion=" + outcome.instance.loaderVersion +
        " memoryMb=" + outcome.instance.memoryMb)
    say("  instanceDir=" + outcome.instanceDir.absolutePath)
    say("  downloadedFiles=" + outcome.downloadedFiles + " bytes=" + outcome.downloadedBytes +
        " overrideFiles=" + outcome.overrideFiles)
    say("  java=" + outcome.install.java.path + " major=" + outcome.install.java.majorVersion +
        " javaInstalled=" + outcome.install.javaInstalled)
    outcome.warnings.forEach { say("  warning: " + it) }

    // ── 4. 产物校验 ─────────────────────────────────────────────────────────
    var productOk = true
    fun check(name: String, ok: Boolean, detail: String = "") {
        say((if (ok) "PASS " else "FAIL ") + name + (if (detail.isEmpty()) "" else " -> " + detail))
        if (!ok) productOk = false
    }

    val modFile = File(outcome.instanceDir, "mods/appleskin-fabric-mc1.20.1-2.5.2.jar")
    check("模组文件已落地", modFile.isFile, modFile.absolutePath)
    if (modFile.isFile) {
        check("模组体积对得上", modFile.length() == modSize, modFile.length().toString())
        check("模组 sha1 对得上", sha1Of(modFile) == modSha1, sha1Of(modFile))
    }
    // 第二个文件：验多文件包能一次下齐，且每个文件各自按自己的 sha1 校验。
    val apiFile = File(outcome.instanceDir, "mods/" + apiName)
    check("依赖模组已落地", apiFile.isFile, apiFile.absolutePath)
    if (apiFile.isFile) {
        check("依赖模组体积对得上", apiFile.length() == apiSize, apiFile.length().toString())
        check("依赖模组 sha1 对得上", sha1Of(apiFile) == apiSha1, sha1Of(apiFile))
    }
    check("包内文件数对得上", outcome.downloadedFiles == 2, outcome.downloadedFiles.toString())
    val clientLayer = File(outcome.instanceDir, "config/platypus-test.cfg")
    check("overrides 已解出", clientLayer.isFile, clientLayer.absolutePath)
    if (clientLayer.isFile) {
        val body = clientLayer.readText()
        check("client-overrides 覆盖了 overrides", body.contains("client-layer-wins"), body.trim())
    }
    check(
        "server-overrides 不该装进客户端",
        !File(outcome.instanceDir, "config/should-not-exist.cfg").exists(),
    )

    val registered: List<Instance> = runBlocking { repository.list() }
    say("REGISTERED count=" + registered.size)
    registered.forEach {
        say("  " + it.id + " loader=" + it.loader + " loaderVersion=" + it.loaderVersion +
            " memoryMb=" + it.memoryMb + " directory=" + it.directory)
    }
    check("实例已登记", registered.any { it.id == outcome.instance.id })
    if (!productOk) exitProcess(4)

    // ── 5. 启动 ─────────────────────────────────────────────────────────────
    val target = outcome.instance
    val useJava: JavaRuntime = outcome.install.java
    say("LAUNCH start")
    val launchAt = System.currentTimeMillis()
    var menuAt = 0L
    var exitCode: Int? = null
    var handle: GameHandle? = null
    val hits = mutableListOf<String>()

    runBlocking {
        withTimeoutOrNull(menuTimeoutMs) {
            val started = DesktopGameRuntime().launch(
                LaunchRequest(
                    instance = target,
                    java = useJava,
                    session = OfflineProfile.session("Dev"),
                ),
            ) { event ->
                when (event) {
                    is LaunchEvent.Stage -> say("  stage " + event.name)
                    is LaunchEvent.Started -> say("  started pid=" + event.pid)
                    is LaunchEvent.LogLine -> {
                        gameLog.appendText(event.text + System.lineSeparator())
                        val lower = event.text.lowercase()
                        if (hits.size < 20 &&
                            (lower.contains("fabric") || lower.contains("mixin") ||
                                lower.contains("appleskin") || lower.contains("knot"))
                        ) {
                            hits.add(event.text.take(300))
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
    say("LOADER LOG HITS (" + hits.size + ")")
    hits.forEach { say("  | " + it) }
    say("RESULT menuReached=" + (menuAt != 0L) + " launchMs=" + launchMs + " exitCode=" + exitCode)
    say("TOTAL ms=" + (System.currentTimeMillis() - startedAt))
    say("report=" + report.absolutePath)
    say("gameLog=" + gameLog.absolutePath)

    if (menuAt == 0L) {
        say("--- game log tail (60) ---")
        gameLog.takeIf { it.isFile }?.readLines()?.takeLast(60)?.forEach { say("  | " + it) }
        exitProcess(5)
    }
    exitProcess(0)
}

private fun sha1Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
