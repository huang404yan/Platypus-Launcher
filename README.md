<p align="center">
  <img src="docs/logo.png" width="128" alt="鸭嘴兽启动器图标">
</p>

# 鸭嘴兽启动器（Platypus Launcher）

从零编写的 Minecraft 启动器。目标：三端（Android 优先、Windows、Linux）、开源、启动快、界面不卡。

> **免责声明**
>
> 鸭嘴兽启动器（Platypus Launcher）是一个由第三方开发者制作的非官方 Minecraft 启动器。本项目与 Mojang Studios 及 Microsoft 无任何关联，未获得其授权、认可或赞助。Minecraft 是 Mojang Studios 的注册商标。请支持购买正版游戏。
>
> Platypus Launcher is an unofficial third-party Minecraft launcher. This project is not affiliated with, endorsed by, or sponsored by Mojang Studios or Microsoft. Minecraft is a registered trademark of Mojang Studios. Please support the official game by purchasing it.

## 品牌

| | 正式版 | 孵化版 |
|---|---|---|
| 中文名 | 鸭嘴兽启动器 | 鸭嘴兽·孵化版 |
| 英文名 | Platypus Launcher | Platypus Preview |
| 版本号 | `1.0.0` 起进位 | `0.x-preview.N` |
| Android applicationId | `io.github.huang404yan.platypus.launcher` | `io.github.huang404yan.platypus.launcher.preview` |
| 命令行 | `platypus-launcher` | `platypus-launcher-preview` |

口号：**把好东西，缝在一起。** 孵化版的关于页另可写「自然界早就缝合好了。」

图标：正式版用成年全身版，孵化版用幼体半壳版。仓库里只留一张总图（`docs/logo.png`），各平台成品已接进源码（Android 自适应图标、桌面窗口图标、jpackage 安装包图标）。

> 不是 PCL / HMCL / Prism 的分支。这些项目只作为产品设计参考，代码一行不抄（见 `CONTRIBUTING.md`）。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 2.4 |
| UI | Compose Multiplatform 1.12 |
| 构建 | Gradle 9.9 + Kotlin DSL |
| JDK | 21（编译目标）/ 25（本机运行） |
| 协议 | Apache-2.0 |

## 模块

```
core/            纯 Kotlin，无 UI 依赖
  instance/      实例模型（实例 = 自包含目录）
  download/      下载器接口（多源/测速/并发/续传/校验）
  runtime/       Java 运行时探测与推荐
  auth/          离线 / 微软 / 外置登录
  launch/        启动请求、事件、GameRuntime 抽象
ui/              Compose Multiplatform，Android 与桌面共享
app-desktop/     桌面端入口
```

`GameRuntime` 是两端的分界线：桌面实现是「起一个 java 进程」，Android 实现是「应用内 JVM + 图形转译」。上层只认接口。

## 构建

```bash
./gradlew :app-desktop:run
```

Windows 下也可以用 `gradlew.bat`。首次构建会下载约 300 MB 依赖。

## 文档

