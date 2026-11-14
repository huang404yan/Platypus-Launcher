plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            api(project(":core"))
        }
        // 界面里的进程级 runner 与格式化函数是普通 Kotlin 代码，不需要 composable 测试框架。
        // 建这个源集是为了让「切页保活」「进度怎么显示」这类逻辑有单测盯着。
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
