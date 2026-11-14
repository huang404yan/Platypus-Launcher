# 30 · 模组冲突检测（Modrinth 的 incompatible 声明）

记录日期：2026-11-14。本文只写**已经跑通过的事实**与**还没做到的部分**。

> 编号说明：01–29 已占用，本轮取 30。同属模组页的机制见 `docs/18`（页面）、`docs/23`（启停与更新）、`docs/28`（依赖求解）。

---

## 一、一句话

模组页「已装」标签页多一个「查冲突」按钮：把 `mods/` 里每个 jar 的 sha1 拿去 Modrinth 反查，读出作者在版本里写的 `dependency_type = "incompatible"`，把「两个都装着」和「只被点名、没装」分成两段列出来。

---

## 二、为什么值得做，以及为什么措辞要小心

这类冲突的表现是「装完能启动、进世界黑屏或直接崩」，用户很难自己想到是两个优化模组打架。2026-11-14 拿本机一个真实 Forge 实例的 34 个 jar 反查，出现 3 条 incompatible 声明：

| 声明方 | 被点名 | 本地是否装着 |
| --- | --- | --- |
| Embeddium | Rubidium | 否 |
| Embeddium | TexTrue's Embeddium Options | 否 |
| Superb Warfare | Iris/Oculus & GeckoLib Compat | 否 |

三条都是 `present = false`，即「作者提到不兼容，但那一边没装」。同一批数据也说明**这个字段并不罕见**：34 个文件里 30 个被 Modrinth 认出，其中 8 个带依赖声明，出现了 3 条 incompatible。

但 `incompatible` 的语义是**作者声明**，不是启动器的判定。有些是功能重复（Embeddium 与 Rubidium 都是 Sodium 移植，选一个就行），两个都装着不一定崩；有些是真抢同一个 mixin 目标，装了就进不去世界。所以界面上的措辞统一按「作者声明的模组冲突」来说，卡片里固定写一句：

> 来源是模组作者在 Modrinth 上写的「不兼容」声明，不是启动器猜的。功能重复的模组也会这么标，两个都装着不一定崩 —— 但作者这么说，出问题先从这里面排查。

把「不一定崩」写进界面，是为了避免下一个问题是「启动器说这两个必崩，但我装了能玩」。

---

## 三、为什么用 sha1 反查，而不是读 jar 里的 modId

冲突声明里点名的是 `project_id`（Modrinth 自己的主键），而 jar 里 `mods.toml` / `fabric.mod.json` 的 `modId` 与它不是一回事：Sodium 碰巧都叫 `sodium`，但大多数模组不是。要按 modId 比对，就得先读每个 jar 的元数据，再把 modId 映射回 project_id —— 反而多一层不确定。

sha1 是 Modrinth 自己给文件的主键，最不会认错。这一条与 `ModUpdateChecker`（检查更新）是同一套理由、同一个接口：

```
POST https://api.modrinth.com/v2/version_files
{"hashes": ["<sha1>", ...], "algorithm": "sha1"}
```

分批大小复用 `ModUpdateChecker.LOOKUP_BATCH`（100）。反查结果里除了 `project_id` 还有 `dependencies` 数组，正是这次要的东西。

---

## 四、core 的两个口子

### 4.1 解析层：required 与 incompatible 分开收

`core/src/main/kotlin/io/github/huang404yan/platypus/core/mods/ModrinthMeta.kt` 里，`dependencies` 数组原来只取 `required` 那一类（依赖求解用），现在先把对象全取出来再按类型分两列：

```kotlin
val depObjects = (obj["dependencies"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
val deps = depObjects.filter { stringOf(it, "dependency_type") == "required" }
    .mapNotNull { stringOf(it, "project_id") }
val conflicts = depObjects.filter { stringOf(it, "dependency_type") == "incompatible" }
    .mapNotNull { stringOf(it, "project_id") }
    .distinct()
```

Modrinth 的四类依赖是 `required` / `optional` / `incompatible` / `embedded`。`optional` 与 `embedded` 都不进任何一列：前者是「可以顺手装」不构成缺前置，后者是「已经打进 jar 了」不需要再下。

