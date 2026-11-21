package io.github.huang404yan.platypus.launcher.preview

import io.github.huang404yan.platypus.core.launch.AndroidGraphicsBundle
import io.github.huang404yan.platypus.core.launch.AndroidGraphicsInstaller
import io.github.huang404yan.platypus.core.launch.AndroidGraphicsProgress
import io.github.huang404yan.platypus.core.launch.megabytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图形后端下载的进度状态，字段含义同 [JavaInstallState]。
 */
internal data class GraphicsInstallState(
    val busy: Boolean = false,
    val progress: AndroidGraphicsProgress? = null,
    val message: String = "",
    val failed: Boolean = false,
    /** 每完成一次（成功或失败）都变一次，页面据此重扫盘上的文件。 */
    val doneAt: Long = 0L,
    /** 最近一次装的是哪一套（后端 + ABI），失败时也用来说清「哪一套没装上」。 */
    val doneBundle: String = "",
)

/**
 * 进程级的图形后端下载任务，理由同 [JavaInstallRunner]。
 *
 * 一套十几到四十兆，走的是和 JRE 同一条镜像链路。切页回来还能看到进度；
 * 中途被打断时已经下完的文件留在盘上，再点一次会接着下。
 */
internal object GraphicsInstallRunner {

    private val _state = MutableStateFlow(GraphicsInstallState())
    val state: StateFlow<GraphicsInstallState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private fun labelOf(bundle: AndroidGraphicsBundle): String =
        bundle.renderer.label + "（" + bundle.abi + "）"

    fun start(nativeDir: File, bundle: AndroidGraphicsBundle) {
        if (_state.value.busy) return
        val label = labelOf(bundle)
        _state.value = GraphicsInstallState(
            busy = true,
            message = "正在下载 " + label + " 的图形后端（约 " + megabytes(bundle.totalBytes) + "）…",
            doneBundle = label,
        )
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AndroidGraphicsInstaller.install(nativeDir, bundle) { progress ->
                        _state.update { it.copy(progress = progress, message = progress.step + " " + progress.detail) }
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        message = if (result.ok) {
                            label + " 装好了：" + result.nativeDir.absolutePath
                        } else {
                            label + " 没装齐，缺的是：" + result.badFiles.joinToString("、") +
                                "。再点一次下载会补上；一直这样说明上游改过这几个文件。"
                        },
                        failed = !result.ok,
                        doneAt = System.currentTimeMillis(),
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        message = "已取消。下了一半的文件留在盘上，再点一次会接着下。",
                        failed = false,
                        doneAt = System.currentTimeMillis(),
                    )
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        message = "装 " + label + " 失败：" + (e.message ?: e.toString()),
                        failed = true,
                        doneAt = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    fun cancel() {
        if (!_state.value.busy) return
        job?.cancel()
        job = null
    }
}
