package io.github.huang404yan.platypus.core.install

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 装过一次、还想再开一次的服务端。
 *
 * 为什么不复用游戏实例的 [io.github.huang404yan.platypus.core.instance.Instance]：
 * 服务端没有 versions/ 布局、不进启动页、也不需要 Java 探测那一套。硬塞进去会让
 * 「实例」列表里混进一堆点「启动」会报错的东西。
 *
 * @param directory 服务端目录（server.jar 或 Forge 的 run.bat 所在处）。
 * @param kind 装的时候选的加载器，只用来在列表上写清楚这是什么。
 * @param loaderVersion Paper 是构建号，Fabric 是 loader 版本，Forge / NeoForge 是加载器版本。
 * @param javaPath 装 Forge / NeoForge 时用的 Java；别的加载器不记。
 */
@Serializable
data class ServerEntry(
    val id: String,
    val name: String,
    val kind: ServerKind = ServerKind.VANILLA,
    val gameVersion: String,
    val loaderVersion: String? = null,
    val directory: String,
    val memoryMb: Int = ServerInstaller.DEFAULT_MEMORY_MB,
    val javaPath: String? = null,
    val createdAt: Long = 0L,
) {
    /** 列表上那一行：「Paper 1.20.1 196」「Forge 1.20.1 47.4.0」。 */
    val title: String
        get() = buildString {
            append(kind.label)
            append(' ')
            append(gameVersion)
            if (!loaderVersion.isNullOrBlank()) {
                append(' ')
                append(loaderVersion)
            }
        }
}

/** 服务端登记的读写。界面只需要这几个动作，实现见 [JsonServerRepository]。 */
interface ServerRepository {
    suspend fun list(): List<ServerEntry>
    suspend fun save(entry: ServerEntry)
    suspend fun remove(id: String)
}

/**
 * 以单个 JSON 文件保存服务端登记。
 *
 * 与 [io.github.huang404yan.platypus.core.instance.JsonInstanceRepository] 同一套做法：
 * 人眼能看、出错能手工修、写坏了不至于连游戏都开不了；写入走临时文件再改名。
 */
class JsonServerRepository(private val indexFile: File) : ServerRepository {

    private val mutex = Mutex()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** 索引文件路径（界面上告诉用户「登记存在哪」用）。 */
    val path: String get() = indexFile.absolutePath

    override suspend fun list(): List<ServerEntry> = mutex.withLock { read() }

    override suspend fun save(entry: ServerEntry) = mutex.withLock {
        require(entry.id.isNotBlank()) { "服务端 id 不能为空" }
        require(entry.directory.isNotBlank()) { "服务端目录不能为空" }
        val current = read().toMutableList()
        val stored = entry.copy(
            memoryMb = entry.memoryMb.coerceIn(512, 32768),
            createdAt = if (entry.createdAt > 0) entry.createdAt else System.currentTimeMillis(),
        )
        // 同一个目录只留一条：用户重装同一个服务端不该在列表里长出第二条。
        val index = current.indexOfFirst { it.id == stored.id || sameDir(it, stored) }
        if (index >= 0) current[index] = stored else current += stored
        write(current.sortedBy { it.createdAt })
    }

    override suspend fun remove(id: String) = mutex.withLock {
        val current = read()
        val kept = current.filterNot { it.id == id }
        if (kept.size != current.size) write(kept)
    }

    private fun read(): List<ServerEntry> {
        if (!indexFile.isFile) return emptyList()
        val text = runCatching { indexFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ServerEntry.serializer()), text)
        }.getOrElse { emptyList() }
    }

    private fun write(entries: List<ServerEntry>) {
        val parent = indexFile.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val text = json.encodeToString(ListSerializer(ServerEntry.serializer()), entries)
        val temp = File(parent ?: indexFile.absoluteFile.parentFile, indexFile.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(indexFile)) {
            // Windows 上目标存在时 renameTo 会失败，退一步「覆盖写」，不比原子更差。
            indexFile.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun sameDir(a: ServerEntry, b: ServerEntry): Boolean =
        a.directory.trim().trim('"').replace('\\', '/').lowercase() ==
            b.directory.trim().trim('"').replace('\\', '/').lowercase()

    companion object {
        /** 默认索引位置：<gameRoot>/platypus/servers.json，与实例索引平级。 */
        fun defaultIndexFile(gameRoot: File): File = File(File(gameRoot, "platypus"), "servers.json")
    }
}

/** 把一次安装结果变成可登记的条目。目录取绝对路径，名字取「加载器 + 版本」。 */
fun serverEntryOf(
    result: ServerInstaller.ServerInstallResult,
    memoryMb: Int,
    javaPath: String? = null,
): ServerEntry = ServerEntry(
    id = "server-" + System.currentTimeMillis().toString(36),
    name = result.dir.name.ifBlank { result.versionId },
    kind = result.kind,
    gameVersion = result.versionId,
    loaderVersion = result.loaderVersion,
    directory = result.dir.absolutePath,
    memoryMb = memoryMb,
    javaPath = javaPath,
)
