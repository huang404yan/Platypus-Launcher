package io.github.huang404yan.platypus.core.instance

import io.github.huang404yan.platypus.core.launch.ResolvedVersion
import io.github.huang404yan.platypus.core.launch.VersionResolver
import java.io.File

/**
 * 实例目录里某一类文件占了多少地方。
 *
 * 只统计一层目录、不跟着符号链接跳出去：实例目录里放的是存档和模组，
 * 不该因为某个 junction 把整块硬盘扫一遍。
 */
data class DirUsage(
    val label: String,
    val dir: File,
    val exists: Boolean,
    val fileCount: Int = 0,
    val bytes: Long = 0L,
)

/**
 * 实例详情页要显示的一切，全部在这里算好。
 *
 * 为什么放 core 不放界面：这些都是「读磁盘算什么」的纯逻辑，界面上只做排版；
 * 放 core 才有单测能盯着（顺带让安卓端以后也能直接用）。
 */
data class InstanceDetails(
    val instance: Instance,
    val layout: InstanceLayout,
    /** mods / saves / config / logs 这几个按实例分开的目录。 */
    val dirs: List<DirUsage> = emptyList(),
    val versionJsonExists: Boolean = false,
    val clientJarExists: Boolean = false,
    val clientJarBytes: Long = 0L,
    /** 合并继承链之后的 mainClass，空串表示 json 里没写。 */
    val mainClass: String = "",
    val inheritsFrom: String? = null,
    /** 版本 json 声明的 Java 大版本；null 表示 json 里没写。 */
    val requiredJavaMajor: Int? = null,
    val javaComponent: String? = null,
    val libraryCount: Int = 0,
    /** 解析过程中的问题（缺父版本、找不到 client jar 等），原样给界面显示。 */
    val warnings: List<String> = emptyList(),
) {
    val versionDir: File get() = layout.versionDir
    val gameRoot: File get() = layout.gameRoot

    /** 实例自己的目录总共占了多少。 */
    val totalBytes: Long get() = dirs.sumOf { it.bytes }
}

/**
 * 读一个实例的详情。
 *
 * 全程不抛异常：版本 json 坏了、目录没了，都变成 [InstanceDetails.warnings]
 * 里的一句话 —— 详情页正是那种「东西坏了才最需要打开看」的页面。
 */
object InstanceDetailsLoader {
    /** 单个目录最多数这么多个文件，防止有人把整个硬盘 junction 进 saves/。 */
    const val MAX_FILES_PER_DIR = 20_000

    private val DIR_LABELS = listOf(
        "mods" to "模组",
        "saves" to "存档",
        "config" to "配置",
        "logs" to "日志",
        "crash-reports" to "崩溃报告",
        "resourcepacks" to "资源包",
        "shaderpacks" to "光影",
        "screenshots" to "截图",
    )

    fun load(instance: Instance): InstanceDetails {
        val versionDir = File(instance.directory.trim().trim('"'))
        val layout = InstanceLayouts.of(versionDir)
        val warnings = mutableListOf<String>()

        val jsonFile = VersionResolver.jsonFile(layout.gameRoot, layout.versionDir.name)
        val versionJsonExists = jsonFile.isFile
        var resolved: ResolvedVersion? = null
        if (versionJsonExists) {
            resolved = runCatching { VersionResolver.load(layout.gameRoot, layout.versionDir.name) }
                .onFailure { warnings += "读版本 json 失败：" + (it.message ?: it.toString()) }
                .getOrNull()
            if (resolved == null) warnings += "版本 " + layout.versionDir.name + " 解析不出来（可能是 json 格式不对）"
            else warnings += resolved.warnings
        } else {
            warnings += "找不到版本文件：" + jsonFile.absolutePath
        }

        val version = resolved?.version
        val jar = resolved?.clientJar ?: VersionResolver.jarFile(layout.gameRoot, layout.versionDir.name)
        val jarExists = jar.isFile && jar.length() > 0

        return InstanceDetails(
            instance = instance,
            layout = layout,
            dirs = DIR_LABELS.map { (name, label) -> usage(label, File(layout.gameDir, name)) },
            versionJsonExists = versionJsonExists,
            clientJarExists = jarExists,
            clientJarBytes = if (jarExists) jar.length() else 0L,
            mainClass = version?.mainClass.orEmpty(),
            inheritsFrom = version?.inheritsFrom,
            requiredJavaMajor = version?.javaVersion?.majorVersion?.takeIf { it > 0 },
            javaComponent = version?.javaVersion?.component?.takeIf { it.isNotBlank() },
            libraryCount = version?.libraries?.size ?: 0,
            warnings = warnings.distinct(),
        )
    }

    private fun usage(label: String, dir: File): DirUsage {
        if (!dir.isDirectory) return DirUsage(label = label, dir = dir, exists = false)
        var count = 0
        var bytes = 0L
        runCatching {
            dir.walkTopDown()
                .onEnter { it == dir || !it.name.startsWith(".") }
                .forEach { file ->
                    if (count >= MAX_FILES_PER_DIR) return@forEach
                    if (file.isFile) {
                        count++
                        bytes += runCatching { file.length() }.getOrDefault(0L)
                    }
                }
        }
        return DirUsage(label = label, dir = dir, exists = true, fileCount = count, bytes = bytes)
    }
}
