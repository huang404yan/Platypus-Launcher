package io.github.huang404yan.platypus.launcher.preview

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.launch.AndroidGraphicsBundle
import io.github.huang404yan.platypus.core.launch.AndroidGraphicsCatalog
import io.github.huang404yan.platypus.core.launch.AndroidRenderer
import io.github.huang404yan.platypus.core.launch.megabytes
import io.github.huang404yan.platypus.core.runtime.AndroidJavaCatalog
import io.github.huang404yan.platypus.core.runtime.AndroidJavaPackage
import io.github.huang404yan.platypus.core.runtime.AndroidJavaRuntimeService
import io.github.huang404yan.platypus.core.setup.AndroidSetup
import io.github.huang404yan.platypus.core.setup.SetupItem
import io.github.huang404yan.platypus.core.settings.JsonSettingsRepository
import io.github.huang404yan.platypus.core.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 第一次打开时的那一页引导。
 *
 * 手机与电脑的差别全在「第一次要自己准备什么」：电脑上装完启动器就能建实例，手机上
 * 得先有 JRE（应用私有目录）、再有一套图形转译库（AGP 不打包的 `.so`），否则实例建出来
 * 也点不动。这两件事原本藏在「设置 → Java」与「启动 → 图形后端」里，第一次用的人不会
 * 自己去找，于是第一局必然卡住。
 *
 * 三件事各自独立、可以单独完成，也可以整个跳过 —— 跳过的代价只是主界面里那项还会再
 * 提醒一次，不是把人锁在这一页。判断「还差几件」的逻辑在 [AndroidSetup] 里，这一页
 * 只负责把盘上的实际情况读出来交给它。
 *
 * 图形后端装到 `filesDir/natives/default`：那是「还没有实例时」启动页会用的同一个目录，
 * 所以这里装一次，之后建的第一个实例直接就有；已经有实例的用户，启动页会把它复制过去。
 */
