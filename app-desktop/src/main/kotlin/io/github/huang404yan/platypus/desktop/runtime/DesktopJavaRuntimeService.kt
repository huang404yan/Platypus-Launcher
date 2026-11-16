package io.github.huang404yan.platypus.desktop.runtime

import io.github.huang404yan.platypus.core.runtime.JavaCandidatePaths
import io.github.huang404yan.platypus.core.runtime.JavaProbeParser
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeOffer
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeService
import io.github.huang404yan.platypus.core.runtime.buildRuntimeOffers
import io.github.huang404yan.platypus.core.runtime.javaExecutableInRuntime
import io.github.huang404yan.platypus.core.runtime.runtimePlatform
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeUnavailableException
import io.github.huang404yan.platypus.core.runtime.JavaSearchEnv
import io.github.huang404yan.platypus.core.runtime.ManagedJava
import io.github.huang404yan.platypus.core.runtime.findExactMajor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 桌面侧 Java 运行时探测。
 *
 * 分工：候选路径的枚举在 core 的 [JavaCandidatePaths]（纯字符串 + 目录枚举），
 * 解析探测输出在 core 的 [JavaProbeParser]；这里只做 core 做不了的两件事——
 * 读环境变量 / 注册表 / PATH，以及真的把 java 跑起来（D5 只约束 core 不碰 Process）。
 *
 * 坏路径一律静默跳过：文件不存在、不是可执行文件、起不来、卡住超时、输出不像 Java，
 * 都只是让这个候选从结果里消失，不抛异常、不影响其它候选。
 *
 * [ensure] 在本机找不到精确主版本时会从 Mojang 官方 runtime 清单下载一份到
 * `<launcherDir>/java/<component>`（见 [MojangJavaRuntimeInstaller]），装好即用。
 * 没有启动器目录（无头冒烟、单测）时退回旧的 `<gameRoot>/runtime`。
 */
