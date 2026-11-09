# 12 一键装闭环（选版本 → 下载 → 补 Java → 建实例 → 启动）

记录日期：2026-11-09。本文只记录**已经跑通过的事实**与**还没做到的部分**，不含计划。

> 编号说明：`docs/` 里 09/10/11 已被 Android 线（`09-android-baseline.md`、`10-android-ceshi-buzhou.md`、`11-android-s1-shiyan.md`）占用，`06-win-acceptance.md` 与 `06-wix-msi-build.md` 又有重号，为了不再撞号，本轮 Windows 闭环文档取 12。

---

## 一、这条链路原来断在哪

`docs/07-qidong-lianlu.md` 记的是「下载 → 探测 Java → 启动」，但它那一版是**三段各自能跑、彼此不接**：用户得先自己知道要下哪个版本、目录填哪，下完还得自己去启动页手选版本，缺 Java 时启动页只会红字报「本机没有 Java 17」。也就是说「能启动」的前提是人先替启动器干完了前面的活。

| 环节 | 改造前状态 |
| --- | --- |
| 补 Java | `DesktopJavaRuntimeService.ensure()` 只 `detect()` + `findExactMajor()`，找不到就 `throw` 一句「自动下载运行时尚未实现」 |
| 实例登记 | `core/instance/Instance.kt` 只有 `interface InstanceRepository { list/find/save/remove }`，**没有任何实现**；`InstanceScanner.buildInstance()` 也不传 `memoryMb`，内存恒 2048 且改不动 |
| 下载页 | 只会 `VanillaInstaller.plan` + `download`，下完什么都不建，用户拿到一堆文件但没有实例 |
| 启动页 | 只调 `detect()`，从不调 `ensure()`；没有内存输入框 |

---

## 二、改了什么（代码改动清单）

### 新增文件

| 文件 | 作用 |
| --- | --- |
| `core/src/main/kotlin/io/github/huang404yan/platypus/core/runtime/JavaRuntimeManifest.kt` | 纯 Kotlin：解析 Mojang 官方 runtime 索引与单组件 manifest，产出「要建哪些目录、要下哪些文件、java 落在哪」的 [`JavaRuntimePlan`]。只认 `downloads.raw`（见下） |
| `core/src/main/kotlin/io/github/huang404yan/platypus/core/instance/JsonInstanceRepository.kt` | `InstanceRepository` 的文件实现：单个 `<gameRoot>/platypus/instances.json`，Mutex 串行，写走「临时文件 + 改名」 |
| `app-desktop/src/main/kotlin/io/github/huang404yan/platypus/desktop/runtime/MojangJavaRuntimeInstaller.kt` | 真正干活的下载器：拉索引 → 选组件 → 拉 manifest → 建目录 → `HttpDownloader` 按 sha1 下载 → 校验 `bin/java.exe` 存在 |
| `ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/download/OneClickInstaller.kt` | 五步流水线本体：[PLANNING → DOWNLOADING → JAVA_CHECK → JAVA_INSTALL → REGISTERING → DONE]，任何一步失败都抛带 `step` 的 `InstallStepException` |
| `app-desktop/src/test/kotlin/io/github/huang404yan/platypus/desktop/E2eInstall.kt` | 开发期无头验收驱动（test 源集，不进发行包），见第三节 |
| `core/src/test/.../instance/InstanceRepositoryTest.kt`、`core/src/test/.../runtime/JavaRuntimeManifestTest.kt` | 11 个新单测 |

### 修改文件

