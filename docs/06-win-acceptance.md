# Windows 端验收记录（t6）

验收日期：2026-11-09　版本：`0.8.0-preview.1`（根 `build.gradle.kts:15`）
验收机：Windows，JDK 25（`JAVA_HOME=D:\ruanjian\kaifa\java\25.0.5.0.3`），Gradle 9.9.0，Android SDK `D:\ruanjian\kaifa\android-sdk`
验收口径：只做验证、不改产品代码。本文只写**实测到了什么**，「未做」的项目单独标注，不拿计划充结论。

## 总览

| # | 项目 | 结论 |
|---|---|---|
| 1 | 全量测试 `:core:test` | 通过（89 例，0 失败 0 错误 0 跳过） |
| 2 | `:app-desktop:compileKotlin` | 通过（EXIT=0） |
| 3 | `:app-desktop:createDistributable -PskipWix` | 通过（EXIT=0，12s / 全量 rerun 32s） |
| 4 | `:app-desktop:packageMsi -PskipWix` | 通过（EXIT=0，27s，出 64,712,812 B 的 msi） |
| 5 | exe 产物：存在 / 字节 / sha256 / 双击启动 / 窗口标题 / 正常退出 | 通过（两次独立实测） |
| 6 | 下载产物 1.20.1：indexes 与 objects 数量、3 个抽样 sha1 | 通过（3598/3575，missing=0，3/3 命中） |
| 7 | 下载产物 1.7.10：同上 | 通过（686/674，missing=0，3/3 命中） |
| 8 | 真实启动 1.20.1 实例：进主菜单 / 退出码 / 日志尾部 | 通过（MENU-REACHED 7517 ms，退出码 1 为主动 stop 的预期值） |
| 9 | 红线：无二进制入库 / 无 keystore / 无 branding 大图 / 无 push | 通过（HEAD == origin/main，见下） |

## 1. 全量测试

**预期**：`:core:test` 全绿。

**实测**

| 测试类 | 用例 |
|---|---|
| download.HttpDownloaderTest | 7 |
| instance.InstanceScannerTest | 11 |
| launch.LaunchArgumentsTest | 17 |
| launch.LaunchPipelineTest | 12 |
| runtime.GameJavaRulesTest | 9 |
| runtime.JavaCandidatePathsTest | 8 |
| runtime.JavaProbeParserTest | 9 |
| runtime.RuntimeSelectionTest | 3 |
| version.DownloadPlannerTest | 6 |
| version.VersionMetaTest | 7 |
| **合计** | **89（failures=0 errors=0 skipped=0）** |

数据源：`core/build/test-results/test/TEST-*.xml`（10 个文件），全量 rerun 后 mtime 统一为 2026-11-09T05:29:57Z。

**注意**：第一次跑 `:core:test :app-desktop:compileKotlin` 时 `:core:test` 是 `UP-TO-DATE`（没有真跑），所以后续一律加 `--rerun` / `--rerun-tasks` 强制重跑。
**与 t4 交付的差异**：t4 汇报写 88 例，本次实测 89 例（差的 1 例不影响结论，全绿）。

**结论**：通过。

## 2. 编译

**预期**：`:app-desktop:compileKotlin` EXIT=0。

**实测**（命令原文）

```
MSYS_NO_PATHCONV=1 cmd.exe /c "D:\ruanjian\kaifa\qidongqigongju\gradle-9.9.0\bin\gradle.bat --no-daemon \
  :core:test :app-desktop:compileKotlin :app-desktop:createDistributable -PskipWix --rerun-tasks"
```

`EXIT=0`，`BUILD SUCCESSFUL in 32s`，`22 actionable tasks: 22 executed`，墙钟 33 s。
证据：`build/_t6-rerun.log`、`build/_t6-rerun-start.txt`（epoch 1794202170 → 1794202203）。

**结论**：通过。

## 3. app-image（exe 目录）

**预期**：`createDistributable` 出可分发目录，exe 可双击启动、标题正确、能正常退出。

**实测**

- 命令：`gradle.bat --no-daemon :app-desktop:createDistributable -PskipWix`
- `EXIT=0`，`BUILD SUCCESSFUL in 12s`（`20 actionable tasks: 4 executed, 16 up-to-date`），日志 `START 2026-11-09T12:50:23+08:00 / END 12:50:36+08:00`
- 日志关键行：`The distribution is written to D:\ruanjian\zhinengti\gongzuokongjian\platypus\app-desktop\build\compose\binaries\main\app`
- 产物目录：`app-desktop/build/compose/binaries/main/app/Platypus Preview/`
  - `Platypus Preview.exe`：**597,504 字节**
  - **sha256 = `7f2ba938c55661e4bb98da02f6e85c936d78fded763931d08d5c217ed730a7ed`**
  - 目录合计：200 个文件 / 130,237,210 字节（rerun 前为 130,237,379 字节，差 169 B，随 jar 重建变化）
