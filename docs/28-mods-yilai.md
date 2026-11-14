# 28 · 模组依赖求解（递归展开 + 一次装齐）

记录日期：2026-11-13。本文只写**已经跑通过的事实**与**还没做到的部分**。

> 编号说明：01–27 已占用，本轮取 28。模组页的整体介绍见 `docs/18`，本文只写依赖这一块。

---

## 一、一句话

装上 Iris 之后，界面自动列出「Sodium 是它的前置」，点「一次装齐」就把 Iris 缺的那一串前置按序下完；查不到、没适配、已装过的逐条说清原因，不会汇总成一句「装好了」。

---

## 二、为什么 Modrinth 的依赖不能直接装

`GET /v2/project/iris/version` 返回的 `dependencies[]` 长这样：

```json
{ "version_id": null, "project_id": "AANobbMI", "file_name": null, "dependency_type": "required" }
```

只有 `project_id`。**没有 slug、没有版本号、没有下载地址。** 要把它变成能下载的东西，至少还要两次请求：

1. `GET /v2/projects?ids=["AANobbMI"]` → 拿到 slug（`sodium`）与标题；
2. `GET /v2/project/sodium/version?game_versions=["1.20.1"]&loaders=["fabric"]` → 版本列表 → `ModrinthMeta.pickBest` 挑一个。

而且第二层前置还可能带自己的依赖，所以这必须写成递归，不是一次查询。

---

## 三、两个 API，各管一段

`core/src/main/kotlin/io/github/huang404yan/platypus/core/mods/ModDependencyResolver.kt`

| 函数 | 输入 | 输出 | 用途 |
| --- | --- | --- | --- |
| `rootsOf(projectIds, source, onStep)` | 一串 `project_id` | `Pair<List<DependencyRoot>, List<SkippedDependency>>` | 「装完发现还缺前置」这条路只拿得到 id，先补齐成挑好文件的根 |
| `solve(roots, installedProjectIds, source, onStep)` | 根 + 已装集合 | `DependencyPlan` | 按层 BFS 递归展开，产出可展示、可按序安装的计划 |
| `install(gameDir, plan, ...)` | 计划 | `DependencyInstallOutcome` | 按计划顺序下载，单个失败不中断 |
| `installedProjectIds(modsDir)` | 模组目录 | `Set<String>` | jar 的 sha1 反查 Modrinth `project_id` |

`rootsOf` 与 `solve` 的返回值第二项都是「跳过项」，但含义不同：前者是根这一层就没排进来的，后者是树里被跳过的。界面把两者合并显示（`solveDependencies` 里 `skippedRoots + solved.skipped`），这样用户才知道「点了装齐为什么还是没齐」。

---

## 四、为什么用 sha1 反查，不读 jar 里的 modId

`ModScanner.describe` 能从 `fabric.mod.json` 读到 `id`（比如 Iris 的 id 是 `iris`），但 **modId 与 Modrinth 的 project_id 不是一回事**：很多模组的 modId 与 slug 完全不同，而依赖表给的是 project_id。

Modrinth 自己给的主键是文件 sha1。所以 `installedProjectIds` 走的是：

```kotlin
ModUpdateChecker.hashAll(jars)                    // 本地算 sha1
→ POST /v2/version_files  {"hashes": [...]}       // 批量反查
→ 响应里的 project_id
```

一次请求能带一批，不是每个 jar 一次。网络失败返回空集合而**不抛**：算不出「已装什么」只会导致多装一份，不该让整个安装失败。

---

## 五、三条硬边界

不设边界的话，一个整合包式模组能把界面挂死。

| 边界 | 值 | 触发时做什么 |
| --- | --- | --- |
| 深度封顶 | `MAX_DEPTH = 8` | 更深的记一条 warning「X 在依赖树里超过 8 层，没有继续展开」，已排进计划的不受影响 |
| 数量封顶 | `MAX_MODS = 64` | warning「依赖超过 64 个，只展开了前 N 个」 |
| 成环保护 | `rounds > MAX_MODS * 4` | warning「依赖关系疑似成环，已停止展开」 |

深度封顶之外还有一道：**按 slug 小写去重**（`indexBySlug`）。同一个库被十几个模组依赖是常态，重复下载会在 `mods/` 里躺两份，Forge 直接报 duplicate mods 起不来。命中去重时不重新展开子依赖，只把「谁还需要它」追加进 `requiredBy`。

