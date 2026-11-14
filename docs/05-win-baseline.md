# Windows 端基线盘点（2026-11-09）

本文只记录实测事实与数字，不写计划。所有命令、退出码、日志路径均为本机原样。

## 1. 本机工具链

| 项 | 实测值 | 来源 |
|---|---|---|
| `JAVA_HOME` | `D:\ruanjian\kaifa\java\25.0.5.0.3` | 环境变量 |
| `java -version` | `java version "25.0.5.0.3" 2026-10-25 LTS`（Java(TM) SE Runtime Environment，HotSpot 64-Bit Server VM） | 命令输出 |
| PATH 里的 java | 有。`which java` → `C:/Program Files/Common Files/Oracle/Java/javapath/java.exe`；同目录还有 `javac.exe`、`javaw.exe`；PATH 中另有 `D:\ruanjian\kaifa\java\25.0.5.0.3\bin` | `which` / `echo $PATH` |
| `jpackage --version` | `25.0.5.0.3` | 命令输出 |
| `$JAVA_HOME/bin` 内 jpackage | `jpackage.exe`（24,744 字节）、`jpackage.dll`、`jlink.exe`、`javaw.exe` 均在 | `ls -la` |
| Gradle | `Gradle 9.9.0`，Build time 2026-11-06 15:49:35 UTC，Kotlin 2.4.21，Launcher JVM 25.0.5.0.3，Daemon JVM `D:\ruanjian\kaifa\java\25.0.5.0.3`，OS Windows 10 10.0 amd64 | `gradle.bat --version` |
| WiX Toolset | **未安装**。PATH 中无 `candle`/`light`；`C:\Program Files (x86)\WiX Toolset*` 与 `C:\Program Files\WiX Toolset*` 均不存在 | `which` / `ls` |
| Android SDK | `local.properties` 里 `sdk.dir=D:/ruanjian/kaifa/android-sdk` | 文件内容 |

构建命令固定形式（MSYS 下 `cmd //c` 会起交互式 cmd，什么都不执行，必须用 `cmd.exe /c`）：

```
MSYS_NO_PATHCONV=1 cmd.exe /c "D:\ruanjian\kaifa\qidongqigongju\gradle-9.9.0\bin\gradle.bat --no-daemon <任务>"
```

## 2. 仓库与版本真源

- 根 `build.gradle.kts` 的 `allprojects { group = "io.github.huang404yan.platypus"; version = "0.8.0-preview.1" }` 是唯一版本真源。
- `core` 由 `generateBuildInfo` 任务生成 `BuildInfo.kt`（`const val VERSION` / `const val GROUP`）注入；`Brand.version` 读 `BuildInfo.VERSION`，`Brand.isPreview` = 版本号含 `-preview`。
- 与真源不一致处（事实，未改动）：`androidApp/build.gradle.kts` 里 `versionName = "0.8.0-preview.1"` 是硬编码字符串，`versionCode = 80001`。
- 盘点时工作区已有**非本次盘点产生的**未提交改动：`M androidApp/src/main/AndroidManifest.xml`、`M androidApp/.../preview/MainActivity.kt`、`?? androidApp/.../preview/DownloadPage.kt`。Git 头提交 `f14a896 chore: 仓库只留源码与构建必需文件`。

## 3. 测试与编译实测

### 3.1 `:core:test`

命令：

```
cd /d/ruanjian/zhinengti/gongzuokongjian/platypus && MSYS_NO_PATHCONV=1 cmd.exe /c "D:\ruanjian\kaifa\qidongqigongju\gradle-9.9.0\bin\gradle.bat --no-daemon :core:test"
```

- 日志：`build/_core-test.log`；退出码 **0**
- `BUILD SUCCESSFUL in 22s`；`5 actionable tasks: 3 executed, 2 up-to-date`
- 测试结果 XML（`core/build/test-results/test/`）四份，全部 `failures="0" errors="0"`：

| 测试类 | tests |
|---|---|
| `HttpDownloaderTest` | 5 |
| `InstanceScannerTest` | 11 |
| `DownloadPlannerTest` | 6 |
| `VersionMetaTest` | 7 |
| 合计 | **29** |

