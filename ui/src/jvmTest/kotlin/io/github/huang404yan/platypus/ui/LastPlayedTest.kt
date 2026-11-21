package io.github.huang404yan.platypus.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「上次玩」那行字的分档。
 *
 * 时间全部由参数注入，不读系统时钟：否则跨时区、跨午夜跑 CI 时结果会变。
 */
class LastPlayedTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 造一个「2026-11-21 12:00 +08:00」的毫秒时间戳。 */
    private fun noon(): Long =
        ZonedDateTime.of(2026, 11, 21, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun zeroMeansNeverLaunched() {
        assertEquals("还没启动过", lastPlayedText(0L, noon(), zone))
        // 负数不可能是正常写入的结果，但老数据被手改过也可能出现：当成没记录，不要显示成未来
        assertEquals("还没启动过", lastPlayedText(-1L, noon(), zone))
    }

    @Test
    fun withinOneMinuteReadsAsJustNow() {
        assertTrue(lastPlayedText(noon() - 30_000L, noon(), zone) == "刚刚玩过")
        // 正好 1 分钟按「1 分钟前」，别把 60000ms 也算成刚刚 —— 边界往下取整
        assertEquals("1 分钟前", lastPlayedText(noon() - 60_000L, noon(), zone))
    }

    @Test
    fun minutesAreFloored() {
        // 59 分 59 秒说「59 分钟前」，不四舍五入成「1 小时前」
        assertEquals("59 分钟前", lastPlayedText(noon() - (59L * 60_000L + 59_000L), noon(), zone))
        assertEquals("1 小时前", lastPlayedText(noon() - 60L * 60_000L, noon(), zone))
        assertEquals("23 小时前", lastPlayedText(noon() - 23L * 3_600_000L - 59L * 60_000L, noon(), zone))
    }

    @Test
    fun yesterdayIsCalendarDay() {
        // 26 小时前 = 昨天 10:00：超过 24 小时后改用日历口径，这一天就是「昨天」
        val text = lastPlayedText(noon() - 26L * 3_600_000L, noon(), zone)
        assertEquals("昨天 10:00", text)
    }

    @Test
    fun sameDayBeyondOneDayReadsAsToday() {
        // 18 小时前还在 24 小时内 → 走小时档；下一条是刚好跨过 24 小时又落在今天的情况：
        // 取「昨天 12:00」（24 小时前）验证边界落在日历那一天上。
        assertEquals("昨天 12:00", lastPlayedText(noon() - 24L * 3_600_000L, noon(), zone))
    }

    @Test
    fun olderThanYesterdayUsesDate() {
        // 3 天前：同年只写月日
        assertEquals("11-18", lastPlayedText(noon() - 3L * 86_400_000L, noon(), zone))
        // 跨年写完整日期
        val lastYear = ZonedDateTime.of(2025, 12, 31, 20, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("2025-12-31", lastPlayedText(lastYear, noon(), zone))
    }

    @Test
    fun futureTimestampDoesNotRenderNegative() {
        // 实例从别的机器拷过来、那边时钟比本机晚，会出现「未来」的时间戳。
        // 显示成「-2 天前」比说「刚刚玩过」糟得多。
        assertEquals("刚刚玩过", lastPlayedText(noon() + 86_400_000L, noon(), zone))
    }

    @Test
    fun followsGivenZoneNotSystemDefault() {
        // 同一个时间戳在东八区是 12:00、在 UTC 是 04:00：说明时区参数真的被用上了，
        // 而不是悄悄读了系统默认值。
        val stamp = noon() - 24L * 3_600_000L
        assertEquals("昨天 12:00", lastPlayedText(stamp, noon(), zone))
        assertEquals("昨天 04:00", lastPlayedText(stamp, noon(), ZoneId.of("UTC")))
    }
}