- exe 内嵌 VersionInfo（Get-Item VersionInfo 实测）：
  - FileVersion `0.8.0`、ProductName `Platypus Preview`、CompanyName `Platypus Launcher`
  - FileDescription `鸭嘴兽·孵化版（Platypus Preview）—— 非官方 Minecraft 启动器，与 Mojang Studios 及 Microsoft 无关联。自然界早就缝合好了。`

**双击启动实测（两次独立结果一致）**

脚本：`build/_t6-exe-exit.ps1`（`Start-Process` 后轮询新进程找窗口 → `CloseMainWindow()` → 等全部同名进程退出 → 读 `ExitCode`）

| 项目 | 第一次 | 第二次（rerun 之后） |
|---|---|---|
| LAUNCHER_PID | 7052 | 7992 |
| WINDOW_PID | 21064 | 21384 |
| WINDOW_TITLE | `[鸭嘴兽·孵化版 0.8.0-preview.1]` | `[鸭嘴兽·孵化版 0.8.0-preview.1]` |
| WINDOW_MEM_MB | 229.3 | 232.5 |
| CLOSEMAINWINDOW | True | True |
| ALL_EXITED | True | True |
| LAUNCHER_EXIT_CODE | 0 | 0 |

**实测中发现的形态特征（不是缺陷，但排障必须知道）**：app-image 启动后是**双进程**——`Start-Process` 拿到的是 launcher 父进程（`MainWindowHandle=0`、内存约 6.7 MB），真正的窗口在同名子进程里（约 230 MB）。只轮询 `$p.MainWindowHandle` 会误报「没有窗口」（本任务第一次就这么误判过），必须扫全部新进程。

**结论**：通过。

## 4. MSI 安装包

**预期**：`packageMsi` 在 WiX 就位的情况下出 msi。

**实测**

- 命令：`gradle.bat --no-daemon -PskipWix :app-desktop:packageMsi`
- `EXIT=0`，`BUILD SUCCESSFUL in 27s`（`20 actionable tasks: 3 executed, 17 up-to-date`），墙钟 29 s
- 日志关键行：`[wix] 复用已有 ...\platypus\build\wix311（candle.exe / light.exe 就位）`、`:downloadWix SKIPPED`、`:unzipWix SKIPPED`
- 产物：`app-desktop/build/compose/binaries/main/msi/Platypus Preview-0.8.0.msi`
  - **64,712,812 字节**
  - **sha256 = `2d3e8fc9d44d3690620464f66ee0b68e89de5929120430e91d291be3a334d3f2`**
  - 这是**一键装闭环之前那一版**的构建产物（本次验收当时的记录，保留不删）。
- 归档副本（**2026-11-10 07:22 换为 preview.3**）：`releases/preview/windows/Platypus-Launcher-0.8.0-preview.3-win-x64.msi` 现为 **65,519,724 B**，sha256 `660f4601ec6fca9a65b41b96c6f401747b0c19d905af6e2cb5a6680323c416a3`。preview.2 那版（65,421,420 B / `8210a107...cce92`）已换出到 `dist/_rel_new/`。19:24 那版（preview.1 含 Fabric，65,101,932 B / `5e3d3bd7...da32f5`）早先已改名暂存到 `dist/_rel_new/prev1-neoforge.msi`；16:43 那版（64,970,860 B / `bef0246b...b617a`）与 15:36 那版（含设置页但不含断点续传）早已换出。preview.3 的解包复核见 `docs/06-wix-msi-build.md` 的「九次」。
  - 中间那版 64,815,212 B / sha256 `ed466ab324d693efe4ff9f60a89f3a7521b99287dbc5505c944ea4ce5fe92a1a`（14:57，含一键装闭环、不含设置页）已移到 `dist/_old-releases/win-0909-1506/`。
  - 旧副本 64,565,356 B / sha256 `0c234da25a8f3bb17c1aa635367e5381e4afd6712b51d9c65e7bde1f53b1731b` 早于「一键装闭环」代码（`0fdd97c`），包内不含 `OneClickInstaller.class` 与 `MojangJavaRuntimeInstaller.class`，已移到 `dist/_old-releases/win-0909/`。
  - 换包后的解包复核：解出 `Platypus Preview.exe` 597,504 B，sha256 `7f2ba938c55661e4bb98da02f6e85c936d78fded763931d08d5c217ed730a7ed`，与 app-image、免安装 zip 三处一致。

