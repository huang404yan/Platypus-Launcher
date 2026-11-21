package io.github.huang404yan.platypus.core.instance

/**
 * 实例列表上「找得到那一条」的两件事：搜与分组。
 *
 * 这两件事都只在界面上用，但判定口径必须与别处一致，所以放在 core 里、不去碰 Compose：
 * 界面上写一遍、测试里测不了，是这类小逻辑反复出错的常见来路。
 */
object InstanceCatalog {

    /**
     * 按 [query] 过滤实例，多个词之间是「且」的关系。
     *
     * 为什么按词切而不是整串子串匹配：用户打字找版本时写的是「1.20 fabric」这种两段，
     * 整串匹配会因为两段在原字符串里隔着别的字符而一条都找不到。
     *
     * 全角空格也当分隔符：中文输入法下打出来的空格常常是全角 U+3000，按半角切的话
     * 用户会看到「明明打对了却搜不到」。
     */
    fun search(instances: List<Instance>, query: String): List<Instance> {
        val terms = query.split(' ', '\u3000').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return instances
        return instances.filter { instance ->
            val haystack = haystackOf(instance)
            terms.all { haystack.contains(it) }
        }
    }

    /**
     * 一条实例上可被搜到的全部文本，拼成小写的一串。
     *
     * 目录整条也拼进去（反斜杠统一成斜杠）：改过名的实例，用户记得的往往是
     * 「在哪个盘哪个文件夹里」，而不是当初起的那个名字。
     */
    private fun haystackOf(instance: Instance): String {
        val directory = instance.directory.trim().trim('"').replace('\\', '/')
        return buildString {
            append(instance.name.lowercase()).append('\n')
            append(instance.id.lowercase()).append('\n')
            append(instance.gameVersion.lowercase()).append('\n')
            append(instance.loader.name.lowercase()).append('\n')
            append((instance.loaderVersion ?: "").lowercase()).append('\n')
            append(directory.lowercase())
        }
    }

    /**
     * 分成「自己登记过的」与「只是扫到的」两组，组内保持传入顺序。
     *
     * 界面上一律按这两组分开摆：混在一起时用户分不清点「删除」会删掉自己配好的实例
     * 还是别的启动器留下的目录。
     */
    fun partition(instances: List<Instance>): InstanceGroups = InstanceGroups(
        registered = instances.filter { it.registered },
        scanned = instances.filter { !it.registered },
    )
}

/** [InstanceCatalog.partition] 的结果。两组都可能为空。 */
data class InstanceGroups(
    val registered: List<Instance>,
    val scanned: List<Instance>,
) {
    val total: Int get() = registered.size + scanned.size
    val isEmpty: Boolean get() = total == 0
}
