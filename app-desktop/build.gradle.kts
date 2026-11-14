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

    // 深色标题栏：Windows 只给了 DwmSetWindowAttribute 这个口子，JDK 没有对等 API。
    // 只在 app-desktop 里用，不进 core / ui（那两块不该知道宿主是什么系统）。
    implementation(libs.jna)

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
// 开发期 Fabric 一键装验收（需要联网）
//
// 与 e2eInstall 走同一条 OneClickInstaller.install，只是额外给了 LoaderChoice.Fabric，
// 也就是 DownloadsPage 勾上「顺带装 Fabric」之后执行的那条路：原版文件 → Fabric 库 →
// 补 Java → 登记实例 → 启动进主菜单。日志落在 dist/_e2e-logs/fabric-*.log。
//
//   gradle :app-desktop:fabricE2e -PfabricDir=dist/_fabric-e2e [-Pgv=1.20.1]
//          [-PfabricLoader=0.16.14] [-PskipOrig=true] [-PinstallMod=sodium]
//
// skipOrig=true 时假定该目录里原版已经装好，只走 FabricInstaller 那一段。
// installMod 给了就从 Modrinth 真装一个模组进实例目录的 mods/ 再启动，
// 用来验「装 Fabric → 装模组 → 一起启动」这条链路（缺省不装）。
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("fabricE2e") {
    group = "verification"
    description = "无头验收：干净目录装 1.20.1 + Fabric 并启动进主菜单（开发期用，需联网）"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.FabricE2eKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("fabricDir").orNull
                ?: error("缺少 -PfabricDir=<游戏目录>（建议 dist/_fabric-e2e）")
            listOf(
                rootProject.file(dir).absolutePath,
                providers.gradleProperty("gv").orElse("1.20.1").get(),
                providers.gradleProperty("fabricLoader").orElse("").get(),
                providers.gradleProperty("e2eUser").orElse("Dev").get(),
                providers.gradleProperty("e2eTimeoutMs").orElse("300000").get(),
                providers.gradleProperty("skipOrig").orElse("false").get(),
                providers.gradleProperty("installMod").orElse("").get(),
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 开发期整合包导入验收（需要联网）
//
// 现场按 mrpack 规范造一个真包（含两层 overrides），走 ModpackImporter.import：
// 解析 → 装原版 + Fabric（复用 OneClickInstaller）→ 下包内文件 → 解 overrides →
// 登记实例 → 启动进主菜单。包内模组指向 Modrinth 上的真文件，sha1 真的会校验。
//
//   gradle :app-desktop:modpackE2e -PmodpackDir=dist/_modpack-e2e
//
// 退出码：0 通过 / 2 解析 / 3 导入 / 4 产物校验 / 5 没进主菜单。
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("modpackE2e") {
    group = "verification"
    description = "无头验收：造 mrpack → 导入 → 启动进主菜单（开发期用，需联网）"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.ModpackE2eKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("modpackDir").orElse("dist/_modpack-e2e").get()
            val packDir = providers.gradleProperty("modpackPackDir").orElse("dist/_modpack-e2e-packs").get()
            val timeout = providers.gradleProperty("e2eTimeoutMs").orElse("300000").get()
            listOf(
                rootProject.file(dir).absolutePath,
                rootProject.file(packDir).absolutePath,
                timeout,
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 开发期 Forge 一键装验收（需要联网）
//
// 与 fabricE2e 同形，只是把 LoaderChoice.Fabric 换成 LoaderChoice.Forge：
// 原版文件 → 补 Java → 跑官方 installer（约 3 分钟）→ 登记实例 → 启动进主菜单。
// 日志落在 dist/_e2e-logs/forge-*.log，installer 自己的日志在 <游戏目录>/forge-installers/。
//
//   gradle :app-desktop:forgeE2e -PforgeDir=dist/_forge-e2e [-Pgv=1.20.1]
//          [-PforgeVersion=47.4.0] [-PskipOrig=true]
//
// skipOrig=true 时假定该目录里原版已经装好，只走 ForgeInstaller 那一段。
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("forgeE2e") {
    group = "verification"
    description = "无头验收：干净目录装 1.20.1 + Forge 并启动进主菜单（开发期用，需联网）"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.ForgeE2eKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("forgeDir").orNull
                ?: error("缺少 -PforgeDir=<游戏目录>（建议 dist/_forge-e2e）")
            listOf(
                rootProject.file(dir).absolutePath,
                providers.gradleProperty("gv").orElse("1.20.1").get(),
                providers.gradleProperty("forgeVersion").orElse("").get(),
                providers.gradleProperty("e2eUser").orElse("Dev").get(),
                providers.gradleProperty("e2eTimeoutMs").orElse("300000").get(),
                providers.gradleProperty("skipOrig").orElse("false").get(),
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 开发期 NeoForge 验收（需要联网，安装器来自 BMCLAPI 镜像）
//
//   gradle :app-desktop:neoForgeE2e -PnfDir=dist/_neoforge-e2e [-Pgv=1.21.1]
//          [-PneoforgeVersion=21.1.265] [-PskipOrig=true]
//
// skipOrig=true 时假定该目录里原版已经装好，只走 NeoForgeInstaller 那一段。
// 日志落在 dist/_e2e-logs/neoforge-e2e-*.log（安装器自己的日志在 <游戏目录>/neoforge-installers/）。
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("neoForgeE2e") {
    group = "verification"
    description = "无头验收：干净目录装 1.21.1 + NeoForge 并启动进主菜单（开发期用，需联网）"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.NeoForgeE2eKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("nfDir").orNull
                ?: error("缺少 -PnfDir=<游戏目录>（建议 dist/_neoforge-e2e）")
            listOf(
                rootProject.file(dir).absolutePath,
                providers.gradleProperty("gv").orElse("1.21.1").get(),
                providers.gradleProperty("neoforgeVersion").orElse("").get(),
                providers.gradleProperty("e2eUser").orElse("Dev").get(),
                providers.gradleProperty("e2eTimeoutMs").orElse("300000").get(),
                providers.gradleProperty("skipOrig").orElse("false").get(),
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 开发期 模组 验收（需要联网，走 Modrinth 公共 API）
//
//   gradle :app-desktop:modsE2e -PmodsDir=dist/_mods-e2e [-PmodQuery=sodium]
//          [-Pgv=1.20.1] [-PmodsLoader=fabric] [-Pe2eTimeoutMs=60000]
//
// 真下一个模组进 <游戏目录>/mods/，再用 ModScanner 扫一遍确认元数据读得出来。
// 日志落在 dist/_e2e-logs/mods-e2e-*.log。
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("modsE2e") {
    group = "verification"
    description = "无头验收：从 Modrinth 下一个模组进 mods/ 并扫描（开发期用，需联网）"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.ModsE2eKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("modsDir").orNull
                ?: error("缺少 -PmodsDir=<游戏目录>（建议 dist/_mods-e2e）")
            listOf(
                rootProject.file(dir).absolutePath,
                providers.gradleProperty("modQuery").orElse("sodium").get(),
                providers.gradleProperty("gv").orElse("1.20.1").get(),
                providers.gradleProperty("modsLoader").orElse("fabric").get(),
                providers.gradleProperty("e2eTimeoutMs").orElse("60000").get(),
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 开发期 模组管理验收（P2，需要联网）：启用 / 停用 / 检查更新 / 删除 一圈真跑
//
//   gradle :app-desktop:modUpdateE2e -PupdDir=dist/_test-mc/_nf1211/versions/neoforge-21.1.265
//          [-Pgv=1.21.1] [-PupdLoader=neoforge] [-PupdMod=iris]
//
// 默认装 iris：它有硬前置 Sodium，能顺手验证「缺前置」这条链路。
// 跑完会把碰过的文件清干净，目录恢复原样。日志落在 dist/_e2e-logs/mod-update-e2e-*.log。
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("modUpdateE2e") {
    group = "verification"
    description = "无头验收：模组启用/停用/检查更新/删除（开发期用，需联网）"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.ModUpdateE2eKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("updDir").orNull
                ?: error("缺少 -PupdDir=<实例目录>（建议 dist/_test-mc/_nf1211/versions/neoforge-21.1.265）")
            listOf(
                rootProject.file(dir).absolutePath,
                providers.gradleProperty("gv").orElse("1.21.1").get(),
                providers.gradleProperty("updLoader").orElse("neoforge").get(),
                providers.gradleProperty("updMod").orElse("iris").get(),
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 实例隔离验收（P1）：证明游戏把存档 / 模组 / 日志写进实例自己的目录
//
//   gradle :app-desktop:isolationSmoke -PisoDir=dist/_test-mc/_nf1211 [-PisoInstance=neoforge-21.1.265]
//          [-PisoUser=Dev] [-Pe2eTimeoutMs=300000]
//
// 只启动、不装东西，所以能在已经装好的目录上反复跑。断言全在磁盘上：
// 实例目录有 logs/latest.log，共享 <游戏目录>/logs/latest.log 的修改时间没变。
// ─────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("isolationSmoke") {
    group = "verification"
    description = "无头验收：启动实例，证明存档模组日志落在实例自己的目录里"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("io.github.huang404yan.platypus.desktop.InstanceIsolationSmokeKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")

    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("isoDir").orNull
                ?: error("缺少 -PisoDir=<游戏目录>（建议用装好的 dist/_test-mc/_nf1211）")
            listOf(
                rootProject.file(dir).absolutePath,
                providers.gradleProperty("isoInstance").orElse("").get(),
                providers.gradleProperty("isoUser").orElse("Dev").get(),
                providers.gradleProperty("e2eTimeoutMs").orElse("300000").get(),
            )
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
