# 16 · 实例删除与版本目录清理

> 补齐「装了不能删」的缺口：之前实例页只有「移除登记」（把 `instances.json` 里的条目删掉，文件全留在磁盘），
> 装多了想清理磁盘没有入口。这一篇记录删除的语义、三种拒绝情况、存档保留策略，以及无头冒烟的输出。

## 一句话设计

**同卷改名 → 递归删 → 失败改名回来**，外加三种「根本不许删」的拒绝。不引第三方回收站库。

## 为什么不引第三方回收站库

删文件进回收站，两条平台路线都要求平台代码：

| 平台 | 官方路子 | 代价 |
| --- | --- | --- |
| Windows | `SHFileOperation` / `IFileOperation`（COM） | 要 JNA 或手写 JNI，桌面端多一份原生依赖 |
| Linux | XDG Trash 规范（`~/.local/share/Trash`） | 要自己实现 `.trashinfo`、跨卷时退化成复制 + 删除 |
| Android | `MediaStore` / `StorageVolume` | 又一套 API，且 Android 端的游戏目录本来就在应用私有区 |

本项目是**纯 Kotlin + 零第三方依赖**（`core` 连 kotlinx-serialization 都只在 core 内用），
为了一个删除按钮引 JNA，等于给发行包塞进一份平台绑定。所以退一步用文件系统自带的能力：

1. **改名** `<dir>` → `<dir>.deleting-<yyyyMMdd-HHmmss>`。同卷改名是原子的，不复制字节，失败也无损。
2. **递归删** 改名后的目录。用 `File.walkBottomUp()` 逐项 `delete()`——叶子先删、目录后删，
   这是 JDK 官方推荐的删除遍历顺序；**不调用任何外部命令**（`rmdir`/`del` 在 Windows 与 Linux 上参数完全不同，
   还要处理引号与编码，得不偿失）。
3. **失败回滚** 任何一个文件删不掉，就把目录名改回去，并把存档搬回原位，把「哪个文件、什么错」原样返回。

这样做的实际收益：**改名之后再删，用户随时能看见「我正在删什么」**；中途失败目录还在，
名字还能改回来；真要找回，至少存档没丢（见下）。

代价说清楚：**删掉之后就真的没了，没有回收站可恢复**。所以界面上必须先算计划、再弹确认框，
把绝对路径、文件数、总字节数摆给用户看——这是「没有回收站」这一选择的补偿。

## 三种拒绝

`InstanceDeleter.inspect()` 按固定顺序判断，任一命中就返回 `refusal` 且 `deletable == false`：

| 枚举值 | 触发条件 | 为什么必须拒绝 |
| --- | --- | --- |
| `OUTSIDE_GAME_ROOT` | 目标 `canonicalFile` 不在 `<gameRoot>/versions/` 之内（`../`、绝对路径、空 id） | 删错目录是不可逆的。先 canonical 再比较，符号链接与 `..` 都绕不过去 |
| `VERSION_DIR_MISSING` | 目录不存在 | 多半是已经删过一次或 id 打错；报「已删除」会骗人 |
| `SHARED_BY_INSTANCES` | `instances.json` 里还有**另一条**记录（id 不同）的 `directory` 指向同一目录 | 删了会连累另一个实例。比较用与 `instanceKey` 一致的反斜杠/大小写归一化 |

顺序是刻意的：**越界判断先于存在性判断**。否则 `../某目录` 恰好存在时，会被当成一个正常的删除目标。

`inspect()` 的 `knownInstances` 参数：界面手上已经有 `backend.instances.list()` 的结果，
而 `inspect` 是同步函数调不了 suspend 的 `list()`，所以允许把内存里那份传进去；传 `null` 时
读 `<gameRoot>/platypus/instances.json`（`JsonInstanceRepository.snapshot()`，同步只读）。
两条路径都有测试盯着。

## 存档保留策略

**`saves/` 默认不删。** 理由很直接：游戏文件、libraries、assets 都能重新下载，
**存档和建造的东西下载不回来**。

`delete(plan, includeSaves = false)`（默认）时的顺序：

1. 把 `<dir>/saves/` **同卷改名**到 `<gameRoot>/platypus/saves-backup/<safeId>-<时间戳>/`。
   放在 `platypus/` 下是因为那里已经是本启动器的私有区（`instances.json`、`settings.json` 都在），
   用户清理别的地方不会顺手把它删了。
2. 搬不动（被占用）就**立刻返回失败，什么都不动**——不会出现「游戏文件删了、存档卡在原地」的半吊子状态。
3. 再改名 + 递归删。
4. 删除失败回滚时，存档也搬回 `<dir>/saves/`。

`DeleteOutcome.summary` 里会写明搬到了哪个绝对路径，界面把它显示在提示行上。

`includeSaves = true`（界面上的「连存档一起删」勾选框）时跳过第 1 步，`saves/` 跟着版本目录一起没。

`moveToRecycleBin = true` 是留给以后的「移到回收站」按钮：只改名、不递归删，`deletedFiles == 0`。
本次没有接进界面，但实现与测试都在。

## 界面

`ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/InstancePage.kt`：

