package io.github.huang404yan.platypus.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Endpoints 的规则测试。
 *
 * 本机构建恒为完整版（BuildInfo.SHELL = false），所以 [Endpoints.use] 里那条
 * 「换成镜像根」的路径在真机上一次都走不到；测的是抽出来的 [Endpoints.rewriteHost]
 * 与两个纯函数 [Endpoints.normalizeBase] / [Endpoints.join] / [Endpoints.dir]。
 */
class EndpointsTest {

    @Test
    fun `完整版下地址原样返回`() {
        assertFalse(Endpoints.shell)
        assertEquals(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            Endpoints.use("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"),
        )
    }

    @Test
    fun `完整版永远说服务可用`() {
        assertTrue(Endpoints.serviceReady())
    }

    @Test
    fun `镜像根去掉末尾斜杠与首尾空白`() {
        assertEquals("https://mirror.example.com", Endpoints.normalizeBase("  https://mirror.example.com/  "))
    }

    @Test
    fun `镜像根接受引号包起来的地址`() {
        assertEquals("https://mirror.example.com", Endpoints.normalizeBase("\"https://mirror.example.com\""))
    }

    @Test
    fun `镜像根要 http 或 https 开头`() {
        assertEquals("", Endpoints.normalizeBase("mirror.example.com"))
        assertEquals("", Endpoints.normalizeBase("ftp://mirror.example.com"))
    }

    @Test
    fun `只有协议头不算地址`() {
        assertEquals("", Endpoints.normalizeBase("https://"))
        assertEquals("", Endpoints.normalizeBase("https://////"))
    }

    @Test
    fun `认不出的主机不该被改`() {
        val url = "https://littleskin.cn/api/yggdrasil/authserver/authenticate"
        assertEquals(url, Endpoints.rewriteHost(url, "https://mirror.example.com"))
    }

    @Test
    fun `官方主机换成镜像根且路径保留`() {
        assertEquals(
            "https://mirror.example.com/mc/game/version_manifest_v2.json",
            Endpoints.rewriteHost(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                "https://mirror.example.com",
            ),
        )
    }

    @Test
    fun `launchermeta 不会被 launcher 先吃掉`() {
        // 两个前缀共享 https://launcher 这一段，顺序错了这里就会拼出 ...meta.mojang.com 的残尾。
        assertEquals(
            "https://mirror.example.com/v1/products/java-runtime/all.json",
            Endpoints.rewriteHost(
                "https://launchermeta.mojang.com/v1/products/java-runtime/all.json",
                "https://mirror.example.com",
            ),
        )
    }

    @Test
    fun `资源与库的地址也走镜像`() {
        assertEquals(
            "https://mirror.example.com/ab/cdef",
            Endpoints.rewriteHost("https://resources.download.minecraft.net/ab/cdef", "https://mirror.example.com"),
        )
        assertEquals(
            "https://mirror.example.com/com/google/guava/guava.jar",
            Endpoints.rewriteHost(
                "https://libraries.minecraft.net/com/google/guava/guava.jar",
                "https://mirror.example.com",
            ),
        )
    }

    @Test
    fun `没填镜像根时官方地址变成空串`() {
        assertEquals("", Endpoints.rewriteHost("https://piston-meta.mojang.com/x.json", ""))
    }

    @Test
    fun `没填镜像根也不该动用户自己的地址`() {
        // 空壳包最怕连坐：根没填只该让官方地址失效，用户填的外置登录地址必须原样。
        assertEquals(
            "https://littleskin.cn/api/yggdrasil",
            Endpoints.rewriteHost("https://littleskin.cn/api/yggdrasil", ""),
        )
    }

    @Test
    fun `join 在根为空时返回空串`() {
        assertEquals("", Endpoints.join("", "/mc/game/version_manifest_v2.json"))
    }

    @Test
    fun `join 容忍两边斜杠的写法`() {
        assertEquals(
            "https://mirror.example.com/mc/game/version_manifest_v2.json",
            Endpoints.join("https://mirror.example.com/", "/mc/game/version_manifest_v2.json"),
        )
        assertEquals(
            "https://mirror.example.com/mc/game/version_manifest_v2.json",
            Endpoints.join("https://mirror.example.com", "mc/game/version_manifest_v2.json"),
        )
    }

    @Test
    fun `dir 给老代码留一个带末尾斜杠的根`() {
        assertEquals("https://mirror.example.com/", Endpoints.dir("https://mirror.example.com"))
        assertEquals("", Endpoints.dir(""))
    }
}
