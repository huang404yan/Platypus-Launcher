# 鸭嘴兽启动器 · 第二步前置：技术栈选型

> 约束（用户 2026-11-08 定）：路线 A 从零写；三端、**手机优先**，其次 Windows；开源；语言要求「兼容好、快、不臃肿」；不急，可以慢慢做。

---

## 1. 结论

**Kotlin + Compose Multiplatform，核心逻辑纯 Kotlin/JVM，Android 与桌面共用同一套代码。**

- 语言：Kotlin（JVM 字节码，无解释器开销）
- UI：Compose Multiplatform（Android 用原生 Compose，桌面临 JVM）
- 核心模块：纯 Kotlin，无 UI 依赖，可单独测试
- 开源协议建议：**Apache-2.0**（含专利授权，比 MIT 更稳；允许以后商业化，也允许别人二开）

---

## 2. 为什么是它

| 待选方案 | Android | Windows | 体积/启动 | 与 Minecraft(Java) 契合度 | 开发速度 |
|---|---|---|---|---|---|
| **Kotlin + Compose MP** | ● 原生官方 | ● JVM | 中，40–80 MB | ● 同 JVM，认证/Jar/Zip/进程都有成熟库 | 快 |
| C# + Avalonia | – 基本不可行 | ● 最优 | 中 | ○ Android 端仍要 JVM，等于两套 | 快 |
| Rust + Tauri/Slint | ○ 移动端 beta | ● | ● 最小，10–30 MB | – 与 JVM 全靠 FFI 桥 | 慢 |
| Flutter | ● | ○ 桌面偏弱 | 中 | – 文件/进程/JVM 全靠 platform channel | 中 |
| Java + JavaFX | ○ | ● | 中 | ● | 快，但 UI 观感落后 |

三条硬理由：

1. **手机优先 + 要让手机跑 Java 版 MC，本质就是 JVM。** Android 应用本身就是 JVM，Kotlin 直接与之同构：不需要额外嵌入一层运行时，也不需要为移动端重写一套核心。
2. **兼容好 = 生态。** Minecraft 周边的一切（authlib-injector、Maven 元数据、jar/zip 解析、Java 进程守护、NBT、Region 文件）在 JVM 生态里都有经过十年验证的库。用 Rust/Flutter 每个都要自己造。
3. **不臃肿。** Kotlin 编成字节码执行；桌面端可以 jlink 裁 JRE + 混淆裁剪，成品 40–60 MB 级，冷启动 1 秒内（配 CDS/AOT），比 Electron 轻一个数量级。

---

## 3. 明确排除的方案

- **C# + Avalonia**：桌面体验最好、你也最熟（PCL 是 VB.NET/WPF），但 Android 端等于重开一个项目，且手机跑 MC 仍要 JVM——两套技术栈，与「从零、慢慢做、一个人维护」冲突。
- **Rust + Tauri**：体积和启动最优，但开发速度慢 2–3 倍，MC 相关库稀缺，Android 端还要同时维护 Rust 与 JVM 两侧。
- **Flutter**：UI 三端最省心，但它把「与系统/JVM 交互」全部推给 platform channel，正好撞在启动器最重的部分上。
- **Electron 系**：XMCL 已经证明可行，但体积 150 MB+，与「不臃肿」直接冲突。

---

## 4. 三端形态（待确认第三端）

| 端 | 形态 | 说明 |
|---|---|---|
| Android | 原生 App，Compose | 手机端**能不能真跑 Java 版 MC**取决于第 1 个待确认项 |
| Windows | Compose Desktop + jpackage/jlink | 免安装单目录分发，可做绿色版 |
| 第三端？ | 默认按 Linux 准备 | 见待确认项 3 |

> iOS 基本不做：Minecraft Java 版在 iOS 上无合法且可行的运行路径（无 JVM、无法分发 JRE）。

---

## 5. 风险（现在就摆出来）

| 风险 | 影响 | 缓解 |
|---|---|---|
| Android 上跑 Java 版 MC 需要 LWJGL/OpenGL 移植（Pojav 走 GL4ES/VirGL + 定制 JRE） | 数月级工作量，性能看机型 | 先做「管理端」，把「运行端」作为独立里程碑 |
| Compose Desktop 在 Windows 的高 DPI、输入法与原生观感不如 WPF | 体验打折 | 主题/字体/缩放早期就定规范，别到最后返工 |
| Compose 桌面冷启动比原生慢 | 与 PCL「秒开」有差距 | 首屏懒加载 + 启动预热的 CDS，必要时拆出原生启动壳 |
| 一套 UI 两端适配，容易写成「手机界面放大版」 | 桌面端体验差 | 桌面与手机两套布局策略，共享设计语言而非共享像素 |

---

## 6. 待确认三件事

1. **手机端的定位**：是「在手机上真的玩 Java 版」（需要移植渲染与 JRE，是硬骨头），还是「在手机上管理电脑/服务器的实例、看日志、远程开服」？
   - 前者：技术栈不变，但 Android 端有一个独立的「运行器」大模块，优先做它意味着前 6 个月看不到可用成品。
   - 后者：Android 端可以很快出可用版本，运行仍交给 PC。
   - 两者都要：建议先管理端跑通，再攻运行端。
2. **开源协议**：Apache-2.0（推荐）／MIT／GPL-3.0（防止别人闭源拿走，但你自己也不能闭源）。
3. **第三端**：macOS 还是 Linux？（macOS 对 MC Java 版支持良好，但签名/公证麻烦；Linux 用户更硬核、测试更省事。）

---

## 7. 与第一步的关系

第一步的优点收集里，P0 清单（实例隔离、下载器、Java 管理、账号、启动链路、整合包）在任何技术栈下都成立，因此**可以立刻开始设计**，不必等选型完全落定。技术栈只影响「UI 层怎么写」，不影响核心模块的职责划分。
