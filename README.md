<p align="center">
  <img src="assets/logo.png" width="128" alt="鸭嘴兽启动器图标">
</p>

# 鸭嘴兽启动器（Platypus Launcher）

从零编写的 Minecraft 启动器。桌面端与安卓端共用一套界面代码，Windows、Linux 与安卓三端同步发布。

> **免责声明**
>
> 鸭嘴兽启动器（Platypus Launcher）是一个由第三方开发者制作的非官方 Minecraft 启动器。本项目与 Mojang Studios 及 Microsoft 无任何关联，未获得其授权、认可或赞助。Minecraft 是 Mojang Studios 的注册商标。请支持购买正版游戏。
>
> Platypus Launcher is an unofficial third-party Minecraft launcher. This project is not affiliated with, endorsed by, or sponsored by Mojang Studios or Microsoft. Minecraft is a registered trademark of Mojang Studios. Please support the official game by purchasing it.

## 版本与发布

三端用同一份源码构建，分两条线。哪一条由版本号判定：带 `-preview` 是孵化版，不带是正式版，判定只有这一条，见 [`Brand.kt`](core/src/main/kotlin/io/github/huang404yan/platypus/core/Brand.kt) 的 `isPreview`。

| | 桌面端（Windows / Linux） | 移动终端（安卓、鸿蒙等） |
|---|---|---|
| 正式版 | 鸭嘴兽启动器（Platypus Launcher），`1.0.0` 起进位 | 鸭嘴兽启动器（安卓），随正式版一同发布 |
| 另一条线 | 孵化版（Platypus Preview），`0.x-preview.N` | 孵化版（Platypus Preview），`0.x-preview.N` |

两端另一条线是同一份代码、同一个叫法：**孵化版**，它同时也是开源版 —— 源码完全公开，谁都能自己编一份发出去。桌面端与移动端的区别只在于发不发产物：桌面端会发，移动端只公开源码。这两条线怎么用、怎么自己编，介绍稍后更新。

