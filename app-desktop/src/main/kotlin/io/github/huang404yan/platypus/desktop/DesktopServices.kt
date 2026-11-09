package io.github.huang404yan.platypus.desktop

import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.launch.GameRuntime
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeService
import io.github.huang404yan.platypus.desktop.runtime.DesktopGameRuntime
import io.github.huang404yan.platypus.desktop.runtime.DesktopJavaRuntimeService
import io.github.huang404yan.platypus.ui.LauncherBackend
import java.io.File

/**
 * 桌面侧的服务装配（组合根）。
 *
 * ui 模块只认 core 里的接口，具体实现由这里创建；整个程序只有 [create] 一处装配点。
 * 刻意不在 Composable 里 new：DesktopJavaRuntimeService 持有探测缓存、DesktopGameRuntime 持有
 * 读日志用的 CoroutineScope，跟着重组重建会漏进程、漏协程。
 */
class DesktopServices private constructor(
    /** Java 运行时探测。 */
    override val javaRuntimes: JavaRuntimeService,
    /** 起游戏进程。 */
    override val gameRuntime: GameRuntime,
    /** 实例登记。 */
    override val instances: InstanceRepository,
) : LauncherBackend {
    companion object {
        /**
         * @param gameRoot 当前游戏目录，可为 null。探测 Java 时会连带扫它的 runtime/ 子目录
         *   （PCL、HMCL、官方启动器把托管运行时都放在这里）；实例登记也存在它的 platypus/ 下。
         */
        fun create(gameRoot: File?): DesktopServices = create(gameRoot, null)

        /**
         * @param instanceIndex 实例索引文件；null 时按 gameRoot 推导，gameRoot 也为 null 时
         *   退到用户目录下的 platypus/instances.json（先能存下来，之后用户指了目录再迁移）。
         */
        fun create(gameRoot: File?, instanceIndex: File?): DesktopServices = DesktopServices(
            javaRuntimes = DesktopJavaRuntimeService(gameRoot = gameRoot),
            gameRuntime = DesktopGameRuntime(),
            instances = JsonInstanceRepository(instanceIndex ?: defaultInstanceIndex(gameRoot)),
        )

        private fun defaultInstanceIndex(gameRoot: File?): File {
            if (gameRoot != null) return JsonInstanceRepository.defaultIndexFile(gameRoot)
            val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
            return JsonInstanceRepository.defaultIndexFile(File(home, ".platypus"))
        }
    }
}
