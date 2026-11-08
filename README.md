<p align="center">
  <img src="branding/source-baby-1024.png" width="128" alt="鸭嘴兽·孵化版图标">
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

图标：孵化版用幼体半壳版，正式版用成年全身版。各平台成品在 `branding/` 下，源码里已经接好（Android 自适应图标、桌面窗口图标、jpackage 安装包图标）。

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

M0（骨架）+ M1（实例扫描）+ 下载器已完成，当前版本 **0.8.0-preview.1**（孵化线）。Java 运行时探测、启动链路尚未开始。

已实现：实例扫描（`core/instance`）、HTTP 下载器（多源镜像、sha1 校验、`.part` 断点、并发限流）、Mojang 版本清单与版本 json 解析（容错缺字段）、下载计划生成（客户端 jar / libraries / assets 索引 / 资源对象，含 BMCLAPI 镜像备选）。单测 29 个全过。