- `docs/01-youdian-shouji.md`：各启动器优点收集
- `docs/02-jishuzhan-xuanxing.md`：技术栈选型与排除理由
- `docs/03-luxian-yu-youxianji.md`：优先级评估与里程碑
- `docs/04-juece-jilu.md`：决策记录
- `docs/05-win-baseline.md`：Windows 端技术基线
- `docs/06-win-acceptance.md`：Windows 端验收清单
- `docs/07-qidong-lianlu.md`：启动链路设计
- `docs/08-win-kaifa-jilu.md`：Windows 端开发记录
- `docs/09-android-baseline.md`：**Android 运行端技术基线**（JRE 只到 17、单版本 700–900 MB、渲染三选一）
- `docs/10-android-ceshi-buzhou.md`：**Android 预览版测试步骤**（给用户的手册）
- `docs/11-android-s1-shiyan.md`：**S1 真机实验清单**（用户可执行，含回报表格）
- `docs/12-win-oneclick-loop.md`：**一键装闭环**（选版本 → 下载 → 补 Java → 建实例 → 启动）
- `docs/13-settings-persistence.md`：**启动器设置持久化**（离线用户名 / 默认游戏目录 / 默认内存）
- `docs/14-resume-and-instance-create.md`：**下载断点续传与实例页新建入口**（`.part` + Range、本地版本直接认养、登记与移除登记）
- `docs/15-fabric-installer.md`：**Fabric 一键安装**（meta.fabricmc.net 元数据、maven 补 url、`1.20.1-fabric-0.20.1` 版本号、真实联网 e2e 数字）
- `docs/16-instance-delete.md`：**实例删除与版本目录清理**（改名 → 递归删 → 失败回滚，三种拒绝，存档保留）
- `docs/17-forge-installer.md`：**Forge 一键安装**（官方 installer CLI、`launcher_profiles.json` 前置、补齐 client jar 才不撞模块）
- `docs/18-mods-page.md`：**模组页**（本地 `mods/` 扫描、Modrinth 搜索安装、为什么不是 CurseForge）
- `docs/20-android-pages.md`：**Android 端模组 / 联机 / 设置三页**（ModInstaller 下沉 core、MulticastLock、为什么启动页仍是占位）
- `docs/19-neoforge-installer.md`：**NeoForge 一键安装**（官方 maven 被重置 → BMCLAPI 镜像、`--mirror` 对 client 安装无效的字节码级证据、`inheritsFrom` 是版本映射的唯一可信来源、预铺 70 个依赖库让 installer 完全不联网）
- `docs/21-gongneng-xifen.md`：**功能细分与补全路线**（对照 PCL 自编版逐页列出差距、「有了但不好用」八条实证、补全 P1–P7 / 细分 S1–S6 / 美化 U1–U4 三阶段）
- `docs/22-shili-geli-yu-xiangqing.md`：**实例真隔离与实例详情页**（`--gameDir` 指实例目录、libraries/assets 仍共享、旧共享 mods 复制不移动、`isolationSmoke` 的五条磁盘判据）
- `docs/23-mods-manage.md`：**模组管理与检查更新**（启用停用改名规则、sha1 反查 Modrinth、四类更新结论、加载器跟随实例）
- `docs/24-android-instance-detail.md`：**安卓实例详情页**（复用 `InstanceDetailsLoader` 做磁盘统计、桌面独有的探测 Java/打开目录换成复制路径、输入合并规则抽成 core 的 `InstanceEdits`）
- `docs/25-zhenghebao-daoru.md`：**整合包导入**（`.mrpack` / MCBBS v2 先在 core 只读解析再导入、overrides 三层覆盖顺序、`safeRelativePath` 两层防越界、先装游戏再下包内文件的原因）
- `docs/26-bengkui-baogao.md`：**崩溃报告读取与归因**（按 mtime 挑最新一份、退出码与时间两道闸、只读头部 256 KB、模组规则必须排在加载器规则之前）
- `docs/27-rizhi-bianma.md`：**日志中文不乱码**（给 JVM 传 `-Dstdout.encoding` + `-Dfile.encoding`、读侧按字节严格试 UTF-8 再退 GBK、为什么不能靠查替换符判编码、64 KB 单行上限）
- `docs/28-mods-yilai.md`：**模组依赖求解**（Modrinth 只给 project_id、sha1 反查已装、按层 BFS + 深度/数量封顶、失败不连坐）
- `docs/29-guangying-ziyuanbao.md`：**光影与资源包**（光影查 `loaders=["iris","optifine"]`、资源包查 `["minecraft"]`、元数据读不出只记 problem 不吞、文件夹形态拒绝停用的原因、安卓端为什么改成页内切换）
- `docs/30-mods-chongtu.md`：**模组冲突检测**（Modrinth 的 `dependency_type=incompatible`、为什么用 sha1 反查而不是读 modId、双向声明合成一条、`present` 为什么定义为「对面文件真在」、措辞只能写「作者声明」）
- `docs/31-zhenghebao-yuancheng.md`：**远程整合包**（只补「取包」这一段、`project_type=modpack` 的 facet、只留 `.mrpack` 但不硬排除 server 包、缓存落 `%TEMP%` 的理由、远程与本地共用同一条导入流水线）
- `docs/32-daoru-hedui.md`：**导入后核对磁盘**（为什么要「再看一眼」而不是信下载器、先比大小必要时才算 sha1、失败不抛而是收集成清单、取消要显示 `Canceled` 而不是停在 `Running`）