@Composable
internal fun FirstRunGuideScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val abi = remember { AndroidJavaCatalog.supportedAbi(Build.SUPPORTED_ABIS?.toList().orEmpty()) }
    val javaRoot = remember { File(context.filesDir, "java") }
    val nativeRoot = remember { File(context.filesDir, "natives") }
    // 与 LaunchPage.nativeDirOf(null) 同一个路径：没有实例时两份代码认的是同一个目录。
    val sharedNativeDir = remember(nativeRoot) { File(nativeRoot, "default") }

    val javaState = JavaInstallRunner.state.collectAsState().value
    val gfxState = GraphicsInstallRunner.state.collectAsState().value

    var renderer by remember { mutableStateOf(AndroidRenderer.DEFAULT) }
    var javaReady by remember { mutableStateOf(false) }
    var gfxReady by remember { mutableStateOf(false) }
    var updateChecked by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }
    var checkingUpdate by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }

    /** 按盘上的实际情况列一遍可下的 JRE（装好了的也会列出来，只是 marked 成 installed）。 */
    fun offersNow(): List<AndroidJavaPackage> = if (abi == null) {
        emptyList()
    } else {
        AndroidJavaCatalog.packages(abi) { major ->
            val home = File(javaRoot, "jre" + major + "-" + abi)
            if (AndroidJavaRuntimeService.canLaunch(home)) home.absolutePath else null
        }
    }

    var javaOffers by remember { mutableStateOf(offersNow()) }
    // 第一局该下哪一版：1.20.1 这类现代版本要 17，1.16 及更早要 8。引导页不下 8（它只在
    // 老版本上用得到），先给 17，装完这一版就能建出现代实例。
    val suggestedJava = javaOffers.firstOrNull { it.majorVersion == 17 } ?: javaOffers.firstOrNull()
    val gfxBundle = remember(renderer, abi) {
        if (abi == null) null else AndroidGraphicsCatalog.bundleOf(renderer, abi)
    }

    /** 重新按盘上的实际情况算一遍「哪几件好了」。装完一版、下完一套都会走到这里。 */
    fun rescan() {
        javaOffers = runCatching { offersNow() }.getOrDefault(emptyList())
        javaReady = javaOffers.any { it.installed }
        gfxReady = runCatching { gfxBundle?.isInstalled(sharedNativeDir) ?: false }.getOrDefault(false)
    }

    fun checkUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { UpdateChecker.check() } }
            checkingUpdate = false
            updateChecked = true
            updateMessage = result.fold(
                onSuccess = { r ->
                    if (r.hasUpdate) {
                        "有新版本 " + (r.latest?.version ?: "") + "。装完这几件再去「关于」页下载就行。"
                    } else {
                        "已经是最新的 " + Brand.version + "。"
                    }
                },
                onFailure = { e -> "这次没查成：" + (e.message ?: "网络不通") + "。不影响用，稍后再查也行。" },
            )
        }
    }

    LaunchedEffect(Unit) {
        rescan()
        checkUpdate()
    }
    // 装完一版 Java 或一套图形库之后重扫一次：doneAt 是单例里记的完成时刻。
    LaunchedEffect(javaState.doneAt) { rescan() }
    LaunchedEffect(gfxState.doneAt) { rescan() }
    // 换后端就是换一整套文件，就绪状态要跟着重算，否则选 Zink 会显示 GL4ES 的结果。
    LaunchedEffect(gfxBundle) { rescan() }

    /** 走完这一页：把时间戳写进设置，写失败也照走 —— 不能因为存不下设置就把人锁在外面。 */
    fun finish() {
        if (finishing) return
        finishing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val root = File(defaultGameRoot())
                    val repo = JsonSettingsRepository(JsonSettingsRepository.defaultFile(root))
                    val settings = repo.load()
                    if (AndroidSetup.needed(settings)) {
                        repo.save(settings.copy(androidGuideAt = AndroidSetup.doneAt(System.currentTimeMillis())))
                    }
                }
            }
            finishing = false
            onDone()
        }
    }

    val progress = AndroidSetup.progress(
        listOfNotNull(
            SetupItem.JAVA.takeIf { javaReady },
            SetupItem.GRAPHICS.takeIf { gfxReady },
            SetupItem.UPDATE.takeIf { updateChecked },
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "先准备三件事",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            progress.headline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (abi == null) {
            GuideCard {
                Text(
                    "这台设备的 CPU 架构（" + Build.SUPPORTED_ABIS.joinToString("、") +
                        "）不在支持列表里，Java 与图形后端都没有对应的包。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // 一、Java 运行时
        GuideStep(
            index = 1,
            title = SetupItem.JAVA.label,
            ready = javaReady,
        ) {
            Text(
                "手机不能像电脑那样找一个 java.exe。这里由启动器自己下一份，下好存在应用私有目录里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (javaReady) {
                Text(
                    "已经装好了，启动页会自己认出它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (suggestedJava == null) {
                Text(
                    "这台设备没有可用的运行时包。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { JavaInstallRunner.start(javaRoot, suggestedJava) },
                        enabled = !javaState.busy,
                    ) {
                        Text(
                            if (javaState.busy) {
                                "正在下载…"
                            } else {
                                "下载 " + suggestedJava.label + "（约 " + megabytes(suggestedJava.totalBytes) + "）"
                            },
                        )
                    }
                    if (javaState.busy) {
                        TextButton(onClick = { JavaInstallRunner.cancel() }) { Text("取消") }
                    }
                }
            }
            javaState.progress?.let { p ->
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            }
            if (javaState.message.isNotBlank()) {
                Text(
                    javaState.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (javaState.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        // 二、图形后端
        GuideStep(
            index = 2,
            title = SetupItem.GRAPHICS.label,
            ready = gfxReady,
        ) {
            Text(
                "手机上的 OpenGL 要靠一层转译库翻成 GLES，那几份 .so 不随安装包发（四十来兆），" +
                    "这里按需下一套。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val options = remember(abi) { if (abi == null) emptyList() else AndroidGraphicsCatalog.renderersOn(abi) }
            if (abi != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (option in options) {
                        val optionBundle = AndroidGraphicsCatalog.bundleOf(option, abi)
                        OutlinedButton(
                            onClick = { renderer = option },
                            enabled = !gfxState.busy,
                        ) {
                            Text(
                                rendererPrefix(option == renderer) + option.label +
                                    if (optionBundle == null) {
                                        "（上游没有这个架构的包）"
                                    } else {
                                        "（约 " + megabytes(optionBundle.totalBytes) + "）"
                                    },
                            )
                        }
                    }
                }
            }
            if (gfxBundle != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { GraphicsInstallRunner.start(sharedNativeDir, gfxBundle) },
                        enabled = !gfxState.busy && !gfxReady,
                    ) {
                        Text(
                            when {
                                gfxState.busy -> "正在下载…"
                                gfxReady -> "这一套已经装好了"
                                else -> "下载 " + renderer.label + "（约 " + megabytes(gfxBundle.totalBytes) + "）"
                            },
                        )
                    }
                    if (gfxState.busy) {
                        TextButton(onClick = { GraphicsInstallRunner.cancel() }) { Text("取消") }
                    }
                }
            }
            gfxState.progress?.let { p ->
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            }
            if (gfxState.message.isNotBlank()) {
                Text(
                    gfxState.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (gfxState.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                "装在这里：" + sharedNativeDir.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 三、检查更新
        GuideStep(
            index = 3,
            title = SetupItem.UPDATE.label,
            ready = updateChecked,
        ) {
            Text(
                "看一眼有没有新版。安卓上装新版要你手动点安装包，所以这里只查不装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (updateMessage.isNotBlank()) {
                Text(updateMessage, style = MaterialTheme.typography.bodySmall)
            }
            if (checkingUpdate) {
                Text(
                    "正在查…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedButton(onClick = { checkUpdate() }) { Text("再查一次") }
            }
        }

        Button(
            onClick = { finish() },
            enabled = !finishing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(progress.primaryLabel)
        }
        Text(
            "没准备的也能先进去：设置里的「Java」、启动页的「图形后端」随时能补，跳过的只是少看一遍这段说明。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一张「第 N 件」的卡：左边序号，右边内容；就绪时标题后面挂个勾。 */
@Composable
private fun GuideStep(
    index: Int,
    title: String,
    ready: Boolean,
    content: @Composable () -> Unit,
) {
    GuideCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (ready) "✓" else index.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (ready) {
                Text(
                    "已就绪",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        content()
    }
}

@Composable
private fun GuideCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

/** 选中项前面的圆点；不选时留等宽的空格，免得按钮文字左右跳。 */
private fun rendererPrefix(selected: Boolean): String = if (selected) "● " else "○ "
