package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.component.Component
import io.github.huang404yan.platypus.core.component.ComponentIcons
import io.github.huang404yan.platypus.core.component.ComponentInventories
import io.github.huang404yan.platypus.core.mods.ModScanner
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * 滚动负载探针：在无窗口环境里把一屏几十个模组滚一遍，逐帧计耗时。
 *
 * 跑法：
 * ```
 * gradlew :ui:scrollProbe                      # 自造 60 个模组当负载，跑完删掉
 * gradlew :ui:scrollProbe -PscrollDir=<实例目录>  # 用真目录（目录下的 mods/ 就是负载）
 * ```
 *
 * 自造负载这一条是给「常规回归」用的：不依赖任何本机素材，clone 下来就能跑，
 * 跑完打印 PASS / FAIL 并给退出码，红了就说明滚动路径上有东西退化了。
 * 它量不了真实模组的解码开销（合成 jar 只有几 KB），要那个数就用真目录。
 *
 * 为什么不放在产品代码里：要量的东西在无窗口渲染里就能量到，不必改产品代码、也不用人手操作。
 * 代价是这里没有真实的「提交到显示器」那一段，数字偏乐观。
 */
private const val WIDTH = 900
private const val HEIGHT = 700
private const val FRAME_NANOS = 16_666_667L
private const val SCROLL_PX = 120f
private const val WARM_FRAMES = 30
private const val SCROLL_FRAMES = 40
private const val SYNTHETIC_ROWS = 60

/** 「整列第一次上屏」算几帧。60 行一起组合要跨好几帧，只量首帧会被类加载稀释。 */
private const val OPENING_FRAMES = 4

/**
 * 回归阈值，按实测留足余量。
 *
 * 实测（60 行合成负载）：冷态 scroll median 1.7 / max 11.5，暖态 median 2.3 / max 7.2，
 * 首次组合帧 319–355 ms，开口五帧合计 454 ms。
 *
 * 阈值比实测宽 3 倍以上，这个宽度是有意的 —— **它能拦什么、拦不住什么要分清**：
 *
 * - 拦得住：把列表从「一屏十几行」变成「每次重组都重解一遍图」、把图标重新挪回
 *   组合线程且每帧都在解、或者整列变成每次滚动都重新组合（median 会从 2 ms 跳到几十毫秒）。
 * - 拦不住：单纯把 [io.github.huang404yan.platypus.ui.rememberIconBitmap] 里的
 *   `withContext(Dispatchers.IO)` 去掉。实测过：去掉之后首帧 351.2 vs 355.2（看不出差别），
 *   开口五帧 535.7 vs 454.0（+18%），而首次组合那一帧本来就背着几百毫秒的类加载，
 *   这点差别被噪音盖住了。所以「异步解码不许改回同步」这条不能靠这个探针守，
 *   它靠的是代码 review 与下面 [measureLegacyDecode] 打出来的那个参考数字（同步解 60 张要 290 ms 左右）。
 */
private const val MAX_WARM_MEDIAN_MS = 12.0
private const val MAX_OVER_BUDGET_WARM = 4
private const val MAX_OVER_BUDGET_COLD = 10

/**
 * 首次组合整列那一帧的上限。
 *
 * 实测（60 行合成负载）：冷态 319.4 ms（几乎全是类加载），暖态 119.9 ms。
 * 这一帧是「一屏 60 行一起去组合」的成本，图标要是又回同步解码，它第一个涨上去。
 * 阈值留到 2 倍多一点：这台机器上它本身就有 ±30% 的抖动。
 */
private const val MAX_FIRST_FRAME_COLD_MS = 800.0
private const val MAX_FIRST_FRAME_WARM_MS = 300.0

/** 复刻「已装模组」列表的形状：整列铺开、每行一个 [ComponentRow]。 */
@Composable
private fun ModListLike(entries: List<Component>, scroll: ScrollState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        entries.forEach { component ->
            ComponentRow(
                component = component,
                busy = false,
                onToggleEnabled = {},
                onDelete = {},
            )
        }
    }
}

