package io.github.huang404yan.platypus.ui

import io.github.huang404yan.platypus.core.instance.InstanceRepository
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.install.InstallOutcome
import io.github.huang404yan.platypus.core.install.InstallStep
import io.github.huang404yan.platypus.core.install.InstallStepException
import io.github.huang404yan.platypus.core.install.LoaderChoice
import io.github.huang404yan.platypus.core.install.ModpackImportException
import io.github.huang404yan.platypus.core.install.ModpackImportOutcome
import io.github.huang404yan.platypus.core.install.ModpackImporter
import io.github.huang404yan.platypus.core.install.ModpackPreview
import io.github.huang404yan.platypus.core.install.ModpackProgress
import io.github.huang404yan.platypus.core.install.ModpackStep
import io.github.huang404yan.platypus.core.install.OneClickInstaller
import io.github.huang404yan.platypus.core.install.StepProgress
import io.github.huang404yan.platypus.core.mods.ModProject
import io.github.huang404yan.platypus.core.mods.ModrinthMeta
import io.github.huang404yan.platypus.core.modpack.ModpackBrowser
import io.github.huang404yan.platypus.core.modpack.ModpackDownloadProgress
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeService
import io.github.huang404yan.platypus.core.version.VersionManifest
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
import java.io.File
import java.util.Locale

/**
 * 进程级的一键安装 / 整合包导入任务。
 *
 * 为什么单独放在页面外面：页面里的 `rememberCoroutineScope()` 会随页面离开组合树而取消
 * （App.kt 的 `when (page)` 直接调用各页面 Composable），下载到一半去实例页看一眼，
 * 回来就只剩一句「已取消」。安卓端 [DownloadJobRunner] 是同一个思路，
 * 这里把状态整体搬到 StateFlow 里，页面只负责 collect —— 切页、重组都不打断任务，
 * 只有点「取消」或退出应用才停。
 */

/** 单个安装步骤在界面上的状态。 */
internal sealed interface StepState {
    data object Pending : StepState
    data object Running : StepState
    data object Succeeded : StepState
    data class Failed(val message: String) : StepState
}

/** 一键装的整体阶段。 */
internal sealed interface InstallPhase {
    data object Idle : InstallPhase
    data object Running : InstallPhase
    data class Finished(val outcome: InstallOutcome) : InstallPhase
    data class Failed(val step: InstallStep, val message: String) : InstallPhase
}

/** 界面上依次展示的步骤（不含 IDLE 与 DONE）。 */
internal val VISIBLE_STEPS = listOf(
    InstallStep.PLANNING,
    InstallStep.DOWNLOADING,
    InstallStep.JAVA_CHECK,
    InstallStep.JAVA_INSTALL,
    InstallStep.LOADER_INSTALL,
    InstallStep.REGISTERING,
)

/** 一键装在页面上的全部可读状态。 */
internal data class OneClickState(
    val phase: InstallPhase = InstallPhase.Idle,
    val currentStep: InstallStep = InstallStep.IDLE,
    val stepStates: Map<InstallStep, StepState> = emptyMap(),
    val progress: StepProgress? = null,
    val speedBytesPerSecond: Long = 0L,
    val elapsedMs: Long = 0L,
    /** 给用户看的一句话：成功摘要、失败原因、取消说明，或输入校验提示。 */
    val message: String = "",
)

internal object OneClickRunner {

    private val _state = MutableStateFlow(OneClickState())
    val state: StateFlow<OneClickState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val meter = SpeedMeter()

