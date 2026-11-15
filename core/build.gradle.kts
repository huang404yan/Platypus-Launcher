plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// 版本号只有一个真源：根 build.gradle.kts 的 version。
// 这里生成 BuildInfo.kt，界面上的版本号就不会和构建版本漂移。
val buildInfoDir = layout.buildDirectory.dir("generated/platypus/kotlin")

// 微软应用 ID（公共客户端）**不进仓库**：源码里恒为空串，打包的人在自己的机器上
// 用 -Pplatypus.msClientId=… 或环境变量 PLATYPUS_MS_CLIENT_ID 传进来，只写进构建产物。
// 正式版靠这一条把 ID 带进包里，下载的人不用做任何事；孵化版不传，包里的就是空串，
// 用户想用正版账号就在设置里填自己注册的那个（见 core/auth/ClientIds.kt）。
// 这与 PCL / PCL-CE / HMCL 的做法一致：公共 ID 随包发出去等于让所有下载者共用一份
// 别人名下的应用注册，被限流、被收紧都不是拿到包的人能处理的。
val msClientIdValue = providers.gradleProperty("platypus.msClientId")
    .orElse(providers.environmentVariable("PLATYPUS_MS_CLIENT_ID"))
    .getOrElse("")
    .trim()

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val versionValue = project.version.toString()
    val groupValue = project.group.toString()
    val clientIdValue = msClientIdValue
    inputs.property("version", versionValue)
    inputs.property("group", groupValue)
    inputs.property("msClientId", clientIdValue)
    outputs.dir(buildInfoDir)
    doLast {
        val file = buildInfoDir.get().file("io/github/huang404yan/platypus/core/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.github.huang404yan.platypus.core

            /** 由 Gradle 生成，请勿手改；改版本只改根 build.gradle.kts 的 version。 */
            object BuildInfo {
                const val VERSION: String = "$versionValue"
                const val GROUP: String = "$groupValue"

                /** 这个包自带的微软应用 ID；空串表示没带（孵化版就是空串）。 */
                const val MS_CLIENT_ID: String = "$clientIdValue"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // 安卓端的 Java 运行时是 tar.xz：xz 解压用这个纯 Java 实现，
    // 自己写 LZMA2 解码不值得（几百行还得自己测）。tar 那层是自己解析的，见 TarXz。
    api(libs.tukaani.xz)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}
