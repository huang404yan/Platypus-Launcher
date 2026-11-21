package io.github.huang404yan.platypus.core.instance

import java.io.File

/**
 * `versions/` 目录里**实际存在的**那些版本目录名。
 *
 * 用途只有一个：[pruneMissing] —— 判断磁盘上那条目录还在不在。
 *
 * 为什么是一张名单而不是每条实例一次 `File.exists()`：启动页、实例页、模组页、
 * 百宝箱、诊断页都各自扫一遍游戏目录，`exists()` 会变成几十上百次系统调用；而
 * `versions/` 下的目录本来就被 [InstanceScanner.scan] 列过一遍了。名单是那次列举的
 * 副产品（见 [ScanResult.versionNames]），顺手带出去，判活变成一次集合查找。
 *
 * 名单为 null 表示「这次没有扫到 versions/」，**不是**「versions/ 是空的」：
 * 读盘失败、目录还没建、调用方压根没扫（例如从没登记过的老数据）都会落到这一支。
 * 这两种情形下 [pruneMissing] 一律不动列表 —— 拿一次没读成的扫描当证据去藏条目，
 * 正是「插着移动硬盘开机、版本全不见了」那类惊吓的来路。
 */
data class VersionNames(val names: Set<String>) {

    /** `versions/` 之下有这个名字的目录。大小写不分：Windows 与 macOS 上那本来就是同一个目录。 */
    fun contains(name: String): Boolean = names.contains(name.lowercase())

    companion object {
        /** 从 `versions/` 下直接列出来的目录名建名单；列不出来时返回 null（含义见类注释）。 */
        fun under(root: File): VersionNames? = of(File(root, "versions").listFiles { f -> f.isDirectory }?.toList())

        /** 已经有目录列表时直接建名单，省一次读盘。 */
        fun of(dirs: List<File>?): VersionNames? = dirs?.let { list ->
            VersionNames(list.mapTo(HashSet(list.size)) { it.name.lowercase() })
        }
    }
}

/** [pruneMissing] 的结果：留下的、摘掉的。两边加起来一定是原来那些。 */
data class PruneResult(
    val kept: List<Instance>,
    val missing: List<Instance>,
)

/**
 * 把磁盘上已经没了的实例从展示列表里摘出去，并报出摘了哪几条。
 *
 * 为什么要有这一步：列表是「登记的 + 扫到的」合并出来的（见 [mergeInstances]），
 * 扫描那一半天然反映磁盘现状，**登记那一半不会自己消失**。用户在外面对着资源管理器
 * 手动删掉一个版本目录、或者换了盘并把游戏目录拷到别处，重新打开启动器仍会看到那一条：
 * 点启动要等好几秒才报「目录不存在」，点详情是一页空白。这不是一个能被用户理解的界面。
 *
 * 刻意**只摘出展示列表，不写盘**：磁盘可能只是暂时不在（移动硬盘拔了、网盘还没挂载上），
 * 而一旦把登记删掉，插回来的人就得重新配一遍内存与 Java。
 *
 * @param names 扫描时顺手列出的 `versions/` 名单；null 时原样返回（判据见 [VersionNames]）
 */
fun pruneMissing(instances: List<Instance>, names: VersionNames?): PruneResult {
    if (names == null) return PruneResult(instances, emptyList())
    val kept = ArrayList<Instance>(instances.size)
    val missing = mutableListOf<Instance>()
    instances.forEach { instance ->
        if (names.contains(versionDirName(instance.directory))) kept += instance else missing += instance
    }
    return PruneResult(kept, missing)
}

/**
 * 从 [Instance.directory] 取出 `versions/` 之下那一层的名字。
 *
 * 只认最后一段：`.../versions/1.20.1-fabric` → `1.20.1-fabric`。
 * 末尾多余的斜杠与引号先剥掉 —— 界面上手贴路径的人经常把 `"...\1.20.1"` 连同引号一起贴进来。
 */
internal fun versionDirName(directory: String): String =
    directory.trim().trim('"').replace('\\', '/').trimEnd('/').substringAfterLast('/')

/**
 * 把 [PruneResult.missing] 写成一行给人看的话；没有摘掉任何东西时返回 null。
 *
 * 为什么要说这一句：条目自己消失是**看不见**的。用户删了一个版本目录再打开启动器，
 * 如果他本来就没打算找那一条，界面上不会有任何变化；等他哪天想起来「我那个 1.20.1 呢」，
 * 也没有任何线索能解释它去哪了。留一句「这几个的目录不在了、接回盘就能看到」，
 * 至少把「我没弄坏它」这句话讲明白了。
 *
 * 放在 core 而不是各端各写一份：桌面和安卓的措辞要一样，否则同一件事在两个界面上
 * 读起来像两个毛病。
 */
fun missingNotice(missing: List<Instance>): String? {
    if (missing.isEmpty()) return null
    val names = missing.joinToString("、") { it.name }
    return "有 ${missing.size} 个实例的目录已经不在了，暂时不列出来：$names。把盘接回来或把目录放回去就会重新出现。"
}
