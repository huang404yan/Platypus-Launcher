# 鸭嘴兽·孵化版 0.8.0-preview.6

> 这是**孵化版（Pre-release）**，不是正式版。功能不完整，界面与数据格式随时会变。
> 正式版号段从 1.0.0 起，与 0.x-preview.N 互不占用。

## 这一版比 preview.5 多了什么

- **切页不再打断长任务**（`f352090`）。下载页的一键安装、整合包导入原来都跑在页面级协程里——`when (page)` 直接调用各页 Composable，页面一离开组合树协程就被取消，下载到 80% 手滑点一下实例页就白下了。现在状态搬进进程级的 `OneClickRunner` / `ModpackRunner`（`ui/.../InstallRunners.kt`，两个 `MutableStateFlow` + `CoroutineScope(SupervisorJob() + Dispatchers.Default)`），切页回来进度条接着走、步骤勾选状态还在，只有点「取消」或关掉应用才停。
- **安卓端同样补齐**（`237724f`）。下载页早就没问题，但模组页与光影/资源包页的安装还在 `rememberCoroutineScope()` 里，切 tab 一样被砍。新建 `InstallJobs.kt`（`ModInstallRunner` / `PackInstallRunner`），带一个 `doneAt` 时间戳给页面做重扫触发——runner 不知道界面在不在，装完只能靠时间戳变化通知。
- **联机页能「点一下就进」**（`f352090`）。每个扫到的世界和手填地址旁边多一个「直接进」按钮，用官方的 `--quickPlayMultiplayer` 参数启动游戏，跳过主菜单直接落到那个世界。关键是**只填占位符不够**：这条参数被 `rules.features` 挡着，`is_quick_play_multiplayer` 不开整段不会进命令行，所以特性开关与占位符必须同时给。1.19 及更早的版本文件没有这条规则，界面上提前标红说明换实例，而不是点下去停在主菜单装作没事。
- **联机页多一段「让朋友连到你」**（`cbd8dea`）。填游戏里「对局域网开放」时聊天栏给出的端口，下面按地址种类列出本机的「地址:端口」——局域网、公网 IPv6、其它各一条，带复制按钮和一句「这条能用在什么场合」。新增 `core/lan/LocalAddresses.kt`：过滤回环、链路本地（169.254./fe80::）、没启用的网卡，去掉 IPv6 的 zone id，同一地址只留一条，排序固定 LAN → IPV6 → OTHER。跨网络真正能连的两条路（双方都有公网 IPv6、路由器端口转发）界面写清楚了；**UDP 打洞与公网中转服务器仍然不做**，那需要一台常驻服务器。
- **模组的 `optional` 依赖会被列出来但不替你装**（`7aaef72`）。Modrinth 的 `dependency_type` 有 required / optional / incompatible / embedded 四类，本来只认 required。现在 optional 解析出来单独进依赖树卡片的一段「作者建议一起装」，写明是谁提的；不装照样能启动，也不会混进「前置都装齐了」的判断里。

## 这一版修了什么

- **依赖树卡片在「只有可选依赖」时标题写「前置依赖 · 0 个」**。装一个没有必需前置、只有可选建议的模组时，卡片标题与按钮会自相矛盾。现在标题与「一次装齐」按钮都只在真有必需前置时出现，只剩「先不管」。
- **装完一个只有 optional 的模组，可选提示会丢**。`install()` 原来只在 `requiredDependencies` 非空时展开依赖树，可选依赖就永远看不到。新增 `ModDependencyResolver.suggestOptional()` 走单独一条路，`ModInstallResult` 也透出 `optionalDependencies`。
- **联机页内容超过一屏会把底部说明裁掉**。加上「让朋友连到你」之后页面变长，桌面端整页从固定高度改成 `verticalScroll`。
- **Forge 幂等复跑补测**：本机已装好的版本走的是「跳过安装、只补 client jar」那条分支，实测 7 秒收工（空目录全装要 7 分 55 秒），这一条原来只有代码没有实测证据。

## 已知限制（别当 bug 报）

- **没有正版登录**。目前只有离线用户名，能改名但不验账号。微软登录延后做。
- **Android 端还不能跑游戏**。手机端只有界面与下载，应用内起 JVM + 图形转译仍是纸面推算，没有真机验证。
- **不做 NAT 穿透与公网中转**。联机页能列出本机地址、能拼对「地址:端口」，但网络本身得通。
- **崩溃卡片只认 crash-report**。Java 根本没起来、natives 解压失败这类不会写报告的失败，仍只走关键词归因。
- **归因仍是字符串匹配**，没有混淆栈反解。
- **依赖求解只查 Modrinth**。CurseForge 上的前置查不到；只按 project_id 递归，模组自己声明的「版本区间」只做粗匹配。
- **NeoForge 只测过 1.21.1 + 21.1.265**；20.2.x 之前没测。
- **跨页保活没有单测**。`ui` 是 KMP 且只有 `jvmMain`，没有测试源集，runner 的正确性目前靠编译 + 人工点击。

## 校验

- 单测 **473 例全过**（41 个测试类，`:core:test`，failures=0 errors=0 skipped=0）。比 preview.5 的 410 例多 63 例，来自 `LocalAddressesTest`（10）、`ModDependencyResolverTest` 可选依赖分支（8）、`ModrinthMetaTest` 可选依赖解析（3）、`LaunchArgumentsTest` quickPlay（4）与依赖树相关若干。
- 无头冒烟任务 12 个：`e2eInstall` / `settingsSmoke` / `instanceFlowSmoke` / `resumeSmoke` / `deleteSmoke` / `isolationSmoke` / `fabricE2e` / `forgeE2e` / `modsE2e` / `neoForgeE2e` / `modUpdateE2e` / `modpackE2e`。
- 真网实测（2026-11-14）：`modsE2e` 搜 `reeses-sodium-options` 装完解析出 2 个可选依赖（Sodium Extra `mc1.20.1-0.5.9`、Iris `1.7.6+1.20.1`），日志 `OPTIONAL resolved=2`；`forgeE2e` 在 `dist/_forge-e2e` 空目录全装 1.20.1 + Forge 47.4.28 后进主菜单。
- 三端编译：`:ui:compileKotlinJvm` / `:app-desktop:compileKotlin` / `:androidApp:assembleDebug` 均 exit 0。

## 源码

<https://github.com/huang404yan/Platypus-Launcher>

Apache-2.0。与 Mojang / Microsoft 无关联，不含任何官方游戏文件。
