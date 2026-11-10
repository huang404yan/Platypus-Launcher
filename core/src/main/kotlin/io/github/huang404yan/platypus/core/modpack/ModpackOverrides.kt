package io.github.huang404yan.platypus.core.modpack

/**
 * 整合包 zip 里 `overrides/` 这类「配置层」的路径换算。
 *
 * 单独抽出来是为了能单测：真正的解压要开 zip，而「哪个 entry 属于哪一层、剥掉前缀之后
 * 落在实例目录的什么位置」纯是字符串运算，出错的代价却是把文件写到错误的地方。
 *
 * 两层容易被搞混：
 * - **层**（`overrides` / `client-overrides`）：包作者写死在 zip 里的目录名；
 * - **基础前缀**（[ModpackLayout.basePrefix]）：有的包把整棵目录套在一个顶层文件夹里
 *   （`MyPack/overrides/...`），识别格式时要把这一层记住再拼回去。
 */
object ModpackOverrides {

    /**
     * 这个 entry 属于 [prefixes] 里的哪一层？返回剥掉前缀后的相对路径；不属于任何一层返回 null。
     *
     * @param entryName zip 里的原始 entry 名（会先归一化反斜杠）
     * @param basePrefix 包布局前缀，例如 `"MyPack/"`；没有就是空串
     * @param prefixes 层目录名，**按优先级排列**，先命中的算（层之间不会互相嵌套，顺序只影响
     *   万一有两个同名前缀时的确定性）
     */
    fun relativePath(
        entryName: String,
        basePrefix: String = "",
        prefixes: List<String> = listOf(ModpackMeta.OVERRIDES_DIR),
    ): String? {
        val name = ModpackMeta.safeRelativePath(entryName) ?: return null
        val base = basePrefix.replace('\\', '/').trimStart('/')
        val afterBase = if (base.isEmpty()) {
            name
        } else {
            if (!name.startsWith(base)) return null
            name.substring(base.length)
        }
        for (prefix in prefixes) {
            val head = prefix.trim('/') + "/"
            if (!afterBase.startsWith(head)) continue
            // 目录条目（"overrides/" 本身）剥完是空串，不是要落盘的文件
            return ModpackMeta.safeRelativePath(afterBase.substring(head.length))
        }
        return null
    }

    /**
     * 同一条相对路径在**不同层之间**的合并顺序：后面的层覆盖前面的。
     *
     * mrpack 官方规定 `overrides/` 是基础层，`client-overrides/` 在客户端最后覆盖；
     * MCBBS 只有一个 `overrides/`。这里按调用顺序原样返回，只为把「谁盖谁」写在一处，
     * 免得两处实现各记一遍。
     */
    fun layersFor(manifest: ModpackManifest): List<String> = manifest.overrideDirs

    /**
     * 判断一个从 zip 里解出来的文件会不会覆盖实例里已有的**用户文件**。
     *
     * 导入一个已经装过的包时，`mods/` 里的同名 jar 覆盖掉是正常的；但 `saves/`（存档）
     * 被覆盖就是数据丢失，宁可让调用方问一句。
     */
    fun touchesUserData(relativePath: String): Boolean {
        val head = relativePath.substringBefore('/').lowercase()
        return head in PROTECTED_TOP_DIRS
    }

    /** 覆盖会丢东西的顶层目录：存档、截图、日志里的历史、以及本地设置。 */
    val PROTECTED_TOP_DIRS: Set<String> = setOf(
        "saves", "screenshots", "logs", "crash-reports", "options.txt", "servers.dat",
    )
}
