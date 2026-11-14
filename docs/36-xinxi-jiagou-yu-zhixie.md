# 信息架构重排与开源致谢

2026-11-14。对照 PCL 自编版把左栏从「八项平铺」收成「六项 + 下载下的二级项」，
并把刷新按钮从内容区挪到导航项上；同日补上关于页的开源致谢。

## 一、为什么重排

原来的左栏是 `LAUNCH / INSTANCE / DOWNLOAD / MODS / PACKS / LINK / SETTINGS / ABOUT` 八项平铺，
其中「模组」与「光影资源包」各自一级项、内部再套二级页（已装/浏览、光影/资源包）。这有三个毛病：

1. **一级项里混了两个不同层级的动作**：启动和实例是「用」，下载和模组是「装」，摆在一起看不出区别。
2. **二级页藏得深**：想装个光影要「点光影资源包 → 点二级页 → 搜」，而它和装原版其实是同一类事。
3. **整合包埋在下载页最底下**：`.mrpack` 导入卡片在山一样的版本列表与进度条后面，滚到一半就放弃。

PCL 的分法（`custom/Pages/PageDownload/PageDownloadLeft.xaml`）是：下载页左侧栏平铺「原版游戏」，
然后一条分隔标题「社区资源」，下面 Mod / 整合包 / 数据包 / 资源包 / 服务器 / 插件 / 光影包 全部平级。
它的一级导航只有 启动 / 下载 / 联机 / 设置 / 其他 五项（`FormMain.xaml.vb` 的 `PageType` 枚举）。

本项目取的是这个形状，但只取**已经做出来的**那几项：

| 一栏项目 | 二级项 | 说明 |
| --- | --- | --- |
| 启动 | — | 版本选择、Java、内存、启动 |
| 实例 | — | 实例卡片、新建、改内存、删除 |
| 下载 | 原版游戏 / Mod / 整合包 / 资源包 / 光影包 | 五件事并列，不再互相嵌套 |
| 联机 | — | 局域网扫描、直接进、让朋友连到你 |
| 设置 | 启动 / 界面 / 其他 | 原样保留 |
| 关于 | — | 多了一段开源致谢 |

没有搬 PCL 的「数据包」「服务器」「插件」：这三个功能一个都没实现，先摆一排空菜单只会让人觉得坏掉了。

## 二、刷新按钮挂在导航项上

PCL 的每个 `MyListItem` 里都嵌了一个 `MyListItem.Buttons`，社区的清单项挂一个图标按钮，
`ToolTip="刷新"`、`Click="Refresh_Click"`。**刷新是菜单项的属性，不是内容区的控件。**

改前本项目每个页面内容区各有一个「刷新」按钮，位置各不相同（下载页在过滤框右边、模组页在目录行右边、
光影页也是），而「刷新列表」和「重扫目录」还是两个不同的动作，用户分不清。

现在 `Navigation.kt` 的 `SubTab` 多了一个 `refreshable` 字段：

```kotlin
data class SubTab(val id: String, val label: String, val refreshable: Boolean = false)
```

左栏渲染二级项时，`refreshable` 的那一项右侧出现一个小 `↻`（`IconButton` + 文字字符，
ui 模块没有 material-icons 依赖，没必要为一颗图标引一整个包）。
点击不是直接调页面函数，而是 `refreshTick++`，页面靠 `LaunchedEffect(refreshTick)` 接住：
这样页面不需要把自己的内部函数暴露给左栏，切页也不会丢状态。

**整合包页故意不挂 ↻**：它没有可刷新的缓存清单（搜索是即时的），PCL 那一项挂刷新是因为它缓存整合包列表。
不放一个点了没反应的按钮，是「有提示」的反面 —— 假按钮比没有按钮更糟。

## 三、整合包独立成页

`ModpackSection` 的内容本体没动，外面套了一层 `ModpackPage`（同样在 `ModpackSection.kt` 里），
补上独立之后必须自己有的标题、目标目录行（带「选择…」按钮）、默认内存。

