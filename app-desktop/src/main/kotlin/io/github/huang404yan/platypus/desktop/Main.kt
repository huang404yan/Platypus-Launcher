package io.github.huang404yan.platypus.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.theme.LauncherTheme
import io.github.huang404yan.platypus.ui.App
import io.github.huang404yan.platypus.ui.defaultGameRoot
import org.jetbrains.skia.Image
import java.awt.Dimension
import java.io.File

/**
 * 默认窗口 1280x820：1080p 屏上不用最大化也能放下整页。
 *
 * 以前没有默认尺寸，Compose 给的初始窗口比内容窄，右侧按钮会被裁掉，
 * 用户第一眼看到的就是「必须最大化才好用」。最小尺寸卡在 1024x640，
 * 再小就没法并排放下侧栏与表单了——各页面自己还有滚动兜底。
 */
fun main(args: Array<String>) {
    val root = args.firstOrNull()
        ?: System.getenv("PLATYPUS_GAME_DIR")
        ?: defaultGameRoot()

    if (root.isNotBlank()) {
        val result = InstanceScanner.scan(File(root))
        println("[scan] root=${result.root.path} gameDirectory=${result.isGameDirectory} instances=${result.instances.size}")
        result.instances.forEach { println("[scan]   ${it.name}  mc=${it.gameVersion}  loader=${it.loader} ${it.loaderVersion ?: ""}") }
        result.warnings.forEach { println("[scan] warning: $it") }
    }

    val title = if (Brand.isPreview) "${Brand.PREVIEW_NAME_ZH} ${Brand.version}" else Brand.NAME_ZH

    // 组合根：整个程序只有这一处装配后端，Composable 里不 new 任何实现。
    val services = DesktopServices.create(root.takeIf { it.isNotBlank() }?.let(::File))

    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = title,
            icon = loadWindowIcon(),
            state = windowState,
        ) {
            LaunchedEffect(Unit) {
                // 拖到比这还小，侧栏与表单会互相挤，不如直接卡住下限。
                window.minimumSize = Dimension(1024, 640)
            }
            // 标题栏由系统画，深色主题下那条浅灰白接缝很扎眼，所以这里跟着配色改。
            // 存成一个状态而不是直接在回调里调：窗口刚组合时 peer 可能还没建好，
            // LaunchedEffect 会等一帧再重试一次（见下面那段 delay）。
            // 标题栏固定深色，不跟随配色。
            //
            // 原因见 WindowTheme 顶部的注释：Win10 上这个属性常常只能单向生效
            // （能设成深色、设不回浅色），跟随的结果是「界面变浅了、标题栏还是黑的」。
            // 固定一个两套配色都能接受的颜色更稳：深潭青与「自然原色」完全一致，
            // 配「特工蓝橙」也是常见的「浅色界面 + 深色标题栏」观感。
            LaunchedEffect(Unit) {
                // 标题栏按版本定，不跟随用户选的配色：
                // 正式版默认「自然原色」（深色界面）配深色标题栏，孵化版默认
                // 「特工蓝橙」（浅色界面）配浅色标题栏 —— 各与本版本的默认配色对齐。
                // 同一版本内不再变化，也就没有「切回浅色切不动」的问题。
                val dark = !Brand.isPreview
                var applied = WindowTheme.applyDarkTitleBar(window, dark)
                if (!applied) {
                    kotlinx.coroutines.delay(250)
                    applied = WindowTheme.applyDarkTitleBar(window, dark)
                }
                println("[ui] title bar dark=$dark applied=$applied")
            }
            App(
                initialGameRoot = root.ifBlank { null },
                backend = services,
                // 配色不再影响标题栏，接口保留只是为了不牵动 ui 模块的签名。
                onThemeChanged = { },
            )
        }
    }
}

/** 窗口图标从 jar 内的 icon/platypus.png 读取；读不到就不带图标，不阻断启动。 */
private fun loadWindowIcon(): BitmapPainter? {
    val bytes = Thread.currentThread().contextClassLoader
        ?.getResourceAsStream("icon/platypus.png")
        ?.use { it.readBytes() }
        ?: return null
    return runCatching { BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap()) }.getOrNull()
}