发布页 [Releases](https://github.com/huang404yan/Platypus-Launcher/releases) 上的附件分两类：

| | re 测试版 | re 正式版 |
|---|---|---|
| 平台 | 只有桌面端 | 桌面端 + 移动终端 |
| 包内容 | 空壳：不内置应用 ID，也不带下载与搜索服务地址 | 完整包：内置应用 ID 与服务地址，装完即可联网 |
| 应用 ID 与地址 | 使用者自己填；填过一次一直记住，覆盖安装与升级都不会冲掉 | 无需填写 |
| 适用 | 想先试新功能，或自己搭了镜像的人 | 日常使用 |
| 标记 | 勾选 Pre-release，不标 Latest | 标 Latest |

**正式版**由本项目构建并发布，功能完整、内置应用 ID。第一次打开需要登录正版账号，验证一次后长期有效。正式版不提供离线进游戏的路径：离线启动等同于盗版，本项目的立足点之一就是不做这件事。

**空壳**指的是包内不带任何下载与搜索服务地址：版本清单、加载器、模组搜索都得先在「设置 → 下载 → 服务地址」填一个镜像根（BMCLAPI 布局的 `http://` / `https://` 地址）才有内容，填完立刻生效、以后一直用；没填之前界面照旧能浏览、本机已有的实例照旧能改，下载页与模组搜索是空的 —— 那不是坏了。应用 ID 同理，自己申请一个填进「设置 → 账号」即可（免费，约三分钟）。

空壳形态由构建开关 `-Pplatypus.shell=true` 决定，发布出去的 re 测试版一律用它构建。自己编一份带服务地址的同样允许 —— 那等于替使用它的人承担镜像流量，编的人自己决定。开关只影响包里有没有服务地址与 ID，不影响任何功能代码。

仓库包含测试版的全部源码；正式版只发布编译好的安装包。

本项目不是 PCL、HMCL、Prism 的分支，仅在产品形态上参考过这几个项目，未复制任何代码，详见 `CONTRIBUTING.md`。

## 需要什么

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 2.4 |
| 界面 | Compose Multiplatform 1.12 |
| 构建 | Gradle 9.9（wrapper 已带）+ Kotlin DSL |
| JDK | 21 |
| 许可 | Apache-2.0 |

**桌面端**只要 JDK 21：

```bash
./gradlew :app-desktop:run
```

Windows 下使用 `gradlew.bat`。首次构建约需下载 300 MB 依赖。

**安卓端**另需 Android SDK：编译需要 Platform 37 与 Build-Tools，编译 JNI 层需要 NDK `28.2.13676358`。将路径写入 `local.properties` 的 `sdk.dir=`，然后：

```bash
./gradlew :androidApp:assembleDebug
```

缺少 NDK 时仍可构建安装包，但包内不含启动游戏所需的 `.so` 文件。

**打 Windows 安装包（msi）**还需要 WiX 3.14，通过 `-PskipWix` 与 `-PwixDir=<WiX 目录>` 指定其位置。只需免安装 zip 时无需配置。

## 目录

```
core/            纯 Kotlin，不依赖界面，也不依赖 Android
  instance/      实例模型（实例 = 自包含目录）
  download/      多源下载、并发、续传、校验
  runtime/       Java 运行时探测、推荐与下载
  auth/          离线 / 微软 / 外置登录
  launch/        启动计划、参数翻译、命令拼装
  modpack/       整合包导入导出
  loader/        加载器元数据（Quilt 的版本列表与 profile 跟别家不一样）
  install/       一键安装流程与四种加载器的安装器
  server/        服务端安装与控制台
ui/              Compose Multiplatform，桌面与安卓共用
app-desktop/     桌面端入口
androidApp/      安卓端入口
```

`GameRuntime` 是两端的分界线：桌面端实现为启动一个 java 进程，安卓端实现为应用内 JVM 加图形转译。上层只依赖该接口，因此模组、存档、整合包等页面在两端共用同一份代码。

两端侧栏为同一套七项：**启动 / 实例 / 下载 / 联机 / 帮助 / 设置 / 关于**。需要下载内容的入口都在「下载」下（原版与加载器、模组、光影资源包、服务端），需要预先配置一次的在「设置」下（常规、账号、Java、诊断）。安卓端二级项在侧栏内展开，桌面端常驻显示。

安卓端的应用内 JVM 已接入 `JNI_CreateJavaVM`。JRE 与图形转译库都由应用自己下载（设置里的「Java 运行时」、启动页的「图形后端」），首次打开会先过一个准备页把这两件与检查更新走一遍；进游戏之前的准备（选择身份、选择运行时、拼装参数、运行自检）可以全程走完。

仍卡在最后一步：Android 10 起禁止从可写目录 `dlopen` 原生库，而 `libjvm.so` 必然解压到可写目录，这是运行层的限制，不是补几行代码能绕过的。

## 下载

编译好的包不在本仓库中，请前往 [Releases](https://github.com/huang404yan/Platypus-Launcher/releases) 下载附件。仓库只保留源码与构建必需的文件：二进制一旦进入提交历史便无法删除，只会持续增大仓库体积。

GitHub 访问不畅时可用 [Gitee 镜像](https://gitee.com/missing-page-404/Platypus-Launcher)。该镜像由 GitHub 直接导入，源码、提交历史与 tag 与上游一致，日常只读同步；**包体仍只在 GitHub Releases 发布**，镜像侧不挂附件。

正版登录需要一个由微软分配的应用 ID。任何随安装包一起下发的 ID，都意味着所有下载者共用同一份他人名下的应用注册；一旦遭遇限流、收紧或滥用判定，拿到包的人无法自行处理：

- **re 正式版**由本项目注入（`-Pplatypus.msClientId=` 或 `PLATYPUS_MS_CLIENT_ID`），下载者无需填写任何内容。
- **re 测试版**不内置。自己申请一个（免费，约三分钟），连同镜像根地址一起填进设置即可，填过一次一直记住，覆盖安装与升级都不会冲掉。字段与取值顺序见 [`ClientIds.kt`](core/src/main/kotlin/io/github/huang404yan/platypus/core/auth/ClientIds.kt)。

自行构建：

```bash
# 带服务地址的包（自己注入应用 ID 与服务地址；不加 shell 开关就是这个形态）
./gradlew :app-desktop:createDistributable

# 空壳包：移除下载与搜索服务地址，应用 ID 也不内置
./gradlew :app-desktop:createDistributable -Pplatypus.shell=true
```

附件命名、目录划分与出包要求见 `releases/README.md`，实现与设计理由见 [`Endpoints.kt`](core/src/main/kotlin/io/github/huang404yan/platypus/core/Endpoints.kt) 与 [`edition/`](core/src/main/kotlin/io/github/huang404yan/platypus/core/edition)。

## 许可与贡献

项目以 Apache-2.0 授权，见 `LICENSE` 与 `NOTICE`。提交改动前请先阅读 `CONTRIBUTING.md`，其中最重要的一条是不复制 GPL 项目的代码。

非官方项目，与 Mojang Studios 及 Microsoft 无关联。
