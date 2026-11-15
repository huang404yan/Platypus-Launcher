package io.github.huang404yan.platypus.core.loader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Quilt 元数据解析的单元测试。
 *
 * 这里所有 JSON 都是从 meta.quiltmc.org 真实响应里截的形状（2026-11 实测），
 * 不是照着我们的数据类反推出来的 —— 反推出来的样本只能证明代码自洽，证明不了能认真响应。
 */
class QuiltMetaTest {

    @Test
    fun `版本号命名与 Fabric 那边对齐`() {
        assertEquals("1.20.1-quilt-0.24.0", QuiltMeta.versionId("1.20.1", "0.24.0"))
        assertEquals("Quilt 0.24.0 / 1.20.1", QuiltMeta.displayName("1.20.1", "0.24.0"))
        assertEquals(
            "https://meta.quiltmc.org/v3/versions/loader/1.20.1",
            QuiltMeta.loaderListUrl("1.20.1"),
        )
        assertEquals(
            "https://meta.quiltmc.org/v3/versions/loader/1.20.1/0.24.0/profile/json",
            QuiltMeta.profileUrl("1.20.1", "0.24.0"),
        )
    }

    @Test
    fun `从三种版本 id 里都能反解出 loader 版本`() {
        // 本启动器自己起的名字
        assertEquals("0.24.0", QuiltMeta.loaderVersionFromId("1.20.1-quilt-0.24.0"))
        // Quilt 官方 profile 的 id：loader 版本后面还跟着原版号
        assertEquals("0.24.0", QuiltMeta.loaderVersionFromId("quilt-loader-0.24.0-1.20.1"))
        // 预发布后缀要原样保留
        assertEquals("0.20.0-beta.9", QuiltMeta.loaderVersionFromId("1.20.1-quilt-0.20.0-beta.9"))
        assertNull(QuiltMeta.loaderVersionFromId("1.20.1"))
    }

    @Test
    fun `官方 id 里的原版号不能被当成 loader 版本`() {
        // quilt-loader-0.24.0-1.20.1 若用宽松正则会把 -1.20.1 一起吞进来，所以官方那条排在前面
        assertEquals("0.24.0", QuiltMeta.loaderVersionFromId("quilt-loader-0.24.0-1.20.1"))
    }

    @Test
    fun `认得出哪些是预发布`() {
        assertTrue(QuiltMeta.isRelease("0.24.0"))
        assertTrue(QuiltMeta.isRelease("0.30.2"))
        assertFalse(QuiltMeta.isRelease("0.20.0-beta.9"))
        assertFalse(QuiltMeta.isRelease("0.31.0-rc.1"))
        assertFalse(QuiltMeta.isRelease(""))
    }

    @Test
    fun `列表第一项是预发布时不能直接取第一个`() {
        // 实测 1.20.1 的列表就是长这样：首项 0.20.0-beta.9，0.24.0 在中间，末项 0.31.0-beta.6
        val versions = listOf(
            QuiltLoaderVersion(version = "0.20.0-beta.9"),
            QuiltLoaderVersion(version = "0.24.0"),
            QuiltLoaderVersion(version = "0.30.2"),
            QuiltLoaderVersion(version = "0.31.0-beta.6"),
        )
        assertEquals("0.30.2", QuiltMeta.latestGrown(versions)?.version)
        assertEquals("0.30.2", QuiltMeta.latestGrown(versions.reversed())?.version)
    }

    @Test
    fun `全是预发布时退回整体最高的那个`() {
        // 新游戏版本的早期只有 beta，硬拦下来用户就没得选了
        val versions = listOf(
            QuiltLoaderVersion(version = "0.20.0-beta.9"),
            QuiltLoaderVersion(version = "0.21.0-beta.2"),
        )
        assertEquals("0.21.0-beta.2", QuiltMeta.latestGrown(versions)?.version)
        assertNull(QuiltMeta.latestGrown(emptyList()))
    }

