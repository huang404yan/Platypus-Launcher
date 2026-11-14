# 启动页左栏化、数据包、服务端、隔离开关与安装器续跑

2026-11-14。接着 `docs/36` 的信息架构重排往下做四件事：把启动页也改成「左栏选、右栏看」，
补上 PCL 下载栏里的数据包与服务端（插件明确不做），给实例加一个版本隔离开关，
并让 Forge / NeoForge 安装器在取消或超时时真的被杀掉、下次能接着装。

## 一、启动页左栏化

### 1.1 改之前是什么样

`LaunchPage` 是一列往下滚的表单：标题 → 实例列表（`heightIn(max = 120.dp)`）→ 内存 → Java →
直连 → 启动按钮 → 状态 → 崩溃卡 → 日志。实例多的时候列表自己先滚一遍，用户想点「启动」
得先划过 Java 列表和直连输入框。日志被顶到最下面，进了游戏回头看日志还要再滚一次。

PCL 的分法（`custom/Pages/PageLaunch/PageLaunchLeft.xaml`）不是菜单列表，而是一整栏操作区：
版本选择、版本设置、正版/离线/第三方三个单选、**54 高的大号启动按钮**、以及启动中的浮层
（进度条 + 当前步骤 + 启动进度 + 下载速度 + 取消）。左栏回答「现在启动哪一个」，
右栏才是设置与日志。

### 1.2 现在怎么分

```
LaunchPage.kt:387-390
Row(Modifier.fillMaxSize(), spacedBy(14.dp)) {
    LaunchSidePanel(merged, selected, busy, running, stageText, username, ...)   // 366 行
    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(...)) { ... }
}
```

`LaunchSidePanel`（`LaunchPage.kt:815`）宽 300dp（窄窗口 248dp，断点仍是 1100dp），里面从上到下：

| 区块 | 为什么在这个位置 |
| --- | --- |
| 「实例 · N 个」标题 + 实例列表（`LazyColumn` + `weight(1f)`） | 列表吃掉剩余高度，实例再多也只是这一栏自己滚 |
| 离线用户名 | 启动前才需要考虑，放在启动按钮上方 |
| 内存 + 「存」 | 同上，改了马上能启 |
| **54 高的启动按钮**（`LaunchPage.kt:905`） | 这一页唯一的主要动作，不该和旁边的次要按钮一样大 |
| 停止 / 正在启动 | 只在跑着的时候出现，平时不占地方 |

右栏从「游戏目录」开始，接 Java、直连、崩溃报告、日志。目录行留在右栏顶部的理由写在
`LaunchPage.kt:398` 的注释里：**一处目录决定了左栏能选到哪些实例**，它属于「范围」而不是「动作」。

实例列表用 `weight(1f)` 而不是固定高度，是因为按钮必须永远在看得见的地方 —— 这正是改之前的问题。

## 二、数据包、服务端，以及不做的插件

### 2.1 数据包：`PackKind` 的第三个值

数据包和资源包在磁盘上都是 zip、都靠 `pack.mcmeta` 认，差别只在目录：

```
core/packs/PackScanner.kt:22-31
enum class PackKind(val dirName: String, val label: String, val projectType: String) {
    SHADER("shaderpacks", "光影", "shader"),
    RESOURCE_PACK("resourcepacks", "资源包", "resourcepack"),
    DATAPACK("datapacks", "数据包", "datapack"),
}
```

`readMeta` 走同一支（`PackScanner.kt:148`），`problemFor` 补一句「zip 里没有 pack.mcmeta
（游戏不认这个数据包）」（`PackScanner.kt:184`）。

**版本查询的 `loaders` 必须用 `["datapack"]`，不能用 `["minecraft"]`**（`PackInstaller.kt:40`）。
实测 Terralith 2.5.4 同时发了两条文件：`loaders=["datapack"]` 的 `Terralith_1.20_v2.5.4.zip`，
和 `loaders=["fabric","forge","neoforge","quilt"]` 的 `Terralith_1.20.x_v2.5.4.jar`。
拿 `minecraft` 去筛只会漏掉 zip 那条 —— 那是数据包本体。

另一个坑在搜索侧：`project_type:datapack` 这个 facet 会把**同名但发 jar 的模组**也带出来
（搜 terralith 会出现 terralith-restoned 等），返回项的 `project_type` 字段实测全是 `mod`。
所以界面不要靠这个字段判断类型，只当它是个宽松的筛选器用。

中文词典跟着加了 11 条（`SearchKeywords.kt` 的「数据包」段）：泰拉瑞斯/地形重做 → terralith、
地下城酒馆 → dungeons-and-taverns、连锁挖矿 → veinminer、地壳 → tectonic、虚无之地 → nullscape……
每条 slug 都用 `/search` 实测过「搜出来非空」。

