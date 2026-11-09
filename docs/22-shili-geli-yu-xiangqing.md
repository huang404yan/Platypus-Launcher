# 22 · 实例真隔离与实例详情页

> 对应 `docs/21-gongneng-xifen.md` 阶段一的 **P1**。
> 起因：`core/instance/Instance.kt:11` 的注释写着「一个实例 = 一个自包含目录」，但实际启动时
> `DesktopGameRuntime` 把 `--gameDir` 反推成 `<gameRoot>`，于是所有实例共用一份 `mods/`、`saves/`、`options.txt`。
> 只要这条不修，后面做的模组管理、整合包导入、存档管理全都会写进同一个全局目录——等于白做。
> 本篇记录隔离的边界、算法落点、旧数据怎么办，以及真机验收的判据与结果。

## 一句话设计

**`--gameDir` 指向实例自己的目录（`<gameRoot>/versions/<id>`），`libraries/` 与 `assets/` 继续共享。**

不新增 `Instance` 字段、不加「隔离开关」、不复制共享库。唯一真源仍然是 `Instance.directory`。

## 隔离前的三方矛盾

改之前代码里有三条互相打架的假设，谁都没错，合起来就是错的：

| 位置 | 假设 | 后果 |
| --- | --- | --- |
| `core/instance/Instance.kt:11` 注释 | 「一个实例 = 一个自包含目录，导入导出就是复制目录」 | 只是注释，没人实现 |
| `app-desktop/.../DesktopGameRuntime.kt` 的 `resolveLayout` | `gameDir = versionDir.parentFile?.parentFile`（即 `<gameRoot>`） | **所有实例共用一份 mods/saves** |
| `core/mods/ModScanner.kt:60` 注释 | 「mods 在游戏目录下，不在 `versions/<id>` 下 —— 所有实例共享」 | 把上面那条当成了设计 |
| `core/instance/InstanceDeleter.kt` | `File(dir, "mods")`、`File(dir, "saves")`（`dir` = 版本目录） | 按「实例目录 = 版本目录」统计，与运行时不符 |

第四条其实**已经站在正确的一边**：删除器一直以为实例是自包含的。所以这次不是发明新语义，是把运行时不符的那一方拉回来对齐。

## 布局算法：`core/instance/InstanceLayout.kt`

算法搬进 `core` 而不是留在 `app-desktop`，理由：安卓端迟早要跑同一套（S1 spike），而且 `core` 才能单测。

```kotlin
data class InstanceLayout(
    val versionDir: File,   // <gameRoot>/versions/<id>
    val gameDir: File,      // == versionDir（P1 的关键：不再是 <gameRoot>）
    val gameRoot: File,     // <gameRoot>
    val librariesDir: File, // <gameRoot>/libraries   （共享）
    val assetsDir: File,    // <gameRoot>/assets      （共享）
    val nativesDir: File,   // <版本目录>/natives/<id>  （按实例分）
)
```

`InstanceLayouts.of(instance)` 与 `InstanceLayouts.of(versionDir: File)` 两条入口，前者只是后者的一层壳。

### `gameRootOf` 只在父目录真的叫 `versions` 时上溯

```kotlin
fun gameRootOf(versionDir: File): File {
    val versions = versionDir.parentFile ?: return versionDir
    if (!versions.name.equals(VERSIONS_DIR_NAME, ignoreCase = true)) return versionDir
    return versions.parentFile ?: versionDir
}
```

为什么这么保守：用户可能把游戏目录填成一个**不是标准布局**的地方（PojavLauncher 的目录、自己拼的目录）。
如果无条件往上走两级，就会把 `libraries/assets` 指到别人的目录里去，报出来的错误是「缺库」，
用户完全看不出是路径猜错了。宁可把 `libraries/assets` 指到实例目录下（找不到 → 启动时明确报缺库），
也不要凭空猜一层。这一条有单测（`InstanceLayoutTest` 的「父目录不叫 versions 时不上溯」）。

