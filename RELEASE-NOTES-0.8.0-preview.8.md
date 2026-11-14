# 鸭嘴兽·孵化版 0.8.0-preview.8

> 这是**孵化版（Pre-release）**，不是正式版。功能不完整，界面与数据格式随时会变。
> 正式版号段从 1.0.0 起，与 0.x-preview.N 互不占用。

## 这一版比 preview.7 多了什么

### 启动时有一层浮层了

- 照 PCL 的 `PanLaunching`：点了「启动」之后，左栏上盖一层半透明浮层，里面是「正在启动游戏 / 实例名 / 进度条 / 当前步骤 / 启动进度百分比」，底下还有一个「取消启动」。以前启动进度只在按钮下方留一行字，窗口一滚就看不见，用户会以为按钮没反应。
- **进度只准前进不后退**：解压原生库那一步会把进度从 0.3 退回 0.15，浮层里要是跟着退，看起来像卡住了重来。
- **取消不砍协程**：`DesktopGameRuntime.launch` 在 `withContext` 里同步起进程，中途取消会让已经起来的 java 进程再也拿不到句柄，变成一个杀不掉的后台进程。所以「取消启动」只是记下意愿，等 launch 返回句柄的那一刻立刻 `stop()`。

### 服务端五种加载器都支持了

以前只能下 Mojang 的 `server.jar`，页面上写着「Forge / NeoForge / Fabric / Paper 这一版不做」。现在五种都在：

| 加载器 | 从哪拿 | 备注 |
|---|---|---|
| 原版 | Mojang `server.jar` | 与以前一样 |
| Paper | `fill.papermc.io/v3` | 老 `api.papermc.io/v2` 已 **410 Gone**，`/v3` 也只剩 403；只有 sha256，没有 sha1，下完自己算一遍比对 |
| Fabric | `meta.fabricmc.net` 的 server jar | 地址**必须带 installer 版本**（`/v2/versions/loader/<gv>/<lv>/<iv>/server/jar`），少一段就是 404 |
| Forge | 官方 installer | 子进程跑 `--installServer`，要几分钟；生成的 `run.bat` 不覆盖 |
| NeoForge | 官方 installer | 同上 |

- **Forge / NeoForge 要用户自己挑一个 Java**。安装器是子进程跑 `java -jar installer.jar`，版本不对会 `UnsupportedClassVersionError`，报错埋在日志里，界面上只看到「失败」。所以加了一行 Java 选择器，默认挑主版本 >= 17 里最新的那个。
- **内存写在哪儿说清楚了**：原版 / Paper / Fabric 改 `start.bat` 就行；Forge / NeoForge 要改 `user_jvm_args.txt`，因为安装器生成的 `run.bat` 读的是它 —— 改 `run.bat` 本身不会生效。
- `ServerInstaller` 的 `ServerInstallResult.jar` 改成可空：Forge / NeoForge 没有单一 jar，只有一个 `run.bat` 加一堆 `libraries/`。

### 装过的服务端会被记住

- 新增 `core/install/ServerRepository.kt` 与 `<游戏目录>/platypus/servers.json`。装完自动登记一条（加载器、版本、目录、内存、创建时间），服务端页底部列出「装过的服务端」，每条能「打开目录」或「从列表里去掉」。
- **同一个目录只留一条**：重装同一个服务端不该长出第二条记录。比较目录时忽略大小写和斜杠方向。
- **刻意不复用 `Instance`**：服务端没有 `versions/` 布局，不进启动页，也不需要 Java 探测那套；硬塞进去只会让实例列表混进一堆点「启动」会报错的东西。
- **也刻意没有「启动服务端」按钮**：开服是长驻进程，要接控制台输入输出，这一版只到「把目录和脚本准备好」。

### 安卓端跟上了桌面端这一轮

