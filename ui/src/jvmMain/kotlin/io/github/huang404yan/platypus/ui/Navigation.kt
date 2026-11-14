package io.github.huang404yan.platypus.ui

/**
 * 一级导航项下面的一个二级项。
 *
 * id 不用下标：页面里存的是「上次选的是哪个二级」，用 Index 存的话，
 * 以后往前插一个新项，用户的选择就会莫名其妙跳到隔壁。
 */
data class SubTab(
    val id: String,
    val label: String,
    /**
     * 这一项旁边要不要挂一个「刷新」按钮。
     *
     * 挂在这里而不是页面内容区顶行，是照 PCL 的做法（`PageDownloadLeft.xaml` 的
     * `MyListItem.Buttons`）：刷新是「对这份清单的重新读取」，属于导航项的属性，
     * 放在左栏和清单挨着，用户不用进内容区找。点下去只发一个信号，具体刷什么由页面决定。
     */
    val refreshable: Boolean = false,
)

/**
 * 每个一级页面自己有几个二级项。
 *
 * 表放在这里而不是各页面里，是因为左侧栏要拿它渲染第二层 —— 页面自己知道
 * 有什么二级项，但侧边栏看不见页面内部。代价是加一个二级项要改两处（这里和页面），
 * 换来的是导航结构只有一份定义，不会出现「侧边栏有这一项、页面里没有」。
 *
 * 空表 = 这个一级页面暂时还是单页，侧边栏不展开第二层。
 *
 * 「下载」下面挂七件事，是照 PCL 的信息架构重排的（`PageDownloadLeft.xaml`）：
 * 装原版、装 Mod、装整合包、装数据包、装资源包、装光影、下服务端，都是「从网上下东西」，
 * 只是产物落点不同。以前 Mod 与光影资源包是两个一级页，和人「我想装点什么」的心智
 * 对不上 —— 用户得先知道那东西叫「模组」，才找得到装它的入口。
 *
 * PCL 那一列还有「服务器」与「插件」两项。服务端这里做了（原版 server.jar + eula.txt +
 * 开服脚本）；插件不做：Modrinth 的 `project_type:plugin` facet 实测会退化成全站模组搜索
 * （返回的 hits 全是 project_type=mod），而且插件要落在服务端的 `plugins/` 目录里，
 * 这一版还没有「服务端实例」这个概念，硬塞一个入口只会让人装到客户端目录里。
 */
val PAGE_SUB_TABS: Map<Page, List<SubTab>> = mapOf(
    Page.DOWNLOAD to listOf(
        SubTab("vanilla", "原版游戏", refreshable = true),
        SubTab("mods", "Mod", refreshable = true),
        // 整合包页没有「已经拉下来的清单」可刷新：搜到的结果随搜随出，导入进度也自带重试。
        // PCL 那一项挂刷新是因为它会缓存整合包列表，这里不缓存，就不放一个点了没反应的按钮。
        SubTab("modpack", "整合包"),
        // 数据包与资源包的位置照 PCL 的 PageDownloadLeft.xaml：数据包挨着整合包（都是「改世界」），
        // 资源包与光影包挨在一起（都是「改观感」）。
        SubTab("datapacks", "数据包", refreshable = true),
        SubTab("resourcepacks", "资源包", refreshable = true),
        SubTab("shaders", "光影包", refreshable = true),
        // 服务端是「下载一个能开服的东西」，没有已装清单可刷，也就不挂刷新。
        SubTab("server", "服务端"),
    ),
    Page.SETTINGS to listOf(
        SubTab("launch", "启动"),
        // 账号紧跟「启动」：它决定「以谁的身份进游戏」，比目录和内存更靠前。
        SubTab("account", "账号"),
        // Java 单独一项：它不属于「启动参数」，而是「往盘上装一份运行时」，
        // 和下载页那几项是同一类动作，只是入口挂在设置里（PCL 也是这么分的）。
        SubTab("java", "Java", refreshable = true),
        SubTab("ui", "界面"),
        SubTab("other", "其他"),
    ),
)

/** 「下载」下的七个二级项 id。页面按下标判断自己该渲染哪一块。 */
const val DOWNLOAD_TAB_VANILLA = "vanilla"
const val DOWNLOAD_TAB_MODS = "mods"
const val DOWNLOAD_TAB_MODPACK = "modpack"
const val DOWNLOAD_TAB_DATAPACKS = "datapacks"
const val DOWNLOAD_TAB_RESOURCEPACKS = "resourcepacks"
const val DOWNLOAD_TAB_SHADERS = "shaders"
const val DOWNLOAD_TAB_SERVER = "server"

/** 某个一级页面的默认二级项：没配就返回空串。 */
fun defaultSubTab(page: Page): String = PAGE_SUB_TABS[page]?.firstOrNull()?.id.orEmpty()
