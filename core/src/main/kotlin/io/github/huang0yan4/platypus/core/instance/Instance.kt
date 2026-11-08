package io.github.huang0yan4.platypus.core.instance

enum class LoaderKind { VANILLA, FORGE, NEOFORGE, FABRIC, QUILT, OPTIFINE, OTHER }

/**
 * 一个实例 = 一个自包含目录。
 * 导入导出就是复制目录，避免全局 .minecraft 被写脏。
 */
data class Instance(
    val id: String,
    val name: String,
    val gameVersion: String,
    val loader: LoaderKind = LoaderKind.VANILLA,
    val loaderVersion: String? = null,
    val directory: String,
    val javaPath: String? = null,
    val memoryMb: Int = 2048,
    val extraJvmArgs: List<String> = emptyList(),
    val createdAt: Long = 0L,
)

interface InstanceRepository {
    suspend fun list(): List<Instance>
    suspend fun find(id: String): Instance?
    suspend fun save(instance: Instance)
    suspend fun remove(id: String)
}
