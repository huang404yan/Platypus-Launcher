# 35 · 界面可用性：窗口自适应、滚动、选目录、中文搜索

2026-11-14。用户反馈原话：

> 实列：多个实例不能滑动，除非最大化窗口需要加上滑动若后续出现多个实例会无法选择；下载：不支持中文搜索且无明显下载提示；启动：根目录只能手动输入，不适合使用需要加上可以选择的按钮；窗口：大部分针对最大化或放大才能正常使用需要自适应；先参考pcl自编版进行优化，当前是能用但不好用，藏的深、无提示、臃肿、操作繁琐；

拆成四条：① 实例多时要能滚 ② 下载页要能中文搜、状态要显眼 ③ 目录要能选不要手打 ④ 窗口别逼人最大化。四条都不是「加功能」，是把已有能力摆到用户看得见、够得着的地方。

## 一、窗口：先给它一个合理尺寸

以前 `app-desktop/.../Main.kt` 的 `Window` 既没有 `state` 也没有最小尺寸，Compose 按内容要的尺寸开窗，结果一开就是偏窄的窗口，右侧按钮被裁在框外——用户的第一反应当然是「最大化才能用」。

```kotlin
val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)
Window(..., state = windowState) {
    LaunchedEffect(Unit) { window.minimumSize = Dimension(1024, 640) }
}
```

1280x820 在 1080p 屏上不用最大化就能放下整页；1024x640 是下限（再小侧栏和表单会互相挤，各页面另有滚动兜底）。

`ui/.../App.kt` 里加了一个断点：

```kotlin
val compact = LocalWindowInfo.current.containerSize.width < 1100
```

窄窗口下侧栏 220dp → 180dp、内边距 16dp → 12dp，内容区留白 24dp → 16dp。

按 PCL 的参照：`custom/FormMain.xaml` 的 MinWidth=810 / MinHeight=470、默认 850x500——它不是「默认最大化」，而是**整个界面按 850x500 设计**，小窗里也放得下。我们没必要压到那么紧凑，但方向一致：默认尺寸就要能用。

## 二、实例页：整页滚动，而不是「固定头 + 可滚列表」

原来根布局是 `Column(fillMaxSize)` + `Box(weight(1f))` 包一个 `LazyColumn`。问题在于顶部那几行（目录行、新建卡片、提示）在窄窗口里自己就吃掉大半屏，权重分完列表只剩几十像素——滚轮落在列表外没反应，表现就是「多个实例看不到、选不着」。

现在根 Column 直接加 `verticalScroll`，列表改用 `merged.forEach { key(...) { InstanceCard(...) } }`：

- 滚轮只作用在一个方向上，不存在「内层列表抢滚动」的困惑；
- 实例数量是个位数到十几的量级，不需要 LazyColumn 的回收；
- 顶部内容跟着一起滚，不会被永久占位。

同一处理用在 `LinkPage`（加「让朋友连到你」段之后内容超一屏）。

## 三、选目录：JFileChooser 而不是 AWT FileDialog

新建 `ui/.../DirectoryPicker.kt`：

```kotlin
internal fun pickDirectory(initial: String?): File?   // 目录选择器，初始目录取当前输入值的父级
```

为什么不用 `java.awt.FileDialog`：它在 Windows 上只能选**文件**，要选目录得先点进目标目录、随便选一个文件、再指望调用方回退一层——这正是整合包那段 `pickArchive()` 的做法，选目录时这么做很别扭。`javax.swing.JFileChooser` 有 `DIRECTORIES_ONLY`，语义直接。

接入了六处：启动页、实例页、下载页、设置页「默认游戏目录」、模组页、光影资源包页。每处都是「输入框 + 选择… + 原来的按钮」三件套，选完自动触发一次重扫，不用再点「扫描」。

## 四、下载页：状态置顶 + 过滤空结果说清楚

### 4.1 安装状态条

原来进度、速度、失败原因全在整页最下面，一滚就出屏。用户点完「一键安装」、页面没动，会以为按钮没反应，再点几次。

新增 `InstallStatusBanner`，钉在说明文字下面：

| 阶段 | 显示 |
| --- | --- |
| Running | 「正在<步骤名> · <进度明细> · <速度>/s · 已用 <时长>」 |
| Failed | 「卡在「<步骤名>」：<原因>」，用 errorContainer 配色 |
| Finished | 「安装完成：<实例名> 已就绪」 |
| Idle | 整条不渲染 |

