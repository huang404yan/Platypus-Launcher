package io.github.huang404yan.platypus.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 安卓端的 [JavaRuntimeService] 实现。
 *
 * ## 它做什么，不做什么
 *
 * 做：认盘上已经下好的那几套 FCL JRE（见 [AndroidJavaCatalog]），报给上层；
 *      用户点「要 Java 17」时把缺的那套下下来。
 * 不做：起 JVM。那要 `JNI_CreateJavaVM` 与 OpenGL 转译，属于 runtime-android 模块，
 *      和这里没有依赖关系 —— 本类只解决「启动器知道哪份 JRE 在哪、是几版」。
 *
 * ## 为什么判断版本不跑 `java -version`
 *
 * 桌面端是跑一次 java 看它自报（[JavaProbeParser]）。安卓上不行：应用内 JVM
 * 只允许存在一个，而且 Android 10 起共享存储里的可执行文件根本 exec 不了。
 * 所以这里读 JRE 目录里的 `release` 文件（[JavaReleaseFile]），纯文本、零副作用。
 *
 * ## 为什么还要查 libjvm.so
 *
 * [AndroidJavaInstaller.isInstalled] 只查 `bin/java` 与 `release` 两个文件在不在，
 * 那是「解包完整」的判据。但「能用来起 JVM」还差一样：`libjvm.so`。它在下层
 * tar 里独立成目录，解包中途断掉完全可能两个标志文件都在、`libjvm.so` 没了。
 * 那种目录报成「已装」的话，会在启动那一刻才炸，而用户看到的只是一个闪退。
 * 所以 [detect] 用的是更严的 [canLaunch]。
 */