原来的 `initialGameVersion = versionText` 改成空串：整合包页前面已经没有版本号输入框了，
猜一个版本反而会把能搜到的包过滤掉 —— 用户搜「农夫乐事」时不该因为某个默认版本而看不到结果。

## 四、开源致谢

### 4.1 用户定的口径

> 后续制作可以参考一些开源的，在优先不动代码的情况下参考理解在制作，部分情况下可以考虑使用代码但要符合人家的要求，
> 优先是自行理解并制作若大量使用按要求标注……使用了标注出来，在介绍页也需标注。（m01729）

落地成三条：

1. 默认「读文档与源码理解做法、自己重写」，这类记 `CreditKind.REFERENCE`，并**写明未使用其代码**。
2. 真的大量使用某项目代码才记 `CreditKind.CODE`；一旦记了，必须同时更新仓库根 `NOTICE`，
   并按对方许可补上版权声明与许可全文。**目前这一组是空的**。
3. 只用到协议/格式/在线服务的记 `CreditKind.SPEC` —— 不算代码依赖，但用户有权知道数据从哪来。

### 4.2 为什么不用 PCL 的中文名数据库

PCL 的中文搜索靠 `PCLCS/Resource/WikiEntries.txt`（946 775 字节 / 31 511 行，含每个工程的 slug、
中文名与热度序列，见同目录 `WikiEntry.cs`）。但 `pcl-src` 源码树里**没有 LICENSE 文件**，
只有 `build/MeloongCore/LICENSE.txt` 与个别文件头的 Apache-2.0 声明，README 只写「包含 PCL 的大部分源代码」。
许可状态不明的数据拷进 Apache-2.0 项目，会把整个项目的授权链条搞浑。

所以中文搜索走自己那 140 余条手写词表（`core/search/SearchKeywords.kt`）：覆盖热门模组、光影、资源包与整合包，
每条 slug 都用 Modrinth 的 `/search` 实测过「搜出来非空」。冷门模组用户仍得给英文名 —— 这个缺口写在 README 的「尚未实现」里。

### 4.3 数据结构

`core/Credits.kt`：

```kotlin
data class Credit(name, kind: CreditKind, license, url = "", note = "")
enum class CreditKind(val label) { CODE, REFERENCE, SPEC, LIBRARY }
object Credits { val items: List<Credit>; fun of(kind: CreditKind): List<Credit> }
```

关于页按 `CreditKind.entries` 的顺序分组渲染，每组前面一句「这一类是什么」——
用户看到 GPL-3.0 会以为自己装了个 GPL 启动器，必须当场说清楚「GPL 代码没有进入本项目」。
分组说明写在 `kindExplanation()` 里，不写进数据（数据是事实，说明是解释，两者变更理由不同）。

### 4.4 单测盯着这件事

`core/src/test/.../core/CreditsTest.kt` 7 例，其中两条是关键：

- `分开记录设计参考与真的用了代码的`：断言 `Credits.of(CreditKind.CODE).isEmpty()`。
  哪天有人真的搬了实现，这条会变红，提醒同时改 `NOTICE`。
- `分组的顺序就是枚举声明顺序`：允许跳过没有条目的组，但顺序不能乱（界面按枚举渲染）。

另外还断言：每条都有名字与许可、没有重名、除 CODE 外每组非空、带网址的都是 `https://` 开头、
中英文说明都提到「哪一类才真的搬了代码」。

## 五、实测

| 项 | 结果 |
| --- | --- |
| `:core:test` | 43 个测试类 / 491 例 / 0 失败 |
| `:ui:compileKotlinJvm` | exit 0 |
| `:app-desktop:compileKotlin` | exit 0 |

## 六、还没做到

- 左栏没有「数据包 / 服务器 / 插件」三项 —— 功能没做，菜单也就不摆。
- 模组页的「已装 / 浏览」两个分栏用的是页面内的 `TextButton`，不是 PCL 那种二级菜单项；
  它在同一个页面里切内容而不是切页面，做成二级菜单会让人以为换了页。
- 致谢里的「使用了代码」一组是空的，因为确实没搬过别人的实现。
- 中文词表只有 140 余条，冷门模组仍得给英文名。
