package io.github.huang404yan.platypus.core.runtime

/**
 * 解析 java -XshowSettings:properties -version / java -version 的文本输出。
 *
 * 纯字符串处理：不启动进程、不读文件，好测也方便桌面侧复用。
 */
object JavaProbeParser {

    /** 形如 "    java.version = 17.0.10" 的属性行。 */
    private val PropertyLine = Regex("""^\s*([A-Za-z0-9_.]+)\s*=\s*(.+?)\s*$""")

    /** 形如 java version "17.0.10" 2024-01-16 或 openjdk version "1.8.0_401"。 */
    private val VersionLine = Regex("""^\s*(\S*)\s*version\s+"([^"]+)"""", RegexOption.IGNORE_CASE)

    /**
     * 从探测输出里还原一个 [JavaRuntime]。
     *
     * 只认 java.version / java.runtime.version / java.specification.version 里能解析出的主版本，
     * 其它信息缺就给 null；连主版本都拿不到时返回 null——调用方据此跳过该候选（坏路径、非 Java 文件等）。
     */
    fun parse(
        output: String,
        path: String,
        managed: Boolean = false,
    ): JavaRuntime? {
        if (output.isBlank()) return null
        val props = properties(output)
        val versionText = props["java.version"]
            ?: props["java.runtime.version"]
            ?: props["java.specification.version"]
            ?: versionLineVersion(output)
            ?: return null
        val major = parseMajor(versionText) ?: return null
        val arch = props["os.arch"]?.takeIf { it.isNotBlank() }
        val bits = bits(props["sun.arch.data.model"], arch)
        return JavaRuntime(
            path = path,
            majorVersion = major,
            vendor = props["java.vendor"]?.takeIf { it.isNotBlank() },
            arch = arch,
            bits = bits,
            managed = managed,
        )
    }

    /** 探测输出里的 java.home（JDK 根目录），用于去重与界面展示。 */
    fun javaHome(output: String): String? =
        properties(output)["java.home"]?.takeIf { it.isNotBlank() }

    /**
     * 版本号文本 → 主版本号。
     *
     * - 25.0.5.0.3 → 25（只取第一段，本机 JDK25 就是四段版本号）
     * - 1.8.0_401 / 1.8 → 8
     * - 带引号的 "17" 也认
     */
    fun parseMajor(version: String): Int? {
        val cleaned = version.trim().trim('"').trim()
        if (cleaned.isEmpty()) return null
        val head = cleaned.substringBefore('+').substringBefore('-')
        val parts = head.split('.').map { it.trim() }
        val first = parts.firstOrNull()?.toIntOrNull() ?: return null
        if (first == 1) {
            return parts.getOrNull(1)?.let { leadingDigits(it) }
        }
        return first.takeIf { it > 0 }
    }

    /** 从 1.8.0_401 这种段里抠出开头的数字。 */
    private fun leadingDigits(segment: String): Int? {
        val sb = StringBuilder()
        for (c in segment) {
            if (c.isDigit()) sb.append(c) else break
        }
        return sb.toString().toIntOrNull()
    }

    private fun properties(output: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in output.lineSequence()) {
            val m = PropertyLine.matchEntire(line) ?: continue
            val key = m.groupValues[1]
            val value = m.groupValues[2]
            if (!map.containsKey(key)) map[key] = value
        }
        return map
    }

    private fun versionLineVersion(output: String): String? {
        for (line in output.lineSequence()) {
            val m = VersionLine.matchEntire(line) ?: continue
            return m.groupValues[2]
        }
        return null
    }

    /** sun.arch.data.model 优先，缺失时按 os.arch 猜。 */
    fun bits(dataModel: String?, arch: String?): Int? {
        when (dataModel?.trim()?.toIntOrNull()) {
            32 -> return 32
            64 -> return 64
        }
        val a = arch?.lowercase() ?: return null
        return when {
            a.contains("64") -> 64
            a == "x86" || a == "i386" || a == "i686" -> 32
            a == "arm" -> 32
            else -> null
        }
    }
}
