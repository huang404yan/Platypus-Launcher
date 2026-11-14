package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.instance.LoaderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 依赖树里要装的一个模组。
 *
 * [depth] 是层数而不是顺序：0 是用户自己点的那个，1 是它的直接前置，2 是前置的前置。
 * 界面按它缩进，一眼能看出「哪些是我要的、哪些是被捎带装的」。
 */
data class PlannedMod(
    val projectId: String,
    val slug: String,
    val title: String,
    val file: ModVersionFile,
    /** 0 = 用户自己点的那个，1 = 它的直接前置，2 = 前置的前置…… */
    val depth: Int,
    /** 谁需要它。根模组为空表；同一份依赖被多个模组需要时会有多个名字。 */
    val requiredBy: List<String> = emptyList(),
)

/** 想装但没排进计划的依赖，以及为什么。界面要如实列出来，不能只说「装好了」。 */
data class SkippedDependency(
    val projectId: String,
    val title: String,
    val reason: String,
    val requiredBy: String = "",
)

/**
 * 作者标成「可选」的依赖：文件已经挑好，但**不排进安装计划**。
 *
 * 之所以单独一个类型而不是塞进 [DependencyPlan.mods]：可选依赖不装也能启动，
 * 一旦混进 mods，[install] 会照单全下、界面也会把它算进「前置都装齐了」。
 * 它存在的意义只是让卡片能写一句「作者建议一起装 X」。
 */
data class OptionalDependency(
    val projectId: String,
    val slug: String,
    val title: String,
    val file: ModVersionFile,
    /** 谁建议装它。同一个人重复建议只留第一条。 */
    val suggestedBy: String = "",
)

/** 一棵展开好的依赖树。按 [mods] 的顺序装即可（根在前，前置在后）。 */
data class DependencyPlan(
    val mods: List<PlannedMod>,
    val skipped: List<SkippedDependency> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** 可选依赖：只提示，不装。见 [OptionalDependency]。 */
    val optional: List<OptionalDependency> = emptyList(),
) {
    /** 除根之外真正被捎带装的个数。 */
    val dependencyCount: Int get() = mods.count { it.depth > 0 }
}

/** 一个根：用户点的那个模组，版本已经挑好了。 */
data class DependencyRoot(
    val projectId: String?,
    val slug: String,
    val title: String,
    val file: ModVersionFile,
)

/**
 * 求解依赖时需要的两次网络查询。
 *
 * 抽成接口是为了单测能塞假数据：真实现要联网，而递归、去重、深度上限这些逻辑
 * 恰恰是最容易写错、又最不需要真的连 Modrinth 才能验证的部分。
 */
interface ModDependencySource {
    /** project_id 批量换 slug / 标题。 */
    suspend fun projects(ids: List<String>): Map<String, ModProject>

    /** 某个模组在当前游戏版本 + 加载器下的版本文件（新的在前）。 */
    suspend fun versionsOf(slug: String): List<ModVersionFile>
}

/**
 * 生产用的 [ModDependencySource]：把游戏版本与加载器固定住，再转给 Modrinth。
 *
 * 版本与加载器固定在这一层，是因为依赖树里每一层都必须和根模组吃同一套条件 ——
 * 给 1.20.1 Fabric 的模组去装一个 1.21 的库，装完照样起不来。
 */
class ModrinthDependencySource(
    private val gameVersion: String?,
    private val loader: LoaderKind,
) : ModDependencySource {

    override suspend fun projects(ids: List<String>): Map<String, ModProject> =
        ModUpdateChecker.resolveProjects(ids)

    override suspend fun versionsOf(slug: String): List<ModVersionFile> =
        ModInstaller.versionsOf(slug, gameVersion, loader)
}

data class FailedMod(val mod: PlannedMod, val reason: String)

data class DependencyInstallOutcome(
    val plan: DependencyPlan,
    val installed: List<PlannedMod> = emptyList(),
    val failed: List<FailedMod> = emptyList(),
) {
    /** 一个都没失败。界面可以直接说「装齐了」。 */
    val complete: Boolean get() = failed.isEmpty()
}

