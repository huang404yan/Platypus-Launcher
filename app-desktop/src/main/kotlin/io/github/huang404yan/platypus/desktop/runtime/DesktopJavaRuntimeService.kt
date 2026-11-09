package io.github.huang404yan.platypus.desktop.runtime

import io.github.huang404yan.platypus.core.runtime.JavaCandidatePaths
import io.github.huang404yan.platypus.core.runtime.JavaProbeParser
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeService
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeUnavailableException
import io.github.huang404yan.platypus.core.runtime.JavaSearchEnv
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
 */
class DesktopJavaRuntimeService(
    /** 额外的候选（界面上用户手动指定的 java.exe 或 JDK 根目录）。 */
    private val extraCandidates: () -> List<String> = { emptyList() },
    /** 游戏目录，用来找启动器自带的 runtime（.minecraft/runtime 等）。 */
    private val gameRoot: File? = null,
) : JavaRuntimeService {

    private val windows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

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
        val seen = LinkedHashMap<String, JavaRuntime>()
        for (item in probed.filterNotNull()) {
            val key = JavaCandidatePaths.normalizeKey(
                item.home ?: File(item.runtime.path).parentFile?.parentFile?.path ?: item.runtime.path,
            )
            if (!seen.containsKey(key)) seen[key] = item.runtime
        }
        seen.values.sortedWith(
            compareByDescending<JavaRuntime> { it.majorVersion }
                .thenBy { it.vendor.orEmpty().lowercase() }
                .thenBy { it.path.lowercase() },
        )
    }

    /**
     * 从已探测到的运行时里挑一个主版本正好是 [major] 的。
     *
     * 只认精确匹配，不拿更高版本顶替：老版本 Minecraft（尤其带 Forge/Fabric mod 的 1.16.5）
     * 在 Java 16/17/21 上会直接崩，静默换一个「更高的 Java」比明确报错更难排查。
     * 自动下载安装 JDK 还没做（不在本任务范围），找不到就抛 [JavaRuntimeUnavailableException]，
     * 消息里带上本机现有的主版本，方便界面提示用户自己装或手动指定。
     */
    override suspend fun ensure(major: Int, onProgress: (Float) -> Unit): JavaRuntime {
        val all = detect()
        onProgress(1f)
        all.findExactMajor(major)?.let { return it }
        val available = all.map { it.majorVersion }.distinct().sorted().joinToString("、") { "Java " + it }
        throw JavaRuntimeUnavailableException(
            if (available.isEmpty()) {
                "本机没有探测到任何 Java 运行时，而游戏要求 Java " + major + "；自动下载运行时尚未实现。"
            } else {
                "本机没有 Java " + major + "，只有 " + available + "；自动下载运行时尚未实现。"
            },
        )
    }

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
        env("APPDATA")?.let { roots += File(it, ".minecraft").path }
        env("APPDATA")?.let { roots += File(it, "PCL").path }
        env("APPDATA")?.let { roots += File(it, "HMCL").path }
        env("LOCALAPPDATA")?.let { roots += File(it, "PCL").path }
        env("PLATYPUS_JAVA_DIR")?.let { roots += it }
        val names = listOf("runtime", "java", "jre", "jres", "java-runtimes", "javas")
        val dirs = LinkedHashSet<String>()
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
