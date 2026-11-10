# 17 · Forge 安装器闭环（选版本 → 跑官方 installer → 补 client jar → 建实例 → 进主菜单）

记录日期：2026-11-09。本文只写**已经跑通过的事实**与**还没做到的部分**，不含计划。

> 编号说明：`docs/` 里 01–16 已占用（06 有重号），本轮取 17。

---

## 一、为什么不自己拼 Forge 的库

Fabric 那条线（`docs/15`）能自己拼，因为它的 profile json 里 8 个库全给了 `url`。Forge 不行：

| 差异 | Fabric | Forge |
| --- | --- | --- |
| 库数量 | 8 | 29（1.20.1-47.4.28 实测） |
| 生成方式 | 官方 profile 接口现成 | 要先跑 installer 里的 **10 个 processor** |
| 中间产物 | 无 | `client-slim.jar` / `client-extra.jar` / `client-srg.jar`，由 jarsplitter、ForgeAutoRenamingTool、binarypatcher 依次加工原版 jar 得到 |
| 打补丁 | 不用 | 用 `data/client.lzma` 这个二进制补丁，**binpatch 结果带 sha1 断言**（`PATCHED_SHA`） |

自己复刻这条流水线 = 实现 lzma 解压 + binpatch + srg 重映射 + jarsplitter 四件事，任何一步做错都是「能装不能起」，而且错在哪要拿 BCV 一类字节码工具才看得出来。所以走**官方 installer jar 的 CLI**：

```
java -jar forge-<mcver>-<forgever>-installer.jar --installClient <目标目录>
```

这正是 PCL/HMCL 的做法，也是文件作者在 `install_profile.json` 的 `_comment_` 里点名要求的（原文："Please do not automate the download and installation of Forge."，后接支持 patreon 的请求）——**界面上留一句致谢**，别当没看见。

## 二、两个硬约束（都会让你「装完等于没装」）

### 2.1 目标目录里必须有 `launcher_profiles.json`

首次跑报错原文：

```
There is no minecraft launcher profile in "D:\mc-test-forge\client", you need to run the launcher first!
There was an error during installation
```

注意 **进程退出码仍然是 0**——installer 自己把失败咽了。所以只看 exit code 会误判成功，必须回头校验 `versions/<id>/<id>.json` 是否真的出现。

修法：装之前写一份最小档案

```json
{"profiles":{},"settings":{},"version":3}
```

已存在就**绝不覆盖**（用户的档案里有他自己启动器的条目）。

### 2.2 装完必须把原版 client jar 复制成 `<versionId>.jar`

不补会崩，报错原文：

```
Exception in thread "main" java.lang.module.ResolutionException: Module minecraft contains package net.minecraft.server, module _1._20._1 exports package net.minecraft.server to minecraft
	at java.base/java.lang.module.Resolver.resolveFail(Resolver.java:901)
	at MC-BOOTSTRAP/cpw.mods.modlauncher@10.0.9/cpw.mods.modlauncher.ModuleLayerHandler.buildLayer(ModuleLayerHandler.java:75)
	at cpw.mods.bootstraplauncher@1.1.2/cpw.mods.bootstraplauncher.BootstrapLauncher.main(BootstrapLauncher.java:141)
```

根因链条：

1. Forge 的 version json 里 jvm 参数带 `-DignoreList=...,${version_name}.jar`，意思是「**版本目录里那个同名 jar 不要当模块加载**」。
2. 该 jar 不存在时，ignoreList 指空气。
3. 启动器的 `VersionResolver.locateClientJar` 沿 `inheritsFrom` 找到原版 `versions/1.20.1/1.20.1.jar`，把它放进 classpath。
4. 那个 jar 没有 `module-info.class`（实测 20 953 个 entry，0 个 module-info），于是被当成**自动模块 `_1._20._1`**。
5. 同时 patch 过的 minecraft 模块（`client-srg.jar` 那条链）也导出 `net.minecraft.server` → 两模块撞包 → `ResolutionException`。

修法：装完把 `versions/<原版>/<原版>.jar` 复制成 `versions/<forgeId>/<forgeId>.jar`（1.20.1 实测 23 028 853 B）。复制而非软/硬链接：用户可能删掉原版版本目录，硬链接会让两边一起消失；软链接在 Windows 上还要开发者模式或管理员。

手工验证：`cp` 一份 jar 后重跑，`MENU REACHED +12830 ms`；把它 `mv` 走后重跑，8 秒退 1。这条结论是**对照实验**得出的，不是猜的。

## 三、端点与坐标（2026-11-09 实测）

