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
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.JsonInstanceRepository
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.instance.mergeInstances
import io.github.huang404yan.platypus.core.instance.pruneMissing
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.runtime.JavaRecommendation
import io.github.huang404yan.platypus.core.runtime.JavaRecommender
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeOffer
import io.github.huang404yan.platypus.core.runtime.JavaVerdict
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * 图形转译那一层不在这里（见启动页的「图形后端」），但这一页的产物是启动的前提：
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
    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }

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
        // 光说「装了哪几份」还不够：用户真正要回答的是「我这几个版本够不够跑」。
        // 实例列表从游戏目录里读，读不到就整块不画，不拿空表冒充「一个版本都没有」。
        instances = withContext(Dispatchers.IO) {
            runCatching {
                val root = File(defaultGameRoot())
                val registered = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(root)).list()
                val scan = InstanceScanner.scan(root)
                // 登记的实例可能早就在资源管理器里被删了，只摘展示、不写盘（理由见 pruneMissing）。
                val live = pruneMissing(registered, scan.versionNames)
                mergeInstances(live.kept, scan.instances, scan.libraryVersions)
            }.getOrDefault(emptyList())
        }
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
            "下好之后启动页会自己认出它是几版、整不完整。图形转译库在启动页的「图形后端」那里下。",
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

        // 各版本要的 Java：结论与桌面同一套（core 的 JavaRecommender），只是候选
        // 从「本机探测到的 java.exe」换成「启动器自己下的这几份 JRE」。
        //
        // 清单也要一并给过去，否则推荐分不清「清单里有但还没下」和「这台机器根本没有这一档」：
        // Java 25 没有 x86 包，只传 detected 的话会一直劝人去下，点了才发现下不了。
        val catalogOffers = remember(offers) {
            offers.map { pkg ->
                JavaRuntimeOffer(
                    component = AndroidJavaRuntimeService.componentOf(pkg.majorVersion, pkg.abi),
                    majorVersion = pkg.majorVersion,
                    versionName = pkg.label + "（" + pkg.abi + "）",
                    manifestSize = pkg.totalBytes,
                    installed = pkg.installed,
                    installedPath = pkg.installedPath,
                )
            }
        }
        val groups = remember(instances, detected) {
            instances.groupBy { GameJavaRules.requiredMajor(it.gameVersion) }.entries.sortedBy { it.key }
        }
        if (groups.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "各版本要的 Java",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "手机上不用自己去外面找 Java，缺哪个直接在这里下。这里只认主版本完全相等 ——" +
                            "拿更高版本顶替，1.16.5 那一代带 Forge 的会直接崩。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    groups.forEach { entry ->
                        val major = entry.key
                        JavaNeedRow(
                            major = major,
                            owned = entry.value,
                            recommendation = JavaRecommender.recommend(major, detected, catalogOffers),
                            busy = running,
                            onDownload = offers
                                .firstOrNull { it.majorVersion == major && !it.installed }
                                ?.let { pkg -> { JavaInstallRunner.start(javaRoot, pkg) } },
                        )
                    }
                }
            }
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

/**
 * 一行一组「哪些版本要这个 Java、现在够不够」。
 *
 * 与桌面端同名组件同一套判定（[JavaRecommender]），只是桌面那边会给出「去下载」按钮
 * 指向 Mojang 清单，手机这边指向启动器自己的 JRE 表 —— 手机装不了桌面那种 JDK。
 *
 * 版本名最多点三个，再多的折成「等 N 个」：手机一行放不下四五个版本名，
 * 而用户在这一行要的是结论，不是完整清单。
 */
@Composable
private fun JavaNeedRow(
    major: Int,
    owned: List<Instance>,
    recommendation: JavaRecommendation,
    busy: Boolean,
    onDownload: (() -> Unit)?,
) {
    val tint = when (recommendation.verdict) {
        JavaVerdict.READY -> MaterialTheme.colorScheme.primary
        JavaVerdict.CAN_DOWNLOAD -> MaterialTheme.colorScheme.onSurface
        JavaVerdict.NOT_AVAILABLE, JavaVerdict.UNKNOWN_NEED -> MaterialTheme.colorScheme.error
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Java " + major,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            when {
                recommendation.ready -> Text(
                    "够用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                onDownload != null -> OutlinedButton(onClick = onDownload, enabled = !busy) { Text("下载") }
            }
        }
        Text(
            owned.take(3).joinToString("、") { it.name.ifBlank { it.gameVersion } } +
                if (owned.size > 3) " 等 " + owned.size + " 个版本" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            JavaRecommender.describe(recommendation),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
