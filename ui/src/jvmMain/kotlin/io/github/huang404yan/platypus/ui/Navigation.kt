package io.github.huang404yan.platypus.ui

/**
 * 一级导航项下面的一个二级项。
 *
 * id 不用下标：页面里存的是「上次选的是哪个二级」，用 Index 存的话，
 * 以后往前插一个新项，用户的选择就会莫名其妙跳到隔壁。
 */
data class SubTab(val id: String, val label: String)

/**
 * 每个一级页面自己有几个二级项。
 *
 * 表放在这里而不是各页面里，是因为左侧栏要拿它渲染第二层 —— 页面自己知道
 * 有什么二级项，但侧边栏看不见页面内部。代价是加一个二级项要改两处（这里和页面），
 * 换来的是导航结构只有一份定义，不会出现「侧边栏有这一项、页面里没有」。
 *
 * 空表 = 这个一级页面暂时还是单页，侧边栏不展开第二层。
 */
val PAGE_SUB_TABS: Map<Page, List<SubTab>> = mapOf(
    Page.MODS to listOf(
        SubTab("installed", "已装"),
        SubTab("browse", "浏览"),
    ),
    Page.PACKS to listOf(
        SubTab("shaders", "光影"),
        SubTab("resourcepacks", "资源包"),
    ),
    Page.SETTINGS to listOf(
        SubTab("launch", "启动"),
        SubTab("ui", "界面"),
        SubTab("other", "其他"),
    ),
)

/** 某个一级页面的默认二级项：没配就返回空串。 */
fun defaultSubTab(page: Page): String = PAGE_SUB_TABS[page]?.firstOrNull()?.id.orEmpty()