- 编译警告原文（唯一一条）：

```
w: file:///D:/ruanjian/zhinengti/gongzuokongjian/platypus/core/src/main/kotlin/io/github/huang404yan/platypus/core/download/HttpDownloader.kt:151:27 'constructor(p0: String!): URL' is deprecated. Deprecated in Java.
```

- Gradle 提示（非失败）：`Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.`

### 3.2 `:app-desktop:compileKotlin`

- 命令同上，任务换成 `:app-desktop:compileKotlin`；日志 `build/_app-desktop-compile.log`；退出码 **0**
- `BUILD SUCCESSFUL in 21s`；`13 actionable tasks: 4 executed, 9 up-to-date`
- `:ui:compileKotlinJvm` 实执行通过，`:app-desktop:compileKotlin` 该次为 `UP-TO-DATE`
- 产物存在：`app-desktop/build/classes/kotlin/main/io/github/huang404yan/platypus/desktop/MainKt.class`

## 4. 桌面运行实测

命令：

```
... gradle.bat --no-daemon :app-desktop:run -PskipWix
```

- 日志：`build/_app-desktop-run.log`
- 标准输出原文：

```
[scan] root=C:\Users\zero\AppData\Roaming\.minecraft gameDirectory=false instances=0
[scan] warning: ������ϷĿ¼��ȱ�� versions/��libraries/ �� assets/����C:\Users\zero\AppData\Roaming\.minecraft
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by org.jetbrains.skiko.LibraryLoader in an unnamed module (file:/C:/Users/zero/.gradle/caches/modules-2/files-2.1/org.jetbrains.skiko/skiko-awt/0.150.2/21a6d7f0748ab032669bf0c031b319100fce258e/skiko-awt-0.150.2.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled

[ui] initial page=INSTANCE
```

- warning 文字乱码：控制台编码非 UTF-8 导致（源码与文件本身是 UTF-8），非程序缺陷。
- 窗口存在性核对：`tasklist /V /FI "IMAGENAME eq java.exe" /FO LIST` 显示 PID 8296 的 `Window Title: 鸭嘴兽·孵化版 0.8.0-preview.1`，`Status: Running`，内存 251,312 K。同时存在本机其它 java 进程（PID 16176、14992，Window Title 均为 `N/A`）。
- 人工 `taskkill /PID 8296 /F` 结束窗口后，Gradle 报 `:app-desktop:run FAILED`、`BUILD FAILED in 1m 36s`、`Process 'command 'D:\ruanjian\kaifa\java\25.0.5.0.3\bin\java.exe'' finished with non-zero exit value 1` —— 是被 kill 的结果，不是构建失败。

## 5. 打包实测

### 5.1 `:app-desktop:createDistributable -PskipWix`（成功）

- 日志：`build/_app-desktop-dist.log`；退出码 **0**；`BUILD SUCCESSFUL in 16s`
- `> Task :downloadWix SKIPPED`、`> Task :unzipWix SKIPPED`（`-PskipWix` 生效；根脚本会建出空的 `build/wix311/`）
- 输出原文：`The distribution is written to D:\ruanjian\zhinengti\gongzuokongjian\platypus\app-desktop\build\compose\binaries\main\app`
- 产物目录结构：

```
app-desktop/build/compose/binaries/main/app/          总计 125M
└── Platypus Preview/
    ├── Platypus Preview.exe   597,504 字节（r-xr-xr-x）
    ├── app/                   依赖 jar + Platypus Preview.cfg + icudtl.dat(10,468,208)
    └── runtime/               jlink 出的运行时（bin/ conf/）
```

