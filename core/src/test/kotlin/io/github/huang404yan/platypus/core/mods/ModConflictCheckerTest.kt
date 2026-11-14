package io.github.huang404yan.platypus.core.mods

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModConflictCheckerTest {

    /** cross() 不读盘，所以这里造个只有名字的 File 就够。 */
    private fun local(fileName: String, sha1: String = "s" + fileName.hashCode()) =
        LocalModFile(fileName, File(fileName), sha1)

    private fun lookup(
        sha1: String,
        projectId: String,
        incompatible: List<String> = emptyList(),
        required: List<String> = emptyList(),
        versionNumber: String = "1.0",
    ) = ModVersionLookup(
        sha1 = sha1,
        projectId = projectId,
        versionNumber = versionNumber,
        fileName = "x.jar",
        incompatibleDependencies = incompatible,
        requiredDependencies = required,
    )

    private fun project(id: String, title: String) =
        ModProject(slug = id.lowercase(), title = title, projectId = id)

    @Test
    fun `两边都装着的冲突会被标成已存在`() {
        // 2026-11-14 本机真实情况：Embeddium 声明不兼容 Rubidium，两个都在 mods 里
        val emb = local("embeddium-0.3.31+mc1.20.1.jar", "h1")
        val rub = local("rubidium-0.7.1.jar", "h2")
        val conflicts = ModConflictChecker.cross(
            files = listOf(emb, rub),
            lookups = mapOf(
                "h1" to lookup("h1", "sk9rgfiA", incompatible = listOf("4ZqxOvjD")),
                "h2" to lookup("h2", "4ZqxOvjD"),
            ),
            projects = mapOf(
                "sk9rgfiA" to project("sk9rgfiA", "Embeddium"),
                "4ZqxOvjD" to project("4ZqxOvjD", "Rubidium"),
            ),
        )
        assertEquals(1, conflicts.size)
        val c = conflicts.single()
        assertTrue(c.present, "Rubidium 装着，属于现在就有的冲突")
        assertEquals("embeddium-0.3.31+mc1.20.1.jar", c.sourceFile)
        assertEquals("Embeddium", c.sourceTitle)
        assertEquals("rubidium-0.7.1.jar", c.otherFile)
        assertEquals("Rubidium", c.otherLabel)
    }

    @Test
    fun `作者点名但那个模组没装时不算已发生`() {
        val emb = local("embeddium.jar", "h1")
        val conflicts = ModConflictChecker.cross(
            files = listOf(emb),
            lookups = mapOf("h1" to lookup("h1", "sk9rgfiA", incompatible = listOf("4ZqxOvjD"))),
            projects = mapOf("4ZqxOvjD" to project("4ZqxOvjD", "Rubidium")),
        )
        val c = conflicts.single()
        assertFalse(c.present)
        assertEquals(null, c.otherFile)
        assertEquals("Rubidium", c.otherLabel, "没装也要显示名字，好让用户知道是什么")
    }

    @Test
    fun `双向声明合成一条并标 mutual`() {
        val a = local("a.jar", "h1")
        val b = local("b.jar", "h2")
        val conflicts = ModConflictChecker.cross(
            files = listOf(a, b),
            lookups = mapOf(
                "h1" to lookup("h1", "AAAA", incompatible = listOf("BBBB")),
                "h2" to lookup("h2", "BBBB", incompatible = listOf("AAAA")),
            ),
            projects = mapOf("AAAA" to project("AAAA", "A"), "BBBB" to project("BBBB", "B")),
        )
        assertEquals(1, conflicts.size, "两边各说一次不能变成两条")
        assertTrue(conflicts.single().mutual)
        assertTrue(conflicts.single().present)
    }

    @Test
    fun `单向声明不标 mutual`() {
        val a = local("a.jar", "h1")
        val b = local("b.jar", "h2")
        val conflicts = ModConflictChecker.cross(
            files = listOf(a, b),
            lookups = mapOf(
                "h1" to lookup("h1", "AAAA", incompatible = listOf("BBBB")),
                "h2" to lookup("h2", "BBBB"),
            ),
        )
        assertEquals(1, conflicts.size)
        assertFalse(conflicts.single().mutual)
    }

    @Test
    fun `同一个项目装了多个文件只占一条`() {
        val old = local("embeddium-0.3.30.jar", "h1")
        val new = local("embeddium-0.3.31.jar", "h2")
        val rub = local("rubidium.jar", "h3")
        val conflicts = ModConflictChecker.cross(
            files = listOf(old, new, rub),
            lookups = mapOf(
                "h1" to lookup("h1", "sk9rgfiA", incompatible = listOf("4ZqxOvjD")),
                "h2" to lookup("h2", "sk9rgfiA", incompatible = listOf("4ZqxOvjD")),
                "h3" to lookup("h3", "4ZqxOvjD"),
            ),
        )
        assertEquals(1, conflicts.size)
        assertEquals("embeddium-0.3.30.jar", conflicts.single().sourceFile, "按文件名排序取第一个，结果稳定")
    }

    @Test
    fun `自己声明不兼容自己时跳过`() {
        val a = local("a.jar", "h1")
        val conflicts = ModConflictChecker.cross(
            files = listOf(a),
            lookups = mapOf("h1" to lookup("h1", "AAAA", incompatible = listOf("AAAA"))),
        )
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `查不到项目名时退回文件名与 project_id`() {
        val emb = local("embeddium.jar", "h1")
        val conflicts = ModConflictChecker.cross(
            files = listOf(emb),
            lookups = mapOf("h1" to lookup("h1", "sk9rgfiA", incompatible = listOf("4ZqxOvjD"))),
        )
        val c = conflicts.single()
        assertEquals("embeddium.jar", c.sourceTitle, "查不到项目就用文件名，不能空着")
        assertEquals("4ZqxOvjD", c.otherLabel)
    }

    @Test
    fun `已装的冲突排在只有声明的前面`() {
        val a = local("a.jar", "h1")
        val b = local("b.jar", "h2")
        val conflicts = ModConflictChecker.cross(
            files = listOf(a, b),
            lookups = mapOf(
                // a 点名 BBBB（b 装着）与 ZZZZ（没装）
                "h1" to lookup("h1", "AAAA", incompatible = listOf("ZZZZ", "BBBB")),
                "h2" to lookup("h2", "BBBB"),
            ),
        )
        assertEquals(2, conflicts.size)
        assertTrue(conflicts[0].present, "现在就可能崩的排前面")
        assertFalse(conflicts[1].present)
    }

    @Test
    fun `报告按 present 分成两组`() {
        val a = local("a.jar", "h1")
        val b = local("b.jar", "h2")
        val conflicts = ModConflictChecker.cross(
            files = listOf(a, b),
            lookups = mapOf(
                "h1" to lookup("h1", "AAAA", incompatible = listOf("ZZZZ", "BBBB")),
                "h2" to lookup("h2", "BBBB"),
            ),
        )
        val report = ModConflictReport(conflicts = conflicts, scanned = 2, recognized = 2)
        assertEquals(1, report.presentConflicts.size)
        assertEquals(1, report.potentialConflicts.size)
    }

    @Test
    fun `没有任何 incompatible 声明时不报冲突`() {
        val a = local("a.jar", "h1")
        val b = local("b.jar", "h2")
        val conflicts = ModConflictChecker.cross(
            files = listOf(a, b),
            lookups = mapOf(
                // required 不是冲突，不能混进来
                "h1" to lookup("h1", "AAAA", required = listOf("BBBB")),
                "h2" to lookup("h2", "BBBB"),
            ),
        )
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `空输入直接返回空表`() {
        assertTrue(ModConflictChecker.cross(emptyList(), emptyMap()).isEmpty())
        assertTrue(ModConflictChecker.cross(listOf(local("a.jar", "h1")), emptyMap()).isEmpty())
    }

    @Test
    fun `反查没认出来的文件不参与比对`() {
        val a = local("a.jar", "h1")
        val unknown = local("unknown.jar", "h9")
        val conflicts = ModConflictChecker.cross(
            files = listOf(a, unknown),
            lookups = mapOf(
                "h1" to lookup("h1", "AAAA", incompatible = listOf("BBBB")),
            ),
        )
        assertEquals(1, conflicts.size)
        assertEquals("a.jar", conflicts.single().sourceFile)
    }
}
