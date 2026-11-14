package io.github.huang404yan.platypus.launcher.preview

import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.mods.ModInstallProgress
import io.github.huang404yan.platypus.core.mods.ModInstaller
import io.github.huang404yan.platypus.core.packs.PackInstallProgress
import io.github.huang404yan.platypus.core.packs.PackInstaller
import io.github.huang404yan.platypus.core.packs.PackKind
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
 * 模组安装的进度状态。
 *
 * [doneAt] 是「这一次装完了」的时间戳，页面拿它当重扫的触发键：
 * 页面的 mods/ 清单是页面自己的状态，runner 发布完了也不知道界面还在不在，
 * 所以只报一个时间戳，页面回来了自己决定要不要重扫。
 */
internal data class ModInstallState(
    val busySlug: String = "",
    val title: String = "",
    val progress: ModInstallProgress? = null,
    val message: String = "",
    val failed: Boolean = false,
    val doneAt: Long = 0L,
) {
    val busy: Boolean get() = busySlug.isNotBlank()
}

/**
 * 进程级的模组安装任务：切页、切 tab、回到桌面都不会打断它，只有杀进程才停。
 *
 * 与桌面的 [io.github.huang404yan.platypus.ui.OneClickRunner] 同一个理由：
 * 页面里 rememberCoroutineScope() 起的协程会随页面离开组合树被取消，
 * 装到一半切去「实例」页，下载就断在原地，回来只剩一句半截进度。
 *
 * 同一时间只跑一个：正在装时再点别的安装按钮直接忽略，不去抢盘。
 */
internal object ModInstallRunner {

    private val _state = MutableStateFlow(ModInstallState())
    val state: StateFlow<ModInstallState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start(
        instanceDir: File,
        instanceName: String,
        slug: String,
        title: String,
        gameVersion: String,
        loader: LoaderKind,
    ) {
        if (_state.value.busy) return
        _state.value = ModInstallState(busySlug = slug, title = title)
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ModInstaller.install(
                        gameDir = instanceDir,
                        slug = slug,
                        gameVersion = gameVersion.trim(),
                        loader = loader,
                    ) { p: ModInstallProgress -> _state.update { it.copy(progress = p) } }
                }
                val extra = if (result.requiredDependencies.isEmpty()) {
                    "。"
                } else {
                    "。还差 " + result.requiredDependencies.size + " 个前置模组，需要自己再装。"
                }
                _state.update {
                    it.copy(
                        busySlug = "",
                        progress = null,
                        message = "已装进 " + instanceName + "：" + result.entry.title + " " +
                            result.version + "（" + fmtBytes(result.downloadedBytes) + "）" + extra,
                        failed = false,
                        doneAt = System.currentTimeMillis(),
                    )
                }
            } catch (e: CancellationException) {
                // 取消是用户点的，不是故障：如实说出来，别留在「正在装」。
                _state.update {
                    it.copy(
                        busySlug = "",
                        progress = null,
                        message = "已取消安装 " + title + "。已经下完的文件留在盘上，再点一次会跳过校验通过的部分。",
                        failed = false,
                        doneAt = System.currentTimeMillis(),
                    )
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busySlug = "",
                        progress = null,
                        message = "装 " + title + " 失败：" + (e.message ?: e.toString()),
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

    /** 校验没过时把话说清楚；不动正在跑的任务。 */
    fun hint(text: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(message = text, failed = true)
    }
}

/** 光影 / 资源包安装的进度状态，字段含义同 [ModInstallState]。 */
internal data class PackInstallState(
    val busySlug: String = "",
    val title: String = "",
    val progress: PackInstallProgress? = null,
    val message: String = "",
    val failed: Boolean = false,
    val doneAt: Long = 0L,
) {
    val busy: Boolean get() = busySlug.isNotBlank()
}

/** 进程级的光影 / 资源包安装任务，理由同 [ModInstallRunner]。 */
internal object PackInstallRunner {

    private val _state = MutableStateFlow(PackInstallState())
    val state: StateFlow<PackInstallState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start(
        instanceDir: File,
        instanceName: String,
        slug: String,
        title: String,
        gameVersion: String?,
        kind: PackKind,
    ) {
        if (_state.value.busy) return
        _state.value = PackInstallState(busySlug = slug, title = title)
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    PackInstaller.install(instanceDir, slug, gameVersion, kind) { p: PackInstallProgress ->
                        _state.update { it.copy(progress = p) }
                    }
                }
                _state.update {
                    it.copy(
                        busySlug = "",
                        progress = null,
                        message = "已装进 " + instanceName + "：" + result.entry.fileName + "（" +
                            result.version + "，" + fmtBytes(result.downloadedBytes) + "）",
                        failed = false,
                        doneAt = System.currentTimeMillis(),
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        busySlug = "",
                        progress = null,
                        message = "已取消安装 " + title + "。已经下完的文件留在盘上，再点一次会跳过校验通过的部分。",
                        failed = false,
                        doneAt = System.currentTimeMillis(),
                    )
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busySlug = "",
                        progress = null,
                        message = "装 " + title + " 失败：" + (e.message ?: e.toString()),
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
}
