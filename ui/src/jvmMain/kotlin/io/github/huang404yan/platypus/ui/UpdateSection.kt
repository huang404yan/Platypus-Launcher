package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.update.ReleaseInfo
import io.github.huang404yan.platypus.core.update.UpdateChecker
import java.awt.Desktop
import kotlinx.coroutines.launch

/**
 * 检查更新。
 *
 * 只回答「有没有新版、说明写在哪、去哪下」—— 不自动下载、更不自动替换正在跑的 exe。
 * 理由见 core 里 UpdateChecker 的 KDoc：自我替换要处理文件占用、签名校验与半途失败回滚，
 * 任何一步没做好就是一个打不开的启动器；而打不开的启动器没法再靠它自己修回来。
 *
 * 进关于页就自动查一次。查失败不当成错误弹窗，只在原地写一句原因 —— 用户来这一页
 * 多数是想看版本号，网络不通不该妨碍他。
 */
@Composable
internal fun UpdateSection() {
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    val scope = rememberCoroutineScope()

    fun check() {
        if (state == UpdateUiState.Checking) return
        state = UpdateUiState.Checking
        scope.launch {
            state = runCatching { UpdateChecker.check() }.fold(
                onSuccess = { result ->
                    val latest = result.latest
                    if (latest != null) UpdateUiState.Available(latest) else UpdateUiState.UpToDate
                },
                onFailure = { error -> UpdateUiState.Failed(reasonOf(error)) },
            )
        }
    }

    LaunchedEffect(Unit) { check() }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("更新", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when (state) {
                        UpdateUiState.Idle, UpdateUiState.UpToDate -> "已是最新"
                        UpdateUiState.Checking -> "正在检查…"
                        is UpdateUiState.Available -> "有新版本"
                        is UpdateUiState.Failed -> "没查到"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (state) {
                        is UpdateUiState.Available -> MaterialTheme.colorScheme.primary
                        is UpdateUiState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            when (val current = state) {
                UpdateUiState.Idle -> Text(
                    "还没查过。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                UpdateUiState.Checking -> Text(
                    "正在问 GitHub 上有没有比 " + Brand.version + " 更新的发布。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                UpdateUiState.UpToDate -> Text(
                    "这一版（" + Brand.version + "）就是最新的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is UpdateUiState.Failed -> Text(
                    current.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                is UpdateUiState.Available -> {
                    val release = current.release
                    Text(
                        "新版 " + release.version + " 已经发布（你现在是 " + Brand.version + "）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (release.publishedAt.isNotBlank()) {
                        Text(
                            "发布于 " + release.publishedAt.substringBefore('T'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (release.body.isNotBlank()) {
                        Text(
                            summarize(release.body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val msi = release.assetWith("msi")
                        if (msi != null) {
                            Button(onClick = { openBrowser(msi.downloadUrl) }) {
                                Text("下载 .msi（" + sizeText(msi.size) + "）")
                            }
                        }
                        val zip = release.assetWith("zip")
                        if (zip != null) {
                            OutlinedButton(onClick = { openBrowser(zip.downloadUrl) }) {
                                Text("下载 .zip")
                            }
                        }
                        OutlinedButton(onClick = { openBrowser(release.htmlUrl.ifBlank { Brand.HOMEPAGE }) }) {
                            Text("打开发布页")
                        }
                    }
                }
            }

            TextButton(onClick = { check() }, enabled = state != UpdateUiState.Checking) {
                Text(if (state is UpdateUiState.Available) "再查一次" else "检查更新")
            }
            Text(
                "启动器不会自己把自己换掉：下载安装都交给你，装完再打开就是新版。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/** 发布说明通常很长，这里只留开头几行，剩下的去发布页看。 */
private fun summarize(body: String): String {
    val lines = body.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val head = lines.take(6).joinToString("\n")
    return if (lines.size > 6) head + "\n（还有 " + (lines.size - 6) + " 行，去发布页看全部）" else head
}

private fun reasonOf(error: Throwable): String {
    val message = error.message?.trim().orEmpty()
    return if (message.isNotEmpty()) message else "连不上 GitHub（" + (error::class.simpleName ?: "未知错误") + "）。"
}

private fun sizeText(bytes: Long): String = when {
    bytes <= 0L -> "大小未知"
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}

/** 用系统默认浏览器打开。打不开就什么都不做 —— 用户手里还有「仓库」那一行的地址可以复制。 */
private fun openBrowser(url: String) {
    if (url.isBlank()) return
    runCatching {
        if (!Desktop.isDesktopSupported()) return@runCatching
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching
        desktop.browse(java.net.URI(url))
    }
}