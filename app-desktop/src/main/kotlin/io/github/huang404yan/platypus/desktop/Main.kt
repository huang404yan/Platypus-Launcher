package io.github.huang404yan.platypus.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.ui.App
import io.github.huang404yan.platypus.ui.defaultGameRoot
import org.jetbrains.skia.Image
import java.io.File

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

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = title,
            icon = loadWindowIcon(),
        ) {
            App(initialGameRoot = root.ifBlank { null })
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