## 下载

编译好的包**不在仓库里**，走 GitHub Releases 的附件。仓库历史只放源码与构建必需的文件，二进制进去就删不掉，只会把仓库撑大。

本地出包后先按通道/平台放到 `releases/` 下（已 gitignore，只在本机）：

```
releases/stable/{android,windows,linux}/    Platypus Launcher 正式版
releases/preview/{android,windows,linux}/   Platypus Preview 孵化版
```

每个平台最多留 5 个，命名与留存规则见 `releases/README.md`。

> 三端孵化版包都已能出：Android 是 `.apk`，Windows 是免安装 `.zip` 与安装包 `.msi`。产物在 `releases/preview/` 下（不入库）。

## 状态

M0（骨架）+ M1（实例扫描）+ M2（下载器）+ M3（**Windows 一键装闭环**）+ M4（**整合包导入**）已完成，当前版本 **0.8.0-preview.5**（孵化线）。

**Windows 端已经能双击玩起来**：选版本 → 下载（客户端 jar / libraries / assets）→ 缺 Java 自动从 Mojang 官方运行时清单补 → 登记成实例 → 启动进主菜单，全程不需要人替启动器干活。实例内存可在启动页改（512–32768 MB）。

**Android 端补齐了三个页面**：侧边栏从「四个真页面 + 两个哑占位」变成「七个真页面 + 启动占位」。新增模组页（本地 `mods/` 列表 + Modrinth 搜索安装）、联机页（组播扫描 + 手动解析地址，多拿一个 `MulticastLock` 才收得到包）、设置页（与桌面读写同一个 `platypus/settings.json`）。为此把 `ModInstaller` 从 `ui` 的 jvmMain 下沉到 `core`，桌面与安卓共用同一份逻辑。启动页仍不存在——「应用内起 JVM + 图形转译」是独立的 `runtime-android` 模块（`docs/09`），占位页现在写清缺什么而不是一句「还没做」（`docs/20`）。

**Android 端也能管光影和资源包了**：侧边栏在「模组」与「联机」之间多一项「光影资源包」，页内顶部两个按钮切光影 / 资源包（手机侧边栏放不下二级）。逻辑与桌面端共用 core 的 `PackScanner` / `PackFileOps` / `PackInstaller`，一行没重写：从实例的 `shaderpacks/` 或 `resourcepacks/` 扫出已装的、勾选框启停、删除二次确认、从 Modrinth 搜了直接装。手机端目前还没有应用内启动游戏的能力（`runtime-android` 未开始），所以这一页的用处是「在手机上把包装好」（`docs/29`）。

**Android 端仍未在真机跑过**：四个新页面和原有的下载页、诊断页一样，验证止于「编译通过 + APK 的 dex 里有这些类」，没有一份真机截图或日志。

设置已经能存住：离线用户名、默认游戏目录、新实例默认内存，落在 `<游戏目录>/platypus/settings.json`，关掉再打开还在（`docs/13`）。

**实例页能自己建实例了**：填版本号 + 实例名 + 内存，本地已有那份游戏就直接认养（不联网），没有才走一键装；已登记的实例能改内存、能移除登记（只去掉登记，版本文件不动）。从实例页点「去启动」会跳到启动页并自动选中它（`docs/14`）。

**实例能删了，且删得小心**：卡片上的「删除文件…」先算计划（绝对路径、文件数、总字节数）再弹确认框；`saves/` 默认不删，搬到 `<游戏目录>/platypus/saves-backup/`，要连存档一起删得自己勾。实现是「改名 `<dir>.deleting-<时间戳>` → `walkBottomUp` 递归删 → 失败改名回来」，没引第三方回收站库。三种情况直接拒绝：版本目录不存在、目标在 `versions/` 之外、同一目录还被别的实例登记着（`docs/16`）。

