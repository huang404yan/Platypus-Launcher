package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.install.ModpackImportException
import io.github.huang404yan.platypus.core.install.ModpackImporter
import io.github.huang404yan.platypus.core.install.ModpackStep
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.version.parseVersionJson
import io.github.huang404yan.platypus.desktop.runtime.DesktopJavaRuntimeService
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * 无头验收：同一个（游戏版本, 加载器）连续导入两个整合包，必须落成两条互不干扰的实例。
 *
 * 以前的行为是把第二个包装进同一个版本目录：两个包的 mods/ 挤在一起，表现为
 * 「导入完只剩一条实例」或「后导入的把前面的覆盖了」。现在期望 ——
 *
 *  1. 第一次导入落在 versions/1.20.1-fabric-<loader>/；
 *  2. 第二次导入复制出 versions/1.20.1-fabric-<loader>_2/，并登记成第二条实例；
 *  3. 两条实例的 directory 不同，各自 mods/ 里只有自己那份文件；
 *  4. 副本目录的 json 里 id 字段已改写成新目录名（不改的话启动器会去找老目录）。
 *
 * 这里不启动游戏：要验的是「导入不覆盖」，启动链路另有 modpackE2e 盯着。
 *
 * 参数：<游戏目录>（不存在就建）  [等待时间无关参数一律不带]
 *
 * 退出码：0 通过；2 造包失败；3 导入失败；4 断言失败。
 */