/**
 * 模组依赖求解：从「用户想装的那一个」出发，把前置一层层展开，再一起装。
 *
 * 为什么之前不做（见 ModInstaller 的文件头）：Modrinth 的依赖只给 project_id，
 * 要再查一次才知道它是什么、该选哪个版本，而猜错版本装一堆不兼容的库比不装更糟。
 * 这里把那两次查询做成确定的流程，并且**每一步失败都如实记下来**：
 * 查不到、没适配版本、已装过，分别有不同说法，不会汇总成一句「装完了」。
 *
 * 三条硬边界，都是为了不把界面卡死：
 * 1. 按 slug 去重 —— 同一个库被十几个模组依赖是常态，不能重复下载。
 * 2. [MAX_DEPTH] 层封顶 —— 依赖成环时（A 要 B、B 要 A）必须有停下的地方。
 * 3. [MAX_MODS] 个封顶 —— 有些整合包式模组能牵出上百个前置。
 */
object ModDependencyResolver {

    /** 依赖树最多展开多少层。正常模组两三层就到底了。 */
    const val MAX_DEPTH = 8

    /** 一次最多装多少个模组（含根）。 */
    const val MAX_MODS = 64

    /** 一次最多列多少个可选依赖。它们不下载，但每个都要查一次版本，同样得封顶。 */
    const val MAX_OPTIONAL = 24

    /**
     * 展开依赖树。只查询、不下载、不写盘，所以可以先给用户看计划再决定装不装。
     *
     * [installedProjectIds] 是实例里已经装了的 project_id：命中就跳过，避免同一份库
     * 在 mods/ 里躺两份（Forge 会因此报 duplicate mods 直接起不来）。
     */
    suspend fun solve(
        roots: List<DependencyRoot>,
        installedProjectIds: Set<String> = emptySet(),
        source: ModDependencySource,
        onStep: (String) -> Unit = {},
    ): DependencyPlan {
        val plan = mutableListOf<PlannedMod>()
        val skipped = mutableListOf<SkippedDependency>()
        val warnings = mutableListOf<String>()
        val indexBySlug = mutableMapOf<String, Int>()
        val queue = ArrayDeque<Request>()
        // 可选依赖单独排队：等必需树全部展开完再查，这样「它是不是已经在必需树里了」才判断得准
        val optionalQueue = ArrayDeque<Request>()

        for (root in roots) {
            val key = root.slug.lowercase()
            if (!indexBySlug.containsKey(key)) {
                indexBySlug[key] = plan.size
                plan += PlannedMod(
                    projectId = root.projectId.orEmpty(),
                    slug = root.slug,
                    title = root.title,
                    file = root.file,
                    depth = 0,
                )
            }
            for (depId in root.file.requiredDependencies) {
                queue += Request(depId, root.title, 1)
            }
            for (depId in root.file.optionalDependencies) {
                optionalQueue += Request(depId, root.title, 1)
            }
        }

        var rounds = 0
        while (queue.isNotEmpty()) {
            if (plan.size >= MAX_MODS) {
                warnings += "依赖超过 " + MAX_MODS + " 个，只展开了前 " + plan.size + " 个。"
                break
            }
            // 每轮处理一层：这样子变宽也不会因为成环无限循环
            if (++rounds > MAX_MODS * 4) {
                warnings += "依赖关系疑似成环，已停止展开。"
                break
            }
            val level = queue.toList()
            queue.clear()
            val ids = level.map { it.projectId }.distinct()
            onStep("解析 " + ids.size + " 个前置模组")
            val projects = runCatching { source.projects(ids) }.getOrNull().orEmpty()

            for (request in level) {
                if (plan.size >= MAX_MODS) {
                    warnings += "依赖超过 " + MAX_MODS + " 个，后面的没有再展开。"
                    break
                }
                val project = projects[request.projectId]
                // 查不到项目时退回用 project_id 当 slug：Modrinth 的 /project/{id}/version 也认
                val slug = project?.slug?.takeIf { it.isNotBlank() } ?: request.projectId
                val title = project?.title?.takeIf { it.isNotBlank() } ?: slug
                val key = slug.lowercase()

                val existing = indexBySlug[key]
                if (existing != null) {
                    // 已经排进计划：只把「谁还需要它」补上，依赖不再重复展开
                    val planned = plan[existing]
                    if (request.requiredBy.isNotBlank() && request.requiredBy !in planned.requiredBy) {
                        plan[existing] = planned.copy(requiredBy = planned.requiredBy + request.requiredBy)
                    }
                    continue
                }
                if (request.projectId in installedProjectIds) {
                    skipped += SkippedDependency(
                        request.projectId, title, "这个实例已经装了", request.requiredBy,
                    )
                    continue
                }
                if (request.depth > MAX_DEPTH) {
                    warnings += title + " 在依赖树里超过 " + MAX_DEPTH + " 层，没有继续展开。"
                    continue
                }

                val versions: List<ModVersionFile> = try {
                    source.versionsOf(slug)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    skipped += SkippedDependency(
                        request.projectId, title,
                        "查版本失败：" + (e.message ?: "网络错误"), request.requiredBy,
                    )
                    continue
                }
                val file = ModrinthMeta.pickBest(versions)
                if (file == null) {
                    skipped += SkippedDependency(
                        request.projectId, title, "没有适配当前游戏版本/加载器的文件", request.requiredBy,
                    )
                    continue
                }

                indexBySlug[key] = plan.size
                plan += PlannedMod(
                    projectId = request.projectId,
                    slug = slug,
                    title = title,
                    file = file,
                    depth = request.depth,
                    requiredBy = listOf(request.requiredBy).filter { it.isNotBlank() },
                )
                for (depId in file.requiredDependencies) {
                    queue += Request(depId, title, request.depth + 1)
                }
                // 可选依赖不再往下展开：作者的「可选」通常只挂在自己的直接关系上，
                // 递归下去会把整张图都拖进来，那等于把「可选」当「必需」用。
                for (depId in file.optionalDependencies) {
                    optionalQueue += Request(depId, title, request.depth + 1)
                }
            }
        }

        val optional = resolveOptional(
            requests = optionalQueue.map { it.projectId to it.requiredBy },
            plannedSlugs = indexBySlug.keys,
            installedProjectIds = installedProjectIds,
            source = source,
            onStep = onStep,
        )
        return DependencyPlan(mods = plan, skipped = skipped, warnings = warnings, optional = optional)
    }

