package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.loader.NeoForgeInstallProfile
import io.github.huang404yan.platypus.core.loader.NeoForgeMeta
import io.github.huang404yan.platypus.core.loader.InstallerLibrary
import io.github.huang404yan.platypus.core.loader.NeoForgeParse
import io.github.huang404yan.platypus.core.loader.NeoForgeVersion
import io.github.huang404yan.platypus.core.loader.parseNeoForgeMetadata
import io.github.huang404yan.platypus.core.loader.pickNeoForgeForMinecraft
import io.github.huang404yan.platypus.core.version.DownloadPlanner
import io.github.huang404yan.platypus.core.version.VersionJson
import io.github.huang404yan.platypus.core.version.parseVersionJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * NeoForge 安装：解析版本清单 → 下官方 installer → 跑 --installClient → 校验产物 → 补齐版本 jar。
 *
 * 与 [ForgeInstaller] 同构（都走官方 installer，因为客户端 jar 的 processor 链不能手写），
 * 但有四处必须不同，写在这里免得后来者当成疏漏：
 * 1. **源是 BMCLAPI 镜像**。官方 maven.neoforged.net 在本机 TLS 被重置（见 NeoForgeMeta 注释）。
 * 2. **没有推荐版本表**。版本清单是 maven-metadata.xml，要按「推出来的 MC 版本」自己挑。
 * 3. **必须先复核 inheritsFrom**。NeoForge 版本号里没有权威的 MC 版本信息，全靠推；
 *    下完 installer 立刻读它 jar 内的 version.json，与用户要的原版号不一致就直接中止，
 *    否则会白跑三分钟装出一个「1.21.1 游戏装了 1.21.3 的 NeoForge」的怪目录。
 * 4. **版本 id 是 neoforge-<版本>**，不带原版号前缀（installer 自己就按这个 id 建目录）。
 *
 * installer 自身的日志原样推给界面：三分钟的过程里，卡住时最后一行是什么比假进度条有用。
 */
object NeoForgeInstaller {

    /** 跑 installer 的总超时。Forge 实测约 3 分钟，NeoForge 的 processor 更多，给到 20 分钟。 */
    private const val INSTALL_TIMEOUT_MINUTES = 20L

    /** 拉某个原版版本可用的 NeoForge 版本列表（只用于界面选版本）。 */
    suspend fun listVersions(gameVersion: String): List<NeoForgeVersion> = withContext(Dispatchers.IO) {
        val text = VanillaInstaller.fetchText(listOf(NeoForgeMeta.metadataUrl()))
        when (val parsed = parseNeoForgeMetadata(text)) {
            is NeoForgeParse.Ok -> parsed.value.filter { it.minecraftVersion == gameVersion }
            is NeoForgeParse.Failure -> throw IOException(parsed.reason)
        }
    }

