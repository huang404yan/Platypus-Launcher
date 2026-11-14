package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadProgress
import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.version.VersionManifest
import io.github.huang404yan.platypus.core.version.parseVersionJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 下载原版服务端（Mojang 的 `downloads.server`）并把它变成「双击就能开」的样子。
 *
 * 只做原版，不做 Forge/NeoForge/Fabric/Paper 服务端：那些要么要跑安装器再换主类，
 * 要么要另外一套下载源（Paper 的 API、Fabric 的 server launcher），一锅端进来只会让
 * 这一版既不可靠也说不清。界面上直接写「只有原版」。
 *
 * 为什么还要自己写 eula.txt：Mojang 的服务端第一次启动时**不会**自动生成同意书，
 * 而是直接退出并打印「You need to agree to the EULA」。用户拿到一个 jar 打开就闪退，
 * 十有八九卡在这一步 —— 这正是「藏得深、无提示」那一类问题。
 */
object ServerInstaller {

    /** eula.txt 的默认内容。Mojang 自己写的注释保持原样，别改成中文——那份文件是给他们看的。 */
    const val EULA_HEADER: String =
        "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA)."

    /** 服务端 jar 落地时的文件名。不用版本号命名：开服脚本里写死一个名字最省事，也方便换版本。 */
    const val JAR_NAME: String = "server.jar"

    /** 开服时给 JVM 的默认内存。服务端比客户端吃内存，2 GB 是 1.20 时代的常识下限。 */
    const val DEFAULT_MEMORY_MB: Int = 2048

    /**
     * 一次服务端安装。
     *
     * [startScript] 在 Windows 上是 `start.bat`，其它平台是 `start.sh`（会尽力加上可执行位）。
     * [alreadyPresent] 为真表示这个目录里本来就有能用的 server.jar，这次没重新下载 ——
     * 重复点一次不该把人家跑着的服覆盖掉。
     */
    data class ServerInstallResult(
        val versionId: String,
        val dir: File,
        val jar: File,
        val eulaFile: File,
        val startScript: File,
        val downloadedBytes: Long,
        val javaMajorVersion: Int,
        val alreadyPresent: Boolean = false,
    ) {
        /** 给人看的一句话，界面直接拿去显示。 */
        val summary: String
            get() = if (alreadyPresent) {
                "这个目录里已经有 " + JAR_NAME + " 了，没重复下载。"
            } else {
                "服务端已就绪：" + JAR_NAME + "（" + versionId + "）。"
            }
    }

    /**
     * 装一个原版服务端到 [targetDir]。
     *
     * 顺序：拉版本 json → 取 downloads.server → 下载（sha1 校验、断点续传走 [HttpDownloader]）
     * → 写 eula.txt（已存在且已同意就不动）→ 写开服脚本。
     *
     * 版本没有 `downloads.server` 时抛 [IOException]：极老的版本服务端是另一个独立包，
     * 不是从 version json 里来的，这一版不猜。
     */
    suspend fun install(
        targetDir: File,
        versionId: String,
        manifest: VersionManifest? = null,
        memoryMb: Int = DEFAULT_MEMORY_MB,
        onProgress: (ServerInstallProgress) -> Unit = {},
    ): ServerInstallResult = withContext(Dispatchers.IO) {
        val requested = versionId.trim()
        if (requested.isEmpty()) throw IOException("版本号不能为空")
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            throw IOException("建不了目录：" + targetDir.absolutePath)
        }

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
        val expectedSha1 = server.sha1
        val expectedSize = server.size
        val usable = jar.isFile && jar.length() > 0L && matches(jar, expectedSha1, expectedSize)

        var downloaded = 0L
        if (usable) {
            onProgress(ServerInstallProgress("检查文件", "本机已有可用的 " + JAR_NAME + "，跳过下载"))
        } else {
            onProgress(ServerInstallProgress("下载服务端", "下载 " + JAR_NAME, expectedSize))
            val item = DownloadItem(
                url = server.url,
                target = jar.absolutePath,
                sha1 = expectedSha1,
                size = expectedSize,
                mirrors = VanillaInstaller.mirrorsFor(server.url),
            )
            val report: DownloadReport = HttpDownloader().download(listOf(item)) { p: DownloadProgress ->
                onProgress(
                    ServerInstallProgress(
                        "下载服务端",
                        "下载 " + JAR_NAME,
                        total = if (p.totalBytes > 0) p.totalBytes else expectedSize,
                        done = p.completedBytes,
                    ),
                )
            }
            if (!report.ok) {
                throw IOException("下载服务端失败：" + (report.failures.firstOrNull()?.reason ?: "未知原因"))
            }
            downloaded = jar.length()
        }

        onProgress(ServerInstallProgress("写 eula.txt", "同意 Minecraft 最终用户许可协议", 1, 1))
        val eula = File(targetDir, "eula.txt")
        // 已经写过 eula=true 就不动它：用户可能在里面加了自己的备注
        val alreadyAgreed = eula.isFile && runCatching { eulaAgreed(eula.readText(Charsets.UTF_8)) }.getOrDefault(false)
        if (!alreadyAgreed) {
            eula.writeText(eulaText(), Charsets.UTF_8)
        }

        onProgress(ServerInstallProgress("写开服脚本", "生成双击就能开的脚本"))
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("windows")
        val scriptName = if (isWindows) "start.bat" else "start.sh"
        val script = File(targetDir, scriptName)
        // 已经存在就不覆盖：用户多半在里面加过自己的 -Xmx
        if (!script.isFile) {
            script.writeText(startScriptText(isWindows, memoryMb), Charsets.UTF_8)
            if (!isWindows) {
                runCatching { script.setExecutable(true) }
            }
        }

        onProgress(ServerInstallProgress("完成", "服务端已就绪", 1, 1))
        ServerInstallResult(
            versionId = resolvedId,
            dir = targetDir,
            jar = jar,
            eulaFile = eula,
            startScript = script,
            downloadedBytes = downloaded,
            javaMajorVersion = version.javaVersion?.majorVersion?.takeIf { it > 0 }
                ?: GameJavaRules.requiredMajor(resolvedId),
            alreadyPresent = usable,
        )
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
     */
    fun startScriptText(isWindows: Boolean, memoryMb: Int): String {
        val memory = memoryMb.coerceIn(512, 32768)
        val minMemory = (memory / 2).coerceAtLeast(512)
        val command = "java -Xms" + minMemory + "M -Xmx" + memory + "M -jar " + JAR_NAME + " nogui"
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

    /** 老 jar 的 sha1/size 跟 JSON 里记的对不上就当它不能用，重新下。 */
    private fun matches(file: File, sha1: String?, size: Long): Boolean {
        if (size > 0 && file.length() != size) return false
        if (sha1.isNullOrBlank()) return false
        return runCatching { sha1Of(file).equals(sha1, ignoreCase = true) }.getOrDefault(false)
    }

    private fun sha1Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
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
