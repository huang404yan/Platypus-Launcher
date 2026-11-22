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

// `gradlew :ui:scrollProbe`                      —— 自造 60 个模组当负载，跑完删掉
// `gradlew :ui:scrollProbe -PscrollDir=<实例目录>` —— 用真目录量真实解码开销
//
// 滚动负载探针：在无窗口环境里把一屏几十个模组滚一遍，逐帧计耗时。用来回答
// 「滚动时主线程有没有被图标解码之类的事占住」。开发期用，不进发行包。
// 放在 ui 模块内，是因为要量的 [ComponentRow] 是 internal，跨模块够不着。
//
// 不传 -PscrollDir 时自己造负载并断言阈值（跑完打印 PASS/FAIL 与退出码），
// 所以它可以当回归用：改过列表 / 图标相关的代码后跑一次，红了就是结构退化了。
// 想量真数字就指一个真实例目录，那时候阈值仍会打印但只作参考。
// ─────────────────────────────────────────────────────────────────────────────
val scrollProbeCompilation = kotlin.jvm().compilations.getByName("test")

tasks.register<JavaExec>("scrollProbe") {
    group = "verification"
    description = "无头滚动探针：ImageComposeScene 渲染模组列表并滚动，统计每帧耗时并断言阈值（开发期用）"
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
            // 不传就一个参数都不给，探针自己造负载 —— 常规回归不该依赖本机某份素材。
            val dir = providers.gradleProperty("scrollDir").orNull
            if (dir.isNullOrBlank()) emptyList() else listOf(rootProject.file(dir).absolutePath)
        },
    )
}

// 「改完界面该跑的」一个口子：单测 + 滚动探针。故意不挂进 check —— 探针要起
// ImageComposeScene，比单测重，挂上去会让每次构建都多等十几秒，最后大家学会绕过它。
tasks.register("verifyUi") {
    group = "verification"
    description = "界面相关的常规验证：:ui:jvmTest + :ui:scrollProbe"
    dependsOn(tasks.named("jvmTest"), tasks.named("scrollProbe"))
}