---

## 六、界面接线（`ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/ModsPage.kt`）

```
装一个模组成功
  → solveDependencies(根标题, result.requiredDependencies, 实例目录, 加载器, result.optionalDependencies)
      ├─ 必需前置为空、只有可选 → suggestOptional()
      │     └─ 只为「作者建议一起装」那一行做准备，不产生安装计划
      └─ 必需前置非空
            ├─ ModrinthDependencySource(游戏版本, 加载器)   ← 版本+加载器锁死在这一层
            ├─ rootsOf()
            ├─ installedProjectIds()  ← 已装的不再下一遍
            └─ solve()  ← 必需树展开完再回头解析可选依赖
  → depPlan 非空（mods / skipped / optional 至少一段有内容）→ 界面上出现 DependencyPlanCard
```

`ModInstallResult` 因此多带一个 `optionalDependencies`：没有它，「装完一个只标了 optional 的模组」这条路会把提示整个丢掉——以前 `missing.isEmpty()` 直接 return。

版本与加载器固定在 `ModrinthDependencySource` 这一层，因为依赖树里**每一层都必须和根模组吃同一套条件**——给 1.20.1 Fabric 的模组去装一个 1.21 的库，装完照样起不来。

卡片内容：

- 标题「前置依赖 · N 个要保持顺序安装」（只有可选依赖时不写这行，否则会变成「前置依赖 · 0 个」）；
- 每个模组按 `depth` 缩进，前缀 `└ `，右边跟版本号；
- `requiredBy` 非空时缩进一层写「被 X、Y 需要」；
- 跳过项单独一段：「跳过 N 个：· 标题 —— 原因（谁需要）」；
- 可选依赖单独一段：「可选依赖 · N 个（不装也能启动）：· 标题 版本 —— 谁建议一起装」，见 8.4 节；
- warnings 用 error 色；
- 固定提示「不带前置启动会崩，或者进游戏后功能是空的；标了可选的不装不影响启动。」；
- 按钮「先不管」/「一次装齐」（只看 `plan.mods`；只有可选依赖时整颗「一次装齐」不出现——留个灰按钮比不留更让人疑惑）。

---

## 七、失败不连坐

`install()` 里循环里每一个都单独 try/catch：

- 成功 → `installed`；
- 失败 → `FailedMod(mod, reason)`，**继续下一个**。一个前置的版本被作者撤了，不该让其余九个白装；
- `CancellationException` 重新抛出（页面切走时要真的停下，不能当成「安装失败」写进结果）。

装完界面把失败项留在计划里（`depPlan = plan.copy(mods = failed.map { it.mod }, skipped = emptyList())`），用户能再点一次「一次装齐」，不用重新展开整棵树。

---

## 八、实测

### 8.1 真网两层展开（2026-11-13，临时 smoke，跑完即删）

直接调 Modrinth：

```
ModInstaller.search("iris", "1.20.1", FABRIC, 3) → hits=3
  iris / Iris Shaders / YL57xq9U
  geckoanimfix / GeckoAnimFix
  iris-flw-compat / Iris FLW Compat

rootsOf([YL57xq9U]) → iris@1.7.6+1.20.1
solve(...) → depth=0 Iris Shaders 1.7.6+1.20.1  deps=1
             depth=1 Sodium mc1.20.1-0.5.13-fabric  deps=0
skipped=0  warnings=0
```

对照：`sodium` 自身 `deps=0`，计划里只有 1 个 `depth=0` 的 `mc1.20.1-0.5.13-fabric` —— 说明没有依赖时不会硬造一层。

### 8.2 界面点击（app-image 双击，2026-11-13 22:4x）

实例：`dist/_testmc/versions/1.20.1-fabric-0.15.11/`（补建 `libraries assets mods` 才被 `InstanceScanner.isGameDirectory` 认）。

| 步骤 | 实测结果 |
| --- | --- |
| 搜 `iris` | 结果 3 条，Iris Shaders 作者 IMS21、2023/9/9 更新、20 个文件 |
| 点「装进 mods」 | 落盘 `iris-1.7.6+mc1.20.1.jar` 2,726,899 B |
| 切「已装」 | 出现前置依赖卡片，列出 `Sodium mc1.20.1-0.5.13-fabric`，标「被 Iris Shaders 需要」 |
| 点「一次装齐」 | 落盘 `sodium-fabric-0.5.13+mc1.20.1.jar` 971,552 B |

