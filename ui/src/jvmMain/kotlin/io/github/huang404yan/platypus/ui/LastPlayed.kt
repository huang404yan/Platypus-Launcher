package io.github.huang404yan.platypus.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「上次玩」那一行怎么写。
 *
 * 分档而不是一律写完整时间：卡片上这行字占的宽度有限，而用户真正想知道的是
 * 「是不是最近玩的那个」—— 「3 小时前」比「2026-11-21 09:12」回答得更快。
 * 超过一周才退回具体日期，那时候「几天前」已经开始含糊了。
 *
 * 取整一律向下：说「1 小时前」时实际可能是 1 小时 59 分，反过来会让人以为
 * 时间走快了。
 *
 * @param millis 上次启动的时间戳，0 表示从没启动过。
 * @param now 拿来做对比的「现在」，单测注入用。
 * @param zone 时区，单测注入用（跨时区跑 CI 时不该改变结果）。
 */
internal fun lastPlayedText(
    millis: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (millis <= 0L) return "还没启动过"
    // 时钟回拨、或者实例是从别的机器拷过来的（那边时间比本机晚），差值会是负的。
    // 这种时候说「刚刚」比说「-2 天前」安全：文件是真的存在，只是时间对不上。
    if (millis > now) return "刚刚玩过"
    val minutes = (now - millis) / 60_000L
    if (minutes < 1L) return "刚刚玩过"
    if (minutes < 60L) return minutes.toString() + " 分钟前"
    val hours = minutes / 60L
    // 24 小时以内一律按「多少小时前」说，哪怕跨过午夜：23:59 玩的、00:10 打开，
    // 说「昨天 23:59」反而不如「11 分钟前」直白。等到超过一天才改用日历口径。
    if (hours < 24L) return hours.toString() + " 小时前"
    val played = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    return when {
        played == today -> "今天 " + at.format(clock)
        played == today.minusDays(1) -> "昨天 " + at.format(clock)
        played.year == today.year -> played.format(DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT))
        else -> LocalDate.from(played).format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT))
    }
}
