# 15 Fabric 安装器闭环（选原版 → 装 Fabric → 补 Java → 建实例 → 启动进主菜单）

记录日期：2026-11-09。本文只记录**已经跑通过的事实**与**还没做到的部分**，不含计划。

> 编号说明：`docs/` 里 01–14 已占用（06 有重号），16 是实例删除。本轮取 15，接在 14 之后。

---

## 一、这块原来断在哪

12 那轮的闭环只能装原版：`OneClickInstaller.install()` 的产出是一个 vanilla 版本目录 + 一个 `loader = VANILLA` 的实例。用户想装模组，必须自己去 Fabric 官网按游戏版本+loader 版本手算 maven 路径、手拼 8 个 jar 的下载地址、手写版本 json——**这正是 PCL 帮用户省掉的那部分活**。

真正的技术障碍不在「下载」而在**版本 json 的形状**：

| 差异点 | Mojang 的 version json | Fabric 的 profile json |
| --- | --- | --- |
| 下载信息位置 | `libraries[i].downloads.artifact.{path,url,sha1,size}` | 没有 `downloads` 字段，`url` / `sha1` / `size` **平铺在库对象上** |
| 库数量 | 几十个 | 8 个（1.20.1 + loader 0.20.1 实测） |
| `path` 字段 | 直接给 `org/ow2/asm/asm/9.10.1/asm-9.10.1.jar` | **完全没有**，得自己按 maven 规则从 `name` 推 |
| 缺 sha1 | 不会发生 | 最后两项（`net.fabricmc:intermediary`、`net.fabricmc:fabric-loader`）只有 `name` + `url` |
| 客户端 jar | 自己带 `downloads.client` | `inheritsFrom` 指向原版，自己没有 |

所以 `core/.../core/version/DownloadPlanner.kt` 的 `libraries()` 第一行就是 `val downloads = library.downloads ?: return@mapNotNull null`——**照它规划，Fabric 的 8 个库会被全部丢掉**，装出来是个空的加载器目录，启动时 `NoClassDefFoundError`。这轮没有改 `DownloadPlanner`（它在原版路径上是验证过的），而是新写了一条并行的规划函数。

---

## 二、Fabric 官方元数据 API（实测响应）

两个接口，都是匿名 GET，不需要 token，也不需要 User-Agent 特判。

### 2.1 loader 版本列表

```
GET https://meta.fabricmc.net/v2/versions/loader/1.20.1
```

实测 2026-11-09，HTTP 200，响应是**数组**。任务书里描述的扁平形状 `{"separator":"+build.","build":15,"version":"0.16.14","stable":true}` 是**旧版/documented 形状**，实际响应多包了一层 `loader` 对象：

```json
[
  {
    "loader": {
      "separator": ".",
      "build": 1,
      "maven": "net.fabricmc:fabric-loader:0.20.1",
      "version": "0.20.1",
      "stable": true
    },
    "intermediary": {
      "maven": "net.fabricmc:intermediary:1.20.1",
      "version": "1.20.1",
      "stable": true
    },
    "launcherMeta": {
      "version": 2,
      "min_java_version": 8,
      "libraries": { "client": [], "common": [ { "name": "org.ow2.asm:asm:9.10.1", "...": "..." } ] }
    }
  }
]
```

数组**新的在前**，所以「最新 stable」= 第一个 `loader.stable == true` 的项，不用排序。

解析器同时吃两种形状：`FabricLoaderEntry`（`core/.../core/loader/FabricMeta.kt:104`）里 `loader` 字段可空，为 null 时用同层平铺的 `version/stable/build/maven` 兜底（`resolved`，`FabricMeta.kt:112`）。这样 Fabric 哪天把包装层去掉，这里不会跟着崩。

### 2.2 完整可启动的版本 json（profile）

```
GET https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.20.1/profile/json
```

实测 HTTP 200，**2862 字节**。关键字段（`dist/_fabric-e2e-run2/versions/1.20.1-fabric-0.20.1/1.20.1-fabric-0.20.1.json` 里落盘后复核过）：

