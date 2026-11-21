package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * 一条「事情办完了」的提示。
 *
 * [id] 只用来区分是不是同一条：界面靠它记「哪条已经淡出过」，不靠文案 —— 两次装同一个
 * 整合包，文案一模一样，没有 id 就分不出是新的一条还是旧的那条又亮了一下。
 */
internal data class LauncherNotice(
    val id: Long,
    val title: String,
    val body: String,
    /**
     * 点这条提示该去哪儿，null 表示只能点掉。
     *
     * 为什么记页面而不是记「打开哪个版本」：装完之后用户要做的第一件事是「去启动它」，
     * 而启动页本来就按 lastInstanceKey 自动选好了刚装的那一个。记一个 page 就够，
     * 不用把 Instance 对象穿过通知中心传一遍。
     */
    val goto: Page? = null,
)

/**
 * 进程级的完成通知：装上 / 导入 / 下载结束后往这儿放一条。
 *
 * 为什么不在各页面里各弹各的：一键装与整合包导入都在 [OneClickRunner] /
 * [ModpackRunner] 里跑，任务活过页面（切页不打断），完成的那一刻用户很可能正在别的页面。
 * 提示必须挂在 App 那一层才看得见，页面自己弹的提示会随着页面销毁一起消失 ——
 * 那就等于没告诉用户。
 *
 * 为什么用 StateFlow 而不是 Compose 的 mutableStateListOf：[OneClickRunner] 就是这么写的，
 * 测试与无界面环境也能直接读。
 */
internal object Notices {

    private val _items = MutableStateFlow<List<LauncherNotice>>(emptyList())

    /** 当前还没被关掉的那些，先来的在前。 */
    val items: StateFlow<List<LauncherNotice>> = _items.asStateFlow()

    private var seq = 0L

    /** 放一条提示进去，返回它。调用方一般不需要这个返回值。 */
    fun push(title: String, body: String, goto: Page? = null): LauncherNotice {
        val notice = LauncherNotice(id = ++seq, title = title, body = body, goto = goto)
        _items.update { it + notice }
        return notice
    }

    /** 用户点掉、或者超时自动消失。传不存在的 id 什么都不做。 */
    fun dismiss(id: Long) {
        _items.update { list -> list.filterNot { it.id == id } }
    }

    /** 一次性清空，退出前用。 */
    fun clear() {
        _items.value = emptyList()
    }
}

/** 一条提示在屏幕上待多久。够读完两行字，又不会一直挡着右下角。 */
private const val HOLD_MILLIS = 6000L

/** 同时最多挂几条。再多会把内容区盖掉，更早的直接让位。 */
private const val MAX_VISIBLE = 3

/**
 * 右下角的完成提示。
 *
 * 为什么不用 Snackbar：Material 的 Snackbar 得套在 Scaffold 里，而且会挤走底部的按钮；
 * 这套界面没有 Scaffold，底部常有「启动游戏」这类主按钮。这里自己画一张浮在角上的小卡，
 * 不占布局位置，也不抢焦点。
 */
@Composable
internal fun NoticeStack(notices: List<LauncherNotice>, onOpen: (LauncherNotice) -> Unit) {
    val shown = notices.takeLast(MAX_VISIBLE)
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Bottom,
    ) {
        shown.forEach { notice -> NoticeCard(notice, onOpen) }
    }
}

@Composable
private fun NoticeCard(notice: LauncherNotice, onOpen: (LauncherNotice) -> Unit) {
    // 每条自己计时：后进来的不会把先进来那几条的钟清零。
    LaunchedEffect(notice.id) {
        delay(HOLD_MILLIS)
        onOpen(notice)
    }
    Surface(
        modifier = Modifier.padding(top = 8.dp).widthIn(max = 360.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        // 整张卡都能点：有去处就直接跳过去（顺手把自己收掉），没去处就只是收掉。
        // 不做一个单独的「查看」按钮 —— 提示本来就只活六秒，多一个可点区域只会分散注意力。
        onClick = { onOpen(notice) },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
