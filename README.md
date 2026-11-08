# 鸭嘴兽启动器（Platypus Launcher）

从零编写的 Minecraft 启动器。目标：三端（Android 优先、Windows、Linux）、开源、启动快、界面不卡。

## 品牌

| | 正式版 | 孵化版 |
|---|---|---|
| 中文名 | 鸭嘴兽启动器 | 鸭嘴兽·孵化版 |
| 英文名 | Platypus Launcher | Platypus Preview |
| 版本号 | `1.0.0` 起进位 | `0.x-preview.N` |
| Android applicationId | `io.github.huang0yan4.platypus.android` | 同上 + `.preview` |
| 命令行 | `platypus-launcher` | `platypus-launcher-preview` |

口号：**把好东西，缝在一起。** 孵化版的关于页另可写「自然界早就缝合好了。」

> 非官方 Minecraft 启动器，与 Mojang / Microsoft 无关联。

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

## 下载

编译好的包在 `releases/` 下，按正式版 / 孵化版分目录，各自再分平台：

```
releases/stable/{android,windows,linux}/    Platypus Launcher 正式版
releases/preview/{android,windows,linux}/   Platypus Preview 孵化版
```

每个平台最多留 5 个，规则见 `releases/README.md`。

> 目前只有 Android 的孵化版包。桌面端还没有可分发的 exe（缺 jpackage 打包配置）。

## 状态

M0（骨架）+ M1（实例扫描）已完成，当前版本 **0.8.0-preview.1**（孵化线）。下载器、Java 运行时、启动链路尚未开始。