### 为什么不加新字段

考虑过给 `Instance` 加 `gameDirOverride` 或 `isolated: Boolean`。放弃的理由：

1. `Instance` 是 `@Serializable` 且写进 `<gameRoot>/platypus/instances.json`。加字段要同时考虑旧文件没有该字段时的默认值、以及未来要不要迁移。
2. 字段一旦存在，就必然出现「字段说 A、目录实际是 B」的漂移，而 `directory` 已经能唯一确定布局。
3. 隔离**不做开关**。开关意味着两条代码路径都得测，而共享 mods 这个能力用户从没要过。

### 为什么不走 `-Dminecraft.gameDir` 或切换 cwd

- `-Dminecraft.gameDir` 在这个版本链里不是官方参数，行为依版本而异（1.12 之前认，之后不认），不可靠。
- 切 `ProcessBuilder.directory()` 只影响 cwd，而游戏判断「存档放哪」看的是 `--gameDir` 参数，不是 cwd。
- 现成的路就是**直接把 `--gameDir` 指对**：`LaunchArguments` 的 `game_directory` 本来就读 `context.gameDir`，改一个值即可。

## 改了哪些地方

| 文件 | 改动 |
| --- | --- |
| `core/instance/InstanceLayout.kt` | 新增（74 行）：布局数据类 + `of()` + `gameRootOf()` + `perInstanceDirs()` |
| `core/instance/InstanceDetails.kt` | 新增（128 行）：实例详情快照（版本 json 摘要 + 八个目录的件数体积 + 警告），全程不抛异常 |
| `core/mods/InstanceMods.kt` | 新增（129 行）：按实例算 `mods/` 目录、识别旧的共享 `mods/`、把旧模组**复制**进实例 |
| `core/mods/ModScanner.kt` | 只改注释：说明 P1 之后传进来的是实例目录，谁当那个目录由 `InstanceMods` 决定 |
| `app-desktop/.../DesktopGameRuntime.kt` | `resolveLayout` 换成 `InstanceLayouts.of`；`VersionResolver.load` 改传 `gameRoot`（不是 gameDir）；natives 用 `layout.nativesDir` |
| `ui/.../InstanceDetailPanel.kt` | 新增（319 行）：实例详情页 |
| `ui/.../InstancePage.kt` | 实例卡片加「详情」按钮；`detailKey` 状态 + 面板分支 |
| `ui/.../ModsPage.kt` | 重写（604 行）：先选实例，再看这个实例的 `mods/`；旧共享 mods 有「搬进这个实例」按钮 |
| `androidApp/.../ModsPage.kt` | 同步改成实例口径（529 行） |
| `app-desktop/.../InstanceIsolationSmoke.kt` | 新增（203 行）：隔离的无头验收 |

### 一个容易踩的坑：`VersionResolver.load` 必须传 `gameRoot`

它内部会拼 `<传入目录>/versions/<id>/<id>.json`。隔离之后 `layout.gameDir` 已经是 `versions/<id>` 本身，
再传它就会去找 `versions/<id>/versions/<id>`。所以那一处必须显式传 `layout.gameRoot`——
代码里留了注释，因为在两行相邻的相似参数之间，这个错误不会报编译错，只会报「版本文件不存在」。

## 旧数据怎么办

隔离之后，原来装在 `<gameRoot>/mods/` 里的模组**不再被任何实例看见**。这是这次改动唯一的破坏面。

处理方式：**复制，不移动**。

