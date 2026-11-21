package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 装完一个东西之后的那句话，浮在整棵界面之上。
 *
 * 与桌面 ui/Notices.kt 是同一套形态，理由也一样：安装任务活在页面之外
 * （[ModInstallRunner] 这类进程级对象），而用户下了整合包之后多半会切去看别的，
 * 回来时那页的 message 还在，但当时那一眼没人看得到。
 *
 * @param goto 装完之后去哪一页；空串表示点了就是关掉。写法是「Tab 名」或
 *   「Tab 名|二级项 id」（例如 `DOWNLOAD|mods`）：光给 Tab 名，点进去会落在那一栏的
 *   第一个子页上，而用户刚装的是模组，把他丢回「原版与加载器」等于又走一遍找路。
 */
internal data class LauncherNotice(
    val id: Long,
    val title: String,
    val body: String,
    val goto: String = "",
)

internal object Notices {

    private val _items = MutableStateFlow<List<LauncherNotice>>(emptyList())
    val items: StateFlow<List<LauncherNotice>> = _items.asStateFlow()

    private var seq = 0L

    /** [goto] 形如 `DOWNLOAD|mods`，含义见 [LauncherNotice]。 */
    fun push(title: String, body: String, goto: String = ""): LauncherNotice {
        val notice = LauncherNotice(id = ++seq, title = title, body = body, goto = goto)
        _items.update { it + notice }
        return notice
    }

    fun dismiss(id: Long) {
        _items.update { list -> list.filterNot { it.id == id } }
    }

    fun clear() {
        _items.update { emptyList() }
    }
}

/** 一条浮卡显示多久。手机上比桌面短一点：屏幕小，挡住的正好是内容。 */
private const val HOLD_MILLIS = 5000L

/** 同时最多显示几条。再多就盖住整屏了。 */
private const val MAX_VISIBLE = 2

@Composable
internal fun NoticeStack(notices: List<LauncherNotice>, onOpen: (LauncherNotice) -> Unit) {
    val shown = notices.takeLast(MAX_VISIBLE)
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        shown.forEach { notice ->
            NoticeCard(notice, onOpen)
        }
    }
}

@Composable
private fun NoticeCard(notice: LauncherNotice, onOpen: (LauncherNotice) -> Unit) {
    // 每条自己计时：后进来的那条不该把前一条的钟清零。
    LaunchedEffect(notice.id) {
        delay(HOLD_MILLIS)
        Notices.dismiss(notice.id)
    }
    Surface(
        modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        onClick = { onOpen(notice) },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                notice.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (notice.body.isNotBlank()) {
                Text(
                    notice.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
