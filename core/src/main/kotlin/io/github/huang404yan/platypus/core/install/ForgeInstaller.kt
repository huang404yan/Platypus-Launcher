package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.loader.ForgeMeta
import io.github.huang404yan.platypus.core.loader.ForgeParse
import io.github.huang404yan.platypus.core.loader.ForgeVersion
import io.github.huang404yan.platypus.core.loader.parseForgePromotions
import io.github.huang404yan.platypus.core.version.DownloadPlanner
import io.github.huang404yan.platypus.core.version.VersionJson
import io.github.huang404yan.platypus.core.version.parseVersionJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Forge 安装：拉推荐版本 → 下官方 installer → 跑 `--installClient` → 校验产物。
 *
 * 为什么不像 Fabric 那样自己拼版本 json：Forge 的客户端 jar 要依次经过
 * jarsplitter（拆 slim/extra）、ForgeAutoRenamingTool（改名到 srg）、binarypatcher
 * （打 client.lzma 补丁）三道 processor，产物还要落到 libraries/net/minecraft/client/...
 * 手写这套链不仅长，还会随 Forge 版本变。官方 installer 就是干这件事的，直接调它最稳。
 *
 * 代价是起一个 Java 子进程（约 3 分钟、100 MB）。所以中途的进度只能靠 installer
 * 自己打的日志行——把它原样显示给用户，比编一个假进度条诚实。
 *
 * 注意：Forge 的 install_profile.json 里作者写了「Please do not automate the download
 * and installation of Forge.」并要求去 patreon 支持。这里保留官方安装器原样、不加镜像、
 * 不改其行为，界面上也写了「安装器来自 Forge 官方」的说明。
 */
object ForgeInstaller {

    /** 跑 installer 的总超时。实测 1.20.1 约 3 分钟，给到 15 分钟容忍慢网。 */
    private const val INSTALL_TIMEOUT_MINUTES = 15L

    /** 只拉推荐版本列表，给 UI 选版本用。 */
    suspend fun listVersions(gameVersion: String): List<ForgeVersion> = withContext(Dispatchers.IO) {
        val text = VanillaInstaller.fetchText(listOf(ForgeMeta.PROMOTIONS_URL))
        when (val parsed = parseForgePromotions(text, gameVersion)) {
            is ForgeParse.Ok -> parsed.value
            is ForgeParse.Failure -> throw IOException(parsed.reason)
        }
    }

