package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.version.OsFamily
import java.io.File

/**
 * 安卓上的图形后端。
 *
 * 手机没有 Windows 上那套 OpenGL 驱动，Minecraft 用的 LWJGL 直接调 GL 是调不通的，
 * 必须中间隔一层把 GL 调用翻译成手机能认的东西。三条路的能力差得很远：
 *
 * - [GL4ES]：默认。把 GL 3.x 调用降级翻译成 GLES 2.0，天花板就是 **GL 2.1**。
 *   1.16 及更早、以及不吃 GL 3+ 的模组能跑；1.17 之后原版就要 GL 3.2，用它会直接崩在
 *   「GL 版本不够」。好处是几乎任何手机都有 GLES 2.0。
 * - [ZINK]：把 GL 调用转成 Vulkan。能上 GL 4.x，主流模组与光影才有戏。
 *   代价是要求 Vulkan 驱动、要求系统够新，低端机/老驱动上开不起来。
 * - [LTW]：实验性的一层，能力介于两者之间，问题最多，只留个口子。
 *
 * **32 位设备只有 GL4ES**：libOSMesa.so（Zink 那层）只打了 arm64-v8a 与 x86_64 的包，
 * armeabi-v7a / x86 上根本没有这个文件，选了也是启动即失败。
 */
enum class AndroidRenderer(
    val id: String,
    val label: String,
    val libraryName: String,
    val note: String,
) {
    GL4ES(
        id = "gl4es",
        label = "GL4ES（稳妥，最高 GL 2.1）",
        libraryName = "libgl4es_114.so",
        note = "几乎任何手机都能开，但只能到 GL 2.1 —— 1.17 之后的原版要 GL 3.2，会崩在启动早期。",
    ),
    ZINK(
        id = "zink",
        label = "Zink（能上 GL 4.x，要 Vulkan）",
        libraryName = "libOSMesa.so",
        note = "走 Vulkan 转 GL，能吃 GL 3.2+ 与新模组；要求手机有可用的 Vulkan 驱动，且只有 64 位包。",
    ),
    LTW(
        id = "ltw",
        label = "LTW（实验）",
        libraryName = "libltw.so",
        note = "实验性的一层，问题最多，只在你已经知道自己在做什么时选。",
    ),
    ;

    /** 这套后端在这个 ABI 上有没有包。 */
    fun supportedOn(abi: String): Boolean = when (this) {
        GL4ES, LTW -> true
        // 只打了 64 位的包，32 位设备上文件都不存在。
        ZINK -> abi.trim().lowercase() in listOf("arm64-v8a", "aarch64", "x86_64", "amd64")
    }

    companion object {
        val DEFAULT: AndroidRenderer = GL4ES

        /** 按 id 取；认不出退回 [DEFAULT]（设置里存 id 字符串，与主题、下载源同一套做法）。 */
        fun of(raw: String): AndroidRenderer =
            entries.firstOrNull { it.id == raw.trim().lowercase() } ?: DEFAULT

        /** 这个 ABI 上能选的后端。 */
        fun supportedOn(abi: String): List<AndroidRenderer> = entries.filter { it.supportedOn(abi) }
    }
}

/**
 * 安卓侧启动需要的那几个路径。
 *
 * 与桌面端最大的不同：**没有「随便找一个 java 可执行文件」这回事**。JRE 只能装在应用私有
 * 目录（Android 10 起可写共享存储里的东西不可执行），游戏目录在外部存储，natives 在应用
 * 私有目录里另开一层 —— 三者在三个不同的地方，缺一个 JVM 就起不来。
 */
data class AndroidLaunchEnv(
    /** --gameDir，也是 -Duser.home：游戏往这里写存档与 options.txt。 */
    val gameDir: File,
    /** 解包好的 JRE 根目录（里面有 bin/、lib/、release）。 */
    val javaHome: File,
    /** 图形后端那几份 .so 解压到的目录，也是 -Djna.boot.library.path。 */
    val nativeDir: File,
    /** -Djava.io.tmpdir：默认的 /tmp 在安卓上不可写。 */
    val tmpDir: File,
    /** -Dext.net.resolvPath 要指的文件；null 表示不传（没这个文件时传了反而更糟）。 */
    val resolvConf: File? = null,
    /** Build.VERSION.RELEASE，拼进 -Dos.version。空串时只写 Android。 */
    val androidRelease: String = "",
)

