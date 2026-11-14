package io.github.huang404yan.platypus.core.mods

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 一条冲突：某个模组在它的 Modrinth 版本里声明「不能和 [otherProjectId] 一起用」。
 *
 * 只描述「作者说的话」，不替作者判断谁对谁错 —— 有些 incompatible 是「功能重复」，
 * 两个都装着不一定崩；有些是真的抢同一个 mixin 目标，装了就进不去世界。界面上的措辞
 * 必须按「作者声明不兼容」来说，不能写成「这两个一定崩」。
 */
data class ModConflict(
    /** 声明冲突的本地文件名。 */
    val sourceFile: String,
    /** 声明冲突的模组显示名（查不到就用文件名）。 */
    val sourceTitle: String,
    /** 被点名不能共存的 project_id。 */
    val otherProjectId: String,
    /** 那个项目已经装在本地时的文件名；没装是 null。 */
    val otherFile: String? = null,
    /** 那个项目的显示名；查不到是 null。 */
    val otherTitle: String? = null,
    /** 对面也声明了不兼容（双向声明）。只有单向声明时是 false。 */
    val mutual: Boolean = false,
) {
    /** 界面上「被谁」那半句：优先给名字，其次给 id。 */
    val otherLabel: String get() = otherTitle?.takeIf { it.isNotBlank() } ?: otherProjectId

    /** 对面那个是否真的装在这个实例里。冲突卡片上「已经装着的」与「作者提到但没装的」要分开说。 */
    val present: Boolean get() = otherFile != null
}

/**
 * 一轮冲突检查的结果。
 *
 * [conflicts] 里既有「两边都装着」的（[ModConflict.present] = true），也有「作者说不兼容、
 * 但那个模组没装」的 —— 后者是预防性的提醒，不该和前一种混在一句里报。
 */
data class ModConflictReport(
    val conflicts: List<ModConflict> = emptyList(),
    /** 参与检查的 jar 数。 */
    val scanned: Int = 0,
    /** 其中 Modrinth 认得出来的（反查有命中）。 */
    val recognized: Int = 0,
    /** 读不了 / 反查失败的文件，如实列出来。 */
    val failures: List<String> = emptyList(),
) {
    val presentConflicts: List<ModConflict> get() = conflicts.filter { it.present }
    val potentialConflicts: List<ModConflict> get() = conflicts.filterNot { it.present }
}

/**
 * 找出「已装模组之间互相声明不兼容」的那几对。
 *
 * 为什么值得做：Modrinth 的依赖声明里有 dependency_type = "incompatible"，真实数据里并不罕见 ——
 * 2026-11-14 拿本机 34 个 jar 反查，8 个带依赖的模组里出现 3 条 incompatible
 * （Embeddium 对 Rubidium 与 TexTrue's Embeddium Options、Superb Warfare 对 GeckoAnimFix）。
 * 这类冲突的表现是「装完能启动、进世界黑屏或直接崩」，用户很难自己想到是两个优化模组打架。
 *
 * 为什么用 sha1 反查而不是读 jar 里的 modId：Modrinth 的项目 id 与 modId 不是一回事
 * （Sodium 的 modId 是 sodium，但很多模组不是），而冲突声明里点名的是 project_id。
 * sha1 是 Modrinth 自己给的主键，最不会认错 —— 与 [ModUpdateChecker] 同一套理由。
 *
 * 只读本地文件，唯一副作用是算 sha1 与一次（分批）网络请求。
 */
object ModConflictChecker {

    /** 与检查更新共用同一批量：Modrinth 的 /version_files 一次收多少没写死，100 是社区实践里稳妥的数。 */
    const val LOOKUP_BATCH = ModUpdateChecker.LOOKUP_BATCH

