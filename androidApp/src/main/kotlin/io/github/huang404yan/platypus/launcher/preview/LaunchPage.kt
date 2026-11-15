package io.github.huang404yan.platypus.launcher.preview

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.auth.OfflineProfile
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.instance.mergeInstances
import io.github.huang404yan.platypus.core.launch.AndroidLaunchArgs
import io.github.huang404yan.platypus.core.launch.AndroidLaunchConfig
import io.github.huang404yan.platypus.core.launch.AndroidLaunchPreparation
import io.github.huang404yan.platypus.core.launch.AndroidLaunchPlanner
import io.github.huang404yan.platypus.core.launch.AndroidRenderer
import io.github.huang404yan.platypus.core.launch.LaunchRequest
import io.github.huang404yan.platypus.core.launch.NativesExtractor
import io.github.huang404yan.platypus.core.runtime.AndroidJavaCatalog
import io.github.huang404yan.platypus.core.runtime.AndroidJavaRuntimeService
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
import io.github.huang404yan.platypus.core.settings.LauncherSettings
import io.github.huang404yan.platypus.launcher.preview.runtime.JvmBridge
import io.github.huang404yan.platypus.launcher.preview.runtime.JvmStartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 启动页（手机）。
 *
 * ## 它做到哪一步
 *
 * 手机上进游戏与电脑上完全是两件事：电脑是 `ProcessBuilder` 起一个 java 进程，
 * 手机要在**同一个进程里** `JVM_CreateJavaVM`，而且 OpenGL 得靠转译层翻译成 GLES。
 * 所以这一页不做「点一下进游戏」，它做到的是**进游戏之前的全部准备**：
 *
 * 1. 挑实例、挑 Java 运行时、挑图形后端；
 * 2. 六条自检（游戏目录 / Java 运行时 / 图形后端 / 渲染库 / 临时目录 / 原生库目录），
 *    哪条不过就说清是哪个路径上缺什么；
 * 3. 把应用内 JVM 要的那张参数表整个算出来并显示出来，可以导出成文件对照；
 * 4. 把版本 json 里的 natives 解压到实例自己的目录里；
 * 5. 自检全过之后，把这张参数表交给 JNI 层的 `JNI_CreateJavaVM` 真正起一个 JVM
 *    （`androidApp/src/main/cpp/jvm_bridge.c`，编成 libplatypus-jvm.so）。
 *
 * ## 最大的风险不在这一页
 *
 * Android 10 起，系统禁止应用从**可写目录** `dlopen` 原生库。而应用内 JVM 的
 * libjvm.so 按定义一定躺在 `/data/data/<包名>/files/java/...` 这种可写目录里 ——
 * 也就是说这台设备只要升到 Android 10 及以上，这一步很可能直接失败在
 * 「没能加载 libjvm.so」上。这是系统限制，不是参数拼错。
 *
 * 所以界面照样给「启动」按钮，但把这句话写在按钮旁边：**能起是运气，起不来不是 bug**。
 * 图形那一层（GL4ES / Zink 转译）同样还没接 —— 即使 JVM 起来了，游戏窗口也挂不上去。
 *
 * ## 为什么不打包图形转译库
 *
 * GL4ES / Zink 那几份 `.so` 的许可证与体积都要求单独决策（见 docs/45 第 2 层），
 * 这一版给的是「放进来用」的口子：按钮选一个 `.so` 文件，复制到实例的 natives 目录，
 * 文件名要对上后端要的那一个（GL4ES 是 libgl4es_114.so，Zink 是 libOSMesa.so）。
 */
