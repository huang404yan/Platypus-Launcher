# 32 · 导入完成后核对磁盘（与「取消」不再静默）

记录日期：2026-11-14。本文只写**已经跑通过的事实**与**还没做到的部分**。

> 编号说明：01–31 已占用，本轮取 32。整合包导入流程本身见 `docs/25`，远程取包见 `docs/31`。

---

## 一、一句话

整合包导入在「下载完」与「解 overrides」之间多了一步**核对包内文件**：按清单逐条比磁盘（存在 / 大小 / 必要时 sha1），有问题就收集成一份清单挂出来，界面标题从「导入完成」改成「导入没装齐」；同时「取消」也不再让界面停在半路，而是明确显示「导入已中断（停在「X」）」。

---

## 二、这个 bug 是怎么来的

现象：一个 77 文件的整合包，`mods/` 里只有 74 个 jar 加一个 51,551,488 字节的 `.part`，缺的那个是 139,966,403 字节（133.5 MB）的 Cobblemon 本体；但界面显示的是「导入完成」，实例已经登记进 `instances.json`。

追下去是两件事叠在一起：

**第一，下载器不会「吞失败」。** `HttpDownloader.download()` 用 `coroutineScope { items.map { async { … } }.awaitAll() }` 收口，只有所有 async 正常结束才返回 `DownloadReport`；`report.ok = failures.isEmpty()`，`ModpackImporter` 见到 `!ok` 会抛「N 个文件下载失败，第一个是 …」。所以「少一个文件但 report.ok == true」在当前代码里不可能发生。

**第二，协程被取消时不返回。** `DownloadsPage` 里 `rememberCoroutineScope()` 起的导入 job 挂在页面上。`App.kt` 的导航是 `when (page)` 分支，切页就销毁整棵子树，scope 随之被 cancel，`awaitAll` 抛 `CancellationException`，`download()` 根本没返回。而导入器的 `.part` 是**故意保留**的（下次自动续传），所以磁盘上只剩半截文件、进程里什么都不留。

界面这边原来的 `catch (e: CancellationException) { throw e }` 只重抛、不改状态，`phase` 就停在 `Running`——进度条卡在一半、按钮还是「取消」，看着像卡死；用户切回来看到上一条「已完成 X / Y 个文件」的消息，很容易读成「装完了」。

> 旁证：那台机器 11:40–12:05 的 Windows Application 日志一条事件都没有，不是崩溃或 OOM 强杀，与「进程被正常关闭 / 任务被取消」一致。

**根因两条，修法也两条**：导入这条路不能只在下完那一刻是对的（过程跨几分钟、上百文件，中途可能被取消、被杀、被杀毒截断），所以结束前必须**再看一眼磁盘**；而取消本身要让用户看见。

---

## 三、核对器：只比大小，必要时才算 sha1

`core/install/ModpackVerifier.kt` 的 `verify(instanceDir, files, onProgress)` 返回 `List<ModpackFileIssue>`，**不抛异常**：

| 磁盘状态 | 报什么 |
| --- | --- |
| 目标不存在但同名 `.part` 在 | 只下到一半（49.2 MB / 133.5 MB），还没落到最终位置 |
| 目标不存在、也没 `.part` | 文件不存在 |
| 目标在、大小与清单不符 | 大小不符（期望 X，实际 Y） |
| 大小未知（清单没写 `fileSize`） | 退回算 sha1，不符则「内容与清单不一致」 |
| 大小未知、sha1 也缺 | 放过（没有可比对的证据时不能凭空报错） |

为什么先比大小：`.mrpack` 的 `modrinth.index.json` 基本每条都带 `fileSize`（这一个包 77/77 条都有），一次 `length()` 就够，77 个文件 650 MB 核对只要几毫秒；只有清单没写大小时才读盘算 sha1（`ModUpdateChecker.sha1Of`，复用模组页已有的实现，没有新写第四份摘要代码）。