### 2.2 服务端：`ServerInstaller` 与「下载 / 服务端」页

PCL 的服务端页（`PageServerInstall.xaml`）是「选版本 → 选加载器 → 选目录 → 下载并自动写 eula.txt」，
加载器有原版 / Forge / NeoForge / Fabric / Paper 五个按钮。

本项目**只做原版**，并且在页面上写清楚为什么（`ServerPage.kt:224`）：另外四家各有各的坑 ——
Forge 要跑安装器再换主类、NeoForge 要预铺库、Paper 是另一套下载源 —— 一锅端进来只会让这一版
既不可靠也说不清。

```
core/install/ServerInstaller.kt
const val EULA_HEADER: String = ...            // 29 行：写清「这是同意声明」，不是空文件
const val JAR_NAME: String = "server.jar"    // 33
const val DEFAULT_MEMORY_MB: Int = 2048         // 36
suspend fun install(targetDir, versionId, manifest, memoryMb, onProgress): ServerInstallResult
fun eulaText(): String                          // 165：eula=true + 换行
fun eulaAgreed(text: String): Boolean           // 173：逐行看，忽略大小写与空格
fun startScriptText(isWindows: Boolean, memoryMb: Int): String   // 191
```

开服脚本照 PCL 那条「下载后自动写 eula.txt」的做法：Windows 是 `start.bat`（CRLF + `chcp 65001`
+ `cd /d %~dp0` + `java -Xms…M -Xmx…M -jar server.jar nogui` + `pause`），Linux 是 `start.sh`（LF）。
`pause` 必须有：闪一下就关掉的窗口等于没给日志。

**`alreadyPresent` 这个字段是为了不骗人**（`ServerInstaller.kt:53`）：目录里已经有能用的 server.jar 时
不重复下载，但 `summary` 会明说「这个目录里已经有 server.jar 了，没重复下载」，
而不是装作刚装完。

服务端不登记实例、不装 Java、不共用 `versions/` 布局，所以没有复用 `DownloadsPage` ——
共用只会让两边都变成 `if`（`ServerPage.kt:156`）。

### 2.3 插件：不做，理由是实测出来的

Modrinth 的 `project_type:plugin` facet **基本不可用**。实测（2026-11-14，UA `platypus-launcher/dev`）：

| 查询 | total_hits | 首条命中 |
| --- | --- | --- |
| `project_type:plugin` + essentialsx | 62 | essentialsx |
| `project_type:plugin` + luckperms | 157 | luckperms |
| `project_type:plugin` + vault | 332 | vaultunlocked（不是 Vault 本体） |
| `project_type:plugin` + spark | 23 | spark-login |
| `project_type:plugin` + 空 | 20396 | — |

返回项的 `project_type` 字段全是 `mod`。插件在 Modrinth 上是**靠 `loaders` 区分的**：
`/v2/project/essentialsx/version?game_versions=["1.20.1"]` 只回 1 条，
`loaders=["bukkit","paper","spigot"]`，文件名 `EssentialsX-2.20.1.jar`；LuckPerms 同版本 16 条，
`loaders` 分 `["velocity"]`、`["bungeecord","waterfall"]`、`["bukkit","folia","paper","spigot"]` 三组。

要做对，就得按 loaders 过滤 + 落盘到服务端的 `plugins/`。但这一版**没有「服务端实例」这个概念**
（服务端只是一次下载，不登记），硬塞一个入口只会让人把插件装进客户端 `mods/`。
所以 `Navigation.kt:36-39` 把这件事写进注释，界面上不出现这一项。

## 三、版本隔离开关

### 3.1 把 `docs/22` 的结论推翻

`docs/22` 当时明确写过「隔离**不做开关**」，理由是「两条代码路径都得测，而共享 mods 这个能力
用户从没要过」。这次改了主意，触发场景很具体：**整合包把 `mods/` 平铺在游戏根目录**。
那种包导入后，实例目录里一个模组都没有，游戏读的是根目录那份 —— 隔离开着等于一个模组都看不见，
而用户没有任何手段关掉它。

### 3.2 落到哪几行

```
core/instance/Instance.kt:33
val isolated: Boolean = true,        // 默认 true = 旧行为，旧 instances.json 读出来就是这个值

core/instance/InstanceLayout.kt:42-49
fun of(instance: Instance): InstanceLayout = of(File(...), isolated = instance.isolated)
fun of(versionDir: File, isolated: Boolean = true): InstanceLayout {
    val gameDir = if (isolated) dir else root
    ...
}

core/instance/InstanceLayout.kt:81
fun supportsIsolation(versionDir: File): Boolean    // 父目录真的叫 versions 才给开关
```

