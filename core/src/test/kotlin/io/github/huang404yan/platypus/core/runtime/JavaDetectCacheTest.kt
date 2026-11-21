package io.github.huang404yan.platypus.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 探测缓存的取舍：什么时候敢复用上一次的探测结果。
 *
 * 这里错一次的代价是「用户装完 Java 却看不见」或者「删掉的运行时还挂在列表里」，
 * 所以三条边界各测一条：空结果不进缓存、过期即失效、写入新结果后立刻可见。
 */
class JavaDetectCacheTest {

    private fun runtime(major: Int) = JavaRuntime(path = "/java/" + major + "/bin/java", majorVersion = major)

    private class FakeClock(var now: Long = 1_000L) {
        fun advance(millis: Long) {
            now += millis
        }
    }

    @Test
    fun `空结果不进缓存 免得装完 Java 还看不见`() {
        val cache = JavaDetectCache(ttlMillis = 30_000L)
        cache.put(emptyList())
        assertNull(cache.get())
    }

    @Test
    fun `空结果同时作废旧值 机器上一个 Java 都没了就别再拿旧的顶上`() {
        val cache = JavaDetectCache(ttlMillis = 30_000L)
        cache.put(listOf(runtime(21)))
        assertEquals(1, cache.get()?.size)
        cache.put(emptyList())
        assertNull(cache.get())
    }

    @Test
    fun `TTL 之内复用 过了就重新探`() {
        val clock = FakeClock()
        val cache = JavaDetectCache(ttlMillis = 30_000L, clock = { clock.now })
        cache.put(listOf(runtime(17), runtime(21)))
        clock.advance(29_000L)
        assertEquals(2, cache.get()?.size)
        clock.advance(1_001L)
        assertNull(cache.get())
    }

    @Test
    fun `invalidate 之后立刻失效 装完或删掉运行时都走它`() {
        val cache = JavaDetectCache(ttlMillis = 30_000L)
        cache.put(listOf(runtime(8)))
        cache.invalidate()
        assertNull(cache.get())
    }

    @Test
    fun `没探过就是 null 不能返回空列表冒充探过了`() {
        val cache = JavaDetectCache(ttlMillis = 30_000L)
        assertNull(cache.get())
    }

    @Test
    fun `再 put 一次会重置计时 不用等满 TTL`() {
        val clock = FakeClock()
        val cache = JavaDetectCache(ttlMillis = 10_000L, clock = { clock.now })
        cache.put(listOf(runtime(17)))
        clock.advance(9_000L)
        cache.put(listOf(runtime(17), runtime(21)))
        clock.advance(9_000L)
        assertEquals(2, cache.get()?.size)
    }
}
