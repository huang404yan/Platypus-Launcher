# 启动浮层、服务端加载器与服务端登记

2026-11-14。接着 `docs/37` 往下做三件事：给启动页补一个照 PCL 做的启动中浮层，
把服务端从「只有原版」扩到五种加载器，再把装过的服务端记下来、下次还能在列表里找到。
另外补两处取消语义、给 `ui` 模块建了测试源集。

## 一、启动中浮层

### 1.1 为什么要有

改之前点了「启动」，界面上只有一行 `stageText` 在变，其余一切照旧。用户不知道：还在解压原生库？
在拉资源？还是已经卡死了？五分钟没有动静时唯一能做的就是关窗口 —— 而那会把已经起来的
java 进程留在后台。

PCL 的做法（`custom/Pages/PageLaunch/PageLaunchLeft.xaml:76-158`）是一整块浮层：
`Grid Name="PanLaunching"` 初始 `Visibility=Collapsed`、`ScaleX/Y=0.8`，显示时 150 ms 淡入 +
500 ms `AniEaseOutBack` 缩放到 1。里面有五行：`MyLoading` 转圈、标题「正在启动游戏」+ 版本名、
一条按比例填色的进度条（两列 Grid 的列宽比例，不是 ProgressBar）、四行两列的「当前步骤 /
登录方式 / 启动进度 / 下载速度」，底部一个 35 高的「取消」。

### 1.2 现在的样子

`LaunchPage.kt:840` 的 `LaunchOverlay`：全屏半透明遮罩（`Color.Black.copy(alpha = 0.45f)`，
底下的界面可见但点不动），中间 `Surface` 宽 320dp，内容从上到下是标题、实例名、
`LinearProgressIndicator`、「当前步骤」「启动进度」两行、`OutlinedButton("取消启动")`。

浮层只在 `phase is LaunchPhase.Starting` 时出现 —— `Running` 之后游戏窗口已经起来了，
再盖一层只是碍事。

### 1.3 进度从哪来

`LaunchEvent.Stage(name, fraction)` 里本来就有 `fraction`，以前只用了名字。现在两个都收：

```kotlin
// LaunchPage.kt:281 / 事件处理段
stageFraction = 0f
is LaunchEvent.Stage -> {
    stageText = event.name
    if (event.fraction > stageFraction) stageFraction = event.fraction
}
```

**只准前进不准后退**是刻意的：解压原生库那一段的 fraction 会从 0.15 掉回 0.1，
直接赋值会让进度条往回跳，看着像卡住重来。

### 1.4 取消为什么不砍协程

这一处与直觉相反，值得写下来。`DesktopGameRuntime.launch` 在 `withContext` 里同步起进程，
如果 `job.cancel()` 打断它，`withContext` 会抛 `CancellationException`，而那时 java 进程
可能已经起来了 —— 句柄再也回不来，用户得到的是一个关不掉的后台进程。

所以取消做的是「先记意愿，等句柄回来再停」：

```kotlin
// LaunchPage.kt:132 / 333-338
var launchCancelRequested by remember { mutableStateOf(false) }
...
if (launchCancelRequested) {
    runCatching { started.stop() }
    handle = null
    launchCancelRequested = false
    stageText = ""
    stageFraction = 0f
}
```

点了「取消启动」之后 `stageText` 会变成「正在取消」，让用户知道点到了。

## 二、服务端加载器

### 2.1 五种各有各的路子

| 加载器 | 从哪拿 | 要不要 Java | 要不要跑安装器 |
| --- | --- | --- | --- |
| 原版 | Mojang `server.jar`（走 BMCLAPI 兜底） | 不用 | 不用 |
| Paper | `fill.papermc.io/v3` 的构建列表 | 不用 | 不用 |
| Fabric | `meta.fabricmc.net` 现成的 server jar | 不用 | 不用 |
| Forge | Maven 的 installer jar | **要** | **要**（几分钟） |
| NeoForge | Maven 的 installer jar | **要** | **要**（几分钟） |

差异全收在 `core/install/ServerInstaller.kt` 里，界面只挑参数。

### 2.2 三个踩过的接口事实

**Paper 的老 API 已经下线。** `api.papermc.io/v2/...` 现在返回 **410 Gone**，
`api.papermc.io/v3/projects/paper` 返回 **403 Endpoint Unsupported**。能用的是
`https://fill.papermc.io/v3/projects/paper/versions/<gv>/builds`，下载落在
`https://fill-data.papermc.io/v1/objects/<sha256>/<file>`。

**Paper 只给 sha256，没有 sha1。** 而 `HttpDownloader` 只认 sha1（`isUpToDate` 里 sha1 非空就强校验，
否则只比 size）。所以 Paper 那条走「sha1 传 null + size 兜底」，下完自己算 sha256 比对，
对不上就删掉重下并明说原因 —— 不然用户会拿到一个「下载成功但用不了」的 jar。

**Fabric 的服务端 jar 地址必须带 installer 版本。**
`/v2/versions/loader/<gv>/<loader>/<installer>/server/jar` 三段一个都不能少：
`1.20.1/0.15.11/1.1.2` 返回 200，不带第三段直接 **404**。installer 版本从
`/v2/versions/installer` 取 `stable: true` 的第一项（实测是 1.1.2）。

### 2.3 Forge / NeoForge 的 Java 为什么让用户挑

