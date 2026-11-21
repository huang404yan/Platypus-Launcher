package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.version.OsFamily
import java.io.File

/**
 * 安卓侧启动要的那几个路径与选择。
 *
 * 都在这里一次给全，是因为它们散在四个不同的地方：JRE 在应用私有目录、游戏目录在外部存储、
 * natives 与临时目录又要自己开 —— 让调用方一个个传，传错一个的表现是「进得去但崩在某一步」，
 * 而不是一句清楚的错误。
 */
data class AndroidLaunchConfig(
    /** 设备的主 ABI，如 arm64-v8a；决定 Zink 能不能选。 */
    val abi: String,
    /** `Build.VERSION.RELEASE`。 */
    val androidRelease: String = "",
    /** 图形后端。 */
    val renderer: AndroidRenderer = AndroidRenderer.DEFAULT,
    /** 临时目录（`-Djava.io.tmpdir`），调用方传 `context.cacheDir` 那一类。 */
    val tmpDir: File,
    /** natives 与渲染库解压到的目录。 */
    val nativeDir: File,
    /** `resolv.conf`；没有就传 null。 */
    val resolvConf: File? = null,
)

/**
 * 一次安卓启动的准备结果。
 *
 * [fullCommand] 只在这一层验证得通的时候才有内容：它已经是可以交给应用内 JVM 的参数表
 * （`JVM_CreateJavaVM` 的 args）。**这一层不负责真正把 JVM 起起来** —— 那要 JNI。
 */
data class AndroidLaunchPreparation(
    val env: AndroidLaunchEnv,
    /** 六条自检（游戏目录 / Java 运行时 / 图形后端 / 渲染库 / 临时目录 / 原生库目录）。 */
    val checks: List<AndroidLaunchArgs.Check>,
    /** 自检没过的那几条；空表才说明这一层齐了。 */
    val blocked: List<AndroidLaunchArgs.Check>,
    /** 实例里手写的额外 JVM 参数被筛过之后的结果。 */
    val sanitized: AndroidLaunchArgs.SanitizeResult,
    /** 拼好的启动计划；版本文件读不出来时为 null。 */
    val plan: LaunchPlan?,
    /** 交给 JVM 的完整参数表；没准备好时为空表。 */
    val fullCommand: List<String>,
    /** 值得让用户看见的话（自检未过、参数被丢、直连降级之类）。 */
    val notes: List<String>,
) {
    /** 参数与环境这一层准备好了没有。 */
    val ready: Boolean get() = blocked.isEmpty() && plan != null

    /** 一句能直接显示的话。 */
    val summary: String
        get() = when {
            plan == null -> "版本文件读不出来，先把游戏文件下全。"
            blocked.isNotEmpty() -> blocked.joinToString("；") { it.label + "：" + it.detail }
            else -> "启动准备完成：" + (plan.command.mainClass) + "，共 " + fullCommand.size + " 个参数。"
        }
}

/**
 * 把「一次安卓启动」翻译成应用内 JVM 要的参数表。
 *
 * ## 这一层为什么存在
 *
 * 桌面端与安卓端前面的步骤一模一样（读版本 json、合继承链、按规则挑库、拼参数），
 * 差别只在最后一步：桌面 `ProcessBuilder` 起进程，安卓要在同一个进程里 `JVM_CreateJavaVM`。
 * 所以能验的部分全放在这里，只有那一步留给 JNI —— 这样绝大部分错都能在单测里抓到，
 * 而不是等到真机上「点了启动闪一下」。
 *
 * ## 它不做什么
 *
 * - 不起进程、不加载 libjvm.so（那要 JNI）。
 * - 不解压 natives（[prepare] 是纯读盘函数，解压交给调用方拿 [AndroidLaunchPreparation.plan] 去做）。
 * - 不下载图形后端：那几份 .so 不随安装包发，由 [AndroidGraphicsInstaller] 按需下到实例的
 *   natives 目录里；这里只按盘上的实际情况判「渲染库文件」在不在。
 */
object AndroidLaunchPlanner {

    /**
     * 算一次完整的启动准备。纯读盘、不联网、不起任何东西。
     *
     * 顺序是刻意的：**先算自检，再拼命令行**。自检没过时不做无用的拼接，
     * 免得日志里出现一张看起来很正常、实际上永远跑不起来的参数表。
     */
    fun prepare(request: LaunchRequest, config: AndroidLaunchConfig): AndroidLaunchPreparation {
        val layout = InstanceLayouts.of(request.instance)
        val env = AndroidLaunchEnv(
            gameDir = layout.gameDir,
            javaHome = javaHomeOf(request),
            nativeDir = config.nativeDir,
            tmpDir = config.tmpDir,
            resolvConf = config.resolvConf,
            androidRelease = config.androidRelease,
        )
        val checks = AndroidLaunchArgs.checks(env, config.renderer, config.abi)
        val blocked = AndroidLaunchArgs.blockers(checks)

        // 实例里手写的额外参数先筛一遍：-Xmx 这类进了安卓会与「最大内存」打架。
        val sanitized = AndroidLaunchArgs.sanitize(request.instance.extraJvmArgs + request.jvmArgs)
        val notes = mutableListOf<String>()
        sanitized.dropped.forEach { notes += "忽略了 " + it.arg + "：" + it.reason }

        val plan = runCatching {
            LaunchPlanner.plan(
                request = request.copy(
                    instance = request.instance.copy(extraJvmArgs = sanitized.kept),
                    jvmArgs = emptyList(),
                ),
                os = OsFamily.ANDROID,
                arch = config.abi,
            )
        }.getOrNull()
        if (plan == null) {
            notes += "版本文件读不出来：" + layout.versionDir.name + ".json"
        } else {
            notes += plan.notes
        }

        val androidArgs = AndroidLaunchArgs.of(env, config.renderer)
        val fullCommand = if (plan == null) emptyList() else androidArgs + plan.command.toArgumentList()
        return AndroidLaunchPreparation(
            env = env,
            checks = checks,
            blocked = blocked,
            sanitized = sanitized,
            plan = plan,
            fullCommand = fullCommand,
            notes = notes,
        )
    }

    /**
     * 从「选好的 Java」反推它的 home。
     *
     * [io.github.huang404yan.platypus.core.runtime.JavaRuntime.path] 指的是可执行文件
     * （`<home>/bin/java`），JVM 自己是靠它反推 home 的 —— 而在安卓上它是被 dlopen 进来的，
     * 反推不出来，所以必须由我们显式给 `-Djava.home`。
     */
    fun javaHomeOf(request: LaunchRequest): File {
        val exe = File(request.java.path)
        return exe.parentFile?.parentFile ?: exe.parentFile ?: exe
    }
}
