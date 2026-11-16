package io.github.huang404yan.platypus.core.lan

import io.github.huang404yan.platypus.core.Endpoints
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 官方最新版这一层：地址怎么拼、JSON 怎么读、摘要怎么认、已装版本怎么记。
 *
 * 全部离线 —— 样本是 2026-11-16 从 api.github.com/repos/burningtnt/Terracotta/releases/latest
 * 抓回来的真实响应裁剪而成。联网那半（[TerracottaReleases.fetchLatest]）不在这里测：
 * 它要是哪天 GitHub 改字段，测试不该跟着红，而是界面里那句「没查到官方最新版」兜住。
 */
class TerracottaReleaseTest {

    private val root: File = Files.createTempDirectory("platypus-terracotta-release-test").toFile()

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private val latestJson = """
        {
          "tag_name": "v0.4.2",
          "name": "Terracotta v0.4.2",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "terracotta-0.4.2-linux-x86_64-pkg.tar.gz",
              "size": 8000000,
              "browser_download_url": "https://github.com/burningtnt/Terracotta/releases/download/v0.4.2/terracotta-0.4.2-linux-x86_64-pkg.tar.gz",
              "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            },
            {
              "name": "terracotta-0.4.2-windows-x86_64-pkg.tar.gz",
              "size": 8135914,
              "browser_download_url": "https://github.com/burningtnt/Terracotta/releases/download/v0.4.2/terracotta-0.4.2-windows-x86_64-pkg.tar.gz",
              "digest": "sha256:07ebe139e3ca5f74576e58b1a96efe59abdfbe148d3f1a49bfdca8b6f70745f0"
            }
          ]
        }
    """.trimIndent()

    // ── 地址规则 ────────────────────────────────────────────────

    @Test
    fun `资产名按版本号拼`() {
        assertEquals("terracotta-0.4.2-windows-x86_64-pkg.tar.gz", TerracottaReleases.assetNameOf("0.4.2"))
    }

    @Test
    fun `官方下载地址带 v 前缀`() {
        assertEquals(
            "https://github.com/burningtnt/Terracotta/releases/download/v0.4.2/terracotta-0.4.2-windows-x86_64-pkg.tar.gz",
            TerracottaReleases.officialUrl("v0.4.2", TerracottaReleases.assetNameOf("0.4.2")),
        )
    }

    @Test
    fun `查最新版走 Endpoints 里的 GitHub 根`() {
        // 空壳包会把这一根换成镜像；非空壳包里它就是官方 api.github.com。
        if (Endpoints.shell) return
        assertEquals(
            "https://api.github.com/repos/burningtnt/Terracotta/releases/latest",
            TerracottaReleases.latestApiUrl(),
        )
    }

    // ── JSON 解析 ───────────────────────────────────────────────

    @Test
    fun `从最新版响应里挑出 Windows 包`() {
        val release = TerracottaReleases.parse(latestJson)
        assertEquals("v0.4.2", release.tag)
        assertEquals("0.4.2", release.version)
        assertEquals("terracotta-0.4.2-windows-x86_64-pkg.tar.gz", release.assetName)
        assertEquals(8_135_914L, release.sizeBytes)
        assertEquals(
            "https://github.com/burningtnt/Terracotta/releases/download/v0.4.2/terracotta-0.4.2-windows-x86_64-pkg.tar.gz",
            release.downloadUrl,
        )
        assertEquals("07ebe139e3ca5f74576e58b1a96efe59abdfbe148d3f1a49bfdca8b6f70745f0", release.sha256)
    }

    @Test
    fun `解析出来的版本正好是内置的那一版时算内置版`() {
        val release = TerracottaReleases.parse(latestJson)
        assertEquals(release.version == Terracotta.VERSION, release.isBuiltinVersion)
    }

    @Test
    fun `没有 tag_name 就报错而不是猜一个版本`() {
        val e = assertFailsWith<IOException> {
            TerracottaReleases.parse("""{"assets": []}""")
        }
        assertTrue(e.message.orEmpty().contains("tag_name"), e.message.orEmpty())
    }

    @Test
    fun `响应不是 JSON 对象时给出人话`() {
        val e = assertFailsWith<IOException> { TerracottaReleases.parse("<html>502</html>") }
        assertTrue(e.message.orEmpty().contains("不是"), e.message.orEmpty())
    }

