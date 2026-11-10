# 24 · 安卓端实例详情页

记录日期：2026-11-10。本文只写**已经跑通过的事实**与**还没做到的部分**。

> 编号说明：01–23 已占用，本轮取 24。
> 上位文档：`docs/22-shili-geli-yu-xiangqing.md`（桌面端实例详情页）与 `docs/20-android-pages.md`（安卓三页）。

---

## 一、这一轮补的是哪件缺口

22 号把桌面端的「实例详情」做出来了：能看版本 json 摘要、能改 Java、能改内存、能看每个目录占多少地方。
安卓端停在 20 号的样子——**实例页只读**：

```kotlin
// 改之前，MainActivity.kt 的 InstancePage 里只有这几个
ItemCard(instance)   // name / 游戏版本 · 加载器 / directory
```

卡片上四行小字，点不动、改不了。用户在手机上能看到「这台手机上有 3 个实例」，
但没有入口知道其中任何一个**到底缺什么**——这正是 22 号在桌面上解决过的问题。

| 能力 | 桌面（22 号之后） | 安卓（本轮之前） |
| --- | --- | --- |
| 看版本 json / client jar / mainClass / 库条目 | 有 | 没有 |
| 改内存 | 有 | 没有 |
| 改 JVM 参数 | 有 | 没有 |
| 指定 Java 路径 | 有（从本机探测列表里挑） | 没有 |
| 看每个目录占多少地方 | 有 | 没有 |
| 打开目录 | 有（资源管理器） | 没有（手机没有「资源管理器」这回事） |

---

## 二、只搬一半，另一半换成手机能用的

桌面详情页有一半是桌面独有的，直接搬过去会变成哑按钮：

| 桌面有的 | 手机上的处理 | 为什么 |
| --- | --- | --- |
| 探测本机 Java（`javaRuntimes.detect()`） | **不做**，改成「需要的 Java 是多少」只读展示 + 一个手填路径的输入框 | 安卓上没有「扫 `C:\Program Files\Java`」这件事；应用内起 JVM 属 `runtime-android`（`docs/11` 的 S1），现在还不存在 |
| 用资源管理器打开目录 | 换成「复制实例路径」「复制根目录」两个按钮 | 手机上没有桌面文件管理器；剪贴板是唯一能把路径交出去的通道（`copyToClipboard`，`DiagnosticPage.kt:161` 现成的） |
| 磁盘统计 | **原样复用** core 的 `InstanceDetailsLoader.load()` | 这段是纯 `File` 遍历，与平台无关，一行都没重写 |

所以新文件 `androidApp/.../preview/InstanceDetailScreen.kt` 的第一段注释写的就是这件事：
不是「手机版少几个功能」，而是「桌面独有的那两件事在手机上换一种做法」。

---

## 三、输入的合并规则抽进了 core：`InstanceEdits`

这一轮顺手把一件迟早会出错的事先处理掉了。

详情页的「保存」要做三件事：内存文字转整数、夹到 512–32768、JVM 参数按行切开丢掉空行。
改之前这段逻辑在**两个地方各写一遍**：

- `ui/.../InstanceDetailPanel.kt` 的 `save()`
- `androidApp/.../InstanceDetailScreen.kt` 的 `save()`

两端读写的是**同一份** `<游戏目录>/platypus/instances.json`（手机和电脑的游戏目录会互相拷贝）。
只要有一端写歪，同一个文件就会被写成两种值，而且用户看不出来是谁改的。

抽成 `core/instance/InstanceEdits.kt` 之后：

```kotlin
sealed interface InstanceEditResult {
    data class Ok(val instance: Instance, val memoryWasClamped: Boolean) : InstanceEditResult
    data class Invalid(val message: String) : InstanceEditResult
}

object InstanceEdits {
    const val BAD_MEMORY_MESSAGE = "内存要填整数，单位 MB。"
    fun jvmArgsOf(text: String): List<String>
    fun fromInput(instance: Instance, memoryText: String, jvmArgsText: String, javaPath: String): InstanceEditResult
    fun savedMessage(result: InstanceEditResult.Ok): String
}
```

三个设计点，都是踩过的坑：