    /**
     * 装 Forge 到 gameRoot。
     *
     * @param vanillaVersion 原版版本号（1.20.1）；installer 装出来的版本 json 的 inheritsFrom。
     * @param forgeVersion 具体 Forge 版本（47.4.0）；传空则取 recommended，没有就取 latest。
     * @param javaPath 跑 installer 用的 java；用游戏本身要求的那个主版本最稳（老版本 Forge 要 Java 8）。
     */
    suspend fun install(
        gameRoot: File,
        vanillaVersion: String,
        javaPath: String,
        forgeVersion: String? = null,
        onProgress: (ForgeProgress) -> Unit = {},
    ): ForgeInstallResult = withContext(Dispatchers.IO) {
        val started = Instant.now()
        var step = ForgeStep.VERSIONS

        try {
            val chosen = if (!forgeVersion.isNullOrBlank()) {
                forgeVersion.trim()
            } else {
                onProgress(ForgeProgress(ForgeStep.VERSIONS, "查询 " + vanillaVersion + " 的 Forge 版本…"))
                val versions = listVersions(vanillaVersion)
                (versions.firstOrNull { it.recommended } ?: versions.first()).version
            }

            val versionId = ForgeMeta.versionId(vanillaVersion, chosen)
            val versionDir = DownloadPlanner.versionDir(gameRoot, versionId)
            val versionJsonFile = File(versionDir, versionId + ".json")

            // 已经装过就直接复用：installer 跑一次三分钟，重复装同一版本没有任何意义。
            val existing = runCatching {
                if (versionJsonFile.isFile) parseVersionJson(versionJsonFile.readText(Charsets.UTF_8)) else null
            }.getOrNull()
            if (existing != null && isForgeProfile(existing, vanillaVersion)) {
                onProgress(ForgeProgress(step, "本机已装好 " + versionId + "，跳过安装"))
                // 跳过安装也要补 jar：老版本装出来的目录里只有 json（这是 0.8.0 之前的 bug），
                // 少了它启动会撞 net.minecraft.server 重复导出。
                step = ForgeStep.CLIENT_JAR
                val repairedJar = ensureClientJar(gameRoot, versionId, vanillaVersion) { detail ->
                    onProgress(ForgeProgress(step, detail))
                }
                return@withContext ForgeInstallResult(
                    versionId = versionId,
                    jsonFile = versionJsonFile,
                    forgeVersion = chosen,
                    versionJson = existing,
                    installerFile = File(ForgeMeta.installersDir(gameRoot), ForgeMeta.installerFileName(vanillaVersion, chosen)),
                    logFile = File(ForgeMeta.installersDir(gameRoot), versionId + "-install.log"),
                    installerExitCode = 0,
                    downloadedBytes = 0L,
                    elapsedMs = Duration.between(started, Instant.now()).toMillis(),
                    clientJarFile = repairedJar,
                )
            }

            step = ForgeStep.INSTALLER
            val installersDir = ForgeMeta.installersDir(gameRoot)
            installersDir.mkdirs()
            val installer = File(installersDir, ForgeMeta.installerFileName(vanillaVersion, chosen))
            val installerBytes = if (isUsableJar(installer)) {
                // 上次下过、jar 结构完好就不再重下
                onProgress(ForgeProgress(step, "复用已下载的安装器 " + installer.name))
                0L
            } else {
                onProgress(ForgeProgress(step, "下载 " + ForgeMeta.installerFileName(vanillaVersion, chosen)))
                val item = DownloadItem(
                    url = ForgeMeta.installerUrl(vanillaVersion, chosen),
                    target = installer.absolutePath,
                    size = -1L,
                    mirrors = emptyList(),
                )
                installer.parentFile?.mkdirs()
                val report = HttpDownloader().download(listOf(item)) { p ->
                    onProgress(
                        ForgeProgress(
                            step = ForgeStep.INSTALLER,
                            detail = "下载安装器 " + p.completedFiles + "/" + p.totalFiles,
                            done = p.completedBytes,
                            total = p.totalBytes,
                        ),
                    )
                }
                if (!report.ok) {
                    val first = report.failures.first()
                    throw IOException("Forge 安装器下载失败：" + first.reason)
                }
                installer.length()
            }
            if (!isUsableJar(installer)) {
                throw IOException("下载到的 Forge 安装器不是有效的 jar：" + installer.absolutePath)
            }

            step = ForgeStep.PREPARE
            // installer 硬性要求目标目录里有 launcher_profiles.json，缺了不会报错退出码却是 0，
            // 结果就是「装完了但什么都没装」。已经有的不要覆盖——那是用户真实的启动器档案。
            val profiles = File(gameRoot, "launcher_profiles.json")
            if (!profiles.isFile) {
                onProgress(ForgeProgress(step, "写入 launcher_profiles.json（installer 的前置要求）"))
                profiles.writeText(ForgeMeta.LAUNCHER_PROFILES_JSON, Charsets.UTF_8)
            }

            step = ForgeStep.RUN
            val logFile = File(installersDir, versionId + "-install.log")
            onProgress(ForgeProgress(step, "运行 Forge 安装器（约 3 分钟，日志：" + logFile.name + "）"))
            val exitCode = runInstaller(
                java = javaPath,
                installer = installer,
                gameRoot = gameRoot,
                logFile = logFile,
            ) { line ->
                // installer 每行日志都往前端推一次，用户能看到它真的在动；
                // 进度不重要，卡住时最后一行是什么才重要。
                onProgress(ForgeProgress(step, "安装器：" + line))
            }

            step = ForgeStep.VERIFY
            onProgress(ForgeProgress(step, "校验安装结果"))
            if (exitCode != 0) {
                throw IOException(
                    "Forge 安装器退出码 " + exitCode + "，日志在 " + logFile.absolutePath,
                )
            }
            if (!versionJsonFile.isFile) {
                throw IOException(
                    "安装器跑完了但没有产出 " + versionId + ".json，日志在 " + logFile.absolutePath,
                )
            }
            val json = parseVersionJson(versionJsonFile.readText(Charsets.UTF_8))
            if (!isForgeProfile(json, vanillaVersion)) {
                throw IOException(
                    "产出的版本 json 不像 Forge（inheritsFrom=" + json.inheritsFrom +
                        "，mainClass=" + json.mainClass + "），日志在 " + logFile.absolutePath,
                )
            }

            // installer 只产出 json 与 libraries，不在版本目录里放 <versionId>.jar。
            // 但 json 的 -DignoreList=${version_name}.jar 假定这个 jar 存在：缺了它，
            // BootstrapLauncher 会把原版 jar 当成自动模块 _1._20._1 加载，与 patch 后的
            // minecraft 模块撞包（net.minecraft.server 等），启动直接 ResolutionException。
            // 官方启动器是复制原版 client jar 顶上，这里照做。
            step = ForgeStep.CLIENT_JAR
            val clientJar = ensureClientJar(gameRoot, versionId, vanillaVersion) { detail ->
                onProgress(ForgeProgress(step, detail))
            }

            ForgeInstallResult(
                versionId = versionId,
                jsonFile = versionJsonFile,
                forgeVersion = chosen,
                versionJson = json,
                installerFile = installer,
                logFile = logFile,
                installerExitCode = exitCode,
                downloadedBytes = installerBytes,
                elapsedMs = Duration.between(started, Instant.now()).toMillis(),
                clientJarFile = clientJar,
            )
        } catch (e: Exception) {
            if (e is ForgeInstallException) throw e
            throw ForgeInstallException(step, e.message ?: e::class.simpleName ?: "未知错误", e)
        }
    }