**下载断点续传**：先写 `<文件>.part`，校验通过才转正；`.part` 有内容就发 `Range` 接着下，断网不清半成品，下次自动接上。进度条会额外显示「其中 N 个接着上次下（省了 M MB）」（`docs/14`）。

单测 **448 例全过**（40 个测试类，`./gradlew :core:test`）。另有十二个无头冒烟任务（`e2eInstall` / `settingsSmoke` / `instanceFlowSmoke` / `resumeSmoke` / `deleteSmoke` / `fabricE2e` / `modpackE2e` / `forgeE2e` / `neoForgeE2e` / `modsE2e` / `modUpdateE2e` / `isolationSmoke`），走的是界面按钮同一批函数；`resumeSmoke` / `fabricE2e` / `modpackE2e` / `forgeE2e` / `neoForgeE2e` / `modsE2e` 需要联网，分别在空目录真装 Fabric、真跑 Forge 与 NeoForge 官方安装器、真从 Modrinth 下模组、真把一个 `.mrpack` 从解析装到进主菜单；`deleteSmoke` 在 `dist/_delete-smoke` 里全程造假数据，不碰真实 `.minecraft`。

**能装 Fabric 了**：下载页勾「顺带装 Fabric」→ 拉 meta.fabricmc.net 的 loader 列表（默认落在最新 stable）→ 装 profile json（只改 `id`，保留 `inheritsFrom`）→ 按 maven 坐标补全 8 个库的地址并下载 → 登记成 `1.20.1-fabric-0.20.1` 这样的实例。实测空目录全流程 4 分 29 秒进主菜单，日志首行 `Loading Minecraft 1.20.1 with Fabric Loader 0.20.1`（`docs/15`）。

**能装 Forge 了**：下载页勾「顺带装 Forge」→ 拉 `promotions_slim.json`（默认 recommended）→ 下载官方 installer（8.8 MB，装过一次就复用）→ 备好 `launcher_profiles.json` → 跑 `--installClient` 逐行透传日志 → 校验 version json → **把原版 client jar 复制成 `<forgeId>.jar`**（不 copy 会撞模块，报 `ResolutionException: Module minecraft contains package net.minecraft.server`）→ 登记成 `1.20.1-forge-47.4.28`。实测空目录全流程 7 分 55 秒（含下载 3641 个原版文件与自动补 Java 17）、已装目录 7 秒，进主菜单 11.0 秒（`docs/17`）。

**实例终于真隔离了**：以前所有实例共用一份 `mods/` 与 `saves/`（`--gameDir` 被反推成游戏根目录），装两个整合包会互相打架。现在 `--gameDir` 指向 `<游戏目录>/versions/<id>`，`libraries/` 与 `assets/` 仍共享；`isolationSmoke` 用五条磁盘判据反向证明（实例目录里的 `logs/latest.log` 有内容 9291 B、共享 `latest.log` 的 mtime 与共享 `saves/` 件数一字未变），实测进主菜单 9.6 秒。模组页跟着改成「先选实例」，隔离前装在共享 `mods/` 里的模组可以一键**复制**进某个实例（不移动、不覆盖同名）。实例卡片新增「详情」：本机 Java 列表里挑运行时、改内存与 JVM 参数、看版本 json 摘要（mainClass / 继承自 / 库条目数 / 需要的 Java）、看八个子目录各占多少、一键打开目录（`docs/22`）。

**有模组页了**：左侧列出 `mods/` 里已装的（读 `fabric.mod.json` / `mods.toml` / `neoforge.mods.toml` 拿 id、名字、版本、加载器，坏 jar 标出来但不崩），右侧从 Modrinth 搜、选一个装进去，下载沿用 `.part` 续传 + sha1 强校验。实测装 Sodium 971 552 字节、33 秒（`docs/18`）。

