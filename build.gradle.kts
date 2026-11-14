import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
}

allprojects {
    group = "io.github.huang404yan.platypus"
    version = "0.8.0-preview.10"
}

// compose 插件在 Windows 上会注册 downloadWix（去 GitHub 下 WiX，只给 Msi 用）。
// 打 Msi 之外的产物时用 -PskipWix 跳过，避免没有外网的机器卡在这里。
if (project.hasProperty("skipWix")) {
    tasks.matching { it.name.contains("Wix") }.configureEach { enabled = false }

    // 关掉自动下载后，compose 插件打 Msi 仍会固定去 <root>/build/wix311 找 candle.exe / light.exe。
    // 这里把本机已经装好的 WiX 复制进去，让 -PskipWix 也能出 msi，且 clean 之后能自动重建：
    //   1) 显式 -PwixDir=<dir>（给 wix314 这种整包目录或它的 bin 都可以）
    //   2) 环境变量 PLATYPUS_WIX_DIR
    //   3) PATH 里能找到 candle.exe 时，跟着它找回去
    // 只复制、不安装、不联网；目标在 build/ 下（已 gitignore），WiX 本体永远不进仓库。
    val wixTarget = layout.buildDirectory.dir("wix311").get().asFile
    wixTarget.mkdirs()

    fun resolveWixSource(): File? {
        val explicit = (project.findProperty("wixDir") as String?)
            ?: System.getenv("PLATYPUS_WIX_DIR")
        if (!explicit.isNullOrBlank()) {
            val dir = file(explicit)
            // 允许直接指 wix314 整包：真正放 candle.exe 的是它下面的 bin/。
            val actual = if (File(dir, "candle.exe").isFile) dir else File(dir, "bin")
            if (File(actual, "candle.exe").isFile) return actual
            logger.warn("[wix] 指定的目录里没有 candle.exe：{}", actual)
            return null
        }
        return System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .map { File(it, "candle.exe") }
            .firstOrNull { it.isFile }
            ?.parentFile
    }

    val wixSource = resolveWixSource()
    val wixReady = File(wixTarget, "candle.exe").isFile && File(wixTarget, "light.exe").isFile
    when {
        // 已经手工铺好（或上一次已复制）就以现有为准，避免每次 clean 后重复复制 117M。
        wixReady -> logger.lifecycle("[wix] 复用已有 {}（candle.exe / light.exe 就位）", wixTarget.absolutePath)
        wixSource != null -> {
            logger.lifecycle("[wix] 从 {} 复制 WiX 到 {}", wixSource.absolutePath, wixTarget.absolutePath)
            wixSource.copyRecursively(wixTarget, overwrite = true)
            logger.lifecycle("[wix] 复制完成：candle.exe={}", File(wixTarget, "candle.exe").isFile)
        }
        else ->
            logger.lifecycle(
                "[wix] 未找到可用的 WiX：需要 msi 时传 -PwixDir=<dir> 或设环境变量 PLATYPUS_WIX_DIR；" +
                    "只用 createDistributable 出 app-image 时不需要 WiX",
            )
    }
}

// 统一字节码目标为 17：Android 端（D8）对 17 支持最稳，桌面 JVM 25 可直接运行 17 字节码。
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }
}
