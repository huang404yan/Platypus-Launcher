# 20 · Android 端补齐：模组 / 联机 / 设置三页

这一篇记录把 Android 端从「四个占位里两个是哑的」补到「只剩启动页还是占位」的过程。

## 1 · 之前是什么样

`MainActivity.kt` 的侧边栏有六项，但 `AppRoot()` 的 `when` 只接了四个：

| Tab | 之前 | 现在 |
| --- | --- | --- |
| 实例 | `InstancePage()`，真页面 | 不变 |
| 下载 | `DownloadPage()`，真页面 | 不变 |
| 模组 | **不存在** | `ModsPage()`（新） |
| 联机 | **不存在** | `LinkPage()`（新） |
| 启动 | `Placeholder` → 「还没做。」 | `LaunchPlaceholder()`，讲清缺什么 |
| 设置 | **不存在** | `SettingsPage()`（新） |
| 诊断 | `DiagnosticPage()`，安卓独有 | 不变 |
| 关于 | `AboutPage()` | 不变 |

「更多」这个 Tab 被删掉了——它原来挂着一个永远不会实现的占位，
现在被模组、联机、设置三个真页面取代。侧边栏顺序改成与桌面端对齐。

## 2 · 为什么先要把 ModInstaller 挪到 core

模组页要的「Modrinth 搜索 → 选版本 → 下载 → sha1 校验 → 落进 mods/」这套逻辑，
桌面端已经写好了，在 `ui/src/jvmMain/.../ui/download/ModInstaller.kt`。

问题是 `ui` 模块的桌面代码在 `jvmMain`，**安卓端拿不到**（androidApp 只依赖 `core`）。
所以做了一次搬迁：

```
ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/download/ModInstaller.kt   ← 删除
core/src/main/kotlin/io/github/huang404yan/platypus/core/mods/ModInstaller.kt      ← 新位置，174 行
```

包名从 `ui.download` 改成 `core.mods`，7 行同包 import 顺手删掉。代码本身一行没改。

引用点两处跟着改：

- `ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/ModsPage.kt`
- `app-desktop/src/test/kotlin/io/github/huang404yan/platypus/desktop/ModsE2e.kt`

搬迁能做的前提是 `ModInstaller` 本来就只用 `core` 里的东西（`HttpDownloader` / `ModScanner` /
`ModrinthMeta`）——`core` 是纯 JVM 模块，只依赖 `kotlinx-coroutines` 与
`kotlinx-serialization`，`HttpDownloader` 用的是 `HttpURLConnection`（安卓与桌面都有），
没有 `java.awt` / `java.net.http` / `ProcessBuilder`。

## 3 · 模组页（手机版与桌面版的差别）

桌面是左右双栏，手机放不下，改成一条竖着滚的流：

```
标题 → 权限卡（缺 MANAGE_EXTERNAL_STORAGE 时） → 游戏目录行
     → 搜索行（关键词 + 加载器轮换按钮）
     → 游戏版本行（140.dp）+ 搜索按钮
     → 「目录里的版本：1.20.1 1.21.1 …」快捷填入
     → 错误 / 提示 / 进度
     → 已装 N 个（启用 x · 停用 y）+ 每个模组一张卡
     → Modrinth 搜索结果 + 「装进 mods/」按钮
```

**加载器轮换顺序** FABRIC → FORGE → QUILT → NEOFORGE → FABRIC，与桌面端一致。

**游戏版本是必填的**，这是这一轮特意加硬的。原因写在桌面端那次实测里：
`gameVersion` 为空时 Modrinth 的 `versions` facet 被省略，`pickBest` 会取到最新版，
实测把 mc26.3 版的 Sodium 装进了 1.20.1 的目录——不报错，等启动崩了才发现。
所以 `search()` 与 `install()` 都先拦：

- 版本空 + 目录里扫到版本 → 「先选游戏版本：这个目录下有 1.20.1、1.21.1。」
- 版本空 + 目录里没版本 → 「先填游戏版本（如 1.20.1）。留空会搜到最新版模组，跟你的游戏对不上。」

`rescan()` 会顺手把 `InstanceScanner` 扫出来的版本号填进 `knownVersions`，
且优先非原版加载器的实例（那才是用户真在玩的）。

## 4 · 联机页：与桌面同一个 LanDiscovery，但多一个 MulticastLock

协议部分完全复用 `core/lan/LanDiscovery.kt`（组播 `224.0.2.60:4445`，
解析 `[MOTD]…[/MOTD]` 与 `[AD]端口[/AD]`）。两处必须改：

1. **剪贴板**：桌面用 `java.awt.Toolkit.systemClipboard`，安卓**不能** import `java.awt`。
   改用 DiagnosticPage.kt 里已有的 `copyToClipboard(context, text)`（走 `ClipboardManager`）。
2. **组播锁**：Android 在 Wi-Fi 上默认丢弃组播包（省电策略）。不拿
   `WifiManager.MulticastLock` 的话，`LanDiscovery.listen()` 会一直听到 0 个世界——
   界面上看着像「房主没开局域网」，实际是本机压根没收到包。所以扫描前后各拿一次、放开一次：

```kotlin
private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? = runCatching {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    wifi.createMulticastLock("platypus-lan-scan").apply {
        setReferenceCounted(false)
        acquire()
    }
}.getOrNull()
```

