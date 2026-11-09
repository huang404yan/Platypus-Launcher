# 07 桌面端启动链路（下载 → 探测 Java → 启动）

记录日期：2026-11-09。本文只记录**已经跑通过的事实**与**还没做到的部分**，不含计划。

---

## 一、这条链路原来断在哪

改造前（t1 基线）三块各自独立，界面上没有任何入口把它们串起来：

| 环节 | 改造前状态 |
| --- | --- |
| 探测 Java | `core/runtime/JavaRuntime.kt` 只有 `interface JavaRuntimeService`；`app-desktop/runtime/DesktopJavaRuntimeService.kt` 已有实现但**没有任何 UI 调用它** |
| 启动实例 | `core/launch/Launch.kt` 只有 `interface GameRuntime`；`app-desktop/runtime/DesktopGameRuntime.kt` 已有实现但同样**没有入口** |
| 启动页 | `ui/App.kt` 的 `Page.LAUNCH` 落到 `PlaceholderPage`，正文就一句「还没做。」 |

也就是说：底层能力在 t1～t6 期间陆续写好了，但**用户点不到**。

---

## 二、改了什么（代码改动清单）

### 新增文件

| 文件 | 作用 |
| --- | --- |
| `ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/LauncherBackend.kt` | `interface LauncherBackend { val javaRuntimes: JavaRuntimeService; val gameRuntime: GameRuntime }`。ui 模块只认这个接口，不 import 任何 app-desktop 的类，也不碰桌面 API |
| `ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/LaunchPage.kt` | 启动页本体，约 470 行。四段式：实例选择 / Java 列表 / 离线用户名 + 启动·停止 / 状态行 + 实时日志 |
| `core/src/main/kotlin/io/github/huang404yan/platypus/core/auth/OfflineProfile.kt` | `OfflineProfile.session(username)`：按 `OfflinePlayer:<name>` 的 MD5 生成 32 位无横线 hex UUID（与 Minecraft 离线模式一致），accessToken 填 `"0"`、userType `"legacy"` |
| `app-desktop/src/main/kotlin/io/github/huang404yan/platypus/desktop/DesktopServices.kt` | `class DesktopServices : LauncherBackend`，`create(gameRoot)` 里 new 出 `DesktopJavaRuntimeService` 与 `DesktopGameRuntime` |

### 修改文件

| 文件 | 改动 |
| --- | --- |
| `ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/App.kt` | 签名改为 `App(initialGameRoot: String? = null, backend: LauncherBackend? = null)`；`when(page)` 最前面加 `Page.LAUNCH -> LaunchPage(backend, initialGameRoot)`。侧栏顺序、默认页（仍是「实例」）不变 |
| `app-desktop/src/main/kotlin/io/github/huang404yan/platypus/desktop/Main.kt` | 在 `application { }` 之前加一句 `val services = DesktopServices.create(...)`，把 `services` 传给 `App(backend = services)`。**全程序只有这一处装配后端** |
| `core/src/main/kotlin/io/github/huang404yan/platypus/core/launch/LaunchDiagnosis.kt` | 见下节「离线 401 噪声」 |
| `core/src/test/kotlin/io/github/huang404yan/platypus/core/launch/LaunchPipelineTest.kt` | 新增回归测试 `离线 401 堆栈在进过主菜单后不算失败原因` |

### 装配方式（为什么这么分）

ui 是 Compose Multiplatform 模块，`jvmMain` 里出现 `ProcessBuilder`、`java.home` 这类东西会让以后接别的平台时没法复用，所以：

- `core` 定义接口（`JavaRuntimeService` / `GameRuntime`，t1 起就有）；
- `app-desktop` 提供实现，并在 `Main.kt` 组装成 `DesktopServices`；
- `ui` 只拿 `LauncherBackend` 接口。

Composable 里没有一处 `new` 实现类。

### 启动页行为

