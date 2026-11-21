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
import io.github.huang404yan.platypus.core.instance.PortableMode
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
    // 便携模式：启动器目录下有 portable.txt（或环境变量 PLATYPUS_PORTABLE=1）时，
    // 设置、账号、实例索引全落在 <启动器目录>/data 下，不往系统用户目录写。
    // 目录建不出来（只读介质、Program Files 无写权限）就当没开，退回常规位置。
    val launcherDir = installDir()
    val portableHome = PortableMode.dataDir(launcherDir)
    if (portableHome != null) {
        println("[portable] 便携模式，数据目录 = " + portableHome.absolutePath)
    } else if (launcherDir != null && PortableMode.isEnabled(launcherDir)) {
        println("[portable] 想用便携模式，但 " + PortableMode.dataDirIn(launcherDir).absolutePath + " 建不出来，退回常规位置")
    }

    // 目录优先级：命令行参数 > 环境变量 > 安装目录下的 game\ > %APPDATA%\.minecraft。
    // 第三条是这一版才加的：以前默认落在 C 盘的用户目录里，便携版拷到别的机器、
    // 或者用户想把整个启动器连游戏一起搬走时，游戏数据会留在原地。
    val root = args.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: System.getenv("PLATYPUS_GAME_DIR")?.takeIf { it.isNotBlank() }
        ?: installGameRoot()
        ?: defaultGameRoot()

    if (root.isNotBlank()) {
        val result = InstanceScanner.scan(File(root))
        println("[scan] root=${result.root.path} gameDirectory=${result.isGameDirectory} instances=${result.instances.size}")
        result.instances.forEach { println("[scan]   ${it.name}  mc=${it.gameVersion}  loader=${it.loader} ${it.loaderVersion ?: ""}") }
        result.warnings.forEach { println("[scan] warning: $it") }
    }

    val title = if (Brand.isPreview) "${Brand.PREVIEW_NAME_ZH} ${Brand.version}" else Brand.NAME_ZH

    // 组合根：整个程序只有这一处装配后端，Composable 里不 new 任何实现。
    // launcherDir 是启动器自己的目录：托管 Java 下在那里，不在游戏目录。
    val services = DesktopServices.create(
        gameRoot = root.takeIf { it.isNotBlank() }?.let(::File),
        instanceIndex = null,
        launcherDir = launcherDir,
        homeDir = portableHome,
    )

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

/**
 * 安装目录下的 game\：便携版默认就把游戏放这儿，不往 C 盘写。
 *
 * 安装目录从 jpackage 启动器的 jpackage.app-path（exe 的绝对路径）拿；
 * 直接跑 jar 的场合退回「自己这个 jar 往上两级」，也就是 app\<产品名>\app\ 的再上一层。
 * 目录建不出来（比如装在 Program Files 下没有写权限）就返回 null，让调用方退回
 * %APPDATA%\.minecraft —— 不能因为默认值选不出来就起不来。返回空串不行：
 * 空串不是 null，`?:` 不会触发，root 会变成「没有默认目录」。
 */
private fun installGameRoot(): String? {
    val base = installDir() ?: return null
    val candidate = File(base, "game")
    val usable = runCatching { candidate.isDirectory || candidate.mkdirs() }.getOrDefault(false)
    if (!usable) return null
    println("[dir] 默认游戏目录 = " + candidate.absolutePath)
    return candidate.absolutePath
}

/**
 * 启动器安装目录；两个来源都拿不到就返回 null。
 *
 * 两处在用：默认游戏目录（[installGameRoot]），以及托管 Java 的落盘位置
 * （[DesktopServices.create] 的 launcherDir）。便携版下它就是解压出来的那个文件夹。
 */
internal fun installDir(): File? {
    System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { path ->
        return runCatching { File(path).absoluteFile.parentFile }.getOrNull()
    }
    val jar = runCatching {
        val cls = Class.forName("io.github.huang404yan.platypus.desktop.MainKt")
        val location = cls.protectionDomain?.codeSource?.location ?: return@runCatching null
        File(location.toURI())
    }.getOrNull() ?: return null
    // <安装目录>\app\<jar>：再往上两级才是安装目录。
    return jar.absoluteFile.parentFile?.parentFile
}

/**
 * 窗口图标按通道取 jar 内的 icon/platypus-adult.png 或 icon/platypus-baby.png。
 *
 * 以前只有一份写死的 icon/platypus.png，而那份是幼体的图 —— 于是正式版窗口、
 * 任务栏、Alt-Tab 里的图标全是幼体，跟安装包图标也不一致。读不到就不带图标，不阻断启动。
 */
private fun loadWindowIcon(): BitmapPainter? {
    val name = if (Brand.isPreview) "icon/platypus-baby.png" else "icon/platypus-adult.png"
    val loader = Thread.currentThread().contextClassLoader
    val bytes = loader?.getResourceAsStream(name)?.use { it.readBytes() }
        ?: loader?.getResourceAsStream("icon/platypus-adult.png")?.use { it.readBytes() }
        ?: return null
    return runCatching { BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap()) }.getOrNull()
}