fun main(args: Array<String>) {
    val gameDir = File(args.getOrElse(0) { "dist/_dup-import-smoke" }).absoluteFile
    val packDir = File(gameDir.parentFile, "_dup-import-packs").absoluteFile

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val logDir = File("dist/_e2e-logs").apply { mkdirs() }
    val report = File(logDir, "dup-import-smoke-" + stamp + ".log")

    fun say(line: String) {
        val text = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] " + line
        println(text)
        report.appendText(text + System.lineSeparator())
    }

    say("gameDir=" + gameDir.absolutePath)
    say("packDir=" + packDir.absolutePath)
    gameDir.mkdirs()
    packDir.mkdirs()

    // ── 造两个包：内置文件各不相同，装完一眼就能看出有没有串 ──
    // 两个包的游戏版本与 loader 版本完全一样 —— 这正是「会撞同一个版本目录」的触发条件。
    val loaderVersion = args.getOrElse(1) { "0.15.11" }
    val appleUrl = "https://cdn.modrinth.com/data/EsAfCjCV/versions/N5XeV21r/" +
        "appleskin-fabric-mc1.20.1-2.5.2.jar"
    val appleSha1 = "f859bb13130788ae2dc2e76a9c80c89e4bbbef5f"
    val appleSize = 1097240L
    val apiUrl = "https://cdn.modrinth.com/data/P7dR8mSH/versions/rvI2dfzR/" +
        "fabric-api-0.92.12%2B1.20.1.jar"
    val apiSha1 = "3e9cdd3e2f827ca9a259df9eb8e31949437b6bd4"
    val apiSize = 2137232L
    val apiName = "fabric-api-0.92.12+1.20.1.jar"

    fun indexOf(name: String, path: String, url: String, sha1: String, size: Long): String =
        """
        {
          "formatVersion": 1,
          "game": "minecraft",
          "versionId": "1.0.0",
          "name": "@NAME@",
          "summary": "鸭嘴兽重复导入验收",
          "files": [
            {
              "path": "@PATH@",
              "hashes": { "sha1": "@SHA1@" },
              "env": { "client": "required", "server": "required" },
              "downloads": [ "@URL@" ],
              "fileSize": @SIZE@
            }
          ],
          "dependencies": {
            "minecraft": "1.20.1",
            "fabric-loader": "@LOADER@"
          }
        }
        """.trimIndent()
            .replace("@NAME@", name)
            .replace("@PATH@", path)
            .replace("@SHA1@", sha1)
            .replace("@SIZE@", size.toString())
            .replace("@URL@", url)
            .replace("@LOADER@", loaderVersion)

    fun writePack(target: File, index: String) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("modrinth.index.json"))
            zip.write(index.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    val packA = File(packDir, "dup-pack-a.mrpack")
    val packB = File(packDir, "dup-pack-b.mrpack")
    try {
        writePack(
            packA,
            indexOf("鸭嘴兽验收包 A", "mods/appleskin-fabric-mc1.20.1-2.5.2.jar", appleUrl, appleSha1, appleSize),
        )
        writePack(
            packB,
            indexOf("鸭嘴兽验收包 B", "mods/" + apiName, apiUrl, apiSha1, apiSize),
        )
    } catch (e: Throwable) {
        say("PACK FAILED: " + e)
        exitProcess(2)
    }
    say("packA=" + packA.absolutePath + " bytes=" + packA.length())
    say("packB=" + packB.absolutePath + " bytes=" + packB.length())

    // 关掉全局扫描，跟 FabricE2e / ModpackE2e 一个理由：验的是「自动补 Java」那条分支。
    val javaService = DesktopJavaRuntimeService(gameRoot = gameDir, scanGlobalLaunchers = false)
    val repository: InstanceRepository =
        JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(gameDir))

    var lastStep: ModpackStep? = null
    fun importOnce(archive: File, label: String): io.github.huang404yan.platypus.core.install.ModpackImportOutcome {
        say("── IMPORT " + label + " " + archive.name)
        lastStep = null
        return try {
            kotlinx.coroutines.runBlocking {
                ModpackImporter.import(
                    archive = archive,
                    gameDir = gameDir,
                    javaRuntimes = javaService,
                    instances = repository,
                    memoryMb = Instance.DEFAULT_MEMORY_MB,
                ) { p ->
                    if (p.step != lastStep) {
                        lastStep = p.step
                        say("  STEP " + p.step.name + " " + p.step.label)
                    }
                    say("    " + p.step.name + " " + p.detail)
                }
            }
        } catch (e: ModpackImportException) {
            say("IMPORT FAILED at " + e.step.name + ": " + e.message)
            exitProcess(3)
        } catch (e: Throwable) {
            say("IMPORT FAILED (unexpected): " + e)
            e.stackTrace.take(8).forEach { say("  at " + it) }
            exitProcess(3)
        }
    }

    val first = importOnce(packA, "A")
    val second = importOnce(packB, "B")

    var ok = true
    fun check(name: String, pass: Boolean, detail: String = "") {
        say((if (pass) "PASS " else "FAIL ") + name + (if (detail.isEmpty()) "" else " -> " + detail))
        if (!pass) ok = false
    }

    // ── 断言 ──
    val firstId = first.instance.id
    val secondId = second.instance.id
    val firstDir = File(first.instance.directory)
    val secondDir = File(second.instance.directory)
    say("first  id=" + firstId + " dir=" + firstDir.absolutePath)
    say("second id=" + secondId + " dir=" + secondDir.absolutePath)

    check("第一次导入没有加后缀", !secondId.isBlank() && firstId == firstId.trim(), firstId)
    check("第二次导入换了 id", secondId != firstId, secondId)
    check("第二个 id 带 _2 后缀", secondId.endsWith("_2"), secondId)
    check("两次导入目录不同", firstDir.absolutePath != secondDir.absolutePath)
    check("第二个目录存在", secondDir.isDirectory, secondDir.absolutePath)
    check("第一个目录仍在", firstDir.isDirectory, firstDir.absolutePath)
    check("两条实例都登记了", runBlockingList(repository).size >= 2, runBlockingList(repository).size.toString())
    check("实例 id 与目录名一致", secondDir.name == secondId, secondDir.name + " vs " + secondId)

    // mods 各归各的：A 里只有 appleskin，B 里只有 fabric-api
    val appleInFirst = File(firstDir, "mods/appleskin-fabric-mc1.20.1-2.5.2.jar")
    val apiInSecond = File(secondDir, "mods/" + apiName)
    check("A 的模组在 A 目录", appleInFirst.isFile, appleInFirst.absolutePath)
    check("B 的模组在 B 目录", apiInSecond.isFile, apiInSecond.absolutePath)
    check("A 目录里没有 B 的模组", !File(firstDir, "mods/" + apiName).exists())
    check("B 目录里没有 A 的模组", !File(secondDir, "mods/appleskin-fabric-mc1.20.1-2.5.2.jar").exists())
    if (appleInFirst.isFile) {
        check("A 的模组体积没变", appleInFirst.length() == appleSize, appleInFirst.length().toString())
    }
    if (apiInSecond.isFile) {
        check("B 的模组体积对得上", apiInSecond.length() == apiSize, apiInSecond.length().toString())
    }

    // 副本的版本壳：json 被改写 id、jar 跟着改名，且能独立解析出加载器
    val copyJson = File(secondDir, secondId + ".json")
    check("副本目录有 json", copyJson.isFile, copyJson.absolutePath)
    if (copyJson.isFile) {
        val parsed = runCatching { parseVersionJson(copyJson.readText(Charsets.UTF_8)) }.getOrNull()
        check("副本 json 能解析", parsed != null)
        check("副本 json 的 id 已改写", parsed?.id == secondId, parsed?.id ?: "null")
        check("副本 json 仍继承原版", parsed?.inheritsFrom == "1.20.1", parsed?.inheritsFrom ?: "null")
    }
    // 客户端 jar 不一定在实例目录里：Fabric/Quilt 的版本 json 是 inheritsFrom 型，
    // jar 落在父版本 versions/1.20.1/1.20.1.jar 上。真正要保证的是「副本还能解析出 jar」，
    // 所以拿 VersionResolver 走一遍，而不是直接看文件在不在。
    val resolved = io.github.huang404yan.platypus.core.launch.VersionResolver.load(gameDir, secondId)
    check("副本能解析成版本", resolved != null, secondId)
    check("副本能定位到客户端 jar", resolved?.clientJar?.isFile == true, resolved?.clientJar?.absolutePath ?: "null")
    check("副本没带 A 的旧 json", !File(secondDir, firstId + ".json").exists())

    val firstInstance = runBlockingList(repository).firstOrNull { it.id == firstId }
    val secondInstance = runBlockingList(repository).firstOrNull { it.id == secondId }
    check("A 实例仍是 FABRIC", firstInstance?.loader == LoaderKind.FABRIC, firstInstance?.loader?.name ?: "null")
    check("B 实例仍是 FABRIC", secondInstance?.loader == LoaderKind.FABRIC, secondInstance?.loader?.name ?: "null")
    check("A 的加载器版本认得出来", !firstInstance?.loaderVersion.isNullOrBlank(), firstInstance?.loaderVersion ?: "null")
    check("B 的加载器版本认得出来", !secondInstance?.loaderVersion.isNullOrBlank(), secondInstance?.loaderVersion ?: "null")
    check("两个实例名字不一样", firstInstance?.name != secondInstance?.name, (firstInstance?.name ?: "?") + " / " + (secondInstance?.name ?: "?"))

    say("RESULT ok=" + ok)
    say("report=" + report.absolutePath)
    if (!ok) {
        say("--- instances.json ---")
        JsonInstanceRepository.defaultIndexFile(gameDir).takeIf { it.isFile }
            ?.readLines()?.forEach { say("  | " + it) }
        exitProcess(4)
    }
    exitProcess(0)
}

private fun runBlockingList(repository: InstanceRepository): List<Instance> =
    kotlinx.coroutines.runBlocking { repository.list() }