- **数据包**：光影 / 资源包两个按钮变成三个，落 `datapacks/`。示例关键词给的是 `terralith`、`veinminer`、`tectonic`。
- **服务端**：侧栏新增「服务端」一项。手机上只有原版 / Paper / Fabric 三种 —— Forge 与 NeoForge 要起 java 子进程跑安装器，手机做不到，页面上直接说了。
- **没有「打开目录」**：`file://` 的 Intent 从 Android 7.0 起抛 `FileUriExposedException`，要配 `FileProvider`，这一版改成「复制路径」。
- **关于页有开源致谢了**，与桌面端同一份数据（`core/Credits.kt`）。

### ui 模块终于有单测了

- `ui` 是 KMP 且一直只有 `jvmMain`，没有测试源集 —— 保活、导航、格式化函数全靠人工点。现在建了 `jvmTest`，56 例里有 15 例在这儿：`FormatHelpersTest`（8 例，字节数换档、速度计采样间隔、时长写法）与 `NavigationTest`（7 例，二级项顺序、id 不重复、默认项、整合包页故意不带刷新）。

## 这一版修了什么

- **取消 Fabric 安装不再报红**。`FabricInstaller` 的 `catch (e: Exception)` 会把 `CancellationException` 包成 `FabricInstallException`，用户点「取消」看到的是「安装失败」。现在取消前置拦截、原样抛出去。
- **取消整合包导入不再报「下载中断」**。`ModpackImporter` 在 `DOWNLOADING_FILES` 段同样问题，同样修法。
- **Forge / NeoForge 安装器的取消与超时真正生效**。以前「起进程 + 循环读日志」，取消只是取消协程、java 还在写 `libraries/`；超时检查装在读循环里，对不出声的进程等于没有。现在抽成 `core/install/InstallerProcess.kt`：协程结束就 `destroyForcibly`（连派生子进程一起杀），日志由独立协程读，主循环 `waitFor(100ms)` 盯超时与取消。
- **安卓 PacksPage 的类型文案不再写死两支**。原来 `if (kind == RESOURCE_PACK) "资源包" else "光影包"`，加了数据包之后会显示成「光影包」；现在换成 `packPageTitle(kind)` 三分支。

## 已知限制（别当 bug 报）

- **没有正版登录**。目前只有离线用户名。
- **Android 端还不能跑游戏**。手机端只有界面、下载与装包，进游戏要的 runtime-android 模块还不存在。
- **服务端不能从界面启动，也看不到控制台输出**。只能打开目录、自己双击脚本。
- **关掉版本隔离不会自动搬文件**。原来在版本目录里的存档、模组留在原处。
- **启动浮层没有「你知道吗」提示块**。PCL 那份有一个显示小知识的区块，这里没有内容源，先不放。
- **不读光影包内部信息**，只靠文件名与 Modrinth 返回的 `versionNumber`。
- **不做 NAT 穿透与公网中转**。
- **崩溃卡片只认 crash-report**；归因仍是字符串匹配。
- **NeoForge 只测过 1.21.1 + 21.1.265**。

## 校验

- 单测 **551 例全过**（49 个测试类：`:core:test` 46 类 525 例 + `:ui:jvmTest` 2 类 15 例；failures=0 errors=0 skipped=0）。比 preview.7 的 512 例多 39 例。
- 三端编译：`:ui:compileKotlinJvm` / `:app-desktop:compileKotlin` / `:androidApp:assembleDebug` 均 exit 0（57 actionable tasks）。
- 无头冒烟任务 12 个未变：`e2eInstall` / `settingsSmoke` / `instanceFlowSmoke` / `resumeSmoke` / `deleteSmoke` / `isolationSmoke` / `fabricE2e` / `forgeE2e` / `modsE2e` / `neoForgeE2e` / `modUpdateE2e` / `modpackE2e`。
- 真网实测（2026-11-14）：`fill.papermc.io/v3/projects/paper/versions/1.20.1/builds` 返回 178 个 STABLE 构建，首个 id=196 / sha256 `234a9b32…5eaea` / 43,230,582 B；`api.papermc.io/v2/...` 全部 **410**，`api.papermc.io/v3/...` **403**；Fabric 少一段 installer 版本的 server jar 地址返回 **404**。

## 源码

<https://github.com/huang404yan/Platypus-Launcher>

Apache-2.0。与 Mojang / Microsoft 无关联，不含任何官方游戏文件。
