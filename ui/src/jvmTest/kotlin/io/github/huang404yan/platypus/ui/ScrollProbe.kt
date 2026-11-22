package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.loadImageBitmap
import io.github.huang404yan.platypus.core.component.Component
import io.github.huang404yan.platypus.core.component.ComponentIcons
import io.github.huang404yan.platypus.core.component.ComponentInventories
import io.github.huang404yan.platypus.core.mods.ModScanner
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * 滚动负载探针：在无窗口环境里把一屏几十个模组滚一遍，逐帧计耗时。
 *
 * 为什么不放在产品代码里：要量的东西在无窗口渲染里就能量到，不必改产品代码、也不用人手操作。
 * 代价是这里没有真实的「提交到显示器」那一段，数字偏乐观。
 *
 * 参数是「一个装着若干 .jar 的模组目录的实例目录」。造负载的办法（每个 jar 都要真带
 * 图标，否则量的是「没图」那条路）：
 *
 * ```
 * R=dist/_scrollmc; mkdir -p $R/assets $R/libraries $R/versions/1.20.1-test/mods
 * echo '{"id":"1.20.1-test","inheritsFrom":"1.20.1"}' > $R/versions/1.20.1-test/1.20.1-test.json
 * for i in $(seq -w 0 59); do
 *   cp <随便一个带 icon.png 的 mod jar> $R/versions/1.20.1-test/mods/mod$i.jar
 * done
 * ```
 */
private const val WIDTH = 900
private const val HEIGHT = 700
private const val FRAME_NANOS = 16_666_667L
private const val SCROLL_PX = 120f
private const val WARM_FRAMES = 30
private const val SCROLL_FRAMES = 40

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

private fun runPass(label: String, entries: List<Component>, scrolls: Int) {
    val probe = Probe()
    val scroll = ScrollState(0)
    val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
        ModListLike(entries, scroll)
    }
    try {
        repeat(WARM_FRAMES) { probe.frame(scene) }
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
        println("  idle    " + fmt(beforeScroll))
        println("  scroll  " + fmt(during))
        println("  over16.7 " + during.count { it > 16.7 } + "/" + during.size)
        println("  settle  " + fmt(afterScroll))
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

fun main(args: Array<String>) {
    val gameDir = File(args.getOrElse(0) { "dist/_scrollmc/versions/1.20.1-test" })
    println("gameDir = " + gameDir.absolutePath)
    val scanBegan = System.nanoTime()
    val entries = ComponentInventories.ofMods(ModScanner.scan(gameDir)).enabled
    println("scan " + entries.size + " entries in " + ((System.nanoTime() - scanBegan) / 1_000_000) + " ms")
    var exit = 0
    try {
        // 第一遍：图标都还没解码过，滚过去时才会一张张解。
        runPass("cold", entries, SCROLL_FRAMES)
        // 第二遍：同一进程里再滚一遍，图标已在进程级缓存里。
        runPass("warm", entries, SCROLL_FRAMES)
        // 用独立的缓存目录，别污染产品那份（也保证这里是真解一遍，不是命中缓存）。
        val legacyCache = Files.createTempDirectory("scroll-probe-icons").toFile()
        try {
            measureLegacyDecode(entries, legacyCache)
        } finally {
            legacyCache.deleteRecursively()
        }
    } catch (e: Throwable) {
        e.printStackTrace()
        exit = 1
    }
    println(if (exit == 0) "SCROLL PROBE OK" else "SCROLL PROBE FAILED")
    exitProcess(exit)
}
