package io.github.huang404yan.platypus.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatHelpersTest {

    @Test
    fun `字节数按 KB MB GB 换档`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("2.5 GB", formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `速度每秒的写法带斜杠秒`() {
        assertEquals("1.0 MB/s", formatSpeed(1024L * 1024))
    }

    @Test
    fun `用时不到一分钟只报秒`() {
        assertEquals("0 秒", formatDuration(0))
        assertEquals("45 秒", formatDuration(45_000))
    }

    @Test
    fun `用时超过一分钟报分加秒`() {
        assertEquals("1 分 5 秒", formatDuration(65_000))
        assertEquals("3 分 0 秒", formatDuration(180_000))
    }

    @Test
    fun `速度计第一次采样不出数`() {
        val meter = SpeedMeter()
        // 第一次没有参照点，报 -1 让界面沿用旧值而不是闪一下 0
        assertEquals(-1L, meter.sample(1000))
    }

    @Test
    fun `速度计两次采样太近就沿用旧值`() {
        val meter = SpeedMeter()
        meter.sample(0)
        assertEquals(-1L, meter.sample(4096))
    }

    @Test
    fun `速度计重置后重新开始`() {
        val meter = SpeedMeter()
        meter.sample(0)
        meter.reset()
        assertEquals(-1L, meter.sample(999))
    }

    @Test
    fun `字节数不会出负数`() {
        assertTrue(formatBytes(-1).endsWith(" B"))
    }
}
