package io.github.huang404yan.platypus.core.instance

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 以单个 JSON 文件保存实例登记的仓库。
 *
 * 选文件而不是数据库：实例数量是个位数，人眼能直接看、出错能手工修、导入导出就是拷文件。
 * 写入走「临时文件 + 原子替换」，中途崩溃不会留下半个 JSON。
 *
 * 与 [InstanceScanner] 的分工：这里只存「本启动器建过 / 用户改过的实例」；扫描出来的外部
 * 实例不进文件，靠 [mergeInstances] 在展示层合并。
 */
class JsonInstanceRepository(private val indexFile: File) : InstanceRepository {

    private val mutex = Mutex()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** 索引文件路径（UI 上用来告诉用户「登记存在哪」）。 */
    val path: String get() = indexFile.absolutePath

    override suspend fun list(): List<Instance> = mutex.withLock { read() }

    override suspend fun find(id: String): Instance? = mutex.withLock {
        read().firstOrNull { it.id == id }
    }

    override suspend fun save(instance: Instance) = mutex.withLock {
        require(instance.id.isNotBlank()) { "实例 id 不能为空" }
        require(instance.directory.isNotBlank()) { "实例目录不能为空" }
        val current = read().toMutableList()
        val stored = instance.copy(
            registered = true,
            memoryMb = Instance.clampMemoryMb(instance.memoryMb),
            createdAt = if (instance.createdAt > 0) instance.createdAt else System.currentTimeMillis(),
        )
        val index = current.indexOfFirst { it.id == stored.id || instanceKey(it) == instanceKey(stored) }
        if (index >= 0) current[index] = stored else current += stored
        write(current.sortedBy { it.id })
    }

    override suspend fun remove(id: String) = mutex.withLock {
        val current = read()
        val kept = current.filterNot { it.id == id }
        if (kept.size != current.size) write(kept)
    }

    private fun read(): List<Instance> {
        if (!indexFile.isFile) return emptyList()
        val text = runCatching { indexFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Instance.serializer()), text)
                .map { it.copy(registered = true, memoryMb = Instance.clampMemoryMb(it.memoryMb)) }
        }.getOrElse { emptyList() }
    }

    private fun write(instances: List<Instance>) {
        val parent = indexFile.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val text = json.encodeToString(ListSerializer(Instance.serializer()), instances)
        val temp = File(parent ?: indexFile.absoluteFile.parentFile, indexFile.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(indexFile)) {
            // Windows 上目标存在时 renameTo 会失败，退一步「覆盖写」，不比原子更差。
            indexFile.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    companion object {
        /** 默认索引位置：<gameRoot>/platypus/instances.json，与 versions/ 平级但不干扰扫描。 */
        fun defaultIndexFile(gameRoot: File): File = File(File(gameRoot, "platypus"), "instances.json")
    }
}
