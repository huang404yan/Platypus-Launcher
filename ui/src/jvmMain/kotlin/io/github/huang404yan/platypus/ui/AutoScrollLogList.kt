package io.github.huang404yan.platypus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 日志列表的跟随判定。
 *
 * 抽成纯函数是因为 Compose 的滚动状态在单测里拿不到，而「这一帧之后还要不要跟着最新一行」
 * 本身是纯逻辑：喂进去滚动状态，出来一个 true/false，可以逐条用例钉住。
 */
internal object LogFollow {

    /**
     * 算出下一次「是否跟随」。
     *
     * @param current 当前是否在跟随
     * @param scrolling 这一帧是否正在滚动（用户拖动或程序滚动都算）
     * @param wasScrolling 上一帧是否正在滚动；用来区分「滚动刚结束」与「压根没在滚」
     * @param canScrollForward 下方还有没有内容；false = 已贴底，或内容不够一屏
     *
     * 为什么需要 wasScrolling：新日志追加会让 canScrollForward 由 false 变 true，
     * 但那不是用户在滚动。只看 canScrollForward 会把「刚来了一行」误判成「用户翻上去了」，
     * 跟随就此关掉，之后再也不滚 —— 日志区就彻底不跟了。
     */
    fun next(current: Boolean, scrolling: Boolean, wasScrolling: Boolean, canScrollForward: Boolean): Boolean =
        when {
            // 滚动中且下方还有内容：用户正在往回翻，立刻停手，不要把他拽回底部
            scrolling && canScrollForward -> false
            // 滚动中但已经贴底：保持原状，等滚动结束再定
            scrolling -> current
            // 滚动刚结束：贴底就恢复跟随，没贴底就保持不跟
            wasScrolling -> !canScrollForward
            // 没有滚动，只是新内容进来了：不动
            else -> current
        }
}

/**
 * 会自动跟到最新一行的日志列表。
 *
 * 为什么不是「新行来了就滚到底」：Minecraft 启动时一秒能刷几十行，用户往上滚一格，
 * 下一行就把他拽回底部，报错永远读不完；服务端控制台同理。
 *
 * 现在的规则是「贴底才跟」：停在底部时新内容跟着滚；一旦往上翻就停住；翻回底部自动恢复，
 * 也可以点右下角的「回到最新」跳回去 —— 用户不用自己算出该滚多远。
 */
@Composable
internal fun AutoScrollLogList(
    itemCount: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    var following by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                val next = LogFollow.next(following, scrolling, wasScrolling, canScrollForward)
                if (next != following) following = next
                wasScrolling = scrolling
            }
    }

    // 只有跟随中才滚。读取 following 作为 key：用户点「回到最新」后立刻生效一次。
    LaunchedEffect(itemCount, following) {
        if (following && itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Box(modifier) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content,
        )
        AnimatedVisibility(
            visible = !following,
            enter = fadeIn(motionTween(Motion.FAST)),
            exit = fadeOut(motionTween(Motion.FAST)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
            ) {
                TextButton(onClick = { following = true }) {
                    Text("回到最新", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
