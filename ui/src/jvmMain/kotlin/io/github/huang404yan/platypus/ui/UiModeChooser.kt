package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.edition.FirstRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 第一次进主界面时问一句「想看得全一点，还是少一点」。
 *
 * 只问一次：选完写进设置的 uiMode，之后（含重装后复用设置文件）都不再问，
 * 想换去「设置 → 界面」，那里点一下立刻生效。
 *
 * 为什么排在首次运行那扇门**之后**而不是之前：那扇门在正式版里是不许关的
 * （没有正版账号就一直挡着）。把模式选择摆在它前面，等于给「点两下就绕过登录」开了一条路。
 * 所以开关由上层传进来（[tick] 在首次运行收掉时加一），而不是自己判断先后。
 *
 * 为什么给两个大卡片而不是一个下拉框：这一次要选的东西用户从没见过的界面长什么样，
 * 光看「完整 / 简洁」四个字选不出来，卡片里得写清各少什么。
 */
@Composable
internal fun UiModeChooserGate(
    backend: LauncherBackend?,
    /** 首次运行那扇门走完时加一：那一刻才知道现在该不该问。 */
    tick: Int,
    onPicked: (UiMode) -> Unit,
) {
    val repository = backend?.settings
    val scope = rememberCoroutineScope()
    // null = 还没判完，先不画。留空比画一个再收掉好，后者会让字跳一下。
    var showing by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(repository, tick) {
        if (repository == null) {
            showing = false
            return@LaunchedEffect
        }
        val settings = withContext(Dispatchers.IO) {
            runCatching { repository.load() }.getOrNull()
        } ?: run {
            showing = false
            return@LaunchedEffect
        }
        // 两条都不问：首次运行还没走完（先让那扇门办完事），或者已经选过了。
        showing = !FirstRun.needed(settings) && settings.uiMode.isBlank()
    }

    if (showing != true) return

    fun pick(mode: UiMode) {
        if (busy) return
        busy = true
        onPicked(mode)
        scope.launch {
            // 写不进去也把弹窗收掉：一个写不进去的设置文件不该把人卡在欢迎页。
            // 代价是下次开还会问一遍，比「进不去」好 —— 与首次运行那扇门同一个取舍。
            runCatching {
                val latest = repository?.load() ?: return@runCatching
                repository.save(latest.copy(uiMode = mode.id))
            }
            showing = false
        }
    }

    AlertDialog(
        // 没有「关掉」这条路：点外面、按 Esc 都算选了完整模式（什么都有，不会让人找不到功能）。
        onDismissRequest = { pick(UiMode.FULL) },
        title = { Text("先选一下怎么看") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "两档看的是同一份东西，只是收起来多少不一样。以后想换，" +
                        "去「设置 → 界面」点一下就行，不用重装。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                UiMode.entries.forEach { mode ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { pick(mode) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    mode.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    uiModeNoteOf(mode),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "用这个",
                                Modifier.width(56.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 主按钮故意留空：两条路都在正文里，按哪个都是选。给一个默认主按钮
            // 反而会让用户以为「不点卡片就不算选」。
            TextButton(onClick = { pick(UiMode.FULL) }) { Text("以后再说") }
        },
    )
}
