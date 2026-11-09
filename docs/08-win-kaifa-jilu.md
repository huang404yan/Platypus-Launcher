# Windows 启动链路开发记录（2026-11-09）

本文记录 platypus-win 团队这一次集中开发的过程与实测结论。只写跑出来的事实，没跑的单独标注。
（用户要求本轮暂停并记录；本文件是过程记录，不是交付文档。）

## 一、起点

- 起点 HEAD：`f14a896b680c7df7eb13d3384a2feae0b6b6052b`（`main` == `origin/main`）
- 起点状态：桌面端只有实例扫描页能用，启动/下载/链接/更多四页都是「还没做。」占位；`core/launch/Launch.kt` 只有接口，没有任何实现；Java 运行时只有一句粗糙的 `requiredMajor` 判断。
- 本机 `%APPDATA%\.minecraft` 里没有 `versions/libraries/assets`，扫描 0 个实例；可用的测试游戏目录是仓库内 `dist/_test-mc/1.20.1`。

## 二、任务分工（7 个任务，4 名成员）

| 任务 | 内容 | 负责人 | 状态 |
|---|---|---|---|
| t1 | Windows 基线盘点 + 打包链路打通 | builder | completed |
| t2 | 出可分发的 Windows 产物（app-image / msi） | builder | completed |
| t3 | Java 运行时探测与选择 | coder | completed |
| t4 | 启动链路（参数拼装、版本解析、起进程） | coder | completed |
| t5 | 下载页 | ui | completed |
| t7 | 端到端真启动（冒烟 + GUI 级验证） | builder → coder（改派） | completed |
| t6 | 验收：全量测试 + 构建 + 产物核对 | verifier | completed（通过） |

t7 中途因 builder 上游 429 限流中断，改派 coder 只做核对与收尾（attempt 2），未重跑整套。

## 三、实测结论

### 3.1 测试

- `:core:test` 全量 rerun：**89 例，failures=0 errors=0 skipped=0**（10 个测试类）。
- 分布：HttpDownloaderTest 7 / InstanceScannerTest 11 / LaunchArgumentsTest 17 / LaunchPipelineTest 12 / GameJavaRulesTest 9 / JavaCandidatePathsTest 8 / JavaProbeParserTest 9 / RuntimeSelectionTest 3 / DownloadPlannerTest 6 / VersionMetaTest 7。
- 注：t4 汇报里写的是 88，实测 89，以 89 为准。

### 3.2 构建与产物

- `:app-desktop:createDistributable -PskipWix` → EXIT=0，200 文件 / 130,237,379 B。
- `:app-desktop:packageMsi -PskipWix` → EXIT=0，出 msi（64,712,812 B，sha256 `2d3e8fc9d44d3690620464f66ee0b68e89de5929120430e91d291be3a334d3f2`）。
- `Platypus Preview.exe` 597,504 B，sha256 `7f2ba938c55661e4bb98da02f6e85c936d78fded763931d08d5c217ed730a7ed`——app-image、msi 解包、归档 zip 三处一致。
- **msi 不是可重现构建**：两次打包 msi 字节数与 sha256 都不同，但解包内容一致。校验一律比解出来的 exe，不比 msi 自身。
- 双击启动实测两次：窗口标题 `鸭嘴兽·孵化版 0.8.0-preview.1`，CloseMainWindow 正常，退出码 0。app-image 是双进程，窗口在子进程里（父进程 MainWindowHandle=0）。

### 3.3 Java 运行时

- 本机 `%APPDATA%\.minecraft\runtime` 只有三个：alpha 16.0.1、beta 17.0.15、epsilon 25.0.1（都是 Microsoft / amd64）。
- 1.20.1 的 `javaVersion.majorVersion = 17`，官方指定 java-runtime-gamma **本机没有**；唯一精确 17 的是 **java-runtime-beta**。
- `ensure()` 只认精确主版本，不做「高版本兼容低版本」的猜测。
- 探测方式：`java -XshowSettings:properties -version 2>&1`，取 `java.version` / `java.vendor` / `os.arch`。java.exe 在 `<runtime>/<name>/bin/` 下。

### 3.4 真启动

- 链路：InstanceScanner.scan → DesktopServices.create → javaRuntimes.detect() → GameRules.requiredMajor → findExactMajor → gameRuntime.launch()，与界面走同一后端。
- 结果：detect 到 4 个 Java → requiredJava=17 → picked java-runtime-beta → started pid → **MENU-REACHED 6.0～7.5 秒** → 停留 20 秒无崩溃 → stop。
- MC 自己的 `logs/latest.log` 实证进主菜单：`Setting user: Dev`、`Backend library: LWJGL version 3.3.1 build 7`、`Sound engine started`。
- 离线启动必然打出 `Failed to verify authentication ... InvalidCredentialsException: Status: 401` 堆栈，但**不阻断进游戏**；`LaunchDiagnosis` 用 `skipWhenReachedMenu` 处理，避免误归因为登录失败。
- 停止链路：正常路径下 stop 返回 true、**129 ms 杀净**，之后进程消失且 +2s 仍消失。曾观察到一次 GUI 自动化后 PID 残留，专项探测证明是点击时序问题，不是 GameRuntime 的 bug。