`ModVersionFile` 与 `ModVersionLookup` 两个数据类都新增 `incompatibleDependencies: List<String> = emptyList()`，默认值让既有调用点不用改。

真数据形状（Embeddium 的 1.20.1 版本）：

```json
"dependencies": [
  {"version_id": null, "project_id": "4ZqxOvjD", "file_name": null, "dependency_type": "incompatible"},
  {"version_id": null, "project_id": "S1tndFDa", "file_name": null, "dependency_type": "incompatible"},
  {"version_id": "z3GJc9bS", "project_id": "9s6osm5g", "file_name": null, "dependency_type": "required"},
  {"version_id": null, "project_id": "Ha28R6CL", "file_name": null, "dependency_type": "optional"}
]
```

断言 `incompatibleDependencies == ["4ZqxOvjD", "S1tndFDa"]`、`requiredDependencies == ["9s6osm5g"]`，`optional` 两边都不进。

### 4.2 比对层：cross 是纯函数

`core/src/main/kotlin/io/github/huang404yan/platypus/core/mods/ModConflictChecker.kt`（188 行）。`cross(files, lookups, projects)` 不联网，单测直接盯它：

```kotlin
fun cross(
    files: List<LocalModFile>,
    lookups: Map<String, ModVersionLookup>,
    projects: Map<String, ModProject> = emptyMap(),
): List<ModConflict>
```

三个决定：

1. **同一个项目装了多个文件时只占一条。** 先按文件名排序、`putIfAbsent` 建 `project_id → 第一个文件` 的映射，结果稳定（不清脆地依赖 `listFiles` 的顺序）。
2. **双向声明合成一条并标 `mutual`。** key 是 `listOf(a, b).sorted().joinToString("|")`，两边各声明一次时后到的那次只把 `mutual` 置真。单向声明就按「谁说的」各占一条 —— 单向里被点名的那一方往往才是该让步的。
3. **自己声明自己跳过**（`otherId == lookup.projectId`）。

排序是 `present` 优先、再按 `sourceTitle`、`otherLabel` 小写升序：现在就可能起不来的排前面，比「作者提到但没装」要紧。

数据类的两个派生属性把界面的判断都收在 core：

```kotlin
val otherLabel: String get() = otherTitle?.takeIf { it.isNotBlank() } ?: otherProjectId
val present: Boolean get() = otherFile != null
```

`present` 的定义是 **「对面那个文件真的在这个实例里」**，不是「对面这个项目被提到过」。用 `otherFile != null` 而不是 `otherTitle != null`：名字查到了只说明项目存在，不代表装了。

### 4.3 联网层：单个失败不连坐

`check(modsDir, onProgress)` 的流水线是：读 `mods/` 下的 `.jar`（按名字小写排序）→ 逐个算 sha1（读不了就进 `failures`）→ 分批反查 → 收集 `project_id` 与 `incompatibleDependencies` 的并集去换名字 → `cross`。

两处刻意的容错：

- **换名字失败不算错**。`runCatching { ModUpdateChecker.resolveProjects(wanted) }.getOrNull().orEmpty()` —— 查不到名字只影响文案（退回显示 `project_id`），不该让整轮冲突检查失败。
- **反查解析失败才抛**。`parseVersionFiles` 返回 `Result`，失败时抛 `IOException("解析反查结果失败：…")`。网络整体失败必须抛，否则会给出「没有冲突」这种假好消息。

与 `ModUpdateChecker` 一致的地方：单个文件读不了只记进 `failures`，不中断整轮。

---

## 五、界面

模组页「已装」标签页的按钮行在「检查更新」之后插一个 `OutlinedButton`，文案在 `查冲突` / `查冲突…` 之间切换，`enabled = !checkingConflicts && snapshot != null`（没扫出模组时点了也没意义）。

`ConflictCard` 的三种状态：

