package io.github.huang404yan.platypus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.Brand

/**
 * 帮助页：给玩家看的说明书。
 *
 * 与 docs/ 下那五十多篇的区别：那些是**开发记录**，写给改代码的人看（为什么这么设计、
 * 当时差在哪、协议字段怎么排）；这一页写给**打开启动器的人**看，只回答「我要做这件事，
 * 该点哪里」。两份东西都必要，但不能互相替代 —— 开发记录里一句「parseMcbbs 第一步查
 * addons[game]」对玩家毫无意义，而「整合包装完在启动页的版本选择里」这句话在开发记录里又太浅。
 *
 * 章节列表为什么从左侧栏挪到页面顶部：改版后一级项只有五项，「更多」下面还挂着
 * 「关于」与「诊断」，八章要是都摊进左列，左列会变成十几行，把「关于」挤到看不见。
 * 章节是「读完一章换下一章」的线性阅读，横排 chips 比竖排列表更贴这个动作。
 */

/** 手册里的一小节：一个标题加若干行正文。 */
data class HelpSection(val heading: String, val lines: List<String>)

/**
 * 手册的一章。
 *
 * @param target 点「去这里」要去的地方；null 表示这一章没有对应的页面可去。
 *   类型是 [NavTarget] 而不是 [Page]：有两章要去的是副页面（版本选择、版本设置），
 *   只在顶部五项里打转的话，用户看完「存档怎么备」还是找不到那一页。
 */
data class HelpChapter(
    val id: String,
    val label: String,
    val title: String,
    val intro: String,
    val sections: List<HelpSection>,
    val target: NavTarget? = null,
)

const val HELP_TAB_START = "start"
const val HELP_TAB_INSTALL = "install"
const val HELP_TAB_ACCOUNT = "account"
const val HELP_TAB_LAUNCH = "launch"
const val HELP_TAB_LINK = "link"
const val HELP_TAB_SERVER = "server"
const val HELP_TAB_SAVES = "saves"
const val HELP_TAB_PROBLEM = "problem"

/**
 * 手册正文。
 *
 * 写法上刻意**不追求穷尽**：每一项只说清「在哪里、点了会发生什么、什么情况下不会生效」。
 * 穷尽式说明书（把 36 个 server.properties 键逐个解释一遍）没有读者 —— 真要看那个的人
 * 会直接去看配置页，那里每个键下面本来就有一行说明。
 */