private class Probe {
    var clock = 0L

    fun frame(scene: ImageComposeScene): Double {
        clock += FRAME_NANOS
        val began = System.nanoTime()
        scene.render(clock)
        return (System.nanoTime() - began) / 1_000_000.0
    }
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    if (sorted.isEmpty()) return 0.0
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

private fun fmt(values: List<Double>): String {
    if (values.isEmpty()) return "n/a"
    return String.format("median %.1f  mean %.1f  max %.1f", median(values), values.average(), values.max())
}

private class PassResult(
    /**
     * 头几帧的累计主线程耗时。
     *
     * 为什么不是只看首帧：图标解码要是压在组合线程上，那笔钱落在「整列第一次组合」
     * 的那一帧里 —— 但那一帧同时也背着类加载，量级上会被稀释；而异步解码会在 IO 线程上
     * 慢慢解、主线程只是稍后多一次重组。看头几帧的合计，两种情况都盖得住。
     */
    val opening: Double,
    val firstFrame: Double,
    val during: List<Double>,
)

/**
 * @param warmupFrames 未计入统计的空跑帧数。冷态传 3 而不是 30：首次组合整列
 *   （连同每一行的图标）就发生在那一帧里，预热太久会把它算进「热身」从而量不到 ——
 *   而「一屏 60 行的首次组合」正是同步解码退化时最该被抓住的地方。
 */
private fun runPass(label: String, entries: List<Component>, scrolls: Int, warmupFrames: Int): PassResult {
    val probe = Probe()
    val scroll = ScrollState(0)
    val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        ModListLike(entries, scroll)
    }
    try {
        val firstFrame = probe.frame(scene)
        // 再跟 OPENING_FRAMES 帧，连首帧一起算「整列第一次出现在屏幕上」这笔账。
        var opening = firstFrame
        repeat(OPENING_FRAMES) { opening += probe.frame(scene) }
        repeat(warmupFrames) { probe.frame(scene) }
        val startOffset = scroll.value
        val maxOffset = scroll.maxValue
        val beforeScroll = (0 until 5).map { probe.frame(scene) }
        val during = (0 until scrolls).map {
            scene.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = Offset(WIDTH / 2f, HEIGHT / 2f),
                scrollDelta = Offset(0f, SCROLL_PX),
            )
            probe.frame(scene)
        }
        val afterScroll = (0 until 5).map { probe.frame(scene) }
        println("[" + label + "] rows=" + entries.size + " scrolls=" + scrolls +
            " offset " + startOffset + " -> " + scroll.value + " (max " + maxOffset + ")")
        println("  first   " + String.format("%.1f", firstFrame) + " ms (" + warmupFrames + " warmup frames follow)")
        println("  opening " + String.format("%.1f", opening) + " ms (" + (OPENING_FRAMES + 1) + " frames, first composition included)")
        println("  idle    " + fmt(beforeScroll))
        println("  scroll  " + fmt(during))
        println("  over16.7 " + during.count { it > 16.7 } + "/" + during.size)
        println("  settle  " + fmt(afterScroll))
        // 滚动一帧都没动过，说明指针事件根本没生效 —— 那种情况下数字再漂亮也不算数。
        val moved = scroll.value != startOffset
        println("  scrolled " + (if (moved) "yes" else "NO (pointer event did not reach the list)"))
        return PassResult(opening, firstFrame, during)
    } finally {
        scene.close()
    }
}

/**
 * 对照：如果图标解码还压在组合线程上，一屏要付多少。
 *
 * c47633c 之前三处图标都是 `remember { file.inputStream().use { loadImageBitmap(it) } }`，
 * 解码直接摊在组合那一帧里。这一段就照那个写法在调用线程上同步做一遍，用它的耗时
 * 说明那项改动值多少 —— 不看它在探针里省了几毫秒（改了之后就已经不在关键路径上了）。
 */
