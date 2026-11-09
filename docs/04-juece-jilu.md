# 决策记录

按时间倒序。每条记录：决定、理由、被放弃的选项。

## 2026-11-09

### D13 开发优先级 Win > Android，发布两端同时

- 决定：**Windows 端为唯一活动开发线**，Android 端暂停；待 Win 一键装闭环稳定后再回到 Android。对外发版时两端同一版本号**同时出包**，不允许只发一端。
- 理由：用户原话「多线容易抢工具导致进度缓慢，优先级 win>a，优先 win 端开发后续开发 a 端；发布同时发」。两条线并行会争抢 gradle daemon、文件锁与网络带宽，实测同一时段内两条线的构建互相阻塞。
- 放弃：双线并行（已被实测证明拖慢）；先发 Win 再补 Android（会造成"两端不同步"的用户观感，与 D10 双号段规则冲突）。

### D12 以 PCL 自编版的功能集为「小目标」，但按「少了它还能不能玩」分档

- 决定：把上一项目（PCL-拾光启动器自编版）的功能表当作**功能参照系**，不当界面或实现参照系；并按一句硬标准分两档——**去掉这一步，用户还能不能双击把游戏玩起来**。不能的进本轮，能的排以后。
- 本轮（能玩起来的充分链条）：选版本 → 下载（带进度）→ 缺 Java 自动补齐 → 登记成实例（内存可改）→ 一键启动进主菜单。
- 以后（本轮明确不做）：正版 / 离线 / Authlib 三方登录、陶瓦联机、服务器安装与启动、控制台、RCON、server.properties 表单、OP 管理、Forge/NeoForge 内存改写、主题配色、自动更新、帮助文档。
- 理由：用户原话「参考上次 pcl 自编版为小目标开发，只向功能」——要的是能力对齐，不是复刻。若把登录、联机、服务器一起铺开，闭环会拖成半成品，反而验证不了「双击就能玩」这件事。
- 对 Android 线额外约束：PCL 功能表的前提是 S1 spike 成立。若 S1 证明应用内跑 Java 版在该机型不成立，这份表对 Android 只是**上限参考**，报告须写明哪几步做不到、卡在哪、需要换机型还是换渲染方案，不接受「理论上可以」。
- 放弃：一次性对齐 PCL 全部功能（范围失控，且当前连实例化都没有）；只做界面不做闭环（与上一轮「能编译 ≠ 能用」的教训冲突）。

## 2026-11-08

### D9 更名：中文「鸭嘴兽启动器」，英文 Platypus Launcher

- 决定：取代 D8，产品定名中文「鸭嘴兽启动器」/ 英文 Platypus Launcher，口号「把好东西，缝在一起。」（关于页可用「自然界早就缝合好了。」）。
- 理由：原名「科学怪人 / Frankenstein」与「缝合各家优点」的立意同构，但不是可注册、可长期运营的品牌词；鸭嘴兽同为「缝合怪」——鸭嘴、海狸尾、产卵哺乳，正好对上产品立意，且取名温和、不易撞名。
- 影响：仓库目录 `platypus`、根包 `io.github.huang404yan.platypus`、Android 正式版 `io.github.huang404yan.platypus.launcher`、孵化版 `.launcher.preview`。
- 已执行：目录与包迁完、全局替换完成，待重新编译验证。

### D10 单一公开测试通道：鸭嘴兽·孵化版（Platypus Preview）

- 决定：只保留两条线——正式版「鸭嘴兽启动器」与唯一公开测试版「鸭嘴兽·孵化版」。不另起内测/公测/灰度之类渠道名，测试版只靠 `preview.N` 进位。
- 理由：渠道越多，版本矩阵与用户反馈越混乱；「孵化」既对应鸭嘴兽的卵生设定，也点明这是未定型的预览线。
- 标识：Android applicationId 两条线各用独立包名（正式版 `.launcher`、孵化版 `.launcher.preview`）——理由见下方 D11；桌面命令行 `platypus-launcher` / `platypus-launcher-preview`；Linux 桌面文件 `platypus.desktop` / `platypus-preview.desktop`；安装包命名如 `Platypus-Launcher-0.8.0-preview.1-win-x64.exe`。
- 版本号：正式版从 1.0.0 起按语义化进位；孵化版为 `0.x-preview.N`。
- 当前落点：仓库现处 0.8.0-preview.1（孵化线），正式版 1.0.0 待启动链路跑通后再立。