```json
{
  "id": "fabric-loader-0.20.1-1.20.1",
  "inheritsFrom": "1.20.1",
  "releaseTime": "2026-11-08T19:09:01+0000",
  "type": "release",
  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
  "arguments": { "game": [], "jvm": ["-DFabricMcEmu= net.minecraft.client.main.Main "] },
  "libraries": [ ... 8 项 ... ]
}
```

**注意 `id` 是 `fabric-loader-0.20.1-1.20.1`（loader 在前、原版在后），与我们要的 `1.20.1-fabric-0.20.1` 相反。** 落盘时必须改 `id`，否则 `VersionResolver` 按目录名找 json 会对不上。

8 个库的实测内容：

| # | name | sha1 | size |
| --- | --- | --- | --- |
| 1 | `org.ow2.asm:asm:9.10.1` | `ada2141c0cc52ee8f5c48cd5fa4ce0e794f22236` | 126,151 |
| 2 | `org.ow2.asm:asm-analysis:9.10.1` | `8d49f14d51f632cb1d87c88d1ceaf50db0d8af1b` | 35,140 |
| 3 | `org.ow2.asm:asm-commons:9.10.1` | `4229e4c55fd8e01c23f9fe9884075cc628aacc50` | 74,840 |
| 4 | `org.ow2.asm:asm-tree:9.10.1` | `e244332a17564c1d1572449399a842de35881be2` | 51,958 |
| 5 | `org.ow2.asm:asm-util:9.10.1` | `7bb9d450e8d4cbf9f9e04096c44bbfe7fba80b15` | 95,628 |
| 6 | `net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7` | `0cef8519a91129d0c21fa03bb9378f0e8ca810e5` | 1,551,593 |
| 7 | `net.fabricmc:intermediary:1.20.1` | —（无字段） | —（无字段） |
| 8 | `net.fabricmc:fabric-loader:0.20.1` | —（无字段） | —（无字段） |

前 6 项还带 `md5` / `sha256` / `sha512`，全部 `url = "https://maven.fabricmc.net/"`。

第 7、8 项**没有 sha1 也没有 size**，这是 Fabric 的正常行为（它们由 maven 的 `.sha1` 附属文件兜底，但 url 里没写）。实测下载后大小：`intermediary-1.20.1.jar` **573,365 B**、`fabric-loader-0.20.1.jar` **1,988,880 B**——这两个文件**只能校验「下载成功 + 非空」，没有 hash 可比**。

---

## 三、maven 坐标补 url 的规则

实现在 `core/src/main/kotlin/io/github/huang404yan/platypus/core/loader/FabricMeta.kt:207` 的 `object FabricLibraries`，三个纯函数：`jarPath` / `jarUrl` / `plan`。

### 3.1 落地路径 `jarPath`

优先级从高到低：

1. `downloads.artifact.path`（Mojang 风格，原样用）；
2. 从 `LibraryName.parse(library.name)` 推：`name` 按 `:` 切成 `group:artifact:version[:classifier]`，路径 = **group 里的 `.` 换成 `/`** + `/artifact/version/artifact-version[-classifier].jar`；
3. `name` 里没有 classifier 但 `natives` 映射到当前系统时，用 `nativeClassifier(os)` 补上（ASM/Mixin 这些纯 java 库没有 natives，这条在本轮实测里没被触发，靠单测 `nativesMappingSuppliesClassifier` 锁住）。

例：`net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7`
→ `net/fabricmc/sponge-mixin/0.17.5+mixin.0.8.7/sponge-mixin-0.17.5+mixin.0.8.7.jar`

注意 `+` 原样保留，不做 URL 编码——实测 maven.fabricmc.net 接受未编码的 `+`，编码成 `%2B` 反而可能 404。

### 3.2 完整地址 `jarUrl`

