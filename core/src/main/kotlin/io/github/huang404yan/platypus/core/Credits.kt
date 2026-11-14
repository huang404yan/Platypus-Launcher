package io.github.huang404yan.platypus.core

/**
 * 一条致谢记录。
 *
 * [kind] 是这条记录的性质，界面上按它分组显示 —— 用户最该先看到的是「真的用了别人的代码」这一类，
 * 其次才是「只读了文档自己写」和「只用格式/协议」。
 */
data class Credit(
    val name: String,
    val kind: CreditKind,
    val license: String,
    val url: String = "",
    val note: String = "",
)

/** 致谢的四种性质。声明顺序就是界面上的显示顺序。 */
enum class CreditKind(val label: String) {
    /** 真的把对方的实现搬过来了。必须在仓库 NOTICE 与关于页同时标注，并满足对方许可的附加条款。 */
    CODE("使用了代码"),

    /** 只读文档、issue、源码来理解「怎么解决这个问题」，代码是自己写的。 */
    REFERENCE("设计参考（未使用代码）"),

    /** 只用到公开协议、数据格式或在线服务，不涉及对方代码。 */
    SPEC("格式与协议"),

    /** 编译进安装包的第三方库。 */
    LIBRARY("运行时依赖"),
}

/**
 * 关于页「开源致谢」的唯一真源。
 *
 * 三条纪律（2026-11-14 定）：
 * 1. 默认走「读文档与源码理解做法、自己重写」，这类记 [CreditKind.REFERENCE]，并写明未使用其代码。
 * 2. 真的大量使用某个项目的代码才记 [CreditKind.CODE]；一旦记了，必须同时更新仓库根 NOTICE，
 *    并按对方许可补上版权声明与许可全文。**目前这一组是空的**，不是漏写。
 * 3. 只用到协议/格式/在线服务的记 [CreditKind.SPEC] —— 不算代码依赖，但用户有权知道数据从哪来。
 *
 * 为什么不抄 PCL 的中文名数据库：它的源码树里没有 LICENSE 文件，只有个别文件头写了 Apache-2.0，
 * 许可状态不明；把它那份 3 万余行的 WikiEntries.txt 拷进来会给整个项目带来版权风险。
 * 中文搜索因此改成手写常用词表（core/search/SearchKeywords.kt），做得少但来路清楚。
 */
object Credits {

    const val INTRO_ZH: String =
        "这个启动器是集各家所长拼出来的：功能设计、交互顺序、报错文案的思路都看过别人的作品。" +
            "代码默认是自己写的 —— 下面标注「使用了代码」的才真的搬了实现，其余都只读了文档与源码。"

    const val INTRO_EN: String =
        "Platypus Launcher borrows its product design from several existing launchers. " +
            "Code is written from scratch except where explicitly marked as reused, and reused code is " +
            "attributed under its own license below."