- `InstanceMods.scan(instance)` 同时扫实例的 `mods/` 和 `<gameRoot>/mods/`，后者在界面上叫「共享 mods/ 里还有 N 个模组（隔离前装的）」。
- 「搬进这个实例」调 `InstanceMods.adoptLegacy(instance)`，逐个 `copyTo`，**同名默认跳过**（进 `skipped`），失败进 `failed` 并带上原因。
- **源文件一律不删**。理由：它可能还要给别的实例用；而且删用户文件这种操作不该由「点了下按钮」触发。
- 特殊情形：目录不在 `versions/` 下时（`gameRootOf` 退回实例目录），共享 mods 与实例 mods 是同一个目录，此时 `legacyScan` 置空，界面不会出现「把模组搬给自己」。

存档（`saves/`）不自动搬。它涉及世界数据，用户在详情页能看见「存档 N 个 / X MB」，真要搬自己去资源管理器（后续 P2/P3 里会做实例内的存档管理）。

## 实例详情页

原来的实例卡片只有四个按钮：去启动 / 改内存 / 移除登记 / 删除文件。它能「看」Java 路径，不能「改」；
装完 NeoForge 想确认装对没有，只能去翻 `versions/`。

详情页做成**钻进一个实例**，不是第九个一级导航入口（决策见 `docs/04-juece-jilu.md` D17）。
面板顶部有「← 返回实例列表」，左侧导航仍然高亮「实例」。

六张卡片：

| 卡片 | 内容 |
| --- | --- |
| 这个实例在哪 | 版本目录 / 游戏根目录 / 「libraries 与 assets 是共享的」说明 + 「打开实例目录」「打开游戏根目录」两个按钮 |
| 版本文件 | 版本 json 有无、client jar 有无与字节、mainClass、继承自、库条目数、需要的 Java |
| Java | 当前设置的路径 + 本机探测到的全部运行时，精确主版本那条标「← 这个版本要求的」；没有精确版本时红字写明「本启动器不做高版本顶替」 |
| 内存与启动参数 | 内存（MB）+ 额外 JVM 参数（一行一个） |
| 占了多少地方 | mods / saves / config / logs / crash-reports / resourcepacks / shaderpacks / screenshots 八行的件数与体积 + 合计 |
| 注意 | 版本 json 缺失、解析失败等警告 |

保存走 `instance.copy(...)` + `backend.instances.save(updated)`，即写进 `instances.json`。

`InstanceDetailsLoader.load()` **全程不抛异常**：json 缺失、json 坏了、目录读不了，都变成 `warnings` 里的一句话。
理由是这页是用来「看哪里不对」的，它自己先崩掉就失去了意义。

## 验收：`isolationSmoke`

不是为了证明「能启动」（`forgeE2e` / `neoForgeE2e` 已经证明了），而是证明**启动之后文件真的落在实例目录**。

### 判据全在磁盘上

启动**前**记录：

- `<gameRoot>/logs/latest.log` 的 `lastModified`
- `<gameRoot>/saves/` 的文件数

启动、进主菜单、停掉，然后断言五条：

```
① <版本目录>/logs/latest.log 存在
② 它的字节数 > 0
③ 它的内容含 "Setting user:" / "Narrator" / "Backend library" 之一
④ <gameRoot>/logs/latest.log 的 mtime 与启动前相同
⑤ <gameRoot>/saves/ 的文件数与启动前相同
```

④⑤ 是这次改动的**反向证明**：如果 `--gameDir` 还指回 `<gameRoot>`，这两条必然失败。

用法：

```bash
gradle :app-desktop:isolationSmoke -PisoDir=dist/_test-mc/_nf1211 -PisoInstance=neoforge-21.1.265 -PskipWix
```

参数：`-PisoDir`（必填）/ `-PisoInstance`（空则自动挑第一个非原版实例）/ `-PisoUser`（默认 Dev）/ `-Pe2eTimeoutMs`（默认 300000）。
退出码：0 通过 / 2 找不到实例 / 3 没 Java / 4 没进主菜单 / 5 隔离断言失败。

### 实测结果（2026-11-10，`dist/_iso_run2.log`）