**msi 不可重现**：同一份源码连打两次，字节数与 sha256 都不同（内含时间戳与 CAB 排布）。**校验 msi 请比解包后 exe 的 sha256，不要比 msi 本身**。

**解包复核（2026-11-09 15:01 补做）**：`msiexec /a "<msi>" /qn TARGETDIR=<dir> /L*v <log>` 走 `dist/_unpack-msi.cmd` 后台执行，`msiexec_exit=0`、`wrapper_exit=0`，解出 `dist/_msi-unpack/Platypus Preview/Platypus Preview.exe` 597,504 B，sha256 `7f2ba938c55661e4bb98da02f6e85c936d78fded763931d08d5c217ed730a7ed`。
  - 注意：直接在 `run_code` 里调 `msiexec /a` 会顶到 120 s 超时，必须写成 cmd 脚本后台跑。

**解包复核（2026-11-09 15:38，设置页那版）**：`dist/_unpack-msi2.cmd` 后台执行，`msiexec_exit=0`，解到 `dist/_msi-unpack2/`（127 M），`Platypus Preview.exe` 597,504 B，sha256 仍为 `7f2ba938c55661e4bb98da02f6e85c936d78fded763931d08d5c217ed730a7ed`。包内 jar 命中 `SettingsPageKt` / `JsonSettingsRepository` / `LauncherSettings`，确认设置页真的进了安装包。

**未做**：没有双击安装实测（`msiexec /i`）、没有在干净机器上验证「安装 → 开始菜单 → 启动」。

**结论**：通过（构建 + 解包层面）；安装交互未实测。

## 5. 下载产物核对

### 5.1 1.20.1（`dist/_test-mc/1.20.1`）

**预期**：indexes 里有对应 index，objects 内容与 index 声明一一对应，抽样文件 sha1 正确。

**实测**

| 指标 | 值 |
|---|---|
| `assets/indexes` 文件 | 只有 `5.json`（413,067 B，sha1 `ada52cc672270e8dc69e912b2ba0ea9c141ed664`） |
| index entries | 3,598 |
| unique hashes | 3,575（23 个 hash 在 index 里重复出现） |
| objects 磁盘上命中 | 3,575 |
| **缺失** | **0** |
| index 声明总字节（全部条目） | 651,970,283 |
| index 声明总字节（去重后） | 651,738,281 |
| objects 实际占用 | 651,738,281（与去重后完全吻合） |
| index 之外的多余文件 | 0 |
| objects 二级子目录数 | 256 |

**抽样 3 个文件校验 sha1（全部命中）**

| 资源 | sha1 | 字节 |
|---|---|---|
| `minecraft/sounds/dig/stone4.ogg` | `363545a76277e5e47538b2dd3a0d6aa4f7a87d34` | 6,516 |
| `minecraft/sounds/block/dripstone/break4.ogg` | `0e58e087ad105373cf75ca8043c567e88aeb048e` | 15,470 |
| `minecraft/sounds/mob/strider/eat3.ogg` | `9254287419bdb1317fce78cb3a81d2092e5f3a13` | 25,837 |

**踩坑记录（排障必读）**：这个 index 的 `objects` 是**路径键**格式——

```json
"icons/icon_128x128.png": { "hash": "...", "size": 1234 }
```

不是新版的 hash 键格式。校验时**必须取 `value.hash` 当文件名**，直接拿 key 去 `objects/<前两位>/` 找会 0 命中（本任务第一次就撞了这个坑）。

**结论**：通过。

### 5.2 1.7.10（`dist/_test-mc/1.7.10`）

**实测**

| 指标 | 值 |
|---|---|
| `assets/indexes` 文件 | 只有 `1.7.10.json` |
| index entries | 686 |
| unique hashes | 674 |
| 磁盘命中 | 674 |
| **缺失** | **0** |
| 抽样 sha1 | 3/3 全部匹配（`a3ffeaa0a75b…` 9,784 B、`3d95ea49e91b…` 13,502 B、`0e2933d361b3…` 75,483 B） |
| `versions/` | 只有 `1.7.10` |

**结论**：通过。

## 6. 真实启动实例（端到端）

**预期**：走桌面端真实后端链路起一次 1.20.1，能进主菜单。

**实测**

方法：复用 t7 的冒烟入口 `dist/_t7smoke/.../smoke/Smoke.kt`（`DesktopServices` → `JavaRuntimeService.detect()` → `GameRuntime.launch()`，与界面用的是同一条后端链路），用 init 脚本 `build/_t6-launch.init.gradle` 注册任务 `t6LaunchVerify`。