    /**
     * 装 NeoForge 到 gameRoot。
     *
     * @param vanillaVersion 用户要装的原版版本（1.21.1）。会用来挑 NeoForge 版本，也会在
     *   拿到 installer 后用 jar 内 inheritsFrom 复核；不一致直接抛错。
     * @param neoforgeVersion 具体 NeoForge 版本（21.1.265）；传空则按 vanillaVersion 自动挑。
     * @param javaPath 跑 installer 用的 java；用游戏本身要求的那个主版本最稳。
     */
    suspend fun install(
        gameRoot: File,
        vanillaVersion: String,
        javaPath: String,
        neoforgeVersion: String? = null,
        onProgress: (NeoForgeProgress) -> Unit = {},
    ): NeoForgeInstallResult = withContext(Dispatchers.IO) {
        val started = Instant.now()
        var step = NeoForgeStep.VERSIONS

        try {
            val chosen = if (!neoforgeVersion.isNullOrBlank()) {
                neoforgeVersion.trim()
            } else {
                onProgress(NeoForgeProgress(step, "查询 " + vanillaVersion + " 可用的 NeoForge 版本…"))
                val all = listVersions(vanillaVersion)
                if (all.isEmpty()) {
                    throw IOException(
                        "NeoForge 没有为 " + vanillaVersion + " 发布版本；" +
                            "可以先去「下载」页装原版，或换一个 NeoForge 支持的游戏版本。",
                    )
                }
                pickNeoForgeForMinecraft(all, vanillaVersion)?.version
                    ?: throw IOException("从 NeoForge 清单里挑不出能装在 " + vanillaVersion + " 上的版本")
            }

            val versionId = NeoForgeMeta.versionId(chosen)
            val versionDir = DownloadPlanner.versionDir(gameRoot, versionId)
            val versionJsonFile = File(versionDir, versionId + ".json")

            // 已经装过就直接复用：installer 跑一次要好几分钟，重复装同一版本没有意义。
            val existing = runCatching {
                if (versionJsonFile.isFile) parseVersionJson(versionJsonFile.readText(Charsets.UTF_8)) else null
            }.getOrNull()
            if (existing != null && isNeoForgeProfile(existing, vanillaVersion)) {
                onProgress(NeoForgeProgress(step, "本机已装好 " + versionId + "，跳过安装"))
                step = NeoForgeStep.CLIENT_JAR
                val repairedJar = ensureClientJar(gameRoot, versionId, vanillaVersion) { detail ->
                    onProgress(NeoForgeProgress(step, detail))
                }
                return@withContext NeoForgeInstallResult(
                    versionId = versionId,
                    jsonFile = versionJsonFile,
                    neoforgeVersion = chosen,
                    versionJson = existing,
                    installerFile = File(
                        NeoForgeMeta.installersDir(gameRoot),
                        NeoForgeMeta.installerFileName(chosen),
                    ),
                    logFile = File(NeoForgeMeta.installersDir(gameRoot), versionId + "-install.log"),
                    installerExitCode = 0,
                    downloadedBytes = 0L,
                    elapsedMs = Duration.between(started, Instant.now()).toMillis(),
                    clientJarFile = repairedJar,
                )
            }

            step = NeoForgeStep.INSTALLER
            val installersDir = NeoForgeMeta.installersDir(gameRoot)
            installersDir.mkdirs()
            val installer = File(installersDir, NeoForgeMeta.installerFileName(chosen))
            val installerBytes = if (isUsableJar(installer)) {
                onProgress(NeoForgeProgress(step, "复用已下载的安装器 " + installer.name))
                0L
            } else {
                onProgress(NeoForgeProgress(step, "下载 " + NeoForgeMeta.installerFileName(chosen)))
                val item = DownloadItem(
                    url = NeoForgeMeta.installerUrl(chosen),
                    target = installer.absolutePath,
                    size = -1L,
                    mirrors = emptyList(),
                )
                installer.parentFile?.mkdirs()
                val report = HttpDownloader().download(listOf(item)) { p ->
                    onProgress(
                        NeoForgeProgress(
                            step = NeoForgeStep.INSTALLER,
                            detail = "下载安装器 " + p.completedFiles + "/" + p.totalFiles,
                            done = p.completedBytes,
                            total = p.totalBytes,
                        ),
                    )
                }
                if (!report.ok) {
                    val first = report.failures.first()
                    throw IOException("NeoForge 安装器下载失败：" + first.reason)
                }
                installer.length()
            }
            if (!isUsableJar(installer)) {
                throw IOException("下载到的 NeoForge 安装器不是有效的 jar：" + installer.absolutePath)
            }

            // ★ 装之前先复核：installer jar 内的 inheritsFrom 才是权威的「这个版本针对哪个 MC」。
            // 版本号推错、或者用户手填了一个不对的版本，都在这里被拦下来——代价只是几秒下载，
            // 而不是白等几分钟再得到一个装错版本的游戏目录。
            step = NeoForgeStep.VERIFY_TARGET
            val declared = NeoForgeMeta.readInheritsFrom(installer)
            if (declared == null) {
                onProgress(NeoForgeProgress(step, "安装器里没有可读的 version.json，跳过目标复核"))
            } else if (declared != vanillaVersion) {
                throw IOException(
                    "NeoForge " + chosen + " 是给 Minecraft " + declared + " 的，不是 " + vanillaVersion +
                        "；请换一个版本，或先装 " + declared + "。",
                )
            } else {
                onProgress(NeoForgeProgress(step, "已确认 " + chosen + " 对应 Minecraft " + declared))
            }

            step = NeoForgeStep.PREPARE
            // installer 硬性要求目标目录里有 launcher_profiles.json，缺了不报错、退出码 0、什么都不装。
            // 已经有的不要覆盖——那是用户真实的启动器档案。
            val profiles = File(gameRoot, "launcher_profiles.json")
            if (!profiles.isFile) {
                onProgress(NeoForgeProgress(step, "写入 launcher_profiles.json（installer 的前置要求）"))
                profiles.writeText(NeoForgeMeta.LAUNCHER_PROFILES_JSON, Charsets.UTF_8)
            }

            // ★ 预铺依赖库。installer 会按 install_profile.json 里写死的 maven.neoforged.net 地址
            // 逐个下 70 个库，而这台机器上那个域名 TLS 被重置：每个库等 5 秒超时、继续下一个，
            // 最后进度条看着在动、其实一个都没装上。--mirror 参数对 client 安装无效（只有 server /
            // fat 用它），所以只能在跑之前把库按 <gameRoot>/libraries/<path> 铺好——
            // installer 的 Downloader 见到文件存在且 sha1 相符就打印 Checksum valid 并跳过，
            // 一个网络请求都不发。详见 NeoForgeInstallProfile 的注释。
            step = NeoForgeStep.PREPARE_LIBS
            val preparedBytes = prepareLibraries(gameRoot, installer) { progress ->
                onProgress(progress)
            }

            step = NeoForgeStep.RUN
            val logFile = File(installersDir, versionId + "-install.log")
            onProgress(NeoForgeProgress(step, "运行 NeoForge 安装器（约 2-5 分钟，日志：" + logFile.name + "）"))
            val exitCode = runInstaller(
                java = javaPath,
                installer = installer,
                gameRoot = gameRoot,
                logFile = logFile,
            ) { line ->
                onProgress(NeoForgeProgress(step, "安装器：" + line))
            }

            step = NeoForgeStep.VERIFY
            onProgress(NeoForgeProgress(step, "校验安装结果"))
            if (exitCode != 0) {
                throw IOException("NeoForge 安装器退出码 " + exitCode + "，日志在 " + logFile.absolutePath)
            }
            if (!versionJsonFile.isFile) {
                throw IOException(
                    "安装器跑完了但没有产出 " + versionId + ".json，日志在 " + logFile.absolutePath,
                )
            }
            val json = parseVersionJson(versionJsonFile.readText(Charsets.UTF_8))
            if (!isNeoForgeProfile(json, vanillaVersion)) {
                throw IOException(
                    "产出的版本 json 不像 NeoForge（inheritsFrom=" + json.inheritsFrom +
                        "，mainClass=" + json.mainClass + "），日志在 " + logFile.absolutePath,
                )
            }

            // 与 Forge 同理：json 的 -DignoreList=${version_name}.jar 假定版本目录里有那个 jar，
            // 缺了 BootstrapLauncher 会把原版 jar 当自动模块加载，与 patch 过的 minecraft 撞包。
            step = NeoForgeStep.CLIENT_JAR
            val clientJar = ensureClientJar(gameRoot, versionId, vanillaVersion) { detail ->
                onProgress(NeoForgeProgress(step, detail))
            }

            NeoForgeInstallResult(
                versionId = versionId,
                jsonFile = versionJsonFile,
                neoforgeVersion = chosen,
                versionJson = json,
                installerFile = installer,
                logFile = logFile,
                installerExitCode = exitCode,
                downloadedBytes = installerBytes + preparedBytes,
                elapsedMs = Duration.between(started, Instant.now()).toMillis(),
                clientJarFile = clientJar,
            )
        } catch (e: CancellationException) {
            // 用户点了「取消」：这不是失败，别包成 NeoForgeInstallException 让界面报红。
            throw e
        } catch (e: Exception) {
            if (e is NeoForgeInstallException) throw e
            throw NeoForgeInstallException(step, e.message ?: e::class.simpleName ?: "未知错误", e)
        }
    }

