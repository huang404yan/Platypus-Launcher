# 13 启动器设置持久化（离线用户名 / 默认游戏目录 / 默认内存）

记录日期：2026-11-09。本文只记录**已经跑通过的事实**与**还没做到的部分**，不含计划。

> 编号说明：09/10/11 归 Android 线，12 是 Windows 一键装闭环，06 有重号。本轮设置层取 13。

---

## 一、这块原来断在哪

界面**早就能改离线用户名**，只是改完关掉窗口就丢：

| 位置 | 改造前 |
| --- | --- |
| `ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/LaunchPage.kt:96` | `var username by remember { mutableStateOf("Dev") }` —— 每次打开都是 Dev |
| `ui/.../ui/App.kt` 的 `Page.MORE("更多")` | 只有 `PlaceholderPage`，一个字「还没做。」 |
| 默认游戏目录 | 每个页面各自 `remember { mutableStateOf(initialGameRoot ?: defaultGameRoot()) }`，用户在启动页填的目录，下载页完全不知道 |
| 新实例内存 | `OneClickInstaller.install` 的 `memoryMb` 参数恒用默认 2048，装出来的实例全是 2048 |
| 设置存哪 | **全项目没有任何通用设置持久化机制**。`grep Settings/Preference/config.json/settings.json` 在 `core/src/main`、`app-desktop/src/main`、`ui/src/jvmMain` 只命中两个无关文件；唯一的落盘实现是 `JsonInstanceRepository` |

三件事都直接卡在「双击能不能玩」上：名字改不了（离线身份永远是 Dev）、目录每次重填、装完的实例内存不对还得再去改一遍。

---

## 二、改了什么

### 新增文件

| 文件 | 行数 | 作用 |
| --- | --- | --- |
| `core/src/main/kotlin/io/github/huang404yan/platypus/core/settings/LauncherSettings.kt` | 75 | `@Serializable data class LauncherSettings(offlineUsername, defaultGameRoot, defaultMemoryMb, updatedAt)`；`companion fun normalize()`；顶层 `normalizeOfflineUsername()` 与 `usernameProblem()`；`interface SettingsRepository { load(); save() }` |
| `core/src/main/kotlin/io/github/huang404yan/platypus/core/settings/JsonSettingsRepository.kt` | 63 | 文件实现：单个 `<gameRoot>/platypus/settings.json`，Mutex 串行，写走「临时文件 + 改名（Windows 上目标已存在时 renameTo 会失败，退覆盖写）」 |
| `ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/SettingsPage.kt` | 265 | 设置页：三张卡片 + 保存 + 填回默认值 + 显示设置文件绝对路径与上次保存时间 |
| `core/src/test/.../core/settings/SettingsRepositoryTest.kt` | — | 6 例 |
| `core/src/test/.../core/settings/OfflineUsernameTest.kt` | — | 4 例 |
| `app-desktop/src/test/.../desktop/SettingsSmoke.kt` | — | 无头冒烟驱动，12 项断言，见第三节 |

### 修改文件

| 文件 | 改动 |
| --- | --- |
| `ui/.../ui/LauncherBackend.kt` | 加 `val settings: SettingsRepository? get() = null`。带默认实现是刻意的：预览与单测里的假后端不必跟着改，生产装配一定提供 |
| `app-desktop/.../desktop/DesktopServices.kt` | 加 `override val settings`，`create()` 里装配 `JsonSettingsRepository(settingsFile(gameRoot))`；新增 `settingsFile()` 与 `fallbackHome()` |
| `ui/.../ui/App.kt` | `Page.MORE("更多")` → `Page.SETTINGS("设置")`，接 `SettingsPage(backend)` |
| `ui/.../ui/LaunchPage.kt` | 首帧读设置：用户名、默认目录（仅在当前为空时填）、默认内存；**改过名字才在启动时回写**，没改不重复写盘；输入框加提示「点「启动」会记住这个名字」 |
| `ui/.../ui/DownloadsPage.kt` | 首帧读设置的默认目录与默认内存；`OneClickInstaller.install` 传入 `memoryMb = defaultMemoryMb`；完成文案带上「内存 N MB（可在启动页改）」 |
| `app-desktop/build.gradle.kts` | 加 `tasks.register<JavaExec>("settingsSmoke")` |

### 三个关键取舍

**设置文件不跟着「用户当前选中的目录」漂移。** 有游戏目录时在 `<gameRoot>/platypus/settings.json`，没有时在 `<user.home>/.platypus/settings.json`。设置本身记的就是那个目录，若文件也跟着目录走，换一次目录就看不到上次的选择。

**只在校验通过后落盘，且落盘的是规范化后的值。** `save()` 内部先 `LauncherSettings.normalize()` 再 `copy(updatedAt = now)` 再写：空名字回 `Dev`、路径 `trim().trim('"')`、内存夹到 512–32768。界面回填的是**存进去的那个值**，不是用户敲进去的那个。

**读失败一律给默认值，不抛。** 文件不存在、空白、JSON 坏了、未知字段，全部退回 `LauncherSettings()`。理由和 `JsonInstanceRepository` 一致：配置文件坏掉不该让启动器打不开。

### 用户名规则

3–16 个字符，只允许英文字母、数字、下划线；校验前先 `trim()`，所以 `"Dev "` 合法。空名字不报错，直接回落到 `Dev`——离线 UUID 由名字算出来（`OfflineProfile.uuidFor`），换名字等于换一个玩家身份，这条说明写进了设置页的提示文案。

---

## 三、实测

### 单测

```
MSYS_NO_PATHCONV=1 cmd.exe /c "gradle.bat --no-daemon :core:test :app-desktop:compileTestKotlin :androidApp:assembleDebug --rerun-tasks -PskipWix"
```

