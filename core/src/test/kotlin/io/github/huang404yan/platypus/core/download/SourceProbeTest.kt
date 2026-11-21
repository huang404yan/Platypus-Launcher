package io.github.huang404yan.platypus.core.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceProbeTest {

    private val official = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private val mirror = "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"

    @Test
    fun speedIsBytesPerSecond() {
        val result = ProbeResult(official, millis = 500L, bytes = 1024L * 1024L)
        assertEquals(2L * 1024L * 1024L, result.bytesPerSecond)
    }

    @Test
    fun failedProbeHasNoSpeed() {
        val result = ProbeResult(official, millis = 500L, bytes = 1024L, error = "HTTP 503")
        assertEquals(0L, result.bytesPerSecond)
        assertTrue(!result.ok)
    }

    @Test
    fun zeroBytesHasNoSpeed() {
        // 连上了但一个字节都没读到：算不出速度，不能让 0/0 变成无穷大把界面撑爆
        assertEquals(0L, ProbeResult(official, millis = 0L, bytes = 0L).bytesPerSecond)
    }

    @Test
    fun fastestIgnoresFailuresAndEmptyReads() {
        val results = listOf(
            ProbeResult(official, 100L, 0L, error = "连接超时"),
            ProbeResult(official, 800L, 200_000L),
            ProbeResult(mirror, 100L, 0L),
            ProbeResult(mirror, 400L, 400_000L),
        )
        assertEquals(mirror, SourceProbe.fastest(results)?.url)
    }

    @Test
    fun fastestIsNullWhenNothingWorked() {
        val results = listOf(
            ProbeResult(official, 100L, 0L, error = "连接超时"),
            ProbeResult(mirror, 900L, 0L),
        )
        assertNull(SourceProbe.fastest(results))
    }

    @Test
    fun suggestedSourceFollowsTheWinner() {
        val mirrorWins = listOf(
            ProbeResult(official, 900L, 100_000L),
            ProbeResult(mirror, 200L, 100_000L),
        )
        assertEquals(DownloadSource.MIRROR, SourceProbe.suggestedSource(mirrorWins))

        val officialWins = listOf(
            ProbeResult(official, 100L, 100_000L),
            ProbeResult(mirror, 900L, 100_000L),
        )
        assertEquals(DownloadSource.AUTO, SourceProbe.suggestedSource(officialWins))
    }

    @Test
    fun noSuggestionWhenNothingWorked() {
        assertNull(SourceProbe.suggestedSource(listOf(ProbeResult(official, 100L, 0L, error = "HTTP 404"))))
        assertNull(SourceProbe.suggestedSource(emptyList()))
    }

    @Test
    fun describeReportsFailureVerbatim() {
        assertEquals("没测成：HTTP 503", SourceProbe.describe(ProbeResult(official, 10L, 0L, error = "HTTP 503")))
    }

    @Test
    fun describeSaysWhenNothingCameBack() {
        assertEquals("连上了但没读到数据", SourceProbe.describe(ProbeResult(official, 10L, 0L)))
    }

    @Test
    fun describeCarriesBothSizeAndSpeed() {
        val text = SourceProbe.describe(ProbeResult(official, millis = 500L, bytes = 256L * 1024L))
        assertTrue(text.contains("256.0 KB"), text)
        assertTrue(text.contains("500 毫秒"), text)
        assertTrue(text.contains("/秒"), text)
    }

    @Test
    fun longProbesAreReadInSeconds() {
        val text = SourceProbe.describe(ProbeResult(official, millis = 2_400L, bytes = 1024L))
        assertTrue(text.contains("2.4 秒"), text)
    }
}