package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.mods.ModEntry
import io.github.huang404yan.platypus.core.mods.ModIcons
import io.github.huang404yan.platypus.core.mods.ModProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图标缓存目录。整个进程共用一份，和 core 里的 [ModIcons] 落盘位置一致。
 *
 * 用 lazy 而不是 remember：它跟界面生命周期无关，换页、重建包装都不该重新算路径。
 */
private val iconCacheDir: File by lazy { ModIcons.defaultCacheDir() }

/**
 * 一个模组的方图标（照 Modrinth 网页那种方形缩略图）。
 *
 * 取图分两条路：搜索结果里的 [ModProject] 走它的 icon_url 联网拉；本地已装的 [ModEntry]
 * 走 jar 里的 icon.png。两条都可能拿不到（断网、作者没传图），拿不到就画首字母占位 ——
 * 列表不会因为一张图变成一排窟窿。
 *
 * 取图在 IO 线程，结果用 produceState 回到组合：卡片的第一次绘制不等网络。
 */
@Composable
internal fun ModIcon(project: ModProject, size: Dp = 40.dp) {
    val file by produceState<File?>(initialValue = null, project.iconUrl) {
        value = withContext(Dispatchers.IO) {
            if (project.iconUrl.isBlank()) null
            else runCatching { ModIcons.fetch(project.iconUrl, iconCacheDir) }.getOrNull()
        }
    }
    IconBox(file = file, title = project.title, size = size)
}

/** 本地 jar 的图标。同一个 jar 只解一次，之后都命中磁盘缓存。 */
@Composable
internal fun ModIcon(entry: ModEntry, size: Dp = 40.dp) {
    val file by produceState<File?>(initialValue = null, entry.path, entry.sizeBytes) {
        value = withContext(Dispatchers.IO) {
            runCatching { ModIcons.fromJar(File(entry.path), entry.modId, iconCacheDir) }.getOrNull()
        }
    }
    IconBox(file = file, title = entry.title, size = size)
}

@Composable
private fun IconBox(file: File?, title: String, size: Dp) {
    // 解码在 IO 线程（见 rememberIconBitmap）：先画首字母，图到了再换。
    val bitmap = rememberIconBitmap(file)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
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
