package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.instance.LoaderKind
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModDependencyResolverTest {

    private fun tempDir(name: String): File =
        Files.createTempDirectory("platypus-deps-" + name).toFile().also { it.deleteOnExit() }

    private fun project(id: String, slug: String, title: String = slug) =
        ModProject(slug = slug, title = title, description = "", author = null, downloads = 0L, projectId = id)

    private fun file(
        slug: String,
        version: String = "1.0",
        deps: List<String> = emptyList(),
        optional: List<String> = emptyList(),
    ) =
        ModVersionFile(
            projectSlug = slug,
            versionNumber = version,
            fileName = slug + "-" + version + ".jar",
            url = "https://example.invalid/" + slug + "-" + version + ".jar",
            sha1 = "a".repeat(40),
            size = 10L,
            releaseType = "release",
            requiredDependencies = deps,
            optionalDependencies = optional,
        )

    /**
     * 假的 Modrinth。
     *
     * 单个依赖树最多查三四次，用假实现能把「查不到 / 抛异常 / 没适配版本」这些
     * 真实网络里很难稳定复现的分支都测到。
     */
    private class FakeSource(
        private val projects: Map<String, ModProject>,
        private val versions: Map<String, List<ModVersionFile>>,
    ) : ModDependencySource {
        val projectCalls = mutableListOf<List<String>>()
        val versionCalls = mutableListOf<String>()
        var failProjects = false
        var failVersionsFor: Set<String> = emptySet()

        override suspend fun projects(ids: List<String>): Map<String, ModProject> {
            projectCalls += ids
            if (failProjects) throw IOException("连不上 Modrinth")
            return projects.filterKeys { it in ids }
        }

        override suspend fun versionsOf(slug: String): List<ModVersionFile> {
            versionCalls += slug
            if (slug in failVersionsFor) throw IOException("读 " + slug + " 失败")
            return versions[slug].orEmpty()
        }
    }

    @Test
    fun `没有前置模组时计划里只有根`() {
        val source = FakeSource(
            projects = mapOf("P1" to project("P1", "sodium", "Sodium")),
            versions = mapOf("sodium" to listOf(file("sodium"))),
        )
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(DependencyRoot("P1", "sodium", "Sodium", file("sodium"))),
                source = source,
            )
        }
        assertEquals(1, plan.mods.size)
        assertEquals(0, plan.mods[0].depth)
        assertEquals(0, plan.dependencyCount)
        assertTrue(plan.skipped.isEmpty())
        assertTrue(source.versionCalls.isEmpty(), "根版本已经由调用方挑好，不该再查一次")
    }

    @Test
    fun `一层前置会被展开并挑出可装的文件`() {
        val source = FakeSource(
            projects = mapOf("P1" to project("P1", "sodium"), "P2" to project("P2", "fabric-api", "Fabric API")),
            versions = mapOf(
                "sodium" to listOf(file("sodium", deps = listOf("P2"))),
                "fabric-api" to listOf(file("fabric-api", "0.92.0")),
            ),
        )
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(DependencyRoot("P1", "sodium", "Sodium", file("sodium", deps = listOf("P2")))),
                source = source,
            )
        }
        assertEquals(2, plan.mods.size)
        assertEquals("fabric-api", plan.mods[1].slug)
        assertEquals(1, plan.mods[1].depth)
        assertEquals("0.92.0", plan.mods[1].file.versionNumber)
        assertEquals(listOf("Sodium"), plan.mods[1].requiredBy)
        assertEquals(1, plan.dependencyCount)
    }

    @Test
    fun `前置的前置也会被展开`() {
        val source = FakeSource(
            projects = mapOf(
                "P1" to project("P1", "iris"),
                "P2" to project("P2", "sodium", "Sodium"),
                "P3" to project("P3", "fabric-api", "Fabric API"),
            ),
            versions = mapOf(
                "sodium" to listOf(file("sodium", deps = listOf("P3"))),
                "fabric-api" to listOf(file("fabric-api")),
            ),
        )
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(DependencyRoot("P1", "iris", "Iris", file("iris", deps = listOf("P2")))),
                source = source,
            )
        }
        assertEquals(listOf("iris", "sodium", "fabric-api"), plan.mods.map { it.slug })
        assertEquals(listOf(0, 1, 2), plan.mods.map { it.depth })
        assertEquals(listOf("Iris"), plan.mods[1].requiredBy)
        assertEquals(listOf("Sodium"), plan.mods[2].requiredBy)
    }

    @Test
    fun `同一个前置被两个模组需要时只排一次并记下两个来源`() {
        val source = FakeSource(
            projects = mapOf("P2" to project("P2", "fabric-api", "Fabric API")),
            versions = mapOf("fabric-api" to listOf(file("fabric-api"))),
        )
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(
                    DependencyRoot("P1", "sodium", "Sodium", file("sodium", deps = listOf("P2"))),
                    DependencyRoot("P3", "iris", "Iris", file("iris", deps = listOf("P2"))),
                ),
                source = source,
            )
        }
        assertEquals(listOf("sodium", "iris", "fabric-api"), plan.mods.map { it.slug })
        assertEquals(listOf("Sodium", "Iris"), plan.mods[2].requiredBy)
        assertEquals(1, plan.mods.count { it.slug == "fabric-api" })
    }

    @Test
    fun `依赖成环时能停下来`() {
        // A 依赖 B，B 又依赖 A —— 真实生态里作者互相引用是有的，解不出来不能死循环
        val source = FakeSource(
            projects = mapOf(
                "P1" to project("P1", "a-mod", "A"),
                "P2" to project("P2", "b-mod", "B"),
            ),
            versions = mapOf(
                "b-mod" to listOf(file("b-mod", deps = listOf("P1"))),
            ),
        )
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(DependencyRoot("P1", "a-mod", "A", file("a-mod", deps = listOf("P2")))),
                source = source,
            )
        }
        assertEquals(listOf("a-mod", "b-mod"), plan.mods.map { it.slug })
        assertTrue(source.versionCalls.none { it == "a-mod" }, "根不该被当成依赖再查一遍版本")
    }

    @Test
    fun `已经装过的前置会被跳过并说明原因`() {
        val source = FakeSource(
            projects = mapOf("P2" to project("P2", "fabric-api", "Fabric API")),
            versions = mapOf("fabric-api" to listOf(file("fabric-api"))),
        )
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(DependencyRoot("P1", "sodium", "Sodium", file("sodium", deps = listOf("P2")))),
                installedProjectIds = setOf("P2"),
                source = source,
            )
        }
        assertEquals(1, plan.mods.size)
        assertEquals(1, plan.skipped.size)
        assertEquals("Fabric API", plan.skipped[0].title)
        assertTrue(plan.skipped[0].reason.contains("已经装了"), plan.skipped[0].reason)
        assertTrue(source.versionCalls.isEmpty())
    }

    @Test
    fun `查版本抛异常时记下来但不中断整棵树`() {
        val source = FakeSource(
            projects = mapOf(
                "P2" to project("P2", "bad-lib", "Bad"),
                "P3" to project("P3", "good-lib", "Good"),
            ),
            versions = mapOf("good-lib" to listOf(file("good-lib"))),
        )
        source.failVersionsFor = setOf("bad-lib")
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(
                    DependencyRoot("P1", "sodium", "Sodium", file("sodium", deps = listOf("P2", "P3"))),
                ),
                source = source,
            )
        }
        assertEquals(listOf("sodium", "good-lib"), plan.mods.map { it.slug })
        assertEquals(1, plan.skipped.size)
        assertTrue(plan.skipped[0].reason.startsWith("查版本失败"), plan.skipped[0].reason)
    }

    @Test
    fun `没有适配版本的前置会进 skipped`() {
        val source = FakeSource(
            projects = mapOf("P2" to project("P2", "old-lib", "Old")),
            versions = emptyMap(),
        )
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(DependencyRoot("P1", "sodium", "Sodium", file("sodium", deps = listOf("P2")))),
                source = source,
            )
        }
        assertEquals(1, plan.mods.size)
        assertEquals(1, plan.skipped.size)
        assertTrue(plan.skipped[0].reason.contains("没有适配"), plan.skipped[0].reason)
    }

    @Test
    fun `查不到项目时退回 project_id 当 slug 继续试`() {
        // projects 接口偶尔会漏（比如项目刚被作者删了或刚发布），此时不能直接放弃
        val source = FakeSource(projects = emptyMap(), versions = mapOf("P2" to listOf(file("P2"))))
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(DependencyRoot("P1", "sodium", "Sodium", file("sodium", deps = listOf("P2")))),
                source = source,
            )
        }
        assertEquals(listOf("sodium", "P2"), plan.mods.map { it.slug })
        assertTrue(source.versionCalls.contains("P2"))
    }

    @Test
    fun `项目查询整体失败时不会崩`() {
        val source = FakeSource(
            projects = mapOf("P2" to project("P2", "fabric-api")),
            versions = mapOf("P2" to listOf(file("P2"))),
        )
        source.failProjects = true
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(DependencyRoot("P1", "sodium", "Sodium", file("sodium", deps = listOf("P2")))),
                source = source,
            )
        }
        // 查不到项目名就退回 id，装还是能装
        assertEquals(listOf("sodium", "P2"), plan.mods.map { it.slug })
    }

    @Test
    fun `深度超过上限时停止展开`() {
        val chain = (1..12).map { "P" + it }
        val projects = chain.associateWith { id -> project(id, "mod" + id.drop(1)) }
        val versions = mutableMapOf<String, List<ModVersionFile>>()
        for (i in 1 until chain.size) {
            // modN 依赖 modN+1，一路串下去
            versions["mod" + (i + 1)] = listOf(
                file("mod" + (i + 1), deps = if (i + 2 <= chain.size) listOf("P" + (i + 2)) else emptyList()),
            )
        }
        val source = FakeSource(projects, versions)
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(DependencyRoot("P1", "mod1", "Mod1", file("mod1", deps = listOf("P2")))),
                source = source,
            )
        }
        assertEquals(ModDependencyResolver.MAX_DEPTH + 1, plan.mods.size)
        assertTrue(plan.warnings.any { it.contains("层") }, plan.warnings.toString())
    }

    @Test
    fun `个数超过上限时不再展开`() {
        val ids = (1..80).map { "P" + it }
        val projects = ids.associateWith { id -> project(id, "mod" + id.drop(1)) }
        val versions = ids.drop(1).associate { id -> "mod" + id.drop(1) to listOf(file("mod" + id.drop(1))) }
        val source = FakeSource(projects, versions)
        val plan = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.solve(
                roots = listOf(DependencyRoot("P1", "mod1", "Mod1", file("mod1", deps = ids.drop(1)))),
                source = source,
            )
        }
        assertTrue(plan.mods.size <= ModDependencyResolver.MAX_MODS, plan.mods.size.toString())
        assertTrue(plan.warnings.any { it.contains("超过") }, plan.warnings.toString())
    }

    @Test
    fun `安装中途失败不影响后面的模组`() {
        val dir = tempDir("install")
        val plan = DependencyPlan(
            mods = listOf(
                PlannedMod("P1", "a", "A", file("a"), 0),
                PlannedMod("P2", "b", "B", file("b"), 1),
                PlannedMod("P3", "c", "C", file("c"), 1),
            ),
        )
        val outcome = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.install(dir, plan) { mod ->
                if (mod.slug == "b") throw IOException("下载失败：404")
            }
        }
        assertEquals(listOf("a", "c"), outcome.installed.map { it.slug })
        assertEquals(1, outcome.failed.size)
        assertEquals("b", outcome.failed[0].mod.slug)
        assertTrue(outcome.failed[0].reason.contains("404"), outcome.failed[0].reason)
        assertTrue(!outcome.complete)
    }

    @Test
    fun `全部成功时 complete 为真`() {
        val dir = tempDir("install-ok")
        val plan = DependencyPlan(mods = listOf(PlannedMod("P1", "a", "A", file("a"), 0)))
        val outcome = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.install(dir, plan) { }
        }
        assertEquals(1, outcome.installed.size)
        assertTrue(outcome.complete)
    }

    @Test
    fun `安装时按顺序报进度`() {
        val dir = tempDir("install-steps")
        val steps = mutableListOf<String>()
        val plan = DependencyPlan(
            mods = listOf(
                PlannedMod("P1", "a", "A", file("a"), 0),
                PlannedMod("P2", "b", "B", file("b"), 1),
            ),
        )
        kotlinx.coroutines.runBlocking {
            ModDependencyResolver.install(dir, plan, onStep = { steps += it }) { }
        }
        assertEquals(2, steps.size)
        assertTrue(steps[0].contains("1/2"), steps[0])
        assertTrue(steps[1].contains("2/2"), steps[1])
    }

    @Test
    fun `空白依赖不会把字符串拼坏`() {
        // 界面上「谁需要它」直接拼在提示行里，空值会变成「（需要：）」
        val plan = DependencyPlan(mods = listOf(PlannedMod("P1", "a", "A", file("a"), 0, emptyList())))
        assertTrue(plan.mods[0].requiredBy.isEmpty())
    }

    @Test
    fun `一串 project_id 能变成挑好文件的根`() {
        val source = FakeSource(
            projects = mapOf(
                "P1" to project("P1", "sodium", "Sodium"),
                "P3" to project("P3", "fabric-api", "Fabric API"),
            ),
            versions = mapOf(
                "sodium" to listOf(file("sodium")),
                "fabric-api" to listOf(file("fabric-api")),
            ),
        )
        val (roots, skipped) = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.rootsOf(listOf("P1", "P3"), source)
        }
        assertEquals(listOf("sodium", "fabric-api"), roots.map { it.slug })
        assertEquals(listOf("Sodium", "Fabric API"), roots.map { it.title })
        assertEquals("P1", roots[0].projectId)
        assertEquals("sodium-1.0.jar", roots[0].file.fileName)
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `查不到项目时退回 project_id 当 slug`() {
        // 依赖表给的是 project_id，但 /project/{id}/version 也认 id，所以查不到元数据也该继续试
        val source = FakeSource(
            projects = emptyMap(),
            versions = mapOf("P9" to listOf(file("P9"))),
        )
        val (roots, skipped) = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.rootsOf(listOf("P9"), source)
        }
        assertEquals(listOf("P9"), roots.map { it.slug })
        assertEquals(listOf("P9"), source.versionCalls)
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `没有适配版本的根会进 skipped 而不是消失`() {
        val source = FakeSource(
            projects = mapOf("P1" to project("P1", "sodium", "Sodium")),
            versions = emptyMap(),
        )
        val (roots, skipped) = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.rootsOf(listOf("P1"), source)
        }
        assertTrue(roots.isEmpty())
        assertEquals(1, skipped.size)
        assertEquals("Sodium", skipped[0].title)
        assertTrue(skipped[0].reason.contains("没有适配"), skipped[0].reason)
    }

    @Test
    fun `同一个根查版本失败不影响后面那个`() {
        val source = FakeSource(
            projects = mapOf(
                "P1" to project("P1", "sodium", "Sodium"),
                "P2" to project("P2", "iris", "Iris"),
            ),
            versions = mapOf("iris" to listOf(file("iris"))),
        )
        source.failVersionsFor = setOf("sodium")
        val (roots, skipped) = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.rootsOf(listOf("P1", "P2"), source)
        }
        assertEquals(listOf("iris"), roots.map { it.slug })
        assertEquals(1, skipped.size)
        assertEquals("Sodium", skipped[0].title)
        assertTrue(skipped[0].reason.contains("查版本失败"), skipped[0].reason)
    }

    @Test
    fun `重复的 project_id 只查一次`() {
        val source = FakeSource(
            projects = mapOf("P1" to project("P1", "sodium", "Sodium")),
            versions = mapOf("sodium" to listOf(file("sodium"))),
        )
        val (roots, _) = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.rootsOf(listOf("P1", " P1 ", "", "P1"), source)
        }
        assertEquals(1, roots.size)
        assertEquals(1, source.versionCalls.size)
    }

    @Test
    fun `空清单不会去联网`() {
        val source = FakeSource(projects = emptyMap(), versions = emptyMap())
        val (roots, skipped) = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.rootsOf(listOf("", "  "), source)
        }
        assertTrue(roots.isEmpty())
        assertTrue(skipped.isEmpty())
        assertTrue(source.projectCalls.isEmpty())
    }

    @Test
    fun `rootsOf 的结果能直接拿去展开依赖树`() {
        // 「装完还缺前置」那条路就是：pendingDeps → rootsOf → solve → install
        val source = FakeSource(
            projects = mapOf(
                "P1" to project("P1", "sodium", "Sodium"),
                "P2" to project("P2", "fabric-api", "Fabric API"),
            ),
            versions = mapOf(
                "sodium" to listOf(file("sodium", deps = listOf("P2"))),
                "fabric-api" to listOf(file("fabric-api")),
            ),
        )
        val plan = kotlinx.coroutines.runBlocking {
            val (roots, _) = ModDependencyResolver.rootsOf(listOf("P1"), source)
            ModDependencyResolver.solve(roots, source = source)
        }
        assertEquals(listOf("sodium", "fabric-api"), plan.mods.map { it.slug })
        assertEquals(listOf(0, 1), plan.mods.map { it.depth })
        assertEquals(1, plan.dependencyCount)
    }

    @Test
    fun `可选依赖只列出来，不排进安装计划`() {
        val source = FakeSource(
            projects = mapOf(
                "P1" to project("P1", "iris", "Iris Shaders"),
                "P2" to project("P2", "sodium", "Sodium"),
            ),
            versions = mapOf(
                "iris" to listOf(file("iris", optional = listOf("P2"))),
                "sodium" to listOf(file("sodium")),
            ),
        )
        val plan = kotlinx.coroutines.runBlocking {
            val (roots, _) = ModDependencyResolver.rootsOf(listOf("P1"), source)
            ModDependencyResolver.solve(roots, source = source)
        }
        // mods 里只有根 —— 可选依赖一旦混进来，「一次装齐」会把它一起下掉
        assertEquals(listOf("iris"), plan.mods.map { it.slug })
        assertEquals(listOf("sodium"), plan.optional.map { it.slug })
        assertEquals("Iris Shaders", plan.optional.single().suggestedBy)
        assertEquals("1.0", plan.optional.single().file.versionNumber)
    }

    @Test
    fun `可选依赖不再往下展开`() {
        val source = FakeSource(
            projects = mapOf(
                "P1" to project("P1", "iris", "Iris Shaders"),
                "P2" to project("P2", "sodium", "Sodium"),
                "P3" to project("P3", "fabric-api", "Fabric API"),
            ),
            versions = mapOf(
                "iris" to listOf(file("iris", optional = listOf("P2"))),
                "sodium" to listOf(file("sodium", deps = listOf("P3"))),
                "fabric-api" to listOf(file("fabric-api")),
            ),
        )
        val plan = kotlinx.coroutines.runBlocking {
            val (roots, _) = ModDependencyResolver.rootsOf(listOf("P1"), source)
            ModDependencyResolver.solve(roots, source = source)
        }
        // 可选依赖自己的前置不跟进来：那等于把「可选」当「必需」用
        assertEquals(listOf("iris"), plan.mods.map { it.slug })
        assertEquals(listOf("sodium"), plan.optional.map { it.slug })
    }

    @Test
    fun `已经在必需树里的可选依赖不再重复列`() {
        val source = FakeSource(
            projects = mapOf(
                "P1" to project("P1", "iris", "Iris Shaders"),
                "P2" to project("P2", "sodium", "Sodium"),
            ),
            versions = mapOf(
                "iris" to listOf(file("iris", deps = listOf("P2"), optional = listOf("P2"))),
                "sodium" to listOf(file("sodium")),
            ),
        )
        val plan = kotlinx.coroutines.runBlocking {
            val (roots, _) = ModDependencyResolver.rootsOf(listOf("P1"), source)
            ModDependencyResolver.solve(roots, source = source)
        }
        assertEquals(listOf("iris", "sodium"), plan.mods.map { it.slug })
        assertTrue(plan.optional.isEmpty(), "必需树里已经排了，不该再列一遍")
    }

    @Test
    fun `可选依赖查不到版本时静默略过不占跳过区`() {
        val source = FakeSource(
            projects = mapOf("P1" to project("P1", "iris", "Iris Shaders")),
            versions = mapOf("iris" to listOf(file("iris", optional = listOf("P9")))),
        )
        val plan = kotlinx.coroutines.runBlocking {
            val (roots, _) = ModDependencyResolver.rootsOf(listOf("P1"), source)
            ModDependencyResolver.solve(roots, source = source)
        }
        assertEquals(listOf("iris"), plan.mods.map { it.slug })
        assertTrue(plan.optional.isEmpty())
        // 可选依赖本来就能不装，不该占「跳过」的位置让人以为装错了
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `已经装过的可选依赖不再提示`() {
        val source = FakeSource(
            projects = mapOf(
                "P1" to project("P1", "iris", "Iris Shaders"),
                "P2" to project("P2", "sodium", "Sodium"),
            ),
            versions = mapOf(
                "iris" to listOf(file("iris", optional = listOf("P2"))),
                "sodium" to listOf(file("sodium")),
            ),
        )
        val plan = kotlinx.coroutines.runBlocking {
            val (roots, _) = ModDependencyResolver.rootsOf(listOf("P1"), source)
            ModDependencyResolver.solve(roots, installedProjectIds = setOf("P2"), source = source)
        }
        assertEquals(listOf("iris"), plan.mods.map { it.slug })
        assertTrue(plan.optional.isEmpty(), "已经装了的不该再劝一次")
    }

    @Test
    fun `没有必需前置时也能单独提示可选依赖`() {
        // 「装完一个只标了 optional 的模组」那条路：没有根可展开，但提示不能丢
        val source = FakeSource(
            projects = mapOf("P2" to project("P2", "sodium", "Sodium")),
            versions = mapOf("sodium" to listOf(file("sodium"))),
        )
        val optional = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.suggestOptional("Iris Shaders", listOf("P2"), source)
        }
        assertEquals(listOf("sodium"), optional.map { it.slug })
        assertEquals("Sodium", optional.single().title)
        assertEquals("Iris Shaders", optional.single().suggestedBy)
    }

    @Test
    fun `单独提示可选依赖时也会跳过已经装过的`() {
        val source = FakeSource(
            projects = mapOf("P2" to project("P2", "sodium", "Sodium")),
            versions = mapOf("sodium" to listOf(file("sodium"))),
        )
        val optional = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.suggestOptional(
                rootTitle = "Iris Shaders",
                projectIds = listOf("P2"),
                source = source,
                installedProjectIds = setOf("P2"),
            )
        }
        assertTrue(optional.isEmpty())
    }

    @Test
    fun `单独提示可选依赖时能限个数`() {
        // 一个模组挂四十个可选依赖时，卡片不能撑成一屏
        val ids = (1..40).map { "P" + it }
        val source = FakeSource(
            projects = ids.associateWith { project(it, "mod" + it, "Mod " + it) },
            versions = ids.associate { "mod" + it to listOf(file("mod" + it)) },
        )
        val optional = kotlinx.coroutines.runBlocking {
            ModDependencyResolver.suggestOptional("Root", ids, source)
        }
        assertEquals(ModDependencyResolver.MAX_OPTIONAL, optional.size)
    }
}
