package io.github.huang404yan.platypus.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.component.Component
import io.github.huang404yan.platypus.core.component.ComponentIcons
import io.github.huang404yan.platypus.core.component.ComponentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一条已装组件的统一长相。
 *
 * 模组、资源包、光影、数据包在磁盘上只差目录名与后缀，界面上要的东西却完全一样：一张图标、
 * 一个名字、一行「文件名叫什么、多大、有没有问题」、右边「启用 / 停用」「删除」。
 * 2026-11-21 之前这套骨架在 [InstalledModsSection] 与 PacksPage 里各写了一遍，于是同一件事
 * 在两页里的行高、图标大小、删除确认的措辞都不一样 —— 用户得重新认一遍。
 *
 * 只统一「骨架」不统一「额外按钮」：模组那行还挂着「更新到 x」「选入队列」，那些是模组
 * 独有的，通过 [trailing] 塞进来。抽象要是把特有动作也吞掉，最后会变成一堆按类型分支的
 * 参数，比各写一遍更难读。
 */
@Composable
internal fun ComponentRow(
    component: Component,
    busy: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    /** 整行点击做什么；null 表示这一行不可点。 */
    onClick: (() -> Unit)? = null,
    /** 排在最前面的插槽：模组页的多选勾选框走这里。 */
    leading: @Composable RowScope.() -> Unit = {},
    /** 覆盖「已启用 · 点这条可停用」那行小字；null 用默认措辞。 */
    hint: String? = null,
    /** 这一类自己才知道的一行字，拼在文件名后面。 */
    extraDetail: List<String> = emptyList(),
    /** 额外按钮，排在「停用」左边。 */
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverAlpha by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = motionTween(if (hovered) Motion.FAST else Motion.BASE),
        label = "componentRowHover",
    )
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
            } else {
                Modifier
            },
        ),
        border = if (hoverAlpha > 0.01f) {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = hoverAlpha * 0.5f),
            )
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = if (component.enabled) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧 5dp 竖条是「启用」标记：勾上时从 0 长到 32dp，取消时缩回去。
            val barHeight by animateDpAsState(
                targetValue = if (component.enabled) 32.dp else 0.dp,
                animationSpec = motionTween(
                    if (component.enabled) Motion.SLOW else Motion.FAST,
                    Motion.decelerate,
                ),
                label = "componentEnabledBar",
            )
            Box(
                Modifier
                    .width(5.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            leading()
            ComponentIcon(component, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        component.title,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (component.enabled) null else TextDecoration.LineThrough,
                        color = if (component.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatSizeShort(component.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    (listOf(component.fileName) + component.detail + extraDetail).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                component.problem?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        hint ?: if (component.enabled) "已启用 · 点这条可停用" else "已停用 · 点这条可启用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    trailing()
                    TextButton(onClick = { onToggleEnabled(!component.enabled) }, enabled = !busy) {
                        Text(if (component.enabled) "停用" else "启用")
                    }
                    TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("删除") }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删掉 " + component.fileName + "？") },
            text = {
                Text(
                    "会直接删掉这个文件，不进回收站。只是暂时不想用的话，点「停用」就行 —— " +
                        "停用只是改文件名，游戏不加载它，随时能点回来。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text("删除文件") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 组件图标。
 *
 * 模组读 jar 里的 icon.png（复用 [ModIcons] 那一套），资源包读根目录 pack.png，
 * 光影包读 preview.png 之类。都拿不到就画首字母 —— 一列清单不该因为缺图变成一排窟窿。
 *
 * 取图在 IO 线程，结果用 produceState 回到组合：第一次绘制不等网络与解压。
 */
@Composable
internal fun ComponentIcon(component: Component, size: Dp = 40.dp) {
    val file by produceState<File?>(initialValue = null, component.path, component.sizeBytes) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ComponentIcons.iconOf(
                    kind = component.kind,
                    file = File(component.path),
                    fileName = component.fileName,
                    cacheDir = componentIconCacheDir(),
                )
            }.getOrNull()
        }
    }
    ComponentIconBox(file = file, title = component.title, size = size)
}

/** 图标缓存目录，与 ModIcon 共用一份（同一个临时目录下的 icons/）。 */
private fun componentIconCacheDir(): File =
    File(File(System.getProperty("java.io.tmpdir") ?: ".", "platypus-mod-icons"), "icons")

@Composable
private fun ComponentIconBox(file: File?, title: String, size: Dp) {
    val bitmap = rememberIconBitmap(file)
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                title.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「已停用」分组：单独一段、摆在启用列表上面、默认摊开。
 *
 * 理由与模组那边一样：停用的通常就几个，又正是回来要找的那几个，摊进一条长列表就沉底了。
 */
@Composable
internal fun DisabledComponentsGroup(
    components: List<Component>,
    busy: Boolean,
    onToggleEnabled: (Component, Boolean) -> Unit,
    onDelete: (Component) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已停用 " + components.size + " 个",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
            }
            Text(
                "停用 = 文件名后面加 .disabled，游戏不会加载它。点条目或右边的「启用」就能恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    components.forEach { component ->
                        ComponentRow(
                            component = component,
                            busy = busy,
                            onToggleEnabled = { onToggleEnabled(component, it) },
                            onDelete = { onDelete(component) },
                            onClick = { onToggleEnabled(component, true) },
                        )
                    }
                }
            }
        }
    }
}
