package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.runtime.GameJavaRules
import io.github.huang404yan.platypus.core.runtime.JavaRecommendation
import io.github.huang404yan.platypus.core.runtime.JavaRecommender
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeOffer
import io.github.huang404yan.platypus.core.runtime.JavaVerdict
import io.github.huang404yan.platypus.core.runtime.ManagedJava
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「设置 → Java」：让用户自己挑一份 Java 下回来。
 *
 * 以前只有启动页那个「自动补 Java N」按钮：它能救急，但用户看不见全貌 ——
 * 不知道这台机器上已经有几份 Java、不知道启动器把运行时下到了哪儿、也没法主动
 * 提前装好某个版本（比如知道要玩 1.20.1 的 Forge，先备一份 17）。
 * 这一页把官方清单摊平成一张表：8 / 16 / 17 / 21 / 25，已装的标出来，没装的给下载按钮。
 *
 * 两处刻意的取舍：
 * - **下载不探测本机**。用户点「下载」就是要一份启动器自己管理的运行时
 *   （在 <启动器目录>/java/ 下、删得掉、不会被系统 Java 更新牵连）。
 *   「本机有没有能用的」是「启动」页那个按钮该操心的事（见 [JavaRuntimeService.ensure]）。
 * - **不做高版本顶替**。表里每一项都是精确主版本，界面也不推荐「装个更高的凑合用」：
 *   1.16.5 的 Forge 在 Java 16+ 上会直接崩（[GameJavaRules] 里那条规则就是这么来的）。
 */
