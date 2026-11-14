package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.mods.ModDependencyResolver
import io.github.huang404yan.platypus.core.mods.ModEntry
import io.github.huang404yan.platypus.core.mods.ModScanner
import io.github.huang404yan.platypus.core.mods.ModrinthDependencySource
import io.github.huang404yan.platypus.core.mods.ModrinthMeta
import io.github.huang404yan.platypus.core.mods.ModInstallProgress
import io.github.huang404yan.platypus.core.mods.ModInstaller
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 无头验收：Modrinth 搜索 → 真下一个模组进 mods/ → 再扫一遍确认读得出元数据。
 *
 *   <游戏目录> [模组名=sodium] [游戏版本=1.20.1] [加载器=fabric] [等毫秒=60000]
 *
 * 退出码：0 成功 / 2 搜索没结果 / 3 下载或安装失败 / 4 装完扫描不到或元数据读不出。
 */
fun main(args: Array<String>): Unit = runBlocking {
    val gameDir = File(args.getOrNull(0) ?: error("缺少游戏目录参数"))
        .let { if (it.isAbsolute) it else File(System.getProperty("user.dir"), it.path) }
    val query = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "sodium"
    val gameVersion = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "1.20.1"
    val loader = parseLoader(args.getOrNull(3))
    val timeoutMs = args.getOrNull(4)?.toLongOrNull() ?: 60_000L

    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
    val logDir = File(System.getProperty("user.dir"), "dist/_e2e-logs").apply { mkdirs() }
    val log = File(logDir, "mods-e2e-" + query + "-" + stamp + ".log")
    var logHandle: java.io.PrintWriter? = null

    fun say(line: String) {
        val text = "[" + DateTimeFormatter.ofPattern("HH:mm:ss.SSS").format(LocalDateTime.now()) + "] " + line
        println(text)
        logHandle?.println(text)
        logHandle?.flush()
    }

    try {
        logHandle = java.io.PrintWriter(log.writer(Charsets.UTF_8), true)
        say("MODS E2E start; query=" + query + " gv=" + gameVersion + " loader=" + loader +
            " gameDir=" + gameDir.absolutePath)

        gameDir.mkdirs()
        val started = System.currentTimeMillis()

        say("SEARCH " + query)
        val projects = withTimeoutOrNull(timeoutMs) {
            ModInstaller.search(query, gameVersion, loader, limit = 5)
        }
        if (projects == null) {
            say("RESULT timeout while searching")
            logHandle.close()
            kotlin.system.exitProcess(3)
        }
        if (projects.isEmpty()) {
            say("RESULT no search results for " + query)
            logHandle.close()
            kotlin.system.exitProcess(2)
        }
        projects.take(5).forEachIndexed { index, project ->
            say("  [" + (index + 1) + "] " + project.slug + " / " + project.title +
                " / " + ModrinthMeta.formatCount(project.downloads) + " 次下载")
        }

        val target = projects.first()
        say("INSTALL " + target.slug)

        val progressLog = mutableListOf<String>()
        val result = withTimeoutOrNull(timeoutMs) {
            ModInstaller.install(
                gameDir = gameDir,
                slug = target.slug,
                gameVersion = gameVersion,
                loader = loader,
            ) { p: ModInstallProgress ->
                val line = p.step.label + " " + p.detail
                if (progressLog.lastOrNull() != line) {
                    progressLog.add(line)
                    say("  " + line)
                }
            }
        }
        if (result == null) {
            say("RESULT timeout while installing")
            logHandle.close()
            kotlin.system.exitProcess(3)
        }

        say("INSTALL ED " + result.file.name + " bytes=" + result.downloadedBytes +
            " version=" + result.version + " sha1Checked=" + (result.entry.problem == null))
        if (result.requiredDependencies.isNotEmpty()) {
            say("  required dependencies not installed: " + result.requiredDependencies.joinToString(", "))
        }
        // 可选依赖：只提示、不排进安装计划。这里顺手把求解层也跑一遍，
        // 用真实 Modrinth 数据证明「作者标了 optional 的 project_id」能被翻成标题+版本。
        if (result.optionalDependencies.isNotEmpty()) {
            say("  optional dependencies not installed: " + result.optionalDependencies.joinToString(", "))
            val source = ModrinthDependencySource(gameVersion, loader)
            val modsDir = ModScanner.modsDir(gameDir)
            val installed = ModDependencyResolver.installedProjectIds(modsDir)
            val suggested = withTimeoutOrNull(timeoutMs) {
                ModDependencyResolver.suggestOptional(
                    rootTitle = result.entry.title,
                    projectIds = result.optionalDependencies,
                    source = source,
                    installedProjectIds = installed,
                ) { step -> say("  " + step) }
            }
            if (suggested == null) {
                say("  OPTIONAL timeout")
            } else {
                say("  OPTIONAL resolved=" + suggested.size)
                suggested.forEach { dep ->
                    say("    · " + dep.title + " " + dep.file.versionNumber + " —— " + dep.suggestedBy + " 建议一起装")
                }
            }
        }

        val scan = ModScanner.scan(gameDir)
        say("SCAN exists=" + scan.exists + " enabled=" + scan.mods.size + " disabled=" + scan.disabled.size +
            " warnings=" + scan.warnings.size)
        (scan.mods + scan.disabled).forEach { entry: ModEntry ->
            say("  " + entry.fileName + " / id=" + (entry.modId ?: "-") + " / name=" + entry.title +
                " / version=" + (entry.version ?: "-") + " / loader=" + (entry.loader?.name ?: "-") +
                " / enabled=" + entry.enabled + " / problem=" + (entry.problem ?: "-"))
        }

        val matched = (scan.mods + scan.disabled).any { it.fileName == result.file.name }
        val elapsed = System.currentTimeMillis() - started
        say("RESULT installed=" + matched + " elapsedMs=" + elapsed + " log=" + log.name)
        logHandle.close()
        kotlin.system.exitProcess(if (matched) 0 else 4)
    } catch (e: Throwable) {
        say("MODS E2E FAILED: " + e)
        e.printStackTrace()
        logHandle?.close()
        kotlin.system.exitProcess(3)
    }
}

private fun parseLoader(raw: String?): LoaderKind = when (raw?.trim()?.lowercase()) {
    null, "" -> LoaderKind.FABRIC
    "fabric" -> LoaderKind.FABRIC
    "forge" -> LoaderKind.FORGE
    "neoforge" -> LoaderKind.NEOFORGE
    "quilt" -> LoaderKind.QUILT
    "vanilla" -> LoaderKind.VANILLA
    else -> error("不认识的加载器：" + raw)
}
