package io.github.huang404yan.platypus.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「下载 Java」那张表怎么从 Mojang 的运行时索引里挑出来。
 *
 * 数据用的是 2026-11-14 实测的 windows-x64 清单形状：八个组件，
 * 其中 gamma-snapshot 与 minecraft-java-exe 是要排除的，beta 与 gamma 都是 17 只要留一个。
 */
class JavaRuntimeOffersTest {

    private val indexJson = """
        {
          "windows-x64": {
            "java-runtime-alpha": [{"manifest": {"sha1": "a", "size": 147226, "url": "https://example.invalid/a.json"}, "version": {"name": "16.0.1.9.1"}}],
            "java-runtime-beta": [{"manifest": {"sha1": "b", "size": 148571, "url": "https://example.invalid/b.json"}, "version": {"name": "17.0.15"}}],
            "java-runtime-delta": [{"manifest": {"sha1": "d", "size": 148529, "url": "https://example.invalid/d.json"}, "version": {"name": "21.0.7"}}],
            "java-runtime-epsilon": [{"manifest": {"sha1": "e", "size": 152664, "url": "https://example.invalid/e.json"}, "version": {"name": "25.0.1"}}],
            "java-runtime-gamma": [{"manifest": {"sha1": "g", "size": 148571, "url": "https://example.invalid/g.json"}, "version": {"name": "17.0.15"}}],
            "java-runtime-gamma-snapshot": [{"manifest": {"sha1": "gs", "size": 148571, "url": "https://example.invalid/gs.json"}, "version": {"name": "17.0.15"}}],
            "jre-legacy": [{"manifest": {"sha1": "l", "size": 80031, "url": "https://example.invalid/l.json"}, "version": {"name": "8u51-cacert462b08"}}],
            "minecraft-java-exe": [{"manifest": {"sha1": "m", "size": 461, "url": "https://example.invalid/m.json"}, "version": {"name": "14"}}]
          },
          "windows-arm64": {
            "java-runtime-delta": [{"manifest": {"sha1": "d", "size": 125687, "url": "https://example.invalid/d.json"}, "version": {"name": "21.0.7"}}],
            "java-runtime-gamma": [{"manifest": {"sha1": "g", "size": 126088, "url": "https://example.invalid/g.json"}, "version": {"name": "17.0.15"}}]
          }
        }
    """.trimIndent()

    @Test
    fun offersCoverTheFiveMajorsSortedAscending() {
        val offers = buildRuntimeOffers(parseRuntimeIndex(indexJson), "windows-x64")
        assertEquals(listOf(8, 16, 17, 21, 25), offers.map { it.majorVersion })
        assertEquals(
            listOf("jre-legacy", "java-runtime-alpha", "java-runtime-gamma", "java-runtime-delta", "java-runtime-epsilon"),
            offers.map { it.component },
        )
    }

    @Test
    fun dropsMinecraftJavaExe() {
        val offers = buildRuntimeOffers(parseRuntimeIndex(indexJson), "windows-x64")
        assertNull(offers.firstOrNull { it.component == "minecraft-java-exe" }, "minecraft-java-exe 不是 Java，不该出现在表里")
        assertNull(offers.firstOrNull { it.majorVersion == 14 })
    }

    @Test
    fun dropsGammaSnapshotInFavourOfGamma() {
        val offers = buildRuntimeOffers(parseRuntimeIndex(indexJson), "windows-x64")
        assertNull(offers.firstOrNull { it.component == "java-runtime-gamma-snapshot" })
        // 17 那一行留的是 gamma，不是 beta、不是 snapshot
        assertEquals("java-runtime-gamma", offers.first { it.majorVersion == 17 }.component)
    }

    @Test
    fun arm64OnlyListsWhatMojangActuallyHas() {
        val offers = buildRuntimeOffers(parseRuntimeIndex(indexJson), "windows-arm64")
        assertEquals(listOf(17, 21), offers.map { it.majorVersion })
    }

    @Test
    fun unknownPlatformYieldsEmptyListRatherThanCrash() {
        assertTrue(buildRuntimeOffers(parseRuntimeIndex(indexJson), "linux-riscv").isEmpty())
    }

    @Test
    fun installedOfMarksTheRowAndKeepsThePath() {
        val offers = buildRuntimeOffers(parseRuntimeIndex(indexJson), "windows-x64") { component ->
            if (component == "java-runtime-delta") "C:/mc/runtime/java-runtime-delta/bin/java.exe" else null
        }
        val delta = offers.first { it.component == "java-runtime-delta" }
        assertTrue(delta.installed)
        assertEquals("C:/mc/runtime/java-runtime-delta/bin/java.exe", delta.installedPath)
        val gamma = offers.first { it.component == "java-runtime-gamma" }
        assertFalse(gamma.installed)
        assertNull(gamma.installedPath)
    }

    @Test
    fun versionNameIsCarriedThroughForTheCard() {
        val offers = buildRuntimeOffers(parseRuntimeIndex(indexJson), "windows-x64")
        assertEquals("8u51-cacert462b08", offers.first { it.majorVersion == 8 }.versionName)
        assertEquals("21.0.7", offers.first { it.majorVersion == 21 }.versionName)
    }

    @Test
    fun componentToMajorCoversEveryComponentWeShip() {
        assertEquals(8, JavaRuntimeComponents.majorOfComponent(JavaRuntimeComponents.JRE_LEGACY))
        assertEquals(16, JavaRuntimeComponents.majorOfComponent(JavaRuntimeComponents.ALPHA))
        assertEquals(17, JavaRuntimeComponents.majorOfComponent(JavaRuntimeComponents.BETA))
        assertEquals(17, JavaRuntimeComponents.majorOfComponent(JavaRuntimeComponents.GAMMA))
        assertEquals(17, JavaRuntimeComponents.majorOfComponent(JavaRuntimeComponents.GAMMA_SNAPSHOT))
        assertEquals(21, JavaRuntimeComponents.majorOfComponent(JavaRuntimeComponents.DELTA))
        assertEquals(25, JavaRuntimeComponents.majorOfComponent(JavaRuntimeComponents.EPSILON))
        assertNull(JavaRuntimeComponents.majorOfComponent("minecraft-java-exe"))
    }

    @Test
    fun versionNameParsingHandlesTheThreeShapesWeSaw() {
        assertEquals(21, JavaRuntimeComponents.majorOfVersionName("21.0.7"))
        assertEquals(25, JavaRuntimeComponents.majorOfVersionName("25.0.1"))
        assertEquals(16, JavaRuntimeComponents.majorOfVersionName("16.0.1.9.1"))
        assertEquals(8, JavaRuntimeComponents.majorOfVersionName("8u51-cacert462b08"))
        assertEquals(17, JavaRuntimeComponents.majorOfVersionName(" 17.0.15 "))
        assertNull(JavaRuntimeComponents.majorOfVersionName(""))
        assertNull(JavaRuntimeComponents.majorOfVersionName("not-a-version"))
    }
}
