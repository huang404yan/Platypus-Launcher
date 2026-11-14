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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *   （在 <游戏目录>/runtime/ 下、删得掉、不会被系统 Java 更新牵连）。
 *   「本机有没有能用的」是「启动」页那个按钮该操心的事（见 [JavaRuntimeService.ensure]）。
 * - **不做高版本顶替**。表里每一项都是精确主版本，界面也不推荐「装个更高的凑合用」：
 *   1.16.5 的 Forge 在 Java 16+ 上会直接崩（[GameJavaRules] 里那条规则就是这么来的）。
 */
@Composable
internal fun JavaTab(backend: LauncherBackend?, gameRoot: String, refreshTick: Int = 0) {
    val scope = rememberCoroutineScope()

    var offers by remember { mutableStateOf<List<JavaRuntimeOffer>>(emptyList()) }
    var detected by remember { mutableStateOf<List<JavaRuntime>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf("") }
    var detecting by remember { mutableStateOf(false) }

    var installing by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var installingMajor by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var noticeIsError by remember { mutableStateOf(false) }

    fun rootProblem(): String? {
        if (backend == null) return "后端没装配，读不到 Java 清单。"
        if (gameRoot.isBlank()) return "先去「启动」页把游戏目录填好 —— 托管运行时会下到那个目录的 runtime/ 下。"
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
            withContext(Dispatchers.IO) { backend.javaRuntimes.catalog() to backend.javaRuntimes.detect() }
        }
        loading = false
        result.onSuccess { (list, local) ->
            offers = list
            detected = local
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
        SettingCard("Java 运行时", "启动器可以自己下官方运行时，装在这里：<游戏目录>/runtime/。") {
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

        SettingCard("本机已经找到的 Java", "启动游戏时会从这些里挑；一个都对不上才用上面的托管版本。") {
            OutlinedButton(
                onClick = {
                    if (backend == null) return@OutlinedButton
                    scope.launch {
                        detecting = true
                        runCatching { withContext(Dispatchers.IO) { backend.javaRuntimes.detect() } }
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Java " + runtime.majorVersion,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            runtime.path + if (runtime.managed) "（启动器下载的）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (notice.isNotBlank()) {
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
                Text(notice, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
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