    /** 扫描磁盘上已装的 NeoForge 版本，给界面做「已安装」提示。 */
    fun installedLoaders(gameRoot: File, vanillaVersion: String): List<InstalledNeoForge> {
        val versionsRoot = File(gameRoot, "versions")
        if (!versionsRoot.isDirectory) return emptyList()
        return (versionsRoot.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val json = File(dir, dir.name + ".json")
                if (!json.isFile) return@mapNotNull null
                val text = runCatching { json.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
                val parsed = runCatching { parseVersionJson(text) }.getOrNull() ?: return@mapNotNull null
                if (!isNeoForgeProfile(parsed, vanillaVersion)) return@mapNotNull null
                val version = NeoForgeMeta.loaderVersionFromId(dir.name) ?: return@mapNotNull null
                InstalledNeoForge(versionId = dir.name, neoforgeVersion = version)
            }
            .sortedBy { it.neoforgeVersion }
    }

    /** 建实例：gameVersion 仍是原版号，loader 标 NEOFORGE。 */
    suspend fun register(
        instances: InstanceRepository,
        gameRoot: File,
        result: NeoForgeInstallResult,
        gameVersion: String,
        javaPath: String?,
        memoryMb: Int,
        instanceName: String? = null,
    ): Instance {
        val instance = Instance(
            id = result.versionId,
            name = instanceName?.takeIf { it.isNotBlank() }
                ?: NeoForgeMeta.displayName(result.neoforgeVersion),
            gameVersion = gameVersion,
            loader = LoaderKind.NEOFORGE,
            loaderVersion = result.neoforgeVersion,
            directory = DownloadPlanner.versionDir(gameRoot, result.versionId).absolutePath,
            javaPath = javaPath,
            memoryMb = Instance.clampMemoryMb(memoryMb),
            registered = true,
        )
        instances.save(instance)
        return instance
    }

    /**
     * 把 installer 要用的依赖库提前铺到 <gameRoot>/libraries/。
     *
     * 为什么必须做、installer 见到文件为什么就跳过，见 [NeoForgeInstallProfile] 的类注释。
     * 返回这次真正下载的字节数（原本就在位的库不计）。
     *
     * 读不出库清单时**不阻断安装**：退回让 installer 自己去下。虽然那样大概率超时，
     * 但日志里至少能看出卡在哪个库，比在这里抛一个「清单读不出来」的死错更有用。
     */
    private suspend fun prepareLibraries(
        gameRoot: File,
        installer: File,
        onProgress: (NeoForgeProgress) -> Unit,
    ): Long {
        val libraries = NeoForgeInstallProfile.libraries(installer)
        if (libraries.isEmpty()) {
            onProgress(
                NeoForgeProgress(NeoForgeStep.PREPARE_LIBS, "安装器里没有可读的库清单，跳过预铺"),
            )
            return 0L
        }

        val items = libraries.mapNotNull { library ->
            val target = NeoForgeInstallProfile.targetFile(gameRoot, library)
            if (isLibraryPresent(target, library)) {
                null
            } else {
                DownloadItem(
                    url = NeoForgeInstallProfile.downloadUrl(library.url),
                    target = target.absolutePath,
                    sha1 = library.sha1,
                    size = library.size,
                    mirrors = NeoForgeInstallProfile.mirrorUrls(library),
                )
            }
        }
        val skipped = libraries.size - items.size

        if (items.isEmpty()) {
            onProgress(NeoForgeProgress(NeoForgeStep.PREPARE_LIBS, "依赖库 " + libraries.size + " 个都已在本地"))
            return 0L
        }

        onProgress(
            NeoForgeProgress(
                step = NeoForgeStep.PREPARE_LIBS,
                detail = "预铺依赖库 " + items.size + " 个" + if (skipped > 0) "（" + skipped + " 个已存在）" else "",
                done = 0L,
                total = items.sumOf { if (it.size > 0) it.size else 0L },
            ),
        )

        val report = HttpDownloader().download(items) { progress ->
            onProgress(
                NeoForgeProgress(
                    step = NeoForgeStep.PREPARE_LIBS,
                    detail = "预铺依赖库 " + progress.completedFiles + "/" + progress.totalFiles,
                    done = progress.completedBytes,
                    total = progress.totalBytes,
                ),
            )
        }
        if (!report.ok) {
            // installer 对缺库是「跳过继续」，最后装出来的东西看着成功、一启动就崩，
            // 所以这里必须硬失败，并且说清是哪几个库。
            val shown = report.failures.take(3).joinToString("；") { failure ->
                File(failure.item.target).name + "：" + failure.reason
            }
            val more = if (report.failures.size > 3) "（另有 " + (report.failures.size - 3) + " 个失败）" else ""
            throw IOException(
                "NeoForge 依赖库有 " + report.failures.size + " 个没下下来，安装器会因此装不全。失败的库：" +
                    shown + more,
            )
        }
        return items.sumOf { File(it.target).length() }
    }

    /** 库是否已经在位：有 sha1 就比 sha1，没有就比大小；对不上都当不在位（会被重下覆盖）。 */
    private fun isLibraryPresent(target: File, library: InstallerLibrary): Boolean {
        if (!target.isFile || target.length() <= 0L) return false
        val sha1 = library.sha1 ?: return library.size < 0 || target.length() == library.size
        return sha1Of(target).equals(sha1, ignoreCase = true)
    }

    private fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            if (value < 0x10) "0" + value.toString(16) else value.toString(16)
        }
    }

    /**
     * 补齐 <versionId>.jar：把父版本的 client jar 复制过来。
     * 理由与 Forge 完全一样（见 ForgeInstaller.ensureClientJar），这里不重复解释。
     * 不用符号链接/硬链接：游戏目录可能被打包或同步盘搬运，链过去会断。
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
                "缺原版 " + vanillaVersion + ".jar，无法补齐 NeoForge 的版本 jar；" +
                    "请先安装原版 " + vanillaVersion + "（路径：" + source.absolutePath + "）",
            )
        }
        onDetail("复制原版 " + source.name + " → " + target.name)
        source.copyTo(target, overwrite = true)
        return target
    }

    /** 判断一个版本 json 是不是「叠在原版上的 NeoForge」。 */
    private fun isNeoForgeProfile(json: VersionJson, vanillaVersion: String): Boolean {
        if (json.inheritsFrom != vanillaVersion) return false
        val main = json.mainClass
        return main == NeoForgeMeta.MAIN_CLASS || main.contains("bootstraplauncher")
    }

    /** 只认压缩包里能找到 META-INF 的 jar；半截下载的文件会被这里挡掉。 */
    private fun isUsableJar(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching { ZipFile(file).use { it.getEntry("META-INF/MANIFEST.MF") != null } }.getOrDefault(false)
    }

    /**
     * 起 installer 子进程，边读日志边回调；超时强杀。
     *
     * 执行本身交给 [InstallerProcess]：那里负责「取消时真的杀进程」与「把取消当取消而不是当失败」。
     * 输出按 UTF-8 读：installer 自身是英文，但它会把模组名、语言文件里的中文一起打出来。
     * 不传 -Dstdout.encoding 的时候，JDK 25 在中文 Windows 上会按 GBK 输出，中文全变问号。
     */
    private suspend fun runInstaller(
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
        return InstallerProcess.run(
            command = command,
            workingDir = gameRoot,
            logFile = logFile,
            timeoutMillis = TimeUnit.MINUTES.toMillis(INSTALL_TIMEOUT_MINUTES),
            onLine = onLine,
        )
    }
}

