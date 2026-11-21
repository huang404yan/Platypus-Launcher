package io.github.huang404yan.platypus.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaRecommenderTest {

    private fun runtime(major: Int, managed: Boolean = false, path: String = "/java/" + major + "/bin/java") =
        JavaRuntime(path = path, majorVersion = major, managed = managed)

    private fun offer(major: Int, installed: Boolean = false, installedPath: String? = null) =
        JavaRuntimeOffer(
            component = "java-runtime-" + major,
            majorVersion = major,
            versionName = major.toString() + ".0.1",
            installed = installed,
            installedPath = installedPath,
        )

    @Test
    fun exactMajorWinsOverNearbyOnes() {
        val result = JavaRecommender.recommend(
            requiredMajor = 17,
            runtimes = listOf(runtime(8), runtime(21), runtime(17)),
        )
        assertEquals(JavaVerdict.READY, result.verdict)
        assertEquals(17, result.runtime?.majorVersion)
    }

    @Test
    fun newerJavaDoesNotStandInForOlder() {
        // 1.16.5 的 Forge 在 Java 21 上会直接崩，不能因为「21 > 17」就拿它顶。
        val result = JavaRecommender.recommend(
            requiredMajor = 17,
            runtimes = listOf(runtime(21)),
            offers = listOf(offer(17)),
        )
        assertEquals(JavaVerdict.CAN_DOWNLOAD, result.verdict)
        assertNull(result.runtime)
        assertEquals(17, result.offer?.majorVersion)
    }

    @Test
    fun managedRuntimeIsPreferredWithinSameMajor() {
        val result = JavaRecommender.recommend(
            requiredMajor = 21,
            runtimes = listOf(
                runtime(21, managed = false, path = "/system/jdk21/bin/java"),
                runtime(21, managed = true, path = "/launcher/java/21/bin/java"),
            ),
        )
        assertEquals("/launcher/java/21/bin/java", result.runtime?.path)
    }

    @Test
    fun catalogSaysInstalledButProbeMissedItStillCountsAsReady() {
        // 托管目录里的运行时不在 PATH 上，探测扫不到是常态；清单给了路径就该直接用。
        val result = JavaRecommender.recommend(
            requiredMajor = 17,
            runtimes = emptyList(),
            offers = listOf(offer(17, installed = true, installedPath = "/launcher/java/17/bin/java")),
        )
        assertEquals(JavaVerdict.READY, result.verdict)
        assertEquals("/launcher/java/17/bin/java", result.runtime?.path)
        assertTrue(result.runtime?.managed == true)
    }

    @Test
    fun withoutCatalogWeSayCanDownloadNotMissing() {
        // 清单没加载不等于官方没有这一版，不能把「不知道」说成「不存在」。
        val result = JavaRecommender.recommend(requiredMajor = 17, runtimes = emptyList(), offers = emptyList())
        assertEquals(JavaVerdict.CAN_DOWNLOAD, result.verdict)
        assertNull(result.offer)
    }

    @Test
    fun catalogLoadedWithoutThatMajorIsNotAvailable() {
        val result = JavaRecommender.recommend(
            requiredMajor = 25,
            runtimes = listOf(runtime(17)),
            offers = listOf(offer(8), offer(17), offer(21)),
        )
        assertEquals(JavaVerdict.NOT_AVAILABLE, result.verdict)
        assertEquals(listOf(17), result.installedMajors)
    }

    @Test
    fun unknownRequirementKeepsInstalledListForTheUi() {
        val result = JavaRecommender.recommend(
            requiredMajor = null,
            runtimes = listOf(runtime(21), runtime(8), runtime(21, path = "/other/21/bin/java")),
        )
        assertEquals(JavaVerdict.UNKNOWN_NEED, result.verdict)
        assertNull(result.requiredMajor)
        assertEquals(listOf(8, 21), result.installedMajors)
    }

    @Test
    fun describeAlwaysNamesTheRequiredMajor() {
        val ready = JavaRecommender.recommend(requiredMajor = 17, runtimes = listOf(runtime(17)))
        assertTrue(JavaRecommender.describe(ready).contains("Java 17"))

        val downloadable = JavaRecommender.recommend(requiredMajor = 21, runtimes = listOf(runtime(8)))
        assertTrue(JavaRecommender.describe(downloadable).contains("Java 21"))

        val missing = JavaRecommender.recommend(
            requiredMajor = 25,
            runtimes = listOf(runtime(17)),
            offers = listOf(offer(17)),
        )
        val text = JavaRecommender.describe(missing)
        assertTrue(text.contains("Java 25"))
        assertTrue(text.contains("Java 17"))
    }
}