private fun measureLegacyDecode(entries: List<Component>, cacheDir: File): Double {
    val began = System.nanoTime()
    var decoded = 0
    entries.forEach { component ->
        val file = ComponentIcons.iconOf(component.kind, File(component.path), component.fileName, cacheDir)
        if (file != null && file.isFile) {
            runCatching { file.inputStream().use { loadImageBitmap(it) } }.onSuccess { decoded++ }
        }
    }
    val ms = (System.nanoTime() - began) / 1_000_000.0
    // 全 ASCII：Windows 控制台是 GBK，中文 println 出来是乱码，grep 不到。
    println("[legacy] sync-decode " + decoded + " icons = " + String.format("%.1f", ms) + " ms (this used to run on the composition thread)")
    return ms
}

/**
 * 一张 256×256 的渐变 png。
 *
 * 尺寸与花纹是照着「真实模组图标」挑的：64×64 纯色块解起来快到量不出差别，
 * 那样就算解码真还压在组合线程上，探针也抓不住（实测只差 30 ms，淹没在类加载噪声里）。
 * 256×256 带渐变、每张颜色都不同，解码成本接近真图，同时 png 仍只有几十 KB，
 * 生成与读盘都不拖慢扫描。
 */
private fun pngBytes(seed: Int): ByteArray {
    val side = 256
    val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    val r0 = (seed * 37 + 40) % 256
    val g0 = (seed * 91 + 60) % 256
    val b0 = (seed * 53 + 90) % 256
    // 叠一层噪声：纯渐变的 png 压缩得太狠，解出来不到 1 ms，抓不住「同步解码」这种退化。
    // 真模组图标是有纹理的，解码成本就在这儿。
    val rnd = java.util.Random(seed.toLong())
    for (y in 0 until side) {
        for (x in 0 until side) {
            val t = (x + y).toFloat() / (side * 2)
            val noise = rnd.nextInt(48) - 24
            val c = Color(
                (r0 + (255 - r0) * t + noise).toInt().coerceIn(0, 255),
                (g0 + (255 - g0) * t + noise).toInt().coerceIn(0, 255),
                (b0 + (255 - b0) * t + noise).toInt().coerceIn(0, 255),
            )
            image.setRGB(x, y, c.rgb)
        }
    }
    g.color = Color.WHITE
    g.font = Font(Font.SANS_SERIF, Font.BOLD, 64)
    g.drawString(seed.toString(), 24, 140)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}

/**
 * 自造一屏负载：每个 jar 里一份最小 fabric.mod.json + 一张真 png。
 *
 * 为什么要造而不是直接拿本机某份模组包：常规回归要不依赖任何本机素材。
 * 合成 jar 只有几 KB，解码比真模组快得多，所以它拦的是「结构退化」（同步解码、
 * 整列一次性重组），不是真实的解码耗时 —— 后者用 -PscrollDir 指真目录去量。
 */
private fun buildSyntheticMods(modsDir: File, count: Int) {
    modsDir.mkdirs()
    for (i in 0 until count) {
        val name = "synthetic-" + i.toString().padStart(2, '0')
        val jar = File(modsDir, name + ".jar")
        ZipOutputStream(jar.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write(
                ("{\"id\":\"" + name + "\",\"name\":\"Synthetic " + i + "\",\"version\":\"1.0." + i + "\"}")
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("icon.png"))
            zip.write(pngBytes(i))
            zip.closeEntry()
        }
    }
}

