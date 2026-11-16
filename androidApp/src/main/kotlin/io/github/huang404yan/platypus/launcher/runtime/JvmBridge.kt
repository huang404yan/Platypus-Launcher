package io.github.huang404yan.platypus.launcher.runtime

import android.util.Log
import io.github.huang404yan.platypus.core.launch.AndroidLaunchPreparation
import java.io.File

/**
 * 把 [AndroidLaunchPreparation] 算出来的参数表交给 native 层，在那里造一个 JVM 并把游戏跑起来。
 *
 * 为什么 JVM 一定要在这层起：Android 应用自己跑在 ART 上，游戏的每一行代码要跑在 HotSpot 上，
 * 而造 JVM 的唯一入口 `JNI_CreateJavaVM` 是个 C 符号 —— Java 侧没有任何办法把第二个 JVM 放进本进程。
 *
 * 启动之后 [start] 会在调用线程上一直跑到游戏退出（游戏的 main 是个死循环，这没法改成异步）。
 * 所以调用方必须把它扔进后台线程，界面线程只负责显示。
 */
object JvmBridge {

    private const val TAG = "PlatypusJvm"

    /** 这一层是不是真的编进来了。没编进来时所有调用都返回一句「这个包不带 JNI」，而不是崩。 */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("platypus-jvm") }.isSuccess
    }

    /** 上一次加载失败的原文。界面把它显示出来，比只说「不可用」有用得多。 */
    val loadFailure: String by lazy {
        runCatching { System.loadLibrary("platypus-jvm") }.exceptionOrNull()
            ?.let { it.message ?: it.javaClass.simpleName }
            .orEmpty()
    }

    /**
     * 起 JVM 并跑游戏主类。**阻塞到游戏退出**，必须在后台线程调用。
     *
     * 返回 [JvmStartResult]：成功是 [JvmStartResult.Ok]，失败带一句能照着改的中文说明。
     */
    fun start(prep: AndroidLaunchPreparation): JvmStartResult {
        if (!available) {
            return JvmStartResult.Failed(
                "这个安装包没带 JNI 层（libplatypus-jvm.so 加载失败：" +
                    loadFailure.ifBlank { "原因未知" } + "）。" +
                    "要么是装的 APK 与手机架构不匹配，要么是打包时漏了这个库。"
            )
        }

        val plan = prep.plan
            ?: return JvmStartResult.Failed("版本文件读不出来，先把游戏文件下全再来。")
        if (!prep.ready) {
            val why = prep.blocked.joinToString("；") { it.label + "：" + it.detail }
            return JvmStartResult.Failed("启动准备没通过：" + why)
        }

        val libDir = File(prep.env.javaHome, "lib")
        val jvmFile = findLibJvm(libDir)
            ?: return JvmStartResult.Failed(
                "在 " + libDir.absolutePath + " 里找不到 libjvm.so —— 这份运行时是残缺的，" +
                    "去「Java 运行时」里重装一次。"
            )

        Log.i(TAG, "启动 " + plan.command.mainClass + "，参数 " + prep.fullCommand.size + " 条")
        val code = nativeStart(
            libDir.absolutePath,
            jvmFile,
            prep.fullCommand.toTypedArray(),
            plan.command.mainClass,
            plan.command.gameArgs.toTypedArray(),
        )
        if (code == 0) return JvmStartResult.Ok

        val detail = runCatching { nativeLastError() }.getOrDefault("")
        return JvmStartResult.Failed(describe(code) + if (detail.isBlank()) "" else "\n\n详情：" + detail)
    }

    /** 在 lib 目录里找 libjvm.so。有的运行时铺在 lib/ 下，有的在 lib/server/ 下。 */
    private fun findLibJvm(libDir: File): String? {
        if (File(libDir, "libjvm.so").isFile) return "libjvm.so"
        if (File(libDir, "server/libjvm.so").isFile) return "server/libjvm.so"
        return null
    }

    /** 返回码翻人话。负数与 native 侧的宏一一对应，改一边必须改另一边。 */
    internal fun describe(code: Int): String = when (code) {
        CODE_OK -> "启动结束了。"
        CODE_ARGS -> "参数没传对：lib 目录、libjvm 文件名、主类名三个都不能为空。"
        CODE_NO_LIBJVM ->
            "没能加载 libjvm.so。最常见的原因是 Android 10 起禁止从可写目录 dlopen 原生库 —— " +
                "这台设备如果升到了那个版本，应用内起 JVM 这条路就走不通，只能等图形层与运行时的另一套做法。"
        CODE_NO_ENTRY -> "libjvm.so 里没有 JNI_CreateJavaVM，它不是一份完整的 JVM。"
        CODE_CREATE_FAILED -> "JNI_CreateJavaVM 失败了，详情见下面与 logcat。"
        CODE_NO_MAIN_CLASS -> "JVM 起来了，但 classpath 里找不到游戏主类。检查客户端 jar 与版本文件里的 mainClass。"
        CODE_NO_MAIN_METHOD -> "主类上没有 public static void main(String[])。"
        CODE_MAIN_THREW -> "游戏主类抛出了异常（完整堆栈在 logcat 里）。"
        CODE_NO_MEMORY -> "内存不够，参数表都拼不出来。"
        else -> "JNI 层返回了未知错误码 " + code + "。"
    }

    private const val CODE_OK = 0
    private const val CODE_ARGS = -1
    private const val CODE_NO_LIBJVM = -2
    private const val CODE_NO_ENTRY = -3
    private const val CODE_CREATE_FAILED = -4
    private const val CODE_NO_MAIN_CLASS = -5
    private const val CODE_NO_MAIN_METHOD = -6
    private const val CODE_MAIN_THREW = -7
    private const val CODE_NO_MEMORY = -8

    private external fun nativeStart(
        libDir: String,
        jvmFile: String,
        options: Array<String>,
        mainClass: String,
        appArgs: Array<String>,
    ): Int

    private external fun nativeLastError(): String
}

/** 起 JVM 的结果。成功时 [Ok]，因为之后游戏一直在跑，没有更多东西要报。 */
sealed interface JvmStartResult {
    data object Ok : JvmStartResult
    data class Failed(val message: String) : JvmStartResult
}