| 项 | 值 |
| --- | --- |
| 实例 | `neoforge-21.1.265` |
| versionDir = gameDir | `...\_nf1211\versions\neoforge-21.1.265` |
| gameRoot | `...\_nf1211` |
| libraries / assets | 都指 gameRoot，`exists=true` |
| natives | `...\versions\neoforge-21.1.265\natives\neoforge-21.1.265` |
| 探测到 Java | 5 个（25 / 25 / 21 / 17 / 16） |
| 需要的 Java | 21（从沿 `inheritsFrom` 合并后的版本 json 读的） |
| 用的 Java | `runtime/java-runtime-delta/bin/java.exe` |
| 进主菜单 | `MENU REACHED +9599 ms`（`Setting user: Dev`） |
| 实例目录 logs/latest.log | **存在，9291 B** |
| 共享 latest.log mtime | before = after = 1794235823623 |
| 共享 saves 文件数 | before = after = 0 |
| 断言 | **PASS 5 条，failures=0** |

### 踩过的坑：Java 需求版本读错文件

第一版直接读 `<gameRoot>/versions/<id>/<id>.json` 的 `requiredJavaMajor`。NeoForge 的版本 json 自己**不带** `javaVersion`
（它在 `neoforge-21.1.265.json` 里只有 `inheritsFrom: 1.21.1`），于是退回默认的 8，然后满机找不到 Java 8，报「本机没有这个实例能用的 Java」，退出码 3。

修法：改用 `VersionResolver.load(gameRoot, id)?.version?.javaVersion?.majorVersion`——沿 `inheritsFrom` 合并之后才拿得到 1.21.1 的 Java 21。
**这条也适用于任何「带继承链的版本要读 javaVersion」的地方**，别直接 parse 子版本的文件。

## 单测

| 测试类 | 例数 | 覆盖 |
| --- | --- | --- |
| `InstanceLayoutTest` | 10 | 版本目录就是实例目录 / 上溯两级得游戏根 / 共享目录在根下 / natives 按实例分 / 父目录不叫 versions 时不上溯 / 顶层目录不崩 / versions 大小写不敏感 / 两种入口等价 / 带引号的目录字符串 / 按实例分开的目录清单 |
| `InstanceDetailsTest` | 6 | 读得出 mainClass 与 Java 版本 / 实例目录就是游戏目录 / 八个目录的件数与体积 / 没有 json 时给警告不抛异常 / json 坏了也只是警告 / 带引号的目录 |
| `InstanceModsTest` | 11 | 实例 mods 在实例目录下 / 两个实例互不可见 / 只看得到自己的模组 / 认得出旧共享 mods / 没有旧的不误报 / 非标准布局时两者重合 / 搬是复制源文件还在 / 带上停用的模组 / 同名默认不覆盖 / 要求时覆盖 / 没有旧 mods 时报错 |

全量 `:core:test`：**27 个测试类 / 272 例 / failures 0 / errors 0 / skipped 0**。

## 已知取舍

| 取舍 | 代价 | 为什么接受 |
| --- | --- | --- |
| `libraries/` 与 `assets/` 不按实例复制 | 实例目录不是 100% 自包含，导出实例时得另带库 | 一份 assets 就是 700 MB 上下，按实例复制会让磁盘立刻爆掉；D16 里记了「暂不复制」 |
| 旧的 `<gameRoot>/mods` 不自动搬 | 老用户升级后看不到原来的模组 | 自动搬会改变用户磁盘上的文件；界面给了「搬进这个实例」按钮，且是复制 |
| 不做隔离开关 | 没法「共用一份 mods 给所有实例」 | 用户从没要过；两个路径都要测，成本翻倍 |
| 详情页不显示日志内容 | 查问题还得去「启动」页 | 日志是启动页的职责；详情页只管「这个实例是什么配置」 |
| 删除器的判断没变 | 仍然按 `<版本目录>/mods` 统计 | 隔离之后这条终于自洽了，不改反而是对的 |
