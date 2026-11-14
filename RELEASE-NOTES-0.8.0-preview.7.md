# 鸭嘴兽·孵化版 0.8.0-preview.7

> 这是**孵化版（Pre-release）**，不是正式版。功能不完整，界面与数据格式随时会变。
> 正式版号段从 1.0.0 起，与 0.x-preview.N 互不占用。

## 这一版比 preview.6 多了什么

### 下载栏按 PCL 的分法重排，多了数据包与服务端

- **左栏从六项扩成「下载」下的七项**（`a2ef0df`）：原版游戏 / Mod / 整合包 / 数据包 / 资源包 / 光影包 / 服务端。整合包从下载页底部独立成页 —— 它和一键装原版是并列的两件事，塞在版本列表和进度条下面时用户要滚过一整屏才看得见，这正是「藏得深」。
- **刷新按钮挂到了左栏菜单项上**（`a2ef0df`）：照 PCL 的 `PageDownloadLeft.xaml`，刷新是导航项的属性（`MyIconButton ToolTip="刷新"`），不是内容区控件。二级项右侧一个 ↻，内容区不再放第二个入口。整合包页故意不挂 —— 它不缓存清单，放一个点了没反应的按钮只会让人怀疑坏了。
- **数据包能装了**：复用资源包那一套扫描/安装代码，落 `datapacks/`，靠 `pack.mcmeta` 认，查询用 `loaders=["datapack"]`。这个值不能跟资源包共用 `["minecraft"]` —— 实测 Terralith 2.5.4 同时发两条文件，只有 `loaders=["datapack"]` 那条才是数据包本体。中文词典跟着加了 11 条（泰拉瑞斯、地牢酒馆、连锁挖矿…）。
- **服务端能下了**：Mojang 原版 `server.jar`，顺手写好 `eula.txt`（`eula=true`，写的是同意不是空文件）和双击就能开的 `start.bat`（Windows，CRLF + `chcp 65001` + `cd /d %~dp0` + `pause`）/ `start.sh`（LF）。目录里已有能用的 jar 时不重复下载，并明说「没重复下载」。**只有原版** —— Forge / NeoForge / Fabric / Paper 的服务端各要另跑安装器，页面上写明了。
- **插件这一项不做，理由是实测的**：Modrinth 的 `project_type:plugin` facet 会把全站模组搜出来（返回项的 `project_type` 全是 `mod`），插件实际靠 `loaders=["bukkit","paper","spigot"]` 这类值区分；而且插件要落进服务端的 `plugins/`，这一版还没有「服务端实例」这个概念，硬塞入口只会让人装进客户端 `mods/`。

### 启动页也变成左栏了

- 照 PCL 的 `PageLaunchLeft.xaml`：**左栏**放实例列表（吃掉剩余高度、自己滚）+ 离线用户名 + 内存 + **54 高的大号启动按钮**（它是这一页唯一的主要动作，不该和旁边的次要按钮一样大）；**右栏**放游戏目录、Java、直连、崩溃报告与日志。以前是一列往下滚的表单，实例一多，想点「启动」得先划过 Java 列表和直连输入框。
- 目录行留在右栏而不是左栏：一处目录决定左栏能选到哪些实例，它属于「范围」而不是「动作」。

### 版本隔离能关了

- `Instance` 新增 `isolated: Boolean = true`。**默认值就是旧行为**，旧 `instances.json` 不用迁移。
- 实例详情页多一张「版本隔离」卡：开着 = 这个实例自己一份存档/模组/配置；关掉 = 与同目录下的其他版本共用一份，`--gameDir` 指回游戏根目录。适用场景是「整合包把 `mods/` 平铺在游戏根目录」那种包 —— 隔离开着等于一个模组都看不见。
- 不是 `versions/<id>` 形状的目录不给这个开关（关不关都落在同一处），详情页会直说，不装作能切。
- 界面层的游戏目录推导统一走 `InstanceLayouts.of(...).gameDir`，模组页、光影页、详情统计跟着一起变。这一条**推翻了 `docs/22` 里「不做隔离开关」的旧论证**，原文保留并在旁边写清了为什么改主意。

### 取消安装不再留一个后台 java

- Forge / NeoForge 的官方安装器以前是「起进程 + 循环读日志」：点「取消」只是取消协程，那个 java 还在往 `libraries/` 里写；超时检查因为装在读循环里，对不出声的进程等于没有。
- 现在抽成 `core/install/InstallerProcess.kt`：协程一结束就 `destroyForcibly`（连派生的子进程一起杀），日志由独立协程读、主循环用 `waitFor(100ms)` 盯超时与取消，取消以 `CancellationException` 的形式冒出去而不是被包成「安装失败」报红。
- 下次再点安装，installer 自己会跳过 `libraries/` 里已存在且 sha1 相符的库 —— 断点续跑不需要额外状态。

### 关于页有开源致谢了（`a2ef0df`）