**模组前置会自动装齐**：Modrinth 的依赖表只给 `project_id`，装完一个模组后启动器再查两次（id → slug、slug → 版本），把前置一层层展开成依赖树，界面按层缩进列出、点一次「一次装齐」全下完。同一个库被多个模组依赖只排一次，`mods/` 里已装的先用 sha1 反查跳过，查不到/没适配/已装过的逐条说清原因。实测装 Iris 1.7.6 时自动列出 `Sodium 0.5.13` 并一并装进（2 726 899 B + 971 552 B）。深度 8 层、单次 64 个封顶，成环会停下（`docs/28`）。

**作者声明的不兼容会提前提示**：模组页的「查冲突」把 `mods/` 里每个 jar 的 sha1 拿去 Modrinth 反查，读出作者在版本里写的 `dependency_type = "incompatible"`，把「两个都装着」和「只被点名、没装」分成两段列出来。本机一个真实 Forge 实例的 34 个 jar 里查出 3 条声明（Embeddium 对 Rubidium 与 TexTrue's Embeddium Options、Superb Warfare 对 Iris/Oculus & GeckoLib Compat），三条都是「作者提到但没装」；另造一对互斥的测试实例验了 `present` 分支真的会标成「都装在这个实例里」。措辞统一按「作者声明」写，卡片里明说「不一定崩」（`docs/30`）。

**能装 NeoForge 了**：官方 `maven.neoforged.net` 在本机被重置（`Connection was reset`），所以元数据与库全走 BMCLAPI 镜像；installer 的 `--mirror` 对 client 安装**无效**（`ClientInstall` 根本不引用 `getMirror()`，字节码级确认），改由启动器在跑 installer 之前把 70 个依赖库**预铺**到 `libraries/`，installer 看到文件在、sha1 对上就打 `Checksum valid` 直接跳过——实测 13822 行安装器日志里 `Checksum valid` 117 次、`Downloading library from` 0 次、超时 0 次。NeoForge 版本号到 MC 版本的映射只信 installer jar 内 `version.json` 的 `inheritsFrom`（前缀只是初筛）。实测 1.21.1 + NeoForge 21.1.265：装 54.8 秒、进主菜单 14.9 秒，日志里 `NeoForge mod loading, version 21.1.265, for MC 1.21.1`（`docs/19`）。

**整合包能直接在启动器里搜了**：以前得先在浏览器里翻到 Files 标签、认出哪个是能导入的 `.mrpack`、下载到本地，再回启动器点「选择文件…」。现在下载页的导入卡片多了「从 Modrinth 搜」：关键词 + 游戏版本 + 加载器（按钮循环切换），空关键词也放行（直接看热门）。选中后启动器把 `.mrpack` 下到 `%TEMP%\platypus-modpacks\packs`，再走原来那条本地导入流水线——两条路只有「取包」不同，进度、错误、重试都共用一份。实测搜 `cobblemon` 装 Cobblemon Official Modpack [Fabric]：包 100,855,720 B，预览写「77 个文件要下载，共 209.1 MB」，13 分钟装完（含 209 MB 游戏文件与按需下载的 76 MB Java 运行时），登记实例 `1.21.1-fabric-0.20.1`，`mods/` 下 60 个 jar（`docs/31`）。

**导入完会自己复核一遍磁盘**：以前「导入完成」只看流程有没有抛异常——切个页面就把导入协程砍了，`.part` 留在磁盘上，界面却停在「已完成 X / Y 个文件」看着像装完了（实测那次 77 个文件只落 76 个，缺的正是 139,966,403 字节的 Cobblemon 本体）。现在下完所有文件后按清单再核一遍：先比大小（`.mrpack` 基本都带 `fileSize`，77 个文件 650 MB 只要几毫秒），清单没写大小时才读盘算 sha1；对不上就收集成清单，标题从「导入完成」改成「导入没装齐」并逐个列出原因（半成品会写明「只下到一半（49.2 MB / 133.5 MB）」）。取消也不再静默：界面明确写「导入已中断（停在「下载整合包文件」）」，重试自动续上没下完的那几个（`docs/32`）。