    /**
     * 单独把一串「作者建议一起装」的 project_id 变成提示项。
     *
     * 给「这个模组没有必需前置、只标了可选依赖」那条路用：此时 [solve] 里没有根可展开，
     * 但用户仍然该看到「作者建议一起装 Sodium」。
     */
    suspend fun suggestOptional(
        rootTitle: String,
        projectIds: List<String>,
        source: ModDependencySource,
        installedProjectIds: Set<String> = emptySet(),
        plannedSlugs: Set<String> = emptySet(),
        onStep: (String) -> Unit = {},
    ): List<OptionalDependency> = resolveOptional(
        requests = projectIds.map { it to rootTitle },
        plannedSlugs = plannedSlugs,
        installedProjectIds = installedProjectIds,
        source = source,
        onStep = onStep,
    )

    /**
     * 把可选依赖换成「挑好文件的提示项」。
     *
     * 三条不做的事，都是为了不让「可选」变成负担：不递归展开它的前置、查不到就静默略过
     * （可选依赖本来就可以不装，没必要占「跳过」的眼球）、已经装了的也不再提。
     */
    private suspend fun resolveOptional(
        requests: List<Pair<String, String>>,
        plannedSlugs: Set<String>,
        installedProjectIds: Set<String>,
        source: ModDependencySource,
        onStep: (String) -> Unit,
    ): List<OptionalDependency> {
        if (requests.isEmpty()) return emptyList()
        // 同一个可选依赖可能被十几个模组建议，只查一次
        val picked = requests
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first }
            .take(MAX_OPTIONAL)
        if (picked.isEmpty()) return emptyList()
        val ids = picked.map { it.first }.distinct()
        onStep("解析 " + ids.size + " 个可选依赖")
        val projects = runCatching { source.projects(ids) }.getOrNull().orEmpty()
        val result = mutableListOf<OptionalDependency>()
        for ((projectId, suggestedBy) in picked) {
            if (projectId in installedProjectIds) continue
            val project = projects[projectId]
            val slug = project?.slug?.takeIf { it.isNotBlank() } ?: projectId
            // 必需树里已经有了：再列一遍等于让用户以为还缺东西
            if (slug.lowercase() in plannedSlugs) continue
            if (result.any { it.slug.equals(slug, ignoreCase = true) }) continue
            val versions: List<ModVersionFile> = try {
                source.versionsOf(slug)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            val file = ModrinthMeta.pickBest(versions) ?: continue
            result += OptionalDependency(
                projectId = projectId,
                slug = slug,
                title = project?.title?.takeIf { it.isNotBlank() } ?: slug,
                file = file,
                suggestedBy = suggestedBy,
            )
        }
        return result
    }

    private data class Request(val projectId: String, val requiredBy: String, val depth: Int)