### D11 Android 两条线各用独立包名（2026-11-08）

- 决定：正式版 `io.github.huang404yan.platypus.launcher`，孵化版 `io.github.huang404yan.platypus.launcher.preview`（品牌表最初写法，最终确认采用）。
- 理由：孵化版是「可以随便炸」的实验线，正式版是「装了就指望它稳定」的发布线。两个包名意味着同一台设备可同时装、互不影响，测试出问题不会污染正式版数据目录；也符合「孵化版不占正式版版本号」的双号段规则。
- 代价：孵化版的改动迁到正式线时要换包名重出一次（不能覆盖安装），且孵化版的用户数据不会自动带过去。在尚未有真实用户的阶段，这不构成负担。
- 已落地：`androidApp/build.gradle.kts` 里 namespace 与 applicationId 都是 `.launcher.preview`，源码在 `androidApp/src/main/kotlin/io/github/huang404yan/platypus/launcher/preview/MainActivity.kt`；构建脚本加了 `check()`，版本号不含 `-preview` 却仍用孵化包名时直接失败，防止发错线。
- 放弃：两条线共用一个包名——两版不能并存，且正式版与孵化版共用同一份用户数据，实验性改动会动到正式环境。

### D1 从零写，不 fork 任何现有启动器

- 决定：路线 A，自建仓库，不基于 HMCL / Prism / XMCL 二开。
- 理由：PCL 的版权问题只是导火索，根本原因是「集各家优点」没法靠改别人的架构实现；GPL 系 fork 会强制整体开源并继承对方的架构债。
- 放弃：fork HMCL（快但架构绑定）、fork XMCL（MIT 最自由，但 Electron 体积与「不臃肿」冲突）。

### D2 Kotlin + Compose Multiplatform

- 决定：核心纯 Kotlin，UI 用 Compose MP，Android 与桌面共享。
- 理由：Android 本身是 JVM；手机要跑 Java 版 MC 就必须有 JVM，Kotlin 与之同构，避免两套技术栈；MC 周边库在 JVM 生态最全。
- 放弃：C# + Avalonia（桌面最优但 Android 等于重开）、Rust + Tauri（体积最优但开发慢 2–3 倍）、Flutter（与 JVM 交互成本高）。
- 代价：桌面端冷启动与原生观感弱于 WPF，需要靠懒加载与 CDS 补。

### D3 许可证 Apache-2.0

- 决定：Apache-2.0，允许商业化，允许他人闭源二次使用。
- 理由：用户明确「纯开源，最多更新慢」。
- 放弃：GPL-3.0（能防别人闭源拿走，但也锁死自己）。

### D4 三端，Android 优先

- 决定：Android 与 Windows 同时推进，Linux 作为第三端准备。
- 设备基线：Windows 本机；Android 骁龙 870 / 8 GB / 256 GB；Linux 老机器（不保证可运行）。
- 影响：所有性能预算以中端 Android（870）为下限。

### D5 GameRuntime 抽象

- 决定：把「运行 Minecraft」抽成接口，桌面与 Android 各自实现。
- 理由：两端运行方式差异极大（子进程 vs 应用内 JVM + 图形转译），但上层逻辑（实例、下载、账号、启动参数）可以共享。
- 影响：core 不得直接依赖 `Process` 或 Android API。

### D6 性能预算写进验收

| 场景 | 目标 |
|---|---|
| 桌面冷启动到首屏 | ≤ 1.5 s |
| 1000 项列表滚动 | 60 fps，无可见卡顿 |
| 下载并发 | ≥ 16 |
| Android 首屏（870） | ≤ 2.5 s |
| 桌面空闲内存 | ≤ 250 MB |
| Android 管理端内存 | ≤ 150 MB |

### D7 Android 运行端先做可行性验证

- 决定：S1 spike 先于 UI 开发，产出《Android 运行端技术基线报告》。
- 理由：它决定「手机优先」是产品主线还是宣传语。
- 待回答：能否进世界、帧率/内存分档、加载器兼容性、30 分钟温度与掉电。
