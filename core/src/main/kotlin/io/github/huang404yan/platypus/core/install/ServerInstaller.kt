package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadProgress
import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.loader.ForgeMeta
import io.github.huang404yan.platypus.core.loader.NeoForgeMeta
import io.github.huang404yan.platypus.core.loader.pickNeoForgeForMinecraft
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.version.VersionManifest
import io.github.huang404yan.platypus.core.version.parseVersionJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 下载服务端并把它变成「双击就能开」的样子。
 *
 * 支持五种：[ServerKind.VANILLA] 用 Mojang 版本 json 里的 downloads.server；[ServerKind.PAPER]
 * 拉 fill.papermc.io 的构建；[ServerKind.FABRIC] 用 meta.fabricmc.net 现成的 server jar；
 * [ServerKind.FORGE] 与 [ServerKind.NEOFORGE] 跑各自的 installer（--installServer）。
 *
 * 为什么还要自己写 eula.txt：Mojang 的服务端第一次启动时**不会**自动生成同意书，
 * 而是直接退出并打印「You need to agree to the EULA」。用户拿到一个 jar 打开就闪退，
 * 十有八九卡在这一步 —— 这正是「藏得深、无提示」那一类问题。
 */
object ServerInstaller {

    /** eula.txt 的默认内容。Mojang 自己写的注释保持原样，别改成中文——那份文件是给他们看的。 */
    const val EULA_HEADER: String =
        "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA)."

    /** 原版 / Paper / Fabric 的 jar 落地时的文件名。不用版本号命名：开服脚本里写死一个名字最省事。 */
    const val JAR_NAME: String = "server.jar"

    /** 开服时给 JVM 的默认内存。服务端比客户端吃内存，2 GB 是 1.20 时代的常识下限。 */
    const val DEFAULT_MEMORY_MB: Int = 2048

    /** 跑 Forge / NeoForge installer 的超时。实测服务端安装比客户端慢，给到 20 分钟。 */
    private const val INSTALLER_TIMEOUT_MINUTES: Long = 20L

    /**
     * 一次服务端安装。
     *
     * [startScript] 是双击就能开的脚本：原版 / Paper / Fabric 由我们生成 start.bat（start.sh），
     * Forge / NeoForge 则指向 installer 自己生成的 run.bat / run.sh —— 那两个脚本里带着一长串
     * 模块参数，我们自己拼一份只会拼错。
     *
     * [alreadyPresent] 为真表示这个目录里本来就有能用的服务端，这次没重新下载 ——
     * 重复点一次不该把人家跑着的服覆盖掉。
     */
    data class ServerInstallResult(
        val versionId: String,
        val dir: File,
        /** 单一 jar；Forge / NeoForge 没有这个（靠 run 脚本 + libraries），为 null。 */
        val jar: File?,
        val eulaFile: File,
        val startScript: File,
        val downloadedBytes: Long,
        val javaMajorVersion: Int,
        val alreadyPresent: Boolean = false,
        val kind: ServerKind = ServerKind.VANILLA,
        /** 实际装上的加载器版本；Paper 是构建号。 */
        val loaderVersion: String? = null,
        /** 额外说明（例如「Forge 自带 run.bat，改内存就改里面的 -Xmx」）。 */
        val note: String = "",
    ) {
        /** 给人看的一句话，界面直接拿去显示。 */
        val summary: String
            get() = when {
                note.isNotBlank() -> note
                alreadyPresent -> "这个目录里已经有能用的 " + kind.label + " 服务端了，没重复下载。"
                kind == ServerKind.VANILLA -> "服务端已就绪：" + JAR_NAME + "（" + versionId + "）。"
                else -> kind.label + " 服务端已就绪（" + versionId + "）。"
            }
    }