    /**
     * 把一串 project_id 变成可以直接喂给 [solve] 的根。
     *
     * 「装完发现还缺前置」那条路只拿得到 project_id（Modrinth 的依赖表就是这么给的），
     * 而展开依赖树需要每个根都已经挑好文件。这一步就把缺的查询补上：id → slug/标题 → 版本 → 最佳文件。
     *
     * 返回第二项是这一步就排不进来的：查版本失败、或者没有适配当前版本+加载器的文件。
     * 它们和树里被跳过的依赖合并后一起显示，用户才知道「为什么点了装齐还是没齐」。
     */
    suspend fun rootsOf(
        projectIds: List<String>,
        source: ModDependencySource,
        onStep: (String) -> Unit = {},
    ): Pair<List<DependencyRoot>, List<SkippedDependency>> {
        val ids = projectIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyList<DependencyRoot>() to emptyList()
        onStep("查询 " + ids.size + " 个模组")
        val projects = runCatching { source.projects(ids) }.getOrNull().orEmpty()
        val roots = mutableListOf<DependencyRoot>()
        val skipped = mutableListOf<SkippedDependency>()
        for (id in ids) {
            val project = projects[id]
            // 查不到项目时退回用 project_id 当 slug：Modrinth 的 /project/{id}/version 也认
            val slug = project?.slug?.takeIf { it.isNotBlank() } ?: id
            val title = project?.title?.takeIf { it.isNotBlank() } ?: slug
            val versions: List<ModVersionFile> = try {
                source.versionsOf(slug)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                skipped += SkippedDependency(id, title, "查版本失败：" + (e.message ?: "网络错误"))
                continue
            }
            val file = ModrinthMeta.pickBest(versions)
            if (file == null) {
                skipped += SkippedDependency(id, title, "没有适配当前游戏版本/加载器的文件")
                continue
            }
            roots += DependencyRoot(
                projectId = project?.projectId?.takeIf { it.isNotBlank() } ?: id,
                slug = slug,
                title = title,
                file = file,
            )
        }
        return roots to skipped
    }

    /**
     * 按计划顺序下载安装。
     *
     * 单个失败不中断整棵树：一个前置的版本被作者撤了，不该让其余九个白装。
     * 全部结果都返回给界面，谁成功谁失败由界面如实显示。
     *
     * [installOne] 默认就是真的下载。做成参数是为了单测能验证「第 2 个失败时第 3 个还装」，
     * 而不用为了一个失败分支去搭桩服务器。
     */
    suspend fun install(
        gameDir: File,
        plan: DependencyPlan,
        onStep: (String) -> Unit = {},
        onProgress: (ModInstallProgress) -> Unit = {},
        installOne: suspend (PlannedMod) -> Unit = { mod ->
            ModInstaller.installFile(gameDir, mod.slug, mod.file) { onProgress(it) }
        },
    ): DependencyInstallOutcome {
        val installed = mutableListOf<PlannedMod>()
        val failed = mutableListOf<FailedMod>()
        for ((index, mod) in plan.mods.withIndex()) {
            onStep("装 " + mod.title + "（" + (index + 1) + "/" + plan.mods.size + "）")
            try {
                installOne(mod)
                installed += mod
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += FailedMod(mod, e.message ?: e::class.simpleName ?: "未知原因")
            }
        }
        return DependencyInstallOutcome(plan = plan, installed = installed, failed = failed)
    }

    /**
     * 实例里已经装了哪些模组（按 Modrinth 的 project_id 算）。
     *
     * 为什么不用 jar 里的 modId：modId 与 Modrinth 的项目不是一回事（很多模组的 modId
     * 跟 slug 完全不同），拿它去比对依赖表的 project_id 必然对不上。sha1 反查才是 Modrinth
     * 自己给的主键。
     *
     * 网络失败返回空集合而不是抛：算不出「已装什么」只会导致多装一份，不该让整个安装失败。
     */
    suspend fun installedProjectIds(modsDir: File): Set<String> = withContext(Dispatchers.IO) {
        val jars = modsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar", ignoreCase = true) }
            ?.toList()
            .orEmpty()
        if (jars.isEmpty()) return@withContext emptySet()
        val locals = runCatching { ModUpdateChecker.hashAll(jars) }.getOrNull().orEmpty()
        if (locals.isEmpty()) return@withContext emptySet()
        val ids = mutableSetOf<String>()
        for (batch in ModUpdateChecker.batches(locals)) {
            val body = runCatching {
                ModInstaller.postJson(
                    ModrinthMeta.versionFilesUrl(),
                    ModUpdateChecker.versionFilesBody(batch.map { it.sha1 }),
                )
            }.getOrNull() ?: continue
            ModrinthMeta.parseVersionFiles(body).getOrNull()?.values?.forEach { ids += it.projectId }
        }
        ids
    }
}