为什么不中途抛：抛出的话用户每次只看到一个毛病，改完再导又被下一个挡住。收集全了再一次性列出来，「哪些没下来、为什么」一目了然。`summarize()` 把前 5 个按「文件名（原因）」排开，多了折叠成「等 N 个」，并且只在真有 `一半` 条目时补一句「半成品会在下次导入同一个包时自动续传。」

失败也不抛异常的第二层理由：这时候**实例已经注册了**（`OneClickInstaller` 在装游戏那步就 `instances.save`），抛出去用户只会看到一个「失败」的实例，却不知道少了什么、更不知道其实能直接用。所以 `ModpackImportOutcome` 多了 `fileIssues` 与 `val complete get() = fileIssues.isEmpty()`，界面按它决定说「导入完成」还是「导入没装齐」。

---

## 四、界面：三种结尾都要说得清

`ImportPhase` 从四种状态变成五种，新增 `Canceled(step)`：

- `Finished` + `complete` → 「导入完成：<实例名>」（绿区，和以前一样）。
- `Finished` + `!complete` → 标题变「导入没装齐：<实例名>」并转成 error 色，下面红字列 `summarize(fileIssues)`，再一行灰字「再点一次「开始导入」会只补没下完的那些文件；已经下好的不会重下。」——按钮不换，重试就是同一个「开始导入」。
- `Canceled` → 红字「导入已中断（停在「下载整合包文件」）。实例目录里已经下好的文件不会白费，再点一次「开始导入」会自动续上没下完的那几个。」
- `Failed` → 不变（异常带 `ModpackStep`，如实报在哪一步断的）。

进度条那边也多了一格：`ModpackStep.VERIFYING`（「核对包内文件」），`detail` 写「已核对 X / Y 个文件」。它刻意排在 `DOWNLOADING_FILES` 与 `EXTRACTING` 之间，因为核的就是刚下完那批文件。

没有把 `Canceled` 做成 `Failed` 的子类：取消不是错误，用户点「取消」是主动行为，需要的是一句「断了，但没白下」，不是一段错误原因。

---

## 五、实测证据

**真实实例上跑核对器**（Cobblemon Official Modpack [Fabric] 1.8.4，那个缺文件的实例）：

```
files=77
issues=1
mods/Cobblemon-fabric-1.8.4+1.21.1.jar :: 只下到一半（49.2 MB / 133.5 MB），还没落到最终位置
---summary---
核对包内文件时有 1 个不对：Cobblemon-fabric-1.8.4+1.21.1.jar（只下到一半（49.2 MB / 133.5 MB），还没落到最终位置）。半成品会在下次导入同一个包时自动续传。
```

同一份清单、同一个目录，用 python 独立比对也得出 missing=1 / size-mismatch=0，与核对器一致。

**单测**：`:core:test` → `BUILD SUCCESSFUL in 31s`，XML 统计 **40 个测试类 / 448 例 / failures 0 / errors 0 / skipped 0**。新增 `ModpackVerifierTest` 12 例，覆盖上表每一行，另外守两条边界：多个问题全部收集（不是只报第一个）、核对进度最后一次 `done == total`。

**编译**：`:ui:compileKotlinJvm` 同时通过（接线处改的是 `ModpackSection.kt`）。

---

## 六、还没做到

- **「导入中」跨页保活已于 2026-11-14 补上**（`docs/34-jie-mian-baohuo.md`）：桌面端照安卓那套做了进程级 `ModpackRunner`，切页不再取消导入，只有点「取消」或退应用才停。仍留的问题见那篇「还没做到」。
- **核对只在导入收尾做一次**。之后用户手动删掉某个 jar、或被杀毒软件隔离，界面不会知道——那是模组页「重新扫描」的事，不是导入的。
- **只在 Cobblemon 这一个包上验过真实数据**。MCBBS v2 格式的包没有文件清单段（`manifest.files` 为空），核对直接返回空，所以那条路等于没加这道闸；它本来也不下包内文件，风险低但没实测。
- **sha1 分支只走了单测**，没有构造过「大小未知且内容被改」的真实包。