1. 顶部：游戏目录输入框 + 「扫描」，列出 `InstanceScanner.scan()` 认到的实例，点一下选中（高亮）。
2. 中部：显示 `GameJavaRules.requiredMajor(instance.gameVersion)` 算出的「游戏需要 Java N」，右侧「重新探测」按钮；下方列出 `detect()` 到的每个 Java（major · vendor · 路径）。**精确匹配 requiredMajor 的那个自动选中并标「推荐」**；没有精确匹配时红字提示「本机没有 Java N」，且不允许启动（不会拿 25 顶替 17）。
3. 下部：「离线用户名」（默认 `Dev`）+「启动」/「停止」。
4. 底部：状态行（`运行中 · PID xxxx` / `已退出 · 退出码 n` / 失败原因）+ 实时日志（monospace，环形缓冲 5000 行，界面只渲染最近 500 行，自动滚到底）。

停止按钮走 `GameHandle.stop(graceMillis)`：`destroy()` → 等 5 秒 → 还在就 `destroyForcibly()`。

### 离线 401 噪声的处理

离线（accessToken=`0`）启动时，MC 自己会打一段：

```
[Render thread/ERROR]: Failed to verify authentication
com.mojang.authlib.exceptions.InvalidCredentialsException: Status: 401
    at com.mojang.authlib.yggdrasil.YggdrasilUserApiService.fetchProperties(YggdrasilUserApiService.java:156)
    ...
```

这是**必然出现且不影响进游戏**的（下一行就 `Setting user: Dev`，照常进主菜单）。原来的 `LaunchDiagnosis` 会把它当成退出原因，用户点了「停止」后看到「登录凭据无效」，会误以为账号有问题。

改法：

- `Rule` 加字段 `skipWhenReachedMenu`，authlib 那条规则置 `true`；
- `diagnose()` 先算 `reached`（日志里出现过 `Setting user:` 或任何进主菜单特征）；
- `reached` 为真时跳过该规则；若此时退出码非 0，返回「游戏在进入主菜单后退出（退出码 n）：通常是手动停止或正常关闭，日志里没有崩溃迹象。」

---

## 三、真启动证据（一次完整记录）

### 环境

- 实例：`dist/_test-mc/1.20.1/`（Minecraft 1.20.1 原版，`versions/1.20.1/1.20.1.jar` 23,028,853 字节，`libraries` 下 64 个 jar）
- Java：`C:/Users/zero/AppData/Roaming/.minecraft/runtime/java-runtime-beta/bin/java.exe`（Microsoft，Java 17.0.15）
- 账号：离线，用户名 `Dev`，UUID 由 `OfflineProfile` 生成，accessToken `0`

### 命令

冒烟脚本用 Gradle init script 注入，不改仓库构建文件：

```
MSYS_NO_PATHCONV=1 cmd.exe /c "D:\ruanjian\kaifa\qidongqigongju\gradle-9.9.0\bin\gradle.bat --no-daemon \
  -I D:/ruanjian/zhinengti/gongzuokongjian/platypus/dist/_t7smoke/t7-smoke.init.gradle \
  :app-desktop:t7Smoke"
```

退出码 **0**。这个任务调的是与界面完全相同的 `DesktopServices.create()` → `detect()` → `GameRuntime.launch()`，只是不画界面。

### 关键输出

```
[smoke] scan root=D:\...\dist\_test-mc\1.20.1 isGameDirectory=true instances=1
[smoke]   instance 1.20.1 mc=1.20.1 loader=VANILLA dir=...\versions\1.20.1
[smoke] java major=25 vendor=Microsoft path=.../java-runtime-epsilon/bin/java.exe
[smoke] java major=25 vendor=Oracle Corporation path=D:/ruanjian/kaifa/java/25.0.5.0.3/bin/java.exe
[smoke] java major=17 vendor=Microsoft path=.../java-runtime-beta/bin/java.exe
[smoke] java major=16 vendor=Microsoft path=.../java-runtime-alpha/bin/java.exe
[smoke] requiredJava=17 picked=.../java-runtime-beta/bin/java.exe
[smoke] stage=准备版本文件 0.05
[smoke] stage=整理 classpath 0.1
[smoke] stage=解压原生库 0.15
[smoke] stage=启动游戏 0.2
[smoke] started pid=15600
[smoke] MENU-REACHED after 7012 ms, lines=18
[smoke] staying alive 20s after menu, no crash
[smoke] stopping process
[smoke] stop result=true
[smoke] exited code=1 diagnosis=游戏在进入主菜单后退出（退出码 1）：通常是手动停止或正常关闭，日志里没有崩溃迹象。
```

