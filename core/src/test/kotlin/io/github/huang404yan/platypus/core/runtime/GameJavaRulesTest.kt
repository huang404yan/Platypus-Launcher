package io.github.huang404yan.platypus.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 游戏版本 → 所需 Java 主版本的边界测试。
 *
 * 规则来自 Mojang 官方版本要求：1.16.5 及更早要 Java 8；1.17.x 要 16；
 * 1.18 – 1.20.4 要 17；1.20.5 起（含 1.21+）要 21。
 */
class GameJavaRulesTest {

    @Test
    fun `1_16_5 及更早要 Java 8`() {
        assertEquals(8, GameJavaRules.requiredMajor("1.16.5"))
        assertEquals(8, GameJavaRules.requiredMajor("1.16"))
        assertEquals(8, GameJavaRules.requiredMajor("1.15.2"))
        assertEquals(8, GameJavaRules.requiredMajor("1.12.2"))
        assertEquals(8, GameJavaRules.requiredMajor("1.8.9"))
        assertEquals(8, GameJavaRules.requiredMajor("1.7.10"))
        assertEquals(8, GameJavaRules.requiredMajor("1.6.4"))
    }

    @Test
    fun `1_17 系列要 Java 16`() {
        assertEquals(16, GameJavaRules.requiredMajor("1.17"))
        assertEquals(16, GameJavaRules.requiredMajor("1.17.1"))
        assertEquals(16, GameJavaRules.requiredMajor("1.17.1-pre1"))
        // 1.18 抬到 17，17 与 18 之间只差一个小版本，这条边界最容易写错
        assertEquals(17, GameJavaRules.requiredMajor("1.18"))
        assertEquals(17, GameJavaRules.requiredMajor("1.18.2"))
    }

    @Test
    fun `1_18 到 1_20_4 要 Java 17`() {
        assertEquals(17, GameJavaRules.requiredMajor("1.19"))
        assertEquals(17, GameJavaRules.requiredMajor("1.19.4"))
        assertEquals(17, GameJavaRules.requiredMajor("1.20"))
        assertEquals(17, GameJavaRules.requiredMajor("1.20.1"))
        assertEquals(17, GameJavaRules.requiredMajor("1.20.4"))
    }

    @Test
    fun `1_20_5 是 21 的分水岭`() {
        assertEquals(21, GameJavaRules.requiredMajor("1.20.5"))
        assertEquals(21, GameJavaRules.requiredMajor("1.20.6"))
        assertEquals(21, GameJavaRules.requiredMajor("1.20.5-pre1"))
        assertEquals(21, GameJavaRules.requiredMajor("1.20.5-rc1"))
    }

    @Test
    fun `1_21 及以后要 Java 21`() {
        assertEquals(21, GameJavaRules.requiredMajor("1.21"))
        assertEquals(21, GameJavaRules.requiredMajor("1.21.1"))
        assertEquals(21, GameJavaRules.requiredMajor("1.21.4"))
        assertEquals(21, GameJavaRules.requiredMajor("1.24"))
    }

    @Test
    fun `快照按最新要求 21`() {
        assertEquals(21, GameJavaRules.requiredMajor("26w01a"))
        assertEquals(21, GameJavaRules.requiredMajor("24w14a"))
        assertEquals(21, GameJavaRules.requiredMajor("23w31a"))
        assertEquals(21, GameJavaRules.LATEST_REQUIRED_MAJOR)
    }

    @Test
    fun `远古版本与非版本号一律按 8 处理`() {
        assertEquals(8, GameJavaRules.requiredMajor("a1.2.6"))
        assertEquals(8, GameJavaRules.requiredMajor("b1.7.3"))
        assertEquals(8, GameJavaRules.requiredMajor("c0.30_01c"))
        assertEquals(8, GameJavaRules.requiredMajor(""))
        assertEquals(8, GameJavaRules.requiredMajor("whatever"))
    }

    @Test
    fun `首尾空格不影响判断`() {
        assertEquals(17, GameJavaRules.requiredMajor("  1.20.1  "))
        assertEquals(21, GameJavaRules.requiredMajor("\t1.21\n"))
        assertEquals(21, GameJavaRules.requiredMajor(" 26w01a "))
    }

    @Test
    fun `结果只会是 8 16 17 21 这四个值`() {
        val allowed = setOf(8, 16, 17, 21)
        for (version in listOf("1.7.10", "1.16.5", "1.17", "1.18.2", "1.20.4", "1.20.5", "1.21", "26w01a")) {
            val major = GameJavaRules.requiredMajor(version)
            assertTrue(major in allowed, "版本 " + version + " 推出了意外的 Java 主版本 " + major)
        }
    }
}