- 实例卡片上，「移除登记」旁边多一个 **「删除文件…」**（已登记与仅扫描到的实例都有）。
- 点击后走 `prepareDelete`：在 `Dispatchers.IO` 里调 `inspect`，算完才弹 `AlertDialog`。
  弹窗里显示：实例名、**绝对路径**、`N 个文件，X MB`、存档的大小与去向、是否有 `mods/`，
  以及一条「文件会先改名为 `<id>.deleting-<时间戳>` 再逐个删除，中途失败会自动改回原名」的说明。
- 有 `saves/` 时给一个 **「连存档一起删（不可恢复）」** 勾选框，**默认不勾**。
- 不能删时（三种拒绝）弹窗里直接显示 core 给的中文原因，确认按钮禁用。
- 成功后：`backend.instances.remove(instance.id)` 移除登记 → `rescan()` → 提示行写
  「已删除 X：N 个文件，释放 M MB」+ 存档保留位置。
- 失败后：提示行写 core 的原文（哪个文件、什么错、是否已改回原名）。

## 单测

`core/src/test/kotlin/io/github/huang404yan/platypus/core/instance/InstanceDeleterTest.kt`，12 例：

| 用例 | 断言要点 |
| --- | --- |
| inspect 统计 + delete 彻底删 | fileCount / totalBytes 与手造文件一致；删完目录不存在 |
| 目录不存在 | `VERSION_DIR_MISSING`，且 `deletable == false` |
| `../outside` 越界 | `OUTSIDE_GAME_ROOT`，且越界目标里的 `precious.txt` 一个字节没动 |
| 绝对路径当 id | 拒绝（Java 的 `File(parent, child)` 会把绝对 child 折回 parent 之下，所以落到 `MISSING` 而不是 `OUTSIDE`；测试只断言「拒绝且没越界」） |
| 两实例共用 | `SHARED_BY_INSTANCES`，`sharedWith` 里出现另一条的 id |
| 自己那条不算共用 | 只剩自己一条登记时 `deletable == true` |
| `includeSaves = true` | 目录连 `saves/` 一起消失，`savesMovedTo == null` |
| `includeSaves = false` | 存档搬到 `platypus/saves-backup/`，`level.dat` 内容长度不变 |
| 只读文件导致失败 | 先探测本机只读是否真能阻止删除（不阻止就打印 SKIP），阻止时断言 `rolledBack` 与目录改回原名 |
| `moveToRecycleBin` | 目录只剩改名后的名字、`deletedFiles == 0` |
| `formatBytes` | B / KB / MB / GB 四档 |

> 坑：Kotlin 测试方法名里不能出现 `/`，否则 `compileTestKotlin` 报
> `Name contains illegal characters: /`。

## 无头冒烟

任务 `deleteSmoke`（`app-desktop/build.gradle.kts`），入口
`app-desktop/src/test/kotlin/io/github/huang404yan/platypus/desktop/InstanceDeleteSmoke.kt`：

```
MSYS_NO_PATHCONV=1 cmd.exe /c "D:\ruanjian\kaifa\qidongqigongju\gradle-9.9.0\bin\gradle.bat --no-daemon :app-desktop:deleteSmoke -PdeleteDir=dist/_delete-smoke"
```

（默认 `-PdeleteDir=dist/_delete-smoke`；全程只在这个临时目录里造/删文件，不碰真实 `.minecraft`。）

覆盖：造版本目录（json + jar + `saves/` 两个文件）→ inspect 出正确文件数与字节数 →
`delete(includeSaves=false)` → 断言版本目录没了、存档在 `platypus/saves-backup/` 且内容完整 →
目录不存在拒绝 → 多实例共用拒绝（手写 `instances.json`，因为仓库本身按目录去重）→
`../` 越界拒绝且 `precious.txt` 没被动 → 连存档一起删 → 只改名 → 重扫看不见被删的版本。

实测输出（46 项 PASS / 0 FAIL）：

```
delete smoke root = D:\ruanjian\zhinengti\gongzuokongjian\platypus\dist\_delete-smoke
index file        = D:\ruanjian\zhinengti\gongzuokongjian\platypus\dist\_delete-smoke\platypus\instances.json
PASS plan fileCount :: 4 vs 4
PASS plan totalBytes :: 5227 vs 5227
PASS plan savesBytes :: 1024
PASS saves path under platypus/saves-backup :: ...\platypus\saves-backup\1.20.1-20261109-182647
PASS saves content intact :: level.dat=512
PASS shared refused :: SHARED_BY_INSTANCES
PASS traversal refused :: OUTSIDE_GAME_ROOT
PASS precious file untouched :: ...\dist\_delete-smoke\precious.txt
PASS dir and saves gone :: ...\dist\_delete-smoke\versions\1.18.2
PASS recycle name prefix :: 1.17.1.deleting-20261109-182647
DELETE SMOKE OK
```

## 已知取舍

- **没有回收站**：删掉即不可恢复。补偿是「先算计划、再弹确认、显示绝对路径与字节数」，以及存档默认搬家。
- **改名 + 递归删不是事务**：极端情况下（改名成功、删到一半系统断电）会留下
  `<dir>.deleting-<时间戳>` 目录。它是可读的、可以手工删，且名字本身就说明了它是什么。
- **`instances.json` 的移除不在 core 里**：`InstanceDeleter.delete` 只动磁盘，
  登记由界面在成功后调 `backend.instances.remove()`。这样 core 的删除函数没有「顺带改配置」的副作用，也更好测。
