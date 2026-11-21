package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
 * 给一个实例改分组。
 *
 * 只用一个文本框 + 几个「最近用过的组」的点选块，不做「新建组 / 删除组」的独立管理页：
 * 组是从实例上长出来的，没有实例的组留着也没意义，改空一个组的最后一条实例，那个组
 * 自己就消失了（[io.github.huang404yan.platypus.core.instance.sectionize] 只认实例上的名字）。
 *
 * 那个选择「点已有组名直接填进框」而不是「点一下就保存」是故意的：点一下就保存会让
 * 「我想把它改到联机组」变成无法反悔的单步动作，而对话框还在，用户以为没生效会再点一次。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupDialog(
    instanceName: String,
    current: String,
    knownGroups: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 调用方按实例的 key 重建这个对话框，所以初值只在打开那一下算一次就够了 ——
    // 对话框开着的时候用户在下面点候选块，不该被外部的旧值刷回去。
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给「" + instanceName + "」分组") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        knownGroups.forEach { name ->
                            AssistChip(onClick = { text = name }, label = { Text(name) })
                        }
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