val HELP_CHAPTERS: List<HelpChapter> = listOf(
    HelpChapter(
        id = HELP_TAB_START,
        label = "第一次打开",
        title = "第一次打开该做什么",
        intro = "从这里到进游戏，一共三步。中间任何一步失败都会在界面上写原因，不用去翻日志。",
        sections = listOf(
            HelpSection(
                "第一步：告诉它游戏装在哪",
                listOf(
                    "启动页右栏有一行「游戏目录」。如果这台机器装过官方启动器，它会自己填上 %APPDATA%\\.minecraft；没有就点旁边的「选择」挑一个空文件夹。",
                    "空文件夹完全可以 —— 后面的下载会自己建出 versions / libraries / assets 三层。",
                    "一个目录就是一份独立的游戏。想让不同整合包互不干扰，就给它们各自一个目录。",
                ),
            ),
            HelpSection(
                "第二步：装一个游戏版本",
                listOf(
                    "去「下载 → 原版游戏」，挑一个版本点安装。它会按顺序做四件事：下版本清单 → 下核心 jar → 下依赖库 → 下素材文件。",
                    "进度条下面会写当前在做什么。断了可以直接再点一次，已经下好的部分不会重下。",
                    "要装模组就先看「装东西」那一章 —— 装完加载器再装模组，顺序反了游戏起不来。",
                ),
            ),
            HelpSection(
                "第三步：点启动",
                listOf(
                    "回到「启动」，左栏选中刚装的实例，点大按钮「启动」。",
                    "第一次启动会先检测 Java：没装会提示你去把下面的「Java 运行时」改掉，或者点「装一个」。",
                    "内存默认是自动分配的。整合包（尤其带光影的）可以在左栏点「改」，拖到 4096 以上。",
                ),
            ),
        ),
        target = NavTarget.Tab(Page.DOWNLOAD, DOWNLOAD_TAB_VANILLA),
    ),
    HelpChapter(
        id = HELP_TAB_INSTALL,
        label = "装东西",
        title = "装模组、整合包、资源包、光影、数据包",
        intro = "「下载」下面七项看着像一回事，其实产物落到不同地方，装错位置的现象也不一样。",
        sections = listOf(
            HelpSection(
                "五类东西分别落在哪",
                listOf(
                    "Mod → 实例的 mods/：要配合加载器。原版游戏直接放进去不会有任何效果，游戏甚至不会报错。",
                    "整合包 → 新建一个实例，里面自带 mods / config / 资源包。它不是一个文件，是一整套。",
                    "资源包 → 实例的 resourcepacks/：改材质与音效，原版也能用。",
                    "光影包 → 实例的 shaderpacks/：**必须**先装 Iris 或 OptiFine 这类光影加载器，否则放进去游戏里也看不到这一项。",
                    "数据包 → 某个世界的 datapacks/：改玩法（合成表、结构、进度），只对那个世界生效。",
                ),
            ),
            HelpSection(
                "装模组的正确顺序",
                listOf(
                    "先装加载器，再装模组。加载器在「下载 → 原版游戏」里选版本时一并选（Fabric / Forge / NeoForge / Quilt 四种）。",
                    "然后去「下载 → Mod」，搜索并安装。页面上会标出「适用于哪个版本」，装到不匹配的实例上它会拦下来。",
                    "同一个模组装了新旧两个版本，现象是游戏一启动就崩在加载阶段。遇到这种先去看「Mod」页的冲突提示。",
                ),
            ),
            HelpSection(
                "整合包怎么来、怎么走",
                listOf(
                    "从 Modrinth 搜、或者拿一个本地的 .zip / .mrpack 导入。也支持从 CurseForge 站点下的包（它的 manifest.json 没有 MCBBS 那套字段，这里做了回退读取）。",
                    "导入时会问你「新建实例还是装进已有实例」。装进已有实例会**覆盖同名文件**，想保平安就新建。",
                    "自己做的整合包也能导出去分享：启动页选中实例 → 「版本设置」→「导出整合包」。三种格式任选，包体积与文件完整性各有取舍，页面上每个选项下面都写了一句。",
                ),
            ),
        ),
        target = NavTarget.Tab(Page.DOWNLOAD, DOWNLOAD_TAB_MODS),
    ),
    HelpChapter(
        id = HELP_TAB_ACCOUNT,
        label = "登录",
        title = "怎么登录、三种方式有什么区别",
        intro = "启动页左栏上面那三个按钮（正版 / 离线 / 第三方）决定这一次用谁的身份，下面列的是已经登录过的账号。",
        sections = listOf(
            HelpSection(
                "三种登录方式",
                listOf(
                    "正版（微软）：点「用微软账号登录」，会给你一个网址和一串设备码，在浏览器里输入即可。需要自己的 Client ID（见下）。",
                    "离线：只填一个名字，不验证。**只能进离线服**，进正版服会被踢。名字会被拿来算一个固定 UUID，改名等于换了一个人。",
                    "外置登录（Yggdrasil）：填认证服务器地址 + 那个服的账号密码。适用于自建服与 LittleSkin 这类第三方认证。",
                ),
            ),
            HelpSection(
                "为什么正版登录要自己填 Client ID",
                listOf(
                    "微软的设备码登录要求每个应用有自己的 Client ID，而这个 ID 不能随安装包分发（公开二进制里带一个等于让所有人共用一个应用）。",
                    "所以孵化版里这一格是空的，需要你自己去 Azure 注册一个免费应用，把它填进「设置 → 账号 → 应用 ID」。",
                    "填完一次就一直记住，存在本机配置文件里，不会上传到任何地方。",
                ),
            ),
            HelpSection(
                "外置登录额外做的一件事",
                listOf(
                    "它除了拿令牌，还会往游戏进程挂一个 authlib-injector（第一次用会自动下载，约 200 KB 到游戏目录下的 platypus 里）。",
                    "所以外置账号启动会比正版账号慢一点点，那是在准备这个。",
                    "认证服务器地址填成 .../authserver 这种带后缀的也能认，它会自己归一成根地址。",
                ),
            ),
        ),
        target = NavTarget.Tab(Page.SETTINGS, SETTINGS_TAB_ACCOUNT),
    ),
    HelpChapter(
        id = HELP_TAB_LAUNCH,
        label = "启动",
        title = "启动、内存、额外参数",
        intro = "启动页左栏那几格是「这次启动用多少内存、以谁的身份」。改完点启动会自动保存。",
        sections = listOf(
            HelpSection(
                "内存给多少合适",
                listOf(
                    "默认是「自动」：启动器按版本和加载器估一个值，改实例的其它设置不会把它弄丢。",
                    "原版 2048 MB 够用；带十几个模组 4096 起；带光影的整合包 6144 到 8192。",
                    "给太多反而更卡：Java 回收垃圾时要扫整块堆，堆越大那一下停顿越明显。",
                    "填的数会被夹到允许范围内，夹了会在下面写一行说明，不是悄悄改。",
                ),
            ),
            HelpSection(
                "版本隔离是什么",
                listOf(
                    "开启后不同实例的 mods / config / 存档 各自独立；关掉则所有实例共用游戏目录下那一份。",
                    "官方启动器是不隔离的（所有版本共用一份 mods）。如果你习惯了那种行为，可以在版本设置里把它关掉。",
                    "切换隔离方式时，启动器会把已有的文件搬到对应位置，不会让你「改完设置模组就没了」。",
                ),
            ),
            HelpSection(
                "崩了怎么办",
                listOf(
                    "启动失败会给你一份崩溃报告：错误类型、可能的原因、建议的动作。常见的那几种（内存不足、模组缺失、Java 版本不对）都翻成了中文。",
                    "报告里有一行「日志末尾」，那才是真正有用的细节。要贴给别人的话，用报告里的复制按钮整份复制。",
                    "游戏是在进主菜单之后才崩的，启动器拦不到那一段 —— 去实例目录的 logs/latest.log 里看。",
                ),
            ),
        ),
        target = NavTarget.Tab(Page.LAUNCH),
    ),
    HelpChapter(
        id = HELP_TAB_LINK,
        label = "联机",
        title = "和朋友一起玩",
        intro = "同一台路由器下面不用来这一页 —— 游戏里「对局域网开放」就够了。这里管的是「不在一个网里也能一起玩」。",
        sections = listOf(
            HelpSection(
                "不在同一个局域网：用陶瓦",
                listOf(
                    "「联机 → 本地联机」最上面那张卡：房主建房拿到一个房间码，把码发给朋友，朋友填进来点加入。双方都不用动路由器。",
                    "陶瓦的程序不随启动器分发（它是 AGPL-3.0，本项目是 Apache-2.0），第一次点「安装 / 更新陶瓦」会下约 8 MB。",
                    "房主那一侧**没有**可以直接发出去的直连地址，所以房主态只显示房间码 —— 陶瓦给房主的地址是游客本机的回环地址，发出去对方一定连不上。",
                    "房主关掉游戏之后，那个房间码就作废了，要重新建房拿新码。",
                ),
            ),
            HelpSection(
                "有公网 IPv6 的话可以直连",
                listOf(
                    "「本地联机」里还有一张卡列出本机的公网 IPv6 地址，复制给朋友、他在游戏里「直接连接」里填就行。",
                    "前提是双方都有公网 IPv6（很多手机流量有、家里宽带没有），而且没有防火墙挡着。",
                    "列表里的内网 IPv4 故意没列出来：它只在同一个路由器下有用，而那种情况根本不用来这一页。",
                ),
            ),
            HelpSection(
                "连上了但是进不去世界",
                listOf(
                    "房主那边必须先在游戏里「对局域网开放」，陶瓦才有东西可以转发。没开放时状态会停在「正在等待游戏开启局域网」。",
                    "双方的游戏版本要一致，差一个小版本也可能进不去，但陶瓦那层看着是「已连上」。这种情况报错来自游戏自己。",
                    "进了世界却看不到对方：房主的世界是不是开了「仅限邀请」或者把对方拉黑了，这些游戏内的设置启动器看不到。",
                ),
            ),
        ),
        target = NavTarget.Tab(Page.LINK),
    ),
    HelpChapter(
        id = HELP_TAB_SERVER,
        label = "开服",
        title = "开一个自己的服务器",
        intro = "「下载 → 服务端」装本体，「联机 → 服务器」那一栏开局、接管、看日志。",
        sections = listOf(
            HelpSection(
                "装与开",
                listOf(
                    "装的时候会问你端口和内存，然后下载 server.jar、写 eula.txt、生成启动脚本。",
                    "没同意 EULA 时服务端会直接退出，看起来像「闪一下就没了」。启动器会先挡住并告诉你去看哪份协议。",
                    "开服前会预检端口：被占了就直说被谁占了，而不是让服务端起不来再让你去翻一屏英文。",
                ),
            ),
            HelpSection(
                "配置页改什么",
                listOf(
                    "配置页只列了三十多个常用的键，每个下面一行中文说明。剩下的键原版有几十个，改了多半只会变慢或起不来，所以没全列。",
                    "文件本身是「按行保留」的：你手写的注释、被 # 注释掉的旧值、服务端自己排的顺序，都不会因为改一个端口就被重排。",
                    "服务端正在跑的时候配置页是只读的。原版启动时把配置读进内存、之后自己写回，我们改盘上的文件会被它盖掉。",
                ),
            ),
            HelpSection(
                "白名单、管理员、命令",
                listOf(
                    "白名单和 OP 都可以在配置页里加人，名字会去查正版 UUID；查不到（或者断网）就按离线规则算，界面上会明确标出来。",
                    "服务端**正在跑**时，它只认控制台里发的那条 whitelist add —— 因为它会拿内存里的名单定期覆盖磁盘上的文件。",
                    "配置页里加完人会顺手把命令发进控制台，并且明确告诉你「当场生效」还是「下次开服生效」。",
                ),
            ),
        ),
        target = NavTarget.Tab(Page.DOWNLOAD, DOWNLOAD_TAB_SERVER),
    ),
    HelpChapter(
        id = HELP_TAB_SAVES,
        label = "存档",
        title = "存档、备份、别把世界弄丢",
        intro = "存档是唯一没法重新下载的东西 —— 模组能重下、Java 能重装、整合包能重导，世界没了就是没了。",
        sections = listOf(
            HelpSection(
                "备份在哪、怎么看",
                listOf(
                    "启动页选中实例 → 「版本设置」→「存档」：上面列世界（带图标、最后游玩时间、大小），下面列备份。",
                    "备份是一个 zip，落在游戏目录下的 platypus/saves-backup/。备份里存的是世界目录本身，不套一层世界名。",
                    "还原默认**不覆盖**同名世界：会问你换个名字，或者明确勾上「覆盖它」。删世界也是先备份再删。",
                ),
            ),
            HelpSection(
                "自动备份",
                listOf(
                    "默认是开的：每次游戏**正常退出**之后，按每个世界各自上次备份的时间判断要不要备，默认 24 小时一份、保留 5 份。",
                    "只在退出码是 0 时才备。崩在半路的时候世界文件未必写完，这时候打出来的 zip 可能是个半份。",
                    "间隔与保留份数在「设置 → 存档」里改。那一页要按底部的「保存」才生效。",
                ),
            ),
            HelpSection(
                "换电脑、发给朋友",
                listOf(
                    "单个世界：版本设置里对着那个世界点「备份」，再去备份列表里找到那个 zip，拷走就行。",
                    "整个实例：版本设置的「导出整合包」会把模组、配置、资源包一起打包。默认**不含存档**。",
                    "换机器之后：把 zip 放进目标机器的存档目录再导入；或者直接把整个实例目录拷过去。",
                ),
            ),
        ),
        target = NavTarget.InstanceSelect,
    ),
    HelpChapter(
        id = HELP_TAB_PROBLEM,
        label = "出问题了",
        title = "起不来、连不上、看着不对",
        intro = "先看界面上写了什么。这个启动器的原则是「失败要说出原因」，绝大多数问题界面上已经给了解释。",
        sections = listOf(
            HelpSection(
                "按现象找",
                listOf(
                    "点了启动没反应：多半是没选实例，或者游戏目录那一行是空的。",
                    "闪一下就退出：看崩溃报告里的第一行。常见是 Java 版本不对（1.17 之后要 17 以上）或内存给小了。",
                    "卡在「正在下载」很久：去「设置 → 下载」把下载源改成「优先国内镜像」再试。",
                    "中文显示成方块或乱码：日志已经强制用 UTF-8 读写了；游戏里的字要装中文字体资源包。",
                    "服务端起不来：先看端口预检的提示，多半是被上一个没关干净的服务端占着。",
                ),
            ),
            HelpSection(
                "怎么反馈问题",
                listOf(
                    "崩溃报告里有「复制」按钮，复制出来的是完整的一份，比截图强很多。",
                    "说清楚三件事就够了：做了什么、期望发生什么、实际发生了什么。",
                    "「更多 → 诊断」里有一键导出：环境、Java、实例、最近日志，都打成一个 zip。",
                ),
            ),
            HelpSection(
                "这一版还没有的",
                listOf(
                    "Android 端还不能真的进游戏（图形转译那一层要在真机上做，开发暂停了）。",
                    "没有云同步存档、没有定时备份（只有退出后那份）、不能整目录打包。",
                    "自动更新不会自动安装，只会给你下载链接。",
                ),
            ),
            HelpSection(
                "想自己确认「它记的东西在哪」",
                listOf(
                    "所有配置都在游戏目录下的 platypus/ 里：账号表、实例清单、设置都在这一层，删掉等于重置。",
                    "认证相关的 jar（authlib-injector）与陶瓦的程序也在这一层，都是「装了才出现」的。",
                    "不想要哪个功能留下的东西，删对应的子目录即可 —— 启动器下次用到时会自己再建。",
                ),
            ),
        ),
        target = NavTarget.Tab(Page.MORE, MORE_TAB_ABOUT),
    ),
)

