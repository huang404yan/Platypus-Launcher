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
            // 回归测试要在真正的界面线程上验证「发命令不堵界面」，需要 Dispatchers.Swing。
            implementation(libs.kotlinx.coroutines.swing)
            // 滚动负载探针要在无窗口环境里渲染真页面（ImageComposeScene）。
            implementation(compose.desktop.currentOs)
        }
    }
}

// `gradle :ui:scrollProbe [-PscrollDir=dist/_scrollmc/versions/1.20.1-test]`
//
// 滚动负载探针：在无窗口环境里把一屏几十个模组滚一遍，逐帧计耗时。用来回答
// 「滚动时主线程有没有被图标解码之类的事占住」。开发期用，不进发行包。
// 放在 ui 模块内，是因为要量的 [ComponentRow] 是 internal，跨模块够不着。
// ─────────────────────────────────────────────────────────────────────────────
val scrollProbeCompilation = kotlin.jvm().compilations.getByName("test")

tasks.register<JavaExec>("scrollProbe") {
    group = "verification"
    description = "无头滚动探针：ImageComposeScene 渲染模组列表并滚动，统计每帧耗时（开发期用）"
    dependsOn(scrollProbeCompilation.compileTaskProvider)
    mainClass.set("io.github.huang404yan.platypus.ui.ScrollProbeKt")
    classpath = files(
        scrollProbeCompilation.output.classesDirs,
        scrollProbeCompilation.runtimeDependencyFiles,
    )
    workingDir = rootProject.projectDir
    jvmArgs("-Dfile.encoding=UTF-8")
    argumentProviders.add(
        CommandLineArgumentProvider {
            val dir = providers.gradleProperty("scrollDir").orElse("dist/_scrollmc/versions/1.20.1-test").get()
            listOf(rootProject.file(dir).absolutePath)
        },
    )
}
