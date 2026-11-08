package io.github.huang0yan4.platypus.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.huang0yan4.platypus.core.instance.InstanceScanner
import io.github.huang0yan4.platypus.ui.App
import io.github.huang0yan4.platypus.ui.defaultGameRoot
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

    application {
        Window(onCloseRequest = ::exitApplication, title = "鸭嘴兽启动器") {
            App(initialGameRoot = root.ifBlank { null })
        }
    }
}
