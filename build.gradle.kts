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
    group = "io.github.huang0yan4.platypus"
    version = "0.8.0-preview.1"
}

// 统一字节码目标为 17：Android 端（D8）对 17 支持最稳，桌面 JVM 25 可直接运行 17 字节码。
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }
}
