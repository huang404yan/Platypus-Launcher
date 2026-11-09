package io.github.huang404yan.platypus.core.launch

import java.io.File
import java.util.zip.ZipInputStream

/** 解压 natives 的结果：成功写出的文件数、处理过的 jar、以及不影响启动的告警。 */
data class NativesResult(
    val targetDir: File,
    val extracted: Int,
    val archives: Int,
    val warnings: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = extracted == 0
}

/**
 * 把老版本 json 里由 natives 字段指定的 jar 解压成 dll/so/dylib 目录。
 *
 * 放在 core 里是因为它只用 java.util.zip（Android 也有），不碰 Process；
 * Java 8 时期的版本靠 -Djava.library.path 找这些库，1.19 之后已经不需要了。
 *
 * 规则照抄 Mojang：
 * - 跳过目录条目与 [DEFAULT_EXCLUDE]（META-INF/ 里的签名文件会把 JVM 弄崩）；
 * - 再跳过每个 library 自己的 extract.exclude；
 * - 同名文件后解压的覆盖先解压的（LWJGL 各平台 jar 里的同名 dll 就是靠这个合并）。
 */
object NativesExtractor {

    /** Mojang 的默认排除项：解压出来的 META-INF 会破坏签名校验。 */
    val DEFAULT_EXCLUDE: List<String> = listOf("META-INF/")

    /** 条目名归一化：反斜杠转斜杠、去掉开头的 ./ 与 /。 */
    fun normalizeEntryName(name: String): String = name
        .replace('\\', '/')
        .trim()
        .removePrefix("./")
        .trimStart('/')

    /**
     * 该条目是否要跳过。
     *
     * 前缀匹配即可：Mojang 的 exclude 写的就是 "META-INF/"，语义是「以它开头的都跳过」。
     */
    fun shouldSkip(entryName: String, exclude: List<String>): Boolean {
        if (entryName.isEmpty() || entryName.endsWith("/")) return true
        val normalized = normalizeEntryName(entryName)
        return exclude.any { prefix ->
            val p = normalizeEntryName(prefix)
            p.isNotEmpty() && normalized.startsWith(p, ignoreCase = true)
        }
    }

    /**
     * 解压 [archives] 到 [targetDir]。
     *
     * 任何单个 jar 的失败都只记一条告警并继续——少一个平台的 dll 不该让整个启动失败，
     * 真正影响启动时 JVM 会自己报 UnsatisfiedLinkError，比这里抛异常更好排查。
     */
    fun extract(archives: List<NativeArchive>, targetDir: File): NativesResult {
        val warnings = mutableListOf<String>()
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            warnings += "无法创建 natives 目录：" + targetDir.path
            return NativesResult(targetDir, 0, 0, warnings)
        }
        var extracted = 0
        var handled = 0
        for (archive in archives) {
            if (!archive.jar.isFile) {
                warnings += "natives jar 不存在，已跳过：" + archive.jar.path
                continue
            }
            handled++
            try {
                ZipInputStream(archive.jar.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = normalizeEntryName(entry.name)
                        val exclude = DEFAULT_EXCLUDE + archive.exclude
                        if (shouldSkip(name, exclude)) {
                            zip.closeEntry()
                            continue
                        }
                        val target = File(targetDir, name)
                        // 防御 zip-slip：解压路径必须仍在目标目录内。
                        if (!target.canonicalPath.startsWith(targetDir.canonicalPath)) {
                            warnings += "跳过越界条目：" + entry.name
                            zip.closeEntry()
                            continue
                        }
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out -> zip.copyTo(out) }
                        extracted++
                        zip.closeEntry()
                    }
                }
            } catch (t: Throwable) {
                warnings += "解压失败：" + archive.jar.name + "（" + t.message + "）"
            }
        }
        return NativesResult(targetDir, extracted, handled, warnings)
    }

    /** 目录里是否已经有解压好的原生库（复用 PCL/HMCL 预先解压的 -natives 目录）。 */
    fun looksPopulated(dir: File): Boolean {
        val files = dir.listFiles() ?: return false
        return files.any { it.isFile }
    }
}