fun main(args: Array<String>) {
    // 有参数就用真目录；没有就自造一份临时负载，跑完删掉。
    val explicit = args.getOrNull(0)?.takeIf { it.isNotBlank() }
    val tempRoot = if (explicit == null) Files.createTempDirectory("scroll-probe").toFile() else null
    val gameDir = if (explicit != null) {
        File(explicit)
    } else {
        File(tempRoot, "versions/1.20.1-synthetic").also { instance ->
            buildSyntheticMods(File(instance, "mods"), SYNTHETIC_ROWS)
            File(instance, "1.20.1-synthetic.json")
                .writeText("{\"id\":\"1.20.1-synthetic\",\"inheritsFrom\":\"1.20.1\"}")
            // 扫描器认 versions/ libraries/ assets/ 三者之一即可，这里补齐免得整份被判成非游戏目录。
            File(tempRoot, "assets").mkdirs()
            File(tempRoot, "libraries").mkdirs()
        }
    }
    println("gameDir = " + gameDir.absolutePath + (if (explicit == null) " (synthetic, removed on exit)" else ""))

    var exit = 0
    try {
        val scanBegan = System.nanoTime()
        val entries = ComponentInventories.ofMods(ModScanner.scan(gameDir)).enabled
        println("scan " + entries.size + " entries in " + ((System.nanoTime() - scanBegan) / 1_000_000) + " ms")
        if (entries.isEmpty()) {
            println("FAIL no mods found under " + gameDir.absolutePath)
            exit = 1
        } else {
            // 第一遍：图标都还没解码过，滚过去时才会一张张解。
            // 冷态只空跑 3 帧：首次组合整列那一下要留在统计里。
            val cold = runPass("cold", entries, SCROLL_FRAMES, warmupFrames = 3)
            // 第二遍：同一进程里再滚一遍，类加载与图标解码都已在缓存里。
            val warm = runPass("warm", entries, SCROLL_FRAMES, warmupFrames = WARM_FRAMES)

            // 用独立的缓存目录，别污染产品那份（也保证这里是真解一遍，不是命中缓存）。
            val legacyCache = Files.createTempDirectory("scroll-probe-icons").toFile()
            try {
                measureLegacyDecode(entries, legacyCache)
            } finally {
                legacyCache.deleteRecursively()
            }

            val warmMedian = median(warm.during)
            val warmOver = warm.during.count { it > 16.7 }
            val coldOver = cold.during.count { it > 16.7 }
            var failed = false
            fun check(name: String, ok: Boolean, detail: String) {
                println((if (ok) "PASS " else "FAIL ") + name + " :: " + detail)
                if (!ok) failed = true
            }
            check(
                "cold first frame <= " + MAX_FIRST_FRAME_COLD_MS + " ms",
                cold.firstFrame <= MAX_FIRST_FRAME_COLD_MS,
                String.format("%.1f ms", cold.firstFrame),
            )
            check(
                "warm first frame <= " + MAX_FIRST_FRAME_WARM_MS + " ms",
                warm.firstFrame <= MAX_FIRST_FRAME_WARM_MS,
                String.format("%.1f ms", warm.firstFrame),
            )
            check(
                "warm scroll median <= " + MAX_WARM_MEDIAN_MS + " ms",
                warmMedian <= MAX_WARM_MEDIAN_MS,
                String.format("%.1f ms", warmMedian),
            )
            check(
                "warm frames over 16.7ms <= " + MAX_OVER_BUDGET_WARM,
                warmOver <= MAX_OVER_BUDGET_WARM,
                warmOver.toString() + "/" + warm.during.size,
            )
            check(
                "cold frames over 16.7ms <= " + MAX_OVER_BUDGET_COLD,
                coldOver <= MAX_OVER_BUDGET_COLD,
                coldOver.toString() + "/" + cold.during.size,
            )
            if (failed) exit = 1
        }
    } catch (e: Throwable) {
        e.printStackTrace()
        exit = 1
    } finally {
        if (tempRoot != null) tempRoot.deleteRecursively()
    }
    println(if (exit == 0) "SCROLL PROBE OK" else "SCROLL PROBE FAILED")
    exitProcess(exit)
}