注意 `detect()` 在本机找到 4 个 Java（两个 25、一个 17、一个 16），**按 requiredJava=17 精确选中的是 17**，没有拿 25 顶替。

### 日志尾部（`build/_t7-launch.log`，共 40 行）

```
[10:45:44] [Render thread/INFO]: Setting user: Dev
[10:45:44] [Render thread/INFO]: Backend library: LWJGL version 3.3.1 build 7
[10:45:45] [Render thread/INFO]: Reloading ResourceManager: vanilla
[10:45:47] [Render thread/INFO]: OpenAL initialized on device OpenAL Soft on ...
[10:45:47] [Render thread/INFO]: Sound engine started
[10:45:47] [Render thread/INFO]: Created: 1024x512x4 minecraft:textures/atlas/blocks.png-atlas
...
[10:45:56] [Render thread/INFO]: Loaded 7 recipes
[10:45:56] [Render thread/INFO]: Loaded 1271 advancements
```

`Loaded N recipes` / `Loaded N advancements` 只会在主菜单初始化完成后才打印，是最硬的「进了主菜单」证据。

### 界面实证（GUI，真窗口）

用 init script 起 `:app-desktop:t7Gui`（跑真正的 `MainKt`），截图核对：

1. 侧栏点「启动」→ 出启动页：标题「启动」，游戏目录已填 `...platypus\dist\_test-mc\1.20.1`，实例卡片 `1.20.1 / 游戏 1.20.1 · VANILLA`，右侧「游戏需要 Java 17」，Java 列表列出 25 Microsoft / 25 Oracle / …，离线用户名 `Dev`。
2. 点「启动」→ 约 18 秒后 Minecraft 窗口（标题 `Minecraft 1.20.1`）弹出并停在**主菜单**（单人游戏 / 多人游戏 / Minecraft Realms / 选项 / 退出游戏），启动器底部状态行变「运行中 · PID 4160」，「启动」置灰、「停止」可用。
3. 截图存于 `build/_t7-gui-launch3.png`（启动页）、`build/_t7-gui-running2.png`（游戏窗口出主菜单，同时启动器显示「运行中 · PID 4160」）。

> 截图目录 `build/` 与 `dist/` 都在 .gitignore 里，不进仓库。

### 退出码为什么是 1

无头冒烟里是我主动 `stop()` 杀掉的，`destroy()` 后进程以非 0 退出属正常；诊断文案已经改成「进入主菜单后退出」，不再误报登录失败。玩家从主菜单点「退出游戏」时，MC 自己返回 0。

---

## 四、还没做到的（离「双击就能玩」还差什么）

按重要性排序，全部是**现状事实**，不是计划：