| 文件 | 改动 |
| --- | --- |
| `core/.../core/instance/Instance.kt` | `@Serializable` 加 `registered: Boolean = false`；补 `DEFAULT/MIN/MAX_MEMORY_MB = 2048/512/32768` 与 `clampMemoryMb`；新增 `mergeInstances()`（登记优先、扫描项 `putIfAbsent`，登记在前按名字排序）与 `instanceKey()`（目录归一化后比较，目录空则退回 `"id:"+id`） |
| `core/.../core/runtime/JavaRuntime.kt` | `JavaRuntimeService` 新增 `ensureRuntime(major, component, onProgress)`，默认实现退回 `ensure`。多带一个 `component` 是因为主版本号只是投影：Mojang 清单里 `java-runtime-beta` 与 `java-runtime-gamma` **实测都是 17.0.15** |
| `app-desktop/.../runtime/DesktopJavaRuntimeService.kt` | `ensureRuntime` 现在会真的下：探测命中直接返回，否则 `MojangJavaRuntimeInstaller(gameRoot).ensure(...)`；装完 `probe()` 拿 vendor，探不动就用文件路径兜底。另加构造参数 `scanGlobalLaunchers: Boolean = true`（第 46 行） |
| `app-desktop/.../desktop/DesktopServices.kt` | `create(gameRoot)` 委托 `create(gameRoot, instanceIndex)`；装配 `JsonInstanceRepository`，索引落在 gameRoot 下（无 gameRoot 时退 `<user.home>/.platypus`） |
| `ui/.../ui/download/VanillaInstaller.kt` | `InstallPlan` 多带 `gameVersion` / `javaMajorVersion` / `javaComponent`，由版本 json 的 `javaVersion` 填，缺失时按 `GameJavaRules.requiredMajor` 兜底 |
| `ui/.../ui/DownloadsPage.kt` | 改成「一键安装」：五步清单 + 进度条 + 速度 + 已用时 + 失败定位到具体步骤 + 完成后「去启动」卡片；可取消（已下文件不丢） |
| `ui/.../ui/LaunchPage.kt` | 加内存输入框 + 「保存到实例」；启动前先 `saveMemory()` 再 `launchGame()`；「自动补 Java N」按钮走 `ensureRuntime(major, javaComponentOf(instance))` |
| `ui/.../ui/App.kt` | `Page.DOWNLOAD` 的 `onInstalled` 直接跳启动页；实例页合并「登记的」与「扫描到的」，卡片显示内存与登记状态 |
| `ui/.../ui/LauncherBackend.kt` | 接口加 `val instances: InstanceRepository` |
| `app-desktop/build.gradle.kts` | 加 `testImplementation(:ui/:core)` 与 `tasks.register<JavaExec>("e2eInstall")` |

### 两个关键取舍

**只认 `downloads.raw`，不碰 `lzma`。** Mojang 的 runtime manifest 里部分 `file` 条目**没有 `lzma` 键**（实测在解析 gamma 时踩过 `KeyError 'lzma'`），而 `raw` 是原样文件、下下来即成品，不需要解压。因此 `planRuntimeManifest` 遇到没有 `raw` 的条目直接跳过，`JavaRuntimePlan.files` 里全是可直接落盘的 `DownloadItem`。

**`component` 优先于主版本号。** 版本 json 里写着 `javaVersion.component`（如 `java-runtime-gamma`）时用它，只有当它缺失才按 `JavaRuntimeComponents.componentForMajor(major)` 推（`<=8→jre-legacy / 16→alpha / 17→gamma / 21→delta / >=25→epsilon`，9–15 这类中间版本 Mojang 没有对应运行时，返回 null 由调用方明确报错）。

---

## 三、真机实测：全新空目录从零走到主菜单

### 命令

```
cmd.exe /c D:/ruanjian/kaifa/qidongqigongju/gradle-9.9.0/bin/gradle.bat --no-daemon \
  :app-desktop:e2eInstall -PskipWix \
  -Pe2eDir=dist/_test-mc/_e2e-clean-20261109 -Pe2eVersion=1.20.1 -Pe2eTimeoutMs=600000
```

**exit status 0**，`BUILD SUCCESSFUL in 4m 2s`，进程墙钟 242,612 ms。

这是 `docs/07` 那种「改 init script 注入冒烟任务」的延续，但这次任务固化进了 `app-desktop/build.gradle.kts`（`e2eInstall`，`group = "verification"`），不再靠 `-I` 注入。

### 起点与时间线

`dist/_test-mc/_e2e-clean-20261109` 是**新建的空目录**（`mkdir` 后 readdir 0 项），报告首行即 `emptyDir=true`。

| 时刻 | 步骤 | 说明 |
| --- | --- | --- |
| 14:21:07.738 | PLANNING | 解析 1.20.1 版本元数据 |
| 14:21:10.489 | DOWNLOADING | 共 **3641** 个文件（client jar + 64 个库 + 3576 个资源对象） |
| 14:24:06.269 | JAVA_CHECK | 「游戏需要 Java 17」 |
| 14:24:06.847 | JAVA_INSTALL | 「本机没有 Java 17，开始下载（java-runtime-gamma）」 |
| 14:24:47.635 | REGISTERING | 写入实例登记 |
| 14:24:47.652 | DONE | 「可以启动了」 |

`INSTALL OK in 219971 ms; javaInstalled=true`、`downloaded=3641 failures=0`。

### 装出来的东西

