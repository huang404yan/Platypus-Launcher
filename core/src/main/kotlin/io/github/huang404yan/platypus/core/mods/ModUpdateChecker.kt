package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.instance.LoaderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** 一个本地 jar 及其 sha1。算一次能重复用，检查更新要用它当查询键。 */
data class LocalModFile(val fileName: String, val file: File, val sha1: String)

/** 检查更新的结论。界面按这个分四类显示，不要用一句「有 3 个可更新」糊过去。 */
enum class ModUpdateStatus {
    /** 本地版本已经是 Modrinth 上给这个游戏版本/加载器的最新正式版。 */
    UP_TO_DATE,
    /** 有更新的版本。 */
    OUTDATED,
    /** Modrinth 不认识这个 sha1：自己改过的包、从别的站下的、或者根本不是模组。 */
    NOT_ON_MODRINTH,
    /** 认得出是哪个模组，但它没有适配当前游戏版本/加载器的版本。 */
    NO_COMPATIBLE_VERSION,
}

/**
 * 一条模组的检查结果。
 *
 * [lookup] 为 null 表示 Modrinth 不认识这个文件；[latest] 为 null 表示没查到能用的新版本。
 * 两者都带上而不是只给一句结论，是因为界面要能说清「为什么没更新」。
 */
data class ModUpdateResult(
    val fileName: String,
    val sha1: String,
    val lookup: ModVersionLookup? = null,
    val latest: ModVersionFile? = null,
    val project: ModProject? = null,
    val note: String? = null,
) {
    val title: String get() = project?.title?.takeIf { it.isNotBlank() } ?: fileName

    val currentVersion: String get() = lookup?.versionNumber.orEmpty()

    val latestVersion: String? get() = latest?.versionNumber?.takeIf { it.isNotBlank() }

    val status: ModUpdateStatus
        get() = when {
            lookup == null -> ModUpdateStatus.NOT_ON_MODRINTH
            latest == null -> ModUpdateStatus.NO_COMPATIBLE_VERSION
            compareVersions(lookup.versionNumber, latest.versionNumber) -> ModUpdateStatus.UP_TO_DATE
            else -> ModUpdateStatus.OUTDATED
        }

    companion object {
        /** 版本号就是同一个字符串才算「已是最新」：不同组织方式（1.2 vs 1.2.0）宁可多提示一次，也不静默漏掉更新。 */
        fun compareVersions(current: String, latest: String): Boolean =
            current.trim().equals(latest.trim(), ignoreCase = true)
    }
}

/**
 * 检查更新：本地 jar 的 sha1 → Modrinth 反查是哪个模组的哪个版本 → 再问这个模组最新是什么。
 *
 * 为什么要 sha1 而不是读 jar 里的 modId：Modrinth 的搜索只按 slug/标题走，modId 与 slug 不是一回事
 * （Sodium 的 modId 是 sodium 但很多模组不是）。sha1 是 Modrinth 自己给的主键，最不会认错。
 *
 * 全程只读本地文件，唯一的副作用是算 sha1 时要读盘。
 */
object ModUpdateChecker {

    /** Modrinth 的 /version_files 一次收多少条未知，100 是社区实践里稳妥的数量。 */
    const val LOOKUP_BATCH = 100

    /** 算一个文件的 sha1。读失败抛 IOException，让界面如实报「哪个文件读不了」。 */
    fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            throw IOException("读不了 " + file.name + "：" + (e.message ?: e::class.simpleName), e)
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val table = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = table[v ushr 4]
            chars[i * 2 + 1] = table[v and 0x0F]
        }
        return String(chars)
    }

    /** 给一批文件算 sha1。按 100 一批反查，避免一次性 POST 一个巨大的数组。 */
    suspend fun hashAll(files: List<File>): List<LocalModFile> = withContext(Dispatchers.IO) {
        files.map { LocalModFile(it.name, it, sha1Of(it)) }
    }

    /**
     * 拼 /version_files 的请求体。
     *
     * 抽出来单测盯着：这里少一个引号就会变成 [`"a,b"`]（两个哈希粘成一个），
     * Modrinth 只会回「不认识这个文件」，界面上表现为「全都查不到更新」——不会报错，
     * 所以只能靠单测拦。
     */
    fun versionFilesBody(hashes: List<String>): String {
        val quoted = hashes.joinToString(",") { "\"" + it + "\"" }
        return "{\"hashes\":[" + quoted + "],\"algorithm\":\"sha1\"}"
    }

    /** 分批把一个列表切开，测试里能直接验证。 */
    fun <T> batches(items: List<T>, size: Int = LOOKUP_BATCH): List<List<T>> {
        if (size <= 0) return listOf(items)
        if (items.isEmpty()) return emptyList()
        return items.chunked(size)
    }

    /**
     * 完整一轮：扫本地 jar → 反查 → 问最新 → 出结论。
     *
     * 不抛异常到界面层以外的范围：单个文件读不了只记在 [ModUpdateResult.note] 里，
     * 网络层失败才整体抛出（否则用户会看到「全部已是最新」这种假好消息）。
     */
    suspend fun check(
        modsDir: File,
        gameVersion: String?,
        loader: LoaderKind,
        onProgress: (String) -> Unit = {},
    ): List<ModUpdateResult> = withContext(Dispatchers.IO) {
        val jars = modsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (jars.isEmpty()) return@withContext emptyList()

        onProgress("读取 " + jars.size + " 个文件")
        val failures = mutableListOf<String>()
        val locals = mutableListOf<LocalModFile>()
        for (jar in jars) {
            try {
                locals += LocalModFile(jar.name, jar, sha1Of(jar))
            } catch (e: IOException) {
                failures += (e.message ?: jar.name)
            }
        }

        onProgress("向 Modrinth 反查 " + locals.size + " 个文件")
        val lookups = mutableMapOf<String, ModVersionLookup>()
        for (batch in batches(locals)) {
            val body = ModInstaller.postJson(
                ModrinthMeta.versionFilesUrl(),
                versionFilesBody(batch.map { it.sha1 }),
            )
            val parsed = ModrinthMeta.parseVersionFiles(body)
            lookups += parsed.getOrNull()
                ?: throw IOException("解析反查结果失败：" + (parsed.reasonOrNull() ?: "响应格式不对"))
        }

        val known = locals.mapNotNull { lookups[it.sha1] }
        onProgress("查询 " + known.size + " 个模组的最新版本")
        val projects = resolveProjects(known.map { it.projectId }.distinct())

        locals.map { local ->
            val note = failures.firstOrNull { it.contains(local.fileName) }
            val lookup = lookups[local.sha1]
            if (lookup == null) {
                return@map ModUpdateResult(local.fileName, local.sha1, note = note)
            }
            val project = projects[lookup.projectId]
            val slug = project?.slug ?: lookup.projectId
            val latest = runCatching {
                ModrinthMeta.pickBest(ModInstaller.versionsOf(slug, gameVersion, loader))
            }.getOrNull()
            ModUpdateResult(local.fileName, local.sha1, lookup, latest, project, note)
        }
    }

    /** 批量把 project_id 换成人能看懂的项目（slug + 标题）。查不到就返回空表，不影响主流程。 */
    suspend fun resolveProjects(ids: List<String>): Map<String, ModProject> = withContext(Dispatchers.IO) {
        val clean = ids.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return@withContext emptyMap()
        val body = ModInstaller.fetchText(ModrinthMeta.projectsUrl(clean))
        ModrinthMeta.parseProjects(body).getOrNull().orEmpty()
    }
}