**崩了会告诉你为什么**：游戏非正常退出时读实例 `crash-reports/` 里最新一份报告，卡片上给文件名、时间、大小、`Description:` 那句和第一个异常，可展开全文 / 复制全文 / 打开目录。两道闸防误报：退出码 0 不读、报告 mtime 必须不早于本次启动（留 5 秒余量），否则实例上次崩过会把旧报告挂成新的。归因规则新增「模组冲突或缺依赖」与「模组文件损坏」两条，且必须排在「加载器未能启动」之前——缺前置的日志后面必跟 `NoClassDefFoundError: cpw/mods` 连带噪声。日志区同时补了复制 / 导出 / 打开崩溃目录（`docs/26`）。

**日志里的中文不再是方块**：以前游戏按 GBK 往 stdout 写、启动器按 UTF-8 解，中文全变成 一串替换符，字节当场丢失。现在两头都改：给 JVM 传 `-Dstdout.encoding=UTF-8` 与 `-Dfile.encoding=UTF-8`（JDK 18+ 只认前者、17 及更早只认后者），读侧再补一个「先严格试 UTF-8、不合法就整行退回 GBK」的 `LogLineReader`。实测 1.20.1 + Forge 47 模组环境，导出日志 35 137 B / 244 行 / `U+FFFD` 计数 0，原先那条全是方块的声音设备名已经完整显示为「扬声器 (适用于 I2S 音频的英特尔® 智音技术)」（`docs/27`）。

**26.x 的新版本号也认了**：Mojang 从 26.1 起把版本号从 `1.21.11` 换成 `26.1`（没有 `1.` 前缀），旧的正则 `^(1)\.(\d+)…` 匹配不到，会一路落到 else 推出「要 Java 8」。现在加了年份式分支：整串形如「两位数字 + 点 + 数字」开头且年份 ≥ 26 → Java 25，`1.20.1` 这类一位数的不会被误判成年份。

顺带把周号快照也从「一律 21」改成按年份分段：`13w16a–21w18a` → 8、`21w19a–22w02a` → 16、`22w03a–24w13a` → 17、`24w14a–25w46a` → 21、`26w14a` 起 → 25。边界不是猜的，是把 version_manifest_v2 里 744 个快照的 `javaVersion.majorVersion` 全拉下来统计出来的跃迁点；按这套规则复算 744 个只有 1 个不一致（`1.18-pre1` 官方 16、我们按 `1.x` 分支算出 17）。这个函数只是兜底，一键装那条路优先用版本 json 自己的 `javaVersion`。依据是官方 version json：26.1 / 26.2 / 26.3 / 26.1.1 / 26.1.2 与 26.4-snapshot-1…8 全是 `javaVersion = {component: java-runtime-epsilon, majorVersion: 25}`，而 1.21.11 与 24w14a 仍是 `java-runtime-delta / 21`。

**光影和资源包也能装了**：左侧导航多一项「光影资源包」，下面分「光影」「资源包」两个二级页。已装的从 `shaderpacks/` 与 `resourcepacks/` 扫出来，勾选框就是启停（改文件名 `.zip` ↔ `.zip.disabled`），删除要二次确认；文件夹形态的包明确拒绝停用——对目录改名游戏照样认，假装停用只会让人困惑。搜装走 Modrinth：光影用 `loaders=["iris","optifine"]`、资源包用 `["minecraft"]`，都不带 categories facet。实测搜 `complementary` 装进 `shaderpacks/`，落盘 `ComplementaryReimagined_r5.9.3.zip` 553 397 B，已装列表同时列出旧的文件夹形态包并标「· 文件夹」。

**联机页**：扫描局域网广播（`224.0.2.60:4445`，读 `[MOTD]`/`[AD]`）列出同网段的世界，也能手动解析 `host:port` 并复制地址。**不在启动器里「点一下就进」**——绕过游戏会丢皮肤与会话，界面里写清了原因。

尚未实现：正版/微软登录（`core/auth` 只有接口）、跨网络联机（NAT 穿透/中转，本版没做）、`optional` 依赖不自动装（只装 `required`；`incompatible` 已能反查并提示）。