- `app/Platypus Preview.cfg` 摘要：`app.mainclass=io.github.huang404yan.platypus.desktop.MainKt`，classpath 含 `app-desktop-0.8.0-preview.1-8f5327324fc23e5d698610356382fc4a.jar`、`core-0.8.0-preview.1-2d4af3eff512c97b2bbbd2489fa91a8a.jar`、`ui`→`foundation-desktop-1.12.2`、`skiko-awt-0.150.2` 等。
- 直接运行该 exe：`tasklist /V` 显示两个 `Platypus Preview.exe`（PID 1584 启动器壳、PID 1148 主进程，后者 `Window Title: 鸭嘴兽·孵化版 0.8.0-preview.1`），窗口正常弹出；控制台输出与 4 节相同。随后 `taskkill /PID 1584 /F` + `taskkill /PID 1148 /F` 结束，复核 `tasklist` 已无残留（含 java.exe）。

### 5.2 `:app-desktop:packageMsi`（失败，任务不存在）

- 日志：`build/_package-msi.log`；退出码 **1**；`BUILD FAILED in 10s`
- 失败原文：

```
* What went wrong:
Task not found
  Cannot locate tasks that match ':app-desktop:packageMsi' as task 'packageMsi' not found in project ':app-desktop'. Some candidates are: 'package', 'packageDeb'.
```

### 5.3 `:app-desktop:tasks --all -PskipWix` 实际存在的 jpackage 相关任务

```
checkRuntime
createDistributable
createReleaseDistributable
createRuntimeImage
package
packageAppImage
packageDeb
packageDistributionForCurrentOS
packageReleaseAppImage
packageReleaseDeb
packageReleaseDistributionForCurrentOS
packageReleaseUberJarForCurrentOS
packageUberJarForCurrentOS
run
runDistributable
runRelease
runReleaseDistributable
suggestRuntimeModules
```

**没有任何 `packageMsi` / `packageExe`**。根因在 `app-desktop/build.gradle.kts`：`nativeDistributions { targetFormats(TargetFormat.Deb, TargetFormat.AppImage) }` 只声明了 Linux 两种格式，Windows 的 `Msi`/`Exe` 未在列表里，因此 Compose 桌面插件不注册对应当前 OS 的任务（`windows { ... }` 配置块虽然写了 `iconFile`/`upgradeUuid`/`shortcut`，但没有对应 targetFormat 就不会被用上）。
另有环境侧阻塞：本机未安装 WiX Toolset，即便加上 `TargetFormat.Msi`，jpackage 也需要 WiX 3.x 的 `candle.exe`/`light.exe` 才能出 msi。

## 6. 源码清点（`wc -l`）

| 文件 | 行数 |
|---|---|
| `core/src/main/kotlin/.../core/Brand.kt` | 41 |
| `core/.../core/auth/Account.kt` | 35 |
| `core/.../core/download/Downloader.kt` | 35 |
| `core/.../core/download/HttpDownloader.kt` | 237 |
| `core/.../core/instance/Instance.kt` | 27 |
| `core/.../core/instance/InstanceScanner.kt` | 120 |
| `core/.../core/launch/Launch.kt` | 35 |
| `core/.../core/runtime/JavaRuntime.kt` | 31 |
| `core/.../core/version/DownloadPlanner.kt` | 130 |
| `core/.../core/version/VersionMeta.kt` | 263 |
| `core/src/test/.../HttpDownloaderTest.kt` | 146 |
| `core/src/test/.../InstanceScannerTest.kt` | 128 |
| `core/src/test/.../DownloadPlannerTest.kt` | 112 |
| `core/src/test/.../VersionMetaTest.kt` | 137 |
| `ui/src/jvmMain/kotlin/.../ui/App.kt` | 266 |
| `ui/src/jvmMain/kotlin/.../ui/About.kt` | 87 |
| `app-desktop/src/main/kotlin/.../desktop/Main.kt` | 46 |
| `androidApp/src/main/kotlin/.../preview/MainActivity.kt` | 423（工作区已改，非本次盘点） |
| `androidApp/src/main/kotlin/.../preview/DownloadPage.kt` | 418（未跟踪，非本次盘点） |
| 合计 | 2,717 |

`ui` 只有 `jvmMain` 源集，没有 `commonMain` 的 `.kt`；KMP 只声明了 jvm 一个 target。

## 7. 三张清单