```
instance id=1.20.1 name=1.20.1 gameVersion=1.20.1
directory=D:\ruanjian\zhinengti\gongzuokongjian\platypus\dist\_test-mc\_e2e-clean-20261109\versions\1.20.1 memoryMb=2048
java=D:\...\_e2e-clean-20261109\runtime\java-runtime-gamma\bin\java.exe major=17 vendor=Microsoft managed=true
REGISTERED count=1
```

磁盘体量（实测）：新目录 **4055 个文件 / 830,593,834 B**；其中 `versions` 2 文件 23 MB、`libraries` 64 文件 60.9 MB、`assets` 3576 文件 652 MB、`runtime` 403 文件 91.2 MB（只含 gamma 一个组件）。登记文件在 `<gameRoot>/platypus/instances.json`（不是 `<gameRoot>/instances.json`，与 `versions/` 平级但不会干扰扫描）。

### 启动

```
started pid=21092
MENU REACHED +6042 ms: [14:24:53] [Render thread/INFO]: Setting user: Dev
exited code=1 diagnosis=游戏在进入主菜单后退出（退出码 1）：通常是手动停止或正常关闭，日志里没有崩溃迹象。
RESULT menuReached=true launchMs=6042 exitCode=1
TOTAL ms=231198
```

退出码 1 是驱动自己 `stop()` 造成的（`docs/07` 已解释过同一现象），不是崩溃。

### 证据文件

| 文件 | 内容 |
| --- | --- |
| `dist/_e2e-logs/e2e-1.20.1-20261109-142107.log` | 本次完整报告（5162 行，含每 1% 的下载/Java 进度） |
| `dist/_e2e-logs/game-1.20.1-20261109-142107.log` | 游戏 stdout（23 行） |
| `dist/_test-mc/_e2e-clean-20261109/platypus/instances.json` | 登记结果 |

`dist/` 与 `build/` 都在 `.gitignore` 里，不进仓库。

---

## 四、「自动补 Java」这条路径确实被执行了（不是探测命中）

这台机器上 `%APPDATA%/.minecraft/runtime` 里**本来就有** `java-runtime-beta`（17.0.15），而 `DesktopJavaRuntimeService.launcherRuntimeDirs()` 默认会扫 `%APPDATA%/.minecraft`、PCL、HMCL 等目录。如果照原样跑，探测会直接命中 beta(17)，`findExactMajor(17)` 成功，**自动下载那段代码永远不会执行**——测出来的「通过」是假的。

所以给 `DesktopJavaRuntimeService` 加了构造参数 `scanGlobalLaunchers: Boolean = true`（`DesktopJavaRuntimeService.kt:46`），产品行为仍是扫（用户机器上已有一份能用的 Java 就不该再下 150 MB），**只有无头验收把它置 false**（`E2eInstall.kt:53`）。注意 `gameRoot` 本身始终无条件被扫。

两处独立证据说明走的是下载：

1. 报告里明确出现 `JAVA_INSTALL 本机没有 Java 17，开始下载（java-runtime-gamma）`，耗时 40.8 秒（14:24:06.847 → 14:24:47.635）；
2. 装出来的路径是 `<gameRoot>/runtime/java-runtime-gamma/bin/java.exe`，**不是** `%APPDATA%/.minecraft/runtime/java-runtime-beta`。

另一个旁证：更早一次（14:14，`dist/_e2e-logs/e2e-1.20.1-20261109-141421.log`）是本机 `%APPDATA%/.minecraft/runtime` 里也**没有 gamma**、只有 beta 的条件下跑的，结论一致。

---

## 五、离线 401 仍然出现在日志里，且仍然不算失败

游戏日志里这段每次离线启动都会出现，属于预期：

```
[14:24:53] [Render thread/ERROR]: Failed to verify authentication
com.mojang.authlib.exceptions.InvalidCredentialsException: Status: 401
	at com.mojang.authlib.yggdrasil.YggdrasilUserApiService.fetchProperties(YggdrasilUserApiService.java:156)
	...
Caused by: com.mojang.authlib.exceptions.MinecraftClientHttpException: Status: 401
```

下一行就是 `Setting user: Dev`，随后 `Backend library: LWJGL version 3.3.1 build 7` / `Sound engine started` / `Created: 1024x512x4 minecraft:textures/atlas/blocks.png-atlas`，照常进主菜单。`LaunchDiagnosis` 的 `skipWhenReachedMenu` 规则（见 `docs/07`）把它排除在失败原因之外，本次诊断文案正是那条「进入主菜单后退出」。

---

## 六、还没做到的（对照 PCL 自编版功能表）

本轮范围是「少了这一步就玩不起来」的那五步。下表全部是**现状事实**，不是计划：

