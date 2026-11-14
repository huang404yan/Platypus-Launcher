package io.github.huang404yan.platypus.core.instance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceEditsTest {

    private fun instance(memoryMb: Int = 2048) = Instance(
        id = "1.20.1",
        name = "1.20.1",
        gameVersion = "1.20.1",
        directory = File("C:/mc/versions/1.20.1").path,
        memoryMb = memoryMb,
        javaPath = null,
        extraJvmArgs = listOf("-XX:+UseG1GC"),
    )

    private fun ok(result: InstanceEditResult): InstanceEditResult.Ok {
        assertTrue(result is InstanceEditResult.Ok, "本该能保存，实际是 " + result)
        return result
    }

    @Test
    fun `填整数就存下来，并把实例登记上`() {
        val result = ok(InstanceEdits.fromInput(instance(), "8192", "", ""))
        assertEquals(8192, result.instance.memoryMb)
        // 写进登记文件就意味着登记；不置 true 的话下次启动会被当成外部实例
        assertTrue(result.instance.registered)
        assertTrue(!result.memoryWasClamped)
    }

    @Test
    fun `内存不是整数时给一句能直接显示的话，而不是抛异常`() {
        val result = InstanceEdits.fromInput(instance(), "八G", "", "")
        assertTrue(result is InstanceEditResult.Invalid)
        assertEquals(InstanceEdits.BAD_MEMORY_MESSAGE, result.message)
    }

    @Test
    fun `内存空着也算没填`() {
        val result = InstanceEdits.fromInput(instance(), "   ", "", "")
        assertTrue(result is InstanceEditResult.Invalid)
    }

    @Test
    fun `超出范围的内存被夹紧，并且报告改过`() {
        val low = ok(InstanceEdits.fromInput(instance(), "64", "", ""))
        assertEquals(Instance.MIN_MEMORY_MB, low.instance.memoryMb)
        assertTrue(low.memoryWasClamped)

        val high = ok(InstanceEdits.fromInput(instance(), "999999", "", ""))
        assertEquals(Instance.MAX_MEMORY_MB, high.instance.memoryMb)
        assertTrue(high.memoryWasClamped)
    }

    @Test
    fun `首尾空格不算改过`() {
        // 填 " 4096 " 与 "4096" 是一回事，不该弹「已按范围改成 4096」
        val result = ok(InstanceEdits.fromInput(instance(), " 4096 ", "", ""))
        assertEquals(4096, result.instance.memoryMb)
        assertTrue(!result.memoryWasClamped)
    }

    @Test
    fun `JVM 参数一行一个，空行与首尾空格都丢掉`() {
        val text = "-XX:+UseG1GC\n\n  -Xms512m  \n\n-Xmx4g\n"
        assertEquals(listOf("-XX:+UseG1GC", "-Xms512m", "-Xmx4g"), InstanceEdits.jvmArgsOf(text))
        // 全空行等于没填参数，而不是「一个空串参数」——空串传给 java 会直接启动失败
        assertEquals(emptyList(), InstanceEdits.jvmArgsOf("\n\n  \n"))
    }

    @Test
    fun `Java 路径留空就是不要它`() {
        assertNull(ok(InstanceEdits.fromInput(instance(), "2048", "", "   ")).instance.javaPath)
        assertEquals(
            "C:/java/bin/javaw.exe",
            ok(InstanceEdits.fromInput(instance(), "2048", "", " C:/java/bin/javaw.exe ")).instance.javaPath,
        )
    }

    @Test
    fun `只改这三项，别的不动`() {
        // 详情页负责改设置，不负责搬家：id / 目录 / 加载器 / 创建时间必须原样带回来
        val original = instance().copy(
            loader = LoaderKind.NEOFORGE,
            loaderVersion = "21.1.265",
            createdAt = 1_700_000_000_000L,
        )
        val updated = ok(InstanceEdits.fromInput(original, "6144", "-Xmx6g", "")).instance
        assertEquals(original.id, updated.id)
        assertEquals(original.directory, updated.directory)
        assertEquals(original.gameVersion, updated.gameVersion)
        assertEquals(LoaderKind.NEOFORGE, updated.loader)
        assertEquals("21.1.265", updated.loaderVersion)
        assertEquals(original.createdAt, updated.createdAt)
    }

    @Test
    fun `不传隔离开关时不改它`() {
        // 安卓详情页与老调用点都不传这一项，必须保持原值 —— 传 false 会把隔离悄悄关掉
        val original = instance().copy(isolated = false)
        val updated = ok(InstanceEdits.fromInput(original, "2048", "", "")).instance
        assertTrue(!updated.isolated)
        assertTrue(ok(InstanceEdits.fromInput(instance(), "2048", "", "")).instance.isolated)
    }

    @Test
    fun `传了隔离开关就按传的存`() {
        val off = ok(InstanceEdits.fromInput(instance(), "2048", "", "", isolated = false)).instance
        assertTrue(!off.isolated)
        val on = ok(InstanceEdits.fromInput(instance().copy(isolated = false), "2048", "", "", isolated = true)).instance
        assertTrue(on.isolated)
    }

    @Test
    fun `保存提示会把参数个数说清楚`() {
        assertEquals("已保存：2048 MB", InstanceEdits.savedMessage(ok(InstanceEdits.fromInput(instance(), "2048", "", ""))))
        assertEquals(
            "已保存：2048 MB，2 个 JVM 参数",
            InstanceEdits.savedMessage(ok(InstanceEdits.fromInput(instance(), "2048", "-Xms1g\n-Xmx2g", ""))),
        )
    }
}
