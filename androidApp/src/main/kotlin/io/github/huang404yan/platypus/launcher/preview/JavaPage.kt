package io.github.huang404yan.platypus.launcher.preview

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.runtime.AndroidJavaCatalog
import io.github.huang404yan.platypus.core.runtime.AndroidJavaInstaller
import io.github.huang404yan.platypus.core.runtime.AndroidJavaPackage
import io.github.huang404yan.platypus.core.runtime.AndroidJavaRuntimeService
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import java.io.File

/**
 * Java 页（手机）。
 *
 * 借鉴的是 FCL 的做法（[FCL-Team/FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher)）：
 * 手机上不问「你本机装了哪些 Java」，而是**由启动器自己下几份常用 JRE**，用户只需要挑版本。
 *
 * 两个跟桌面不一样的地方，都是安卓的限制：
 *
 * 1. **只能装到应用私有目录**。Android 10 起禁止从可写的共享存储执行代码，
 *    `/sdcard` 上的 `bin/java` 就算解出来了也 exec 不了。所以这里固定落在
 *    `context.filesDir/java/`，不进游戏目录 —— 这也意味着换手机不会跟着搬家。
 * 2. **只有四个大版本**。FCL 那份预编译资产里就 8 / 17 / 21 / 25 四个，
 *    其中 25 没有 32 位 x86 的包，所以那一档在 x86 设备上不出现。
 *
 * 这一版仍不能进游戏（runtime-android 的 JNI 与图形转译还没做），但它能做的事比上一版多了：
 * 启动器**认得出**盘上那几份 JRE 各是几版、哪一套是完整的（[AndroidJavaRuntimeService]）。
 * 认版本不靠跑 `java -version`（应用内 JVM 只允许一个，安卓 10 起共享存储也不可执行），
 * 而是读 JRE 目录里的 `release` 文件 —— 所以「已装」的判据也从「两个标志文件在不在」
 * 升级成「libjvm.so 也在」，否则解包断在一半的目录会被当成能用，等真启动时才闪退。
 */
@Composable
internal fun JavaPage() {
    val context = LocalContext.current

    // 装到应用私有目录：Android 10 起共享存储不可执行。
    val javaRoot = remember { File(context.filesDir, "java") }

    // 设备认哪几种 ABI：优先 arm64，其次 32 位 arm，再是模拟器那两种。
    val abi = remember {
        AndroidJavaCatalog.supportedAbi(Build.SUPPORTED_ABIS?.toList().orEmpty())
    }

    // 认版本、认完整性的那一层；abi 为 null 时它自己会给空表。
    val service = remember(javaRoot, abi) { AndroidJavaRuntimeService(javaRoot, abi) }

    var offers by remember { mutableStateOf<List<AndroidJavaPackage>>(emptyList()) }
    var removing by remember { mutableStateOf<AndroidJavaPackage?>(null) }
    var detected by remember { mutableStateOf<List<JavaRuntime>>(emptyList()) }

    val state = JavaInstallRunner.state.collectAsState().value
    val running = state.busy

    /** 重新按盘上的实际情况算一遍「装没装」。解包成功后目录会变，所以每次都重扫。 */
    fun reload() {
        offers = if (abi == null) {
            emptyList()
        } else {
            AndroidJavaCatalog.packages(abi) { major ->
                val home = File(javaRoot, "jre" + major + "-" + abi)
                if (AndroidJavaRuntimeService.canLaunch(home)) home.absolutePath else null
            }
        }
    }

    LaunchedEffect(state.doneAt) {
        reload()
        detected = runCatching { service.detect() }.getOrDefault(emptyList())
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Java", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "手机不能像电脑那样随便找一个 java.exe。这里由启动器自己下几份常用运行时，" +
                "下好就存在应用私有目录里，删掉应用才会没。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "注意：这一版的安卓端还不能进游戏（应用内起 JVM 与图形转译没做），" +
                "但启动器已经认得出这些 Java 各是几版、哪份是完整的。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )

        if (detected.isNotEmpty()) {
            Text(
                "启动器认出来的：" + detected.joinToString("、") { runtimeLabel(it) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (abi == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    "这台设备的 CPU 架构不在支持列表里（" + Build.SUPPORTED_ABIS.joinToString("、") +
                        "）。现在只有 arm64-v8a、armeabi-v7a、x86_64、x86 四种包。",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            return@Column
        }

        // 进度与操作：放在列表上方，滚到哪儿都看得见当前在干什么。
        state.progress?.let { p ->
            LinearProgressIndicator(
                progress = { p.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.message.isNotBlank()) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (running) {
            OutlinedButton(onClick = { JavaInstallRunner.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text("取消")
            }
        }

        Text(
            "装在这里：" + javaRoot.absolutePath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(offers, key = { it.majorVersion }) { offer ->
                JavaOfferRow(
                    offer = offer,
                    busy = running,
                    onDownload = { JavaInstallRunner.start(javaRoot, offer) },
                    onRemove = { removing = offer },
                )
            }
        }
    }

    val target = removing
    if (target != null) {
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删掉 " + target.label + "？") },
            text = {
                Text(
                    "会直接删掉 " + AndroidJavaInstaller.homeOf(javaRoot, target).absolutePath +
                        "。想再用的话重新下一遍。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        AndroidJavaInstaller.remove(javaRoot, target)
                        removing = null
                        reload()
                    },
                ) { Text("删掉") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("留着") } },
        )
    }
}

/** 一行一个 Java 大版本：左边版本与其来源，右边下载 / 已装。 */
@Composable
private fun JavaOfferRow(
    offer: AndroidJavaPackage,
    busy: Boolean,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(offer.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "约 " + mbOf(offer.totalBytes) + " · " + offer.abi,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    offer.installed -> OutlinedButton(onClick = onRemove, enabled = !busy) { Text("删除") }
                    else -> Button(onClick = onDownload, enabled = !busy) { Text("下载") }
                }
            }
            offer.installedPath?.takeIf { it.isNotBlank() }?.let { path ->
                Text(
                    "已装：" + path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 「Java 17（17.0.9，aarch64）」；release 里没写版本号时就只报主版本。 */
private fun runtimeLabel(runtime: JavaRuntime): String {
    val bits = buildString {
        append("Java ")
        append(runtime.majorVersion)
        val arch = runtime.arch
        if (!arch.isNullOrBlank()) {
            append("，")
            append(arch)
        }
        runtime.bits?.let {
            append(" ")
            append(it)
            append(" 位")
        }
    }
    return bits
}

/** 「约 40 MB」，四舍五入到整数兆。 */
private fun mbOf(bytes: Long): String =
    if (bytes <= 0L) "未知大小" else ((bytes + 1024L * 1024L - 1) / (1024L * 1024L)).toString() + " MB"
