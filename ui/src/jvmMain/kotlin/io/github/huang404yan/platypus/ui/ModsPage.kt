package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.InstanceScanner
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.instance.instanceKey
import io.github.huang404yan.platypus.core.instance.mergeInstances
import io.github.huang404yan.platypus.core.mods.DependencyInstallOutcome
import io.github.huang404yan.platypus.core.mods.DependencyPlan
import io.github.huang404yan.platypus.core.mods.ModDependencyResolver
import io.github.huang404yan.platypus.core.mods.ModInstallProgress
import io.github.huang404yan.platypus.core.mods.ModInstaller
import io.github.huang404yan.platypus.core.mods.ModProject
import io.github.huang404yan.platypus.core.mods.ModScanner
import io.github.huang404yan.platypus.core.mods.ModrinthDependencySource
import io.github.huang404yan.platypus.core.search.SearchTerm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 模组页：只负责「搜一个模组装进选中实例」。
 *
 * 以前这一页有两个分栏 ——「已装」和「浏览」。已装那一半（启停、删除、检查更新、多选更新、
 * 查冲突、搬隔离前的旧模组）本质上是对某一个具体实例的维护动作，放在下载页里就得先挑一遍
 * 实例再操作，跟「版本设置」里做的事完全重复。现在它们都在「版本设置 → Mod」，这一页只剩
 * 搜索与安装。
 *
 * 装完一个模组会顺手把它的前置依赖树递归展开（A→B→C 三层也展开），列在结果列表上面让用户
 * 点一下「一次装齐」。查不到、没适配版本、实例里已经装过的逐条说明原因 —— 不能只说「装好啦」
 * 然后进游戏崩在加载器里。
 */