/** 按 id 找一章；找不到退回第一章（章节 id 被外部改坏时不至于白屏）。 */
fun helpChapterOf(id: String): HelpChapter =
    HELP_CHAPTERS.firstOrNull { it.id == id } ?: HELP_CHAPTERS.first()

/**
 * 帮助页。
 *
 * @param onGo 点「去这里」时跳页；由上层决定怎么切页、要不要开副页面。
 */
@Composable
fun HelpPage(onGo: (NavTarget) -> Unit = {}) {
    // 章节选中状态放在这一页里：它是「读的时候翻到哪一章」，不是要跨页记住的东西，
    // 下次从「更多」进来从头看起反而更合理。
    var current by remember { mutableStateOf(HELP_TAB_START) }
    val chapter = helpChapterOf(current)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("帮助", style = MaterialTheme.typography.headlineSmall)
        Text(
            "每一章只讲「这件事该点哪里」。想知道为什么这么设计，去看仓库里的 docs/（那是写给改代码的人看的）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HELP_CHAPTERS.forEach { item ->
                val selected = item.id == current
                Text(
                    item.label,
                    Modifier
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            } else {
                                Color.Transparent
                            },
                            MaterialTheme.shapes.small,
                        )
                        .clickable { current = item.id }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            chapter.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            chapter.intro,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        chapter.sections.forEach { section -> HelpSectionCard(section) }
        val target = chapter.target
        if (target != null) {
            // 手册最容易失效的地方就是「看完了不知道按钮在哪」，所以能跳就让它跳。
            OutlinedButton(onClick = { onGo(target) }) {
                Text(goLabelOf(target))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            Brand.NAME_ZH + " v" + Brand.version,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「去这里」按钮上的字。副页面得说清是「打开哪一页」，不能只说页面名。 */
internal fun goLabelOf(target: NavTarget): String = when (target) {
    is NavTarget.Tab -> "去「" + target.page.label + "」"
    NavTarget.InstanceSelect -> "去「版本选择」"
}

@Composable
private fun HelpSectionCard(section: HelpSection) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                section.heading,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            section.lines.forEach { line ->
                Row(Modifier.fillMaxWidth()) {
                    // 「·」用普通字符而不是图标：ui 模块没有 icons 依赖，为一个列表点引一整个图标库不划算。
                    Text("· ", style = MaterialTheme.typography.bodySmall)
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}