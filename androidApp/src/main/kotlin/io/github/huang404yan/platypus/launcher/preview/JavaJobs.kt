package io.github.huang404yan.platypus.launcher.preview

import io.github.huang404yan.platypus.core.runtime.AndroidJavaInstaller
import io.github.huang404yan.platypus.core.runtime.AndroidJavaPackage
import io.github.huang404yan.platypus.core.runtime.AndroidJavaProgress
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
 * 安卓 Java 下载 + 解包的进度状态，字段含义同 [ModInstallState]。
 */
internal data class JavaInstallState(
    val busy: Boolean = false,
    val progress: AndroidJavaProgress? = null,
    val message: String = "",
    val failed: Boolean = false,
    val doneAt: Long = 0L,
    /** 装好的那个 jre 目录（绝对路径）；没装成为空串。 */
    val donePath: String = "",
)

/**
 * 进程级的 Java 下载任务，理由同 [ModInstallRunner]。
 *
 * 这事比装模组慢得多（两份 tar.xz 加解包，四五十兆），更要命的是解包一旦被打断，
 * 目标目录里就是半套 JRE。所以进度由这里持有，页面切走回来还能看到；
 * 真被打断（杀进程）时留下的是 .tmp- 临时目录，下次装会整个重来，不会把半套当成装好了。
 */
internal object JavaInstallRunner {

    private val _state = MutableStateFlow(JavaInstallState())
    val state: StateFlow<JavaInstallState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start(javaRoot: File, offer: AndroidJavaPackage) {
        if (_state.value.busy) return
        _state.value = JavaInstallState(
            busy = true,
            message = "正在下载 " + offer.label + "（约 " + mbOf(offer.totalBytes) + "）…",
        )
        job = scope.launch {
            try {
                val home = withContext(Dispatchers.IO) {
                    AndroidJavaInstaller.install(javaRoot, offer) { p: AndroidJavaProgress ->
                        _state.update { it.copy(progress = p, message = p.detail.ifBlank { p.step }) }
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        progress = null,
                        message = offer.label + " 装好了：" + home.absolutePath,
                        failed = false,
                        doneAt = System.currentTimeMillis(),
                        donePath = home.absolutePath,
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
                        message = "装 " + offer.label + " 失败：" + (e.message ?: e.toString()),
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

    fun hint(text: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(message = text, failed = true)
    }

    /** 只用来在提示里写「约 40 MB」，四舍五入到整数兆。 */
    private fun mbOf(bytes: Long): String =
        if (bytes <= 0L) "未知大小" else ((bytes + 1024L * 1024L - 1) / (1024L * 1024L)).toString() + " MB"
}
