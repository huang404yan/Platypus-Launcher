package io.github.huang404yan.platypus.core.instance

/**
 * 分组名的长度上限。
 *
 * 段头与卡片上都是一行小字，超过这个长度必然被省略号截掉；与其让用户打完再发现看不见，
 * 不如在录入时就收住。24 个汉字已经足够表达「1.20 联机」「Mod 战斗」这类名字。
 */
const val MAX_GROUP_NAME_LENGTH = 24

/**
 * 收一下用户输入的组名：全角空格换成半角、去掉首尾空白、内部连续空白折成一个、超长截断。
 *
 * 界面上的文本框与 `instances.json` 手改两条路都走这里，两边归一化规则不一致的话，
 * 会出现「界面上叫 Mod，手改后多出一个叫「Mod 」的空组」这种看得见的重影。
 */
fun normalizeGroupName(raw: String): String = raw
    .replace('\u3000', ' ')
    .trim()
    .split(' ')
    .filter { it.isNotEmpty() }
    .joinToString(" ")
    .take(MAX_GROUP_NAME_LENGTH)
    .trim()

/**
 * 把一条实例归到某个组（[group] 传空串就是移出分组）。
 *
 * 顺手 `registered = true`：用户既然主动给这一版分了组，这条就该被我们记住 ——
 * 只扫描到的外部实例原先不在 `instances.json` 里，不登记的话下次启动分组就没了。
 */
fun withGroup(instance: Instance, group: String): Instance =
    instance.copy(group = normalizeGroupName(group), registered = true)

/**
 * 列表里的一段：组名 + 属于它的实例。
 *
 * [name] 为空串表示「未分组」那一段。
 */
data class InstanceSection(
    val name: String,
    val instances: List<Instance>,
) {
    val ungrouped: Boolean get() = name.isEmpty()
}

/**
 * 按分组把一摞实例切成若干段，供列表页一段一段摆。
 *
 * 段内顺序原样保留（传进来的已经是 [instanceOrder] 排好的：收藏 → 已登记 → 最近玩过 → 名字），
 * 所以同一段里收藏的仍然在最前。段与段之间按组名排，未分组永远放最后 —— 用户建了组，
 * 意思就是「我要先看到这几组」，剩下一堆没归类的堆在末尾当备用。
 *
 * 组名比对按原文（归一化之后），不合并大小写：显示的是用户自己打的那个样子，
 * 而「Mod」与「mod」同时存在的情况在个位数条目里几乎不会出现，为此把两者并成一段
 * 反而会让人以为自己打错了。
 */
fun sectionize(instances: List<Instance>): List<InstanceSection> {
    val named = LinkedHashMap<String, MutableList<Instance>>()
    val ungrouped = mutableListOf<Instance>()
    instances.forEach { instance ->
        val name = normalizeGroupName(instance.group)
        if (name.isEmpty()) ungrouped += instance else named.getOrPut(name) { mutableListOf() } += instance
    }
    val sections = named.entries
        .sortedBy { it.key.lowercase() }
        .map { InstanceSection(it.key, it.value.toList()) }
    return if (ungrouped.isEmpty()) sections else sections + InstanceSection("", ungrouped)
}

/**
 * 这摞实例里出现过的组名，给「改分组」对话框列出最近用过的几个。
 *
 * 按名字排而不是按出现顺序：用户在对话框里找的是「我上次建的那个组叫什么」，
 * 名字排序比「越早建的越靠前」更容易扫。
 */
fun groupNames(instances: List<Instance>): List<String> = instances
    .map { normalizeGroupName(it.group) }
    .filter { it.isNotEmpty() }
    .distinct()
    .sortedBy { it.lowercase() }
