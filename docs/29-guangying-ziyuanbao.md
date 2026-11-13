# 29 · 光影与资源包（扫本地 + 从 Modrinth 装 + 启停与删除）

记录日期：2026-11-13。本文只写**已经跑通过的事实**与**还没做到的部分**。

> 编号说明：01–28 已占用，本轮取 29。模组页的同类机制见 `docs/18`、`docs/23`、`docs/28`。

---

## 一、一句话

左侧导航多一项「光影资源包」，下面分「光影」「资源包」两个二级页：从实例的 `shaderpacks/` 或 `resourcepacks/` 扫出已装的，勾选框就是启停（改文件名），删除要二次确认；右边从 Modrinth 搜、点一下就装进对应目录。

---

## 二、为什么分成两个二级页，而不是一个「包管理」

光影和资源包在磁盘上只差一个目录名，但**认它们的方式完全不同**：

| | 目录 | 怎么认 | Modrinth 的 `project_type` | 版本查询的 `loaders` |
| --- | --- | --- | --- | --- |
| 光影 | `shaderpacks/` | zip 里有没有 `shaders/` | `shader` | `["iris","optifine"]` |
| 资源包 | `resourcepacks/` | 有没有 `pack.mcmeta` | `resourcepack` | `["minecraft"]` |

两边都写成常量放在 `PackKind` 枚举里（`core/src/main/kotlin/io/github/huang404yan/platypus/core/packs/PackScanner.kt:19`），不让调用点各写各的字符串：

```kotlin
enum class PackKind(val dirName: String, val label: String, val projectType: String) {
    SHADER("shaderpacks", "光影", "shader"),
    RESOURCE_PACK("resourcepacks", "资源包", "resourcepack"),
}
```

**`loaders` 那两个值不是猜的。** 照模组的 `fabric` 去查光影，一条都查不到——Modrinth 给光影标的加载器是 `iris` / `optifine`（它俩是能读这个格式的渲染器），资源包标的是 `minecraft`。光影写成两个值是 OR 语义：只写 `iris` 会漏掉只标了 `optifine` 的老包，而 Iris 本体能读绝大多数 OptiFine 光影。

另一个坑在搜索侧：`ModrinthMeta.searchUrl` 原来按 `LoaderKind` 拼 `categories:fabric` 这种 facet。传 `LoaderKind.VANILLA` 让 `loaderName` 返回 null，就不会被加上错误的 facet，同时靠新加的第 6 个参数 `projectType` 限定类型。

---

## 三、三处改动（core 两个新口子）

| 位置 | 改动 |
| --- | --- |
| `ModrinthMeta.searchUrl` | 第 6 参数 `projectType: String = PROJECT_TYPE_MOD`，另有 `PROJECT_TYPE_SHADER` / `PROJECT_TYPE_RESOURCEPACK` 两个常量 |
| `ModrinthMeta.versionsUrl` | 加 `loaders: List<String>? = null`；非空时直接用它，绕过 `loaderName` 的单值拼装 |
| `core/packs/` 三个新文件 | `PackScanner`（扫 + 读元数据）、`PackFileOps`（启停 / 删除）、`PackInstaller`（搜 / 查版本 / 下载） |

`versionsUrl` 为什么要加参数而不是在调用点拼 URL：现有签名只接受单个 `LoaderKind`，而光影需要 `loaders=["iris","optifine"]` 这种数组，改签名比在界面层手拼 URL 更不容易拼错。

---

## 四、元数据读不出来不算错误

用户从群里拿到的 zip 常常没有 `pack.mcmeta`，游戏也可能照样认。所以 `PackScanner.describe` **只把原因写进 `PackEntry.problem`，条目照样列出**：

```kotlin
val meta = runCatching { readMeta(kind, file) }.getOrNull()
    ?: return base.copy(problem = problemFor(kind, file))
```

对应的两句说明：

