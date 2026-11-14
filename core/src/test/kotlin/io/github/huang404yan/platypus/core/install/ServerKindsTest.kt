package io.github.huang404yan.platypus.core.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerKindsTest {

    private val paperJson = """
        [
          {"id":196,"channel":"STABLE","downloads":{
            "server:mojang":{"name":"mojang.jar","url":"https://fill-data/mojang.jar",
              "checksums":{"sha256":"aaa"},"size":1},
            "server:default":{"name":"paper-1.20.1-196.jar",
              "url":"https://fill-data/paper-196.jar",
              "checksums":{"sha256":"234a9b"},"size":43230582}},
           "commits":[]},
          {"id":195,"channel":"STABLE","downloads":{
            "server:default":{"name":"paper-1.20.1-195.jar",
              "url":"https://fill-data/paper-195.jar",
              "checksums":{"sha256":"ffff"},"size":43200000}}}
        ]
    """.trimIndent()

    @Test
    fun `Paper 的构建列表能解析出文件名地址与 sha256`() {
        val builds = PaperMeta.parseBuilds(paperJson)
        assertEquals(2, builds.size)
        assertEquals(196, builds[0].id)
        assertEquals("paper-1.20.1-196.jar", builds[0].fileName)
        assertEquals("https://fill-data/paper-196.jar", builds[0].url)
        assertEquals("234a9b", builds[0].sha256)
        assertEquals(43230582L, builds[0].size)
    }

    @Test
    fun `Paper 只认 server default 不拿 mojang 那份`() {
        val build = PaperMeta.parseBuilds(paperJson).first()
        // server:mojang 是喂给别的工具的原始产物，拿错了开不起来
        assertEquals("https://fill-data/paper-196.jar", build.url)
        assertFalse(build.url.contains("mojang"))
    }

    @Test
    fun `Paper 取最新构建时按 id 而不是数组顺序`() {
        val builds = PaperMeta.parseBuilds(paperJson)
        assertEquals(196, PaperMeta.latest(builds)?.id)
        // 顺序反过来也应该是 196
        assertEquals(196, PaperMeta.latest(builds.reversed())?.id)
    }

    @Test
    fun `Paper 的空响应当作没有构建而不是崩掉`() {
        assertTrue(PaperMeta.parseBuilds("").isEmpty())
        assertTrue(PaperMeta.parseBuilds("not json").isEmpty())
        assertTrue(PaperMeta.parseBuilds("{}").isEmpty())
        assertNull(PaperMeta.latest(emptyList()))
    }

    @Test
    fun `Paper 的构建地址用 fill 而不是已经下线的 api`() {
        val url = PaperMeta.buildsUrl("1.20.1")
        assertEquals("https://fill.papermc.io/v3/projects/paper/versions/1.20.1/builds", url)
        assertFalse(url.contains("api.papermc.io"))
    }

    @Test
    fun `Paper 缺 checksums 也不丢掉这一项`() {
        val json = """[{"id":7,"downloads":{"server:default":{"name":"p.jar","url":"https://x/p.jar"}}}]"""
        val build = PaperMeta.parseBuilds(json).single()
        assertEquals(7, build.id)
        assertEquals("", build.sha256)
        assertEquals(-1L, build.size)
    }

    @Test
    fun `Fabric 的 installer 版本取最新的稳定版`() {
        val json = """[
            {"version":"1.2.0","stable":false},
            {"version":"1.1.2","stable":true},
            {"version":"1.0.1","stable":true}
        ]"""
        assertEquals("1.1.2", FabricServer.parseInstallerVersion(json))
    }

    @Test
    fun `Fabric 没有标注 stable 时退回第一项`() {
        val json = """[{"version":"9.9.9"}]"""
        assertEquals("9.9.9", FabricServer.parseInstallerVersion(json))
        assertNull(FabricServer.parseInstallerVersion("garbage"))
        assertNull(FabricServer.parseInstallerVersion("[]"))
    }

    @Test
    fun `Fabric 的服务端地址必须带 installer 版本`() {
        val url = FabricServer.serverJarUrl("1.20.1", "0.15.11", "1.1.2")
        assertEquals(
            "https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.15.11/1.1.2/server/jar",
            url,
        )
        assertEquals(1, url.split("/server/jar").size - 1, "只会出现一个 server/jar")
    }

    @Test
    fun `Fabric 服务端 jar 的文件名带上游戏与加载器版本`() {
        assertEquals("fabric-server-1.20.1-0.15.11.jar", FabricServer.serverJarName("1.20.1", "0.15.11"))
    }

    @Test
    fun `只有 Forge 与 NeoForge 需要跑安装器`() {
        assertTrue(ServerKind.FORGE.needsInstaller)
        assertTrue(ServerKind.NEOFORGE.needsInstaller)
        assertFalse(ServerKind.VANILLA.needsInstaller)
        assertFalse(ServerKind.PAPER.needsInstaller)
        assertFalse(ServerKind.FABRIC.needsInstaller)
    }

    @Test
    fun `默认的服务端加载器是原版`() {
        assertEquals(ServerKind.VANILLA, ServerLoader.Vanilla.kind)
        assertNull(ServerLoader.Vanilla.loaderVersion)
        assertEquals(ServerKind.VANILLA, ServerLoader().kind)
    }

    @Test
    fun `每种服务端都有自己的中文名`() {
        val labels = ServerKind.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size, "中文名不能重复")
        assertTrue(labels.all { it.isNotBlank() })
    }
}