    @Test
    fun `发行版里没有 Windows 包时点名是哪个版本缺`() {
        val json = """{"tag_name":"v9.9.9","assets":[{"name":"terracotta-9.9.9-linux-x86_64-pkg.tar.gz"}]}"""
        val e = assertFailsWith<IOException> { TerracottaReleases.parse(json) }
        assertTrue(e.message.orEmpty().contains("v9.9.9"), e.message.orEmpty())
        assertTrue(e.message.orEmpty().contains("windows"), e.message.orEmpty())
    }

    @Test
    fun `API 没给 browser_download_url 时按官方规则拼`() {
        val json = """{"tag_name":"v1.2.3","assets":[{"name":"terracotta-1.2.3-windows-x86_64-pkg.tar.gz","size":10}]}"""
        val release = TerracottaReleases.parse(json)
        assertEquals(
            "https://github.com/burningtnt/Terracotta/releases/download/v1.2.3/terracotta-1.2.3-windows-x86_64-pkg.tar.gz",
            release.downloadUrl,
        )
        assertNull(release.sha256)
    }

    // ── digest 解析 ─────────────────────────────────────────────

    @Test
    fun `认得标准 sha256 digest`() {
        assertEquals(
            "07ebe139e3ca5f74576e58b1a96efe59abdfbe148d3f1a49bfdca8b6f70745f0",
            TerracottaReleases.parseDigest("sha256:07EBE139E3CA5F74576E58B1A96EFE59ABDFBE148D3F1A49BFDCA8B6F70745F0"),
        )
    }

    @Test
    fun `digest 格式不对时给 null 而不是残缺值`() {
        assertNull(TerracottaReleases.parseDigest(null))
        assertNull(TerracottaReleases.parseDigest(""))
        assertNull(TerracottaReleases.parseDigest("sha256:abc"))
        // 长度对了但不是十六进制
        assertNull(TerracottaReleases.parseDigest("sha256:" + "z".repeat(64)))
        // 算法不是 sha256（老 release 里可能有 sha512）
        assertNull(TerracottaReleases.parseDigest("sha512:" + "a".repeat(64)))
    }

    // ── 已装版本与摘要 ───────────────────────────────────────────

    @Test
    fun `没装过时读不出已装版本`() {
        assertNull(TerracottaInstaller.readInstalledVersion(root))
    }

    @Test
    fun `已经是最新版本时不再重下`() {
        // 手工摆一套「装好了、版本也和官方最新一致」的现场，再让 ensure 去查更新：
        // 它应该在下载之前就返回，不会碰网络。
        File(root, "terracotta.exe").writeText("x")
        File(root, ".installed-version").writeText(Terracotta.VERSION + "\n", Charsets.UTF_8)
        val steps = mutableListOf<String>()
        val exe = kotlinx.coroutines.runBlocking {
            TerracottaInstaller.ensure(
                root,
                onProgress = { p -> steps += p.step },
                checkUpdate = true,
                latest = { TerracottaInstaller.builtinRelease() },
            )
        }
        assertEquals("terracotta.exe", exe.name)
        assertEquals(1, File(root, "terracotta.exe").readText().length)
        assertTrue(steps.any { it.contains("已经是最新版本") }, steps.toString())
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".part") }, "不该留下半截文件")
    }

    @Test
    fun `内置版本描述与常量一致`() {
        val builtin = TerracottaInstaller.builtinRelease()
        assertEquals(Terracotta.VERSION, builtin.version)
        assertEquals("v" + Terracotta.VERSION, builtin.tag)
        assertTrue(builtin.downloadUrl.endsWith(Terracotta.packageName()), builtin.downloadUrl)
        assertTrue(builtin.isBuiltinVersion)
        // 内置版的校验值不在发布信息里给，走写死的 SHA-512。
        assertNull(builtin.sha256)
    }

    @Test
    fun `两个摘要函数算的是真实摘要`() {
        val file = File(root, "abc.txt")
        // "abc" 的 SHA-256 / SHA-512 是公开的标准向量，写死在这里能证明实现没写歪。
        file.writeText("abc", Charsets.UTF_8)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TerracottaInstaller.sha256Of(file),
        )
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            TerracottaInstaller.sha512Of(file),
        )
    }
}
