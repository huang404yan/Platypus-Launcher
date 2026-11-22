plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    // 包名一经发布不可改。两条线各用独立包名：
    //   正式版 io.github.huang404yan.platypus.launcher（1.0.0 起；现在还没切过去）
    //   孵化版 io.github.huang404yan.platypus.launcher.preview（当前构建）
    // 两者是同一台设备上可以并存的两个应用，切正式线时把这里与源码 package 一起去掉 .preview。
    namespace = "io.github.huang404yan.platypus.launcher.preview"
    compileSdk = 37
    // 应用内起 JVM 那一层是 C 写的（见 src/main/cpp/jvm_bridge.c）。
    // 版本写死在这里而不是靠 AGP 猜：不同 NDK 版本对 dlopen 的默认行为不一样，
    // 出问题时要能一眼看出是拿哪一套编的。
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.huang404yan.platypus.launcher.preview"
        minSdk = 26
        targetSdk = 37
        versionCode = 80025
        versionName = "0.8.0-preview.25"

        // 通道由版本号决定：0.x-preview.N 是孵化版，1.0.0 起是正式版。
        // 名字与包名都跟着它走，发正式版时忘了改包名会在这里直接构建失败。
        val isPreview = versionName!!.contains("-preview")
        val expectedId = "io.github.huang404yan.platypus.launcher" + if (isPreview) ".preview" else ""
        check(applicationId == expectedId) {
            "包名与版本通道不符：versionName=$versionName 应当用 $expectedId，当前是 $applicationId"
        }
        check(namespace == applicationId) {
            "namespace 必须与 applicationId 一致（源码 package 声明要跟着改），当前 namespace=$namespace"
        }
        resValue("string", "app_name", if (isPreview) "鸭嘴兽 · 孵化版" else "鸭嘴兽启动器")
    }

    buildFeatures {
        compose = true
        // AGP 9 默认关闭自定义资源值，resValue() 要靠这个开关才生效。
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 用最朴素的 ndk-build：这一层只有一个 .c 文件、零第三方依赖，
    // 为它再装一套 CMake 不划算。Android.mk / Application.mk 就在源码目录里。
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
