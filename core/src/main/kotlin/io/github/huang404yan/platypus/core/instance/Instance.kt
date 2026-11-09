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
) {
    companion object {
        const val DEFAULT_MEMORY_MB = 2048
        const val MIN_MEMORY_MB = 512
        const val MAX_MEMORY_MB = 32768

        /** 把用户输入的内存夹到合法区间；低于 512 几乎必然启动失败，高于 32G 基本是手滑。 */
        fun clampMemoryMb(value: Int): Int = value.coerceIn(MIN_MEMORY_MB, MAX_MEMORY_MB)
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
 */
fun mergeInstances(registered: List<Instance>, scanned: List<Instance>): List<Instance> {
    val merged = LinkedHashMap<String, Instance>()
    registered.forEach { merged[instanceKey(it)] = it.copy(registered = true) }
    scanned.forEach { merged.putIfAbsent(instanceKey(it), it.copy(registered = false)) }
    return merged.values.sortedWith(
        compareByDescending<Instance> { it.registered }.thenBy { it.name.lowercase() },
    )
}

/** 判定「同一条实例」的键：目录（归一化后的绝对路径），取不到才退回 id。 */
fun instanceKey(instance: Instance): String =
    instance.directory.trim().trim('"').replace('\\', '/').lowercase().ifBlank { "id:" + instance.id }
