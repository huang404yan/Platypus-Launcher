# 26 · 崩溃报告读取与归因（非零退出码 → 一句话结论 + 一份报告）

记录日期：2026-11-13。本文只写**已经跑通过的事实**与**还没做到的部分**。

> 编号说明：01–25 已占用，本轮取 26。

---

## 一、一句话

游戏非正常退出后，启动页不再只显示「退出码 1」，而是把实例 `crash-reports/` 里最新一份报告挑出来：卡片上给文件名、时间、大小、`Description:` 那一句、第一个异常，可展开全文 / 复制全文 / 打开目录；日志区同时补了复制、导出、打开崩溃目录三个按钮。

---

## 二、为什么按 mtime 挑，不按文件名

原版 1.13 之后的命名是 `crash-2026-11-10_12.00.00-client.txt`，看着能直接解析出时间，但：

- 服务端与部分加载器改过命名格式（`crash-<日期>_<时间>-server.txt`、`crash-<时间戳>-client.txt` 都见过）；
- 同一秒内可能写出两份，文件名才会分先后。

所以 `CrashReports.latest` 用 `lastModified()` 当主键、文件名当次键：

```kotlin
files.maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })
```

只认 `.txt`（`ignoreCase = true`）：某些启动器/整合包会在同目录放 `.log`、`.json`、`.zip`，那不是给玩家读的报告。

---

## 三、两道闸：什么时候不该显示崩溃卡片

首页只想知道「这次为什么挂」，把上一次的旧报告挂出来比不显示更糟——用户会照着无关的报告去删模组。

| 闸 | 条件 | 挡掉的情形 |
| --- | --- | --- |
| 退出码闸 | `event.code == 0` 直接不读 | 玩家自己关掉游戏，`crash-reports/` 里可能还躺着历史报告 |
| 时间闸 | `report.modifiedAt >= startedAt - 5000` | 实例上一次崩过，这次正常启动后又被强杀，旧报告会被当成新的 |

5 秒余量（`CRASH_MTIME_SLACK_MS`）的两个来源：文件系统时间戳精度，以及崩溃写盘到进程退出之间那几十毫秒。

---

## 四、只读头部 256 KB

`readHead` 按 UTF-8 逐行读到 256 KB 就停（`MAX_READ_BYTES`）。理由：卡死循环的模组能把 crash-report 写到几十 MB，界面里逐行铺开会直接卡住。

选 UTF-8 而不是探测编码：1.13 之后游戏自己写 UTF-8；万一读到旧版 GBK 报告，头部那两行（`Description:` 与异常类名）仍然全是 ASCII，挑字段不受影响。

界面侧另有一道同样的闸：`File.readTextSafe()` 超过 256 K 字符就截断并追加「…（已截断，全文见文件本身）」。

---

## 五、归因规则的两处调整

`LaunchDiagnosis.RULES` 新增两个 `Rule`，并给 `Rule` 加了 `allOf` 字段（全部命中才算）：

```kotlin
data class Rule(
    val keywords: List<String>,
    val message: String,
    val skipWhenReachedMenu: Boolean = false,
    val allOf: List<String> = emptyList(),
)
```

| 规则 | 关键词 | 说明 |
| --- | --- | --- |
| 模组冲突 / 缺依赖 | `Incompatible mods found`、`Missing or unsupported mandatory dependencies`、`ModResolutionException`、`DuplicateModsFoundException`、`Contains duplicate mods`、`requires any version of`、`but it is not installed` | 日志里会点名模组 ID 与它缺的前置 |
| 模组文件损坏 | `zip END header not found`、`Premature end of file`、`Invalid mod file`、`Unable to read mod`、`java.util.zip.ZipException` | `allOf = listOf("mods")`：`ZipException` 单独出现也可能来自资源包 |