| 用途 | 地址 | 实测 |
| --- | --- | --- |
| 版本推荐 | `https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json` | 200 / 4 153 B |
| 安装器 | `https://maven.minecraftforge.net/net/minecraftforge/forge/<mcver>-<forgever>/forge-<mcver>-<forgever>-installer.jar` | 200 / Content-Length 6 056 587（47.4.0）、8 859 760（47.4.28） |
| 安装器 sha1 | 上述地址 + `.sha1` | 200 / 40 B |

`promotions_slim.json` 形状：

```json
{"homepage":"...","promos":{"1.20.1-latest":"47.4.29","1.20.1-recommended":"47.4.28","1.20.4-recommended":"49.2.13","1.20.6-latest":"50.2.15"}}
```

解析规则：`<游戏版本>-recommended` 优先，其次 `<游戏版本>-latest`，两者相同只留一条（界面标签分别叫「推荐」「最新」）。没有该游戏版本时给中文原因而不是空列表。

**NeoForge 这条线本轮没做**：`maven.neoforged.net` 在本机 100% 不通（curl `http_code=000`，DNS 10 ms 解析成功、TCP 连接 0 秒即失败，15 s / 30 s 都超时）。代码可以照 Forge 写，但**没有一条能跑的验证**，所以不写。

## 四、代码结构

| 层 | 文件 | 干什么 |
| --- | --- | --- |
| core | `core/src/main/kotlin/io/github/huang404yan/platypus/core/loader/ForgeMeta.kt` | 纯逻辑：promotions 解析、安装器坐标、`versionId`、`displayName`、从 id 反推 forge 版本、最小 `launcher_profiles.json` 常量 |
| core 测试 | `core/src/test/kotlin/.../core/loader/ForgeMetaTest.kt` | 10 例，含 1.20.1 / 1.20.4 / 1.20.6 / 1.7.10 四个真实响应夹具 |
| core | `core/src/main/kotlin/io/github/huang404yan/platypus/core/install/ForgeInstaller.kt` | 编排：查版本 → 下安装器 → 备档案 → 起子进程 → 校验 json → 补 client jar |
| core | `.../core/install/OneClickInstaller.kt` | `LoaderChoice` 加 `Forge` 分支，与 Fabric 共用同一套五步流程 |
| ui | `.../ui/DownloadsPage.kt` | 新增 `ForgeRow`，与 `FabricRow` 共用抽出来的 `LoaderPickerRow` |
| 验收 | `app-desktop/src/test/.../desktop/ForgeE2e.kt` | 无头全流程，退出码与 `FabricE2e` 同义 |

`ForgeInstaller.install()` 的六步（`ForgeStep`）：

| 步骤 | label | 细节 |
| --- | --- | --- |
| `VERSIONS` | 查询 Forge 版本 | 只在没指定版本时走 |
| `INSTALLER` | 下载 Forge 安装器 | 已存在且是合法 zip（有 `META-INF/MANIFEST.MF`）就复用，不重下 |
| `PREPARE` | 准备启动器档案 | 写 `launcher_profiles.json`，已存在不覆盖 |
| `RUN` | 运行 Forge 安装器 | 子进程逐行透传日志，落 `<游戏目录>/forge-installers/<versionId>-install.log`，15 分钟超时后 `destroyForcibly()` |
| `VERIFY` | 校验安装结果 | json 存在 且 `inheritsFrom == 原版` 且 `mainClass` 是 `cpw.mods.bootstraplauncher.BootstrapLauncher` |
| `CLIENT_JAR` | 补齐版本 jar | 见 2.2 |

已装过（json 已存在且合法）时**跳过 installer 但仍走 `CLIENT_JAR`**——修的是老版本装出来的那份缺 jar 的目录。

## 五、实测数字

### 5.1 已有原版的目录（`dist/_test-mc/_e2e-clean-20261109`，skipOrig=true）

```
[20:52] FORGE OK in 7341 ms; versionId=1.20.1-forge-47.4.28 version=47.4.28 exit=0
[20:52]   LOADER_INSTALL 补齐版本 jar：复制原版 1.20.1.jar → 1.20.1-forge-47.4.28.jar
[20:52] REGISTERED 1.20.1-forge-47.4.28 loader=FORGE loaderVersion=47.4.28
[20:52] MENU REACHED +12284 ms: Setting user: Dev
```

### 5.2 首次真装（同一目录，installer 真跑）

```
INSTALL OK in 173285 ms
installer=forge-1.20.1-47.4.28-installer.jar bytes=8859760 exit=0 ms=166127
```

### 5.3 空目录全流程（`dist/_test-mc/_e2e-fresh3-20261109`，skipOrig=false，2026-11-09 21:26–21:34）

从一个**完全空的目录**开始，走完下载 → 补 Java → 装 Forge → 补 client jar → 登记 → 启动：

