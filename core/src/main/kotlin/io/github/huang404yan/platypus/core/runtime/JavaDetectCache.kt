package io.github.huang404yan.platypus.core.runtime

/**
 * [JavaRuntimeService.detect] 的短期缓存。
 *
 * 探测一次的开销不在枚举目录上，而在「把每个候选 java 真跑起来看一眼」：读注册表、
 * 扫几个厂商安装目录、PATH 里每个 javapath 垫片都要起一个进程。本机装过几个 JDK，
 * 这一趟就是几秒。而启动页、实例详情、Java 页、诊断页各自都会调一次，同一屏里
 * 连调三次就白跑三遍，用户看到的是「打开启动器要愣两秒」。
 *
 * 规则：
 * - **只缓存非空结果**。空列表通常意味着「还没装完」或「这次没扫到」，缓存住会让
 *   用户装完 Java 也看不见，于是下一次又自动补一遍，兜成循环。
 * - 过了 [ttlMillis] 就作废。托管运行时可能在启动器之外被删掉（用户手删、杀毒软件
 *   隔离），缓存不能一直当真。
 * - 读写加锁：[detect] 会在启动页与实例详情里并发发起，不许读到半截列表。
 */
class JavaDetectCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var value: List<JavaRuntime>? = null
    private var stamp: Long = 0L

    /** 缓存里的结果；没探过、已过期、或上次探到空都返回 null。 */
    fun get(): List<JavaRuntime>? = synchronized(lock) {
        val cached = value ?: return@synchronized null
        if (clock() - stamp > ttlMillis) return@synchronized null
        cached
    }

    /** 记下一次探测结果。空列表不进缓存，并顺手把旧结果作废。 */
    fun put(result: List<JavaRuntime>) = synchronized(lock) {
        if (result.isEmpty()) {
            value = null
            stamp = 0L
        } else {
            value = result
            stamp = clock()
        }
    }

    /** 作废：装完 / 删掉一份托管运行时之后，以及用户点「重新探测」时调用。 */
    fun invalidate() = synchronized(lock) {
        value = null
        stamp = 0L
    }

    companion object {
        /**
         * 默认 30 秒。
         *
         * 够挡掉「同一屏里连调几次」这种密集重复，又不至于让刚装好的 Java 迟迟不出现 ——
         * 装完本来就会显式作废，TTL 只是兜底。
         */
        const val DEFAULT_TTL_MILLIS = 30_000L
    }
}