1. `downloads.artifact.url`（Mojang 风格）；
2. `library.url`：**以 `.jar` 结尾就当完整地址直接用**，否则当成仓库前缀，`trimEnd('/') + "/" + path`；
3. 两者都没有时按 group 选默认仓库：`net.fabricmc` / `org.quiltmc` → `https://maven.fabricmc.net/`，其余 → `https://libraries.minecraft.net/`。

之所以要第 3 条：老版本 Fabric json（以及某些第三方启动器生成的 json）只写 `name` 不写 `url`。单测 `missingUrlFallsBackByGroup` 锁的是这条。

### 3.3 镜像

`mirrors = DownloadPlanner.mirrorOf(url)`。实测 **Fabric maven 没有镜像，8 项 mirrors 全是空表**，走直连；Mojang `libraries.minecraft.net` 的库才有 BMCLAPI 备胎。这解释了为什么 e2e 里 Fabric 这 8 个库 1.9 MB 用了 8.4 秒——直连海外，没有加速。

---

## 四、版本号命名

`FabricMeta.versionId(gameVersion, loaderVersion)` = `<原版号>-fabric-<loader 版本>`，即 `1.20.1-fabric-0.20.1`，与 PCL 习惯一致。

反向解析 `loaderVersionFromId()` 认三种写法：

| 写法 | 来源 | 正则 |
| --- | --- | --- |
| `1.20.1-fabric-0.16.14` | 本启动器 | `.*-fabric-(\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.\-]+)?)$` |
| `fabric-loader-0.16.14-1.20.1` | Fabric 官方 profile 的 id | `fabric[-_]loader[-_](\d+\.\d+(?:\.\d+)?)` |
| `fabric-0.16.14` | 老的自定义命名 | 同第 2 条（`[-_]` 兼容） |

**这里踩过一个坑**：官方 profile 的 id 里 loader 版本后面还跟着原版号，第一版正则给 `\d+\.\d+` 加了可选后缀 `[-+][0-9A-Za-z.\-]+`，结果 `fabric-loader-0.16.14-1.20.1` 被解析成 `0.16.14-1.20.1`（把 `-1.20.1` 一起吃进去了），`FabricMetaTest` 里 `loaderVersionFromOurIdAndOfficialId` 直接红。官方那条**不能**允许后缀；自家那条（`-fabric-` 锚定在末尾）才可以。

`InstanceScanner.detectLoader`（`core/.../core/instance/InstanceScanner.kt:101`）同步加了自家写法：先试 `fabric[-_]loader[-_](...)`，不中再试 `fabric[-_]([0-9][0-9.]*)$`。这样手工拷进来的 Fabric 版本目录也能被扫描认出来。

---

## 五、代码结构

| 层 | 文件 | 职责 |
| --- | --- | --- |
| core（纯 Kotlin，无网络） | `core/.../core/loader/FabricMeta.kt`（~300 行） | URL 拼装、版本号命名与反解、`FabricParse` 结果壳、loader 列表/profile 解析、`FabricFiles.writeProfile`、`FabricLibraries` 补 url + `plan` |
| core 改 | `core/.../core/version/VersionMeta.kt:117-126` | `Library` 增加平铺字段 `url/sha1/size/md5`，**全部带默认值**，Mojang json 的反序列化不受影响 |
| core 改 | `core/.../core/instance/InstanceScanner.kt:101` | 认 `1.20.1-fabric-x.y.z` 这种版本号 |
| ui（联网与编排） | `ui/.../ui/download/FabricInstaller.kt`（222 行） | `listLoaders` / `install` / `installedLoaders` / `register`；`FabricStep` 四步 + `FabricProgress` + `FabricInstallException` |
| ui 改 | `ui/.../ui/download/OneClickInstaller.kt` | 新增 `sealed interface LoaderChoice { Vanilla, Fabric(loaderVersion) }`；`InstallStep.LOADER_INSTALL`；`InstallOutcome.fabric` |
| ui 改 | `ui/.../ui/DownloadsPage.kt` | `FabricRow`（勾选 + loader 列表 + stable/beta 标记）、`refreshLoaders`、按钮文案随勾选变 |
| ui 改 | `ui/.../ui/InstancePage.kt:810` | `describeStep` 补 `LOADER_INSTALL` 分支（`when` 必须穷尽） |