/**
 * 安卓端启动 Minecraft 时必须手补的 JVM 参数。
 *
 * ## 为什么这些不能交给 version json 与 rules
 *
 * version json 的 rules 只认 os.name / os.arch / features，它能表达的是「这个库要不要进
 * classpath」。而安卓要补的是「这台机器上 JVM 自己看错了世界」：它以为自己跑在普通 Linux 上，
 * 于是去找 /tmp、去找系统装的 GL 库、用 POSIX_SPAWN 起子进程（安卓没有 jspawnhelper），
 * 每一条都会让游戏在**还没画出窗口**的时候就死掉。这些只能由启动器手写进去。
 *
 * ## 每一条为什么在这儿
 *
 * - -Djava.home：JVM 从自己的可执行文件路径反推 home，安卓上它是被 dlopen 进来的，推不出来。
 * - -Djava.io.tmpdir：默认 /tmp 在安卓上不可写，日志与纹理缓存第一个写不进去。
 * - -Djna.boot.library.path：JNA 在 libs/ 里找 libjnidispatch.so，指错就找不到。
 * - -Duser.home：不指的话游戏往不可写处写存档，表现为「进得去但存不了世界」。
 * - -Dos.name=Linux：**关键**。不伪装成标准 Linux，很多库会走 Android 分支去调
 *   android.* 的类，而那些在游戏进程里根本没有。
 * - -Dos.version=Android-<版本>：与上一条配套，让崩溃报告里能看出真实系统。
 * - -Dorg.lwjgl.opengl.libname：**换渲染后端就换这一条**，见 [AndroidRenderer]。
 * - -Dorg.lwjgl.vulkan.libname：Zink 那条路要它。
 * - -Dorg.lwjgl.freetype.libname：字体渲染，不指就崩在加载字体。
 * - -Dglfwstub.initEgl=false：GLFW 的安卓桩自己初始化 EGL 会和游戏的窗口抢，必须关掉。
 * - -Dext.net.resolvPath：安卓没有 /etc/resolv.conf，DNS 解析要靠这一条指过去。
 * - -Djdk.lang.Process.launchMechanism=FORK：**关键**。安卓没有 jspawnhelper，
 *   默认的 POSIX_SPAWN 直接失败 —— 表现为莫名其妙地「启动失败」但日志里什么都没有。
 * - -Dlog4j2.formatMsgNoLookups=true：Log4j2 那个著名的漏洞面，顺手关掉。
 * - -Dfml.earlyprogresswindow / -Dloader.disable_forked_guis：Forge 的早启动窗口与
 *   分叉 GUI 在安卓上开不出来，关掉才不会卡在一个永远不会出现的窗口上。
 */
object AndroidLaunchArgs {

    /** -Dos.name 的固定值。改它之前先读本对象的类注释。 */
    const val OS_NAME = "Linux"

    /** 要被丢掉的额外参数前缀。 */
    private val DROPPED_PREFIXES = listOf(
        "-Xms",
        "-Xmx",
        "-d32",
        "-d64",
        "-Xint",
        "-XX:+UseLargePages",
        "-XX:UseLargePages",
        "-XX:ActiveProcessorCount",
    )

    /**
     * 拼出安卓侧的 JVM 参数。
     *
     * 顺序无关紧要（都是 -D），但保持一致的好处是日志与诊断页能直接逐行比对。
     * [renderer] 决定 -Dorg.lwjgl.opengl.libname 指哪个 .so。
     */
    fun of(env: AndroidLaunchEnv, renderer: AndroidRenderer = AndroidRenderer.DEFAULT): List<String> {
        val nativeDir = env.nativeDir.absolutePath
        val androidVersion = if (env.androidRelease.isBlank()) "Android" else "Android-" + env.androidRelease.trim()
        return buildList {
            add("-Djava.home=" + env.javaHome.absolutePath)
            add("-Djava.io.tmpdir=" + env.tmpDir.absolutePath)
            add("-Djna.boot.library.path=" + nativeDir)
            add("-Duser.home=" + env.gameDir.absolutePath)
            add("-Dos.name=" + OS_NAME)
            add("-Dos.version=" + androidVersion)
            add("-Dorg.lwjgl.vulkan.libname=libvulkan.so")
            add("-Dorg.lwjgl.opengl.libname=" + renderer.libraryName)
            add("-Dorg.lwjgl.freetype.libname=" + File(nativeDir, "libfreetype.so").absolutePath)
            add("-Dglfwstub.initEgl=false")
            env.resolvConf?.let { add("-Dext.net.resolvPath=" + it.absolutePath) }
            add("-Djdk.lang.Process.launchMechanism=FORK")
            add("-Dlog4j2.formatMsgNoLookups=true")
            add("-Dfml.earlyprogresswindow=false")
            add("-Dloader.disable_forked_guis=true")
        }
    }

    /** 被丢掉的一条额外参数，以及丢它的理由（要显示给用户，不能让改动静默发生）。 */
    data class DroppedArg(val arg: String, val reason: String)

    /** [sanitize] 的结果：能留下的、被丢掉的。 */
    data class SanitizeResult(val kept: List<String>, val dropped: List<DroppedArg>)

