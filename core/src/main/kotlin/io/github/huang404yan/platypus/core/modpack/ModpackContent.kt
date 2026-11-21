package io.github.huang404yan.platypus.core.modpack

/**
 * 包里 `overrides/` 这类配置层里到底装了什么。
 *
 * 为什么需要它：MCBBS v2 包的 [ModpackManifest.files] 恒为空 —— 模组实体全铺在
 * `overrides/mods/` 里，清单里一个字都没写。只看 fileCount 的话，一个带两百个模组的包
 * 和一个什么都没带的包在界面上长得一模一样（都是「0 个文件要下载」），用户根本看不出
 * 「导出时漏勾了模组」和「启动器把模组丢了」的区别。
 *
 * 只读 zip 的 entry 名，不解压，代价可以忽略（几千条字符串各过一遍路径换算而已）。
 */
data class ModpackContent(
    /** 配置层里所有文件的总数（目录条目不算）。 */
    val totalFiles: Int,
    /** 其中 `mods/` 下的 .jar 数量 —— 这是「这个包有没有带模组」的唯一可靠信号。 */
    val modJars: Int,
    /** 顶层目录，按文件数从多到少；同数按名字排，保证输出稳定。 */
    val dirs: List<Dir>,
) {
    /** 一个顶层目录的小计。 */
    data class Dir(val name: String, val files: Int, val jars: Int)

    /** 配置层里一个文件都没有（包只在清单里声明要装什么）。 */
    val isEmpty: Boolean get() = totalFiles == 0

    /**
     * 一行话，给界面直接显示：`mods 12 个文件（12 个 jar） · config 959 个文件`。
     *
     * 超过 [maxDirs] 个目录就截断成 `…还有 N 个目录`，避免一行里塞二十个目录名反而看不清。
     */
    fun describe(maxDirs: Int = 5): String {
        if (isEmpty) return "一个文件都没有"
        val head = dirs.take(maxDirs).joinToString(" · ") { dir ->
            if (dir.jars > 0) {
                dir.name + " " + dir.files + " 个文件（" + dir.jars + " 个 jar）"
            } else {
                dir.name + " " + dir.files + " 个文件"
            }
        }
        val rest = dirs.size - maxDirs
        return if (rest > 0) head + " · …还有 " + rest + " 个目录" else head
    }
}

/** 扫 zip entry 名，统计 [ModpackContent]。纯字符串运算，不碰磁盘。 */
object ModpackContents {

    /** 文件名叫这个样子的算「模组文件」（停用/旧版本也照样是模组，只是游戏不加载）。 */
    private val MOD_SUFFIXES = listOf(".jar", ".jar.disabled", ".jar.old")

    /** 一个文件是不是模组 jar。目录名不限，只看文件名后缀。 */
    internal fun isModJar(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return MOD_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * @param entryNames zip 里的全部 entry 名
     * @param basePrefix 包布局前缀（套了一层顶层文件夹的包），与 [ModpackOverrides.relativePath] 同义
     * @param prefixes 配置层目录名，通常就是 [ModpackManifest.overrideDirs]
     */
    fun scan(
        entryNames: Collection<String>,
        basePrefix: String = "",
        prefixes: List<String> = listOf(ModpackMeta.OVERRIDES_DIR),
    ): ModpackContent {
        if (prefixes.isEmpty()) return ModpackContent(0, 0, emptyList())
        var total = 0
        var modJars = 0
        val counts = LinkedHashMap<String, IntArray>()
        for (raw in entryNames) {
            // 目录条目（`overrides/mods/`）剥完前缀是 `mods`，不是文件。zip 里目录就是
            // 以斜杠结尾的那条，先按这个判断掉，不然统计出来的数会比实际文件多。
            if (raw.endsWith("/") || raw.endsWith("\\")) continue
            val relative = ModpackOverrides.relativePath(raw, basePrefix, prefixes) ?: continue
            if (relative.isEmpty()) continue
            total++
            val top = relative.substringBefore('/')
            val counter = counts.getOrPut(top) { IntArray(2) }
            counter[0]++
            val fileName = relative.substringAfterLast('/')
            if (isModJar(fileName)) {
                counter[1]++
                // mods/ 下的 jar 才算「这个包带的模组」；config 里偶尔也有 .jar（很少见，不算）
                if (top.equals("mods", ignoreCase = true)) modJars++
            }
        }
        val dirs = counts.entries
            .map { ModpackContent.Dir(it.key, it.value[0], it.value[1]) }
            .sortedWith(compareByDescending<ModpackContent.Dir> { it.files }.thenBy { it.name })
        return ModpackContent(totalFiles = total, modJars = modJars, dirs = dirs)
    }
}
