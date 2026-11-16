package io.github.huang404yan.platypus.core.instance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「这个实例实际给多少内存」的规则。
 *
 * 这条规则同时被启动页、实例卡片、版本设置、安卓端读 —— 直接读 `instance.memoryMb` 会让
 * 「跟随全局」在那些地方静默失效（改设置里的默认内存，界面上纹丝不动），所以它值得单测。
 */
class InstanceMemoryTest {

    private fun instance(
        memoryMb: Int = 2048,
        follows: Boolean = false,
    ) = Instance(
        id = "1.21.1",
        name = "1.21.1",
        gameVersion = "1.21.1",
        directory = File("C:/mc/versions/1.21.1").path,
        memoryMb = memoryMb,
        memoryFollowsDefault = follows,
    )

    @Test
    fun `不跟随的实例读自己存的那个数`() {
        val target = instance(memoryMb = 6144, follows = false)
        assertEquals(6144, Instance.effectiveMemoryMb(target, 8192))
    }

    @Test
    fun `跟随的实例读设置里的默认内存，不看自己存的旧数`() {
        val target = instance(memoryMb = 2048, follows = true)
        assertEquals(8192, Instance.effectiveMemoryMb(target, 8192))
    }

    @Test
    fun `默认内存越界时跟随的实例也夹到合法区间`() {
        val target = instance(follows = true)
        assertEquals(Instance.MAX_MEMORY_MB, Instance.effectiveMemoryMb(target, 999_999))
        assertEquals(Instance.MIN_MEMORY_MB, Instance.effectiveMemoryMb(target, 1))
    }

    @Test
    fun `抄默认内存只对跟随的实例动手`() {
        val following = instance(memoryMb = 2048, follows = true)
        assertEquals(8192, Instance.applyDefaultMemory(following, 8192).memoryMb)

        val own = instance(memoryMb = 6144, follows = false)
        assertEquals(6144, Instance.applyDefaultMemory(own, 8192).memoryMb)
    }

    @Test
    fun `抄写时同样夹区间`() {
        val following = instance(follows = true)
        assertEquals(Instance.MAX_MEMORY_MB, Instance.applyDefaultMemory(following, 99_999).memoryMb)
    }

    @Test
    fun `旧数据没有这个字段时按不跟随处理`() {
        // instances.json 里 0.8.0-preview.20 之前的记录没有 memoryFollowsDefault，
        // 反序列化会落到默认值 false —— 老实例里那个数是用户亲手选的，不能自动变成跟随。
        val legacy = instance(memoryMb = 3072)
        assertFalse(legacy.memoryFollowsDefault)
        assertEquals(3072, Instance.effectiveMemoryMb(legacy, 16384))
        assertTrue(legacy == legacy.copy(memoryFollowsDefault = false))
    }
}
