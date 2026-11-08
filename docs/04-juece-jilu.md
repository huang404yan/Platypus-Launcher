# 决策记录

按时间倒序。每条记录：决定、理由、被放弃的选项。

## 2026-11-08

### D9 更名：中文「鸭嘴兽启动器」，英文 Platypus Launcher

- 决定：取代 D8，产品定名中文「鸭嘴兽启动器」/ 英文 Platypus Launcher，口号「把好东西，缝在一起。」（关于页可用「自然界早就缝合好了。」）。
- 理由：原名「科学怪人 / Frankenstein」与「缝合各家优点」的立意同构，但不是可注册、可长期运营的品牌词；鸭嘴兽同为「缝合怪」——鸭嘴、海狸尾、产卵哺乳，正好对上产品立意，且取名温和、不易撞名。
- 影响：仓库目录 `platypus`、根包 `io.github.huang0yan4.platypus`、Android `io.github.huang0yan4.platypus.android`。
- 已执行：目录与包迁完、全局替换完成，待重新编译验证。

### D10 单一公开测试通道：鸭嘴兽·孵化版（Platypus Preview）

- 决定：只保留两条线——正式版「鸭嘴兽启动器」与唯一公开测试版「鸭嘴兽·孵化版」。不另起内测/公测/灰度之类渠道名，测试版只靠 `preview.N` 进位。
- 理由：渠道越多，版本矩阵与用户反馈越混乱；「孵化」既对应鸭嘴兽的卵生设定，也点明这是未定型的预览线。
- 标识：Android applicationId 在正式包名后加 `.preview`；桌面命令行 `platypus-launcher` / `platypus-launcher-preview`；Linux 桌面文件 `platypus.desktop` / `platypus-preview.desktop`；安装包命名如 `Platypus-Launcher-0.8.0-preview.1-win-x64.exe`。
- 版本号：正式版从 1.0.0 起按语义化进位；孵化版为 `0.x-preview.N`。
- 当前落点：仓库现处 0.8.0-preview.1（孵化线），正式版 1.0.0 待启动链路跑通后再立。

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