```
[21:26:41] STEP PLANNING     解析版本元数据   emptyDir=true
[21:26:44] STEP DOWNLOADING  下载游戏文件     3641 个文件，21:30:42 到 100%
[21:30:42] STEP JAVA_CHECK   检查 Java 运行时  游戏需要 Java 17
[21:30:42] STEP JAVA_INSTALL 下载 Java 运行时  本机没有 Java 17，自动下 java-runtime-gamma，21:31:46 到 100%
[21:31:46] STEP LOADER_INSTALL 安装加载器
             查询 Forge 版本 → 1.20.1-recommended = 47.4.28
             下载 forge-1.20.1-47.4.28-installer.jar（8 859 760 B）
             Successfully installed client into launcher.（21:34:20，installer 自己跑 153 978 ms）
             校验安装结果 → 通过
             补齐版本 jar：复制原版 1.20.1.jar → 1.20.1-forge-47.4.28.jar
[21:34:20] STEP REGISTERING  登记实例        count=1
[21:34:20] INSTALL OK in 458859 ms; javaInstalled=true
             java=...\runtime\java-runtime-gamma\bin\java.exe major=17 vendor=Microsoft managed=true
             vanilla downloaded=3641 failures=0
[21:34:31] MENU REACHED +11011 ms: Setting user: Dev
[21:34:36] RESULT menuReached=true launchMs=11011 exitCode=1（1 = e2e 自己 stop）
             TOTAL ms=475125
```

两个细节值得记：

1. **自动补的 Java 17 落在游戏目录内**（`<游戏目录>/runtime/java-runtime-gamma/`），不是全局。这样「删掉游戏目录」就等于「彻底卸载」，不留系统级残留。
2. 游戏日志里 `Forge mod loading, version 47.4.28, for MC 1.20.1 with MCP 20230612.114412` 与 `MinecraftForge v47.4.28 Initialized` 都在，说明**真的是 Forge 在跑**，不是回退到原版。

installer 自己挑的是 Microsoft 17.0.15（本机 `%APPDATA%\.minecraft\runtime\java-runtime-beta`），下载走它内建的 `maven.creeperhost.net` 镜像——**这不受我们控制**，也不是我们指定的。

installer 日志里有大量 `Cant Find Class: org/lwjgl/glfw/GLFWErrorCallback`、`com/google/gson/JsonParseException`，属正常噪声（它把整个 classpath 都扫一遍找可选适配器），**不影响成功**。

### 5.4 装出来的目录（1.20.1 + 47.4.0，约 100 MB）

```
launcher_profiles.json
libraries/net/minecraftforge/{...}        24 个 jar
libraries/net/minecraft/client/1.20.1-20230612.114412/{slim,extra,srg}.jar
versions/1.20.1/1.20.1.jar
versions/1.20.1-forge-47.4.0/1.20.1-forge-47.4.0.json    ← 只有 json
```

注意 `client-srg.jar` 那三个是 processor 的中间产物，**必须留着**，不是缓存。

## 六、界面

下载页在「顺带装 Fabric」下面多一行「顺带装 Forge」：

- 版本号框为空时状态写「先选一个原版版本」，不发请求；
- 输入框停顿 400 ms 后拉 promotions（4 KB，比 Fabric 的 loader 列表更便宜）；
- 拉回来默认落在 **recommended**，展开可选 latest；
- 勾选框与 Fabric **互斥**（勾一个自动清另一个），按钮文案随之变成「安装 Forge」；
- 状态行提示「<版本>（官方安装器，约 3 分钟）」，把耗时先说清楚，别让人以为卡住。

## 七、已知取舍

| 取舍 | 理由 / 代价 |
| --- | --- |
| 依赖官方 installer 子进程 | 优点：不用复刻 4 件字节码工具，跟着官方升级；代价：要本机有 Java，且 installer 自己会再下一遍原版 jar |
| 装之前必须已有 Java | installer 本身是 jar，得先有运行时。所以 `OneClickInstaller` 里 Forge 分支排在「补 Java」之后 |
| 只有 exit code 不可信 | installer 失败也退 0，一律以 `VERIFY` 的结果为准 |
| 安装器缓存在 `<游戏目录>/forge-installers/` | 重装同一版本不再下 8.8 MB；代价是每个游戏目录多一个可能被忘掉的文件夹（日志也在里面） |
| NeoForge 未做 | 本机到 `maven.neoforged.net` 不通，写了也没法验，宁可空着 |
| 不做 `--installServer` | 只服务客户端，服务端安装是另一个产品形态 |

## 八、还没做到

- NeoForge（网络不通，无验证）
- Forge 的老版本（1.7.10 及更早）用的是**解压式**安装器，没有 `--installClient`，需要另一条路；本轮只覆盖 1.13+ 的 installer 形态
- 安装器升级判断：目前按 `versionId` 命中就直接跳过，不比对 installer 自身版本