1. **没有正版登录**。现在只有离线用户名。`core/auth/Account.kt` 的 `AuthService` 至今没有任何实现（Microsoft OAuth 设备码流程、Yggdrasil 都没有）。离线只能进单人游戏和自己开的服务器，**进不了正版验证服务器**。
2. **没有「一键装游戏」**。下载页能按版本号拉原版进指定目录，但用户还得自己知道要下哪个版本、目录填哪；启动页也不会在「本机没有 Java N」时自动下 Java。缺的是「选版本 → 自动下载 → 自动补 Java → 自动建实例」这条闭环。
3. **没有实例管理**。只能扫「已经存在的 `.minecraft` 目录」，不能在启动器里新建/删除实例、改内存、加 JVM 参数。`InstanceRepository` 只有接口，没有实现，也没有持久化。
4. **没有 Mod 加载器安装**。Forge / NeoForge / Fabric / Quilt 只能识别，不能安装。
5. **下载源基本单一**。`DownloadPlanner` 有 bmclapi2 镜像映射，但只在主源失败后作**兜底**（`core/.../download/HttpDownloader.kt` 第 131-134 行：`candidates` 先放 `item.url`，随后才追加 `item.mirrors`），没有「优先国内镜像」开关；也没做断点续传（请求只设 `User-Agent`、不设 `Range`，失败重试是整文件 `.part` 从零重下）。
6. **日志编码**。乱码是真的，但原因和修法都不是「改成按 GBK 解码」：
   - 读取侧**已经是 UTF-8**。`app-desktop/src/main/kotlin/io/github/huang404yan/platypus/desktop/runtime/DesktopGameRuntime.kt` 第 112 行原文是 `BufferedReader(process.inputStream.reader(Charsets.UTF_8)).use { reader -> while (true) { val line = reader.readLine() ?: break; handle.append(line); onLine(line) } }`，写死 `Charsets.UTF_8`，没有任何 `file.encoding` / GBK 分支。
   - 真正的因在写侧：MC 子进程按系统代码页（本机 GBK）输出，UTF-8 解码把非法字节替换成 U+FFFD，**不可逆**。实测 `build/_t7-launch.log`（40 行）里 26 个 U+FFFD 全集中第 24 行 OpenAL 设备名一行（`OpenAL initialized on device OpenAL Soft on ...`），那行现在已经是替换字符，回读也救不回来。
   - 修法（**未验证**）：给子进程同时加 `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8`。依据是本机两组探针：JDK 25 只认 `-Dstdout.encoding=UTF-8`（加 `-Dfile.encoding=UTF-8` 输出仍是 GBK 字节 `d6 d0 ce c4 ...`），Java 17 只认 `-Dfile.encoding=UTF-8`（加 `-Dstdout.encoding=UTF-8` 输出仍是 GBK 字节）。两个一起传才能覆盖新旧 JRE，但**没有在真实 MC 启动上跑过**，只在 `build/_t7enc/` 的探针类上验过。
7. **打包产物里的 WiX 依赖**。出 msi 要本机有 WiX（装到 PATH，或 `-PwixDir=` / `PLATYPUS_WIX_DIR` 指过去），没有就出不了安装包（详见 `docs/06-wix-msi-build.md`）。app-image（zip）不受影响。本机现状：`build/wix311/candle.exe`（28,672 字节）与 `light.exe`（36,864 字节）已铺好、`D:\ruanjian\kaifa\wix314\bin\candle.exe` 存在、`app-desktop/build/compose/binaries/main/msi/Platypus Preview-0.8.0.msi` 已产出——即「本机可用」，但换个干净环境仍要先铺 WiX。

---

## 五、测试

`:core:test`：**89 个测试全绿**（BUILD SUCCESSFUL in 17s，10 个测试类）。

新增的回归测试在 `core/src/test/kotlin/io/github/huang404yan/platypus/core/launch/LaunchPipelineTest.kt`：

```kotlin
@Test
fun `离线 401 堆栈在进过主菜单后不算失败原因`() {
    // lines 含 401 堆栈 + "Setting user: Dev" + "Backend library: LWJGL" + "Sound engine started"
    // 断言：有诊断、不含「登录凭据无效」、含「进入主菜单后退出」
    // 再取 lines.take(3)（只有 401 堆栈、没进主菜单）验证仍归因「登录凭据无效」
}
```

注意这个测试第一次跑是**失败**的：断言写的是 `diagnosis == null`，实际 `diagnose()` 会返回那句「进入主菜单后退出」的兜底文案。是测试写错、不是产品错，改成断言文案内容后通过。

---

## 六、冒烟脚手架（不进仓库）

放在 `dist/_t7smoke/`（`dist/` 已被 .gitignore）：

| 文件 | 作用 |
| --- | --- |
| `kotlin/.../desktop/smoke/Smoke.kt` | 无头跑一遍 detect + launch，打印 stage / PID / 进主菜单耗时 / 退出码 |
| `t7-smoke.init.gradle` | `gradle.projectsLoaded` 里给 app-desktop 加 `srcDir` 并 register `t7Smoke`（JavaExec） |
| `t7-gui.init.gradle` | register `t7Gui`（JavaExec，`mainClass = ...MainKt`，args 传实例目录） |
| `shot.ps1` / `click2.ps1` / `win.ps1` | 全屏截图 / 定位窗口后点击 / 移动或聚焦窗口 |

用 `gradle -I <init>` 注入，**仓库里任何 build 文件都没为冒烟改过一行**。
