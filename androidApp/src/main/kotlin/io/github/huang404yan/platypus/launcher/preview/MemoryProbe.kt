package io.github.huang404yan.platypus.launcher.preview

import android.app.ActivityManager
import android.content.Context
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.MemoryDefaults

/**
 * 这台设备的物理内存（MB）。
 *
 * 设置页与实例详情页都要按它算「自动」内存，所以放在这里而不是各写一份 ——
 * 两处算出来的数不一致时，用户会看到「设置里写 6 GB，实例里说跟随 4 GB」。
 *
 * 拿不到（旧机型不给 `totalMem`、或 ActivityManager 抛异常）就返回 0；
 * [MemoryDefaults.autoMb] 见到 0 会退回 [Instance.DEFAULT_MEMORY_MB]，
 * 所以这里不需要再兜一次默认值，调用方也不用判。
 */
internal fun deviceTotalMemoryMb(context: Context): Int = runCatching {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    (info.totalMem / (1024L * 1024L)).toInt()
}.getOrDefault(0)