安装器是子进程：`java -jar installer.jar --installServer <dir>`。版本不对的 java
（拿 Java 21 去跑给 Java 8 写的 1.12 安装器）会直接 `UnsupportedClassVersionError`，
而那个报错埋在 installer 日志里，界面上只看得到「安装器退出码 1」。

`ServerPage.kt:562` 的 `JavaPickerRow` 因此列出来让用户选，默认挑主版本 ≥17 里最大的那个
（1.17 之后的服务端安装器要 16+），本机一个都没有时明确告诉用户去启动页点「自动补 Java」。

### 2.4 装完还写什么

- `eula.txt`：已经写着 `eula=true` 就不动；否则写成同意，并在卡片上明说「等于替你同意了
  Minecraft 最终用户许可协议，不同意就改成 false」。
- 开服脚本：原版 / Paper / Fabric 写 `start.bat`（Windows，CRLF + `chcp 65001` + `cd /d %~dp0`
  + `pause`）或 `start.sh`（LF）。**Forge / NeoForge 不写** —— 安装器自己生成了 `run.bat`，
  覆盖它等于把人家装好的东西弄坏。它们的脚本会读 `user_jvm_args.txt`，所以卡片上说的是
  「改内存改 `user_jvm_args.txt`，别改 `run.bat` 本身」。

## 三、服务端登记

### 3.1 为什么不复用 `Instance`

游戏实例与 Service 端是两种东西：实例有 `versions/<id>/` 布局、进启动页、走 Java 探测；
服务端是一堆散装文件 + 一个 `run.bat`。硬塞进同一张表，结果是「实例」列表里混进一堆
点「启动」会报错的条目。

所以 `core/install/ServerRepository.kt` 单开一套：`ServerEntry`（id / name / kind / gameVersion /
loaderVersion / directory / memoryMb / javaPath / createdAt）+ `JsonServerRepository`，
落在 `<gameRoot>/platypus/servers.json`，与 `instances.json` 平级。

写法与 `JsonInstanceRepository` 一致（mutex、prettyPrint、坏文件读成空、temp + renameTo），
只多一条规则：**同一个目录只留一条**。用户重装同一个服务端会拿到新 id，但目录没变，
列表里不该长出第二条。去重比的是归一化路径（去引号、反斜杠转正斜杠、转小写）。

### 3.2 装完自动登记

`ServerPage.kt:261` 的 `LaunchedEffect(state.phase)` 盯着 `Finished`：写上一条、刷新列表。
用**目录**而不是 id 去重，因为页面切走再回来时那个 `Finished` 还在，重复登记只会把同一条
覆盖成一样的内容。

内存写的是 `lastMemoryMb`（点「开始下载」那一刻的值），不是登记时再读输入框 ——
用户装完之后完全可能已经改了输入框，而已经装好的那个服务端用的是当时那个值。

### 3.3 为什么没有「启动服务端」

`ServerPage.kt:789` 的 `ServerRegistryCard` 只有「打开目录」与「从列表里去掉」。
开服是长驻进程：要接控制台输入输出（`/op`、`/stop` 全靠 stdin）、要能实时看日志、
要处理「端口被占」「EULA 没同意」这些只在运行期才出现的情况。给一个按钮让人以为点完就开好了，
比不给按钮更糟。

「从列表里去掉」只是不显示，不动磁盘 —— 这句话就印在按钮下面，否则用户不敢点。

## 四、两处残留的取消语义

`FabricInstaller.kt` 与 `ModpackImporter.kt` 各有一个 `catch (e: Exception)` 会把
`CancellationException` 包成自己的安装异常，用户在安装途中点取消会看到红色「失败」。
两个文件都在 `catch (e: Exception)` 之前加了一条：

```kotlin
} catch (e: CancellationException) {
    // 取消不是失败，交给上层按取消处理。
    throw e
} catch (e: Exception) {
```

## 五、ui 模块终于有单测了

`ui/build.gradle.kts` 加了 `jvmTest` 源集（只依赖 `kotlin("test")`，不需要 compose 测试框架）。
先补两个能脱离界面测的东西：

- `FormatHelpersTest`（8 例）：`formatBytes` / `formatSpeed` / `formatDuration` 的换档边界，
  以及 `SpeedMeter` 第一次采样返回 -1、两次太近沿用旧值。
- `NavigationTest`（7 例）：二级项的顺序、id 不重复、默认项是第一个、整合包页与服务端页
  故意没有刷新按钮。

这两组盯的都是「改一条常量就悄悄坏掉、但界面看起来正常」的地方。

## 六、实测

```
:core:test :ui:jvmTest :app-desktop:compileKotlin :androidApp:assembleDebug
EXIT=0  BUILD SUCCESSFUL in 38s  57 actionable tasks
测试 XML：classes 49  tests 551  fail 0  err 0  skip 0
```

改之前是 45 类 512 例；这一轮新增 `ServerKindsTest`（13）、`ServerRepositoryTest`（11）、
`FormatHelpersTest`（8）、`NavigationTest`（7）。

## 七、还没做到

- 服务端不能从界面启动，也不能看控制台输出。
- Paper 的构建列表只取 `server:default`，`server:mojang`（带 Mojang 映射的那份）没用上。
- 插件仍然不做（理由见 `docs/37`）。
- 安卓端没有跟着补这三件事。
