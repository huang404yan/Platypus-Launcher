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
}

compose.desktop {
    application {
        mainClass = "io.github.huang0yan4.platypus.desktop.MainKt"

        // 原生包用 jpackage 打，图标与应用名跟孵化版对齐。
        // 产物在 app-desktop/build/compose/binaries/main/ 下。
        nativeDistributions {
            // Msi 需要 WiX（root 工程的 downloadWix 会去 GitHub 拉），
            // 连不上 GitHub 时用 -PskipWix 跳过 WiX 下载。
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "Platypus Preview"
            packageVersion = project.version.toString().substringBefore("-")
            description = "非官方 Minecraft 启动器，与 Mojang Studios 及 Microsoft 无关联。"
            vendor = "Platypus Launcher"

            windows {
                iconFile.set(project.file("src/main/resources/icon/platypus.ico"))
                menuGroup = "Platypus Launcher"
                shortcut = true
                // 固定装到程序目录，避免默认装进 LocalAppData 让用户找不到。
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6d1f3c52-8a4e-4b57-9c1d-2f0a7b6e5d84"
            }

            linux {
                iconFile.set(project.file("src/main/resources/icon/platypus.png"))
                menuGroup = "Platypus Launcher"
                appCategory = "Game"
                packageName = "platypus-preview"
            }

        }
    }
}