### 装 Fabric 的四步（`FabricStep`）

| step | 做什么 | 失败文案 |
| --- | --- | --- |
| `LOADER_LIST` | GET loader 列表，挑第一个 `stable` | 「Fabric 查询 Fabric 版本失败：…」 |
| `PROFILE` | GET profile json，校验 `libraries` 非空 + `mainClass` 非空 | 「Fabric 拉取 Fabric 配置失败：…」 |
| `WRITE_JSON` | 改写 `id` 后落盘，**其余字段原样保留** | 「Fabric 写入版本 json 失败：…」 |
| `LIBRARIES` | 规划 8 个库 → 建目录 → `HttpDownloader`（16 并发、带续传） | 「Fabric 下载 Fabric 库失败：N 个 Fabric 库下载失败，第一个是 X：…」 |

在 `OneClickInstaller` 里的位置是**第 ④ 步，在补 Java 之后、登记实例之前**：Fabric 的 profile 是 `inheritsFrom` 原版的，必须先有原版；而 `HttpDownloader` 与 Java 无关，所以放在 Java 后面能保证失败时用户已经拿到了可复用的原版+Java，重试只补加载器。Forge 下一轮接进来时在这加一个 `LoaderChoice.Forge` 分支即可，顺序不用动。

### 为什么 writeProfile 只改 id

`FabricFiles.writeProfile`（`FabricMeta.kt:187`）用 `parseToJsonElement` 拿 `JsonObject`，只 `put("id", ...)`，再用 `VersionJsonFormat.encodeToString` 写回。**不反序列化成 `VersionJson` 再序列化**——那样会把 `releaseTime` / `sha256` / `sha512` / `launcherMeta` 这些我们没有建模的字段全丢掉，而 `inheritsFrom` 一旦丢就起不来。

实测落盘 2855 B，`id` 已改成 `1.20.1-fabric-0.20.1`，`inheritsFrom` 仍是 `1.20.1`，`arguments.jvm` 仍是 `["-DFabricMcEmu= net.minecraft.client.main.Main "]`，`libraries` 仍是 8 项且第 1 项连 `sha512` 都还在。

### 实例的字段

装完的实例（实测 `dist/_fabric-e2e-run2/platypus/instances.json`）：

```
id            = 1.20.1-fabric-0.20.1
name          = Fabric 0.20.1 / 1.20.1
gameVersion   = 1.20.1        ← 仍是原版号
loader        = FABRIC
loaderVersion = 0.20.1
memoryMb      = 2048
registered    = true
```

`LoaderKind` 枚举**本来就有 FABRIC**（`core/.../core/instance/Instance.kt`），不需要加，也没动反序列化默认值。

---

## 六、端到端实测

### 6.1 命令

```
MSYS_NO_PATHCONV=1 cmd.exe /c "D:\ruanjian\kaifa\qidongqigongju\gradle-9.9.0\bin\gradle.bat \
  --no-daemon :app-desktop:fabricE2e \
  -PfabricDir=dist/_fabric-e2e-run2 -Pgv=1.20.1 -PskipWix"
```

`BUILD SUCCESSFUL in 4m 41s`，exit 0。游戏目录 `D:\ruanjian\zhinengti\gongzuokongjian\platypus\dist\_fabric-e2e-run2`（运行前空目录，`emptyDir=true`）。

报告：`dist/_e2e-logs/fabric-e2e-1.20.1-20261109-190724.log`
游戏日志：`dist/_e2e-logs/fabric-game-1.20.1-20261109-190724.log`

### 6.2 时间线（实测，时间为报告内时间戳）

