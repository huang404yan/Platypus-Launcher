package io.github.huang0yan4.platypus.core.launch

import io.github.huang0yan4.platypus.core.auth.Session
import io.github.huang0yan4.platypus.core.instance.Instance
import io.github.huang0yan4.platypus.core.runtime.JavaRuntime

data class LaunchRequest(
    val instance: Instance,
    val java: JavaRuntime,
    val session: Session,
    val jvmArgs: List<String> = emptyList(),
    val gameArgs: List<String> = emptyList(),
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