@Composable
internal fun LaunchPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val abi = remember { AndroidJavaCatalog.supportedAbi(Build.SUPPORTED_ABIS?.toList().orEmpty()) }
    val javaRoot = remember { File(context.filesDir, "java") }
    val nativeRoot = remember { File(context.filesDir, "natives") }
    val tmpDir = remember { context.cacheDir }
    val service = remember(javaRoot, abi) { AndroidJavaRuntimeService(javaRoot, abi) }

    var root by remember { mutableStateOf(defaultGameRoot()) }
    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var selected by remember { mutableStateOf<Instance?>(null) }
    var runtimes by remember { mutableStateOf<List<JavaRuntime>>(emptyList()) }
    var javaExe by remember { mutableStateOf("") }
    var renderer by remember { mutableStateOf(AndroidRenderer.DEFAULT) }
    var username by remember { mutableStateOf(LauncherSettings.DEFAULT_OFFLINE_USERNAME) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var prep by remember { mutableStateOf<AndroidLaunchPreparation?>(null) }
    var exported by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var startNotice by remember { mutableStateOf("") }

    /** 这个实例的 natives 目录：一个实例一份，免得两个版本互相盖掉。 */
    fun nativeDirOf(instance: Instance?): File {
        val raw = instance?.directory.orEmpty().trim().trim('"')
        val name = raw.replace('\\', '/').trimEnd('/').substringAfterLast('/')
        return if (name.isBlank()) File(nativeRoot, "default") else File(nativeRoot, name)
    }

    fun scan() {
        val dir = File(root.trim())
        busy = true
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val registered = runCatching {
                    JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(dir)).list()
                }.getOrNull().orEmpty()
                mergeInstances(registered, InstanceScanner.scan(dir).instances)
            }
            instances = list
            busy = false
            notice = if (list.isEmpty()) "这个目录里没找到实例。先去「下载」页装一个版本。" else ""
            selected = selected?.let { old -> list.firstOrNull { it.directory == old.directory } } ?: list.firstOrNull()
        }
    }

    fun reloadJava(prefer: Instance?) {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { service.installedRuntimes() }.getOrDefault(emptyList())
            }
            runtimes = list
            // 只认完全相等的主版本：1.16.5 跑在 Java 17 上是直接崩，不是慢一点。
            val major = prefer?.let { GameJavaRules.requiredMajor(it.gameVersion) }
            val found = list.firstOrNull { it.majorVersion == major } ?: list.firstOrNull()
            javaExe = found?.let { File(File(it.path), "bin/java").absolutePath }.orEmpty()
        }
    }

    fun prepare() {
        val instance = selected ?: run { notice = "先选一个实例。"; return }
        val deviceAbi = abi ?: run { notice = "这台机器的架构没有对应的 Java 包（支持的是 " + AndroidJavaCatalog.ABIS.joinToString("、") + "）。"; return }
        val exe = javaExe.trim()
        if (exe.isBlank()) { notice = "先到「Java」页装一个运行时，再回来做自检。"; return }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val runtime = JavaRuntime(
                        path = exe,
                        majorVersion = GameJavaRules.requiredMajor(instance.gameVersion),
                    )
                    val request = LaunchRequest(
                        instance = instance,
                        java = runtime,
                        session = OfflineProfile.session(username),
                    )
                    AndroidLaunchPlanner.prepare(
                        request = request,
                        config = AndroidLaunchConfig(
                            abi = deviceAbi,
                            androidRelease = Build.VERSION.RELEASE.orEmpty(),
                            renderer = renderer,
                            tmpDir = tmpDir,
                            nativeDir = nativeDirOf(instance),
                        ),
                    )
                }
            }
            busy = false
            result.fold(
                onSuccess = { prep = it; exported = ""; notice = it.summary },
                onFailure = { prep = null; notice = "自检本身出错了：" + describe(it) },
            )
        }
    }

    fun extractNatives() {
        val plan = prep?.plan ?: run { notice = "先做一次自检。"; return }
        val target = nativeDirOf(selected)
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val result = NativesExtractor.extract(plan.nativeArchives, target)
                    val builder = StringBuilder()
                    builder.append("解压了 ").append(result.extracted).append(" 个文件到 ").append(target.absolutePath)
                    if (result.warnings.isNotEmpty()) {
                        builder.append('；').append(result.warnings.joinToString("；"))
                    }
                    builder.toString()
                }.getOrElse { "解压失败：" + describe(it) }
            }
            busy = false
            notice = text
        }
    }

    fun exportArgs() {
        val current = prep ?: run { notice = "先做一次自检。"; return }
        if (current.fullCommand.isEmpty()) { notice = "自检还没过，没有参数表可导出。"; return }
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val logs = File(current.env.gameDir, "logs")
                    logs.mkdirs()
                    val file = File(logs, "platypus-launch-android.txt")
                    val token = OfflineProfile.session(username).accessToken
                    val body = current.fullCommand.joinToString(System.lineSeparator()) { arg ->
                        if (token.isNotBlank() && arg.contains(token)) arg.replace(token, "***") else arg
                    }
                    file.writeText(body + System.lineSeparator(), Charsets.UTF_8)
                    file.absolutePath
                }.getOrElse { "写文件失败：" + describe(it) }
            }
            busy = false
            exported = text
            notice = if (text.startsWith("写文件失败")) text else "参数表已写到 " + text
        }
    }

    /**
     * 真正启动：[JvmBridge.start] 会一直在调用线程上跑到游戏退出，所以必须扔进后台线程。
     *
     * 失败时把原因留在界面上 —— 这里几乎每一种失败（没有 JNI 层、加载不了 libjvm.so、
     * JVM 造不出来、主类找不到）都是用户自己改不了的系统或环境问题，
     * 一句「启动失败」等于什么都没说。
     */
    fun startGame() {
        val current = prep ?: run { startNotice = "先做一次自检。"; return }
        if (!current.ready) { startNotice = "自检还没过，先把上面标红的几条处理掉。"; return }
        starting = true
        startNotice = ""
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { JvmBridge.start(current) }
                    .getOrElse { JvmStartResult.Failed("启动时抛了异常：" + describe(it)) }
            }
            starting = false
            startNotice = when (outcome) {
                is JvmStartResult.Ok -> "游戏进程结束了。"
                is JvmStartResult.Failed -> outcome.message
            }
        }
    }

    // 选文件那个回调是 remember 的，捕获的是首次组合的变量值，得用最新的这份。
    val latestSelected by rememberUpdatedState(selected)
    val latestRenderer by rememberUpdatedState(renderer)
    val pickLibrary = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val instance = latestSelected
        val target = File(nativeDirOf(instance), latestRenderer.libraryName)
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    target.parentFile?.mkdirs()
                    val input = context.contentResolver.openInputStream(uri)
                        ?: return@runCatching "读不到这个文件（系统没给出输入流）。"
                    input.use { source ->
                        target.outputStream().use { sink -> source.copyTo(sink) }
                    }
                    "已放入 " + target.absolutePath + "（" + target.length() + " 字节）"
                }.getOrElse { "复制失败：" + describe(it) }
            }
            busy = false
            notice = text
        }
    }

    LaunchedEffect(Unit) {
        val store = JsonSettingsRepository(JsonSettingsRepository.defaultFile(File(root.trim())))
        username = runCatching { store.load() }.getOrNull()?.offlineUsername
            ?: LauncherSettings.DEFAULT_OFFLINE_USERNAME
        scan()
        reloadJava(null)
    }

    LaunchedEffect(selected?.directory) {
        prep = null
        exported = ""
        if (selected != null) reloadJava(selected)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("启动", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "手机上要在应用内起 JVM 再把 OpenGL 转译成 GLES。这一页做到：挑运行时、六条自检、" +
                "拼出完整的启动参数表，自检全过之后可以试着启动。" +
                "但 Android 10 起系统禁止从可写目录加载原生库，这一步大概率起不来 —— 起不来不是参数问题。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )

        LaunchCard("游戏目录") {
            OutlinedTextField(
                value = root,
                onValueChange = { root = it },
                label = { Text("游戏目录") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scan() }, enabled = !busy) { Text("重新扫描") }
                Text(
                    if (instances.isEmpty()) "还没扫到实例" else "认出 " + instances.size + " 个实例",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        LaunchCard("实例") {
            if (instances.isEmpty()) {
                LaunchHint("没有可选实例。先去「下载」页装一个版本，或者把上面那个目录改对。")
            } else {
                instances.forEach { instance ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = instance }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (selected?.directory == instance.directory) "●" else "○",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected?.directory == instance.directory) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Column {
                            Text(instance.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                instance.loader.name + " · " + instance.gameVersion + " · 需要 Java " +
                                    GameJavaRules.requiredMajor(instance.gameVersion),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        LaunchCard("Java 运行时") {
            if (runtimes.isEmpty()) {
                LaunchHint("还没有装好的运行时。去「Java」页装一个（版本要对上实例需要的那一档）。")
            } else {
                runtimes.forEach { runtime ->
                    val path = File(File(runtime.path), "bin/java").absolutePath
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { javaExe = path }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (javaExe == path) "●" else "○",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (javaExe == path) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Column {
                            Text("Java " + runtime.majorVersion, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                runtime.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        LaunchCard("图形后端") {
            AndroidRenderer.supportedOn(abi.orEmpty()).forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { renderer = option }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (renderer == option) "●" else "○",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (renderer == option) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Column {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            option.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "要把它要的 " + renderer.libraryName + " 放进：" + nativeDirOf(selected).absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickLibrary.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Text("放入 " + renderer.libraryName)
                }
                OutlinedButton(onClick = { extractNatives() }, enabled = !busy && prep?.plan != null) {
                    Text("解压原版 natives")
                }
            }
            LaunchHint(
                "转译库的体积与许可证要单独决定（见 docs/45），所以这一版不随包分发：" +
                    "你自己有那份 .so 时用「放入」选进来就行。文件名必须就是它要的那一个。",
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { prepare() }, enabled = !busy && selected != null) {
                Text(if (busy) "正在检查…" else "做启动自检")
            }
            OutlinedButton(onClick = { exportArgs() }, enabled = !busy && prep?.fullCommand?.isNotEmpty() == true) {
                Text("导出参数表")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { startGame() },
                enabled = !busy && !starting && prep?.ready == true,
            ) {
                Text(if (starting) "游戏运行中…" else "启动")
            }
            Text(
                if (JvmBridge.available) "JNI 层已就位" else "这个包没带 JNI 层：" + JvmBridge.loadFailure,
                style = MaterialTheme.typography.labelSmall,
                color = if (JvmBridge.available) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (startNotice.isNotBlank()) {
            LaunchHint(startNotice, error = startNotice != "游戏进程结束了。")
        }

        if (notice.isNotBlank()) {
            val isError = notice.startsWith("自检本身出错") || notice.startsWith("复制失败") ||
                notice.startsWith("解压失败") || notice.startsWith("写文件失败");
            LaunchHint(notice, error = isError);
        }

        val current = prep
        if (current != null) {
            LaunchCard(if (current.ready) "自检通过" else "自检没过") {
                Text(
                    current.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (current.ready) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                current.checks.forEach { check ->
                    Column {
                        Text(
                            (if (check.ok) "· " else "× ") + check.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (check.ok) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        if (!check.ok) {
                            Text(
                                check.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (current.notes.isNotEmpty()) {
                    current.notes.forEach { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (current.fullCommand.isNotEmpty()) {
                LaunchCard("启动参数（" + current.fullCommand.size + " 条）") {
                    SelectionContainer {
                        Text(
                            current.fullCommand.joinToString(System.lineSeparator()),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (exported.isNotBlank() && !exported.startsWith("写文件失败")) {
                        Text(
                            "已经写到：" + exported,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        LaunchHint(
            "「启动」走的是 native 层（libplatypus-jvm.so）：dlopen 运行时的 libjvm.so、" +
                "dlsym 拿到 JNI_CreateJavaVM、带着上面那张参数表造一个 JVM、再调游戏主类。" +
                "这条路被 Android 10 的「不许从可写目录加载原生库」挡着，起不来是常态；" +
                "图形转译（GL4ES / Zink）也还没接到这一层，即使 JVM 起来了窗口也挂不上去。",
        )
    }
}

@Composable
private fun LaunchCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun LaunchHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