下方详细的分步列表、进度条、重试按钮一个都没删——两处读的是同一份 `OneClickRunner` 状态。

### 4.2 过滤词打错要立刻说

版本列表的过滤框只看正式版。以前过滤词打错就是一个空列表，看起来像版本清单挂了。现在明写：「没有名字里带「xxx」的正式版。清空过滤框能看全部 N 个；也可以直接在版本号里手填。」

## 五、中文搜索：Modrinth 不认中文，只能替它翻译

### 5.1 PCL 是怎么做的（看清楚了再动手）

`custom/Modules/Resource/ResourceSearcher.vb:188-292` 是 PCL 的「中文搜索」区段。它不是猜，而是查表：

- 数据源是内置数据库 `PCLCS/Resource/WikiEntries.txt`（946 775 字节 / 31 511 行），由 `WikiEntry.cs` 解析；
- 每行先是 slug（`@` 前缀 = 仅 Modrinth，后缀 = 两平台同 slug，中缀 `a@b` = 两个不同 slug），最后是中文名；
- 中文查询时对中文名做模糊匹配（前 100 条、相似度阈值 0.25），取出候选后**换成英文关键词**再请求 API；
- Modrinth 侧按「相似度 × 热度」累加词权重，取权重最高的一个词当关键词，同时把前 100 条的 slug 攒起来走 `/v2/projects?ids=[...]` 批量直取；
- 全都没匹配上才报「无搜索结果，请尝试搜索其英文名称」。

关键点：**它的中文能力来自一份外部中文名数据库**，不是算法。

### 5.2 我们为什么不照搬那个文件

`pcl-src` 里没有 LICENSE 文件（只有 `build/MeloongCore/LICENSE.txt` 与个别文件头的 Apache-2.0 声明），README 只写「包含 PCL 的大部分源代码」。把 95 万字节、3 万余行的 `WikiEntries.txt` 直接拷进本项目（Apache-2.0）许可状态不明，不做。

### 5.3 我们的做法：手写小词典 + 可点候选

`core/src/main/kotlin/io/github/huang404yan/platypus/core/search/SearchKeywords.kt`：

| 成员 | 作用 |
| --- | --- |
| `SearchTerm(zh, en, kind)` | 一条对照，`Kind` 分 MOD / SHADER / RESOURCE_PACK / MODPACK / ANY |
| `SearchKeywords.all` | 约 90 条中文→英文对照（钠→sodium、铟→indium、高清修复→optifine、互补→complementary…） |
| `looksCjk(query)` | 见到拉丁字母或数字就判否，中英混写的英文查询不拦 |
| `translate(query, kind, limit)` | 整串相等优先，其次包含关系按中文长度降序，再次半截词 |
| `popular(kind, limit)` | 空查询时给这一类常用词，当作起手引导 |

界面侧 `ui/.../SearchHints.kt` 的 `ChineseSearchHint`：中文查询时给一句「搜不到是因为 Modrinth 不认中文」+ 一组可点的英文候选 chip（点一下就把关键词替换掉）；输入框为空时给热门词。接入四处：模组页、光影页、资源包页、整合包远程搜索。

为什么不假装支持：Modrinth 的 `/v2/search` 只匹配英文名与 slug，直接回一句「没搜到」会让用户以为启动器坏了。明说原因 + 给出下一步，比沉默强。

## 六、实测

| 项 | 结果 |
| --- | --- |
| `:core:test` | 42 个测试类 / **484 例** / 0 失败（改造前 473 例） |
| `:ui:compileKotlinJvm` | exit=0 |
| `:app-desktop:compileKotlin` | exit=0 |
| `:androidApp:assembleDebug` | exit=0 |

## 七、还没做到

- **中文词典只有约 90 条**：常见模组覆盖得到，冷门模组仍要用户自己给英文名。扩充是纯数据活，跟着实际搜不到的词补，比一次堆几千条更实际。
- **没做「中文名 → 项目直取」**：PCL 那种「查到就跳过关键词搜索、直接按 slug 拿项目」的路子更准，但我们没有可信的中文名来源，硬做只会造出一个比关键词搜索更容易出错的旁路。
- **界面点击验证由用户手动做**：以上均为编译 + 单测 + 静态核对，没有真实鼠标点击的截图或录屏。
- **窗口最小 1024x640 是拍出来的**：没有逐页在 1024 宽下截图确认每个控件都完整。