`nativesDir` 仍然按版本目录算：它跟存档模组没关系，只是解压出来的临时文件。

`supportsIsolation` 的必要性：用户可能把游戏目录填成不是标准布局的地方（PojavLauncher 的目录、
自己拼的目录）。那种情况下关不关隔离都落在同一处，给一个拨了没反应的开关比不给更糟 ——
详情页会明说「这个目录不是 versions/<id> 的形状，开不开隔离都落在同一处，所以不给开关」
（`InstanceDetailPanel.kt:208`）。

### 3.3 详情页那张卡

`InstanceDetailPanel.kt:189-228` 新增「版本隔离」卡：左边两行写开启时是什么、关闭时是什么，
右边一个 `Switch`；改了没保存时下面出一句「改完要点下面的「保存」才生效，下次启动游戏才用新目录」。

`InstanceDetails.isolated`（`InstanceDetails.kt:57`）**只看目录不看字段**：

```
val isolated: Boolean get() = layout.gameDir != layout.gameRoot
```

因为非标准布局下就算字段是 true，`gameDir` 和 `gameRoot` 也是同一个目录 —— 按字段显示会撒谎。

保存走的是同一个 `InstanceEdits.fromInput`，新参数是可空的：

```
core/instance/InstanceEdits.kt:54
isolated: Boolean? = null,      // 传 null = 这次不改它
```

**可空是必须的**：安卓详情页没有渲染这个开关（手机端暂时不给这一项）。如果参数是 `Boolean`，
它会默认传 false，用户每在手机上保存一次设置，隔离就被悄悄关掉一次。

### 3.4 顺带把「游戏目录」统一了

以前界面层到处写 `File(target.directory.trim().trim('"'))` 当游戏目录，关掉隔离之后这些地方
会继续读写实例目录，和游戏实际读写的根目录不是一处。现在统一走 `InstanceLayouts.of(...).gameDir`：

| 位置 | 改法 |
| --- | --- |
| `ui/ModsPage.kt`（5 处）/ `ui/PacksPage.kt` | `InstanceLayouts.of(instance).gameDir` |
| `androidApp/.../ModsPage.kt`（7 处）/ `PacksPage.kt` | 同上 |
| `core/mods/InstanceMods.kt` | `modsDirOf(instance)` / `modsDirOf(dir, isolated)` 两个口子 |
| `core/instance/InstanceDetails.kt` | `dirs` 统计跟着 `layout.gameDir` |

`DesktopGameRuntime` 一行没动：它本来就调 `InstanceLayouts.of(request.instance)`，
`--gameDir` 从 `context.gameDir` 取，改的是 `of()` 内部。

## 四、安装器中断续跑

### 4.1 已经能续的部分（不用改）

- `HttpDownloader` 一直是断点续传：先写 `<文件>.part`，有内容就发 `Range`，206 接着写、200 从头写，
  校验通过才改名。原版文件、Fabric 库、NeoForge 预铺库、installer jar 全走它。
- `ForgeInstaller.kt:83-107` 与 `NeoForgeInstaller.kt:94-119` 本来就有幂等分支：版本 json 已在且
  profile 对得上就跳过安装，顺手补 `<versionId>.jar`。
- `isUsableJar` 让 installer jar 下载一次就能复用。

### 4.2 真正的缺口：取消不会杀掉 java

原来 Forge / NeoForge 各有一份私有的 `runInstaller`，`ProcessBuilder` 起进程后循环 `readLine()`。
三个问题：

1. 用户点「取消」，协程被取消，但那个 java 还在后台往 `libraries/` 里写文件 —— 用户以为停了，实际没停。
2. `CancellationException` 是 `Exception` 的子类，被 `catch (e: Exception)` 包成 `ForgeInstallException`，
   界面于是显示红色报错，用户以为装坏了。
3. 超时检查写在读循环里。安装器常有几十分钟不打印任何东西的时候（在下大库），
   对没输出的进程，这个检查等于不存在。

### 4.3 `core/install/InstallerProcess.kt`

```
suspend fun run(
    command: List<String>,
    workingDir: File,
    logFile: File,
    timeoutMillis: Long,
    onLine: (String) -> Unit,
): Int
```

三件必须做对的事，KDoc 里逐条写了理由：

1. **取消要真的杀进程**：`currentCoroutineContext().job.invokeOnCompletion { cause -> if (cause != null) kill(process) }`
   （`InstallerProcess.kt:66`）。`kill` 先 `process.descendants()` 再 `destroyForcibly`，最后等 5 秒。