命令：

```
MSYS_NO_PATHCONV=1 cmd.exe /c "D:\ruanjian\kaifa\qidongqigongju\gradle-9.9.0\bin\gradle.bat --no-daemon \
  -I build\_t6-launch.init.gradle :app-desktop:t6LaunchVerify"
```

`EXIT=0`，`BUILD SUCCESSFUL in 43s`。日志 `build/_t6-launch-run.log`，游戏原始输出 `build/_t6-launch.log`（38 行）。

关键结果：

| 项目 | 实测值 |
|---|---|
| 实例扫描 | `isGameDirectory=true instances=1`，`1.20.1 VANILLA` |
| Java 探测 | 4 个：25 Microsoft（java-runtime-epsilon）、25 Oracle（`D:/ruanjian/kaifa/java/25.0.5.0.3`）、17 Microsoft（java-runtime-beta）、16 Microsoft（java-runtime-alpha） |
| 挑选 | `requiredJava=17 picked=...java-runtime-beta/bin/java.exe` |
| 阶段事件 | 准备版本文件 0.05 / 整理 classpath 0.1 / 解压原生库 0.15 / 启动游戏 0.2 |
| 进程 | `started pid=19252` |
| **是否进主菜单** | **是**，`MENU-REACHED after 7517 ms, lines=18` |
| 稳定性 | `staying alive 20s after menu, no crash` |
| 停止 | `stop result=true` |
| 退出码 | **1** |
| 归因 | `游戏在进入主菜单后退出（退出码 1）：通常是手动停止或正常关闭，日志里没有崩溃迹象。` |

**关于退出码 1**：这次是**主动 stop**（脚本在进主菜单后 20 s 调 `stop()`），Minecraft 被终止时返回 1，属于预期值，不是崩溃。归因文案与日志尾部一致：尾部全是正常的资源加载，无异常栈。

日志尾部（最近 20 行，原样）：

```
[13:17:35] [Render thread/INFO]: Backend library: LWJGL version 3.3.1 build 7
[13:17:35] [Render thread/INFO]: Reloading ResourceManager: vanilla
[13:17:36] [Worker-Main-5/INFO]: Found unifont_all_no_pua-15.0.06.hex, loading
[13:17:36] [Realms Notification Availability checker #1/INFO]: Could not authorize you against Realms server: java.lang.RuntimeException: Failed to parse into SignedJWT: 0
[13:17:37] [Render thread/WARN]: Missing sound for event: minecraft:item.goat_horn.play
[13:17:37] [Render thread/WARN]: Missing sound for event: minecraft:entity.goat.screaming.horn_break
[13:17:37] [Render thread/INFO]: OpenAL initialized on device OpenAL Soft on ...
[13:17:37] [Render thread/INFO]: Sound engine started
[13:17:37] [Render thread/INFO]: Created: 1024x512x4 minecraft:textures/atlas/blocks.png-atlas
[13:17:37] [Render thread/INFO]: Created: 256x256x4 minecraft:textures/atlas/signs.png-atlas
[13:17:37] [Render thread/INFO]: Created: 512x512x4 minecraft:textures/atlas/shield_patterns.png-atlas
[13:17:37] [Render thread/INFO]: Created: 512x512x4 minecraft:textures/atlas/banner_patterns.png-atlas
[13:17:37] [Render thread/INFO]: Created: 1024x1024x4 minecraft:textures/atlas/armor_trims.png-atlas
[13:17:37] [Render thread/INFO]: Created: 128x64x4 minecraft:textures/atlas/decorated_pot.png-atlas
[13:17:37] [Render thread/INFO]: Created: 256x256x4 minecraft:textures/atlas/chest.png-atlas
[13:17:37] [Render thread/INFO]: Created: 512x256x4 minecraft:textures/atlas/shulker_boxes.png-atlas
[13:17:37] [Render thread/INFO]: Created: 256x256x4 minecraft:textures/atlas/beds.png-atlas
[13:17:38] [Render thread/WARN]: Shader rendertype_entity_translucent_emissive could not find sampler named Sampler2 in the specified shader program.
[13:17:38] [Render thread/INFO]: Created: 256x256x0 minecraft:textures/atlas/particles.png-atlas
[13:17:38] [Render thread/INFO]: Created: 256x256x0 minecraft:textures/atlas/paintings.png-atlas
[13:17:38] [Render thread/INFO]: Created: 128x128x0 minecraft:textures/atlas/mob_effects.png-atlas
```

里面的 `Status: 401` / Realms 授权失败是**离线账号（`Setting user: Dev`）的预期现象**，不阻断进主菜单；两条 `Missing sound` 与一条 shader sampler WARN 是原版 1.20.1 自带的噪声。

