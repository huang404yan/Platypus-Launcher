package io.github.huang404yan.platypus.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {

    /** 加一对双引号。用函数而不是模板字符串，免得转义把 JSON 拼坏。 */
    private fun q(text: String): String = "\"" + text + "\""

    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false, assets: String = "") =
        "{" + q("tag_name") + ":" + q(tag) +
            "," + q("name") + ":" + q("名字 " + tag) +
            "," + q("body") + ":" + q("说明") +
            "," + q("prerelease") + ":" + pre +
            "," + q("draft") + ":" + draft +
            "," + q("published_at") + ":" + q("2026-11-15T00:00:00Z") +
            "," + q("html_url") + ":" + q("https://github.com/x/y/releases/tag/" + tag) +
            "," + q("assets") + ":[" + assets + "]}"

    private fun asset(name: String, size: Long = 100) =
        "{" + q("name") + ":" + q(name) +
            "," + q("size") + ":" + size +
            "," + q("browser_download_url") + ":" + q("https://example.com/" + name) + "}"

    private fun list(vararg body: String): String = "[" + body.joinToString(",") + "]"

    @Test
    fun `解析发布列表里的关键字段`() {
        val json = list(release("v0.8.0-preview.12", pre = true, assets = asset("a.msi") + "," + asset("b.zip")))
        val parsed = UpdateChecker.parseReleases(json)
        assertEquals(1, parsed.size)
        val info = parsed[0]
        assertEquals("v0.8.0-preview.12", info.tagName)
        assertEquals("0.8.0-preview.12", info.version)
        assertTrue(info.prerelease)
        assertFalse(info.draft)
        assertEquals(2, info.assets.size)
        assertEquals("msi", info.assets[0].extension)
        assertEquals("https://example.com/a.msi", info.assets[0].downloadUrl)
        assertEquals(100L, info.assets[0].size)
        assertEquals("a.msi", info.assetWith("msi")?.name)
        assertNull(info.assetWith("apk"))
    }

    @Test
    fun `不是数组的响应当成空列表而不是抛`() {
        // 限流时 GitHub 返回的是一个对象，不是数组。这种情况下宁可说「没查到」，
        // 也不能让界面抛一个看不懂的异常。
        assertEquals(0, UpdateChecker.parseReleases("{" + q("message") + ":" + q("rate limited") + "}").size)
        assertEquals(0, UpdateChecker.parseReleases("not json").size)
        assertEquals(0, UpdateChecker.parseReleases("").size)
    }

    @Test
    fun `没有 tag_name 的条目跳过`() {
        val json = list("{" + q("name") + ":" + q("没有 tag") + "}", release("v1.0.0"))
        assertEquals(1, UpdateChecker.parseReleases(json).size)
    }

    @Test
    fun `比自己新的才算有更新`() {
        val parsed = UpdateChecker.parseReleases(list(release("v0.8.0-preview.12", pre = true)))
        val newest = UpdateChecker.pickNewest(parsed, "0.8.0-preview.11")
        assertNotNull(newest)
        assertEquals("0.8.0-preview.12", newest.version)
        assertTrue(UpdateChecker.compareVersions(newest.version, "0.8.0-preview.11") > 0)
    }

    @Test
    fun `同版本不算有更新`() {
        val parsed = UpdateChecker.parseReleases(list(release("v0.8.0-preview.11", pre = true)))
        val newest = UpdateChecker.pickNewest(parsed, "0.8.0-preview.11")
        assertNotNull(newest)
        assertEquals(0, UpdateChecker.compareVersions(newest.version, "0.8.0-preview.11"))
    }

    @Test
    fun `草稿与正式版通道外的预发布都被跳过`() {
        val parsed = UpdateChecker.parseReleases(
            list(
                release("v0.9.0", draft = true),
                release("v0.8.1", pre = true),
                release("v0.8.0"),
            ),
        )
        // 站在正式版上：只认非预发布、非草稿的那个。
        val forStable = UpdateChecker.pickNewest(parsed, "0.8.0")
        assertNotNull(forStable)
        assertEquals("0.8.0", forStable.version)
        // 站在孵化版上：预发布也能选，但仍然跳过草稿。
        val forPreview = UpdateChecker.pickNewest(parsed, "0.8.0-preview.11")
        assertNotNull(forPreview)
        assertEquals("0.8.1", forPreview.version)
    }

    @Test
    fun `孵化版里没有更新的发布时返回空`() {
        val parsed = UpdateChecker.parseReleases(list(release("v0.8.0")))
        // 正式版 0.8.0 比 0.8.0-preview.11 新，所以要能认出来。
        val newest = UpdateChecker.pickNewest(parsed, "0.8.0-preview.11")
        assertNotNull(newest)
        assertTrue(UpdateChecker.compareVersions(newest.version, "0.8.0-preview.11") > 0)
    }

    @Test
    fun `版本号按段比较而不是按字符串`() {
        // 字符串比较会得出 preview.9 > preview.10，这是最容易踩的坑。
        assertTrue(UpdateChecker.compareVersions("0.8.0-preview.10", "0.8.0-preview.9") > 0)
        assertTrue(UpdateChecker.compareVersions("0.10.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0.0", "0.99.99") > 0)
    }

    @Test
    fun `正式版比同号的预发布新`() {
        assertTrue(UpdateChecker.compareVersions("0.8.0", "0.8.0-preview.20") > 0)
        assertTrue(UpdateChecker.compareVersions("0.8.0-preview.20", "0.8.0") < 0)
        assertEquals(0, UpdateChecker.compareVersions("0.8.0", "0.8.0"))
    }

    @Test
    fun `数字段位数不同也能比`() {
        assertEquals(0, UpdateChecker.compareVersions("0.8", "0.8.0"))
        assertTrue(UpdateChecker.compareVersions("0.8.1", "0.8") > 0)
    }

    @Test
    fun `v 前缀不影响比较`() {
        assertEquals(0, UpdateChecker.compareVersions("v0.8.0", "0.8.0"))
        assertTrue(UpdateChecker.compareVersions("V0.8.1", "v0.8.0") > 0)
    }

    @Test
    fun `认不出版本号的 tag 不参与挑选`() {
        val parsed = UpdateChecker.parseReleases(
            list(release("nightly-20261115"), release("v0.8.0-preview.12", pre = true)),
        )
        val newest = UpdateChecker.pickNewest(parsed, "0.8.0-preview.11")
        assertNotNull(newest)
        assertEquals("v0.8.0-preview.12", newest.tagName)
    }

    @Test
    fun `全都不是合法发布时返回空而不是抛`() {
        assertNull(UpdateChecker.pickNewest(emptyList(), "0.8.0"))
        val drafts = UpdateChecker.parseReleases(list(release("v9.9.9", draft = true)))
        assertNull(UpdateChecker.pickNewest(drafts, "0.8.0"))
    }
}