package io.github.huang404yan.platypus.core.component

import io.github.huang404yan.platypus.core.mods.ModIcons
import java.io.File
import java.util.zip.ZipFile

/**
 * 组件图标的兜底顺序：模组读 jar 里的 icon.png，光影与资源包读自己带的预览图。
 *
 * 模组那部分直接用 [ModIcons]（它已经处理了 fabric.mod.json 的 icon 字段与四种常见路径），
 * 这里只补它不管的两种：
 *
 * - 资源包把封面放在根目录的 `pack.png`，这是原版自己认的规格；
 * - 光影包没有统一清单，实际生态里叫 `preview.png` / `preview.jpg` / `screenshot.png` 的
 *   都有，取第一张不超过上限的图。
 *
 * 拿不到就返回 null，界面画首字母占位 —— 一张图失败不该让整列清单变成窟窿。
 */
object ComponentIcons {

    /** 单张图的上限，与 [ModIcons.MAX_BYTES] 同一个理由：正常图标几十 KB。 */
    const val MAX_BYTES: Long = 2L * 1024L * 1024L

    /** 资源包封面：原版规格写在根目录。 */
    private val PACK_CANDIDATES = listOf("pack.png", "pack.jpg", "pack.jpeg")

    /** 光影包的预览图，按常见程度排。 */
    private val SHADER_CANDIDATES = listOf(
        "preview.png", "preview.jpg", "preview.jpeg",
        "screenshot.png", "screenshot.jpg",
    )

    /**
     * 取一个已装组件的图标文件（缓存到 [cacheDir]）。
     *
     * @param fileName 磁盘上的文件名，用来算缓存键 —— 停用改过名也算同一个，基名一样。
     */
    fun iconOf(kind: ComponentKind, file: File, fileName: String, cacheDir: File): File? {
        if (kind == ComponentKind.MOD) {
            // 模组名里带着 .disabled 时 ModIcons 照样读得到内容，它只看 zip 里有什么
            return ModIcons.fromJar(file, modId = null, cacheDir = cacheDir)
        }
        if (!file.isFile) return null
        val target = File(cacheDir, cacheKey(file, fileName) + ".img")
        if (target.isFile && target.length() > 0L) return target
        val bytes = runCatching { imageBytesOf(kind, file) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return writeAtomically(target, bytes)
    }

    private fun imageBytesOf(kind: ComponentKind, file: File): ByteArray? {
        val candidates = when (kind) {
            ComponentKind.SHADER -> SHADER_CANDIDATES
            else -> PACK_CANDIDATES
        }
        ZipFile(file).use { zip ->
            for (path in candidates) {
                val entry = zip.getEntry(path) ?: continue
                if (entry.isDirectory || entry.size <= 0L || entry.size > MAX_BYTES) continue
                return zip.getInputStream(entry).use { it.readBytes() }
            }
            // 光影包常把预览图塞进自己的子目录，退一步按后缀找第一张
            if (kind == ComponentKind.SHADER) {
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.size in 1..MAX_BYTES }
                    .filter { entry -> IMAGE_SUFFIXES.any { entry.name.endsWith(it, ignoreCase = true) } }
                    .minByOrNull { it.name.count { ch -> ch == '/' } }
                    ?.let { return zip.getInputStream(it).use { stream -> stream.readBytes() } }
            }
        }
        return null
    }

    private val IMAGE_SUFFIXES = listOf(".png", ".jpg", ".jpeg", ".webp")

    /**
     * 缓存键：文件名 + 大小 + 修改时间。
     *
     * 不用 [ModIcons] 那套 sha1 是因为没必要再引一份摘要 —— 同一个文件名换内容时
     * 大小或时间必然变，够用了。
     */
    private fun cacheKey(file: File, fileName: String): String {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "comp-" + safeName + "-" + file.length() + "-" + file.lastModified()
    }

    /** 先写 .part 再改名：中途被杀掉不会留下半张图片让下次当成缓存命中。 */
    private fun writeAtomically(target: File, bytes: ByteArray): File? = runCatching {
        val dir = target.parentFile
        if (dir != null && !dir.isDirectory) dir.mkdirs()
        val temp = File(target.absolutePath + ".part")
        temp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        target
    }.getOrNull()
}
