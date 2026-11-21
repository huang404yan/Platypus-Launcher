package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.MAX_GROUP_NAME_LENGTH
import io.github.huang404yan.platypus.core.instance.normalizeGroupName

/**
 * 给一个实例改分组（手机版）。
 *
 * 与桌面 (`ui/GroupDialog.kt`) 同一套语义：组名归一化走 core 的 [normalizeGroupName]，
 * 落盘只写 `instances.json`，不动版本目录里的任何文件 —— 手机上的 versions/ 也可能被
 * 别的启动器、整合包、甚至从电脑拷过来的文件夹共用。
 *
 * 已有组这里是竖排一行一个的文字按钮，不是桌面那样的 FlowRow 芯片：手机竖屏一行放不下
 * 两个多字的组名，芯片会折成参差不齐的两三行，比列表还难扫。
 *
 * 点候选只把名字填进框里，不直接保存 —— 理由同桌面：点一下就保存会让「手滑点错组」
 * 变成无法反悔的单步动作。
 */
@Composable
internal fun GroupDialog(
    instanceName: String,
    current: String,
    knownGroups: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 调用方按实例的 key 重建这个对话框，初值只在打开那一下算一次：
    // 对话框开着时用户点候选、改字，不该被外部的旧值刷回去。
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给「" + instanceName + "」分组") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "组名随便起，比如「1.20 联机」「Mod 战斗」。留空就是不分组。" +
                        "分组只影响这一页怎么摆，不动磁盘上的任何文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= MAX_GROUP_NAME_LENGTH * 2) text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("组名（最多 " + MAX_GROUP_NAME_LENGTH + " 个字）") },
                    singleLine = true,
                )
                if (knownGroups.isNotEmpty()) {
                    Text(
                        "已经有的组：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    knownGroups.forEach { name ->
                        TextButton(onClick = { text = name }) { Text(name) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(normalizeGroupName(text)) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