    /** 同一时间只跑一个安装任务；正在跑时再点按钮直接忽略。 */
    fun start(
        gameDir: File,
        versionId: String,
        memoryMb: Int,
        loader: LoaderChoice,
        manifest: VersionManifest?,
        javaRuntimes: JavaRuntimeService,
        instances: InstanceRepository,
    ) {
        if (_state.value.phase is InstallPhase.Running) return
        job?.cancel()
        meter.reset()
        val startedAt = System.currentTimeMillis()
        _state.value = OneClickState(
            phase = InstallPhase.Running,
            currentStep = InstallStep.PLANNING,
            message = "正在解析 " + versionId + " 的版本元数据…",
        )
        job = scope.launch {
            try {
                val outcome = OneClickInstaller.install(
                    gameDir = gameDir,
                    versionId = versionId,
                    javaRuntimes = javaRuntimes,
                    instances = instances,
                    manifest = manifest,
                    memoryMb = memoryMb,
                    loader = loader,
                ) { step -> onStep(step, startedAt) }
                val elapsed = System.currentTimeMillis() - startedAt
                _state.update {
                    it.copy(
                        phase = InstallPhase.Finished(outcome),
                        currentStep = InstallStep.DONE,
                        // 只把真跑过的步骤标成成功：只装原版时不该给「安装 Fabric 加载器」打勾。
                        stepStates = it.stepStates.mapValues { StepState.Succeeded },
                        progress = null,
                        elapsedMs = elapsed,
                        message = describeOutcome(outcome),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InstallStepException) {
                val reason = e.message ?: "失败"
                _state.update {
                    it.copy(
                        phase = InstallPhase.Failed(e.step, reason),
                        currentStep = e.step,
                        stepStates = it.stepStates + (e.step to StepState.Failed(reason)),
                        progress = null,
                        message = "卡在「" + e.step.label + "」：" + reason,
                    )
                }
            } catch (e: Exception) {
                val reason = e.message ?: (e::class.simpleName ?: "未知错误")
                _state.update { current ->
                    val step = current.currentStep.takeIf { it != InstallStep.IDLE } ?: InstallStep.PLANNING
                    current.copy(
                        phase = InstallPhase.Failed(step, reason),
                        stepStates = current.stepStates + (step to StepState.Failed(reason)),
                        progress = null,
                        message = "卡在「" + step.label + "」：" + reason,
                    )
                }
            }
        }
    }

    /**
     * 取消当前任务。已写好的文件留在盘上（下次按 sha1 跳过），只丢掉这次没下完的 .part。
     */
    fun cancel() {
        if (_state.value.phase !is InstallPhase.Running) return
        job?.cancel()
        job = null
        _state.update {
            it.copy(
                phase = InstallPhase.Idle,
                currentStep = InstallStep.IDLE,
                speedBytesPerSecond = 0L,
                progress = null,
                message = "已取消。已下好的文件不会丢，再点一次会跳过校验通过的部分。",
            )
        }
    }

    /** 输入校验没通过时借同一块提示区说一句话，不启动任务。 */
    fun hint(text: String) {
        _state.update { it.copy(message = text) }
    }

    private fun onStep(step: StepProgress, startedAt: Long) {
        // 速度采样放在 update 外面：update 的 lambda 在竞争时会重跑，重复采样会算错间隔。
        val sampled = if (step.step == InstallStep.DOWNLOADING) meter.sample(step.done) else -1L
        _state.update { current ->
            var states = current.stepStates
            var active = current.currentStep
            if (step.step != active) {
                if (active != InstallStep.IDLE && active != InstallStep.DONE) {
                    states = states + (active to StepState.Succeeded)
                }
                active = step.step
            }
            if (step.step != InstallStep.IDLE && step.step != InstallStep.DONE) {
                states = states + (step.step to StepState.Running)
            }
            current.copy(
                currentStep = active,
                stepStates = states,
                progress = step,
                message = if (step.detail.isNotBlank()) step.detail else current.message,
                elapsedMs = System.currentTimeMillis() - startedAt,
                speedBytesPerSecond = if (sampled >= 0L) sampled else current.speedBytesPerSecond,
            )
        }
    }

    private fun describeOutcome(outcome: InstallOutcome): String = buildString {
        append("已完成：")
        append(outcome.versionId)
        outcome.fabric?.let {
            append("（Fabric ")
            append(it.loaderVersion)
            append("，")
            append(it.libraryFiles)
            append(" 个库")
            if (it.downloadedBytes > 0) {
                append("，")
                append(formatBytes(it.downloadedBytes))
            }
            append("）")
        }
        outcome.forge?.let {
            append("（Forge ")
            append(it.forgeVersion)
            append("，官方安装器退出码 ")
            append(it.installerExitCode)
            append("，日志 ")
            append(it.logFile.name)
            append("）")
        }
        outcome.neoforge?.let {
            append("（NeoForge ")
            append(it.neoforgeVersion)
            append("，官方安装器退出码 ")
            append(it.installerExitCode)
            append("，日志 ")
            append(it.logFile.name)
            append("）")
        }
        append("，Java ")
        append(outcome.java.majorVersion)
        append(if (outcome.javaInstalled) "（这次新下的）" else "（本机已有）")
        append("，内存 ")
        append(outcome.instance.memoryMb)
        append(" MB（可在启动页改），用时 ")
        append(formatDuration(outcome.elapsedMs))
    }
}

/** 整合包导入的整体阶段。 */
internal sealed interface ImportPhase {
    data object Idle : ImportPhase
    data object Running : ImportPhase
    data class Finished(val outcome: ModpackImportOutcome) : ImportPhase
    data class Failed(val step: ModpackStep, val message: String) : ImportPhase

    /**
     * 用户点了「取消」，或者任务被中断。
     *
     * 单独一态而不是悄悄留在 [Running]：之前取消后 phase 一直是 Running，
     * 界面就永远停在半截进度上、旁边还挂着一个「取消」按钮，看起来像卡死；
     * 更糟的是用户会以为「没报错 = 装好了」。取消必须说出来。
     */
    data class Canceled(val step: ModpackStep) : ImportPhase
}

/** 整合包导入在页面上的全部可读状态。 */
internal data class ImportState(
    val archivePath: String = "",
    val preview: ModpackPreview? = null,
    val previewError: String? = null,
    val loadingPreview: Boolean = false,
    val phase: ImportPhase = ImportPhase.Idle,
    val progress: ModpackProgress? = null,
    val message: String = "",
    /** 正在从 Modrinth 准备（查版本 / 下 .mrpack）的那个包；空表示没在准备。 */
    val preparingSlug: String = "",
    val preparingDetail: String = "",
    val downloadProgress: ModpackDownloadProgress? = null,
    val remoteNotice: String = "",
)

internal object ModpackRunner {

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inspectJob: Job? = null
    private var importJob: Job? = null
    private var prepareJob: Job? = null

    /** 只读解析一个本地压缩包，不做任何写盘。 */
    fun inspect(file: File) {
        inspectJob?.cancel()
        _state.update {
            it.copy(
                archivePath = file.absolutePath,
                preview = null,
                previewError = null,
                loadingPreview = true,
                phase = ImportPhase.Idle,
                progress = null,
                message = "",
            )
        }
        inspectJob = scope.launch {
            try {
                val parsed = ModpackImporter.inspect(file)
                _state.update { it.copy(preview = parsed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(previewError = describeImportError(e)) }
            } finally {
                // 取消时也要收掉，否则界面永远停在「正在读压缩包…」
                _state.update { it.copy(loadingPreview = false) }
            }
        }
    }

    /** 开始导入；同一时间只跑一个。 */
    fun startImport(
        archive: File,
        gameDir: File,
        memoryMb: Int,
        allowOverwrite: Boolean,
        javaRuntimes: JavaRuntimeService,
        instances: InstanceRepository,
    ) {
        if (_state.value.phase is ImportPhase.Running) return
        inspectJob?.cancel()
        importJob?.cancel()
        _state.update {
            it.copy(phase = ImportPhase.Running, progress = null, message = "", previewError = null)
        }
        importJob = scope.launch {
            try {
                val outcome = ModpackImporter.import(
                    archive = archive,
                    gameDir = gameDir,
                    javaRuntimes = javaRuntimes,
                    instances = instances,
                    memoryMb = memoryMb,
                    allowOverwriteUserData = allowOverwrite,
                ) { step ->
                    _state.update { current ->
                        current.copy(
                            progress = step,
                            message = if (step.detail.isNotBlank()) step.detail else current.message,
                        )
                    }
                }
                _state.update {
                    it.copy(phase = ImportPhase.Finished(outcome), progress = null, message = "")
                }
            } catch (e: CancellationException) {
                // 先记下来再原样抛：抛出是协程的规矩（吞掉会让外层以为跑完了），
                // 但界面得知道「这次导入断了」——留在 Running 会一直转灰条。
                val step = _state.value.progress?.step ?: ModpackStep.PARSING
                _state.update { it.copy(phase = ImportPhase.Canceled(step), progress = null, message = "") }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(phase = ImportPhase.Failed(stepOf(e), describeImportError(e)), progress = null)
                }
            }
        }
    }

    /** 从 Modrinth 下一个 .mrpack 到缓存目录，顺手解析成预览（还不动游戏目录）。 */
    fun downloadRemote(project: ModProject, gameVersion: String?, loader: LoaderKind) {
        prepareJob?.cancel()
        _state.update {
            it.copy(
                preparingSlug = project.slug,
                preparingDetail = "查版本…",
                downloadProgress = null,
                remoteNotice = "",
            )
        }
        prepareJob = scope.launch {
            try {
                val versions = ModpackBrowser.versionsOf(
                    slug = project.slug,
                    gameVersion = gameVersion,
                    loader = loader,
                )
                if (versions.isEmpty()) {
                    _state.update {
                        it.copy(
                            remoteNotice = "《" + project.title + "》没有适配" +
                                (if (gameVersion.isNullOrBlank()) "" else " " + gameVersion) +
                                " / " + loaderNameOf(loader) + " 的 .mrpack。换个游戏版本或加载器再试。",
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(preparingDetail = "共 " + versions.size + " 个 .mrpack，挑最新的一个…") }
                val picked = ModrinthMeta.pickBest(versions) ?: versions.first()
                _state.update { it.copy(preparingDetail = "下载 " + picked.fileName) }
                val local = ModpackBrowser.download(picked, ModpackBrowser.defaultCacheDir()) { p ->
                    _state.update { it.copy(downloadProgress = p, preparingDetail = p.step + " " + p.detail) }
                }
                _state.update { it.copy(downloadProgress = null, preparingDetail = "读取压缩包…") }
                val parsed = ModpackImporter.inspect(local)
                _state.update {
                    it.copy(
                        archivePath = local.absolutePath,
                        preview = parsed,
                        previewError = null,
                        phase = ImportPhase.Idle,
                        message = "",
                        remoteNotice = "已下载《" + parsed.manifest.name + "》" + picked.versionNumber +
                            "（" + formatBytes(local.length()) + "），下面确认后开始导入。",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(downloadProgress = null, remoteNotice = describeImportError(e)) }
            } finally {
                _state.update { it.copy(preparingSlug = "", preparingDetail = "") }
            }
        }
    }

    /** 输入校验没过时只写一句话提示，不动任何阶段状态。 */
    fun hint(text: String) {
        _state.update { it.copy(message = text) }
    }

    /** 取消导入。Canceled 由协程里的 catch 写，这样能带上停在哪一步。 */
    fun cancelImport() {
        if (_state.value.phase !is ImportPhase.Running) return
        importJob?.cancel()
        importJob = null
    }

    /** 取消正在准备的远程整合包（下载到一半的那个）。 */
    fun cancelRemote() {
        prepareJob?.cancel()
        prepareJob = null
    }

    /** 用户点「先不管」时清掉上一次导入的结果，回到干净的初始态。 */
    fun reset() {
        if (_state.value.phase is ImportPhase.Running) return
        val slug = _state.value.preparingSlug
        if (slug.isNotBlank()) return
        _state.value = ImportState()
    }
}

private fun stepOf(error: Throwable): ModpackStep =
    (error as? ModpackImportException)?.step ?: ModpackStep.PARSING

private fun describeImportError(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: (error::class.simpleName ?: "未知错误")

private fun loaderNameOf(kind: LoaderKind): String = when (kind) {
    LoaderKind.FABRIC -> "Fabric"
    LoaderKind.FORGE -> "Forge"
    LoaderKind.NEOFORGE -> "NeoForge"
    LoaderKind.QUILT -> "Quilt"
    LoaderKind.VANILLA -> "原版"
    else -> "其他"
}

/** 用相邻采样点的字节差算瞬时速度；间隔太短就沿用上一次的值。 */
internal class SpeedMeter {
    private var lastBytes = Long.MIN_VALUE
    private var lastAt = 0L

    fun reset() {
        synchronized(this) {
            lastBytes = Long.MIN_VALUE
            lastAt = 0L
        }
    }

    /** 返回字节/秒；样本不足时返回 -1，调用方应保留原值。 */
    fun sample(totalBytes: Long): Long {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (lastBytes == Long.MIN_VALUE || lastAt == 0L) {
                lastBytes = totalBytes
                lastAt = now
                return -1L
            }
            val elapsed = now - lastAt
            if (elapsed < 300L) return -1L
            val delta = totalBytes - lastBytes
            lastBytes = totalBytes
            lastAt = now
            return if (delta <= 0L) 0L else delta * 1000L / elapsed
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.size - 1) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.ROOT, "%.1f ", value) + units[index]
}

internal fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0L) "—" else formatBytes(bytesPerSecond) + "/s"

internal fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) minutes.toString() + " 分 " + seconds + " 秒" else seconds.toString() + " 秒"
}