`BUILD SUCCESSFUL in 41s`，exit 0。解析 `core/build/test-results/test/*.xml`：

| 测试类 | 例数 |
| --- | --- |
| HttpDownloaderTest | 7 |
| InstanceRepositoryTest | 6 |
| InstanceScannerTest | 11 |
| LaunchArgumentsTest | 17 |
| LaunchPipelineTest | 12 |
| GameJavaRulesTest | 9 |
| JavaCandidatePathsTest | 8 |
| JavaProbeParserTest | 9 |
| JavaRuntimeManifestTest | 10 |
| RuntimeSelectionTest | 3 |
| **OfflineUsernameTest** | **4** |
| **SettingsRepositoryTest** | **6** |
| DownloadPlannerTest | 6 |
| VersionMetaTest | 7 |
| **合计** | **115（14 个测试类，0 失败 0 错误 0 跳过）** |

比上一轮 105 例多 10 例，全部来自新增的两个设置测试类。

### 无头冒烟（走真实装配点）

```
MSYS_NO_PATHCONV=1 cmd.exe /c "gradle.bat --no-daemon :app-desktop:settingsSmoke -PsmokeDir=dist/_settings-smoke -PskipWix"
```

`BUILD SUCCESSFUL in 24s`，exit 0，12 项全部 PASS：

```
PASS fresh defaults username :: Dev
PASS fresh defaults memory :: 2048
PASS fresh load does not create file :: ...\dist\_settings-smoke\platypus\settings.json
PASS save writes <gameRoot>/platypus/settings.json :: ...
PASS repository path matches :: ...
PASS reopen username :: Steve
PASS reopen gameRoot :: D:\ruanjian\zhinengti\gongzuokongjian\platypus\dist\_settings-smoke
PASS reopen memory :: 4096
PASS reopen updatedAt set :: 1794209369318
PASS normalize blank username :: Dev
PASS normalize memory clamp :: 32768
PASS corrupted file falls back :: Dev
SETTINGS SMOKE OK
```

它调的是 `DesktopServices.create(dir).settings`——与界面用的是同一条装配路径，因此证明了四件事：文件落点正确、反序列化正确、「关掉再打开」能读回、文件被写坏时程序仍能起来。

### 发行产物

`:app-desktop:createDistributable -PskipWix` → `BUILD SUCCESSFUL in 12s`。用 Python `zipfile` 读 app-image 里的 jar：

```
core-0.8.0-preview.1-*.jar -> ['JsonSettingsRepository', 'LauncherSettings']
ui-jvm-0.8.0-preview.1-*.jar -> ['SettingsPageKt']
```

新代码确实进了发行包。

随后又打了一次完整发行产物（2026-11-09 15:36–15:38）：

- msi：`:app-desktop:packageMsi -PskipWix -PwixDir=D:\ruanjian\kaifa\wix314` → `BUILD SUCCESSFUL in 25s`，产物 64,876,652 B，sha256 `558b5a2529a1e910053c10cfd7026f4b6b66e1e8309affa98ff1d0673b171401`。
- 解包复核：`dist/_unpack-msi2.cmd` 后台 `msiexec /a`（`msiexec_exit=0`）解到 `dist/_msi-unpack2/`（127 M），`Platypus Preview.exe` 597,504 B、sha256 `7f2ba938...0a7ed`（jpackage 壳不随代码变，与 app-image、zip 三处一致）；解包目录里的 `ui-jvm-...-3b736be06b44fb18e8705d234374.jar` 命中 `SettingsPageKt`，`core-...-91c0c23e41a37a58fd58cdd028cbacf0.jar` 命中 `JsonSettingsRepository` 与 `LauncherSettings`。
- zip：用 Python `zipfile` 重打 app-image 目录，200 条目、63,212,178 B，sha256 `9caa1320829a8d2c2ebfb86bbc3e7d7721176c810b572e445e2d7ecbdb32b12c`。
- 两份都已放回 `releases/preview/windows/`（旧的 15:06 那版移到 `dist/_old-releases/win-0909-1506/`）。

**出包必踩的坑**：`-PskipWix` 与 `-PwixDir` 必须同时给。只给 `-PwixDir` 会在 `:unzipWix` 失败：`Cannot expand ZIP 'C:\Users\zero\.gradle\compose-jb\wix311.zip'`。

---

## 四、没做的部分（明确列出，不含糊）

| 没做 | 说明 |
| --- | --- |
| 设置页的界面自动化测试 | Compose 桌面端的 UI 测试在本项目没有基础设施，本轮的证据停在「编译通过 + 单测 + 走真实装配点的冒烟」。**界面按钮本身没有脚本点过** |
| 设置项的导入/导出 | 没做，也不需要——就一个 json 文件，用户自己复制 |
| 多套配置切换 | 没做。一个游戏目录一份设置 |
| 默认目录的自动发现 | `defaultGameRoot()` 仍只看 `%APPDATA%\.minecraft`，没读注册表、没扫其它启动器 |
| `LINK("联机")` 页 | 仍是 `PlaceholderPage` |

---

## 五、下一步能接的地方

1. **界面真跑一遍**：冒烟驱动绕过的只有鼠标点击，但设置页的「保存 / 填回默认值 / 错误提示回显」三处交互还没有人眼确认过。装一个 msi 或直接跑 app-image，点三下就知道。
2. **默认内存接进实例页**：目前只有下载页的一键装用它；实例页没有「新建实例」入口，也就没地方用。
3. **用户名与正版登录的关系**：等 `AuthService` 有实现时，`offlineUsername` 该是「离线模式下的名字」，正版登录后以账号名为准。现在两者没有交集。
