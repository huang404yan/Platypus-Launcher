package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.search.SearchKeywords
import io.github.huang404yan.platypus.core.search.SearchTerm

/**
 * 中文搜索时给的提示与可点候选。
 *
 * 背景：Modrinth 的 `/search?query=` 不认中文，传过去不报错、只是安静地返回空列表。
 * 用户看到的现象是「搜什么都搜不到」，很容易以为启动器坏了。这个组件把三件事讲清楚：
 * 1. 为什么搜不到（[SearchKeywords.modrinthNote]）；
 * 2. 你想搜的那个东西现在该填什么（可点的英文候选，点一下直接填进搜索框）；
 * 3. 还没想好搜什么时，给几个这一类里常用的词。
 *
 * 英文查询一律不渲染任何东西 —— 那时候用户不需要被教育。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChineseSearchHint(
    query: String,
    kind: SearchTerm.Kind,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cjk = SearchKeywords.looksCjk(query)
    val suggestions = if (cjk) SearchKeywords.translate(query, kind) else emptyList()
    // 空查询时给热门词：比一句「输入名字后点搜索」更能让人动手
    val popular = if (query.isBlank()) SearchKeywords.popular(kind) else emptyList()

    if (!cjk && popular.isEmpty()) return

    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (cjk) {
                Text(
                    "搜不到是因为 Modrinth 不认中文",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    SearchKeywords.modrinthNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (suggestions.isEmpty()) {
                    Text(
                        "这个词库里还没有对应的英文名。去 Modrinth 网页版搜到之后，把地址栏里的 slug（例如 sodium）填进来。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "点一个填进搜索框：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { en ->
                            AssistChip(onClick = { onPick(en) }, label = { Text(en) })
                        }
                    }
                }
            } else {
                Text(
                    "常用关键词，点一个直接搜：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    popular.forEach { term ->
                        AssistChip(onClick = { onPick(term.en) }, label = { Text(term.zh + " · " + term.en) })
                    }
                }
            }
        }
    }
}