| 时刻 | 事件 | 耗时 |
| --- | --- | --- |
| 19:07:24.973 | STEP PLANNING | — |
| 19:07:30.192 | STEP DOWNLOADING「3641 个文件」 | — |
| 19:10:55.273 | STEP JAVA_CHECK | 原版下载约 **205 秒** |
| 19:10:55.899 | 本机没有 Java 17 → 下载 `java-runtime-gamma` | — |
| 19:11:26.367 | Java 100% | 约 **30 秒** |
| 19:11:26.534 | **STEP LOADER_INSTALL** | — |
| 19:11:28.711 | 拉到 0.20.1 的 profile json | 约 2 秒 |
| 19:11:29.724 | 写入 `1.20.1-fabric-0.20.1` | — |
| 19:11:34.950 | 8 个库下完（8/8） | 约 **5.2 秒** |
| 19:11:34.965 | `INSTALL OK in 250045 ms`（Java 这次是新下的） | — |
| 19:11:34.980 | LAUNCH start | — |
| **19:11:48.780** | **`MENU REACHED +13796 ms: [19:11:48] [Render thread/INFO]: Setting user: Dev`** | — |
| 19:11:54.389 | `RESULT menuReached=true launchMs=13796 exitCode=1` | — |
| — | `TOTAL ms=269471`（**4 分 29 秒**） | — |

`exitCode=1` 是脚本在进主菜单 3 秒后主动 `stop()` 的退出码，`LaunchDiagnosis` 判定为「进入主菜单后退出，日志里没有崩溃迹象」。

### 6.3 数字

| 项 | 值 |
| --- | --- |
| 原版文件 | **3641 个，failures=0** |
| Fabric 库 | **8 个，1,935,310 B（json 里声明的 size 之和），failures=0** |
| Fabric 这一步耗时 | **8,413 ms**（19:11:26.534 → 19:11:34.95） |
| 自动挑中的 loader | **0.20.1**（最新 stable，不是任务书举例的 0.16.14） |
| 版本 json | `dist/_fabric-e2e-run2/versions/1.20.1-fabric-0.20.1/1.20.1-fabric-0.20.1.json`，**2855 B** |
| Java | `dist/_fabric-e2e-run2/runtime/java-runtime-gamma/bin/java.exe`，major=17，vendor=Microsoft，managed=true |
| 进主菜单 | **13,796 ms** |

> 只算 Fabric 那一段的下载字节：前 6 个有 size 的库合计 1,935,310 B，但真实落盘 8 个文件合计 4,497,555 B——差的 2,562,245 B 就是第 7、8 项（`intermediary-1.20.1.jar` 573,365 B + `fabric-loader-0.20.1.jar` 1,988,880 B）**因为 json 里没写 size 而没被计入进度分母**。进度条因此会先冲到 100% 再停几秒。这是已知缺陷，见第九节。

### 6.4 能证明「Fabric 加载器正常初始化」的日志原文

`dist/_e2e-logs/fabric-game-1.20.1-20261109-190724.log` 第 1–6 行（完整 33 行）：

```
本版本没有 client jar，使用父版本 1.20.1 的 jar
[19:11:42] [main/INFO]: Loading Minecraft 1.20.1 with Fabric Loader 0.20.1
[19:11:42] [main/INFO]: Fabric is preparing JARs on first launch, this may take a few seconds...
[19:11:42] [main/INFO]: Loading 4 mods:
	- fabricloader 0.20.1
	   \-- mixinextras 0.5.5
	- java 17
	- minecraft 1.20.1
[19:11:43] [main/INFO]: SpongePowered MIXIN Subsystem Version=0.8.7 Source=file:/D:/ruanjian/zhinengti/gongzuokongjian/platypus/dist/_fabric-e2e-run2/libraries/net/fabricmc/sponge-mixin/0.17.5+mixin.0.8.7/sponge-mixin-0.17.5+mixin.0.8.7.jar Service=Knot/Fabric Env=CLIENT
```