1. **`memoryWasClamped` 单独返回一个布尔**。用户填 999999，钳制成 32768 后界面如果说「已保存：32768 MB」，
   用户会以为自己填的 999999 生效了。现在会说「已按允许范围（512–32768 MB）改成 32768 MB。」。
   比较用**钳制后的数值**而不是原文字，所以填 `" 4096 "` 与 `"4096"` 不会被误报成「被改过」。
2. **`jvmArgsOf` 丢空行**。用户从别处粘贴常常带空行，原样传给 java 会多出一个空串参数，启动直接报错。
   全空行时返回 `emptyList()` 而不是 `listOf("")`。
3. **只改这三项**。`fromInput` 用 `copy()` 只覆盖 `javaPath` / `memoryMb` / `extraJvmArgs` / `registered`，
   `id` / `directory` / `loader` / `createdAt` 原样带回来——详情页负责改设置，不负责搬家。

有单测：`core/src/test/.../instance/InstanceEditsTest.kt`，**9 例**。
这是本轮唯一能在无设备环境里证明「保存逻辑是对的」的东西，所以写得比较细（见第六节）。

---

## 四、扫描结果要和登记文件合并

安卓实例页原来是「扫到什么显示什么」。加详情页之后这条会出事：

用户把 1.20.1 的内存改成 8192 → 写进 `instances.json` → 回到列表 → 页面重建重扫
→ `InstanceScanner.scan()` 从磁盘版本目录造出的一份新 `Instance`，`memoryMb` 是默认的 2048
→ **改过的内存被打回去了**，但文件里还留着 8192，界面和文件对不上。

桌面端早就处理过这个问题（`mergeInstances`），安卓这次跟上：

```kotlin
val registered = JsonInstanceRepository(JsonInstanceRepository.defaultIndexFile(dir)).list()
val scanned = InstanceScanner.scan(dir)
scanned.copy(instances = mergeInstances(registered, scanned.instances))
```

`mergeInstances` 的规则（`core/instance/Instance.kt` 里，22 号就定下的）：同一条目录以**登记的那份**为准，
登记的排在前面，`registered` 为 true。所以列表上「已登记」的那条点进去看到的就是用户改过的值。

保存之后还要就地更新两处状态，否则界面显示旧值：

```kotlin
onSaved = { saved ->
    selected = saved
    result = result?.let { r ->
        r.copy(instances = mergeInstances(listOf(saved), r.instances.filterNot { instanceKey(it) == instanceKey(saved) }))
    }
}
```

`instanceKey`（`core/instance/Instance.kt:62`）是「这条实例的唯一定位」，用它做过滤而不是 `id`：
扫描出来的实例与登记的实例 `id` 可能不同（`1.20.1` vs `1.20.1-forge-47.2.0`），但目录是同一个。

---

## 五、界面结构

`androidApp/.../preview/InstanceDetailScreen.kt`（280 行，新建）。
`@Composable internal fun InstanceDetailScreen(instance, gameRoot, onBack, onSaved)`。

五张卡片，顺序按「用户最想先知道什么」排：

| 卡片 | 内容 |
| --- | --- |
| 这个实例在哪 | 版本目录 / 游戏根目录 / 共享目录说明 + 「复制实例路径」「复制根目录」 |
| 版本文件 | 版本 json 有无、client jar 有无 + 字节数、mainClass、继承自、库条目数 |
| Java | 需要的 Java（`d.requiredJavaMajor ?: GameJavaRules.requiredMajor(gameVersion)`）、当前设置、路径输入框 |
| 内存与启动参数 | 内存输入框（标范围 512–32768）、JVM 参数多行输入框 |
| 占了多少地方 | 八个目录各一行（`DirLine`），加合计（不含共享的 libraries/ 与 assets/） |

底部「保存」按钮 + 一行提示文字。`d.warnings` 非空时多一张「注意」卡片（红字）。

一个刻意的差异：`DetailLine` 的标签固定 `Modifier.width(96.dp)`，桌面端是 `fillMaxWidth(0.18f)`。
手机屏窄，按比例分到 1400px 的 18% 只剩几十像素，「客户端 jar」这种词会被挤成竖排。

---

## 六、实测（能做的都做了，做不了的如实写）

### 6.1 编译

```
gradle.bat --no-daemon :ui:compileKotlinJvm :androidApp:compileDebugKotlin -PskipWix
BUILD SUCCESSFUL in 25s
```

