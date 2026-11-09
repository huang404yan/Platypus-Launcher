package io.github.huang404yan.platypus.ui

import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.launch.GameRuntime
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeService

/**
 * 界面需要的后端能力集合。
 *
 * ui 模块只依赖 core 的接口；具体实现（桌面端的探测与进程管理）由 app-desktop 装配后注入，
 * 这样 ui 里不出现任何平台 API，也便于用假实现做预览或测试。
 */
interface LauncherBackend {
    val javaRuntimes: JavaRuntimeService
    val gameRuntime: GameRuntime

    /** 实例登记（本启动器建过 / 用户改过的实例，带内存与 Java 路径）。 */
    val instances: InstanceRepository
}
