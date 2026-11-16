package io.github.huang404yan.platypus.launcher.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 账号名单的版本号 —— 跨页面通知「名单变了」。
 *
 * 问题出在手机上的页面切分：账号页负责「添一个身份」（登录、外置服、移除），
 * 启动页负责「这一局用谁」。两个页面各自 remember 自己的状态，中间没有任何通道，
 * 于是在账号页登录成功、切回启动页时，启动页手里还是进来时那份账号名单 ——
 * 用户看到的就是「明明授权成功了，还说没有账号」。
 *
 * 桌面端不存在这个问题：那边是一个 App 持有全局状态，账号页只负责弹层。
 * 手机上最轻量的等价物就是这个计数器：谁改了名单谁 [bump]，正在看的页面
 * 把 [value] 当 LaunchedEffect 的 key，值一变就重新读一遍盘。
 *
 * 之所以不做成「共享一份账号列表」：名单的真相在 accounts.json 里，
 * 共享一份内存副本反而要多管一套失效逻辑；重读一次只要几毫秒。
 */
internal object AccountRevision {

    /** 当前版本号。读它 = 订阅；值变了会触发读取方的 LaunchedEffect。 */
    var value by mutableIntStateOf(0)
        private set

    /** 名单被改过（登录成功、切换当前账号、移除一条、外置登录）。 */
    fun bump() {
        value += 1
    }
}
