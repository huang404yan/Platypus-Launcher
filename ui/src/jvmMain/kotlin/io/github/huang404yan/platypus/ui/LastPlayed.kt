package io.github.huang404yan.platypus.ui

import java.time.ZoneId

/**
 * 「上次玩」那一行怎么写 —— 规则在 core，见 [io.github.huang404yan.platypus.core.instance.lastPlayedText]。
 *
 * 这里只留一层转调，是为了让 ui 里的调用点继续写 `lastPlayedText(...)`：
 * 安卓那边也在用同一份规则，规则只该有一处。
 */
internal fun lastPlayedText(
    millis: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = io.github.huang404yan.platypus.core.instance.lastPlayedText(millis, now, zone)