    /**
     * 纯比对，不联网 —— 单测直接盯这里。
     *
     * [files] 是本地 jar（[LocalModFile] 带 fileName 与 sha1），[lookups] 是 sha1 → 反查结果，
     * [projects] 是 project_id → 项目（用来把 id 变成人话）。
     *
     * 同一对被两边各声明一次（A 说不兼容 B，B 也说不兼容 A）会合成一条并标 [ModConflict.mutual]；
     * 只单向声明的就按「谁说的」各占一条 —— 单向声明里，谁被点名往往是那个该让步的。
     */
    fun cross(
        files: List<LocalModFile>,
        lookups: Map<String, ModVersionLookup>,
        projects: Map<String, ModProject> = emptyMap(),
    ): List<ModConflict> {
        if (files.isEmpty() || lookups.isEmpty()) return emptyList()

        // 一个项目可能装了多个文件（新旧版并存、或者把旧版改名成 .disabled 前），
        // 只要有一个还在，就算「这个项目在场」。这里按文件名排序取第一个，结果稳定。
        val byProject = mutableMapOf<String, LocalModFile>()
        for (file in files.sortedBy { it.fileName.lowercase() }) {
            val lookup = lookups[file.sha1] ?: continue
            byProject.putIfAbsent(lookup.projectId, file)
        }

        fun titleOf(projectId: String): String? =
            projects[projectId]?.title?.takeIf { it.isNotBlank() }

        // key = 两个 project_id 排序后拼起来，用来把双向声明合成一条
        val merged = LinkedHashMap<String, ModConflict>()
        for (file in files.sortedBy { it.fileName.lowercase() }) {
            val lookup = lookups[file.sha1] ?: continue
            for (otherId in lookup.incompatibleDependencies) {
                if (otherId.isBlank() || otherId == lookup.projectId) continue
                val key = listOf(lookup.projectId, otherId).sorted().joinToString("|")
                val other = byProject[otherId]
                val existing = merged[key]
                if (existing != null) {
                    // 对面也声明了：标成互斥，并尽量把另一侧的文件名补上
                    merged[key] = existing.copy(mutual = true)
                    continue
                }
                merged[key] = ModConflict(
                    sourceFile = file.fileName,
                    sourceTitle = titleOf(lookup.projectId) ?: file.fileName,
                    otherProjectId = otherId,
                    otherFile = other?.fileName,
                    otherTitle = titleOf(otherId),
                )
            }
        }

        // 已经装在本地的那几对排前面：那是「现在就可能起不来」，比「作者提到但没装」要紧
        return merged.values.sortedWith(
            compareByDescending<ModConflict> { it.present }
                .thenBy { it.sourceTitle.lowercase() }
                .thenBy { it.otherLabel.lowercase() },
        )
    }

    /**
     * 完整一轮：算 sha1 → 反查 → 换名 → 交叉比对。
     *
     * 与检查更新一样，单个文件读不了只记进 [ModConflictReport.failures]；
     * 网络整体失败才抛异常（否则会给出「没有冲突」这种假好消息）。
     */
    suspend fun check(
        modsDir: File,
        onProgress: (String) -> Unit = {},
    ): ModConflictReport = withContext(Dispatchers.IO) {
        val jars = modsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (jars.isEmpty()) return@withContext ModConflictReport()

        onProgress("读取 " + jars.size + " 个文件")
        val failures = mutableListOf<String>()
        val locals = mutableListOf<LocalModFile>()
        for (jar in jars) {
            try {
                locals += LocalModFile(jar.name, jar, ModUpdateChecker.sha1Of(jar))
            } catch (e: IOException) {
                failures += (e.message ?: jar.name)
            }
        }

        onProgress("向 Modrinth 反查 " + locals.size + " 个文件")
        val lookups = mutableMapOf<String, ModVersionLookup>()
        for (batch in ModUpdateChecker.batches(locals)) {
            val body = ModInstaller.postJson(
                ModrinthMeta.versionFilesUrl(),
                ModUpdateChecker.versionFilesBody(batch.map { it.sha1 }),
            )
            val parsed = ModrinthMeta.parseVersionFiles(body)
            lookups += parsed.getOrNull()
                ?: throw IOException("解析反查结果失败：" + (parsed.reasonOrNull() ?: "响应格式不对"))
        }

        val known = locals.mapNotNull { lookups[it.sha1] }
        // 冲突点名的 id 可能是「没装的那个」，所以要把两边都拿去换名字
        val wanted = (known.map { it.projectId } + known.flatMap { it.incompatibleDependencies })
            .filter { it.isNotBlank() }
            .distinct()
        onProgress("查询 " + wanted.size + " 个项目名")
        // 查不到名字只影响文案（会退回显示 project_id），不该让整轮冲突检查失败
        val projects = runCatching { ModUpdateChecker.resolveProjects(wanted) }.getOrNull().orEmpty()

        val conflicts = cross(locals, lookups, projects)
        ModConflictReport(
            conflicts = conflicts,
            scanned = locals.size,
            recognized = known.size,
            failures = failures,
        )
    }
}
