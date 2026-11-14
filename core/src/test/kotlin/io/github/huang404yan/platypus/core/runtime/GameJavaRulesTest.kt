package io.github.huang404yan.platypus.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 游戏版本 → 所需 Java 主版本的边界测试。
 *
 * 规则来自 Mojang 官方版本要求：1.16.5 及更早要 Java 8；1.17.x 要 16；
 * 1.18 – 1.20.4 要 17；1.20.5 起（含 1.21+）要 21；26.1 起的年份式 id 要 25；
 * 周号快照按周号分段（13w16a–21w18a → 8、21w19a–22w02a → 16、22w03a–24w13a → 17、
 * 24w14a–25w46a → 21、26w14a 起 → 25）。
 *
 * 年份式那几条断言对应的真实数据（2026-11-14 拉的 version_manifest_v2）：
 * 26.1 / 26.3 / 26.4-snapshot-8 的 json 里 javaVersion 都是
 * {component: java-runtime-epsilon, majorVersion: 25}；1.21.11 与 24w14a 仍是 delta / 21。
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
    fun `周号快照按年份分段`() {
        // 边界来自官方 744 个快照的 javaVersion.majorVersion 实测跃迁点
        // （2026-11-14 拉 version_manifest_v2 统计，见 docs/05 的 GameJavaRules 条目）
        assertEquals(8, GameJavaRules.requiredMajor("13w16a"))
        assertEquals(8, GameJavaRules.requiredMajor("20w51a"))
        assertEquals(8, GameJavaRules.requiredMajor("21w18a"))
        assertEquals(16, GameJavaRules.requiredMajor("21w19a"))
        assertEquals(16, GameJavaRules.requiredMajor("21w37a"))
        assertEquals(17, GameJavaRules.requiredMajor("22w03a"))
        assertEquals(17, GameJavaRules.requiredMajor("24w13a"))
        assertEquals(21, GameJavaRules.requiredMajor("24w14a"))
        assertEquals(21, GameJavaRules.requiredMajor("25w45a"))
        assertEquals(25, GameJavaRules.requiredMajor("26w14a"))
        // 26w01a 不是 26.1：没有点，走周号分支，还是 21
        assertEquals(21, GameJavaRules.requiredMajor("26w01a"))
    }

    @Test
    fun `带后缀的特殊快照 id 也能分段`() {
        // 后缀不是单字母的两种：23w13a_or_b 按周号 2313 → 17；
        // 24w14potato 是同周愚人节快照，官方 json 写 17（24w14a 是 21），单独兜住
        assertEquals(17, GameJavaRules.requiredMajor("23w13a_or_b"))
        assertEquals(17, GameJavaRules.requiredMajor("24w14potato"))
        assertEquals(21, GameJavaRules.requiredMajor("24w14a"))
        assertEquals(21, GameJavaRules.requiredMajor("25w14craftmine"))
    }

    @Test
    fun `26_1 起的年份式版本号要 Java 25`() {
        // 实测这三个版本的 json 都是 java-runtime-epsilon / majorVersion 25
        assertEquals(25, GameJavaRules.requiredMajor("26.1"))
        assertEquals(25, GameJavaRules.requiredMajor("26.3"))
        assertEquals(25, GameJavaRules.requiredMajor("26.1.2"))
        assertEquals(25, GameJavaRules.requiredMajor("26.4-snapshot-8"))
        assertEquals(25, GameJavaRules.requiredMajor(" 26.1 "))
        assertEquals(25, GameJavaRules.LATEST_REQUIRED_MAJOR)
    }

    @Test
    fun `年份式判断不会把 1_x 误认成年份`() {
        // YearVersionRegex 要求点前面正好两位数字，1.20.1 只有一位
        assertEquals(17, GameJavaRules.requiredMajor("1.20.1"))
        assertEquals(8, GameJavaRules.requiredMajor("1.7.10"))
        assertEquals(8, GameJavaRules.requiredMajor("b1.7.3"))
        // 25.x 还没出现，25 以下仍按老的 1.x 规则走
        assertEquals(8, GameJavaRules.requiredMajor("25.0"))
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
        assertEquals(25, GameJavaRules.requiredMajor("  26.3  "))
    }

    @Test
    fun `结果只会是 8 16 17 21 25 这五个值`() {
        val allowed = setOf(8, 16, 17, 21, 25)
        for (version in listOf("1.7.10", "1.16.5", "1.17", "1.18.2", "1.20.4", "1.20.5", "1.21", "26w01a", "26.1", "26.4-snapshot-8")) {
            val major = GameJavaRules.requiredMajor(version)
            assertTrue(major in allowed, "版本 " + version + " 推出了意外的 Java 主版本 " + major)
        }
    }
}