@Composable
fun ModsPage(
    initialGameRoot: String? = null,
    backend: LauncherBackend? = null,
    /** 左栏「刷新」按钮的信号：每点一次加一，页面拿它当 LaunchedEffect 的 key。 */
    refreshTick: Int = 0,
    /**
     * 进来时优先选中的实例（instanceKey 或目录都认）。
     *
     * 来自详情页的「去下载 Mod」：用户是从某个具体版本点过来的，落在这页还得自己再挑一遍
     * 就白点了。找不到就按原来的规则退到第一个，不影响从侧栏直接进来的人。
     */
    focusInstanceKey: String = "",
) {
    val scope = rememberCoroutineScope()

    var rootText by remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }
    var instances by remember { mutableStateOf<List<Instance>>(emptyList()) }
    var scanError by remember { mutableStateOf("") }
    /** 选中的实例，用 instanceKey 记：切换目录后重新扫描，靠它把选择找回来。 */
    var selectedKey by remember { mutableStateOf("") }

    var gameVersion by remember { mutableStateOf("") }
    var loader by remember { mutableStateOf<LoaderKind?>(LoaderKind.FABRIC) }

    /**
     * 加载器是跟着哪个实例对齐的（instanceKey）。
     *
     * 用它而不是每次刷新都覆盖：用户手动轮换过加载器（比如拿 Fabric 实例去搜一个 Forge 包）
     * 的时候，刷一下列表不该把人家刚选的改回去；换了实例才重新对齐。
     */
    var loaderInstanceKey by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    var results by remember { mutableStateOf<List<ModProject>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }

    var installingSlug by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<ModInstallProgress?>(null) }
    /** 安装结果提示。 */
    var notice by remember { mutableStateOf("") }

    /**
     * 装完模组后展开的前置依赖树。
     *
     * 以前这里只存一串 project_id（缺什么），界面上是「还差 N 个前置」加一个按钮，按下去才
     * 逐个装 —— 前置自己的前置没人管，装完照样崩。现在是先递归查出整棵树，连「前置的前置」
     * 「查不到 / 没适配版本 / 已装过」都摆出来，再一次性装。
     */
    var depPlan by remember { mutableStateOf<DependencyPlan?>(null) }
    /** 求解依赖树的过程中正在做什么（查询 N 个、解析 M 个），空串表示没在忙。 */
    var depStep by remember { mutableStateOf("") }
    var depsInstalling by remember { mutableStateOf(false) }

    /** 扫游戏目录里的实例清单，尽量保住当前选择。 */
    suspend fun rescanInstances(): List<Instance> {
        if (rootText.isBlank()) {
            instances = emptyList()
            return emptyList()
        }
        val root = File(rootText.trim())
        val scanned = withContext(Dispatchers.IO) { runCatching { InstanceScanner.scan(root) }.getOrNull() }
        val registered = withContext(Dispatchers.IO) {
            runCatching { backend?.instances?.list() }.getOrNull().orEmpty()
        }
        scanError = when {
            scanned == null -> "读不了这个目录：" + root.absolutePath
            !scanned.isGameDirectory -> "这里不是游戏目录（缺少 versions/、libraries/ 或 assets/）"
            else -> ""
        }
        val merged = mergeInstances(registered, scanned?.instances.orEmpty())
        instances = merged
        // 从详情页点过来时把那个实例选上。只在当前选择落空时才用 focus ——
        // 否则用户自己挑过之后每刷一次列表就被拽回去。
        if (selectedKey.isBlank() && focusInstanceKey.isNotBlank()) {
            val hit = merged.firstOrNull {
                instanceKey(it) == focusInstanceKey ||
                    it.directory.equals(focusInstanceKey, ignoreCase = true)
            }
            if (hit != null) selectedKey = instanceKey(hit)
        }
        if (merged.none { instanceKey(it) == selectedKey }) {
            // 选择失效就退到第一个：先按名字排，原版实例排后面（原版装不了模组）
            selectedKey = merged
                .sortedBy { it.loader == LoaderKind.VANILLA }
                .firstOrNull()
                ?.let { instanceKey(it) }
                .orEmpty()
        }
        return merged
    }

    /**
     * 把选中实例的游戏版本与加载器带进搜索条件。
     *
     * 加载器必须跟着实例走：默认是 Fabric，切到一个 NeoForge 实例还不改加载器的话，Modrinth
     * 会把 fabric 版的包当成「最新版」—— 装下去这个实例直接起不来。只在换了实例时对齐一次，
     * 用户手动轮换过的选择不覆盖。
     */
    fun syncSearchContext() {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey } ?: return
        if (loaderInstanceKey != instanceKey(target)) {
            loaderInstanceKey = instanceKey(target)
            loaderOf(target.loader)?.let { loader = it }
        }
        // 实例自己记着 gameVersion，比让用户手打靠谱得多；用户改过的值不覆盖。
        if (gameVersion.isBlank() && target.gameVersion.isNotBlank()) {
            gameVersion = target.gameVersion
        }
    }

    suspend fun search() {
        val text = query.trim()
        if (text.isBlank()) {
            searchError = "先输入要搜的模组名（如 sodium、jei）。"
            return
        }
        if (gameVersion.isBlank()) {
            // 留空会让 Modrinth 的 versions facet 被省略，pickBest 取到最新版模组（例如 mc26.3
            // 的 Sodium）—— 装进去要等启动崩溃才发现。宁可拦住。
            searchError = if (instances.isEmpty()) {
                "先在游戏目录里选一个实例：没有实例就不知道要给哪个版本找模组。"
            } else {
                "先填游戏版本（如 1.20.1）。留空会搜到最新版模组，跟你的游戏对不上。"
            }
            return
        }
        searching = true
        searchError = ""
        try {
            results = withContext(Dispatchers.IO) {
                ModInstaller.search(text, gameVersion.trim().ifBlank { null }, loader ?: LoaderKind.VANILLA)
            }
            searched = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results = emptyList()
            searched = true
            searchError = e.message ?: e.toString()
        } finally {
            searching = false
        }
    }

    /**
     * 展开「装完这个模组还差什么」的依赖树。
     *
     * 只查询、不下载，所以结果可以先摆在界面上让用户看一眼再决定。递归展开是必须的：Sodium
     * 要 Fabric API，而 Fabric API 自己不缺东西；但很多模组是 A→B→C 三层，只装直接前置的话
     * 进游戏照样崩。
     */
    suspend fun solveDependencies(
        rootTitle: String,
        missing: List<String>,
        instanceDir: File,
        kind: LoaderKind,
        suggestedOptional: List<String> = emptyList(),
    ) {
        if (missing.isEmpty() && suggestedOptional.isEmpty()) return
        depStep = "查前置…"
        try {
            val plan = withContext(Dispatchers.IO) {
                val source = ModrinthDependencySource(gameVersion.trim().ifBlank { null }, kind)
                val modsDir = ModScanner.modsDir(instanceDir)
                if (missing.isEmpty()) {
                    // 没有必需前置，只有作者标的可选依赖：以前这里直接 return，结果是
                    // 「装完 Sodium 这种没前置的模组，可选提示全丢了」。可选依赖不进安装计划，
                    // 所以这张卡只有「先不管」是可点的。
                    DependencyPlan(
                        mods = emptyList(),
                        optional = ModDependencyResolver.suggestOptional(
                            rootTitle = rootTitle,
                            projectIds = suggestedOptional,
                            source = source,
                            installedProjectIds = ModDependencyResolver.installedProjectIds(modsDir),
                        ) { depStep = it },
                    )
                } else {
                    val (roots, skippedRoots) = ModDependencyResolver.rootsOf(missing, source) { depStep = it }
                    // 已经装过的（含刚装进去的那个根）不再下一遍：mods/ 里躺两份同名库，
                    // Forge 会直接起不来
                    val installedIds = ModDependencyResolver.installedProjectIds(modsDir)
                    val solved = ModDependencyResolver.solve(roots, installedIds, source) { depStep = it }
                    // 根这一层自己带着「谁缺它」的信息：rootsOf 只拿得到一串 project_id，
                    // 不知道是哪个模组报出来的，这里补上，卡片上才不会只显示一串包名。
                    solved.copy(
                        mods = solved.mods.map { mod ->
                            if (mod.depth == 0) mod.copy(requiredBy = listOf(rootTitle)) else mod
                        },
                        skipped = skippedRoots.map {
                            if (it.requiredBy.isBlank()) it.copy(requiredBy = rootTitle) else it
                        } + solved.skipped,
                        // 可选依赖同理：根自己声明的可选依赖，rootsOf 这一层还不知道是谁提的
                        optional = solved.optional.map {
                            if (it.suggestedBy.isBlank()) it.copy(suggestedBy = rootTitle) else it
                        },
                    )
                }
            }
            // 一样都没查出来（既没有要装的前置，也没有可选的）就别弹一张空卡片占位置
            depPlan = plan.takeIf { it.mods.isNotEmpty() || it.skipped.isNotEmpty() || it.optional.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 查不出依赖不该把「装好啦」这条提示也吞掉，单独说一句
            notice = (notice + " ").trim() + rootTitle + " 的前置没查出来：" + (e.message ?: e.toString())
        } finally {
            depStep = ""
        }
    }

    suspend fun install(project: ModProject) {
        val target = instances.firstOrNull { instanceKey(it) == selectedKey }
        if (target == null) {
            notice = "先选一个实例：模组装进实例自己的 mods/，没有实例就没有目标目录。"
            return
        }
        if (gameVersion.isBlank()) {
            notice = "先填游戏版本，否则装进来的模组跟你的游戏对不上。"
            return
        }
        val kind = loader ?: LoaderKind.FABRIC
        val instanceDir = InstanceLayouts.of(target).gameDir
        installingSlug = project.slug
        progress = null
        notice = ""
        try {
            val result = withContext(Dispatchers.IO) {
                ModInstaller.install(
                    gameDir = instanceDir,
                    slug = project.slug,
                    gameVersion = gameVersion.trim().ifBlank { null },
                    loader = kind,
                ) { p: ModInstallProgress -> progress = p }
            }
            notice = "已装进 " + target.name + "：" + result.entry.title + " " + result.version +
                "（" + formatSizeShort(result.downloadedBytes) + "）" +
                when {
                    result.requiredDependencies.isNotEmpty() ->
                        "。它还要 " + result.requiredDependencies.size +
                            " 个前置，正在展开整棵依赖树，展开后在下面点「一次装齐」。"
                    result.optionalDependencies.isNotEmpty() ->
                        "。它自己就能跑，作者另外标了 " + result.optionalDependencies.size +
                            " 个建议一起装的，已列在下面的卡片里。"
                    else -> "。"
                }
            // 把整棵前置依赖树展开，卡片就摆在这个页面，用户当场就能装齐。
            // 只展开直接前置是不够的：前置自己还可能有前置，漏一层进游戏照样崩。
            solveDependencies(
                rootTitle = result.entry.title,
                missing = result.requiredDependencies,
                instanceDir = instanceDir,
                kind = kind,
                suggestedOptional = result.optionalDependencies,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = "装 " + project.title + " 失败：" + (e.message ?: e.toString())
        } finally {
            installingSlug = ""
            progress = null
        }
    }

    /**
     * 把已经展开好的依赖树一次装齐。
     *
     * 装之前这棵树是 [ModDependencyResolver.solve] 查出来的（见 [install]），所以这里不再重复
     * 查版本，只下载。单个失败不中断整棵树：一个前置的版本被作者撤了，不该让其余九个白装；
     * 失败项留在 [DependencyInstallOutcome.failed] 里如实显示。
     */
    suspend fun installDependencies() {
        val plan = depPlan ?: return
        val target = instances.firstOrNull { instanceKey(it) == selectedKey } ?: return
        val dir = InstanceLayouts.of(target).gameDir
        depsInstalling = true
        depStep = ""
        notice = ""
        var outcome: DependencyInstallOutcome? = null
        try {
            outcome = withContext(Dispatchers.IO) {
                ModDependencyResolver.install(
                    gameDir = dir,
                    plan = plan,
                    onStep = { depStep = it },
                    onProgress = { progress = it },
                )
            }
            val done = outcome.installed
            val failed = outcome.failed
            notice = buildString {
                if (done.isNotEmpty()) {
                    append("装了 ").append(done.size).append(" 个：")
                        .append(done.joinToString("、") { it.title }).append("。")
                }
                if (failed.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("还有 ").append(failed.size).append(" 个没装上：")
                    append(failed.joinToString("；") { it.mod.title + "（" + it.reason + "）" })
                }
                if (failed.isEmpty() && plan.skipped.isEmpty()) append("前置都装齐了。")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = "装前置失败：" + (e.message ?: e.toString())
        } finally {
            depsInstalling = false
            depStep = ""
            progress = null
            // 装成功的那批不再需要提示；有失败就把它们留在计划里，让用户能再点一次
            val failed = outcome?.failed.orEmpty()
            depPlan = if (failed.isEmpty()) null else plan.copy(
                mods = failed.map { it.mod },
                skipped = emptyList(),
            )
        }
    }

    LaunchedEffect(Unit) {
        val repository = backend?.settings
        if (repository != null) {
            val stored = withContext(Dispatchers.IO) { runCatching { repository.load() }.getOrNull() }
            if (stored != null && initialGameRoot.isNullOrBlank() && stored.defaultGameRoot.isNotBlank()) {
                rootText = stored.defaultGameRoot
            }
        }
        if (rootText.isNotBlank()) {
            rescanInstances()
            syncSearchContext()
        }
    }

    // 左栏那个「↻」：重扫目录里的实例。tick 为 0 是首次组合，不用重复扫。
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0 && rootText.isNotBlank()) {
            rescanInstances()
            syncSearchContext()
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("模组", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "从 Modrinth 搜一个模组，装进选中实例自己的 mods/。已装的清单在「版本设置 → Mod」里。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = rootText,
                onValueChange = { rootText = it },
                modifier = Modifier.weight(1f),
                label = { Text("游戏目录（.minecraft）") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    pickDirectory(rootText)?.let {
                        rootText = it.absolutePath
                        scope.launch { rescanInstances(); syncSearchContext() }
                    }
                },
            ) { Text("选择…") }
        }

        InstancePicker(
            instances = instances,
            selectedKey = selectedKey,
            loading = instances.isEmpty() && scanError.isBlank(),
            onSelect = {
                selectedKey = it
                // 换实例等于换游戏版本，之前自动带出的版本要跟着换；
                // 用户手填过的内容在这里没法分辨，直接按新实例重置更可预期。
                gameVersion = ""
                // 换实例要重新对齐加载器，见 syncSearchContext 的说明
                loaderInstanceKey = ""
                results = emptyList()
                searched = false
                notice = ""
                depPlan = null
                syncSearchContext()
            },
        )
        if (scanError.isNotBlank()) ModHint(scanError, error = true)

        BrowseTab(
            modifier = Modifier.fillMaxWidth().weight(1f),
            query = query,
            onQueryChange = { query = it },
            gameVersion = gameVersion,
            onGameVersionChange = { gameVersion = it },
            loader = loader,
            onCycleLoader = { loader = nextLoader(loader) },
            searching = searching,
            onSearch = { scope.launch { search() } },
            searchError = searchError,
            notice = notice,
            installingSlug = installingSlug,
            progress = progress,
            results = results,
            searched = searched,
            onInstall = { project -> scope.launch { install(project) } },
            depPlan = depPlan,
            depStep = depStep,
            depsInstalling = depsInstalling,
            onInstallDeps = { scope.launch { installDependencies() } },
            onDismissDeps = { depPlan = null },
        )
    }
}

