package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.auth.Session
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.runtime.JavaRuntime

data class LaunchRequest(
    val instance: Instance,
    val java: JavaRuntime,
    val session: Session,
    val jvmArgs: List<String> = emptyList(),
    val gameArgs: List<String> = emptyList(),
    /**
     * 多人游戏「直接连接」的目标，形如 `192.168.1.10:54321`；空串 = 正常启动，停在主菜单。
     *
     * 1.20+ 拼成 `--quickPlayMultiplayer`（桌面实现会顺手打开
     * [LaunchArguments.QUICK_PLAY_MULTIPLAYER_FEATURE]，否则那条参数根本不会被拼进去）；
     * 1.19 及更早的版本 json 没有这条规则，自动降级成 `--server` + `--port`。
     * 哪个走哪条由 [LaunchArguments.directConnectMode] 决定。
     */
    val quickPlayServer: String = "",
    /**
     * 这次进游戏用哪个语言，写进 options.txt 的 `lang:` 那一行。
     *
     * 空串 = 设置里的「跟随系统」：由 [GameLanguage.resolve] 映射成本机系统语言再写。
     * 换句话说进游戏前一定会把这一行摆成「这次要用的语言」，不管设置里那一格是不是空的
     * —— 早先空串被当成「不插手」，结果选了「跟随系统」反而什么都不写，看起来像没生效。
     */
    val gameLanguage: String = "",
)

sealed interface LaunchEvent {
    data class Stage(val name: String, val fraction: Float) : LaunchEvent
    data class Started(val pid: Long) : LaunchEvent
    data class LogLine(val text: String) : LaunchEvent
    data class Exited(val code: Int, val diagnosis: String? = null) : LaunchEvent
}

interface GameHandle {
    val pid: Long?
    fun isAlive(): Boolean
    suspend fun stop(graceMillis: Long = 5000): Boolean
}

/**
 * 运行 Minecraft 的抽象。
 * 桌面实现 = 起一个 java 进程；Android 实现 = 应用内 JVM + 图形转译。
 * 上层 core 只认这个接口，两端共享逻辑。
 */
interface GameRuntime {
    suspend fun launch(request: LaunchRequest, onEvent: (LaunchEvent) -> Unit): GameHandle
}