### 3.5 下载产物

- 1.20.1：entries=3598 / unique=3575（23 个重复 hash）/ 磁盘命中 3575 / missing=0 / extra=0；去重后 651,738,281 B 与实际字节数完全相等；抽样 3 个 sha1 全命中。
- 1.7.10：entries=686 / unique=674 / 命中 674 / missing=0。
- 注意 index 是「路径键」格式，须取 `value.hash` 当文件名，拿 key 会 0 命中。

### 3.6 打包链路

- compose 桌面插件在 Windows 上无条件注册 `downloadWix`；`-PskipWix` 关掉下载，但仍校验 `wixToolsetDir` 存在。
- 打通机制：根 `build.gradle.kts` 的 skipWix 分支把 WiX 二进制铺进 `<root>/build/wix311`，而 jpackage 找 WiX 的目录正是这里。
- 三条来源全部实测可用：`-PwixDir=` / 环境变量 `PLATYPUS_WIX_DIR` / 把 `wix314\bin` 前置进 PATH。
- WiX 3.14.1 已装到 `D:\ruanjian\kaifa\wix314\bin`，详见 `docs/06-wix-msi-build.md`。

## 四、离「双击就能玩」还差什么

1. 无正版登录（只有离线模式，用户名随便填）。
2. 没有「选版本 → 下载 → 补 Java → 建实例」的闭环，下载页和启动页各管一段。
3. 界面不能建实例、不能改内存分配（`memoryMb` 硬编码 2048）。
4. 装不了 Forge / Fabric（`inheritsFrom` 继承合并未做）。
5. 下载源基本单一：镜像只在主源失败后兜底（`HttpDownloader.kt:131-134`），无 Range、无断点续传。
6. 日志中文乱码：读侧本来就是 UTF-8（`DesktopGameRuntime.kt:112`），真因是写侧 MC 按 GBK 输出，26 个 U+FFFD 全落在 OpenAL 那一行且不可逆。修法实测（探针，非真实 MC）：JDK25 只认 `-Dstdout.encoding`，Java17 只认 `-Dfile.encoding`，两个都传才通吃 8/17/25——**此条未在真实 MC 上验证**。
7. msi 仍需本机铺 WiX，干净机器要先准备。

## 五、遗留项

- **文档编号冲突**：`docs/06-win-acceptance.md` 与 `docs/06-wix-msi-build.md` 同为 06；`docs/07-qidong-lianlu.md` 第 170 行的引用指向 06-wix-msi-build，现阶段不影响阅读，但编号应重排。
- **Finding F1（low，既有）**：`androidApp/build.gradle.kts:19` 的 `versionName` 硬编码了根 `build.gradle.kts:15` 的版本号。本次两者一致所以没事，下次只改根版本会静默不一致。现有 `check(applicationId == expectedId)` 只护包名与通道，查不出 versionName 落后。建议改成 `rootProject.version.toString()` 或加一条 check。
- **所有人未 commit、未 push**，工作区保持改动状态（`HEAD == origin/main == f14a896`）。
- **Android 侧三个未提交改动**（`M AndroidManifest.xml`、`M MainActivity.kt`、`?? DownloadPage.kt`）是另一条对话在做，本轮全员全程未碰。

## 六、本次改动的文件

新增（core）：`launch/LaunchArguments.kt`、`launch/LaunchDiagnosis.kt`、`launch/NativesExtractor.kt`、`launch/VersionResolver.kt`、`runtime/JavaCandidatePaths.kt`、`runtime/JavaProbeParser.kt`、`auth/OfflineProfile.kt` 及 6 个测试文件。

新增（桌面）：`DesktopServices.kt`、`runtime/DesktopGameRuntime.kt`、`runtime/DesktopJavaRuntimeService.kt`。

新增（ui）：`LaunchPage.kt`、`LauncherBackend.kt`、`DownloadsPage.kt`、`download/VanillaInstaller.kt`。

修改：`build.gradle.kts`、`app-desktop/build.gradle.kts`、`Main.kt`、`App.kt`、`HttpDownloader.kt`（建目录改 `Files.createDirectories`）、`JavaRuntime.kt`（加 `findExactMajor`）、`HttpDownloaderTest.kt`、`releases/README.md`。

删除：`core/launch/VersionMerger.kt`（与 VersionResolver 重复，已送回收站）。

## 七、证据文件

均在仓库 `build/` 下（被 gitignore，不进版本库）：`_t6-rerun.log`、`_t6-core-test-rerun.log`、`_t6-createDistributable.log`、`_t6-packageMsi.log`、`_t6-exe-exit.log`、`_t6-launch.log`、`_t6-launch-run.log`、`_t7-smoke2.log`、`_t7-launch.log`、`_stopprobe-out.log`、`_t7-gui-*.png`。

冒烟脚手架在 `dist/_t7smoke/`（同样 gitignore）。
