package io.github.huang404yan.platypus.launcher.preview

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 「所有文件访问权限」开关的全局刷新信号。
 *
 * ## 为什么需要一个进程级计数器
 *
 * 权限是跳到系统设置页去开的，开完按返回键回来，Activity 只会走 onResume ——
 * Compose 这一层没有任何东西知道「刚刚去开了权限」。于是每个页面 remember 的那个
 * granted 还停在 false：按钮仍写着「去授权」，页面底下仍挂着红卡，用户以为没生效，
 * 又点一次「去授权」，跳到那个已经打开着的开关页，再回来 —— 这就是「授权成功还是要
 * 二次点击」的由来（2026-11-16 用户报的；桌面端不存在这个问题，那边没有系统设置页可跳）。
 *
 * 修法是把 onResume 变成一个可观察的信号：Activity 每次回到前台就把计数加一，
 * 页面用 [rememberFileAccessState] 重新查一次。查的是运行时真值
 * （Environment.isExternalStorageManager），不是自己记的标志位 —— 用户在设置页里把开关
 * 关掉再回来，同样要立刻显示成未授权。
 */
internal object FileAccessGate {
    /** 每次回到前台加一；读它的 Composable 会重新查一次权限。 */
    var tick by mutableIntStateOf(0)
        private set

    /** Activity 的 onResume 调这个。重复调用无副作用，只是让页面重查一次。 */
    fun notifyResumed() {
        tick++
    }
}

/**
 * 可读可写的文件访问权限状态，每次 Activity 回到前台自动重查一次。
 *
 * 返回的是 MutableState，所以页面原来的 `var granted by remember { mutableStateOf(...) }`
 * 换成 `var granted by rememberFileAccessState(context)` 之后，函数体里那些
 * `granted = hasFileAccess(context)` 的重新赋值一行都不用改。
 *
 * 注意它**不能替代**点击时的即时查询：用户在设置页里改了开关但还没返回过这一页时，
 * 这里读到的仍是上一次的值，所以按钮的 onClick 里照样要先查一次再决定要不要跳走。
 */
@Composable
internal fun rememberFileAccessState(context: Context): MutableState<Boolean> {
    val state = remember { mutableStateOf(hasFileAccess(context)) }
    // tick 只当 key 用：值本身没意义，变一次就重查一次。
    LaunchedEffect(FileAccessGate.tick) {
        state.value = hasFileAccess(context)
    }
    return state
}