@Composable
internal fun JavaTab(
    backend: LauncherBackend?,
    gameRoot: String,
    refreshTick: Int = 0,
    launcherDir: File? = backend?.launcherDir,
) {
    val scope = rememberCoroutineScope()

    var offers by remember { mutableStateOf<List<JavaRuntimeOffer>>(emptyList()) }
    var detected by remember { mutableStateOf<List<JavaRuntime>>(emptyList()) }
    // 这一页要回答的不只是「有哪些 Java」，还有「我手上这几个版本各自够不够」。
    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf("") }
    var detecting by remember { mutableStateOf(false) }

    var installing by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var installingMajor by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var noticeIsError by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf("") }
    // 待确认删除的那一份；不问一句就从盘上抹掉 150 MB 太狠。
    var pendingDelete by remember { mutableStateOf<JavaRuntime?>(null) }

    // 认作「托管的」所有根：启动器目录的 java/（新版）与游戏目录的 runtime/（老版本）都算。
    // 拿不到启动器目录时只剩后一个 —— 老用户的那份仍删得掉，只是界面上不会出现新版那份。
    val runtimeRoots = remember(launcherDir, gameRoot) {
        val roots = ArrayList<File>(2)
        launcherDir?.let { roots += ManagedJava.rootOf(it) }
        if (gameRoot.isNotBlank()) roots += ManagedJava.legacyRootOf(File(gameRoot))
        roots.distinctBy { it.absoluteFile.normalize().path.lowercase() }
    }

    fun rootProblem(): String? {
        if (backend == null) return "后端没装配，读不到 Java 清单。"
        if (launcherDir == null && gameRoot.isBlank()) {
            return "不知道启动器装在哪儿，也没填游戏目录 —— 托管运行时没地方放。"
        }
        return null
    }

    suspend fun reload() {
        if (backend == null) {
            loading = false
            catalogError = "后端没装配，读不到 Java 清单。"
            return
        }
        loading = true
        catalogError = ""
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val catalog = backend.javaRuntimes.catalog()
                val local = backend.javaRuntimes.detect()
                // 实例读失败不该连带整页报错：没有实例列表就只是少一张卡。
                val owned = runCatching { backend.instances.list() }.getOrDefault(emptyList())
                Triple(catalog, local, owned)
            }
        }
        loading = false
        result.onSuccess { (list, local, owned) ->
            offers = list
            detected = local
            instances = owned
            if (list.isEmpty()) {
                catalogError = "这台机器一份可下载的运行时都没列出来。可能是断网，也可能是当前平台官方没提供。"
            }
        }.onFailure {
            catalogError = "读 Java 清单失败：" + (it.message ?: it.javaClass.simpleName)
        }
    }

    fun download(offer: JavaRuntimeOffer) {
        if (backend == null || installing.isNotBlank()) return
        val rootIssue = rootProblem()
        if (rootIssue != null) {
            notice = rootIssue
            noticeIsError = true
            return
        }
        installing = offer.component
        installingMajor = offer.majorVersion
        progress = 0f
        noticeIsError = false
        notice = ""
        scope.launch {
            val result = runCatching {
                backend.javaRuntimes.download(offer.majorVersion, offer.component) { fraction ->
                    progress = fraction
                }
            }
            result.onSuccess { runtime ->
                notice = "Java " + offer.majorVersion + " 已就绪：" + runtime.path
                noticeIsError = false
                installing = ""
                reload()
            }.onFailure {
                notice = "下载 Java " + offer.majorVersion + " 失败：" + (it.message ?: it.javaClass.simpleName)
                noticeIsError = true
                installing = ""
            }
        }
    }

    // 游戏目录变了要重读（清单里「已装」的判定靠它），侧边栏点「↻」也要重读。
    LaunchedEffect(backend, gameRoot, refreshTick) { reload() }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingCard("Java 运行时", "启动器可以自己下官方运行时，装在启动器自己的 java/ 里（便携版就是解压出来那个文件夹）。") {
            Text(
                "挑一个版本下回来；已经装好的那几行会标出来。不用手动找 java.exe。" +
                    "装在别处的 Java（PATH、JAVA_HOME、其它启动器的 runtime）不会显示在这一栏里，但启动时会自动扫到。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (loading) {
                Text("正在读官方清单…", style = MaterialTheme.typography.bodySmall)
            }
            if (catalogError.isNotBlank()) {
                Text(
                    catalogError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (installing.isNotBlank()) {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(
                    "正在下载 Java " + installingMajor + "（" + (progress * 100).toInt() + "%）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (offers.isNotEmpty()) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(4.dp)) {
                        items(offers, key = { it.component }) { offer ->
                            RuntimeRow(
                                offer = offer,
                                busy = installing.isNotBlank(),
                                downloading = installing == offer.component,
                                onDownload = { download(offer) },
                            )
                        }
                    }
                }
            }
        }

        SettingCard(
            "各版本要的 Java",
            "按每个版本要的主版本算了一遍。同一份 Java 能覆盖的归到一行，省得逐个点开看。",
        ) {
            if (instances.isEmpty()) {
                Text(
                    "还没有登记过版本。先去「启动」页装一个，这里才有东西可算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 按主版本归并再算：几十个实例往往只需要两三种 Java，逐个算既慢又会把同一句话说很多遍。
                val grouped = instances
                    .groupBy { GameJavaRules.requiredMajor(it.gameVersion) }
                    .entries
                    .sortedBy { it.key }
                for (entry in grouped) {
                    val major = entry.key
                    val owned = entry.value
                    val rec = JavaRecommender.recommend(major, detected, offers)
                    JavaNeedRow(
                        major = major,
                        instances = owned,
                        recommendation = rec,
                        busy = installing.isNotBlank(),
                        onDownload = { rec.offer?.let { download(it) } },
                    )
                }
            }
        }

        SettingCard("本机已经找到的 Java", "启动游戏时会从这些里挑；一个都对不上才用上面的托管版本。") {
            OutlinedButton(
                onClick = {
                    if (backend == null) return@OutlinedButton
                    scope.launch {
                        detecting = true
                        // 点了「重新探测」就得真去探，不能 30 秒内的缓存糊弄过去。
                        runCatching {
                            withContext(Dispatchers.IO) { backend.javaRuntimes.detect(useCache = false) }
                        }
                            .onSuccess { detected = it }
                            .onFailure {
                                notice = "重新探测失败：" + (it.message ?: it.javaClass.simpleName)
                                noticeIsError = true
                            }
                        detecting = false
                    }
                },
                enabled = backend != null && !detecting,
            ) { Text(if (detecting) "探测中…" else "重新探测") }
            if (detected.isEmpty()) {
                Text(
                    "一个都没探测到。上表下载一份托管运行时之后就会出现在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (runtime in detected) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Java " + runtime.majorVersion,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            runtime.path + if (runtime.managed) "（启动器下载的）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        // 只给「启动器自己下的、且确实落在托管目录里」的那几份画删除。
                        // 系统 JDK、PCL/HMCL 留下的 runtime 在探测结果里也有，但删它们不是我们该干的事。
                        if (ManagedJava.removable(runtime, runtimeRoots)) {
                            TextButton(
                                onClick = { pendingDelete = runtime },
                                enabled = deleting.isBlank(),
                            ) { Text(if (deleting == runtime.path) "删除中…" else "删除") }
                        }
                    }
                }
            }
        }

        // 删除确认：说清楚删的是哪一份、删了会怎样。
        val doomed = pendingDelete
        if (doomed != null) {
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("删掉这份 Java？") },
                text = {
                    Text(
                        "Java " + doomed.majorVersion + "\n" + doomed.path +
                            "\n\n会直接删掉整个运行时目录，不进回收站。" +
                            "删完之后这个版本要用的 Java 会重新探测；本机一份都不剩的话，" +
                            "下次启动时启动器会自己下一份 Java 17。",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            if (backend == null) return@TextButton
                            deleting = doomed.path
                            scope.launch {
                                val removed = runCatching {
                                    withContext(Dispatchers.IO) { backend.javaRuntimes.remove(doomed) }
                                }
                                deleting = ""
                                removed.onSuccess { ok ->
                                    if (ok) {
                                        notice = "已删掉 Java " + doomed.majorVersion + "：" + doomed.path
                                        noticeIsError = false
                                        reload()
                                    } else {
                                        // 具体原因在桌面实现里（lastRemoveError），界面拿不到类型，
                                        // 用一条通用说法兜底。
                                        notice = "没能删掉这份 Java。可能它不是启动器下的，或者文件正被占用 —— 关掉游戏再试。"
                                        noticeIsError = true
                                    }
                                }.onFailure {
                                    notice = "删除 Java " + doomed.majorVersion + " 失败：" +
                                        (it.message ?: it.javaClass.simpleName)
                                    noticeIsError = true
                                }
                            }
                        },
                    ) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("取消") }
                },
            )
        }

        NoticeFade(notice) { shown ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (noticeIsError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ),
            ) {
                Text(shown, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 表里的一行：Java 主版本 + 官方版本号 + 已装标记或下载按钮。 */
@Composable
private fun RuntimeRow(
    offer: JavaRuntimeOffer,
    busy: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (downloading) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Java " + offer.majorVersion,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                offer.versionName + " · " + offer.component,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 跨模块的属性没有智能转换，先取到局部变量再判空。
            val installedAt = offer.installedPath
            if (offer.installed && !installedAt.isNullOrBlank()) {
                Text(
                    installedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (downloading) {
            Text("下载中…", style = MaterialTheme.typography.labelSmall)
        } else if (offer.installed) {
            Text(
                "已装",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Button(onClick = onDownload, enabled = !busy) { Text("下载") }
        }
    }
}

/**
 * 「某个主版本要的 Java，本机够不够」一行。
 *
 * 为什么按主版本归并而不是逐实例列：几十个实例往往只要两三种 Java，
 * 逐条列会把同一个结论重复很多遍，真正的差异（哪个版本没 Java）反而被淹没。
 *
 * 右边那颗按钮只在「本机没有、而清单里正好有这项」时才出现 ——
 * 缺的版本官方没有时给一颗点了会失败的按钮，比不给更糟。
 */
@Composable
private fun JavaNeedRow(
    major: Int,
    instances: List<Instance>,
    recommendation: JavaRecommendation,
    busy: Boolean,
    onDownload: () -> Unit,
) {
    val tint = when (recommendation.verdict) {
        JavaVerdict.READY -> MaterialTheme.colorScheme.primary
        JavaVerdict.CAN_DOWNLOAD -> MaterialTheme.colorScheme.onSurface
        JavaVerdict.NOT_AVAILABLE, JavaVerdict.UNKNOWN_NEED -> MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Java " + major,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            // 名字最多点三个，剩下的折成「等 N 个」：这一行的结论跟具体是哪个版本无关。
            val names = instances.map { it.name.ifBlank { it.id } }
            val shownNames = names.take(3).joinToString("、")
            Text(
                if (names.size > 3) shownNames + " 等 " + names.size + " 个版本" else shownNames,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                JavaRecommender.describe(recommendation),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (recommendation.verdict == JavaVerdict.READY) {
            Text("够用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } else if (recommendation.verdict == JavaVerdict.CAN_DOWNLOAD && recommendation.offer != null) {
            Button(onClick = onDownload, enabled = !busy) { Text("下载") }
        }
    }
}