    /**
     * 把用户手写的额外参数里「在安卓上必然起不来」的那几条挑出去。
     *
     * 这些在桌面上都有意义甚至是必需的，但在手机上：
     * - -Xms / -Xmx：内存由实例设置里的「最大内存」统一给（启动参数里我们自己会拼一条
     *   -Xmx<N>M），用户再写一条会把它顶掉；两条同时出现时 JVM 取**最后一条**，
     *   正好是用户那条没人再看过的数。
     * - -d32 / -d64：这是选 32/64 位虚拟机的开关，安卓上虚拟机是进程内嵌的，没有得选。
     * - -Xint：关掉 JIT，游戏能跑但只有个位数帧。
     * - -XX:+UseLargePages / -XX:ActiveProcessorCount：安卓的运行时里没有对应支持。
     *
     * 丢掉不是静默的：返回的 [SanitizeResult.dropped] 要显示出来。
     */
    fun sanitize(args: List<String>): SanitizeResult {
        val kept = mutableListOf<String>()
        val dropped = mutableListOf<DroppedArg>()
        for (raw in args) {
            val arg = raw.trim()
            if (arg.isEmpty()) continue
            val prefix = DROPPED_PREFIXES.firstOrNull { arg.startsWith(it, ignoreCase = true) }
            when {
                prefix == null -> kept += arg
                prefix == "-Xms" || prefix == "-Xmx" -> dropped += DroppedArg(
                    arg = arg,
                    reason = "内存由实例设置里的「最大内存」统一决定，这一条会让两边打架。",
                )
                prefix == "-d32" || prefix == "-d64" -> dropped += DroppedArg(
                    arg = arg,
                    reason = "安卓上没有得选 32/64 位虚拟机，这一条没有作用。",
                )
                prefix == "-Xint" -> dropped += DroppedArg(
                    arg = arg,
                    reason = "关掉 JIT 之后游戏会卡到不能玩。",
                )
                else -> dropped += DroppedArg(
                    arg = arg,
                    reason = "这一条在安卓的运行时上没有对应支持。",
                )
            }
        }
        return SanitizeResult(kept = kept, dropped = dropped)
    }

    /** 自检的一条结果。[ok] 为 false 时这条会挡住启动。 */
    data class Check(val label: String, val detail: String, val ok: Boolean)

    /**
     * 启动前自检：把「缺什么」一条条列出来，而不是等到点了启动才闪退。
     *
     * 判据都只看文件在不在，不解包、不联网、不跑任何东西 —— 这一层要能随时调。
     * [abi] 留空表示不校验「这个后端在这个 ABI 上有没有包」。
     */
    fun checks(
        env: AndroidLaunchEnv,
        renderer: AndroidRenderer = AndroidRenderer.DEFAULT,
        abi: String = "",
    ): List<Check> {
        val checks = mutableListOf<Check>()
        checks += Check(
            label = "游戏目录",
            detail = if (env.gameDir.isDirectory) env.gameDir.absolutePath else "不存在：" + env.gameDir.absolutePath,
            ok = env.gameDir.isDirectory,
        )
        val release = File(env.javaHome, "release")
        val libJvm = File(env.javaHome, "lib/libjvm.so").isFile || File(env.javaHome, "lib/server/libjvm.so").isFile
        checks += Check(
            label = "Java 运行时",
            detail = when {
                !env.javaHome.isDirectory -> "没装：" + env.javaHome.absolutePath
                !release.isFile -> "目录在，但没有 release 文件，多半是解包断在一半：" + env.javaHome.absolutePath
                !libJvm -> "找不到 libjvm.so，这份运行时是残缺的：" + env.javaHome.absolutePath
                else -> env.javaHome.absolutePath
            },
            ok = env.javaHome.isDirectory && release.isFile && libJvm,
        )
        checks += Check(
            label = "图形后端",
            detail = when {
                abi.isBlank() -> renderer.label
                renderer.supportedOn(abi) -> renderer.label + "（" + abi + " 上有这个包）"
                else -> renderer.label + " 在 " + abi + " 上没有对应的库，换 GL4ES。"
            },
            ok = abi.isBlank() || renderer.supportedOn(abi),
        )
        val rendererLib = File(env.nativeDir, renderer.libraryName)
        checks += Check(
            label = "渲染库文件",
            detail = if (rendererLib.isFile) rendererLib.absolutePath else "还没解出来：" + rendererLib.absolutePath,
            ok = rendererLib.isFile,
        )
        checks += Check(
            label = "临时目录",
            detail = if (env.tmpDir.isDirectory) env.tmpDir.absolutePath else "不存在，启动前要建出来：" + env.tmpDir.absolutePath,
            ok = env.tmpDir.isDirectory,
        )
        checks += Check(
            label = "原生库目录",
            detail = if (env.nativeDir.isDirectory) env.nativeDir.absolutePath else "不存在：" + env.nativeDir.absolutePath,
            ok = env.nativeDir.isDirectory,
        )
        return checks
    }

    /** 自检里没过的那些；空表表示「参数与环境这一层已经齐了」。 */
    fun blockers(checks: List<Check>): List<Check> = checks.filterNot { it.ok }

    /**
     * 安卓端应该用哪个 [OsFamily] 去求版本 json 的 rules。
     *
     * 单独给个函数而不是让调用方自己写 OsFamily.ANDROID：这个值意味着
     * 「按 Linux 的规则挑库」，是容易被改错的地方（改成按 android 比会一份 natives 都挑不出来）。
     */
    fun osFamily(): OsFamily = OsFamily.ANDROID
}