- 四组分类：使用了代码 / 设计参考（未使用代码） / 格式与协议 / 运行时依赖。「**使用了代码**」这一组目前是空的，而且单测盯着它 —— 数据在 `core/Credits.kt`，关于页与仓库根的 `NOTICE` 同源，不会出现「界面说没抄、NOTICE 里写着抄了」。
- 设计参考里明确写了 PCL2（含它那份 3 万余行的中文名数据库 `WikiEntries.txt` 未使用 —— 该项目的源码树没有 LICENSE 文件）、HMCL、Prism Launcher。

### 界面可用性（`507dfca`）

- 窗口默认 1280x820、最小 1024x640，窄窗口下侧栏自动 220→180dp、留白 24→16dp。以前 `Window` 没给尺寸，Compose 按内容开窗，一开就偏窄、右侧按钮被裁在框外。
- 实例页整页可滚（原来「固定头 + 权重列表」，窗口不高时列表只剩几十像素）。
- 启动 / 下载 / 设置 / 模组 / 光影五个页面的目录都能「选择…」了，不用手打路径。
- 下载页多一条状态条贴在顶部：正在哪一步、进度、速度、已用时间，或「卡在「步名」：原因」—— 详细分步列表还在下面，但 1280x800 的窗口一滚就出屏，用户会以为按钮没反应。

## 这一版修了什么

- **中文搜索不再直接回一句「没搜到」**。Modrinth 只认英文名与 slug，输入「钠」什么都搜不到 —— 现在界面明说「搜不到是因为 Modrinth 不认中文」，并给一组可点的英文候选 chip；输入框为空时给这一类热门词。词典 140 余条，每条 slug 都用 Modrinth 的 `/search` 实测过「搜出来非空」。
- **实例页多个实例选不中**：列表原来靠 `weight(1f)` 吃剩余高度，窗口一窄就看不全且不能滚。
- **`ServerInstallResult` 是 `ServerInstaller` 的嵌套类**，import 必须写全限定，写 `...core.install.ServerInstallResult` 会连带 `summary` / `dir` / `startScript` 全部解析不出来 —— 这条记在 `docs/37` 里，免得下次再踩。
- **整合包页不再显示一个点了没反应的 ↻**。

## 已知限制（别当 bug 报）

- **没有正版登录**。目前只有离线用户名，能改名但不验账号。微软登录延后做。
- **Android 端还不能跑游戏**。手机端只有界面与下载。
- **服务端只有原版**，且没有「服务端实例」的概念 —— 每个服务端是独立目录，不登记进实例列表，不参与启动链路。
- **关掉版本隔离不会自动搬文件**。原来在版本目录里的存档、模组留在原处，游戏看不到它们，得自己搬。
- **启动中的浮层没做**。PCL 的 `PanLaunching` 是盖在左栏上的进度浮层，这里把状态写在按钮下方与右栏。
- **不读光影包内部信息**（作者、版本号），只靠文件名与 Modrinth 返回的 `versionNumber`；`pack_format` 只显示不比对本机版本。
- **不做 NAT 穿透与公网中转**。联机页能列出本机地址、能拼对「地址:端口」，但网络本身得通。
- **崩溃卡片只认 crash-report**；归因仍是字符串匹配，没有混淆栈反解。
- **NeoForge 只测过 1.21.1 + 21.1.265**；20.2.x 之前没测。
- **跨页保活与界面改动没有单测**。`ui` 是 KMP 且只有 `jvmMain`，没有测试源集，正确性目前靠编译 + 人工点击。

## 校验

- 单测 **512 例全过**（45 个测试类，`:core:test`，failures=0 errors=0 skipped=0）。比 preview.6 的 473 例多 39 例，来自 `InstallerProcessTest`（4）、`ServerInstallerTest`（10）、`CreditsTest`（7）、`SearchKeywordsTest`（11）、`LocalAddressesTest` 之外的隔离三例与界面侧若干。
- 三端编译：`:ui:compileKotlinJvm` / `:app-desktop:compileKotlin` / `:androidApp:assembleDebug` 均 exit 0（52 actionable tasks）。
- 无头冒烟任务 12 个未变：`e2eInstall` / `settingsSmoke` / `instanceFlowSmoke` / `resumeSmoke` / `deleteSmoke` / `isolationSmoke` / `fabricE2e` / `forgeE2e` / `modsE2e` / `neoForgeE2e` / `modUpdateE2e` / `modpackE2e`。
- 真网实测（2026-11-14）：数据包搜索 `terralith` 31 条、`dungeons-and-taverns` 18 条、`veinminer` 15 条；插件 facet 实测 `essentialsx` 62 / `luckperms` 157 / `vault` 332 / 空查询 20396，返回项 `project_type` 全是 `mod`。

## 源码

<https://github.com/huang404yan/Platypus-Launcher>

Apache-2.0。与 Mojang / Microsoft 无关联，不含任何官方游戏文件。
