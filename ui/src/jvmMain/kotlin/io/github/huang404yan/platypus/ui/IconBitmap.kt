package io.github.huang404yan.platypus.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 图标解码：不在组合线程上读盘。
 *
 * 原来三处图标（模组、组件、存档）都是 `remember { file.inputStream().use { loadImageBitmap(it) } }`，
 * 解一张 40dp 的 png 看着不起眼，但列表一次进来十来个就是十几毫秒，而组合线程一卡，
 * 整帧就跟着往后挪 —— 列表滚动时那种「跟手不跟手」的差别就出在这儿。
 *
 * 现在统一走这里：先给 null（卡片先画首字母占位），IO 线程解完再回来换图。
 * 占位是本来就要有的降级路径（没网、作者没传图），所以多这一帧不新增状态，
 * 用户看到的是「先字母后图」，和网络图标到达时一模一样。
 */
@Composable
internal fun rememberIconBitmap(file: File?, stamp: Any? = null): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, file?.absolutePath, stamp, file?.length()) {
        value = withContext(Dispatchers.IO) { IconBitmaps.load(file) }
    }
    return state.value
}

/**
 * 按文件缓存解码结果。
 *
 * 同一条列表上下滚两下就会反复要同一批文件；磁盘页缓存能省掉 IO，但解码本身还在，
 * 二十张乘起来就落在关键路径上。
 *
 * 键里带长度与修改时间：整合包里换个图、或存档截了新缩略图，必须重解，
 * 否则界面上永远是旧的那张，用户会以为「改了没生效」。
 *
 * 淘汰按最近最少使用：滚动时反复要的是同一屏那十几张，把最久没碰的挤掉即可。
 * 上限按「一屏列表 + 两屏预取」估的。
 *
 * 值类型做成泛型只为一件事：让这层逻辑能脱离 skia 单测 —— 真拿 [ImageBitmap] 当
 * 测试替身会去初始化 skiko 原生库，单测类路径里没有它。
 */
internal class IconCache<T>(private val maxEntries: Int = 96) {

    private val entries = object : LinkedHashMap<String, T?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T?>): Boolean =
            size > maxEntries
    }

    /**
     * 取一个结果，没解过就解。
     *
     * 解失败也记下来（存 null）：文件坏了的话，每次重组都重读一遍、重解一遍、
     * 再失败一次，比成功更贵。返回 null 时调用方画占位，与「没图」同一条路。
     */
    fun get(file: File?, decode: (File) -> T?): T? {
        if (file == null || !file.isFile) return null
        val key = key(file)
        synchronized(entries) {
            // 先用 containsKey 判在不在（它不动访问顺序），再用 get 取值（它会把这条
            // 挪到队尾）。只调 get 分不清「没有」和「解失败存下的 null」。
            if (entries.containsKey(key)) return entries[key]
        }
        val value = runCatching { decode(file) }.getOrNull()
        synchronized(entries) { entries[key] = value }
        return value
    }

    /** 缓存键：路径 + 长度 + 修改时间。抽出来是为了能单测「改过之后键变了」。 */
    fun key(file: File): String = file.absolutePath + "|" + file.length() + "|" + file.lastModified()

    /** 当前存了多少条（含解失败的）。 */
    val size: Int get() = synchronized(entries) { entries.size }
}

/** skia 那条解码路径。单独拎出来，好让上面那层不依赖它就能测。 */
internal fun decodeImage(file: File): ImageBitmap? = file.inputStream().use { loadImageBitmap(it) }

internal object IconBitmaps {

    private val cache = IconCache<ImageBitmap>()

    /** 解一张图。任何异常都当「没有图」处理，不让一张坏图解崩掉整页。 */
    fun load(file: File?): ImageBitmap? = cache.get(file, ::decodeImage)
}