两个 jar 同时在 `mods/` 里，即证明递归展开 + 一次装齐端到端可用。

### 8.3 单测

`ModDependencyResolverTest.kt` 31 例（含 8.4 那 8 例），用 `FakeSource` 塞假 `ModDependencySource`（带 `projectCalls` / `versionCalls` 计数），**不联网**：

没有前置时只有根 / 一层展开 / 前置的前置也展开 / 多来源只排一次并记两个来源 / 成环能停 / 已装跳过 / 查版本抛异常不中断 / 无适配版本进 skipped / 查不到项目退回 project_id / 项目查询整体失败不崩 / 深度超限停止 / 数量超限停止 / 安装中途失败不影响后面 / 全成功 complete 为真 / 按序报进度 / 空白依赖不拼坏字符串 / `rootsOf` 8 例。

全量 `:core:test` 实测 **40 个测试类 / 459 例 / 0 失败 0 错误 0 跳过**（旧基线 40 类 448 例）。

### 8.4 可选依赖（2026-11-14）

解析层（`ModrinthMeta`）：`ModVersionFile` 与 `ModVersionLookup` 各加 `optionalDependencies: List<String> = emptyList()`，`parseVersions` / `parseVersionFiles` 里 `filter { dependency_type == "optional" } → mapNotNull project_id → distinct()`。

求解层（`ModDependencyResolver`）：

- `OptionalDependency(projectId, slug, title, file, suggestedBy)`，`DependencyPlan.optional` 默认空表，`dependencyCount` 不变（可选不算「缺前置」）；
- `solve()` 里可选依赖走**独立队列** `optionalQueue`，等必需树全部展开完再解析——这样「它是不是已经在必需树里了」才判断得准；**不递归展开可选依赖自己的前置**，否则等于把「可选」当「必需」用；
- `MAX_OPTIONAL = 24`：一个模组挂四十个可选项时，卡片不能撑成一屏；
- 已装过 / 已在必需树里 / 同 slug 重复 —— 三种都跳过，不占「跳过」区；
- 查版本抛异常或没适配版本：**静默略过**，可选依赖本来就能不装，写进跳过区反而让人以为装错了；
- 新增公开入口 `suggestOptional(rootTitle, projectIds, source, installedProjectIds, plannedSlugs, onStep)`：给「没有必需前置、只有可选依赖」那条路用（`solve()` 在那条路上没有根可展开）。

界面层（`ModsPage.kt`）：`ModInstallResult` 多带 `optionalDependencies`，`install()` 把它一并传给 `solveDependencies`；装上后提示分三档——有必需前置写「正在展开整棵依赖树」，只有可选写「它自己就能跑，作者另外标了 N 个建议一起装的」，都没有就只写「。」

`:core:test` 全量 **40 个测试类 / 459 例 / 0 失败 0 错误**（加 optional 之前是 40 类 448 例：`ModDependencyResolverTest` +5 例 solve 路径、+3 例 suggestOptional、`ModrinthMetaTest` +3 例）。`:ui:compileKotlinJvm` BUILD SUCCESSFUL。界面点击验证还没做。

---

## 九、还没做到

- **不解决版本冲突**：A 要 B 的 1.x、C 要 B 的 2.x 时，按先来的装，后一个记「查版本失败」或装成同一份——没有真正的约束求解。
- **`embedded` 依赖仍然完全忽略**：Modrinth 的 `dependency_type` 四类里，`required` / `optional` / `incompatible` 现在都解析了，只剩 `embedded`（jar 内已自带那份库）没有分支，也不需要——它本来就不该被单独下载。
- **依赖求解不处理 `incompatible`**：这一层只管「缺什么要补」；互斥声明后来由模组页的「查冲突」单独检查（`docs/30`），两条线各管一头，不在求解阶段拦。
- **已装的不升级**：命中 `installedProjectIds` 直接跳过，哪怕装的是旧版。
- **没有「取消安装」按钮**：页面切走才会因协程取消停下。
