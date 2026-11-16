package io.github.huang404yan.platypus.core.instance

import kotlinx.serialization.Serializable

enum class LoaderKind { VANILLA, FORGE, NEOFORGE, FABRIC, QUILT, OPTIFINE, OTHER }

/**
 * 一个实例 = 一个自包含目录。
 * 导入导出就是复制目录，避免全局 .minecraft 被写脏。
 *
 * @param directory 版本目录（<gameRoot>/versions/<id>）。DesktopGameRuntime 由它反推 gameDir。
 * @param registered 是否由本启动器登记（登记过的才能改内存、才会出现在「实例」页的托管分组里）。
 *   目录扫描出来的外部实例恒为 false，但仍然可以用，只是改动会顺带把它登记进来。
 * @param memoryFollowsDefault 内存跟着设置页的「默认内存」走（PCL 的「跟随全局设置」）。
 *   为 true 时 [memoryMb] 总被读成设置里的默认值，实例自己不存独立的数；用户在这个实例上
 *   拖过滑条才变成 false。默认 false 是为了不改变旧数据的意思：老实例里那个数是用户
 *   亲手选的（或当初的默认值），不能因为加了这一版就集体跳到新的默认内存上。
 * @param isolated 这个实例的存档 / 模组 / 配置是「自己一份」还是「和同目录下别的版本共用」。
 *   默认 true（等于旧行为：--gameDir 指 <gameRoot>/versions/<id>）。设成 false 时
 *   --gameDir 指回 <gameRoot>，所有版本共用 <gameRoot>/mods、saves、options.txt ——
 *   只有一种情况该这么用：一个整合包把 mods/ 平铺在游戏根目录下，版本目录里干干净净。
 *   参照 PCL 的「版本隔离」开关（ModMinecraft.vb 的 PathIndie）。
 *   想要「新实例按设置里的默认来」用 [isolatedFollowsDefault]，不是每次建实例都抄一遍。
 */
@Serializable
data class Instance(
    val id: String,
    val name: String,
    val gameVersion: String,
    val loader: LoaderKind = LoaderKind.VANILLA,
    val loaderVersion: String? = null,
    val directory: String,
    val javaPath: String? = null,
    val memoryMb: Int = DEFAULT_MEMORY_MB,
    val extraJvmArgs: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val registered: Boolean = false,
    val isolated: Boolean = true,
    val memoryFollowsDefault: Boolean = false,
    /**
     * 版本隔离跟着设置页的「默认」走（2026-11-16 加，参照 [memoryFollowsDefault] 的做法）。
     *
     * 为 true 时 [isolated] 存的那份不再作数，实际用 [InstanceLayouts.effectiveIsolated]。
     * 默认 false 是为了不改老数据的意思：老实例里那个开关是当初建的实例自己带的，
     * 不能因为加了全局默认就集体跳一次 —— 那会让一部分人的存档在没人察觉时换地方。
     */
    val isolatedFollowsDefault: Boolean = false,
) {
    companion object {
        const val DEFAULT_MEMORY_MB = 2048
        const val MIN_MEMORY_MB = 512
        const val MAX_MEMORY_MB = 32768

        /** 把用户输入的内存夹到合法区间；低于 512 几乎必然启动失败，高于 32G 基本是手滑。 */
        fun clampMemoryMb(value: Int): Int = value.coerceIn(MIN_MEMORY_MB, MAX_MEMORY_MB)

        /**
         * 这个实例实际该用多少内存。
         *
         * 跟随全局的实例读设置页那个默认值，其余的读自己存的数。启动、详情、卡片、安卓端
         * 都该走这里，不要直接读 [Instance.memoryMb] —— 直接读会让「跟随全局」在那些地方
         * 静默失效（改设置里的默认内存，界面上这些实例纹丝不动）。
         */
        fun effectiveMemoryMb(instance: Instance, defaultMemoryMb: Int): Int =
            if (instance.memoryFollowsDefault) clampMemoryMb(defaultMemoryMb) else instance.memoryMb

        /**
         * 跟随全局时把默认值抄进实例自己存的字段。
         *
         * 为什么还要抄一份：`instances.json` 是给人看、也能手改的，里面留着一个和实际
         * 不一样的老数会让人以为改设置没生效。抄一份只让文件自解释，读的时候仍以设置页
         * 那个默认值为准。
         */
        fun applyDefaultMemory(instance: Instance, defaultMemoryMb: Int): Instance =
            if (!instance.memoryFollowsDefault) instance
            else instance.copy(memoryMb = clampMemoryMb(defaultMemoryMb))
    }
}

interface InstanceRepository {
    suspend fun list(): List<Instance>
    suspend fun find(id: String): Instance?
    suspend fun save(instance: Instance)
    suspend fun remove(id: String)
}

/**
 * 把「登记的实例」与「目录扫描到的实例」合成一份列表。
 *
 * 同一目录只留一条，登记的优先（保住用户改过的内存与 Java 路径）；扫描到的外部实例
 * 原样保留在列表里，避免「别的启动器装的版本」从界面上消失。
 *
 * [libraryVersions] 是扫描器判定的「只被加载器版本继承的原版目录」。这些目录在磁盘上
 * 必须留着（加载器靠 inheritsFrom 拿它的 client jar 与库），但不该在列表里和带加载器的
 * 那条并排出现 —— 用户会以为装重了。**登记的也要一起挡**：先前单独下过原版、后来又在
 * 同一目录上补装加载器的用户，原版那条是登记过的，光靠扫描层折叠挡不住。
 */
fun mergeInstances(
    registered: List<Instance>,
    scanned: List<Instance>,
    libraryVersions: List<String> = emptyList(),
): List<Instance> {
    val merged = LinkedHashMap<String, Instance>()
    registered.forEach {
        // 只挡「就是那个纯原版目录」的登记项：别的加载器版本、或者用户自己加过 mod 的
        // 同名目录不算（后者 loader 仍标 VANILLA，但它的 id 不会被别的版本 inheritsFrom 指到）。
        if (it.loader == LoaderKind.VANILLA && it.id in libraryVersions) return@forEach
        merged[instanceKey(it)] = it.copy(registered = true)
    }
    scanned.forEach { merged.putIfAbsent(instanceKey(it), it.copy(registered = false)) }
    return merged.values.sortedWith(
        compareByDescending<Instance> { it.registered }.thenBy { it.name.lowercase() },
    )
}

/** 判定「同一条实例」的键：目录（归一化后的绝对路径），取不到才退回 id。 */
fun instanceKey(instance: Instance): String =
    instance.directory.trim().trim('"').replace('\\', '/').lowercase().ifBlank { "id:" + instance.id }