class DesktopJavaRuntimeService(
    /** 额外的候选（界面上用户手动指定的 java.exe 或 JDK 根目录）。 */
    private val extraCandidates: () -> List<String> = { emptyList() },
    /** 游戏目录，用来找启动器自带的 runtime（.minecraft/runtime 等）。 */
    private val gameRoot: File? = null,
    /**
     * 是否同时扫描别的启动器留下的运行时（%APPDATA%/.minecraft、PCL、HMCL 等）。
     *
     * 产品行为是扫：用户机器上已经有一份能用的 Java 时不该再下一份 150 MB 的。
     * 开发期的无头验收要关掉它，否则宿主机上碰巧存在的同版本运行时会掩盖
     * 「自动下载」这条代码路径，测出来的就是假的通过。
     */
    private val scanGlobalLaunchers: Boolean = true,
    /**
     * 启动器自己的目录（安装版 = 安装目录，便携版 = 解压出来的文件夹）。
     *
     * 托管运行时落在这里而不是游戏目录：Java 是启动器的东西，换个游戏目录不该弄丢，
     * 卸载启动器也不该把一份 150 MB 的 JRE 留在用户盘上。为 null 时退回 [gameRoot]，
     * 保持无头冒烟与单测的旧行为。
     */
    val launcherDir: File? = null,
) : JavaRuntimeService {

    private val windows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Mojang 运行时清单里的平台键（windows-x64 之类），[catalog] 与下载共用。 */
    private val platform: String = runtimePlatform()

    /**
     * 托管运行时的下载与「已装」判定落在哪个根目录。
     *
     * 优先用启动器目录（[launcherDir]），没有才退回游戏目录。这个根只影响**新下载**与
     * 「这个组件装没装」，探测另走 [runtimeRoots]（那边两个根都扫，老用户的旧版本也认得出）。
     */
    private val installRoot: File? get() = launcherDir ?: gameRoot

    /**
     * 认作「托管」的所有根，删运行时与判断能不能删都用它。
     *
     * 两代位置都要认：老版本下在 `<游戏目录>/runtime`（[ManagedJava.legacyRootOf]），
     * 新版下在 `<启动器目录>/java`（[ManagedJava.rootOf]）。升级上来的用户两种都在盘上，
     * 漏一个就会退化成「删不掉」。
     */
    private val runtimeRoots: List<File>
        get() = listOfNotNull(launcherDir?.let(ManagedJava::rootOf), gameRoot?.let(ManagedJava::legacyRootOf))
            .distinctBy { it.absoluteFile.normalize().path.lowercase() }

    override suspend fun detect(): List<JavaRuntime> = withContext(Dispatchers.IO) {
        val candidates = JavaCandidatePaths.candidates(
            env = buildSearchEnv(),
            windows = windows,
            maxScanDepth = MAX_SCAN_DEPTH,
        )
        // 先按文件存在性过滤，省掉绝大多数无意义的进程启动。
        val executable = candidates.filter { isExecutableFile(it) }
        if (executable.isEmpty()) return@withContext emptyList()

        val gate = Semaphore(PROBE_PARALLELISM)
        val probed = coroutineScope {
            executable
                .map { path ->
                    async(Dispatchers.IO) {
                        gate.withPermit { probe(path) }
                    }
                }
                .awaitAll()
        }

        // 同一个 JDK 常常三条路一起命中（JAVA_HOME、PATH 里的 javapath 垫片、Program Files），
        // 按 java.home 去重，拿不到 java.home 时退回可执行文件的上级上级目录。
        //
        // 顺带把「落在托管根里的」标成 managed：probe 只看得到路径，不知道这份是谁装的。
        // 不标的话，重启一次之后界面上那份就不叫「启动器下载的」了，删除按钮也跟着消失 ——
        // 用户刚下回来的 150 MB 从此删不掉，只能手动去文件管理器里翻。
        val roots = runtimeRoots
        val seen = LinkedHashMap<String, JavaRuntime>()
        for (item in probed.filterNotNull()) {
            val key = JavaCandidatePaths.normalizeKey(
                item.home ?: File(item.runtime.path).parentFile?.parentFile?.path ?: item.runtime.path,
            )
            if (!seen.containsKey(key)) {
                val ours = ManagedJava.homeIn(item.runtime.path, roots) != null
                seen[key] = if (ours && !item.runtime.managed) item.runtime.copy(managed = true) else item.runtime
            }
        }
        seen.values.sortedWith(
            compareByDescending<JavaRuntime> { it.majorVersion }
                .thenBy { it.vendor.orEmpty().lowercase() }
                .thenBy { it.path.lowercase() },
        )
    }

    /**
     * 确保主版本正好是 [major] 的运行时可用：先探测，探测不到再从 Mojang 官方清单下载。
     *
     * 只认精确匹配，不拿更高版本顶替：老版本 Minecraft（尤其带 Forge/Fabric mod 的 1.16.5）
     * 在 Java 16/17/21 上会直接崩，静默换一个「更高的 Java」比明确报错更难排查。
     */
    override suspend fun ensure(major: Int, onProgress: (Float) -> Unit): JavaRuntime =
        ensureRuntime(major, component = null, onProgress = onProgress)

    /**
     * 带版本 json 里 `javaVersion.component` 的版本：component 是 Mojang 的权威标识
     * （如 java-runtime-gamma），只在它缺失时才按主版本号兜底映射。
     */
    override suspend fun ensureRuntime(
        major: Int,
        component: String?,
        onProgress: (Float) -> Unit,
    ): JavaRuntime {
        val all = detect()
        all.findExactMajor(major)?.let {
            onProgress(1f)
            return it
        }
        onProgress(0.15f)

        val root = installRoot ?: throw JavaRuntimeUnavailableException(
            "本机没有 Java " + major + "，且不知道启动器装在哪儿，无法下载托管运行时；" +
                "请手动指定 java.exe 或通过 -Dplatypus.gameDir 指定目录。",
        )
        val installer = MojangJavaRuntimeInstaller(root, runtimeRootOverride = ManagedJava.rootOf(root))
        val java = try {
            installer.ensure(major, component) { fraction -> onProgress(0.15f + 0.85f * fraction) }
        } catch (e: JavaRuntimeInstallException) {
            val available = all.map { it.majorVersion }.distinct().sorted().joinToString("、") { "Java " + it }
            val suffix = if (available.isEmpty()) "本机没有探测到任何 Java。" else "本机现有 " + available + "。"
            throw JavaRuntimeUnavailableException("自动下载 Java " + major + " 失败：" + e.message + " " + suffix)
        }
        onProgress(1f)
        // 装完立刻探一次，拿到 vendor/arch；探不动也用文件路径兜底（文件确实存在）。
        val probed = probe(java.path)?.runtime
        return probed?.copy(managed = true) ?: JavaRuntime(
            path = java.path,
            majorVersion = major,
            vendor = "Mojang",
            managed = true,
        )
    }

    /**
     * 官方清单里这台机器能装的运行时，带「已装」标记。
     *
     * 「已装」先看托管目录（<启动器目录>/java/<component>/bin/java.exe），再看本机探测到的
     * 同主版本 Java —— 用户机器上已经有一份能用的 Java 17 时，列表里那一行就该显示「已装」，
     * 而不是让他再下一份 150 MB 的。两者都没有才显示「下载」。
     *
     * 拿不到清单（断网、镜像挂了、没配游戏目录都算）时返回空表并把原因放进 [lastCatalogError]，
     * 由界面决定显示「列表拉不到」还是「这台机器一个都装不了」。
     */
    override suspend fun catalog(): List<JavaRuntimeOffer> {
        lastCatalogError = null
        val root = installRoot
        if (root == null) {
            lastCatalogError = "不知道启动器装在哪儿，也不知道游戏目录，没法决定 Java 下到哪儿。"
            return emptyList()
        }
        val index = try {
            MojangJavaRuntimeInstaller(root, runtimeRootOverride = ManagedJava.rootOf(root)).loadIndex()
        } catch (e: JavaRuntimeInstallException) {
            lastCatalogError = e.message
            return emptyList()
        }
        val detected = detect()
        val offers = buildRuntimeOffers(index, platform) { component ->
            // 两代托管根都算「已装」：不能因为换过位置就把下过的 150 MB 再下一遍。
            val managed = runtimeRoots.asSequence()
                .map { javaExecutableInRuntime(File(it, component), platform) }
                .firstOrNull { it.isFile }
            managed?.path
        }
        // 本机别处已装同主版本时补上「已装」，但路径显示托管那份的优先。
        return offers.map { offer ->
            if (offer.installed) return@map offer
            val local = detected.findExactMajor(offer.majorVersion) ?: return@map offer
            offer.copy(installed = true, installedPath = local.path)
        }
    }

    /**
     * 强制下载一份托管运行时。
     *
     * 与 [ensureRuntime] 的唯一差别是**不先探测本机**：用户明确点了「下载 Java 21」，
     * 就该得到一份启动器自己管的、能在列表里看见、删得掉的运行时，
     * 而不是悄悄用回他系统里那个（那个可能明天就被卸载了）。
     */
    override suspend fun download(
        major: Int,
        component: String?,
        onProgress: (Float) -> Unit,
    ): JavaRuntime {
        val root = installRoot ?: throw JavaRuntimeUnavailableException(
            "不知道启动器装在哪儿，无法下载托管运行时；请手动指定 java.exe。",
        )
        val installer = MojangJavaRuntimeInstaller(root, runtimeRootOverride = ManagedJava.rootOf(root))
        val java = try {
            installer.ensure(major, component, onProgress)
        } catch (e: JavaRuntimeInstallException) {
            throw JavaRuntimeUnavailableException("下载 Java " + major + " 失败：" + e.message)
        }
        val probed = probe(java.path)?.runtime
        return probed?.copy(managed = true) ?: JavaRuntime(
            path = java.path,
            majorVersion = major,
            vendor = "Mojang",
            managed = true,
        )
    }

    /**
     * 删掉一份启动器自己装的运行时。
     *
     * 三条都要过：这份是托管的、路径确实落在某个托管根的 `<组件名>/bin/` 里、目录还在。
     * 过了就先改名成 `<组件名>.deleting-<时间戳>` 再整个删 —— 与 [InstanceDeleter] 一个路子：
     * 项目不带回收站依赖，改名这一步保证「删到一半失败」时用户看到的不是一坨半残的运行时。
     * 删不完（文件被占用）就把名字改回去，并把原因放进 [lastRemoveError]。
     */
    override suspend fun remove(runtime: JavaRuntime): Boolean = withContext(Dispatchers.IO) {
        lastRemoveError = null
        if (!runtime.managed) {
            lastRemoveError = "这一份不是启动器下载的，不在删除范围里。"
            return@withContext false
        }
        val home = ManagedJava.homeIn(runtime.path, runtimeRoots)
        if (home == null || !home.isDirectory) {
            lastRemoveError = "这一份不在启动器的 java/ 目录里，不动它。"
            return@withContext false
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
        val trashed = File(home.parentFile, home.name + ".deleting-" + stamp)
        if (!home.renameTo(trashed)) {
            lastRemoveError = "文件正被占用（游戏可能还开着），关掉之后再删。"
            return@withContext false
        }
        var failure: File? = null
        trashed.walkBottomUp().forEach { entry ->
            if (!entry.delete() && failure == null) failure = entry
        }
        if (failure != null) {
            // 删了一半：把名字改回去，至少目录还在、还能用。
            trashed.renameTo(home)
            lastRemoveError = "删到一半失败：" + failure!!.absolutePath + "（文件被占用）"
            return@withContext false
        }
        true
    }

    /** 上次 [remove] 失败的原因，成功时为 null。 */
    @Volatile
    var lastRemoveError: String? = null
        private set

    /** 上次 [catalog] 失败的原因，成功时为 null。 */
    @Volatile
    var lastCatalogError: String? = null
        private set

    /** 读环境变量、注册表、PATH、启动器目录，拼出候选枚举需要的输入。 */
    private fun buildSearchEnv(): JavaSearchEnv {
        val programFiles = buildList {
            env("ProgramFiles")?.let { add(it) }
            env("ProgramW6432")?.let { add(it) }
            env("ProgramFiles(x86)")?.let { add(it) }
        }
        return JavaSearchEnv(
            javaHome = env("JAVA_HOME"),
            programFilesDirs = programFiles,
            registryJavaHomes = if (windows) registryJavaHomes() else emptyList(),
            launcherRuntimeDirs = launcherRuntimeDirs(),
            pathDirs = env("PATH").orEmpty().split(File.pathSeparatorChar),
            extraJavas = extraCandidates(),
        )
    }

    private fun env(name: String): String? =
        System.getenv(name)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }

    /** .minecraft 与常见第三方启动器放自带 JRE 的位置。 */
    private fun launcherRuntimeDirs(): List<String> {
        val roots = LinkedHashSet<String>()
        gameRoot?.let { roots += it.path }
        if (scanGlobalLaunchers) {
            env("APPDATA")?.let { roots += File(it, ".minecraft").path }
            env("APPDATA")?.let { roots += File(it, "PCL").path }
            env("APPDATA")?.let { roots += File(it, "HMCL").path }
            env("LOCALAPPDATA")?.let { roots += File(it, "PCL").path }
            env("PLATYPUS_JAVA_DIR")?.let { roots += it }
        }
        val names = listOf("runtime", "java", "jre", "jres", "java-runtimes", "javas")
        val dirs = LinkedHashSet<String>()
        // 启动器自己的运行时根：<启动器目录>/java。位置是我们自己定的，不拼 name 变体，
        // 也不加启动器目录本身 —— 那会把 app/、runtime/（jpackage 自带的那个 JRE）一起扫，纯浪费。
        launcherDir?.let { launcher ->
            val ownRuntime = ManagedJava.rootOf(launcher)
            if (ownRuntime.isDirectory) dirs += ownRuntime.path
        }
        for (root in roots) {
            if (!File(root).isDirectory) continue
            dirs += root
            for (name in names) {
                for (variant in listOf(name, name.replaceFirstChar { it.uppercaseChar() })) {
                    val dir = File(root, variant)
                    if (dir.isDirectory) dirs += dir.path
                }
            }
        }
        return dirs.toList()
    }

    /**
     * 注册表里登记的 JavaHome。
     *
     * 用 reg.exe 而不是 JNI，避免给桌面模块加本地依赖；查询失败（没有 reg、键不存在、权限不足）就当没有。
     */
    private fun registryJavaHomes(): List<String> {
        val keys = listOf(
            "HKLM\\SOFTWARE\\JavaSoft\\JDK",
            "HKLM\\SOFTWARE\\JavaSoft\\Java Development Kit",
            "HKLM\\SOFTWARE\\JavaSoft\\Java Runtime Environment",
            "HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\Java Development Kit",
            "HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\Java Runtime Environment",
            "HKLM\\SOFTWARE\\Eclipse Adoptium",
            "HKLM\\SOFTWARE\\Microsoft\\JDK",
            "HKLM\\SOFTWARE\\Azul Systems\\Zulu",
        )
        val homes = LinkedHashSet<String>()
        for (key in keys) {
            val output = runCommand(listOf("reg", "query", key, "/s"), REG_TIMEOUT_MS) ?: continue
            for (line in output.lineSequence()) {
                val m = RegistryValueLine.matchEntire(line) ?: continue
                val name = m.groupValues[1]
                if (!name.equals("JavaHome", ignoreCase = true) && !name.equals("Path", ignoreCase = true)) continue
                val value = m.groupValues[2].trim()
                if (value.isNotBlank()) homes += value
            }
        }
        return homes.toList()
    }

    /** 跑一个候选：能解析出版本才算数。 */
    private fun probe(executable: String): ProbeResult? {
        val output = runCommand(
            listOf(executable, "-XshowSettings:properties", "-version"),
            PROBE_TIMEOUT_MS,
        )
        if (output.isNullOrBlank()) return null
        val runtime = JavaProbeParser.parse(output = output, path = executable) ?: return null
        return ProbeResult(runtime, JavaProbeParser.javaHome(output))
    }

    /**
     * 跑命令并拿到合并后的输出（java 把版本信息写到 stderr，必须合流）。
     *
     * 超时直接强杀；进程起不来返回 null。绝不把异常抛给调用方。
     */
    private fun runCommand(command: List<String>, timeoutMs: Long): String? {
        var process: Process? = null
        return try {
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)
            val started = pb.start()
            process = started
            // 关掉 stdin：万一某个歪 java 卡在「等待输入」，也不会把探测挂死。
            runCatching { started.outputStream.close() }
            val buffer = ByteArrayOutputStream()
            val pump = Thread { runCatching { started.inputStream.copyTo(buffer) } }
            pump.isDaemon = true
            pump.start()
            val finished = started.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                started.destroyForcibly()
                started.waitFor(1, TimeUnit.SECONDS)
                return null
            }
            pump.join(500)
            String(buffer.toByteArray(), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { process?.takeIf { it.isAlive }?.destroyForcibly() }
        }
    }

    private fun isExecutableFile(path: String): Boolean = try {
        val f = File(path)
        f.isFile && (!windows || f.name.lowercase().endsWith(".exe"))
    } catch (_: Throwable) {
        false
    }

    /** 探测结果 + 该运行时自报的 java.home（用于去重）。 */
    private data class ProbeResult(val runtime: JavaRuntime, val home: String?)

    private companion object {
        /** 同时最多跑几个 java 探测，别把本机 CPU 打满。 */
        const val PROBE_PARALLELISM = 6
        const val MAX_SCAN_DEPTH = 4
        const val PROBE_TIMEOUT_MS = 5_000L
        const val REG_TIMEOUT_MS = 4_000L

        /** 形如 "    JavaHome    REG_SZ    C:\Program Files\Java\jdk-17"。 */
        val RegistryValueLine = Regex("""^\s+(\S+)\s+REG_(?:SZ|EXPAND_SZ)\s+(.+?)\s*$""")
    }
}
