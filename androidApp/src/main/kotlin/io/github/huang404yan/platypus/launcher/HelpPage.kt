package io.github.huang404yan.platypus.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 手机上的帮助页。
 *
 * 内容只讲安卓这条线能做的事 —— 桌面那份帮助（ui/HelpPage.kt）里有一半是「装 Forge」
 * 「开服」「备份存档」这类手机上没有的东西，照搬过来会让读者找不着北。
 *
 * 分成两栏：左边八章目录，右边一段可滚的正文。横屏两千多像素宽，左右分栏比一个
 * 长条往下滚更容易「我在第几章」；竖屏也照这个比例分，不会挤成一列。
 */
@Composable
internal fun HelpPage() {
    var current by remember { mutableStateOf(ANDROID_HELP_CHAPTERS.first().id) }
    val chapter = ANDROID_HELP_CHAPTERS.firstOrNull { it.id == current } ?: ANDROID_HELP_CHAPTERS.first()
    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(210.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "帮助",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ANDROID_HELP_CHAPTERS.forEach { item ->
                val selected = item.id == current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
                            MaterialTheme.shapes.small,
                        )
                        .clickable { current = item.id }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .padding(start = 20.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (chapter.intro.isNotEmpty()) {
                Text(
                    text = chapter.intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            chapter.sections.forEach { section ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = section.heading,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    section.lines.forEachIndexed { index, line ->
                        // 以「1. 」「2. 」开头的行是步骤，缩进一格并单列，读起来是清单；
                        // 其余当普通段落。这样数据里只写文本，不用给每行标类型。
                        val numbered = line.length > 2 && line[0] in '0'..'9' && line[1] == '.'
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (numbered) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(start = if (numbered) 10.dp else 0.dp),
                        )
                    }
                }
            }
            if (chapter.code.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    chapter.code.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            if (chapter.note.isNotEmpty()) {
                Text(
                    text = chapter.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}

/** 一章：标题 + 一句导语 + 若干小节（每节是标题加几行文字）+ 可选代码块 + 可选提示。 */
private data class HelpChapter(
    val id: String,
    val label: String,
    val title: String,
    val intro: String = "",
    val sections: List<HelpSection> = emptyList(),
    val code: List<String> = emptyList(),
    val note: String = "",
)

private data class HelpSection(val heading: String, val lines: List<String>)

private val ANDROID_HELP_CHAPTERS: List<HelpChapter> = listOf(
    HelpChapter(
        id = "start",
        label = "开始使用",
        title = "这台手机上的启动器怎么用",
        intro = "一共七项：启动、实例、下载、联机、帮助、设置、关于。真正要动的只有三处 —— 授予文件权限、装一份 Java、下载一个游戏版本。",
        sections = listOf(
            HelpSection(
                heading = "第一步：给它一个目录",
                lines = listOf(
                    "启动器自己不造游戏目录，它去你指定的地方找。第一次进「实例」页会显示一个默认路径，多半是 /storage/emulated/0/.minecraft。",
                    "Android 11 起，读写别的应用的外部存储要单独授权。页面上那张卡会提示「还没给访问权限」，点了会跳到系统的「所有文件访问权限」页，找到「鸭嘴兽·孵化版」打开即可。",
                    "授权之后回到启动器，点「重新扫描」，找到的实例会按版本名列出来。",
                ),
            ),
            HelpSection(
                heading = "第二步：装一份 Java",
                lines = listOf(
                    "进「设置 → Java」。这一页列的是这台机器上的 ABI（几乎一定是 arm64-v8a）以及能装的运行时。",
                    "点一个运行时右侧的「安装」，它会下载解压到应用私有目录，装完在列表里显示版本号与占用空间。",
                    "装错 ABI 的包会启动时直接报 UnsatisfiedLinkError，界面在下载之前就按 Build.SUPPORTED_ABIS 过滤过一遍，正常点不到错的那个。",
                ),
            ),
            HelpSection(
                heading = "第三步：下载一个游戏版本",
                lines = listOf(
                    "进「下载 → 原版与加载器」。左边是版本列表，默认只显示正式版；搜索框可以填版本号或类型。",
                    "选一个版本，右边会显示它依赖的资产与库。点底部的「下载」，进度会按阶段显示：清单 → 库 → 资产 → 主 jar。",
                    "下完回到「实例」页重新扫描，新版本就出现了。顺序装错了也没关系 —— 下载器按 sha1 校验，重复的文件直接跳过。",
                ),
            ),
        ),
        note = "下面几章按「你要做什么」分开写。遇到对不上的界面，先看「遇到问题」那一章。",
    ),
    HelpChapter(
        id = "account",
        label = "账号与身份",
        title = "用谁的身份进游戏",
        intro = "三种：正版（微软账号）、外置登录（自建服务器或第三方认证站）、离线。启动时用哪一个，看「设置 → 账号」里选中的那一条。",
        sections = listOf(
            HelpSection(
                heading = "离线模式",
                lines = listOf(
                    "什么都不用配。用户名在「设置 → 常规」里填，启动页会显示「离线（用户名）」。",
                    "离线只能进「离线模式」的服务器，进正版验证的服务器会被踢。",
                ),
            ),
            HelpSection(
                heading = "微软账号（正版）",
                lines = listOf(
                    "点「用微软账号登录」，界面会显示一串短码和一个网址。把短码在浏览器里输进去，用微软账号确认，手机这边会自动接上。",
                    "登录成功后本地只留一枚 refresh_token，存在游戏目录下的 platypus/accounts.json 里 —— 那个文件等价于账号密码，别发给别人。",
                    "如果这一页让你先填「应用 ID」，说明这个安装包没自带。申请办法就写在同一个页面里，四步，免费。",
                ),
            ),
            HelpSection(
                heading = "外置登录",
                lines = listOf(
                    "同一个页面往下滚，填认证服务器地址（例如 LittleSkin 的 https://littleskin.cn/api/yggdrasil）、账号、密码，点「登录这个服务器」。",
                    "启动时会自动带上 authlib-injector 的参数，不用你自己下 jar。",
                ),
            ),
        ),
        note = "换身份不会影响存档，但会影响「用哪个 UUID 进服务器」。同一个世界里用不同身份进，在服务器看来是两个人。",
    ),
    HelpChapter(
        id = "install",
        label = "模组与资源包",
        title = "往实例里加东西",
        intro = "都在「下载」下面：模组、光影资源包、服务端。它们和三样东西有关 —— 实例的版本号、加载器类型、目标目录，选错了装上去也不会加载。",
        sections = listOf(
            HelpSection(
                heading = "模组",
                lines = listOf(
                    "「下载 → 模组」先选实例。选中之后列表会自动按这个实例的版本号与加载器过滤 —— 给 Fabric 实例塞一个 Forge 模组是永远不会生效的。",
                    "装好的模组在同一个页面切到「已安装」看，可以停用（改后缀）而不删除。",
                    "有的模组要求特定版本的依赖，页面上会标出来。缺依赖的表现是启动到一半崩掉，日志里写着 NoClassDefFoundError。",
                ),
            ),
            HelpSection(
                heading = "光影与资源包",
                lines = listOf(
                    "「下载 → 光影资源包」顶部三个按钮切换光影、资源包、数据包。光影要求实例已经装了支持光影的加载器（Iris / OptiFine），纯原版装了也不会亮。",
                    "资源包的 pack_format 要跟游戏版本对得上；页面上只显示它声明的值，不替你判断能不能用。",
                ),
            ),
        ),
        note = "手机上下载的是 jar 与 zip 本身，安装只是把它们拷进对应目录，没有「安装器」那一步。",
    ),
    HelpChapter(
        id = "launch",
        label = "启动与自检",
        title = "为什么点了启动还进不去",
        intro = "启动页把「进游戏之前要满足的条件」逐条列出来了，点「做启动自检」，每一条后面会写通过还是没过、没过是为什么。",
        sections = listOf(
            HelpSection(
                heading = "自检看什么",
                lines = listOf(
                    "实例目录在不在、版本 json 读不读得出来；Java 运行时能不能执行（版本号与架构）；",
                    "登录身份换不换得到令牌；图形后端有没有对应的 .so；JNI 层能不能加载。",
                    "任何一条不过，自检结果会直接写出下一步该做什么，比如「去设置 → Java 装一份」或者「这个 ABI 没有可用的渲染后端」。",
                ),
            ),
            HelpSection(
                heading = "安卓上现在还进不了游戏",
                lines = listOf(
                    "这是已知限制，不是你的操作问题。两处卡住：① Android 10 起禁止从应用可写目录加载原生库，而 JVM 的 libjvm.so 必须落在那样的目录里；② 图形层还没接，没有 EGL 上下文，游戏拿不到窗口。",
                    "「启动」按钮能起 JVM，但它会在起游戏窗口那一步停下。日志在诊断页可以看。",
                ),
            ),
            HelpSection(
                heading = "诊断页",
                lines = listOf(
                    "「设置 → 诊断」把设备型号、SDK、ABI、各目录是否可写、运行时清单、JNI 能否加载，一次性列出来。报问题的时候把这一页截图发出去，比描述半天有用。",
                ),
            ),
        ),
    ),
    HelpChapter(
        id = "link",
        label = "联机",
        title = "和别人的世界连起来",
        intro = "两条路：自己开一个局域网房间让别人进来，或者加入别人给的地址。",
        sections = listOf(
            HelpSection(
                heading = "直接进（同一个 Wi-Fi）",
                lines = listOf(
                    "在游戏里按 Esc →「对局域网开放」，游戏会给出一个端口号（四到五位）。",
                    "用「联机」页把那个端口和本机地址填进去，别人在同一 Wi-Fi 下就能连。地址是本机地址枚举出来的，通常 192.168.x.x。",
                ),
            ),
            HelpSection(
                heading = "不在同一个网络",
                lines = listOf(
                    "要用陶瓦（Terracotta）那类内网穿透工具。桌面端已经接好了，手机上这一版只做了「直接进」和本机地址枚举。",
                ),
            ),
        ),
        note = "服务器本身要允许「在线模式关闭」才能用离线身份进；正版验证的服务器只认微软账号。",
    ),
    HelpChapter(
        id = "server",
        label = "服务端",
        title = "手机上装个服务端",
        intro = "能装、能改配置，但这一版不能在应用里直接开服 —— 开服要一个长驻的 java 子进程，安卓上做这个还要先解决后台被杀与前台服务通知。",
        sections = listOf(
            HelpSection(
                heading = "能做的",
                lines = listOf(
                    "「下载 → 服务端」选一个目录与类型（原版 / Paper / Fabric），它会下载安装并生成 eula.txt、server.properties。",
                    "配置页可以改常见的 server.properties 字段，加白名单、加管理员，改完直接写盘。",
                    "目录里会生成一个启动脚本，你可以拷到电脑上跑。",
                ),
            ),
            HelpSection(
                heading = "要注意的",
                lines = listOf(
                    "和电脑共用同一个服务端目录时，两边的配置会互相覆盖。别把同一个目录同时给手机和电脑用。",
                    "eula.txt 里的同意条款要你自己看一遍再改成 true —— 启动器不会替你点同意。",
                ),
            ),
        ),
    ),
    HelpChapter(
        id = "problem",
        label = "遇到问题",
        title = "常见故障怎么查",
        intro = "先看「设置 → 诊断」那一页，大部分问题在那里就能看出是哪一环断了。",
        sections = listOf(
            HelpSection(
                heading = "找不到实例 / 扫出来是空的",
                lines = listOf(
                    "九成是目录不对或没给权限。实例页最上面那张卡会分别说明。",
                    "安卓上 .minecraft 常在 /storage/emulated/0/.minecraft；有些启动器把它放在 /storage/emulated/0/Android/data/<包名>/files/.minecraft，那个位置从 Android 11 起别的应用读不到。",
                ),
            ),
            HelpSection(
                heading = "下载卡住或反复失败",
                lines = listOf(
                    "先看进度条下面那行字写的是哪个阶段。库和资产走的是不同域名，国内网络对其中一个不通很常见。",
                    "「设置 → 常规」里可以切换下载源。切换之后重跑一次下载，已经下好的文件会按 sha1 跳过。",
                ),
            ),
            HelpSection(
                heading = "登录失败",
                lines = listOf(
                    "报「用户名或密码错误」是服务端原话，不是启动器编的；注意有些外置认证站要求用邮箱而不是游戏名。",
                    "报「还没有填 Client ID」说明这个包没自带应用 ID，去账号页填一个（那儿写着申请办法）。",
                ),
            ),
            HelpSection(
                heading = "怎么把日志发出来",
                lines = listOf(
                    "游戏日志在实例目录下的 logs/latest.log。崩了还会在 crash-reports/ 里留一份。",
                    "启动器自己的日志在「诊断」页底部，可以整段复制。",
                ),
            ),
        ),
        note = "报问题时给三样东西最有用：诊断页截图、latest.log、你点的是哪一步。",
    ),
    HelpChapter(
        id = "about",
        label = "关于这个启动器",
        title = "它是什么，不是什么",
        intro = "鸭嘴兽启动器是从零写的一个 Minecraft 启动器，不是 PCL、HMCL、Prism 的分支，也没有抄它们的代码。",
        sections = listOf(
            HelpSection(
                heading = "两条通道",
                lines = listOf(
                    "看版本号就能分清：带 -preview 的是孵化版，不带的是正式版（1.0.0 起）。",
                    "正式版：安装包自带正版登录要的应用 ID，第一次启动会要求先登录一个正版账号，验证一次就够了。",
                    "孵化版：等于测试版也等于开源版，安装包里不带任何人的私有凭据，正版登录的应用 ID 要你自己填一个。",
                    "两者包名不同，可以同时装在一台手机上。",
                ),
            ),
            HelpSection(
                heading = "它不做什么",
                lines = listOf(
                    "不内置任何登录跳转、不代理你的账号。令牌只存在本地。",
                    "不上传诊断数据。诊断页那些内容只显示在屏幕上，你不复制它就不出这台设备。",
                    "不替你做「一键变强」之类的整合包改动。",
                ),
            ),
        ),
        note = "版本与更新记录在「关于」页；源码仓库地址也在那里。",
    ),
)