**顺序坑（实测踩过）**：首版把这两条放在「加载器未能启动」（`Failed to launch` + `GradleWorkerMain` + `NoClassDefFoundError: cpw/mods`）之后，`CrashReportsTest` 的「模组规则优先于连带异常」用例直接跑红——实际归成了「Forge/NeoForge 的库文件可能不完整」。根因：缺前置的日志后面必然跟 `NoClassDefFoundError: cpw/mods` + `GradleWorkerMain` 这类连带噪声，排在后面就会被抢先命中。所以两条模组规则必须**排在加载器规则之前**，源码里已写注释说明。

---

## 六、界面侧：崩溃卡片压扁日志区的布局 bug

卡片一出现，日志工具条（复制 / 导出 / 打开崩溃目录）几乎消失，日志区只剩 8 px。

根因：`LaunchPage` 根容器是 `Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp))`，**不能滚动**；日志区用 `Surface(Modifier.fillMaxWidth().weight(1f))`。卡片插入后固定高度内容总和超过窗口高度，`weight(1f)` 被压到接近 0。

修法（`ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/LaunchPage.kt`）：

1. 根容器加 `verticalScroll(rememberScrollState())`；
2. 日志区 `Surface` 从 `weight(1f)` 改成固定 `height(240.dp)`——外层已经是滚动容器，`weight` 在那里拿不到有界高度；日志本身是 `LazyColumn`，也必须有个确定上界才能滚。

这跟下载页 `DownloadsPage.kt:641` 早就是这么干的（`Modifier.fillMaxSize().verticalScroll(rememberScrollState())`），本轮只是把既有做法补到启动页。

---

## 七、实测证据

**单测**：`:core:test --rerun-tasks` → `EXIT=0 BUILD SUCCESSFUL in 45s`，`33 个测试类 / 350 例 / 0 失败 0 错误 0 跳过`（`CrashReportsTest` 10 例）。比此前基线 340 例多 10。

**界面点击验证**（app-image 双击起，进程 16352 壳 + 10376 主窗口，窗口 2060×1330 于 (40,40)）：

| 操作 | 结果 |
| --- | --- |
| 点「复制」 | 提示行「已复制日志（N 字符）」 |
| 点「导出」 | 落盘 `D:\ruanjian\youxi\pcl2ziyong\.minecraft\versions\1.20.1-Forge_47.4.28\logs\platypus-20261113-204402.log`，4128 字节 |
| 点「打开崩溃报告目录」 | explorer 窗口标题 `crash-reports` |
| 跑起 MC（PID 8908 / 383 MB）后直接 taskkill，不写报告 | **无卡片**（旧报告 20:45 早于本次启动，被时间闸挡掉）——这条正好验证了 mtime 闸有效 |
| 再启动（PID 7140 / 385 MB），写 `crash-2026-11-13_20-54-30-client.txt`（mtime 晚于启动）后 taskkill | 卡片出现：`崩溃报告 · crash-2026-11-13_20-54-30-client.txt`、`Description: Rendering overlay`、`java.lang.NullPointerException`，三个按钮齐全 |
| 点「查看全文」/「复制全文」 | 报告全文铺进 220 dp 高的 LazyColumn；提示行出现 |

---

## 八、还没做到

- **只有崩溃报告能被读到**：`logs/latest.log` 里那些不进 crash-report 的失败（比如 Java 根本没起来、natives 解压失败）仍然只走 `LaunchDiagnosis` 的关键词匹配，没有「自动摘录最后 N 行关键日志」。
- **不改文件、只读**：卡片不提供「删除这份报告」「上传到 issue」。
- **没在真实崩溃上验证过**：本轮三份报告都是手工写的仿真文件（`Description: Rendering overlay` + `NullPointerException` 取自 1.20.1 真实报告形状），真实随机崩溃里的 `Description` 句式更多样。
- **归因规则仍是字符串匹配**：没有混淆栈反解（Forge 生产环境栈里全是 `net.minecraft.class_310` 这类 SRG 名）。
