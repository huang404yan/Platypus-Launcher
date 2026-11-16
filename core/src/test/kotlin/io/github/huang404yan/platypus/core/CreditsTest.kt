package io.github.huang404yan.platypus.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreditsTest {

    @Test
    fun `每条致谢都有名字和许可`() {
        for (item in Credits.items) {
            assertTrue(item.name.isNotBlank(), "名字不能为空")
            assertTrue(item.license.isNotBlank(), "${item.name} 缺许可说明")
            assertTrue(item.license.none { it == '\n' }, "${item.name} 的许可里不该有换行")
        }
    }

    @Test
    fun `致谢里没有重名条目`() {
        val names = Credits.items.map { it.name }
        assertEquals(names.size, names.distinct().size, "重名会让人以为标了两遍")
    }

    @Test
    fun `除「使用了代码」外每一类都有内容`() {
        // CODE 允许为空 —— 现在就是空的，那意味着没搬过别人的实现（见下一条用例）。
        for (kind in CreditKind.entries.filter { it != CreditKind.CODE }) {
            assertTrue(Credits.of(kind).isNotEmpty(), "${kind.label} 这一组不该是空的")
        }
    }

    @Test
    fun `分开记录设计参考与真的用了代码的`() {
        // 目前没有任何项目被搬过实现 —— 这一条空着是有意的，不是漏写。
        // 哪天真的用了别人的代码，这里会变红，提醒同时改 NOTICE 与关于页。
        assertTrue(Credits.of(CreditKind.CODE).isEmpty(), "用了代码就要在 NOTICE 里也标注")
        assertTrue(Credits.of(CreditKind.REFERENCE).isNotEmpty(), "至少要有设计参考的记录")
        for (item in Credits.of(CreditKind.REFERENCE)) {
            assertTrue(item.license.isNotBlank(), "参考对象也要写清许可")
        }
    }

    @Test
    fun `带网址的条目都是完整链接`() {
        for (item in Credits.items.filter { it.url.isNotBlank() }) {
            assertTrue(item.url.startsWith("https://"), "${item.name} 的网址应以 https:// 开头")
        }
    }

    @Test
    fun `分组的顺序就是枚举声明顺序`() {
        val kinds = Credits.items.map { it.kind }.distinct()
        // 允许跳过没有条目的组（现在 CODE 就是空的），但顺序不能乱。
        assertEquals(kinds, CreditKind.entries.filter { it in kinds }, "界面按枚举顺序渲染，条目也应这么排")
    }

    @Test
    fun `说明文案把「参考」和「用了代码」分开讲`() {
        assertTrue(Credits.INTRO_ZH.contains("使用了代码"), "得让用户知道哪一类才真的搬了代码")
        assertTrue(Credits.INTRO_EN.contains("attributed"), "英文说明也要提到标注")
    }

    @Test
    fun `漏报过的两个编译期依赖现在都在册`() {
        // XZ for Java 与 JNA 一直是编译进包的依赖（core 用前者解 .tar.xz，app-desktop 用后者调
        // Win32 API 画标题栏），NOTICE 里却漏了很久。这条用例钉住它们，免得下次改版本目录时又漏。
        val names = Credits.items.joinToString("\n") { it.name }
        assertTrue(names.contains("XZ for Java"), "XZ for Java 是编译进包的依赖，必须列进致谢")
        assertTrue(names.contains("JNA"), "JNA 只被桌面端用，但同样随包分发，必须列进致谢")
    }

    @Test
    fun `随包分发的 Java 运行时与只在运行时下载的资产分开记`() {
        // 这两类容易混：Oracle JDK 25 裁剪版是 jpackage 打进 runtime/ 的，陶瓦则一个字节都不进包，
        // 只在用户点「安装」时下到他自己盘上。它们的许可义务完全不同，所以都要各自有一条。
        val items = Credits.items.filter { it.kind == CreditKind.LIBRARY }
        assertTrue(items.any { it.license.contains("NFTC") }, "自带运行时按 Oracle NFTC 分发，得写清许可名")
        assertTrue(items.any { it.name.contains("Terracotta") }, "陶瓦是运行时下载的 AGPL 资产，得单独记一条")
    }
}