/** 安装步骤；失败时抛 [NeoForgeInstallException]，step 说明卡在哪。 */
enum class NeoForgeStep(val label: String) {
    VERSIONS("查询 NeoForge 版本"),
    INSTALLER("下载 NeoForge 安装器"),
    VERIFY_TARGET("复核目标游戏版本"),
    PREPARE("准备启动器档案"),
    PREPARE_LIBS("预铺 NeoForge 依赖库"),
    RUN("运行 NeoForge 安装器"),
    VERIFY("校验安装结果"),
    CLIENT_JAR("补齐版本 jar"),
}

data class NeoForgeProgress(
    val step: NeoForgeStep,
    val detail: String = "",
    val done: Long = 0L,
    val total: Long = 0L,
) {
    val fraction: Float
        get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

class NeoForgeInstallException(
    val step: NeoForgeStep,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class NeoForgeInstallResult(
    val versionId: String,
    val jsonFile: File,
    val neoforgeVersion: String,
    val versionJson: VersionJson,
    val installerFile: File,
    val logFile: File,
    val installerExitCode: Int,
    val downloadedBytes: Long,
    val elapsedMs: Long,
    /** 版本目录里的 <versionId>.jar（原版 client jar 的副本），缺了它启动会崩。 */
    val clientJarFile: File? = null,
)

data class InstalledNeoForge(val versionId: String, val neoforgeVersion: String)