- 光影 → 「zip 里没有 shaders/ 目录（游戏的「光影」页面多半不认它）」
- 资源包 → 「zip 里没有 pack.mcmeta（游戏不认这个资源包）」

让人自己决定删不删，比直接吞掉更诚实。另外 `stripFormatting` 会去掉 `§` 颜色码并把换行折成空格：资源包的 `description` 基本都是 `§a■ 1.11.2 BASE§8\n■ By FreshLX` 这种，不清掉在界面上就是方块。

`pack_format` 也有个兜底：新版格式把范围写在 `supported_formats` 里，`pack_format` 可能缺失，取不到就退回 `min_inclusive`。

---

## 五、启停为什么改文件名，文件夹形态为什么拒绝

机制和模组一样：停用 = 把 `x.zip` 改成 `x.zip.disabled`。不用配置文件记状态的理由见 `docs/23`——用户在资源管理器里也看得见、也能自己改回来。

**不复用 `ModFileOps`**：那边把 `.jar` 写死在名字拼装里（`x.jar` ↔ `x.jar.disabled`），拿它改 `x.zip` 会变成 `x.zip.jar`。两边规则几乎一样但后缀不同，复制一份比加参数更不容易改错（`PackFileOps.kt:13-15` 有注释说明这一点）。

停用尾巴认三种，**顺序先长后短**，避免 `.zip` 被提前剥掉：

```kotlin
private val DISABLED_SUFFIXES = listOf(".zip.disabled", ".zip.bak", ".disabled")
```

文件夹形态的包**明确拒绝停用**并抛错说明原因：游戏对目录只看名字不看后缀，改名成 `x.disabled` 依然是个目录，照样会被列出。与其假装停用了，不如让用户把整个文件夹移出去或先压缩成 zip。

删除走 `PackFileOps.delete`，删不掉就抛错（文件被游戏占用时如实说），界面上是 AlertDialog 二次确认。

---

## 六、实测证据（2026-11-13）

| 项 | 实测 |
| --- | --- |
| 单测 | 全量 `:core:test` **37 类 410 例**全绿；本轮新增 `PackScannerTest` 11 例 + `PackFileOpsTest` 8 例 + `ModrinthMetaTest` 补 4 例 |
| 真网搜索 | 搜 `complementary` → 光影 24 个版本，最新 `ComplementaryReimagined_r5.9.3.zip` |
| 界面安装 | 点「装进 shaderpacks/」→ 落盘 `D:\ruanjian\youxi\pcl2ziyong\.minecraft\versions\1.20.1-Forge_47.4.28\shaderpacks\ComplementaryReimagined_r5.9.3.zip`，**553 397 B**，界面提示「已装 ComplementaryReimagined_r5.9.3.zip（r5.9.3，540 KB）」 |
| 已装列表 | 「已装 · 3 个」，三行分别 540 KB / 9.3 MB·文件夹 / 540 KB；文件夹形态那行显示「· 文件夹」 |
| 旧包兼容 | 该实例原有 `ComplementaryUnbound_r5.9.3.zip` 与解压出来的同名文件夹都被正确列出，后者标「· 文件夹」 |

---

## 七、还没做到

- **勾选停用那一下没在界面里验成**：点 Checkbox 后 `shaderpacks` 目录没有变化。怀疑是点空了（截图坐标 (479,1030) vs 屏幕坐标 (519,1070)），`PackFileOps.setEnabled` 本身有 8 例单测覆盖改名逻辑，但**「界面勾一下真的改名」这条还没有实测证据**，下次开窗口第一件事补验。
- 只有桌面端（jvmMain）做了这一页，**安卓端没有**。
- 不读光影包内部信息（比如作者、版本号），只靠文件名与 Modrinth 返回的 `versionNumber`。
- 不支持「从本地 zip 导入」——只能从 Modrinth 装。
- 资源包的 `pack_format` 只显示不比对本机版本，装了不匹配的资源包不会有任何提示。
