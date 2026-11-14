package io.github.huang404yan.platypus.ui

import io.github.huang404yan.platypus.core.install.ServerRepository
import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.launch.GameRuntime
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeService
import io.github.huang404yan.platypus.core.settings.SettingsRepository

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

    /**
     * 启动器设置（离线用户名、默认游戏目录、默认内存）。
     *
     * 给默认值 null 是为了让只关心实例与运行时的假实现（预览、单测）不用跟着改；
     * 生产装配（DesktopServices）一定会提供，界面在 null 时退回内置默认值。
     */
    val settings: SettingsRepository? get() = null

    /**
     * 服务端登记（「下载 / 服务端」页装过的那些）。
     *
     * 同样给默认 null：服务端不进实例列表，不给它也不影响其它页面。
     */
    val servers: ServerRepository? get() = null
}