    /**
     * 装一个服务端到 [targetDir]。
     *
     * @param versionId 游戏版本号（1.20.1）。
     * @param loader 装哪种加载器；默认原版。
     * @param javaPath 跑 Forge / NeoForge 安装器用的 java；其它加载器不需要。
     */
    suspend fun install(
        targetDir: File,
        versionId: String,
        loader: ServerLoader = ServerLoader.Vanilla,
        manifest: VersionManifest? = null,
        memoryMb: Int = DEFAULT_MEMORY_MB,
        javaPath: String? = null,
        onProgress: (ServerInstallProgress) -> Unit = {},
    ): ServerInstallResult = withContext(Dispatchers.IO) {
        val requested = versionId.trim()
        if (requested.isEmpty()) throw IOException("版本号不能为空")
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            throw IOException("建不了目录：" + targetDir.absolutePath)
        }
        when (loader.kind) {
            ServerKind.VANILLA -> installVanilla(targetDir, requested, manifest, memoryMb, onProgress)
            ServerKind.PAPER -> installPaper(targetDir, requested, loader.loaderVersion, memoryMb, onProgress)
            ServerKind.FABRIC -> installFabric(targetDir, requested, loader.loaderVersion, memoryMb, onProgress)
            ServerKind.FORGE, ServerKind.NEOFORGE ->
                installWithInstaller(targetDir, requested, loader, memoryMb, javaPath, onProgress)
        }
    }

    // ---- 原版 ----

    /** 原版：Mojang 版本 json 的 downloads.server。 */
    private suspend fun installVanilla(
        targetDir: File,
        requested: String,
        manifest: VersionManifest?,
        memoryMb: Int,
        onProgress: (ServerInstallProgress) -> Unit,
    ): ServerInstallResult {
        onProgress(ServerInstallProgress("解析版本", "查询 " + requested + " 的版本元数据"))
        val text = VanillaInstaller.loadVersionJson(requested, manifest)
        val version = parseVersionJson(text)
        val resolvedId = version.id.ifBlank { requested }
        val server = version.downloads?.server
            ?: throw IOException(
                "Mojang 没有给 " + resolvedId + " 提供服务端 jar。1.2.5 之前的服务端要单独下载，" +
                    "这一版只做能从版本 json 里直接拿到的版本。",
            )
        if (server.url.isBlank()) throw IOException(resolvedId + " 的服务端地址是空的，Mojang 那边数据有问题。")

        val jar = File(targetDir, JAR_NAME)
        val usable = jar.isFile && jar.length() > 0L && matches(jar, server.sha1, server.size)
        var downloaded = 0L
        if (usable) {
            onProgress(ServerInstallProgress("检查文件", "本机已有可用的 " + JAR_NAME + "，跳过下载"))
        } else {
            downloaded = download(
                url = server.url,
                target = jar,
                sha1 = server.sha1,
                size = server.size,
                mirrors = VanillaInstaller.mirrorsFor(server.url),
                step = "下载服务端",
                detail = "下载 " + JAR_NAME,
                onProgress = onProgress,
            )
        }
        return finish(
            targetDir = targetDir,
            versionId = resolvedId,
            jar = jar,
            kind = ServerKind.VANILLA,
            loaderVersion = null,
            downloadedBytes = downloaded,
            javaMajorVersion = version.javaVersion?.majorVersion?.takeIf { it > 0 }
                ?: GameJavaRules.requiredMajor(resolvedId),
            alreadyPresent = usable,
            startScriptName = null,
            memoryMb = memoryMb,
            onProgress = onProgress,
        )
    }

    // ---- Paper ----

    /** Paper：fill.papermc.io 的构建列表，挑最新（或用户指定的构建号）。 */
    private suspend fun installPaper(
        targetDir: File,
        gameVersion: String,
        buildText: String?,
        memoryMb: Int,
        onProgress: (ServerInstallProgress) -> Unit,
    ): ServerInstallResult {
        onProgress(ServerInstallProgress("解析版本", "查询 Paper " + gameVersion + " 的构建列表"))
        val text = VanillaInstaller.fetchText(listOf(PaperMeta.buildsUrl(gameVersion)))
        val builds = PaperMeta.parseBuilds(text)
        if (builds.isEmpty()) {
            throw IOException(
                "Paper 没有 " + gameVersion + " 的构建：fill.papermc.io 返回了空列表。" +
                    "换个游戏版本，或者先用原版服务端。",
            )
        }
        val wanted = buildText?.trim()?.toIntOrNull()
        val build = if (wanted != null) {
            builds.firstOrNull { it.id == wanted }
                ?: throw IOException("Paper " + gameVersion + " 没有构建 " + wanted + "，最新是 " + PaperMeta.latest(builds)?.id + "。")
        } else {
            PaperMeta.latest(builds) ?: builds.first()
        }

        val jar = File(targetDir, JAR_NAME)
        val usable = jar.isFile && matchesSha256(jar, build.sha256)
        var downloaded = 0L
        if (usable) {
            onProgress(ServerInstallProgress("检查文件", "本机已有可用的 " + JAR_NAME + "，跳过下载"))
        } else {
            // Paper 只发 sha256，HttpDownloader 只认 sha1；这里按 size 下，下完自己算 sha256。
            downloaded = download(
                url = build.url,
                target = jar,
                sha1 = null,
                size = build.size,
                mirrors = emptyList(),
                step = "下载服务端",
                detail = "下载 " + build.fileName,
                onProgress = onProgress,
            )
            if (build.sha256.isNotBlank() && !matchesSha256(jar, build.sha256)) {
                jar.delete()
                throw IOException("Paper 服务端 sha256 对不上，已删掉重下：" + build.url)
            }
        }
        return finish(
            targetDir = targetDir,
            versionId = gameVersion,
            jar = jar,
            kind = ServerKind.PAPER,
            loaderVersion = build.id.toString(),
            downloadedBytes = downloaded,
            javaMajorVersion = GameJavaRules.requiredMajor(gameVersion),
            alreadyPresent = usable,
            startScriptName = null,
            memoryMb = memoryMb,
            note = "Paper " + build.label + " 已就绪（" + gameVersion + "）。",
            onProgress = onProgress,
        )
    }

    // ---- Fabric ----

    /** Fabric：meta 现成的 server jar，不用跑安装器。 */
    private suspend fun installFabric(
        targetDir: File,
        gameVersion: String,
        loaderText: String?,
        memoryMb: Int,
        onProgress: (ServerInstallProgress) -> Unit,
    ): ServerInstallResult {
        onProgress(ServerInstallProgress("解析版本", "查询 Fabric " + gameVersion + " 的 loader 列表"))
        val loaderVersion = if (!loaderText.isNullOrBlank()) {
            loaderText.trim()
        } else {
            val loaders = FabricInstaller.listLoaders(gameVersion)
            val latest = loaders.firstOrNull { it.stable } ?: loaders.first()
            latest.version
        }
        onProgress(ServerInstallProgress("解析版本", "查询 Fabric installer 版本"))
        val installerText = VanillaInstaller.fetchText(listOf(FabricServer.installerListUrl()))
        val installerVersion = FabricServer.parseInstallerVersion(installerText)
            ?: throw IOException("Fabric 的 installer 版本列表形状变了，解析不出来。")

        val url = FabricServer.serverJarUrl(gameVersion, loaderVersion, installerVersion)
        val jar = File(targetDir, JAR_NAME)
        // Fabric 的 server jar 没有公布校验值：只按「存在且非空」判可用，并在界面上说明。
        val usable = jar.isFile && jar.length() > 0L
        var downloaded = 0L
        if (usable) {
            onProgress(ServerInstallProgress("检查文件", "本机已有可用的 " + JAR_NAME + "，跳过下载"))
        } else {
            downloaded = download(
                url = url,
                target = jar,
                sha1 = null,
                size = -1L,
                mirrors = emptyList(),
                step = "下载服务端",
                detail = "下载 Fabric " + loaderVersion + " 服务端",
                onProgress = onProgress,
            )
        }
        return finish(
            targetDir = targetDir,
            versionId = gameVersion,
            jar = jar,
            kind = ServerKind.FABRIC,
            loaderVersion = loaderVersion,
            downloadedBytes = downloaded,
            javaMajorVersion = GameJavaRules.requiredMajor(gameVersion),
            alreadyPresent = usable,
            startScriptName = null,
            memoryMb = memoryMb,
            note = "Fabric " + loaderVersion + " 服务端已就绪（" + gameVersion + "）。",
            onProgress = onProgress,
        )
    }

    // ---- Forge / NeoForge ----

    /** Forge / NeoForge：下载 installer，跑 --installServer，交回它自己生成的 run 脚本。 */
    private suspend fun installWithInstaller(
        targetDir: File,
        gameVersion: String,
        loader: ServerLoader,
        memoryMb: Int,
        javaPath: String?,
        onProgress: (ServerInstallProgress) -> Unit,
    ): ServerInstallResult {
        val java = javaPath?.trim().orEmpty()
        if (java.isBlank()) {
            throw IOException(
                "装 " + loader.kind.label + " 服务端要用 Java 跑安装器：先选一个 Java，或者点「自动补 Java」。",
            )
        }
        val isForge = loader.kind == ServerKind.FORGE

        onProgress(ServerInstallProgress("解析版本", "查询 " + loader.kind.label + " 版本列表"))
        val chosen = if (!loader.loaderVersion.isNullOrBlank()) {
            loader.loaderVersion.trim()
        } else if (isForge) {
            val versions = ForgeInstaller.listVersions(gameVersion)
            (versions.firstOrNull { it.recommended } ?: versions.first()).version
        } else {
            val versions = NeoForgeInstaller.listVersions(gameVersion)
            (pickNeoForgeForMinecraft(versions, gameVersion) ?: versions.first()).version
        }

        val installerName = if (isForge) {
            ForgeMeta.installerFileName(gameVersion, chosen)
        } else {
            NeoForgeMeta.installerFileName(chosen)
        }
        val installerUrl = if (isForge) {
            ForgeMeta.installerUrl(gameVersion, chosen)
        } else {
            NeoForgeMeta.installerUrl(chosen)
        }

        // installer 放在目标目录旁边的一个子目录里：装完留着，重装同一版本不用再下 8 MB。
        val cacheDir = File(targetDir, ".platypus-installer")
        cacheDir.mkdirs()
        val installer = File(cacheDir, installerName)
        val installerBytes = if (installer.isFile && installer.length() > 0L) {
            0L
        } else {
            download(
                url = installerUrl,
                target = installer,
                sha1 = null,
                size = -1L,
                mirrors = emptyList(),
                step = "下载安装器",
                detail = "下载 " + installerName,
                onProgress = onProgress,
            )
        }

        val logFile = File(cacheDir, loader.kind.label.lowercase() + "-install.log")
        onProgress(
            ServerInstallProgress(
                "运行安装器",
                "跑 " + loader.kind.label + " installer（要几分钟，日志：" + logFile.name + "）",
            ),
        )
        val exitCode = InstallerProcess.run(
            command = listOf(
                java,
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-jar",
                installer.absolutePath,
                "--installServer",
                targetDir.absolutePath,
            ),
            workingDir = targetDir,
            logFile = logFile,
            timeoutMillis = TimeUnit.MINUTES.toMillis(INSTALLER_TIMEOUT_MINUTES),
        ) { line ->
            onProgress(ServerInstallProgress("运行安装器", "安装器：" + line))
        }
        if (exitCode != 0) {
            throw IOException(
                loader.kind.label + " 服务端安装器退出码 " + exitCode + "，日志在 " + logFile.absolutePath,
            )
        }

        // 1.17 起 installer 会生成 run.bat / run.sh；更老的版本只有 forge-<mc>-<ver>.jar。
        val isWindows = isWindows()
        val officialScript = File(targetDir, if (isWindows) "run.bat" else "run.sh")
        val serverJar = findInstalledServerJar(targetDir, gameVersion, chosen, isForge)
        if (!officialScript.isFile && serverJar == null) {
            throw IOException(
                "安装器跑完了，但既没有 run 脚本也没有服务端 jar，日志在 " + logFile.absolutePath,
            )
        }

        val scriptName = if (officialScript.isFile) {
            officialScript.name
        } else {
            if (isWindows) "start.bat" else "start.sh"
        }
        val note = if (officialScript.isFile) {
            loader.kind.label + " " + chosen + " 服务端已就绪（" + gameVersion + "）；开服脚本是安装器生成的 " +
                officialScript.name + "."
        } else {
            ""
        }
        return finish(
            targetDir = targetDir,
            versionId = gameVersion,
            jar = serverJar,
            kind = loader.kind,
            loaderVersion = chosen,
            downloadedBytes = installerBytes,
            javaMajorVersion = GameJavaRules.requiredMajor(gameVersion),
            alreadyPresent = false,
            startScriptName = if (officialScript.isFile) scriptName else null,
            jarNameForScript = serverJar?.name,
            memoryMb = memoryMb,
            note = note,
            onProgress = onProgress,
        )
    }

    /** 找 installer 装出来的服务端 jar：新版本在 libraries/ 下，老版本在目录根上。 */
    private fun findInstalledServerJar(
        targetDir: File,
        gameVersion: String,
        loaderVersion: String,
        isForge: Boolean,
    ): File? {
        val root = targetDir.listFiles().orEmpty()
        val marker = if (isForge) {
            "forge-" + gameVersion + "-" + loaderVersion
        } else {
            "neoforge-" + loaderVersion
        }
        root.firstOrNull { it.isFile && it.name.startsWith(marker) && it.name.endsWith(".jar") && !it.name.contains("installer") }
            ?.let { return it }
        return null
    }

    // ---- 收尾：eula + 开服脚本 ----

    /**
     * 写 eula.txt 与开服脚本，返回结果。
     *
     * [startScriptName] 非空表示那个脚本已经存在（Forge 的 run.bat），只用不写。
     * [jarNameForScript] 是我们自己生成脚本时要写的 jar 文件名。
     */
    private fun finish(
        targetDir: File,
        versionId: String,
        jar: File?,
        kind: ServerKind,
        loaderVersion: String?,
        downloadedBytes: Long,
        javaMajorVersion: Int,
        alreadyPresent: Boolean,
        startScriptName: String?,
        memoryMb: Int,
        onProgress: (ServerInstallProgress) -> Unit,
        note: String = "",
        jarNameForScript: String? = null,
    ): ServerInstallResult {
        onProgress(ServerInstallProgress("写 eula.txt", "同意 Minecraft 最终用户许可协议", 1, 1))
        val eula = File(targetDir, "eula.txt")
        // 已经写过 eula=true 就不动它：用户可能在里面加了自己的备注
        val agreed = eula.isFile && runCatching { eulaAgreed(eula.readText(Charsets.UTF_8)) }.getOrDefault(false)
        if (!agreed) {
            eula.writeText(eulaText(), Charsets.UTF_8)
        }

        onProgress(ServerInstallProgress("写开服脚本", "生成双击就能开的脚本"))
        val windows = isWindows()
        val scriptName = startScriptName ?: if (windows) "start.bat" else "start.sh"
        val script = File(targetDir, scriptName)
        // 已经存在就不覆盖：用户多半在里面加过自己的 -Xmx。Forge 的 run.bat 更不能碰。
        if (startScriptName == null && !script.isFile) {
            val jarName = jarNameForScript ?: jar?.name ?: JAR_NAME
            script.writeText(startScriptText(windows, memoryMb, jarName), Charsets.UTF_8)
            if (!windows) {
                runCatching { script.setExecutable(true) }
            }
        }

        onProgress(ServerInstallProgress("完成", "服务端已就绪", 1, 1))
        return ServerInstallResult(
            versionId = versionId,
            dir = targetDir,
            jar = jar,
            eulaFile = eula,
            startScript = script,
            downloadedBytes = downloadedBytes,
            javaMajorVersion = javaMajorVersion,
            alreadyPresent = alreadyPresent,
            kind = kind,
            loaderVersion = loaderVersion,
            note = note,
        )
    }

    // ---- 下载 ----

    /** 下单个文件并报进度；返回真正落盘的字节数。失败抛 [IOException]。 */
    private suspend fun download(
        url: String,
        target: File,
        sha1: String?,
        size: Long,
        mirrors: List<String>,
        step: String,
        detail: String,
        onProgress: (ServerInstallProgress) -> Unit,
    ): Long {
        onProgress(ServerInstallProgress(step, detail, size))
        val item = DownloadItem(
            url = url,
            target = target.absolutePath,
            sha1 = sha1,
            size = size,
            mirrors = mirrors,
        )
        val report: DownloadReport = HttpDownloader().download(listOf(item)) { p: DownloadProgress ->
            onProgress(
                ServerInstallProgress(
                    step,
                    detail,
                    total = if (p.totalBytes > 0) p.totalBytes else size,
                    done = p.completedBytes,
                ),
            )
        }
        if (!report.ok) {
            throw IOException(step + "失败：" + (report.failures.firstOrNull()?.reason ?: "未知原因"))
        }
        return if (target.isFile) target.length() else 0L
    }

    /** eula.txt 的内容。末尾一个换行，跟 Mojang 自己写出来的那份一致。 */
    fun eulaText(): String = EULA_HEADER + "\n" + "eula=true" + "\n"

    /**
     * 这份 eula.txt 是不是已经同意了。
     *
     * 认 eula=true / eula = true / EULA=TRUE 这几种写法（服务端自己就是这么宽容的），
     * 因为用户可能手改过，也可能被别的启动器写过。注释行不算。
     */
    fun eulaAgreed(text: String): Boolean = text.lineSequence().any { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) return@any false
        val eq = trimmed.indexOf('=')
        if (eq <= 0) return@any false
        trimmed.substring(0, eq).trim().equals("eula", ignoreCase = true) &&
            trimmed.substring(eq + 1).trim().equals("true", ignoreCase = true)
    }

    /**
     * 开服脚本。
     *
     * 三个细节：
     * 1. Windows 用 cd /d %~dp0，sh 用 cd 到脚本所在目录 —— 服务端会把存档写在工作目录，
     *    双击时工作目录不一定对，错了就找不回来。
     * 2. .bat 的行尾必须是 CRLF，所以这里显式拼 \r\n。
     * 3. nogui 不是可选项：双击 bat 开服时让控制台留在窗口里，别再弹一个图形界面。
     *
     * [jarName] 默认 server.jar；Forge 老版本生成的 jar 名字不一样，由调用方传进来。
     */
    fun startScriptText(isWindows: Boolean, memoryMb: Int, jarName: String = JAR_NAME): String {
        val memory = memoryMb.coerceIn(512, 32768)
        val minMemory = (memory / 2).coerceAtLeast(512)
        val command = "java -Xms" + minMemory + "M -Xmx" + memory + "M -jar " + jarName + " nogui"
        return if (isWindows) {
            listOf(
                "@echo off",
                "chcp 65001 >nul",
                "cd /d %~dp0",
                "title Minecraft Server",
                command,
                "echo.",
                "echo Minecraft 服务端已停止，按任意键关闭窗口。",
                "pause >nul",
            ).joinToString("\r\n") + "\r\n"
        } else {
            listOf(
                "#!/bin/sh",
                "# 由鸭嘴兽启动器生成：改内存就改下面这一行的 -Xmx。",
                "cd \"$(dirname \"$0\")\"",
                "exec " + command,
            ).joinToString("\n") + "\n"
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    /** 老 jar 的 sha1/size 跟 JSON 里记的对不上就当它不能用，重新下。 */
    private fun matches(file: File, sha1: String?, size: Long): Boolean {
        if (size > 0 && file.length() != size) return false
        if (sha1.isNullOrBlank()) return false
        return runCatching { sha1Of(file).equals(sha1, ignoreCase = true) }.getOrDefault(false)
    }

    /** Paper 只给 sha256；空校验值一律当作不通过（宁可重下也不带着坏包开服）。 */
    private fun matchesSha256(file: File, expected: String): Boolean {
        if (expected.isBlank() || !file.isFile) return false
        return runCatching { sha256Of(file).equals(expected, ignoreCase = true) }.getOrDefault(false)
    }

    private fun sha1Of(file: File): String = digestOf(file, "SHA-1")

    private fun sha256Of(file: File): String = digestOf(file, "SHA-256")

    private fun digestOf(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 服务端安装进度：步骤名 + 细节 + 可选的字节进度。 */
data class ServerInstallProgress(
    val step: String,
    val detail: String,
    val total: Long = 0L,
    val done: Long = 0L,
) {
    val fraction: Float
        get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}
