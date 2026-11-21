package io.github.huang404yan.platypus.core.launch

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

class GameLanguageTest {

    private fun tempDir(): File = createTempDirectory("platypus-lang-test").toFile()

    @Test
    fun systemDefaultSimplifiedChinese() {
        assertEquals("zh_cn", GameLanguage.systemDefault(Locale.forLanguageTag("zh-CN")))
        assertEquals("zh_cn", GameLanguage.systemDefault(Locale.forLanguageTag("zh")))
    }

    @Test
    fun systemDefaultTraditionalChinese() {
        assertEquals("zh_tw", GameLanguage.systemDefault(Locale.forLanguageTag("zh-TW")))
        assertEquals("zh_tw", GameLanguage.systemDefault(Locale.forLanguageTag("zh-HK")))
        assertEquals("zh_tw", GameLanguage.systemDefault(Locale.forLanguageTag("zh-MO")))
    }

    @Test
    fun systemDefaultMatchesOtherLanguages() {
        assertEquals("en_us", GameLanguage.systemDefault(Locale.forLanguageTag("en-US")))
        assertEquals("ja_jp", GameLanguage.systemDefault(Locale.forLanguageTag("ja-JP")))
        assertEquals("de_de", GameLanguage.systemDefault(Locale.forLanguageTag("de-AT")))
    }

    @Test
    fun systemDefaultFallsBackWhenUnknown() {
        assertEquals(GameLanguage.FALLBACK, GameLanguage.systemDefault(Locale.forLanguageTag("th-TH")))
        assertEquals("en_us", GameLanguage.FALLBACK)
    }

    @Test
    fun resolveEmptyMeansFollowSystem() {
        assertEquals("zh_cn", GameLanguage.resolve("", Locale.forLanguageTag("zh-CN")))
        assertEquals("en_us", GameLanguage.resolve("   ", Locale.forLanguageTag("en-GB")))
    }

    @Test
    fun resolveNormalizesDashAndCase() {
        assertEquals("zh_cn", GameLanguage.resolve("zh-CN", Locale.forLanguageTag("en-US")))
        assertEquals("en_us", GameLanguage.resolve(" EN_US ", Locale.forLanguageTag("zh-CN")))
    }

    @Test
    fun labelOfReturnsIdWhenUnknown() {
        assertEquals("简体中文", GameLanguage.labelOf("zh_cn"))
        assertEquals("xx_yy", GameLanguage.labelOf("xx_yy"))
    }

    @Test
    fun writeLangCreatesFileWhenMissing() {
        val dir = File(tempDir(), "空目录")
        assertNull(GameOptions.readLang(dir))
        assertTrue(GameOptions.writeLang(dir, "zh_cn"), "会顺手建目录并新建一份只含这一行的")
        assertEquals("zh_cn", GameOptions.readLang(dir))
    }

    @Test
    fun writeLangOnlyTouchesLangLine() {
        val dir = tempDir()
        val file = File(dir, "options.txt")
        file.writeText(
            "version:3955\n" +
                "fov:0.0\n" +
                "lang:en_us\n" +
                "gamma:0.5\n",
        )
        assertTrue(GameOptions.writeLang(dir, "zh_cn"))
        assertEquals(
            listOf("version:3955", "fov:0.0", "lang:zh_cn", "gamma:0.5"),
            file.readLines(),
        )
    }

    @Test
    fun writeLangSkipsWhenUnchanged() {
        val dir = tempDir()
        val file = File(dir, "options.txt")
        file.writeText("lang:zh_cn\n")
        val before = file.lastModified()
        assertFalse(GameOptions.writeLang(dir, "zh_cn"))
        assertEquals(before, file.lastModified())
    }

    @Test
    fun writeLangAppendsWhenAbsent() {
        val dir = tempDir()
        val file = File(dir, "options.txt")
        file.writeText("version:3955\nfov:0.0\n")
        assertTrue(GameOptions.writeLang(dir, "ja_jp"))
        assertEquals(listOf("version:3955", "fov:0.0", "lang:ja_jp"), file.readLines())
    }

    @Test
    fun writeLangIgnoresBlank() {
        val dir = tempDir()
        assertFalse(GameOptions.writeLang(dir, "  "))
        assertFalse(File(dir, "options.txt").exists())
    }

    @Test
    fun replaceLangKeepsSingleLine() {
        val lines = listOf("lang:en_us", "fov:0.0", "lang:ja_jp")
        assertEquals(listOf("lang:zh_cn", "fov:0.0"), GameOptions.replaceLang(lines, "zh_cn"))
    }

    @Test
    fun ensureLangWritesSystemLanguageWhenSettingIsBlank() {
        // 「跟随系统」存的是空串。早先两端都拿 isNotBlank() 挡了一道，于是选了跟随系统
        // 反而什么都不写 —— 用户看到的就是「跟随没生效」。这条测试钉住这个行为。
        val dir = tempDir()
        val result = GameOptions.ensureLang(dir, "", Locale.forLanguageTag("zh-CN"))
        assertTrue(result is GameOptions.LangWrite.Written, "空串也要真的写一次")
        assertEquals("zh_cn", result.lang)
        assertEquals("zh_cn", GameOptions.readLang(dir))
    }

    @Test
    fun ensureLangReportsAlreadyWhenValueMatches() {
        val dir = tempDir()
        File(dir, "options.txt").writeText("lang:ja_jp\n")
        val result = GameOptions.ensureLang(dir, "ja_jp")
        assertTrue(result is GameOptions.LangWrite.Already, "值本来就对时不该被当成写失败")
        assertEquals("ja_jp", result.lang)
    }

    @Test
    fun ensureLangReportsWrittenWhenValueChanges() {
        val dir = tempDir()
        File(dir, "options.txt").writeText("lang:en_us\n")
        val result = GameOptions.ensureLang(dir, "zh-CN")
        assertTrue(result is GameOptions.LangWrite.Written)
        assertEquals("zh_cn", result.lang, "手写成 zh-CN 也要归一成游戏认的写法")
        assertEquals("zh_cn", GameOptions.readLang(dir))
    }

    @Test
    fun ensureLangReportsFailedWhenTargetIsAFile() {
        // 把 gameDir 指到一个普通文件上：建目录必然失败，返回值必须是 Failed 而不是 Already。
        val notADir = File(tempDir(), "not-a-dir")
        notADir.writeText("x")
        val result = GameOptions.ensureLang(notADir, "zh_cn")
        assertTrue(result is GameOptions.LangWrite.Failed)
        assertEquals("zh_cn", result.lang)
    }

    @Test
    fun langKeyIgnoresCase() {
        assertEquals("zh_cn", GameOptions.langIn(listOf("Lang : zh_cn")))
        assertNull(GameOptions.langIn(listOf("language:zh_cn")))
    }
}