    @Test
    fun `同一个主版本里预发布排在正式版前面`() {
        assertTrue(QuiltMeta.compareVersions("0.24.0-beta.1", "0.24.0") < 0)
        assertTrue(QuiltMeta.compareVersions("0.24.0", "0.30.2") < 0)
        assertTrue(QuiltMeta.compareVersions("0.30.2", "0.30.10") < 0)
        assertEquals(0, QuiltMeta.compareVersions("0.24.0", "0.24.0"))
    }

    @Test
    fun `按真实响应形状解析 loader 列表`() {
        val text = """[
            {"loader":{"maven":"org.quiltmc:quilt-loader:0.24.0","version":"0.24.0","build":0,
             "separator":"+build.","hashes":{}}},
            {"loader":{"maven":"org.quiltmc:quilt-loader:0.30.2","version":"0.30.2","build":2,
             "separator":"+build.","hashes":{}}}
        ]"""
        val parsed = parseQuiltLoaderVersions(text)
        val list = assertNotNull(parsed.getOrNull(), parsed.reasonOrNull())
        // 形状照抄 meta.quiltmc.org 真实响应：外层还有 hashed / intermediary / launcherMeta。
        assertEquals(2, list.size)
        assertEquals("0.24.0", list[0].version)
        assertEquals("0.30.2", list[1].version)
    }

    @Test
    fun `loader 字段缺失时用扁平字段兜底`() {
        val text = """[{"version":"0.24.0","build":0,"maven":"org.quiltmc:quilt-loader:0.24.0"}]"""
        val list = assertNotNull(parseQuiltLoaderVersions(text).getOrNull())
        assertEquals("0.24.0", list.single().version)
    }

    @Test
    fun `空响应与空数组要给出不同的原因`() {
        assertNotNull(parseQuiltLoaderVersions("").reasonOrNull())
        assertEquals(
            "Quilt 元数据里没有任何 loader 版本",
            parseQuiltLoaderVersions("[]").reasonOrNull(),
        )
    }

    @Test
    fun `解析 profile json`() {
        val profile = assertNotNull(
            parseQuiltProfile(profileJson, "1.20.1", "0.24.0").getOrNull(),
        )
        assertEquals("1.20.1", profile.gameVersion)
        assertEquals("0.24.0", profile.loaderVersion)
        assertEquals(QuiltMeta.MAIN_CLASS, profile.mainClass)
        assertEquals(1, profile.versionJson.libraries.size)
        // raw 必须原样保留：里面字段比我们的模型多，重新序列化会丢
        assertEquals(profileJson, profile.raw)
    }

    @Test
    fun `profile 缺关键字段时直接报原因`() {
        val noLibs = """{"id":"x","mainClass":"org.quiltmc.loader.impl.launch.knot.KnotClient"}"""
        assertNotNull(parseQuiltProfile(noLibs, "1.20.1", "0.24.0").reasonOrNull())
        val noMain = """{"id":"x","libraries":[{"name":"a"}]}"""
        assertNotNull(parseQuiltProfile(noMain, "1.20.1", "0.24.0").reasonOrNull())
        val wrongMain = profileJson.replace("org.quiltmc", "net.fabricmc")
        assertNotNull(parseQuiltProfile(wrongMain, "1.20.1", "0.24.0").reasonOrNull())
        assertNotNull(parseQuiltProfile("", "1.20.1", "0.24.0").reasonOrNull())
    }

    private val profileJson: String = """{
        "id":"quilt-loader-0.24.0-1.20.1",
        "inheritsFrom":"1.20.1",
        "type":"release",
        "mainClass":"org.quiltmc.loader.impl.launch.knot.KnotClient",
        "libraries":[{"name":"org.quiltmc:quilt-loader:0.24.0",
                     "url":"https://maven.quiltmc.org/repository/release/"}]
    }"""
}