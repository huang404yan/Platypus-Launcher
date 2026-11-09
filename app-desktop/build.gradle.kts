import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    // 只有开发期无头验收（src/test）用得到，不进发行包。
    testImplementation(project(":ui"))
    testImplementation(project(":core"))
    testImplementation(libs.kotlinx.coroutines.swing)
}

// ─────────────────────────────────────────────────────────────────────────────
// 开发期无头验收
//
// 把「下载 → 补 Java → 登记实例 → 启动进主菜单」整条链路在一个真实目录上跑一遍，
// 调用的就是界面按钮背后那几个函数，只是没有鼠标点击。产物落 dist/_e2e-logs/。
//
//   gradle :app-desktop:e2eInstall -Pe2eDir=dist/_test-mc/_e2e-YYYYMMDD [-Pe2eVersion=1.20.1]
//
// 注意：不要把宿主机 %APPDATA%/.minecraft 里已有的运行时算进来，驱动里已显式关掉
// 全局启动器扫描，否则自动下载那段代码根本不会被走到。
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("e2eInstall") {
    group = "verification"
    description = "无头验收：在干净目录上从零走到主菜单（开发期用）"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.E2eInstallKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("e2eDir").orNull
                ?: error("缺少 -Pe2eDir=<游戏目录>（建议 dist/_test-mc/_e2e-<日期>）")
            listOf(
                rootProject.file(dir).absolutePath,
                providers.gradleProperty("e2eVersion").orElse("1.20.1").get(),
                providers.gradleProperty("e2eUser").orElse("Dev").get(),
                providers.gradleProperty("e2eTimeoutMs").orElse("300000").get(),
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 开发期设置持久化冒烟
//
// 验证「界面保存 → 落盘 → 重新装配读回」这条真实链路，走的是 DesktopServices.create
// 这个唯一装配点。产物只在临时目录里，不碰用户真实的 .minecraft。
//
//   gradle :app-desktop:settingsSmoke -PsmokeDir=dist/_settings-smoke
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("settingsSmoke") {
    group = "verification"
    description = "无头冒烟：设置文件落盘位置与读回（开发期用）"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.SettingsSmokeKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("smokeDir").orElse("dist/_settings-smoke").get()
            listOf(rootProject.file(dir).absolutePath)
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 开发期实例页链路冒烟
//
// 实例页的每个按钮背后只有几步：认养/新建 → 写登记 → 扫描 → 合并 → 改内存 → 移除登记。
// 界面本身跑不了无头，但这几步都在 DesktopServices 装配出来的真实仓库上跑一遍，
// 走的路径与界面完全相同（界面里也是调 backend.instances.save）。
//
//   gradle :app-desktop:instanceFlowSmoke -PinstanceDir=dist/_instance-smoke
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("instanceFlowSmoke") {
    group = "verification"
    description = "无头冒烟：实例认养、登记、合并、改内存、移除（开发期用）"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.InstanceFlowSmokeKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("instanceDir").orElse("dist/_instance-smoke").get()
            // 第二个参数可选：给一个真实的 .minecraft，就在它上面做一次只读认养验证。
            val real = providers.gradleProperty("instanceRealDir").orNull
            listOf(rootProject.file(dir).absolutePath) +
                listOfNotNull(real?.takeIf { it.isNotBlank() }?.let { rootProject.file(it).absolutePath })
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 开发期实例删除冒烟
//
// 实例页的「删除文件…」背后只有 inspect（算计划）与 delete（动盘）两步。这个任务在
// 临时目录里造版本目录，把两条路径都跑一遍：正常删除（存档搬家）、目录不存在、
// 多实例共用拒绝、../ 越界拒绝、连存档一起删、只改名不递归删。
//
//   gradle :app-desktop:deleteSmoke -PdeleteDir=dist/_delete-smoke
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("deleteSmoke") {
    group = "verification"
    description = "无头冒烟：实例删除的 inspect/delete 与三种拒绝（开发期用）"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.InstanceDeleteSmokeKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("deleteDir").orElse("dist/_delete-smoke").get()
            listOf(rootProject.file(dir).absolutePath)
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 开发期真实网络续传冒烟（需要联网）
//
// 单测里的续传跑的是本地 HttpServer，伪造得了 206 / 200 / 416 / 提前 EOF，
// 但证明不了**真实 CDN 真的配合 Range**。这个任务拿 Mojang 官方 CDN 上不可变的
// 文件跑三件事：全新下载对 sha1、预置 10 MB 半成品后只下剩下的、坏半成品自愈。
//
//   gradle :app-desktop:resumeSmoke -PresumeDir=dist/_resume-smoke
//
// 断网时不跑它就行；它不在 test 依赖链上，不会拖慢常规构建。
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("resumeSmoke") {
    group = "verification"
    description = "无头冒烟：真实 CDN 上的断点续传（开发期用，需联网）"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.ResumeSmokeKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("resumeDir").orElse("dist/_resume-smoke").get()
            listOf(rootProject.file(dir).absolutePath)
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 品牌与打包参数
//
// 版本号唯一真源是根 build.gradle.kts 的 allprojects.version；这里只按「版本号里
// 有没有 -preview」判断走孵化线还是正式线，两条线共用同一份构建脚本。
//
// 文案与 core/src/main/kotlin/io/github/huang404yan/platypus/core/Brand.kt 保持一致
// —— 构建脚本读不到 core 的编译产物（BuildInfo 是编译期生成的），改 Brand.kt 的
// 品牌名/标语时请同步改这里，反之亦然。
// ─────────────────────────────────────────────────────────────────────────────

/** 一条产品线的打包参数。两条线必须互不覆盖。 */
data class ChannelBrand(
    val nameEn: String,
    val nameZh: String,
    val slogan: String,
    /** 相对仓库根；branding/ 不入库，缺失时回退到随源码提交的通用图标。 */
    val windowsIcon: String,
    val linuxIcon: String,
    val linuxPackageName: String,
    /** 安装包的升级标识。两条线必须不同，否则跨通道升级会互相覆盖。 */
    val upgradeUuid: String,
)

val previewBrand = ChannelBrand(
    nameEn = "Platypus Preview",
    nameZh = "鸭嘴兽·孵化版",
    slogan = "自然界早就缝合好了。",
    windowsIcon = "branding/windows/platypus-baby.ico",
    linuxIcon = "branding/linux/baby/platypus-baby.png",
    linuxPackageName = "platypus-preview",
    // 孵化线沿用仓库最早的 UUID，保持既有配置不跳变。
    upgradeUuid = "6d1f3c52-8a4e-4b57-9c1d-2f0a7b6e5d84",
)

val stableBrand = ChannelBrand(
    nameEn = "Platypus Launcher",
    nameZh = "鸭嘴兽启动器",
    slogan = "把好东西，缝在一起。",
    windowsIcon = "branding/windows/platypus-adult.ico",
    linuxIcon = "branding/linux/adult/platypus-adult.png",
    linuxPackageName = "platypus",
    // 正式线独立 UUID：与孵化线并存时不互相抢升级。
    upgradeUuid = "5b2e7d10-9c43-4f68-8a1b-3e6d0f94c275",
)

val fullVersion = project.version.toString()
val isPreviewChannel = fullVersion.contains("-preview")
val brand = if (isPreviewChannel) previewBrand else stableBrand

/** 首选图标不存在时回退到通用图标；两者都没有就返回 null，让 jpackage 用默认图标。 */
fun resolveIcon(preferred: String, fallback: String): java.io.File? {
    val candidate = rootProject.file(preferred)
    if (candidate.isFile) return candidate
    val fallbackFile = rootProject.file(fallback)
    if (fallbackFile.isFile) {
        logger.info("[package] 图标 {} 不存在，回退到 {}", preferred, fallback)
        return fallbackFile
    }
    logger.warn("[package] 图标 {} 与 {} 都不存在，jpackage 将使用默认图标", preferred, fallback)
    return null
}

compose.desktop {
    application {
        mainClass = "io.github.huang404yan.platypus.desktop.MainKt"

        // 原生包用 jpackage 打。产物在 app-desktop/build/compose/binaries/main/ 下。
        nativeDistributions {
            // 目标格式跟着构建机走，不能写死 Linux 格式：
            //   Windows → Msi（需要 WiX Toolset；没有 WiX 时用 -PskipWix 跳过下载，
            //             代价是出不了 msi，只能出 createDistributable 的 app-image）
            //   macOS   → Dmg
            //   其它    → Deb / AppImage
            // 在 Windows 上声明 Deb 只会得到「Task not found: packageMsi」之类的空结果。
            val hostOs = System.getProperty("os.name").orEmpty().lowercase()
            val hostTargets = when {
                hostOs.contains("win") -> listOf(TargetFormat.Msi)
                hostOs.contains("mac") -> listOf(TargetFormat.Dmg)
                else -> listOf(TargetFormat.Deb, TargetFormat.AppImage)
            }
            targetFormats(*hostTargets.toTypedArray())

            packageName = brand.nameEn
            // jpackage 只接受纯数字版本（主.次.修订），preview 后缀不能进包版本。
            packageVersion = fullVersion.substringBefore("-")
            description =
                brand.nameZh + "（" + brand.nameEn + "）—— 非官方 Minecraft 启动器，" +
                    "与 Mojang Studios 及 Microsoft 无关联。" + brand.slogan
            vendor = "Platypus Launcher"

            windows {
                resolveIcon(brand.windowsIcon, "app-desktop/src/main/resources/icon/platypus.ico")
                    ?.let { iconFile.set(it) }
                menuGroup = brand.nameEn
                shortcut = true
                // 允许用户选安装目录，避免默认装进 LocalAppData 让用户找不到。
                dirChooser = true
                perUserInstall = true
                upgradeUuid = brand.upgradeUuid
            }

            linux {
                resolveIcon(brand.linuxIcon, "app-desktop/src/main/resources/icon/platypus.png")
                    ?.let { iconFile.set(it) }
                menuGroup = brand.nameEn
                appCategory = "Game"
                packageName = brand.linuxPackageName
            }
        }
    }
}