    /** 全部致谢条目。按 [CreditKind] 的声明顺序分组显示。 */
    val items: List<Credit> = listOf(
        // —— 设计参考 ——
        Credit(
            name = "PCL2（Plain Craft Launcher 2）",
            kind = CreditKind.REFERENCE,
            license = "专有许可（源码公开但未声明开源许可）",
            note = "下载页左侧栏的分组方式（原版游戏 / 社区资源：Mod、整合包、数据包、资源包、" +
                "服务器、插件、光影包）、把刷新按钮挂在菜单项而不是内容区、中文名搜索的整体思路、" +
                "启动页把版本选择与启动按钮放在左栏、Java 下载列表按版本 json 里声明的 component 精确匹配" +
                "（匹配不上才退回主版本区间）、微软登录七步链路里 XSTS 错误码的对照表与「先查持有记录再拿档案」的顺序，" +
                "都来自它。没有复制任何源码，也没有使用它内置的" +
                "中文名数据库（该项目未附带 LICENSE，许可状态不明）。",
        ),
        Credit(
            name = "HMCL（Hello Minecraft! Launcher）",
            kind = CreditKind.REFERENCE,
            license = "GPL-3.0",
            note = "实例目录的布局、离线登录的命名规则、Java 运行时的按版本挑选方式参考了它的公开文档。" +
                "GPL 代码一行未取，否则本项目不得不整体改成 GPL。",
        ),
        Credit(
            name = "Prism Launcher",
            kind = CreditKind.REFERENCE,
            license = "GPL-3.0",
            note = "实例隔离的形态（每个实例一份独立目录，共享 libraries 与 assets）参考了它的设计。",
        ),
        Credit(
            name = "美西螈启动器（Axolotl Launcher）",
            kind = CreditKind.REFERENCE,
            license = "GPL-3.0",
            url = "https://github.com/Axolotl-Launcher/Axolotl",
            note = "设置页按「改了会影响什么」分组、把 Java 运行时单独列成一张带已装标记的表，" +
                "这两处排版参考了它。它是 Rust + Vue 的桌面应用，技术栈与本项目完全不同，" +
                "一行代码都搬不过来。",
        ),

        Credit(
            name = "FCL（FoldCraftLauncher）",
            kind = CreditKind.REFERENCE,
            license = "GPL-3.0",
            url = "https://github.com/FCL-Team/FoldCraftLauncher",
            note = "安卓端 Java 页的形态参考了它：不问「你本机装了哪些 Java」，而是由启动器自己下几份" +
                "常用运行时让用户挑。它的实现是 Java + XML 布局，与本项目的 Compose 不是一回事，" +
                "代码一行未取。",
        ),

        // —— 格式与协议 ——
        Credit(
            name = "Modrinth API",
            kind = CreditKind.SPEC,
            license = "Modrinth 使用条款",
            url = "https://docs.modrinth.com/api/",
            note = "模组、光影、资源包、整合包的检索与下载，以及版本里的依赖声明（required / optional / " +
                "incompatible / embedded）。.mrpack 是它定义的整合包格式。",
        ),
        Credit(
            name = "Fabric Meta",
            kind = CreditKind.SPEC,
            license = "Apache-2.0",
            url = "https://meta.fabricmc.net/",
            note = "Fabric 与 Quilt 的加载器版本清单和 profile 格式。",
        ),
        Credit(
            name = "Forge / NeoForge 官方安装器",
            kind = CreditKind.SPEC,
            license = "LGPL-2.1",
            url = "https://files.minecraftforge.net/",
            note = "以子进程方式调用官方 installer，让它自己生成版本 profile 与库清单，本项目不链接、不修改其代码。",
        ),
        Credit(
            name = "MCBBS v2 整合包格式",
            kind = CreditKind.SPEC,
            license = "社区约定",
            note = "本地整合包解析（manifest.json + modpack.json + overrides）。",
        ),
        Credit(
            name = "离线登录 UUID 规则",
            kind = CreditKind.SPEC,
            license = "社区约定",
            note = "OfflinePlayer:<名字> 的 MD5 转 v3 UUID，这样和其它启动器离线模式下的存档、服务器白名单能互通。",
        ),

        // —— 运行时依赖 ——
        Credit(
            name = "安卓 JRE 资产（FCL 的 jreAssets）",
            kind = CreditKind.LIBRARY,
            license = "OpenJDK：GPL-2.0 with Classpath Exception",
            url = "https://github.com/FCL-Team/FoldCraftLauncher",
            note = "手机上要一份能在 bionic libc 上跑、按 ABI 预编译好的 OpenJDK，自己编不现实。" +
                "运行时本体是 OpenJDK，不是 FCL 的代码；本项目不把它打进安装包，而是用户点「下载」时" +
                "从 FCL 仓库的 jreAssets 目录取 tar.xz（universal + bin-<abi> 两包）解到应用私有目录。" +
                "GPL-2.0+CE 允许随产品分发而不传染，但仍在此标注来源。",
        ),
        Credit(
            name = "Kotlin / kotlinx-coroutines / kotlinx-serialization",
            kind = CreditKind.LIBRARY,
            license = "Apache-2.0",
            url = "https://kotlinlang.org/",
            note = "语言、协程与 JSON 序列化。",
        ),
        Credit(
            name = "Compose Multiplatform",
            kind = CreditKind.LIBRARY,
            license = "Apache-2.0",
            url = "https://www.jetbrains.com/compose-multiplatform/",
            note = "桌面与安卓共用的界面层。",
        ),
        Credit(
            name = "AndroidX（core-ktx / activity-compose / Compose BOM）",
            kind = CreditKind.LIBRARY,
            license = "Apache-2.0",
            url = "https://developer.android.com/jetpack",
        ),
    )

    /** 取某一类的条目；界面按类分段渲染，空组直接跳过。 */
    fun of(kind: CreditKind): List<Credit> = items.filter { it.kind == kind }
}
