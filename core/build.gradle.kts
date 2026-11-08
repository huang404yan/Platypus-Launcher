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

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val versionValue = project.version.toString()
    val groupValue = project.group.toString()
    inputs.property("version", versionValue)
    inputs.property("group", groupValue)
    outputs.dir(buildInfoDir)
    doLast {
        val file = buildInfoDir.get().file("io/github/huang0yan4/platypus/core/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package io.github.huang0yan4.platypus.core

            /** 由 Gradle 生成，请勿手改；改版本只改根 build.gradle.kts 的 version。 */
            object BuildInfo {
                const val VERSION: String = "$versionValue"
                const val GROUP: String = "$groupValue"
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
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}