### 7.1 已实现（有代码、有测试或可实测运行）

- `Brand`：品牌名/口号/主页/版本/预览判定/中英免责声明。
- `BuildInfo`：由 Gradle 任务生成，版本单一来源。
- `HttpDownloader`：并行下载（默认 16）、`.part` 落盘后改名、sha1 强校验、镜像回退、失败清单、进度节流 80ms、缓冲区 64KB —— 29 个测试里 5 个覆盖它。
- `Downloader` 数据模型：`DownloadItem`/`DownloadProgress`/`DownloadFailure`/`DownloadReport`。
- `VersionMeta`：version_manifest 与 version json 解析、`inheritsFrom`、库 rules/os 过滤、natives 分类、`RuleEvaluator`、`LibraryName.parse`、`OsFamily.current()`、`requiredJavaMajor`。
- `DownloadPlanner`：client.jar / assetIndex / libraries / assets 的落盘路径与 bmclapi2 镜像前缀替换，按 sha1 前两位分桶。
- `InstanceScanner`：扫 `versions/<id>/<id>.json`，识别 vanilla/forge/neoforge/fabric/quilt/optifine，PCL 风格版本目录，`isGameDirectory` 判定，warning 收集。
- `GameJavaRules.requiredMajor`：按游戏版本推 Java 主版本（>20 → 21；20.5+ → 21；≥17 → 17；否则 8）。**2026-11-14 扩充**：26.1 起的年份式版本号（26.1 / 26.3 / 26.4-snapshot-8，没有 `1.` 前缀）→ 25。依据是官方 version json 的 `javaVersion`：26.1 / 26.2 / 26.3 / 26.1.1 / 26.1.2 与 26.4-snapshot-1…8 全是 `{component: java-runtime-epsilon, majorVersion: 25}`，1.21.11 与 24w14a 仍是 `java-runtime-delta / 21`。
  **周号快照同日改成按年份分段**（原来是「一律 21」）：把 version_manifest_v2 里 744 个快照的 `javaVersion.majorVersion` 全拉下来统计，真实跃迁点只有五个 —— `13w16a` → 8、`21w19a` → 16、`22w03a` → 17、`24w14a` → 21、`26.1-snapshot-1` → 25。按这套规则本地复算 744 个快照，只有 1 个对不上（`1.18-pre1` 官方 16、我们 17，因为它走的是 `1.x` 分支而不是周号分支）。例外还有 `24w14potato`（愚人节快照，官方 17 而同周 `24w14a` 是 21），已单独判。原始数据落在 `dist/_snapjava_all.txt`。
- UI（仅 JVM）：`App` 骨架 + 侧栏 6 个入口 + `InstancePage`（目录输入框、扫描按钮、实例列表、loader 徽标）+ `AboutPage`（版本/通道/协议/仓库/完整免责声明）。
- 桌面入口 `MainKt`：参数 / `PLATYPUS_GAME_DIR` / `%APPDATA%\.minecraft` 三级取值，启动即扫描并打印，窗口标题按 `isPreview` 切换，窗口图标从资源加载（失败不阻断）。
- 打包链路：`createDistributable -PskipWix` → 125M app-image + 可双击运行的 `Platypus Preview.exe`（实测出窗口）。

### 7.2 只有接口 / 数据模型，没有任何实现

| 文件 | 只有声明的东西 | 仓库内实现类数量 |
|---|---|---|
| `core/.../runtime/JavaRuntime.kt` | `interface JavaRuntimeService { suspend fun detect(): List<JavaRuntime>; suspend fun ensure(major, onProgress): JavaRuntime }` + `data class JavaRuntime(path, majorVersion, vendor?, arch?, managed=false)` | 0 |
| `core/.../launch/Launch.kt` | `interface GameRuntime { suspend fun launch(request, onEvent): GameHandle }`、`GameHandle`、`LaunchRequest`、`LaunchEvent`（Stage/Started/LogLine/Exited） | 0 |
| `core/.../auth/Account.kt` | `interface AuthService { login / refresh }` + `Account.Offline/Microsoft/Yggdrasil` + `Session` | 0 |
| `core/.../instance/Instance.kt` | `interface InstanceRepository { list/find/save/remove }` + `data class Instance` + `enum LoaderKind` | 0 |