2. **取消不能被当成失败**：`process.exitValue()` 之后先 `pump.join()`，再 `ensureActive()`
   （`InstallerProcess.kt:112`）—— 进程被我们杀掉之后 `waitFor` 也会返回 true，不在这里抛出去，
   调用方看到的就是一个莫名其妙的「安装器退出码 1」。
3. **日志必须落盘**：第一行写完整命令行、第二行写 `# pid=`，之后是安装器的输出，每行 flush。
   PID 写进去是为了排查时用户能直接说「这个 pid 卡住了」，单测也靠它确认进程真的没了。

两个容易写错的地方：

- **日志由独立协程读**（`launch(Dispatchers.IO)`），主循环用 `waitFor(100ms)` 盯进程与 deadline。
  把 deadline 检查放进读循环，对不出声的进程永不生效。
- **顺序是先 `waitFor` 到结束、再 `pump.join()`**。反过来会死等一个还没 EOF 的管道。

`timeoutMillis` 收的是毫秒而不是分钟，原因写在参数注释里：单测要能用几百毫秒验证「到点真的被杀」，
等一分钟的测试没人跑。`describeTimeout` 会把毫秒说成「N 分钟」或「N 秒」，说成「0 分钟」
会让人以为压根没设超时。

### 4.4 两侧调用点

`ForgeInstaller.kt` 与 `NeoForgeInstaller.kt` 的 `runInstaller` 都缩成「拼命令 + 转调」，
并在 `install()` 的 `catch (e: Exception)` 之前插一支：

```
} catch (e: CancellationException) {
    // 用户点了「取消」：这不是失败，别包成 ForgeInstallException 让界面报红。
    throw e
} catch (e: Exception) {
```

### 4.5 还没收干净的角落（已知，未修）

`FabricInstaller.kt:141-144` 只有 `catch (e: Exception) { if (e is FabricInstallException) throw e; ... }`，
没有 `CancellationException` 前置。取消 Fabric 安装时它会被包成 `FabricInstallException`。
优先级低：Fabric 安装只剩几个库的下载，`HttpDownloader` 内部保证取消能冒出去，
且 `OneClickRunner` 最前面已经有一支 `catch (e: CancellationException) { throw e }` 兜住。

同理 `ModpackImporter.kt:264` 在下载文件段会把取消包成 `ModpackImportException`，
由 `ModpackRunner` 的外层 catch 兜住。两处都记在这里，下次动到时一并改成显式重抛。

## 五、实测

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 全量单测 + 三端编译 | `:core:test :ui:compileKotlinJvm :app-desktop:compileKotlin :androidApp:assembleDebug` | exit=0，BUILD SUCCESSFUL in 28s / 52 tasks |
| 测试用例 | 读 `core/build/test-results/test/TEST-*.xml` | **45 个测试类 / 512 例 / failures 0 / errors 0 / skipped 0**（改造前 43 类 491 例） |
| 新增测试 | `InstallerProcessTest` 4 例、`ServerInstallerTest` 10 例 | 全过 |

`InstallerProcessTest` 四例：跑完把退出码原样返回并落日志 / 退出码非零时不抛异常而是原样返回 /
不出声的进程也会在超时后被杀掉 / 取消时进程被杀且以取消的形式抛出。

`ServerInstallerTest` 十例：eula 文件里写的是同意而不是空文件 / 读到 `eula=true` 就算已经同意 /
大小写与空格都不影响同意判定 / 没同意或者只有注释行都算没同意 / 开服脚本会先切到自己所在的目录 /
开服脚本里的内存就是给的那一档 / 内存给得太小或太大都会被夹到能用的范围 /
开服脚本一定是 jar 加 nogui / bat 用 CRLF 行尾而 sh 用 LF / 结束时会停住窗口而不是一闪而过。

**这些是静态证据**：界面点得对不对（左栏选实例是否顺手、关掉隔离后模组页能不能看见根目录的模组、
服务端 `start.bat` 双击能不能起来）仍然要人手动点。

## 六、还没做到

1. **服务端只有原版**。Forge / NeoForge / Fabric / Paper 的服务端不做，页面上已写明。
2. **没有「服务端实例」概念**：下一份服务端不能像游戏那样登记、不能一键开服、不能看日志。
3. **插件不做**：理由见 2.3，要做得先有服务端实例与 `plugins/` 目录。
4. **关闭隔离后不自动搬运文件**：根目录有模组、实例目录也有模组时，游戏只看得见根目录那份，
   实例目录里的不会丢但也不会被读到。界面上有说明，但没有「合并进根目录」的按钮。
5. **启动中的浮层没做**：PCL 在左栏里有一块「正在启动 + 进度 + 取消」，本项目仍在右栏用状态行与日志表达。