### 会挡住「真正玩起来」的

1. **没有正版登录**。只有离线用户名（`OfflineProfile`），进不了正版验证服务器。`core/auth/Account.kt` 的 `AuthService` 仍无实现，Microsoft OAuth 设备码流程、Yggdrasil 都没有。
2. **没有 Mod 加载器安装**。Forge / NeoForge / Fabric / Quilt 只能*识别*已有实例，不能安装；`OneClickInstaller` 只走原版。
3. **下载没有断点续传，镜像只作兜底**。失败重试是整文件 `.part` 从零重下；bmclapi2 镜像只在主源失败后追加，没有「优先国内镜像」开关。

### 不影响「能不能玩」的（本轮明确不做）

正版 / 离线 / Authlib 三方登录切换、陶瓦联机、服务器安装与启动、服务端控制台、RCON、`server.properties` 表单、OP 管理、Forge/NeoForge 内存改写、主题配色、自动更新、帮助文档。

### 已知小问题

4. **实例删除没有 UI**。`JsonInstanceRepository.remove()` 有实现、有测试，但界面上没有删除入口。
5. **内存改动的持久化边界**：`LaunchPage.saveMemory()` 只在点「保存到实例」或点「启动」时写盘，改完输入框不点按钮就切页会丢。
6. **`e2eInstall` 是开发期任务**，写死在 `app-desktop/build.gradle.kts` 里，要求 `-Pe2eDir` 必填；它进不了发行包，但也不受 `check` 生命周期约束，需要手动跑。
7. **日志乱码**（`docs/07` 第六节第 6 条）与 **WiX 依赖**（第 7 条）未变。

---

## 七、测试与构建（实测）

| 命令 | 结果 |
| --- | --- |
| `gradle --no-daemon :core:test :app-desktop:compileKotlin --rerun-tasks -PskipWix` | **BUILD SUCCESSFUL in 33s**，exit 0 |
| 解析 `core/build/test-results/test/*.xml` | **12 个 suite、105 例、failures+errors=0、skipped=0**（基线 89 例，新增 16 例） |
| `gradle :app-desktop:e2eInstall -Pe2eDir=...` | exit 0，见第三节 |

新增 16 例：

- `InstanceRepositoryTest`（6）：`saveThenListRoundTrips` / `savingSameIdUpdatesInsteadOfDuplicating` / `clampsMemoryToLegalRange` / `removeDropsOnlyGivenId` / `corruptIndexReadsAsEmptyInsteadOfThrowing` / `mergesRegisteredAndScannedWithoutDuplicates`
- `JavaRuntimeManifestTest`（10）：`parsesIndexAndSkipsEmptyOrOddEntries` / `mapsPlatformKeys` / `mapsMajorToOfficialComponent` / `plansRuntimeFromRawDownloadsOnly` / `fallsBackToExecutableFlagWhenJavaPathIsUnusual` / `rejectsIndexThatIsNotAnObject` / `rejectsManifestThatIsNotAnObject` / `rejectsManifestWithoutFilesField` / `reportsEmptyPlanAsStructuralChange` / `reportsMissingJavaExecutableAfterDownload`

后 5 例专门锁「清单结构变了」这类未来风险：Mojang 若把 `files` 改名或去掉、或把条目全换成只有 `lzma`、或不再把 java 放在 `bin/` 下，测试会先红，而不是等用户点了按钮才发现。为此在 core 里把两处判断抽成纯函数 `runtimePlanProblem(component, plan)` 与 `runtimeJavaProblem(executable)`，安装器只负责调用并把文案写进 `lastFailure`——原先「清单是空的」「没找到 java.exe」这两条路径**不写 lastFailure**，等于埋点丢了。

---

## 八、界面上三条点击路径（用户视角）

下面按用户真正会点的顺序写。三条路径与 e2e 驱动走的是**同一套函数**（`OneClickInstaller.install` / `InstanceRepository.save` / `JavaRuntimeService.ensureRuntime`），e2e 只是把「点按钮」换成了直接调用，没有第二套逻辑。

### 路径 1：下载页「一键安装」→ 装完直接去启动页