即：`detect()` 从未被调用过，`GameRuntime` 从未被实例化过；`app-desktop` 与 `androidApp` 都没有引用这四个接口。

### 7.3 完全缺失

- **Java 运行时探测**：无 `JavaRuntimeService` 实现，无扫描注册表 / `Program Files\Java` / `JAVA_HOME` / launcher 自带 runtime 的代码，无版本探测（`java -version` 解析），无 `ensure()` 的下载安装。UI 里没有任何 Java 选择入口。
- **启动实例**：无命令行拼装（classpath/natives/主类/游戏参数/离线会话占位）、无进程启动与日志采集、无 `GameHandle` 实现。`InstancePage` 的实例卡片没有「启动」按钮。
- **桌面下载页**：`App.kt` 里 `Page.DOWNLOAD`/`LAUNCH`/`LINK`/`MORE` 四个页面都落到 `PlaceholderPage`，显示「还没做。」。仓库里唯一的下载页是 `androidApp/.../DownloadPage.kt`（418 行，未跟踪），桌面端没有对应实现。
- **Windows 安装包**：`targetFormats` 只有 `Deb`/`AppImage`，`:app-desktop:tasks` 里无 `packageMsi`/`packageExe`；本机无 WiX。当前只能出 app-image 目录，出不了 `.exe`/`.msi` 安装包。
- **实例仓库持久化**：无 `InstanceRepository` 实现，扫描结果不落盘，重启即丢。
- **版本/实例下载流程编排**：`VersionMeta` + `DownloadPlanner` + `HttpDownloader` 三块都在，但没有把它们串起来的「下载某个版本 → 装成实例」的服务。
- **实时日志与错误诊断界面**：`LaunchEvent.Exited(code, diagnosis?)` 有字段，无 `diagnosis` 生成逻辑，也无展示。
- **认证**：无微软登录（device code / OAuth）、无 Yggdrasil 外置登录实现。

## 8. 本机 `.minecraft` 现状

`C:\Users\zero\AppData\Roaming\.minecraft` 只有两项：

```
cache/    runtime/
```

无 `versions/`、`libraries/`、`assets/`。因此启动即打印 `gameDirectory=false instances=0`，实例页为空列表。窗口能开，但界面上没有任何实例可看。

## 9. 结论：当前能不能在本机跑起桌面窗口

**能，且已实测到窗口标题。**

- `gradle :app-desktop:run -PskipWix` → 窗口弹出，标题 `鸭嘴兽·孵化版 0.8.0-preview.1`，默认页 `INSTANCE`。
- `gradle :app-desktop:createDistributable -PskipWix` → 产出 125M app-image，双击 `Platypus Preview.exe` 同样出窗口。
- `:core:test` 29 个测试全绿，`:app-desktop:compileKotlin` 通过。以上三步退出码均为 0。

**卡在哪一步（按阻塞顺序）：**

1. **界面里没有可启动的实例** —— `%APPDATA%\.minecraft` 不是完整游戏目录（无 `versions/`/`libraries/`/`assets/`），扫描结果为 0；即使用户指定目录，也没有「启动」入口（`GameRuntime` 零实现）。
2. **无法探测 Java** —— `JavaRuntimeService` 零实现，`JavaRuntime` 只是个数据类，没有任何代码去发现本机 JDK。
3. **下载页不存在** —— 桌面端 `Page.DOWNLOAD` 是「还没做。」占位，无法通过界面获得游戏文件。
4. **出不了 Windows 安装包** —— `targetFormats(Deb, AppImage)` 不含 `Msi`/`Exe`，`packageMsi` 任务不存在；本机也未装 WiX，需同时补 targetFormat 与 WiX 两件事才能出 `.exe`。

换言之：**壳能跑（窗口 + 实例扫描 + 关于页），肉没有（Java、启动、下载、安装包）。**