/** 搜索与安装：Modrinth 搜索 + 装进当前实例。结果列表占满整屏。 */
@Composable
private fun BrowseTab(
    modifier: Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    gameVersion: String,
    onGameVersionChange: (String) -> Unit,
    loader: LoaderKind?,
    onCycleLoader: () -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    searchError: String,
    notice: String,
    installingSlug: String,
    progress: ModInstallProgress?,
    results: List<ModProject>,
    searched: Boolean,
    onInstall: (ModProject) -> Unit,
    depPlan: DependencyPlan?,
    depStep: String,
    depsInstalling: Boolean,
    onInstallDeps: () -> Unit,
    onDismissDeps: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                label = { Text("在 Modrinth 搜模组（如 sodium、jei、iris）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = gameVersion,
                onValueChange = onGameVersionChange,
                modifier = Modifier.width(180.dp),
                label = { Text("游戏版本（必填）") },
                singleLine = true,
            )
            OutlinedButton(onClick = onCycleLoader) {
                Text("加载器：" + loaderLabel(loader))
            }
            Button(onClick = onSearch, enabled = !searching) {
                Text(if (searching) "搜索中…" else "搜索")
            }
        }

        // 中文查询时先解释「为什么搜不到」，再给可点的英文候选：
        // 直接回一句「没搜到」会让人以为启动器坏了。
        ChineseSearchHint(query = query, kind = SearchTerm.Kind.MOD, onPick = onQueryChange)

        if (notice.isNotBlank()) ModHint(notice)
        if (searchError.isNotBlank()) ModHint("Modrinth：" + searchError, error = true)
        if (installingSlug.isNotBlank()) {
            ModHint("正在装 " + installingSlug + "…" + progressDetail(progress))
        }

        if (depPlan != null) {
            DependencyPlanCard(
                plan = depPlan,
                step = depStep,
                busy = depsInstalling,
                onInstall = onInstallDeps,
                onDismiss = onDismissDeps,
            )
        }

        Card(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (results.isEmpty()) {
                    ModHint(if (searched) "没搜到。换个关键词，或放宽版本 / 加载器过滤。" else "输入名字后点「搜索」。")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(results, key = { it.projectId }) { project ->
                            SearchResultRow(
                                project = project,
                                busy = installingSlug.isNotBlank(),
                                onInstall = { onInstall(project) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 实例选择条。
 *
 * 横向滚动而不是下拉：实例通常三五个，一眼能看全，也省了展开/收起的状态。
 * 原版实例排后面 —— 它们装不了模组，排在前面只会让人点了再发现不行。
 */
@Composable
private fun InstancePicker(
    instances: List<Instance>,
    selectedKey: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (instances.isEmpty()) {
                if (loading) "正在找实例…" else "没有实例。先去「实例」页新建一个，或者把游戏目录改成装过版本的那个。"
            } else {
                "装在哪个实例里（共 " + instances.size + " 个）："
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (instances.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(instances.sortedBy { it.loader == LoaderKind.VANILLA }, key = { instanceKey(it) }) { instance ->
                    val key = instanceKey(instance)
                    val active = key == selectedKey
                    OutlinedButton(
                        onClick = { onSelect(key) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            instance.name + "  ·  " + instance.gameVersion +
                                (if (instance.loader == LoaderKind.VANILLA) "  原版" else "  " + loaderLabel(instance.loader)),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

internal fun nextLoader(current: LoaderKind?): LoaderKind? = when (current) {
    LoaderKind.FABRIC -> LoaderKind.FORGE
    LoaderKind.FORGE -> LoaderKind.QUILT
    LoaderKind.QUILT -> LoaderKind.NEOFORGE
    else -> LoaderKind.FABRIC
}

/** 实例记着的加载器 → 搜索用的加载器。原版实例没有加载器，返回 null 表示不动当前选择。 */
internal fun loaderOf(kind: LoaderKind): LoaderKind? = when (kind) {
    LoaderKind.VANILLA, LoaderKind.OTHER -> null
    else -> kind
}

internal fun loaderLabel(kind: LoaderKind?): String = when (kind) {
    LoaderKind.FABRIC -> "Fabric"
    LoaderKind.FORGE -> "Forge"
    LoaderKind.QUILT -> "Quilt"
    LoaderKind.NEOFORGE -> "NeoForge"
    else -> "不限"
}

@Composable
private fun SearchResultRow(project: ModProject, busy: Boolean, onInstall: () -> Unit) {
    // 照 Modrinth 网页那种列表：左边一张方图标，右边标题 + 下载量 + 简介 + 装。
    // 有图的条目一眼就能认出是哪个模组，纯文字列表必须逐个读完标题才知道。
    Card {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModIcon(project, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        project.downloadsLabel + " 次下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                project.author?.let {
                    Text("作者 " + it, style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    project.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        project.slug,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onInstall, enabled = !busy) { Text("装进 mods/") }
                }
            }
        }
    }
}
@Composable
internal fun ModHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun progressDetail(progress: ModInstallProgress?): String {
    if (progress == null) return ""
    return if (progress.total > 0L) {
        "（" + progress.step.label + " " + (progress.fraction * 100).toInt() + "%）"
    } else {
        "（" + progress.step.label + "）"
    }
}

internal fun formatSizeShort(bytes: Long): String = when {
    bytes <= 0L -> "-"
    bytes < 1024L -> bytes.toString() + " B"
    bytes < 1024L * 1024L -> (bytes / 1024L).toString() + " KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/**
 * 展开好的前置依赖树：按层缩进列出要装什么，被跳过的说清为什么，再给一个「一次装齐」。
 *
 * 为什么要按层列而不是只说「还差 3 个」：前置还可能有前置，用户看着一串扁平的包名
 * 分不清哪个是自己要的、哪个是被捎带的；装失败时也需要知道是哪一层断了。
 * 跳过的项（已装过 / 没有适配版本 / 查不到）单独一段，否则用户会以为「点了装齐」就一定齐。
 */
@Composable
private fun DependencyPlanCard(
    plan: DependencyPlan,
    step: String,
    busy: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 只有可选依赖时不存在「保持顺序」，标题得换，否则会写成「前置依赖 · 0 个」
            if (plan.mods.isNotEmpty()) {
                Text(
                    "前置依赖 · " + plan.mods.size + " 个要保持顺序安装",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            plan.mods.forEach { mod ->
                Text(
                    // 每层缩进两个全角空格，depth 0 就是用户自己点的那个
                    "　".repeat(mod.depth.coerceAtLeast(0)) +
                        (if (mod.depth > 0) "└ " else "") +
                        mod.title + " " + mod.file.versionNumber,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (mod.requiredBy.isNotEmpty()) {
                    Text(
                        "　".repeat(mod.depth + 1) + "被 " + mod.requiredBy.joinToString("、") + " 需要",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (plan.optional.isNotEmpty()) {
                Text(
                    "可选依赖 · " + plan.optional.size + " 个（不装也能启动）：",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                plan.optional.forEach { dep ->
                    Text(
                        "· " + dep.title + " " + dep.file.versionNumber +
                            if (dep.suggestedBy.isNotBlank()) " —— " + dep.suggestedBy + " 建议一起装" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (plan.skipped.isNotEmpty()) {
                Text(
                    "跳过 " + plan.skipped.size + " 个：",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                plan.skipped.forEach { dep ->
                    Text(
                        "· " + dep.title + " —— " + dep.reason +
                            if (dep.requiredBy.isNotBlank()) "（" + dep.requiredBy + " 需要）" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            plan.warnings.forEach { warning ->
                Text("· " + warning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                "不带前置启动会崩，或者进游戏后功能是空的；标了可选的不装不影响启动。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (step.isNotBlank()) {
                Text(step, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("", modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !busy) { Text("先不管") }
                // 只有可选依赖时没有「装齐」这回事：留一个灰着的按钮比不留更让人疑惑
                if (plan.mods.isNotEmpty()) {
                    OutlinedButton(onClick = onInstall, enabled = !busy) {
                        Text(if (busy) "安装中…" else "一次装齐")
                    }
                }
            }
        }
    }
}