后面 `KnotClient.main` 出现在 `MinecraftClientHttpException` 的栈里，说明**入口类确实是 Fabric 的 `KnotClient` 而不是原版 `Main`**：

```
	at net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider.launch(MinecraftGameProvider.java:517) ~[fabric-loader-0.20.1.jar:?]
	at net.fabricmc.loader.impl.launch.knot.Knot.launch(Knot.java:72) ~[fabric-loader-0.20.1.jar:?]
	at net.fabricmc.loader.impl.launch.knot.KnotClient.main(KnotClient.java:23) ~[fabric-loader-0.20.1.jar:?]
```

然后是进主菜单的两行：

```
[19:11:48] [Render thread/INFO]: Setting user: Dev
[19:11:48] [Render thread/INFO]: Backend library: LWJGL version 3.3.1 SNAPSHOT
```

**这些都是实测**：`Loading Minecraft 1.20.1 with Fabric Loader 0.20.1` 这一行只有 Fabric 的 `MinecraftGameProvider` 会打，原版客户端打的是 `Loading Minecraft 1.20.1 with Fabric Loader ...` 之外的另一套启动日志。

### 6.5 日志里那条 401 不是问题

```
[19:11:48] [Render thread/ERROR]: Failed to verify authentication
com.mojang.authlib.exceptions.InvalidCredentialsException: Status: 401
```

这是**离线登录**（`OfflineProfile.session("Dev")`）的必然结果：游戏拿离线 token 去调 Mojang 的 sessionserver 校验，当然 401。原版 e2e（`docs/12`）里同样有这条，游戏照样进主菜单。**与 Fabric 无关**。

---

## 七、测试

### 7.1 core 单测：`FabricMetaTest` 22 例

`core/src/test/kotlin/io/github/huang404yan/platypus/core/loader/FabricMetaTest.kt`。

| 用例 | 锁的是什么 |
| --- | --- |
| `parsesNestedLoaderList` / `parsesFlatLoaderList` | 实测的嵌套形状与文档的扁平形状都能吃 |
| `blankLoaderListGivesReason` / `emptyLoaderListGivesReason` | 空响应 / `[]` 给中文原因，不抛裸异常 |
| `brokenLoaderListGivesReason` | `{"not":"an array"}` 给原因 |
| `entriesWithoutVersionAreReported` | 每条都缺 `version` 时明确报「字段缺失」 |
| `latestStableSkipsNewerUnstable` / `latestStableFallsBackToFirstWhenNoneStable` | 挑第一个 stable；全 beta 时退化为第一个 |
| `parsesProfile` | 真 profile（8 库 + KnotClient）解析成功 |
| `profileWithoutLibrariesGivesReason` / `profileWithoutMainClassGivesReason` / `brokenProfileGivesReason` | 三种残 json 各给原因 |
| `writeProfileRewritesIdKeepsInheritsFrom` | 只改 `id`，`inheritsFrom` 与 `arguments` 原样保留 |
| `versionIdFollowsPclHabit` | `1.20.1-fabric-0.16.14` |
| `loaderVersionFromOurIdAndOfficialId` | 自家与官方两种 id 都反解出 `0.16.14`（这条红过一次，见第四节） |
| `fillsUrlFromMavenCoordinateWithDeclaredPrefix` | `url` 前缀 + maven 路径拼出完整地址 |
| `missingUrlFallsBackByGroup` | 无 `url` 时 `net.fabricmc` → Fabric maven，其他 → Mojang |
| `classifierInNameIsKept` | `...:natives-windows` 这种 classifier 不被丢掉 |
| `nativesMappingSuppliesClassifier` | `name` 无 classifier 时用 `natives` 映射补 |
| `mojangStyleDownloadsStillWin` | 有 `downloads.artifact` 时优先用它，不被平铺字段覆盖 |
| `fabricLoaderWithoutSha1StillPlans` | 第 7/8 项无 sha1 时仍进 plan（`sha1 = null`，下载只校验非空） |
| `planSkipsDisallowedLibrariesAndDedupesTargets` | `rules` 不允许的库不进 plan；同 target 去重 |