1. 侧栏「下载」→ 目标目录填 `D:/mc-test`（留空会红字「先填一个目标目录，比如 D:/mc-test。」）→ 在版本列表里点一个正式版（或手填版本号）。
2. 点「一键安装」。校验顺序是：目录空 → 版本号空 → 后端未装配，三条都在点击瞬间就红字提示，不会先跑起流水线再失败。
3. 五步清单逐条点亮：`解析版本元数据` → `下载游戏文件`（带百分比 + 速度 + 已用时）→ `检查 Java 运行时` → `下载 Java 运行时`（本机已有精确主版本时这一步显示「本机已有」并跳过）→ `登记实例`。
4. 任何一步失败：该步骤前面变 ✗、下面一行红字 `卡在「<步骤名>」：<原始异常>`，并出现「重试这一步」按钮——重试会复用已下好且 sha1 通过的文件，不会从头再来。
5. 成功：出现「实例已就绪：1.20.1」卡片（游戏目录 + Java 路径），点「去启动」直接跳到启动页，实例已选中。

### 路径 2：实例页的内存修改（改在启动页写盘）

1. 侧栏「实例」看得到合并后的列表：登记的实例带「已登记」和内存数，扫描到的带「仅扫描到」。**实例卡片本身是只读的**，这是刻意的：扫描结果不能改，否则刷新一次就丢。
2. 真正改内存要切到「启动」页：选中实例 → 内存输入框填数字 → 点「保存到实例」。
3. 三种结果都在同一行提示：非数字 → 「内存要填整数，单位 MB。」；超范围 → 自动夹到 512–32768 并提示「已按允许范围（512–32768 MB）改成 N MB。」；成功 → 「已写入实例：N MB。」；写盘失败（目录只读、磁盘满等）→ 「写入实例失败：<原始异常>」。
4. 点「启动」时会**先强制保存一次内存**再启动，所以改完直接点启动不会丢。

### 路径 3：启动页「缺 Java 就自动补」

1. 选中实例后，页面显示「游戏需要 Java 17」。若本机探测结果里没有精确主版本，出现「自动补 Java 17」按钮。
2. 点它 → 状态变「准备下载 Java 17」→「正在下载 Java 17（NN%）」→ 完成后自动重新探测并选中刚下好的那个。装的位置是 `<游戏目录>/runtime/java-runtime-gamma/bin/java.exe`，不动 `%APPDATA%/.minecraft`。
3. 失败时红字前缀区分两种原因：**「探测 Java 失败：」**（枚举本机 Java 时出错）与**「补 Java 失败：」**（下载/解压/校验那一段出错），后面跟原始异常原文。两者诱因完全不同，混成一句「探测失败」会把用户引到错误方向。
4. 若在没选中 Java 的情况下点了启动（按钮通常禁用，但内存保存失败等并发情况可能走到），不再静默无反应，而是红字「还没选 Java：本机没有 Java 17，先点「自动补 Java 17」，或在列表里选一个。」

### 四类失败在界面上的落点

| 故障 | 落在哪一步 | 用户看到什么 |
| --- | --- | --- |
| 网络断 / 版本元数据拉不到 | PLANNING | 「卡在「解析版本元数据」：拉取版本元数据失败：<原文>」 |
| 下载中途断网 | DOWNLOADING | 「卡在「下载游戏文件」：下载中断：<原文>」；若是个别文件失败则是「N 个文件下载失败，第一个是 <文件名>：<原始原因>」 |
| 磁盘满 | DOWNLOADING / JAVA_INSTALL / REGISTERING 任一 | 下载器把 IOException 折成中文原因（「无法创建目录：<path>（<msg>）」或「下载失败：<msg>」），再被该步骤前缀包住 |
| Java 下载失败 | JAVA_INSTALL | 「卡在「下载 Java 运行时」：补 Java 17 失败：自动下载 Java 17 失败：<原文> 本机现有 Java 8、Java 17。」 |
| instance 写不进去 | REGISTERING（安装时）/ 启动页保存内存 | 「卡在「登记实例」：写实例登记失败：<原文>」/「写入实例失败：<原文>」 |

---

## 九、本轮范围内的自选动作（不在父任务四件事里，一并记录）

- `DesktopJavaRuntimeService` 的 `scanGlobalLaunchers` 开关：为「验收不被宿主机环境掩盖」而加，产品默认值未改变行为。
- `Docs`：本文件与 `docs/04-juece-jilu.md` 的 D12 条目。
- 实例页合并展示（`mergeInstances`）与卡片上的「已登记 / 仅扫描到」：让「登记」这件事在界面上看得见，否则用户分不清哪些实例是启动器管的。
- `JavaPhase.Failed` 增加 `duringInstall` 字段：把「探测失败」与「补 Java 失败」分开，属失败路径打磨的一部分。
- 下载页失败后的「重试这一步」按钮：原先失败只能再点一次「一键安装」，用户不知道会不会从头下。
