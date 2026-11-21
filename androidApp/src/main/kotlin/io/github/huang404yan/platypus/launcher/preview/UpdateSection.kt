package io.github.huang404yan.platypus.launcher.preview

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.update.ReleaseInfo
import io.github.huang404yan.platypus.core.update.UpdateChecker
import kotlinx.coroutines.launch

/**
 * 检查更新（安卓版）。
 *
 * 与桌面 `ui/UpdateSection.kt` 是同一套结论、同一套状态机，差别只有出货方式：
 * 桌面推 .msi / .zip，手机上只有一个 .apk，装法也变成「用浏览器下完再点开安装包」。
 *
 * 仍然只回答「有没有新版、去哪下」：不在应用内下载，也不自己触发安装。
 * 安卓侧自己装自己的 apk 需要 `REQUEST_INSTALL_PACKAGES`，那是比「检查一下」重得多
 * 的权限；用户手动点一次安装包，路径短、心态清楚。
 *
 * 进关于页自动查一次。查失败只在原地写一句原因，不弹窗：来这一页多半只是想看版本号。
 */
@Composable
internal fun UpdateSection() {
    var state by remember { mutableStateOf<AndroidUpdateUiState>(AndroidUpdateUiState.Idle) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun check() {
        if (state == AndroidUpdateUiState.Checking) return
        state = AndroidUpdateUiState.Checking
        scope.launch {
            state = runCatching { UpdateChecker.check() }.fold(
                onSuccess = { result ->
                    val latest = result.latest
                    if (latest != null) AndroidUpdateUiState.Available(latest) else AndroidUpdateUiState.UpToDate
                },
                onFailure = { error -> AndroidUpdateUiState.Failed(updateReasonOf(error)) },
            )
        }
    }

    LaunchedEffect(Unit) { check() }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("更新", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when (state) {
                        AndroidUpdateUiState.Idle, AndroidUpdateUiState.UpToDate -> "已是最新"
                        AndroidUpdateUiState.Checking -> "正在检查…"
                        is AndroidUpdateUiState.Available -> "有新版本"
                        is AndroidUpdateUiState.Failed -> "没查到"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (state) {
                        is AndroidUpdateUiState.Available -> MaterialTheme.colorScheme.primary
                        is AndroidUpdateUiState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            when (val current = state) {
                AndroidUpdateUiState.Idle -> Text(
                    "还没查过。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AndroidUpdateUiState.Checking -> Text(
                    "正在问 GitHub 上有没有比 " + Brand.version + " 更新的发布。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AndroidUpdateUiState.UpToDate -> Text(
                    "这一版（" + Brand.version + "）就是最新的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is AndroidUpdateUiState.Failed -> Text(
                    current.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                is AndroidUpdateUiState.Available -> {
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
                            summarizeRelease(release.body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val apk = release.assetWith("apk")
                        if (apk != null) {
                            Button(onClick = { openInBrowser(context, apk.downloadUrl) }) {
                                Text("下载 .apk（" + sizeText(apk.size) + "）")
                            }
                            Text(
                                "下完点开那个 apk 手装一次，覆盖安装即可。存档与设置都保存在应用数据中，不会被清除。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "这一版没挂 apk 安装包，去发布页看看。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { openInBrowser(context, release.htmlUrl.ifBlank { Brand.HOMEPAGE }) }) {
                            Text("打开发布页")
                        }
                    }
                }
            }

            TextButton(onClick = { check() }, enabled = state != AndroidUpdateUiState.Checking) {
                Text(if (state is AndroidUpdateUiState.Available) "再查一次" else "检查更新")
            }
            Text(
                "启动器不会自己把自己换掉：下载安装都交给你。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private sealed interface AndroidUpdateUiState {
    data object Idle : AndroidUpdateUiState
    data object Checking : AndroidUpdateUiState
    data object UpToDate : AndroidUpdateUiState
    data class Available(val release: ReleaseInfo) : AndroidUpdateUiState
    data class Failed(val message: String) : AndroidUpdateUiState
}

/** 发布说明通常很长，这里只留开头几行，剩下的去发布页看。 */
private fun summarizeRelease(body: String): String {
    val lines = body.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val head = lines.take(6).joinToString("\n")
    return if (lines.size > 6) head + "\n（还有 " + (lines.size - 6) + " 行，去发布页看全部）" else head
}

private fun updateReasonOf(error: Throwable): String {
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

/**
 * 用系统浏览器打开。
 *
 * 加 NEW_TASK 是因为 context 理论上可能不是 Activity（Compose 的 LocalContext 在
 * Activity 里就是它本身，但此处不做此假设）；打不开就静默放弃，下面「仓库」那一行的地址仍可复制。
 */
private fun openInBrowser(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
