package io.github.huang404yan.platypus.core.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadSourceTest {

    private val bmcl = "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
    private val mojang = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    @Test
    fun `设置里存的字符串认得出两个选项`() {
        assertEquals(DownloadSource.AUTO, DownloadSource.of("auto"))
        assertEquals(DownloadSource.MIRROR, DownloadSource.of("mirror"))
    }

    @Test
    fun `认不出的值与空串都退回默认`() {
        assertEquals(DownloadSource.DEFAULT, DownloadSource.of(""))
        assertEquals(DownloadSource.DEFAULT, DownloadSource.of("   "))
        assertEquals(DownloadSource.DEFAULT, DownloadSource.of("only-official"))
        // 老版本没有这个字段，读出来是空串；升级后必须还能起来，并且落到镜像优先。
        assertEquals(DownloadSource.MIRROR, DownloadSource.DEFAULT)
    }

    @Test
    fun `大小写与两端空格都不影响`() {
        assertEquals(DownloadSource.MIRROR, DownloadSource.of(" MIRROR "))
        assertEquals(DownloadSource.MIRROR, DownloadSource.of("Mirror"))
    }

    @Test
    fun `镜像主机认得出来`() {
        assertTrue(DownloadSources.isMirror(bmcl))
        assertTrue(DownloadSources.isMirror("https://gh-proxy.com/https://example.com/a.jar"))
        assertTrue(DownloadSources.isMirror("https://ghfast.top/whatever"))
        assertFalse(DownloadSources.isMirror(mojang))
        assertFalse(DownloadSources.isMirror(""))
    }

    @Test
    fun `名字里带镜像主机但不是那个站的不算镜像`() {
        // 拼在前面的假域名不能因为包含子串就被当成镜像 —— 那会把文件下到别人家去。
        assertFalse(DownloadSources.isMirror("https://evilbmclapi2.bangbang93.com/a.jar"))
        assertFalse(DownloadSources.isMirror("https://bmclapi2.bangbang93.com.evil.cn/a.jar"))
    }

    @Test
    fun `自动模式保持原来的顺序`() {
        val ordered = DownloadSources.order(mojang, listOf(bmcl), DownloadSource.AUTO)
        assertEquals(listOf(mojang, bmcl), ordered)
    }

    @Test
    fun `优先镜像时镜像排到前面而其余保持原序`() {
        val other = "https://launcher.mojang.com/a.jar"
        val ordered = DownloadSources.order(mojang, listOf(bmcl, other), DownloadSource.MIRROR)
        assertEquals(bmcl, ordered.first())
        // 非镜像之间的相对顺序不能被打乱。
        assertEquals(listOf(mojang, other), ordered.drop(1))
    }

    @Test
    fun `候选里重复的地址只留一条`() {
        val ordered = DownloadSources.order(mojang, listOf(mojang, bmcl), DownloadSource.MIRROR)
        assertEquals(listOf(bmcl, mojang), ordered)
    }

    @Test
    fun `没有主地址时只剩镜像`() {
        assertEquals(listOf(bmcl), DownloadSources.order("", listOf(bmcl), DownloadSource.AUTO))
        assertEquals(listOf(bmcl), DownloadSources.order("  ", listOf(bmcl, ""), DownloadSource.AUTO))
    }

    @Test
    fun `一个候选都没有时返回空表`() {
        assertTrue(DownloadSources.order("", emptyList(), DownloadSource.MIRROR).isEmpty())
    }

    @Test
    fun `文本接口那一串也能按偏好重排`() {
        val urls = listOf(mojang, bmcl)
        assertEquals(urls, DownloadSources.orderAll(urls, DownloadSource.AUTO))
        assertEquals(listOf(bmcl, mojang), DownloadSources.orderAll(urls, DownloadSource.MIRROR))
    }

    @Test
    fun `文本接口那一串会去掉空串与重复`() {
        val ordered = DownloadSources.orderAll(listOf(mojang, "", mojang, bmcl), DownloadSource.MIRROR)
        assertEquals(listOf(bmcl, mojang), ordered)
    }

    @Test
    fun `连接数没调过时按代码里的默认走`() {
        // DownloadPreference 是进程级全局量，其它用例可能改过它，进出各归零一次。
        val original = DownloadPreference.connections
        DownloadPreference.connections = 0
        try {
            assertEquals(DownloadPreference.DEFAULT_CONNECTIONS, DownloadPreference.effectiveConnections())
        } finally {
            DownloadPreference.connections = original
        }
    }

    @Test
    fun `调过就按调过的数开连接`() {
        val original = DownloadPreference.connections
        try {
            DownloadPreference.connections = 8
            assertEquals(8, DownloadPreference.effectiveConnections())
            DownloadPreference.connections = DownloadPreference.MAX_CONNECTIONS
            assertEquals(DownloadPreference.MAX_CONNECTIONS, DownloadPreference.effectiveConnections())
        } finally {
            DownloadPreference.connections = original
        }
    }

    @Test
    fun `越界的连接数被收敛回区间`() {
        // 手改设置文件能写进 99999；不收敛的话一次会开十万条 socket。
        assertEquals(DownloadPreference.MAX_CONNECTIONS, DownloadPreference.normalizeConnections(99999))
        assertEquals(0, DownloadPreference.normalizeConnections(-1))
        assertEquals(0, DownloadPreference.normalizeConnections(0))
        assertEquals(DownloadPreference.MIN_CONNECTIONS, DownloadPreference.normalizeConnections(1))
    }

    @Test
    fun `上限比默认值大，界面上的档位才有意义`() {
        // 要是哪天有人把上限调到比默认还小，设置页那排档位就会显示成「比自动还少」。
        assertTrue(DownloadPreference.MAX_CONNECTIONS > DownloadPreference.DEFAULT_CONNECTIONS)
        assertTrue(DownloadPreference.DEFAULT_CONNECTIONS >= DownloadPreference.MIN_CONNECTIONS)
    }

    @Test
    fun `两个选项的说明里都有为什么要它`() {
        // 界面上只显示 label 与 note，note 空掉的话用户看到的就是两个没差别的单选项。
        DownloadSource.entries.forEach {
            assertTrue(it.label.isNotBlank(), it.id)
            assertTrue(it.note.length > 10, it.id)
            assertTrue(it.id == it.id.trim().lowercase(), it.id)
        }
    }
}