### 6.2 出 APK 并确认新代码真在包里

```
gradle.bat --no-daemon :androidApp:assembleDebug -PskipWix
BUILD SUCCESSFUL in 21s
40 actionable tasks: 5 executed, 35 up-to-date
```

产物 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`，**13 095 962 字节**，
时间戳 Nov 10 08:44，包内 140 个条目 / 12 个 `classes*.dex`。

dex 里类名被压缩但字符串仍是明文，用 `apkanalyzer dex packages --defined-only` 直接查：

| 类 | 方法数 / 字节数 |
| --- | --- |
| `...preview.InstanceDetailScreenKt` | 54 / 16 074 |
| `...preview.InstanceDetailScreenKt\$InstanceDetailScreen\$save\$1` | 5 / 765 |
| `...preview.MainActivityKt` | 66 / 22 499 |
| `...core.instance.InstanceDetails` | 34 / 3 069 |

说明详情页、保存协程、以及它依赖的 core 类都真进了包，不是「编译通过但没打进去」。

### 6.3 单测

```
gradle.bat --no-daemon :core:test :app-desktop:compileTestKotlin -PskipWix
BUILD SUCCESSFUL in 17s
classes 30  tests 308  failures 0  errors 0  skipped 0
```

比上一轮的 299 例多 9 例，正好是 `InstanceEditsTest`。30 个测试类。

### 6.4 真机：**没做**

```
adb devices
List of devices attached        ← 空
```

本机没连安卓设备，SDK（`D:/ruanjian/kaifa/android-sdk`）里也**没有 emulator、没有 system-images**：
已装的只有 build-tools 36.0.0 / 37.0.0、platform-tools 37.0.1、platforms android-36 / 37.0 / 37.2。

CPU 虚拟化是开着的（`systeminfo`：VM Monitor Mode Extensions / Virtualization Enabled In Firmware /
SLAT / DEP 全 Yes），D 盘余量 124 GB，所以真要跑模拟器只差装包：
`sdkmanager "emulator" "system-images;android-36;google_apis;x86_64"`（约 1.5–3 GB）再
`avdmanager create avd`。**这一轮没装**，原因是它属于「为了验证一个页面下载 2 GB」，
应该由用户决定要不要装，而不是我替他决定。

所以本页的界面布局、点击响应、真机读写权限都**未验证**。

---

## 七、还没做到

| 事 | 状态 |
| --- | --- |
| 真机验收 | **没做过**（本机无设备、无模拟器，见 6.4） |
| 手机端启动游戏 | 不存在，属 `runtime-android`（`docs/11` 的 S1）。所以内存 / JVM 参数 / Java 路径在手机上**只写不读**，界面已明说「只在桌面端启动时生效」 |
| 手机上探测可用 Java | 没做。手机上「哪来的 Java 可考」这件事本身要等 runtime-android 定下来 |
| 「打开目录」 | 换成复制路径。真要打开得走 `Intent.ACTION_VIEW` + 文件管理器，各家 ROM 行为不一，没验证过不敢写 |
| 依赖安装（一次装齐） | 安卓模组页仍只有一句「还差 N 个前置模组，需要自己再装」，桌面端有 `installDependencies()`。属 P2 的尾巴 |
| 实例删除 | 安卓端没有。core 的 `InstanceDeleter` 是纯 `File` 操作可以直接用，但没有界面入口 |

---

## 八、下次接手先看这里

- 详情页入口就一处：`MainActivity.kt` 的 `InstancePage()` 里
  `val open = selected; if (open != null) { InstanceDetailScreen(...); return }`。
- 三个输入框的合并规则**只能改 `core/instance/InstanceEdits.kt`**，不要在 composable 里再写一份。
  改完跑 `:core:test --tests *InstanceEditsTest*`。
- 想在真机验证，最省事的顺序：装 APK → 授权「所有文件访问权限」→
  实例页填 `/sdcard/.minecraft` → 扫描 → 点一个实例如 → 改内存成 8192 → 保存 →
  返回列表再重扫一次，**那条实例应该还显示 8192**（这一条就是第四节合并逻辑的验证点）。
  磁盘上的 `/sdcard/.minecraft/platypus/instances.json` 里也该有 8192。
