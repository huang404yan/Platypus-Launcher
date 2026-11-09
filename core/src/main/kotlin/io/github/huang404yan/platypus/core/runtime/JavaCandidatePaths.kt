package io.github.huang404yan.platypus.core.runtime

import java.io.File

/**
 * 一次 Java 探测要看的环境事实。
 *
 * 全部由调用方填：桌面侧读环境变量 / 注册表 / 目录，测试造假目录。
 * core 自己不读环境变量、不启动进程（D5：core 不直接依赖 Process）。
 */
data class JavaSearchEnv(
    /** JAVA_HOME 指向的 JDK 根目录。 */
    val javaHome: String? = null,
    /** Program Files / Program Files (x86) 这类放 JDK 的根目录。 */
    val programFilesDirs: List<String> = emptyList(),
    /** 注册表里登记的 JavaHome（桌面侧 reg query 得到，每项是 JDK 根目录）。 */
    val registryJavaHomes: List<String> = emptyList(),
    /** 启动器自带运行时的目录（.minecraft/runtime 等），会在里面递归找 bin/java。 */
    val launcherRuntimeDirs: List<String> = emptyList(),
    /** PATH 条目。 */
    val pathDirs: List<String> = emptyList(),
    /** 用户在界面上手动指定的 java（可执行文件或 JDK 根目录）。 */
    val extraJavas: List<String> = emptyList(),
)

/**
 * 候选 java 可执行文件枚举：纯字符串 + 目录枚举，不跑任何命令。
 *
 * 顺序即优先级：手动指定 > JAVA_HOME > 注册表 > 厂商安装目录 > 启动器自带运行时 > PATH。
 * 这里只负责「去哪儿找」，能不能跑由调用方逐个试跑决定，坏路径直接跳过。
 */
object JavaCandidatePaths {

    /** 常见厂商的安装目录名（注册表里的 JavaHome 也基本落在这些目录下）。 */
    val VENDOR_DIRS: List<String> = listOf("Java", "Eclipse Adoptium", "Zulu", "Microsoft")

    /** 直接装在 Program Files 根下的 JDK 目录名特征：jdk-17.0.10、jre1.8.0_401、openjdk-21 等。 */
    private val JdkDirName = Regex("^(jdk|jre|openjdk|graalvm|zulu|corretto|temurin|microsoft-jdk)[-_0-9.].*", RegexOption.IGNORE_CASE)

    private const val WINDOWS_JAVA = "java.exe"
    private const val UNIX_JAVA = "java"

    fun javaExecutableName(windows: Boolean = true): String = if (windows) WINDOWS_JAVA else UNIX_JAVA

    /**
     * 目录 → 里面 java 可执行文件的路径。
     * 传入的已经是 java.exe / java，或已经指向 .../bin 时也能给出正确结果；空串返回 null。
     */
    fun javaExecutableIn(dirOrExe: String, windows: Boolean = true): String? {
        val raw = dirOrExe.trim().trim('"')
        if (raw.isEmpty()) return null
        val path = raw.replace('\\', '/').trimEnd('/')
        if (path.isEmpty()) return null
        val name = javaExecutableName(windows)
        val last = path.substringAfterLast('/')
        if (last.equals(name, ignoreCase = true) || (last.equals(UNIX_JAVA, ignoreCase = true) && !windows)) return path
        if (last.equals("bin", ignoreCase = true)) return path + "/" + name
        return path + "/bin/" + name
    }

    /**
     * 按优先级列出候选 java 可执行文件路径（已去重，顺序稳定）。
     *
     * @param maxScanDepth 在启动器运行时目录里递归找 bin/java 的最大深度（含 root 本身为 0）
     */
    fun candidates(
        env: JavaSearchEnv,
        windows: Boolean = true,
        maxScanDepth: Int = 4,
    ): List<String> {
        val ordered = LinkedHashMap<String, String>()
        fun add(path: String?) {
            val value = path?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return
            val key = normalizeKey(value)
            if (!ordered.containsKey(key)) ordered[key] = value
        }

        env.extraJavas.forEach { add(javaExecutableIn(it, windows)) }
        add(env.javaHome?.let { javaExecutableIn(it, windows) })
        env.registryJavaHomes.forEach { add(javaExecutableIn(it, windows)) }

        for (root in env.programFilesDirs) {
            if (root.isBlank()) continue
            for (vendor in VENDOR_DIRS) {
                val vendorDir = join(root, vendor)
                add(javaExecutableIn(vendorDir, windows))
                for (child in childDirs(vendorDir)) add(javaExecutableIn(child, windows))
            }
            // 有的安装器直接把 JDK 目录铺在 Program Files 下
            for (child in childDirs(root)) {
                if (JdkDirName.matches(childName(child))) add(javaExecutableIn(child, windows))
            }
        }

        for (root in env.launcherRuntimeDirs) {
            for (home in findJavaHomes(root, maxScanDepth)) add(javaExecutableIn(home, windows))
        }

        for (dir in env.pathDirs) {
            val entry = dir.trim().trim('"')
            if (entry.isEmpty()) continue
            // PATH 里的条目可能是 java 所在的目录（有的安装器不放 bin 子目录），
            // 也可能是 .../bin 或 JDK 根目录：先看目录里有没有直接的 java，没有再按 JDK 形态展开。
            val direct = join(entry, javaExecutableName(windows))
            if (File(direct).isFile) add(direct) else add(javaExecutableIn(entry, windows))
        }

        return ordered.values.toList()
    }

    /** 在 root 下递归找「含 bin/java(.exe) 的目录」，返回这些目录（即 java.home 形态）。 */
    fun findJavaHomes(root: String, maxScanDepth: Int = 4): List<String> {
        val start = File(root)
        if (!start.isDirectory) return emptyList()
        val exeName = javaExecutableName(true)
        val altName = UNIX_JAVA
        val homes = mutableListOf<String>()
        var level = listOf(start)
        var depth = 0
        while (level.isNotEmpty() && depth <= maxScanDepth) {
            val next = mutableListOf<File>()
            for (dir in level) {
                val bin = File(dir, "bin")
                if (File(bin, exeName).isFile || File(bin, altName).isFile) {
                    if (homes.none { normalizeKey(it) == normalizeKey(dir.path) }) homes += dir.path
                    // 命中的目录不再往下钻：JDK 里的 bin 目录不值得再扫
                    continue
                }
                next += childDirFiles(dir)
            }
            level = next
            depth++
        }
        return homes.sorted()
    }

    private fun childDirs(dir: String): List<String> {
        val f = File(dir)
        if (!f.isDirectory) return emptyList()
        return childDirFiles(f).map { it.path }
    }

    private fun childDirFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() }.orEmpty()

    private fun childName(path: String): String = path.replace('\\', '/').trimEnd('/').substringAfterLast('/')

    private fun join(root: String, name: String): String =
        root.trim().trim('"').replace('\\', '/').trimEnd('/') + "/" + name

    /** 去重键：Windows 路径不区分大小写，分隔符统一成 /。 */
    fun normalizeKey(path: String): String = path.trim().trim('"').replace('\\', '/').lowercase()
}
