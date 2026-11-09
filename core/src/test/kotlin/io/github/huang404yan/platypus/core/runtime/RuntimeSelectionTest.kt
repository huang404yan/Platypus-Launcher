package io.github.huang404yan.platypus.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 运行时挑选规则：只认主版本精确相等，绝不拿高版本顶替。
 *
 * 这条规则是 1.16.5 的实际坑：老版本在 Java 16+ 上会直接崩，
 * 静默挑一个「更高的 Java」会让用户面对一个莫名其妙闪退的启动器。
 */
class RuntimeSelectionTest {

    private fun runtime(major: Int, path: String = "D:/jdk-" + major + "/bin/java.exe") =
        JavaRuntime(path = path, majorVersion = major, vendor = "Test", arch = "amd64", bits = 64)

    @Test
    fun `主版本精确相等才命中`() {
        val all = listOf(runtime(8), runtime(17), runtime(21))
        assertEquals(17, all.findExactMajor(17)?.majorVersion)
        assertEquals(8, all.findExactMajor(8)?.majorVersion)
        assertEquals(21, all.findExactMajor(21)?.majorVersion)
    }

    @Test
    fun `没有精确匹配时返回 null 而不是挑一个更高的`() {
        val all = listOf(runtime(17), runtime(21), runtime(25))
        assertNull(all.findExactMajor(8), "本机没有 Java 8 时不能拿 17 顶上")
        assertNull(all.findExactMajor(16))
        assertNull(all.findExactMajor(11))
    }

    @Test
    fun `空列表与重复主版本都能处理`() {
        assertNull(emptyList<JavaRuntime>().findExactMajor(17))
        val all = listOf(runtime(17, "D:/a/bin/java.exe"), runtime(17, "D:/b/bin/java.exe"))
        assertEquals("D:/a/bin/java.exe", all.findExactMajor(17)?.path, "同主版本时取先探测到的那个")
    }
}