| 情形 | 卡片标题 | 正文 |
| --- | --- | --- |
| 没有 `present` 的冲突 | 作者声明的模组冲突（没有两个都装着的） | 只列 `potential` 段 |
| 有 `present` | 模组冲突 · N 条已经装着（error 色） | `present` 逐条 + `potential` 段 |
| 一条都没有 | 不显示卡片，只留一行提示 | 「没查到作者声明的不兼容」 |

`present` 每条写两行：

```
· Embeddium ↔ Rubidium（双方都声明了）
    都装在这个实例里：embeddium-0.3.31+mc1.20.1.jar 与 rubidium-mc1.20.1-0.7.1.jar
```

`potential` 段标题是「作者提到但不兼容的还有 N 个项目（没装，别去装就行）：」，每条写一行 `· A × B`。「装着的」用 `↔`、「没装的」用 `×`，一眼能分开。

默认只列前 5 条（`limit = 5`），超过时给「展开全部 N 条」/「只看前 5 条」。展开状态放在 InstalledTab 层的 `remember` 里而不是卡片内部，否则每次重组都会收回。

**重新扫描会清掉旧结论**：`rescanMods()` 里在 `scanning = false` 之后清空 `conflictReport` / `conflictProgress`。文件集合变了，上一轮的「没有冲突」就过期了，留着比没有更误导。

---

## 六、实测证据

| 项目 | 结果 |
| --- | --- |
| 单测 | `ModConflictCheckerTest` **12 例**、`ModrinthMetaTest` **18 例**（+2），全量 **427 例 / 38 个测试类**，failures 0 / errors 0 / skipped 0 |
| 真网（Forge 实例 34 个 jar） | `scanned=34 recognized=30 conflicts=3 present=0 potential=3 failures=0` |
| 真网（测试实例 2 个 jar，故意放一对互斥） | `scanned=2 recognized=2 conflicts=2 present=1 potential=1`，命中的是 `Embeddium -> Rubidium present=true` |
| 界面（Forge 实例） | 点「查冲突」→ 卡片标题「模组冲突 · 0 条已经装着」，下面列三条 `potential` |
| 界面（2 个 jar 的测试实例） | 卡片标题「模组冲突 · 1 条已经装着」，第一行 `Embeddium ↔ Rubidium` + 「都装在这个实例里：embeddium-0.3.31+mc1.20.1.jar 与 rubidium-mc1.20.1-0.7.1.jar」，下方 `potential` 段 1 条（TexTrue's Embeddium Options） |
| 安卓 APK | `:androidApp:assembleDebug` EXIT=0，13,386,990 B / 142 entries；dex 里 `ModConflictChecker` 63 处、`ModConflictReport` 12、`ConflictCard` 13、`checkConflicts` 10（**未真机验证**） |

界面两张截图都验过「展开 / 收起」与「知道了」（关掉卡片）。

---

## 七、还没做到

- **只查 Modrinth 作者写的声明**。PCL 那类启动器会读 jar 里的 `mixins` 目标、`Refmap`、冲突的类名，在没有作者声明时也能发现重复注入。这一层没做。
- **没有版本区间判断**。库拿到的是「这个项目的某个版本声明不兼容那个项目」，不判断「本地那个版本是不是落在声明范围内」。作者常写「不兼容 0.5.x」，本地装 0.4 其实没事，现在照样报。
- **不查崩溃日志里的 mixin 冲突**。`docs/26` 的崩溃归因是另一条线，两边结论没有互相关联（比如「这条崩溃报告里的 `Mixin apply failed` 对应冲突卡片第三条」）。
- **不做一键处置**。卡片只提示，停用/删除仍要用户自己到列表里点。
- **安卓端只编译过，没在真机跑过**。手机模组页接的是同一份 `ModConflictChecker`（按钮 `查冲突`、卡片 `ConflictCard` 不放「展开全部」而是全列），dex 里能扫到 `ModConflictChecker` / `ConflictCard` / `checkConflicts`，但没有一份真机截图。
- **`recognized` 不到 100% 时如实报**。Forge 实例 34 个里 4 个 Modrinth 不认识（多是自编译或非 Modrinth 来源），卡片底部会写「N 个文件 Modrinth 不认识」—— 这些文件不参与冲突检查，卡片不给它们背书的结论。