### 7.2 全量测试与构建（实测）

| 命令 | 结果 |
| --- | --- |
| `gradle --no-daemon :core:test :ui:compileKotlinJvm :app-desktop:compileKotlin :app-desktop:compileTestKotlin :androidApp:assembleDebug -PskipWix` | **BUILD SUCCESSFUL**，exit 0，56 tasks |
| 解析 `core/build/test-results/test/*.xml` | **17 个 suite、160 例、failures+errors=0、skipped=0**（基线 15 类 127 例，本轮 +2 类 +33 例） |

逐类：HttpDownloaderTest 13 / InstanceDeleterTest 11 / InstanceRepositoryTest 6 / InstanceScannerTest 11 / LocalInstanceTest 6 / LaunchArgumentsTest 17 / LaunchPipelineTest 12 / **FabricMetaTest 22** / GameJavaRulesTest 9 / JavaCandidatePathsTest 8 / JavaProbeParserTest 9 / JavaRuntimeManifestTest 10 / RuntimeSelectionTest 3 / OfflineUsernameTest 4 / SettingsRepositoryTest 6 / DownloadPlannerTest 6 / VersionMetaTest 7。

---

## 八、e2e 抓出来的两个真 bug

第一次跑完（`fabric-e2e-1.20.1-20261109-185703.log`）时，`LOADER_INSTALL` 的进度行打成了这样：

```
下载 [DownloadItem(url=https://maven.fabricmc.net/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar, target=D:\...], ...).size 个 Fabric 库 0%
0.20.1 库 DownloadProgress(completedFiles=0, totalFiles=8, ...).completedFiles/DownloadProgress(...).totalFiles 0%
```

根因是 Kotlin 字符串模板：`"下载 $items.size 个 Fabric 库"` 里 `$items` 是对象本身，`.size` 成了字面量；`"$p.completedFiles/$p.totalFiles"` 同理，`$p` 被展开成整个 `DownloadProgress`。改成 `${items.size}` / `${p.completedFiles}`（`FabricInstaller.kt:95` 与 `:108`）后复跑，同一段变成：

```
[19:11:29.765]   LOADER_INSTALL 下载 Fabric 库：下载 8 个 Fabric 库 0%
[19:11:29.782]   LOADER_INSTALL 下载 Fabric 库：0.20.1 库 0/8 0%
...
[19:11:34.950]   LOADER_INSTALL 下载 Fabric 库：0.20.1 库 8/8 100%
```

**这类 bug 编译期一定发现不了**（`$x.y` 在 Kotlin 里合法，只是语义不是你以为的那个），单测也覆盖不到（它只影响给用户看的文案）。只有把真实 e2e 日志读一遍才会发现。

---

## 九、还没做到的

1. **Forge / NeoForge 不在这轮**。`LoaderChoice` 已经留了位置（`OneClickInstaller.kt:65`），Forge 需要额外跑 installer jar（`--installClient` 或新版 `--install`），不是纯 HTTP 拼 url 能解决的。
2. **进度条分母少算 2.5 MB**。`FabricLibraries.plan` 用 json 里的 `size` 求和，第 7/8 项没有 `size` 就按 0 算，所以进度会先到 100% 再停几秒。修法有两条（都未验证）：下完 6 个有 size 的之后发 HEAD 探 `Content-Length`；或者干脆用「文件数」当分母而不是字节数。
3. **两个无 sha1 的库没做内容校验**。`intermediary-1.20.1.jar` 与 `fabric-loader-0.20.1.jar` 只能验「下完了、非空」。Fabric maven 提供 `<jar>.sha1` 附属文件，可以补一次 GET 拿 sha1——未实现。
4. **没有真的装一个模组验证**。本文的通过标准是「进主菜单 + 加载器初始化」（任务书允许），没有下载任何 mod jar 放进 `mods/`。所以「`mods/` 目录能被扫描、模组能被加载」这一层**未验证**。
5. **界面没做过真机点击测试**。`FabricRow` 的勾选框、loader 列表展开、按钮文案切换，只有编译通过 + 代码审查，**没有截图或人工点击记录**。无头 e2e 走的是 `OneClickInstaller.install(loader = LoaderChoice.Fabric(...))`，绕过了所有 Compose 代码。
6. **loader 列表没有缓存**。`LaunchedEffect(versionText)` 每换一次版本号就拉一次网（实测 1.2–2 秒），没有内存或磁盘缓存，离线时这一栏会显示「loader 列表拉取失败」。
7. **没有「已装过这个 loader」的判断**。`FabricInstaller.installedLoaders()` 写好了但界面没用它——重复对同一个版本点「安装 Fabric」会重下一遍（文件会跳过，但列表会再拉一次）。
8. **没有模组商店、没有光影（Iris/OptiFine）**。这些都不在本轮范围。