class AndroidJavaRuntimeService(
    /** 应用私有目录下的 java 根，通常是 `filesDir/java`。 */
    private val javaRoot: File,
    /** 设备主 ABI，来自 `Build.SUPPORTED_ABIS`；null 表示这台机器没有对应的 JRE 包。 */
    private val abi: String?,
    private val installer: AndroidJreInstaller = DefaultAndroidJreInstaller,
) : JavaRuntimeService {

    /** 已经装好、看起来能用的那几套，按主版本升序。 */
    override suspend fun detect(): List<JavaRuntime> = withContext(Dispatchers.IO) {
        installedRuntimes()
    }

    override suspend fun ensure(major: Int, onProgress: (Float) -> Unit): JavaRuntime =
        ensureRuntime(major, null, onProgress)

    /**
     * 先看盘上有没有，没有再下。
     *
     * 与桌面端同一条策略：**只认完全相等的主版本**（[findExactMajor]），
     * 不拿高版本顶替 —— 1.16.5 这类老版本在 Java 16+ 上直接崩，顶替只会把
     * 「装一个 Java 8」变成「启动就闪退」。
     */
    override suspend fun ensureRuntime(
        major: Int,
        component: String?,
        onProgress: (Float) -> Unit,
    ): JavaRuntime {
        detect().findExactMajor(major)?.let { return it }
        return download(major, component, onProgress)
    }

    /**
     * 能下哪些版本 + 已装状态。
     *
     * 与桌面端的差别：这里不问「本机装了哪些 Java」，因为安卓上不存在系统 Java，
     * 只有我们自己的 [AndroidJavaCatalog] 那四套。所以 catalog 就是「这四套里你下过哪些」。
     */
    override suspend fun catalog(): List<JavaRuntimeOffer> = withContext(Dispatchers.IO) {
        val device = abi ?: return@withContext emptyList()
        AndroidJavaCatalog.packages(device) { major ->
            val home = homeOf(device, major)
            if (canLaunch(home)) home.absolutePath else null
        }.map { offer ->
            JavaRuntimeOffer(
                component = componentOf(offer.majorVersion, offer.abi),
                majorVersion = offer.majorVersion,
                versionName = offer.label + "（" + offer.abi + "）",
                manifestSize = offer.totalBytes,
                installed = offer.installed,
                installedPath = offer.installedPath,
            )
        }
    }

    /**
     * 下并解压一套。
     *
     * 与桌面端 [JavaRuntimeService.download] 一致：**不先探测** —— 用户点了
     * 「下载 Java 21」，他要的就是启动器自己管的这一份，即使别处已有一份。
     */
    override suspend fun download(
        major: Int,
        component: String?,
        onProgress: (Float) -> Unit,
    ): JavaRuntime {
        val device = abi ?: throw JavaRuntimeUnavailableException(
            "这台设备的 CPU 架构没有对应的 Java 运行时（" +
                AndroidJavaCatalog.ABIS.joinToString("、") + " 才有）。",
        )
        val wanted = component?.let { parseComponent(it) }
        // component 里带了 ABI 且和本机不一致时以 component 为准：调用方明确指名了要哪个包。
        val deviceAbi = if (wanted?.second != null) wanted.second!! else device
        val offer = AndroidJavaCatalog.packages(deviceAbi) { null }
            .firstOrNull { it.majorVersion == major }
            ?: throw JavaRuntimeUnavailableException(
                "Java " + major + " 在 " + deviceAbi + " 上没有可用的包。" +
                    "能下的是 " + AndroidJavaCatalog.MAJORS.joinToString("、") + "。",
            )
        onProgress(0f)
        val home = try {
            installer.install(javaRoot, offer) { progress -> onProgress(progress.fraction) }
        } catch (e: Exception) {
            throw JavaRuntimeUnavailableException("下载 Java " + major + " 失败：" + (e.message ?: e.javaClass.simpleName))
        }
        onProgress(1f)
        return runtimeOf(home) ?: throw JavaRuntimeUnavailableException(
            "下完了但 " + home.absolutePath + " 里认不出版本号（release 文件缺失或读不动）。",
        )
    }

    /** 盘上所有通过 [canLaunch] 的 JRE。 */
    fun installedRuntimes(): List<JavaRuntime> {
        val dirs = javaRoot.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { runtimeOf(it) }
            .distinctBy { it.path }
            .sortedBy { it.majorVersion }
    }

    /**
     * 把一目录认成 [JavaRuntime]；不合格返回 null。
     *
     * 主版本优先读 `release`；读不出来时退回目录名 `jre<N>-<abi>`，
     * 因为 [AndroidJavaInstaller.homeOf] 就是那么起的名字。
     */
    fun runtimeOf(home: File): JavaRuntime? {
        if (!canLaunch(home)) return null
        val release = JavaReleaseFile.read(home)
        val major = JavaReleaseFile.majorOf(release) ?: majorFromDirName(home.name)
        if (major == null || major <= 0) return null
        return JavaRuntime(
            path = home.absolutePath,
            majorVersion = major,
            vendor = JavaReleaseFile.vendorOf(release),
            arch = JavaReleaseFile.archOf(release),
            bits = JavaReleaseFile.bitsOf(release),
            managed = true,
        )
    }

    private fun homeOf(device: String, major: Int): File =
        File(javaRoot, "jre" + major + "-" + device)

    companion object {

        /** component 前缀：与桌面的 Mojang 组件名（jre-legacy / gamma …）区分开。 */
        const val COMPONENT_PREFIX = "android-jre"

        /** `android-jre17-arm64-v8a`。 */
        fun componentOf(major: Int, abi: String): String = COMPONENT_PREFIX + major + "-" + abi

        /** 反解 [componentOf]；认不出返回 null。 */
        fun parseComponent(component: String): Pair<Int, String?>? {
            val rest = component.trim().removePrefix(COMPONENT_PREFIX)
            if (rest == component.trim()) return null
            val dash = rest.indexOf('-')
            if (dash <= 0) {
                return rest.toIntOrNull()?.let { it to null }
            }
            val major = rest.substring(0, dash).toIntOrNull() ?: return null
            val abi = rest.substring(dash + 1).takeIf { it.isNotBlank() }
            return major to abi
        }

        /**
         * 这套 JRE 是否具备「能起 JVM」的最低条件。
         *
         * 三样：`bin/java`（启动器）、`release`（版本信息）、`libjvm.so`
         * （真正的虚拟机）。前两样是 [AndroidJavaInstaller.isInstalled] 的口径，
         * 第三样是这里多查的，理由见类注释。
         */
        fun canLaunch(javaHome: File): Boolean {
            if (!AndroidJavaInstaller.isInstalled(javaHome)) return false
            return hasLibJvm(javaHome)
        }

        /** `libjvm.so` 在 lib/ 下某一层，Linux 构建通常在 `lib/server/`。 */
        fun hasLibJvm(javaHome: File): Boolean {
            val lib = File(javaHome, "lib")
            if (!lib.isDirectory) return false
            if (File(lib, "libjvm.so").isFile) return true
            return runCatching {
                lib.walkTopDown().maxDepth(3).any { it.isFile && it.name == "libjvm.so" }
            }.getOrDefault(false)
        }

        /** `jre17-arm64-v8a` → 17；`jre25-x86` → 25；认不出 null。 */
        fun majorFromDirName(name: String): Int? {
            val trimmed = name.trim()
            if (!trimmed.startsWith("jre")) return null
            val rest = trimmed.removePrefix("jre")
            val digits = rest.takeWhile { it.isDigit() }
            if (digits.isEmpty()) return null
            return digits.toIntOrNull()
        }
    }
}

/**
 * 真正去下包的那一层。
 *
 * 抽成接口是为了单测：默认实现要联网下四十多兆，跑一遍单测不该依赖 GitHub 通不通。
 */
fun interface AndroidJreInstaller {
    suspend fun install(
        targetRoot: File,
        offer: AndroidJavaPackage,
        onProgress: (AndroidJavaProgress) -> Unit,
    ): File
}

/** 生产实现：交给 [AndroidJavaInstaller]。 */
object DefaultAndroidJreInstaller : AndroidJreInstaller {
    override suspend fun install(
        targetRoot: File,
        offer: AndroidJavaPackage,
        onProgress: (AndroidJavaProgress) -> Unit,
    ): File = AndroidJavaInstaller.install(targetRoot, offer, onProgress)
}