    /** 扫描磁盘上已装的 Forge 版本，给 UI 做「已安装」提示。 */
    fun installedLoaders(gameRoot: File, vanillaVersion: String): List<InstalledForge> {
        val versionsRoot = File(gameRoot, "versions")
        if (!versionsRoot.isDirectory) return emptyList()
        return (versionsRoot.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val json = File(dir, dir.name + ".json")
                if (!json.isFile) return@mapNotNull null
                val text = runCatching { json.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
                val parsed = runCatching { parseVersionJson(text) }.getOrNull() ?: return@mapNotNull null
                if (!isForgeProfile(parsed, vanillaVersion)) return@mapNotNull null
                val version = ForgeMeta.loaderVersionFromId(dir.name) ?: return@mapNotNull null
                InstalledForge(versionId = dir.name, forgeVersion = version)
            }
            .sortedBy { it.forgeVersion }
    }

    /** 建实例：gameVersion 仍是原版号，loader 标 FORGE。 */
    suspend fun register(
        instances: InstanceRepository,
        gameRoot: File,
        result: ForgeInstallResult,
        gameVersion: String,
        javaPath: String?,
        memoryMb: Int,
        instanceName: String? = null,
    ): Instance {
        val instance = Instance(
            id = result.versionId,
            name = instanceName?.takeIf { it.isNotBlank() }
                ?: ForgeMeta.displayName(gameVersion, result.forgeVersion),
            gameVersion = gameVersion,
            loader = LoaderKind.FORGE,
            loaderVersion = result.forgeVersion,
            directory = DownloadPlanner.versionDir(gameRoot, result.versionId).absolutePath,
            javaPath = javaPath,
            memoryMb = Instance.clampMemoryMb(memoryMb),
            registered = true,
        )
        instances.save(instance)
        return instance
    }

    /**
     * 补齐 <versionId>.jar：把父版本的 client jar 复制过来。
     *
     * 为什么需要它：Forge 的 version json 里写着 -DignoreList=...,${version_name}.jar，
     * 意思是「别把版本目录里那个 jar 当模块加载」。installer 自己不产出这个 jar，
     * 官方启动器会在安装后复制原版 client jar 顶上。少了它，BootstrapLauncher 会把
     * classpath 里的原版 jar 当成自动模块 _1._20._1 加载，与 patch 过的 minecraft 模块
     * 同时导出 net.minecraft.server 等包，java.lang.module.ResolutionException 直接崩。
     *
     * 不复用符号链接/硬链接：用户的游戏目录可能被打包、同步盘搬运，链过去会断。
     */
    private fun ensureClientJar(
        gameRoot: File,
        versionId: String,
        vanillaVersion: String,
        onDetail: (String) -> Unit,
    ): File {
        val versionDir = DownloadPlanner.versionDir(gameRoot, versionId)
        versionDir.mkdirs()
        val target = File(versionDir, versionId + ".jar")
        if (target.isFile && target.length() > 0L) {
            onDetail("版本 jar 已存在（" + (target.length() / 1024 / 1024) + " MB）")
            return target
        }
        val source = File(DownloadPlanner.versionDir(gameRoot, vanillaVersion), vanillaVersion + ".jar")
        if (!source.isFile || source.length() <= 0L) {
            throw IOException(
                "缺原版 " + vanillaVersion + ".jar，无法补齐 Forge 的版本 jar；" +
                    "请先安装原版 " + vanillaVersion + "（路径：" + source.absolutePath + "）",
            )
        }
        onDetail("复制原版 " + source.name + " → " + target.name)
        source.copyTo(target, overwrite = true)
        return target
    }

    /** 判断一个版本 json 是不是「叠在原版上的 Forge」。 */
    private fun isForgeProfile(json: VersionJson, vanillaVersion: String): Boolean {
        if (json.inheritsFrom != vanillaVersion) return false
        val main = json.mainClass
        return main == ForgeMeta.MAIN_CLASS || main.contains("bootstraplauncher")
    }

    /** 只认压缩包里能找到 META-INF 的 jar；半截下载的文件会被这里挡掉。 */
    private fun isUsableJar(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching { ZipFile(file).use { it.getEntry("META-INF/MANIFEST.MF") != null } }.getOrDefault(false)
    }

    /**
     * 起 installer 子进程，边读日志边回调；超时强杀。
     *
     * 输出用 UTF-8 读：installer 全是英文，但版本 json 里会带中文模组名，宁可多解一次。
     */
    private fun runInstaller(
        java: String,
        installer: File,
        gameRoot: File,
        logFile: File,
        onLine: (String) -> Unit,
    ): Int {
        val command = listOf(
            java,
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-jar",
            installer.absolutePath,
            "--installClient",
            gameRoot.absolutePath,
        )
        val builder = ProcessBuilder(command)
            .directory(gameRoot)
            .redirectErrorStream(true)
        val process = builder.start()
        logFile.parentFile?.mkdirs()
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(INSTALL_TIMEOUT_MINUTES)

        logFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("# " + command.joinToString(" "), 0, ("# " + command.joinToString(" ")).length)
            writer.newLine()
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    writer.write(line)
                    writer.newLine()
                    // 刷盘：进程被强杀时日志也得留下来给用户看
                    writer.flush()
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) onLine(trimmed)
                    if (System.currentTimeMillis() > deadline) {
                        process.destroyForcibly()
                        throw IOException(
                            "Forge 安装器超过 " + INSTALL_TIMEOUT_MINUTES + " 分钟没跑完，已强制结束；日志在 " +
                                logFile.absolutePath,
                        )
                    }
                }
            }
        }
        return process.waitFor()
    }
}

/** 安装步骤；失败时抛 [ForgeInstallException]，step 说明卡在哪。 */
enum class ForgeStep(val label: String) {
    VERSIONS("查询 Forge 版本"),
    INSTALLER("下载 Forge 安装器"),
    PREPARE("准备启动器档案"),
    RUN("运行 Forge 安装器"),
    VERIFY("校验安装结果"),
    CLIENT_JAR("补齐版本 jar"),
}

data class ForgeProgress(
    val step: ForgeStep,
    val detail: String = "",
    val done: Long = 0L,
    val total: Long = 0L,
) {
    val fraction: Float
        get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

class ForgeInstallException(
    val step: ForgeStep,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class ForgeInstallResult(
    val versionId: String,
    val jsonFile: File,
    val forgeVersion: String,
    val versionJson: VersionJson,
    val installerFile: File,
    val logFile: File,
    val installerExitCode: Int,
    val downloadedBytes: Long,
    val elapsedMs: Long,
    /** 版本目录里的 <versionId>.jar（原版 client jar 的副本），缺了它启动会崩。 */
    val clientJarFile: File? = null,
)

data class InstalledForge(val versionId: String, val forgeVersion: String)