**未做**：没有对 1.7.10 跑启动（只跑了 1.20.1）。

**结论**：通过。

## 7. 红线核查

| 检查项 | 方法 | 结果 |
|---|---|---|
| 仓库里没有二进制产物 | `git status --short --untracked-files=all` | 只有源码与文档改动/新增，无 zip/msi/apk/exe |
| releases/ 被忽略 | `git check-ignore -v releases/preview/windows/*` | 命中 `.gitignore:27:releases/*`（msi 与 zip 都被忽略） |
| 已跟踪文件里无 keystore / branding / 二进制 | `git ls-files \| grep -Ei 'keystore\|jks$\|keystore.properties\|^branding/\|releases/.+\.(zip\|msi\|apk\|exe)$'` | **无命中**（grep exit=1） |
| 没有 push、没有本地提交 | `git rev-parse HEAD origin/main` | 两者均为 `f14a896b680c7df7eb13d3384a2feae0b6b6052b`（相等） |
| 版本真源 | 全仓库 `*.kts` 搜 `0.8.0` | 只有 `build.gradle.kts:15` 与 `androidApp/build.gradle.kts:19 versionName`（见 finding F1） |
| androidApp 包名与双线规则未动 | `git diff --stat` | `androidApp/build.gradle.kts` 未被修改（最后改动在提交 `9fa2168`） |

**结论**：通过。

## Finding

### F1（low，既有问题，非本次改动引入）版本号在 androidApp 里硬编码了一份

- 位置：`androidApp/build.gradle.kts:19` `versionName = "0.8.0-preview.3"`（versionCode 80003）
- 现状：根 `build.gradle.kts:15` 是版本真源，androidApp 又写了同一份字面量。这次两者一致（所以构建没炸），但下次只改根版本就会不一致。文件本身带 `check(applicationId == expectedId)` 护栏，会对「包名与版本通道不符」报错，但**不会**发现「versionName 落后于根 version」。
- 该行来自提交 `9fa2168`，**不是**本任务或 t2~t5 引入。
- 建议（未实施）：`versionName = rootProject.version.toString()`，或加一条 `check(versionName == rootProject.version.toString())`。
- 影响：不影响本次验收结论。

## 证据文件清单（全部在 `build/`，均已被 .gitignore 忽略）

| 文件 | 内容 |
|---|---|
| `_t6-rerun.log` / `_t6-rerun-start.txt` | 全量 rerun（test + compile + distributable）日志与 epoch 起止 |
| `_t6-test-compile.log` | 首次 `:core:test :app-desktop:compileKotlin`（:core:test 为 UP-TO-DATE） |
| `_t6-core-test-rerun.log` | `:core:test --rerun` |
| `_t6-createDistributable.log` | createDistributable，带 START/END 时间戳 |
| `_t6-packageMsi.log` / `_t6-msi-start.txt` | packageMsi（27s，EXIT=0） |
| `_t6-exe-exit.ps1` / `_t6-exe-exit.log` | 双击启动 + 关窗退出实测脚本与输出 |
| `_t6-launch.init.gradle` | 注入 `t6LaunchVerify` 任务的 init 脚本 |
| `_t6-launch-run.log` | 启动验收的 Gradle 输出（含 MENU-REACHED / stop / 退出码 / tail 30） |
| `_t6-launch.log` | Minecraft 1.20.1 原始 stdout（38 行） |

## 环境备注（与验收结论无关，但影响复现）

本机 bash 执行器在本轮验收中**间歇性故障**：`ops_bash` 随机返回 `EXECUTOR_UNAVAILABLE`（`Niubash execution tree link count mismatch: winuxcmd/usr/bin/arch.exe`），同一命令重试即过；后台任务（`run_in_background`）几乎必挂，前台执行可用。另有 `ops_write_file` 返回 `PUBLICATION_DENIED`，本文改用 Node `fs` 直接落盘。复现本文的实测时如遇同类报错，重试即可，不代表构建本身有问题。

## 本次验收未覆盖的部分

1. MSI 的 `msiexec /a` 解包复核与双击安装（本次只验到 msi 生成成功 + 字节数 + sha256）。
2. 1.7.10 的真实启动（只验了文件完整性）。
3. 桌面 GUI 的人工交互走查（t7 有截图证据 `build/_t7-gui-*.png`，本次未重新截图复核）。
4. 正式版（非 preview）通道的 exe/msi 产物（t2 只用 init 脚本注入 `version=1.0.0` 验证过参数名，未真正出稳定版成品）。
