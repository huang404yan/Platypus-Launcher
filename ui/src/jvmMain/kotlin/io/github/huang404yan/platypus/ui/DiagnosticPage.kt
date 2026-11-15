package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.auth.StoredAccount
import io.github.huang404yan.platypus.core.launch.CrashReport
import io.github.huang404yan.platypus.core.launch.CrashReports
import io.github.huang404yan.platypus.core.runtime.JavaRuntime
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断页（顶栏「更多 → 诊断」）。
 *
 * 存在的理由只有一个：用户来报「进不去游戏」的时候，能一次性把该问的东西
 * 抄成一段文字贴出来。所以在场的东西必须恰好是排查时要问的 ——
 * 系统与 Java 是什么、游戏目录与设置文件在哪、装了几个版本、登的是谁、
 * 最近一次崩在哪儿。**不在这里做「修复」按钮**：这一页只负责把事实摆出来，
 * 动手改东西的地方各有各的页面，混在一起会让人不敢点。
 */
@Composable
fun DiagnosticPage(backend: LauncherBackend? = null, initialGameRoot: String? = null) {
    var gameRoot by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    var runtimes by remember { mutableStateOf<List<JavaRuntime>>(emptyList()) }
    var detecting by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf<List<StoredAccount>>(emptyList()) }
    var instances by remember { mutableStateOf(0) }
    var crash by remember { mutableStateOf<CrashReport?>(null) }
    var settingsPath by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun collect() {
        val root = File(gameRoot.trim())
        crash = withContext(Dispatchers.IO) { runCatching { CrashReports.read(root) }.getOrNull() }
        settingsPath = withContext(Dispatchers.IO) {
            runCatching { backend?.settings?.load()?.defaultGameRoot }.getOrNull().orEmpty()
        }
        instances = withContext(Dispatchers.IO) {
            runCatching { backend?.instances?.list()?.size ?: 0 }.getOrDefault(0)
        }
        accounts = withContext(Dispatchers.IO) {
            runCatching { backend?.accounts?.list() }.getOrNull().orEmpty()
        }
    }

    fun detectJava() {
        val service = backend?.javaRuntimes ?: return
        if (detecting) return
        detecting = true
        scope.launch {
            runtimes = try {
                service.detect()
            } catch (t: Throwable) {
                notice = "探测 Java 失败：" + (t.message ?: t.javaClass.simpleName)
                emptyList()
            }
            detecting = false
        }
    }

    LaunchedEffect(gameRoot) {
        collect()
        detectJava()
    }

    val lines = diagnosticLines(gameRoot, runtimes, accounts, instances, crash, settingsPath)

    fun copyAll() {
        notice = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(lines.joinToString("\n")), null)
            "已复制：粘到哪儿都行，聊天窗口、issue 都认这段文字。"
        }.getOrElse { "复制失败：" + (it.message ?: it.javaClass.simpleName) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("诊断", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "这一页只把排查要问的东西摆出来，不改任何东西。要报问题就把下面这段整个复制走。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { copyAll() }) { Text("复制诊断信息") }
            OutlinedButton(
                onClick = { scope.launch { collect(); detectJava() } },
                enabled = !detecting,
            ) { Text(if (detecting) "探测中…" else "重新收集") }
        }
        if (notice.isNotBlank()) {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DiagCard("系统") {
            DiagLine("启动器", (if (Brand.isPreview) Brand.PREVIEW_NAME_ZH else Brand.NAME_ZH) + " " + Brand.version)
            DiagLine("系统", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
            DiagLine("Java（启动器自己跑在）", System.getProperty("java.version") + " · " + System.getProperty("java.vendor"))
            DiagLine("用户目录", System.getProperty("user.home").orEmpty())
        }

        DiagCard("游戏目录") {
            DiagLine("根目录", gameRoot)
            DiagLine("这个目录是游戏目录吗", if (File(gameRoot.trim()).isDirectory) "是" else "不存在")
            DiagLine("已登记的版本", instances.toString() + " 个")
            if (settingsPath.isNotBlank()) DiagLine("设置里的默认目录", settingsPath)
        }

        DiagCard("Java 运行时") {
            if (runtimes.isEmpty()) {
                DiagLine("结果", if (detecting) "还在探测…" else "一个都没找到")
            } else {
                runtimes.forEach { runtime ->
                    DiagLine(
                        "Java " + runtime.majorVersion,
                        runtime.path + (runtime.vendor?.let { " · " + it } ?: "") +
                            (if (runtime.managed) " · 启动器托管" else ""),
                    )
                }
            }
        }

        DiagCard("登录的账号") {
            if (accounts.isEmpty()) {
                DiagLine("结果", "没有登录过账号，启动时用离线身份")
            } else {
                accounts.forEach { account ->
                    DiagLine(
                        account.name + ("（" + account.kind + "）"),
                        if (account.isYggdrasil) account.serverUrl else account.uuid,
                    )
                }
            }
        }

        DiagCard("最近一次崩溃") {
            val found = crash
            if (found == null) {
                DiagLine("结果", "这个版本目录下没有 crash-reports")
            } else {
                DiagLine("文件", found.fileName)
                DiagLine("大小", formatBytes(found.bytes))
                if (found.description.isNotBlank()) DiagLine("Description", found.description)
                if (found.exception.isNotBlank()) DiagLine("第一个异常", found.exception)
            }
        }

        Text("可以粘贴出去的版本", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            SelectionContainer {
                Text(
                    lines.joinToString("\n"),
                    Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 拼成一段能直接贴出去的纯文本。
 *
 * 单独抽成函数是为了它能被单测覆盖：这一段是用户唯一会复制走的东西，
 * 少了哪一行（比如忘了写 Java 主版本）就等于白问一轮。
 */
fun diagnosticLines(
    gameRoot: String,
    runtimes: List<JavaRuntime>,
    accounts: List<StoredAccount>,
    instances: Int,
    crash: CrashReport?,
    settingsPath: String,
): List<String> {
    val out = mutableListOf<String>()
    out += "启动器：" + (if (Brand.isPreview) Brand.PREVIEW_NAME_ZH else Brand.NAME_ZH) + " " + Brand.version
    out += "系统：" + System.getProperty("os.name") + " " + System.getProperty("os.arch")
    out += "自带的 Java：" + System.getProperty("java.version") + "（" + System.getProperty("java.vendor") + "）"
    out += "游戏目录：" + gameRoot + if (File(gameRoot.trim()).isDirectory) "（存在）" else "（不存在）"
    if (settingsPath.isNotBlank()) out += "设置里的默认目录：" + settingsPath
    out += "已登记的版本：" + instances + " 个"
    if (runtimes.isEmpty()) {
        out += "找到的 Java：一个都没有"
    } else {
        runtimes.forEach { runtime ->
            out += "找到的 Java：" + runtime.majorVersion + " · " + runtime.path +
                (runtime.vendor?.let { " · " + it } ?: "") +
                (if (runtime.managed) " · 启动器托管" else "")
        }
    }
    if (accounts.isEmpty()) {
        out += "登录的账号：没有（用的是离线身份）"
    } else {
        accounts.forEach { account ->
            out += "登录的账号：" + account.name + "（" + account.kind + "）"
        }
    }
    if (crash == null) {
        out += "最近崩溃：没有 crash-reports"
    } else {
        out += "最近崩溃：" + crash.fileName +
            (crash.description.takeIf { it.isNotBlank() }?.let { " · " + it } ?: "") +
            (crash.exception.takeIf { it.isNotBlank() }?.let { " · " + it } ?: "")
    }
    return out
}

@Composable
private fun DiagCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            label,
            Modifier.weight(0.36f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(value.ifBlank { "—" }, Modifier.weight(0.64f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