拿不到（移动数据、系统不给锁）就返回 `null`，扫描照常进行——有线与部分机型本来不需要它。

`AndroidManifest.xml` 新增两条权限：

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```

缺了不会崩，只是永远「找到 0 个世界」——这种失败最难查，所以写进注释了。

**为什么手机端也不做「点一下就进」**：与桌面同理，绕过游戏去连会丢皮肤与会话。
手机端更甚——现在根本没有应用内 JVM，能在启动器里点的「进游戏」只能是假的。
这一页只负责把地址找出来、复制走。

## 5 · 设置页

与桌面 SettingsPage 读写**同一个** `JsonSettingsRepository`：
`<游戏目录>/platypus/settings.json`。同一份目录在手机与桌面之间来回拷，设置跟着走。

三个字段：离线用户名（3–16 位，走 `usernameProblem()` 校验并实时提示）、
默认游戏目录（实时显示「目录存在 / 不存在」）、默认内存（512–32768 MB，
由 `Instance.MIN_MEMORY_MB` / `MAX_MEMORY_MB` 夹住）。

页面底部那张卡显示设置文件的绝对路径，用户想手工改或整个带走都找得到。

## 6 · 启动页：从「还没做」改成「缺什么」

原来的 `Placeholder` 只有一句「还没做。」。现在 `LaunchPlaceholder()` 写清三件事：

1. 手机上进游戏要先在应用内起 JVM，再把 OpenGL 转译成手机的图形接口；
2. 这是独立的 `runtime-android` 模块，**现在还不存在**（`docs/03` 第 3 节的 S1 spike）；
3. 这一版能做的四件事：下全游戏文件、认出已有实例、装模组、找局域网世界。

不再放一个哑按钮，是因为哑按钮会让人以为「功能有 bug」，而实际是「能力还没建」。

## 7 · 实测

### 7.1 编译与出包

```
\.\gradlew :androidApp:assembleDebug -PskipWix
BUILD SUCCESSFUL in 34s
40 actionable tasks: 5 executed, 35 up-to-date
```

产物 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`，**12 879 708 字节**，
时间戳 Nov 9 23:15。

### 7.2 包里真有新代码吗

Android 的 class 都编进 dex，`jar tf` 只能看到 12 个 `classes*.dex`，看不到类名。
所以直接扫 dex 字节（`dist/_apk_scan.py`，模式字符串在 dex 里是明文）：

| 符号 | 在 dex 里出现次数 |
| --- | --- |
| `ModsPageKt` | 202 |
| `LinkPageKt` | 100 |
| `SettingsPageKt` | 70 |
| `LaunchPlaceholder` | 6 |
| `ModInstaller` | 25 |
| `LanDiscovery` | 24 |

六个全在，说明这三个新页面与搬迁后的 `ModInstaller` 真进了包，不是「编译通过但没打进去」。

### 7.3 编译期修掉的两个真错

1. `LinkPage.kt:123` —— `Smart cast to 'String' is impossible, because 'error' is a public API
   property declared in different module.`
   `LanScanResult.error` 在 `core` 模块里，跨模块的 public 属性 Kotlin 不做智能转换。
   改成先取局部变量 `val message = current.error ?: ""`。
2. `SettingsPage.kt:154` —— `Suspend function 'save()' can only be called from a coroutine`。
   按钮的 `onClick` 不是挂起上下文，补 `rememberCoroutineScope()` + `scope.launch { save() }`。

## 8 · 还没做到

| 事 | 状态 |
| --- | --- |
| 真机运行 | **没做过**。本机没连安卓设备，全部验证止于「编译通过 + 包内有类」。下载页、模组页、联机页、设置页都还没有一份真机截图或日志 |
| 启动（应用内 JVM + 图形转译） | 不存在，属 `runtime-android` 模块，即 `docs/11` 的 S1 实验 |
| 安卓端单测 | 没有。安卓逻辑都在 composable 里，能测的部分（`LanDiscovery` / `ModInstaller` / `ModrinthMeta`）已经在 `core` 的 245 例里覆盖 |
| 组播锁的真实效果 | **未验证**。代码按 Android 官方要求拿了锁，但没有设备证明「不拿锁收不到、拿了能收到」 |
| 模组页的本地停用/启用 | **已补**（2026-11-10，`docs/23-mods-manage.md`）：勾选框一次点击改名，删除与检查更新也补齐了。安卓端编译通过、**无设备验证** |

## 9 · 下次接手先看这里

- 安卓三页的入口都在 `MainActivity.kt` 的 `Tab` 枚举与 `AppRoot()` 的 `when` 里，
  加页只改这两处。
- `ModsPage.kt` / `LinkPage.kt` / `SettingsPage.kt` 都在
  `androidApp/src/main/kotlin/io/github/huang404yan/platypus/launcher/preview/` 下，
  都是 `internal fun`，与既有的 `InstancePage` / `DownloadPage` 同一个包。
- 想在真机验证，最省事的顺序是：装 APK → 授权「所有文件访问权限」→
  实例页填 `/sdcard/.minecraft` → 下载页下一份 1.20.1 → 模组页搜 sodium 装一个 →
  联机页点扫描（应该有防火墙/组播权限相关的结果）。