---

## 十、踩坑记录（本轮新增）

**Kotlin 正则字面量转义写重了。** 第一版 `PatternOwnId` 写成 `""".*-fabric-(d+.d+)"""`——`\d` 被写成 `d`，正则永远匹配不上，`FabricMetaTest` 报 `java.lang.AssertionError at FabricMetaTest.kt:167`。改成 raw string 里的单个反斜杠 `\d+\.\d+` 才对。

**`when` 加了枚举值就必须全量补分支。** `InstallStep` 新增 `LOADER_INSTALL` 后，`InstancePage.kt:810` 的 `describeStep` 报 `'when' expression must be exhaustive. Add the 'LOADER_INSTALL' branch or an 'else' branch.`。同一个 `when` 在 `DownloadsPage` 里也有，但那边有 `else`，所以只有一处报错——**加枚举值时不要只搜直接用法，要搜所有 `when`**。

**`LazyColumn` 的 `items` 重载会因 import 错误选错。** `DownloadsPage.kt` 里 `import ...ui.download.FabricLoaderVersion`（不存在）时，Kotlin 没有报「import 不存在」而是把 `items(versions, ...)` 解析成 `LazyListScope.items(count: Int)` 重载，于是报一串 `Unresolved reference 'version' on receiver of type 'Int'`。**看到「receiver of type 'Int'」这类怪错误，先查 import。**

**Compose MP 桌面端不要用 `ExposedDropdownMenu`。** 第一版 loader 下拉用了它，弹层里的点击响应不稳，改成「`OutlinedButton` 切换展开 + `Card` 里铺 `LazyColumn`」（`DownloadsPage.kt:64` 的 `FabricRow`）。

---

## 十一、复现步骤

```bash
# 1. 单测 + 全量编译
MSYS_NO_PATHCONV=1 cmd.exe /c "D:\ruanjian\kaifa\qidongqigongju\gradle-9.9.0\bin\gradle.bat \
  --no-daemon :core:test :ui:compileKotlinJvm :app-desktop:compileKotlin \
  :app-desktop:compileTestKotlin :androidApp:assembleDebug -PskipWix"

# 2. Fabric 端到端（真实联网，约 4-5 分钟，空目录）
MSYS_NO_PATHCONV=1 cmd.exe /c "D:\ruanjian\kaifa\qidongqigongju\gradle-9.9.0\bin\gradle.bat \
  --no-daemon :app-desktop:fabricE2e \
  -PfabricDir=dist/_fabric-e2e-new -Pgv=1.20.1 -PskipWix"

# 可选：只补加载器（原版已装好时），跳过原版与 Java 下载
#   -PskipOrig=true  -PfabricLoader=0.20.1
```

退出码：`0` 进主菜单；`2` 安装失败；`3` 启动失败；`4` 超时未进主菜单；`5` `skipOrig` 但缺原版 jar；`6` `skipOrig` 但本机没有 Java 17。
