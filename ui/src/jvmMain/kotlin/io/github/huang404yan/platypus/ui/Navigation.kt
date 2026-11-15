package io.github.huang404yan.platypus.ui

/**
 * 一级导航。照 PCL 的顶部横排五项做（custom/FormMain.xaml:123-131 的五个 MyRadioButton：
 * 启动 / 下载 / 联机 / 设置 / 更多），不再用左侧竖排。
 *
 * 为什么放弃竖排：竖排把「实例」也算成一级项，于是启动页、实例页、下载页各有一份
 * 「这一版怎么启动」的入口，用户得记「改内存去哪、换身份去哪、看 Java 去哪」。
 * 顶部横排五项是 PCL 十年迭代下来的答案 —— 玩家心里只有五件事：
 * 今天玩哪个（启动）、装个什么（下载）、跟人一起玩（联机）、改我的设置（设置）、
 * 其余杂项（更多）。
 *
 * [MORE] 里收的是「帮助 / 关于 / 诊断」：三件事都是「出了问题或想了解」时才去，
 * 平时不占视线；但它们又必须能一眼找到，所以不做成二级里再套一层。
 */
enum class Page(val label: String) {
    LAUNCH("启动"),
    DOWNLOAD("下载"),
    LINK("联机"),
    SETTINGS("设置"),
    MORE("更多"),
}

/**
 * 副页面：压在主界面上、右上角带返回的那种（PCL 的 InstanceSelect / InstanceSetup）。
 *
 * 不占顶部五格：它们永远是从某个具体对象出发的（「这个版本」的设置、
 * 「选哪个版本」的选择），脱离那个对象就没有意义，做成一级项会变成空页面。
 */
sealed interface Overlay {
    /** 版本选择：启动页点「版本选择」进来，选一个就压栈回去。 */
    data object InstanceSelect : Overlay

    /** 版本设置：对某一个实例做精细设置（Java / 内存 / Mod / 资源包 / 存档 / 导出）。 */
    data class InstanceSetup(val instanceKey: String) : Overlay
}

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
     * 挂在这里而不是页面内容区顶行，是照 PCL 的做法（PageDownloadLeft.xaml 的
     * MyListItem.Buttons）：刷新是「对这份清单的重新读取」，属于导航项的属性，
     * 放在左栏和清单挨着，用户不用进内容区找。点下去只发一个信号，具体刷什么由页面决定。
     */
    val refreshable: Boolean = false,
)

/**
 * 每个一级页面自己有几个二级项。
 *
 * 表放在这里而不是各页面里，是因为左侧栏要拿它渲染第二层 —— 页面自己知道
 * 有什么二级项，但左边那一列看不见页面内部。代价是加一个二级项要改两处（这里和页面），
 * 换来的是导航结构只有一份定义，不会出现「左栏有这一项、页面里没有」。
 *
 * 空表 = 这个一级页面没有二级项，左栏不展开第二层（启动页与联机页就是这种：
 * 启动页的东西全在左栏那张卡里，联机页用页面内的分段切换）。
 *
 * 「下载」下面挂七件事，是照 PCL 的信息架构重排的（PageDownloadLeft.xaml）：
 * 装原版、装 Mod、装整合包、装数据包、装资源包、装光影、下服务端，都是「从网上下东西」，
 * 只是产物落点不同。以前 Mod 与光影资源包是两个一级页，和人「我想装点什么」的心智
 * 对不上 —— 用户得先知道那东西叫「模组」，才找得到装它的入口。
 *
 * 「设置」下的 Java 并进了「启动」：它不属于「往盘上装一份运行时」这种独立动作，
 * 而是「这个实例用哪个 Java」，和内存、窗口一样是启动参数的一部分。
 */
val PAGE_SUB_TABS: Map<Page, List<SubTab>> = mapOf(
    Page.DOWNLOAD to listOf(
        SubTab("vanilla", "原版游戏", refreshable = true),
        SubTab("mods", "Mod", refreshable = true),
        // 整合包页没有「已经拉下来的清单」可刷新：搜到的结果随搜随出，导入进度也自带重试。
        SubTab("modpack", "整合包"),
        // 数据包挨着整合包（都是「改世界」），资源包与光影包挨在一起（都是「改观感」）。
        SubTab("datapacks", "数据包", refreshable = true),
        SubTab("resourcepacks", "资源包", refreshable = true),
        SubTab("shaders", "光影包", refreshable = true),
        // 服务端是「下载一个能开服的东西」，没有已装清单可刷，也就不挂刷新。
        SubTab("server", "服务端"),
    ),
    Page.SETTINGS to listOf(
        // 标可刷新：这一页里要读本机 Java 清单，用户重装 / 换目录后需要手动重读。
        SubTab(SETTINGS_TAB_LAUNCH, "启动", refreshable = true),
        // 账号紧跟「启动」：它决定「以谁的身份进游戏」，比目录和内存更靠前。
        SubTab(SETTINGS_TAB_ACCOUNT, "账号"),
        // 下载源属于「装东西的那条路怎么走」，和外观无关，放中间。
        SubTab(SETTINGS_TAB_DOWNLOAD, "下载"),
        // 存档自动备份：它是玩家唯一下不回来的东西，单独一项。
        SubTab(SETTINGS_TAB_SAVES, "存档"),
        SubTab(SETTINGS_TAB_UI, "界面"),
        SubTab(SETTINGS_TAB_OTHER, "其他"),
    ),
    // 「更多」三项：帮助在最前（遇到问题第一站），关于与诊断在后。
    Page.MORE to listOf(
        SubTab(MORE_TAB_HELP, "帮助"),
        SubTab(MORE_TAB_ABOUT, "关于与鸣谢"),
        SubTab(MORE_TAB_DIAG, "诊断"),
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

/** 「更多」下的三个二级项 id。 */
const val MORE_TAB_HELP = "help"
const val MORE_TAB_ABOUT = "about"
const val MORE_TAB_DIAG = "diag"

/** 某个一级页面的默认二级项：没配就返回空串。 */
fun defaultSubTab(page: Page): String = PAGE_SUB_TABS[page]?.firstOrNull()?.id.orEmpty()

/**
 * 帮助页「去这里」按钮的目标。
 *
 * 不能只写 [Page]：帮助里有一章讲「存档怎么备」，它的目标是某个具体实例的
 * 版本设置（副页面），而不是某个顶部一级项。把两种情况收进一个类型，
 * 帮助页就不用知道主界面是用什么方式摆页面的。
 */
sealed interface NavTarget {
    /** 切到某个一级项，并可指定它的二级项；sub 为 null 表示用默认二级项。 */
    data class Tab(val page: Page, val sub: String? = null) : NavTarget

    /** 打开「版本选择」副页面。 */
    data object InstanceSelect : NavTarget
}