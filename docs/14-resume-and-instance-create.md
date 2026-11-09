# 14 下载断点续传与实例页新建入口

记录日期：2026-11-09。本文只记录**已经跑通过的事实**与**还没做到的部分**，不含计划。

> 编号说明：09/10/11 归 Android 线，12 是一键装闭环，13 是设置持久化，06 有重号（`06-win-acceptance.md` 与 `06-wix-msi-build.md`）。本轮取 14。

---

## 一、这块原来断在哪

两件事都卡在「用户得替启动器干活」上：

| 环节 | 改造前状态 |
| --- | --- |
| 下载中断 | `core/src/main/kotlin/io/github/huang404yan/platypus/core/download/HttpDownloader.kt` 的 `attemptOnce` **一进来就 `part.delete()`**，`finally` 里也是 `if (part.exists()) part.delete()`。断一次网，已下的几百 MB 全废，重来一遍。1.20.1 全量实测 651,738,281 B（约 622 MB），代价很明显 |
| 没有任何 `Range` 请求头 | 全文件只有 GET + User-Agent，服务器从来没有机会只发剩下的部分 |
| 实例页只读 | `ui/.../ui/App.kt` 里的 `private @Composable fun InstancePage` 纯展示，没有「新建」入口、没有删除、不能改内存；想建实例只能去下载页走一键装 |

---

## 二、断点续传（`HttpDownloader` 全量重写）

`core/src/main/kotlin/io/github/huang404yan/platypus/core/download/HttpDownloader.kt` 从 245 行重写为 368 行。行为约定写在类注释里，下面逐条对应到代码：

| 场景 | 行为 | 代码位置 |
| --- | --- | --- |
| 正常下载 | 先写 `<target>.part`，校验通过才 `renameTo` 成 `<target>` | `promote()` `HttpDownloader.kt:306` |
| `.part` 已有内容 | 发 `Range: bytes=<len>-` | `HttpDownloader.kt:212` |
| 服务器回 206 | 接着写，先把已有字节补喂进 `MessageDigest` 再算整个文件的 sha1 | `HttpDownloader.kt:246-256` |
| 服务器忽略 Range 回 200 | 自动从头写，之前的半成品作废（不会把两段拼错） | `HttpDownloader.kt:240-243` |
| `Content-Range` 起点对不上 | 删 `.part`，报「续传起点对不上（要 N，服务器给 M）」 | `HttpDownloader.kt:236-239` |
| HTTP 416 | 删 `.part` 从零重下 | `HttpDownloader.kt:220-223` |
| 网络异常 / 提前 EOF / HTTP 错误 | **保留** `.part`，下次自动续 | 提前 EOF `HttpDownloader.kt:276-281`；异常 `HttpDownloader.kt:297-299` |
| 写多了 / sha1 不符 / 半成品读不全 | 删 `.part`（内容已不可信） | `HttpDownloader.kt:282`、`:287-292`、`:249-252` |
| 目标文件已存在且 sha1 命中 | 顺手清掉同名 `.part`，不重复下载 | `HttpDownloader.kt:190-196` |

两个容易踩的点，都写进了代码注释：

**必须发 `Accept-Encoding: identity`**（`HttpDownloader.kt:211`）。服务端若对 `Range` 请求回 gzip，`Content-Range` 里的偏移是按未压缩字节算的，而客户端拿到的是压缩流，续传起点会整体错位。宁可多传几个字节，也不能让偏移对不上。

**`HttpURLConnection` 没有 416 的常量**。`HttpDownloader.kt:363` 自己写了 `private const val HTTP_RANGE_NOT_SATISFIABLE = 416`。

### 进度条不虚高的两个细节

- 续传时已落盘的字节也要 `onBytes(base)` 报一次（`HttpDownloader.kt:255`），否则进度条会从「上次的 40%」突然掉回 0 再爬。
- 失败时用 `rollback()` 把本次已上报的字节**退回**（`HttpDownloader.kt:326-329`），不然重试会让总进度一路涨到超过 100%。

### 续传统计透到界面

`core/.../core/download/Downloader.kt:23-24` 给 `DownloadProgress` 加了两个字段：

```kotlin
val resumedFiles: Int = 0,
val resumedBytes: Long = 0L,
```

它们是 `completedFiles` / `completedBytes` 的**子集**，不额外累加，语义是「其中有多少是接着上次没下完的部分」。`HttpDownloader` 里用 `AtomicInteger` / `AtomicLong` 统计，只有成功且 `outcome.resumedBytes > 0` 才计入。

界面在 `ui/.../ui/download/OneClickInstaller.kt:100-102` 把它翻成人话：进度文案追加「，其中 N 个接着上次下（省了 M MB）」。用户一眼能看出这次不是从头再来。

### 测试

`core/src/test/kotlin/io/github/huang404yan/platypus/core/download/HttpDownloaderTest.kt` 从 7 例扩到 13 例，新增 6 例（全部实测通过）：

| 用例 | 锁的是什么 |
| --- | --- |
| `续传：已有半成品时只请求剩下的部分` | 预置 100 KB `.part`，断言只发 **1 次** GET（不是 HEAD+GET），进度报完整大小 |
| `续传：服务器不认 Range 时自动从头重下且结果正确` | `rangeSupport=false` 时内容仍然正确，不会拼错 |
| `续传：半成品比目标还大时清掉重下` | 坏 `.part` 不会把下载卡死 |
| `续传：416 时清掉半成品并从零成功` | 服务器拒绝 Range 的兜底 |
| `断网后保留半成品，第二次调用能接着下完` | `cutOffAfter` 截断到 80 KB，断言 `.part` **留存**且第二次内容完整 |
| `目标文件已存在时顺手清掉同名 part 文件` | 不留下垃圾文件 |

测试服务器同步改造：`respondRangeAware` 认 Range 回 206 + `Content-Range`，`start >= size` 回 416；新增 `send()` 走 chunked（`sendResponseHeaders(code, 0)`），这样截断时客户端看到的是「提前 EOF」而不是「长度不符」——这两条路径的恢复策略不同。

---

## 三、实例页新建入口

### 结构变化

原来 `ui/.../ui/App.kt` 里塞着 `private fun InstancePage`、`InstanceCard`、`LoaderBadge`、`Hint`、`WarningLine` 五个函数（`App.kt:142-215` 与 `:218-288`）。本轮拆成独立文件 `ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/InstancePage.kt`（686 行），`App.kt` 瘦到 142 行。

```kotlin
@Composable
fun InstancePage(
    backend: LauncherBackend?,
    initialGameRoot: String?,
    onLaunch: (Instance) -> Unit = {},
)
```

`App.kt:66-69` 的接线：实例页点「去启动」→ 写 `focusKey = instanceKey(instance)` → `page = Page.LAUNCH`。`LaunchPage` 第三参收 `focusKey`（`LaunchPage.kt:75`），`rescan()` 里的选中优先级变成「`focusKey` 命中的 > 当前手选的 > 列表第一个」（`LaunchPage.kt:126`）——否则从实例页跳过去还得再手点一次实例。

### core 层抽出的纯函数 `LocalInstance`

`core/src/main/kotlin/io/github/huang404yan/platypus/core/instance/LocalInstance.kt`（60 行）：

```kotlin
object LocalInstance {
    fun adopt(
        gameRoot: File,
        versionId: String,
        name: String? = null,
        memoryMb: Int = Instance.DEFAULT_MEMORY_MB,
        javaPath: String? = null,
    ): Instance?
}
```

返回 `null` 的四种情况：版本号空白、版本 json 解析不出、client jar 不存在、client jar 是 0 字节。**这四种都是「本地没有这个版本」，调用方据此决定要不要联网下载。**

判断「本地已经有」的判据是 `VersionResolver.load()` 成功**且** `clientJar` 非空。只判 json 存在是不够的：版本目录里可能只有 json 没有 jar（下载中断、手工拷了一半），那种情况必须重新下。

抽到 core 的理由：这条逻辑要能单测。`core/src/test/.../core/instance/LocalInstanceTest.kt` 6 例，其中一例专门测「jar 是空文件时不算数」。

### 实例页的三条路径

**路径 A：本地已有版本，直接认养（不联网）**

`InstancePage.kt:203` 的 `createInstance()` 先调 `LocalInstance.adopt()`（`InstancePage.kt:237`）。命中就**不拉 manifest、不下任何文件**，直接 `instances.save()`，文案「本地已经有 X，没有联网下载，直接登记成实例。」为了让「装完」这条分支的 UI 统一，这里构造了一个 `DownloadReport(0, emptyList())` 的假 `InstallOutcome`。

**路径 B：本地没有，走完整一键装**

`LocalInstance.adopt()` 返回 null 时，`VanillaInstaller.loadManifest()` → `OneClickInstaller.install()`（`InstancePage.kt:270`）。`install()` 本轮加了参数 `instanceName: String? = null`（`OneClickInstaller.kt:71`，在 `memoryMb` 之后、`onProgress` 之前，**带默认值所以既有调用不受影响**），登记时 `displayName = instanceName?.trim()?.takeIf { it.isNotEmpty() } ?: plan.versionId`（`OneClickInstaller.kt:170`）。这样「实例名」这个字段第一次真的能填，之前恒等于版本号。

**路径 C：扫描到的实例，登记成受管实例**

扫描出来的实例 `registered = false`，卡片上只有「去启动 / 登记成实例」。点登记会顺手探测一次精确主版本的 Java 并写进 `javaPath`；本机没有对应 Java 时文案提示「已登记 X。本机没有 Java N，去「启动」页点「自动补 Java」现下一个。」——不在这里替用户下载，因为补 Java 的进度与失败处理都在启动页。

### 每张卡片上的按钮

| 实例状态 | 按钮 |
| --- | --- |
| 已登记（`registered = true`） | 去启动 / 改内存 / 移除登记 |
| 仅扫描到（`registered = false`） | 去启动 / 登记成实例 |

「移除登记」的文案是「已移除 X 的登记。文件没删，随时可以再登记回来。」——**刻意不删版本文件**。实例页管的是「登记表」，不是磁盘。

「改内存」在原地展开输入框，复用和启动页同一套校验：非整数 →「内存要填整数，单位 MB。」；越界 → 夹到 512–32768 并提示「已按允许范围（512–32768 MB）改成 N MB。」；保存失败 →「写入实例失败：<原始异常>」。

---

## 四、无头冒烟（`instanceFlowSmoke`）

界面点击没法自动测，但下面这层可以：`app-desktop/src/test/kotlin/io/github/huang404yan/platypus/desktop/InstanceFlowSmoke.kt` 走的是 `LocalInstance.adopt` + `JsonInstanceRepository` + `InstanceScanner` + `mergeInstances`，**与实例页按钮调的完全同一批函数**。

### 命令

```
MSYS_NO_PATHCONV=1 cmd.exe /c "D:\ruanjian\kaifa\qidongqigongju\gradle-9.9.0\bin\gradle.bat \
  --no-daemon :app-desktop:instanceFlowSmoke -PskipWix \
  -PinstanceDir=dist/_instance-smoke \
  -PinstanceRealDir=dist/_test-mc/_e2e-clean-20261109"
```

### 实测结果

`BUILD SUCCESSFUL in 23s`，exit 0，**19 项断言全部 PASS**，加 5 项真实目录只读断言：

| 组 | 断言 |
| --- | --- |
| 1 认养 | `adopt existing version` / `adopted registered` / `adopted memory 3072` / `adopted directory` |
| 2 索引落盘 | `index file written <gameRoot>/platypus/instances.json` / `listed after save 1` / `listed name 我的 1.20.1` |
| 3 合并 | `scan sees version 1.19.4,1.20.1` / `merge dedup by directory`（2 条不是 3 条）/ `merge keeps registered` / `merge keeps edited name` |
| 4 重开 | `reopen after memory edit 8192` / `reopen keeps name` |
| 5 夹取 | `clamp on save 32768` |
| 6 拒绝 | `reject empty jar` |
| 7 移除登记 | `remove drops registration` / `remove keeps files` / `still scannable after remove` / `merged as scanned-only` |
| 8 真实目录（可选） | `adopt real 1.20.1` / `real gameVersion` / `real directory` / `real memory 4096` / `real client jar exists` |

第 7 组是这次最该锁的一条：**移除登记之后版本文件必须还在，而且还能被扫描回来**。如果哪天有人把「移除登记」实现成删目录，这一组会立刻红。

第 8 组用的是本轮 e2e 真下过的 `dist/_test-mc/_e2e-clean-20261109`（里面是真实 1.20.1 全量文件），只读调用 `adopt`，不写索引，证明这条逻辑在**真实版本目录**上也成立，不只是在手搓的假 json 上。

> 控制台里中文显示成乱码是 MSYS 控制台的 GBK 解码问题，`instances.json` 里的内容是正确的（用 `cat` 复核过）。`build.gradle.kts` 里已经给了 `jvmArgs("-Dfile.encoding=UTF-8")`。

---

## 五、测试与构建（实测）

| 命令 | 结果 |
| --- | --- |
| `gradle --no-daemon :core:test :ui:compileKotlinJvm :app-desktop:compileKotlin :androidApp:assembleDebug --rerun-tasks -PskipWix` | **BUILD SUCCESSFUL in 56s**，exit 0，52 tasks executed |
| 解析 `core/build/test-results/test/*.xml` | **15 个 suite、127 例、failures+errors=0、skipped=0**（上一轮 115 例，本轮 +12） |

本轮新增 12 例：

- `HttpDownloaderTest` +6（续传六种场景，见第二节表格）
- `LocalInstanceTest` 6（本地认养：齐全 / 无 jar / 空 jar / 无 json / 自定义名与内存 / 加载器版本）
- `InstanceFlowSmoke` 不是 JUnit，是 `JavaExec` 任务，19+5 项断言（见第四节）

逐类明细：HttpDownloaderTest 13 / InstanceRepositoryTest 6 / InstanceScannerTest 11 / LocalInstanceTest 6 / LaunchArgumentsTest 17 / LaunchPipelineTest 12 / GameJavaRulesTest 9 / JavaCandidatePathsTest 8 / JavaProbeParserTest 9 / JavaRuntimeManifestTest 10 / RuntimeSelectionTest 3 / OfflineUsernameTest 4 / SettingsRepositoryTest 6 / DownloadPlannerTest 6 / VersionMetaTest 7。

---

## 五之二、发行产物（实测）

| 项 | 值 |
| --- | --- |
| 出包命令 | `gradle --no-daemon :app-desktop:createDistributable :app-desktop:packageMsi -PskipWix -PwixDir=D:\ruanjian\kaifa\wix314` → **BUILD SUCCESSFUL in 24s**，exit 0 |
| msi | `releases/preview/windows/Platypus-Launcher-0.8.0-preview.1-win-x64.msi`，**64,970,860 B**，sha256 `bef0246be60a98ba72f257b7d28a80b3a1e43829539203427e4e43ca869b617a` |
| zip | `releases/preview/windows/Platypus-Launcher-0.8.0-preview.1-win-x64.zip`，**63,294,137 B**（200 条目），sha256 `f366da718e39643f6d2a13d188c7b4a435afc84f4036c465d514cf885bde23be` |
| app-image exe | 597,504 B，sha256 `7f2ba938c55661e4bb98da02f6e85c936d78fded763931d08d5c217ed730a7ed` |
| 旧包去向 | 15:36 那版（msi `558b5a25...171401` 64,876,652 B / zip `9caa1320...32b12c` 63,212,178 B）移到 `dist/_old-releases/win-0909-1536/` |

**三处 exe 哈希一致**（app-image 目录、zip 内、`msiexec /a` 解包出来的），这是唯一可信的一致性判据——msi 本身不可重现，同一个源码两次打包体积与哈希都不同（见 `docs/06-wix-msi-build.md`）。

解包解到 `dist/_msi-unpack3/`，包内类实测命中：`ui-jvm-...-d38215d6....jar` 有 `InstancePageKt`（30 个类）与 `SettingsPageKt`（5 个），`core-...-d334c0a3....jar` 有 `LocalInstance` 与 `HttpDownloader`（5 个）。→ 新代码确实进了包。

> 解包脚本第一版用 `%~dp0..\releases\...` 拼路径，`msiexec` 报 **1619**（无法打开此安装程序包）。改绝对路径后 exit 0。

---

## 五之三、真实网络续传冒烟（实测）

单测里的续传跑的是本地 `HttpServer`，伪造得了 206 / 200 / 416 / 提前 EOF，但**证明不了真实 CDN 真的配合 Range**。为此加了一个需要联网的无头任务：

```
gradle --no-daemon :app-desktop:resumeSmoke -PskipWix -PresumeDir=dist/_resume-smoke2
```

`BUILD SUCCESSFUL in 36s`，exit 0，**16 项断言全部 PASS，末行 `RESUME SMOKE OK`**。三个用例都用 Mojang 官方 CDN 上**不可变**的文件（版本 json 里写死的 sha1），所以断言可以写死，不会因上游更新假红。

| 用例 | 文件 | 实测 |
| --- | --- | --- |
| 1 全新下载 | `piston-data.mojang.com` 的 `client.jar`（sha1 `0c3ec587af28e5a785c0b4a7b8a30f9a8f78f838`） | 大小 23,028,853 B 与 sha1 均正确，无残留 `.part` |
| 2 预置 10 MB 再下 | 同一个 client.jar | `resumedFiles=1`、`resumedBytes=10485760`、耗时 **3,697 ms**（只下了剩下 13 MB；若 CDN 忽略 Range 会把 23 MB 全下，耗时不会这么短）、最终 sha1 一致、无残留 `.part` |
| 3 坏半成品自愈 | `piston-meta.mojang.com` 的 `5.json`（413,067 B，sha1 `ada52cc6...141ed664`） | 预置 20,000 字节全 `0x07` 垃圾，下载后大小与 sha1 都正确、无残留 `.part` |

用例 2 的前置断言还顺手证明了 CDN 支持 Range：`HTTP 206`，取到正好 10,485,760 字节。

**用例 3 第一版写错了，值得记下来。** 最初只传 `size = -1L`、不传 sha1，断言也只看「文件大于 1000 字节」——结果通过了，但通过得毫无意义：续传会把 20 KB 垃圾当成好数据接着往后拼，长度对得上、内容却是坏的。这不是测试，是给自己发合格证。改成传真实 sha1 + 真实 size 后，才真正验证了「sha1 不符 → 删掉半成品 → 重下」这条路径。

另外，四个真实下载源对 Range 的支持此前已单独探过（Python `urllib` 发 `Range: bytes=0-1023` + `Accept-Encoding: identity`）：piston-meta、piston-data、libraries.minecraft.net、resources.download.minecraft.net、bmclapi2.bangbang93.com **全部返回 206**。

---

## 六、还没做到的

1. **续传没在真实断网过程中验证过**。6 个单测覆盖了 206 / 200 / 416 / 提前 EOF / 连接异常五类响应，真实 CDN 的支持情况也已用 `resumeSmoke` 实测（见五之三，四类域名全部 206）；但「下到一半网线真的断了」这件事没法自动化——现在验证的是「半成品在盘上时能接着下」，不是「下载途中断开时程序反应正确」。后者仍需要手工拔网线或限速工具跑一次。
2. **没有「上次下到 40%」这类具体续传提示**。`DownloadsPage` 的 `cancelInstall()` 文案是「已下好的文件不会丢，再点一次会跳过校验通过的部分」，这句话在改造前其实**不完全成立**（只有校验通过并转正的文件会跳过，`.part` 会被删）。本轮修了底层，这句话才真的对，但界面只在进度条下面显示「其中 N 个接着上次下」。
3. **实例页没有「删除实例」**。只有「移除登记」。真要删版本文件得手动去目录里删。
4. **不能从实例页改 Java 路径**。换 Java 还是只能去启动页。
5. **新建实例表单只认原版**。Forge / Fabric 的版本号填进去会被 `LocalInstance.adopt` 或一键装当作原版处理，加载器安装器仍不存在。

---

## 七、踩坑记录

**Kotlin 里中文函数名必须用反引号。** `LocalInstanceTest.kt` 第一版把 `续传：半成品比目标还大时清掉重下` 直接写成 `fun 续传：...`，编译报 `e: Function declaration must have a name.` 与 `Function '<no name provided>' without a body must be abstract.`。中文标识符是合法的，但**函数声明里的中文后面紧跟的 `：` 会被当成类型标注的冒号**，解析就崩了。

**Kotlin 测试函数名里不能出现 `.`。** `HttpDownloaderTest.kt` 里 `续传：.part 比目标还大时清掉重下` 报 `Name contains illegal characters: ..`（`HttpDownloaderTest.kt:272` / `:325`）。改成「半成品」「part 文件」这类不带点的说法。

**断言写错一次。** 原以为续传要发 2 次请求（HEAD + GET），实际只发 1 次 GET，实测 `expected:<2> but was:<1>`（`HttpDownloaderTest.kt:250`），改成 `assertEquals(before + 1, hits.get())`。教训是**先看实现再写断言**，不要按「常见做法」猜。

**跨模块的 public val 不能智能转换。** `InstancePage.kt:237` 报 `e: Smart cast to File is impossible, because clientJar is a public API property declared in different module`。改成先 `val clientJar = local?.clientJar` 再判空。

**`-PskipWix` 与 `-PwixDir` 必须同时给。** 只给 `-PwixDir` 会在 `:unzipWix` 失败（`Cannot expand ZIP 'C:\Users\zero\.gradle\compose-jb\wix311.zip'`）。根 `build.gradle.kts:20` 的 `skipWix` 分支才是把本机 WiX 铺到 `build/wix311` 的那条路。

**MSYS 下跑 gradle 必须用 `cmd.exe /c` 且带 `MSYS_NO_PATHCONV=1`**，直接 `cmd //c "..."` 会把参数路径转义掉。

**`:ui` 是 KMP 模块，任务名是 `:ui:compileKotlinJvm`**。写 `:ui:compileKotlin` 报 `Ambiguous task name ... Candidates are: compileKotlinJvm, compileKotlinMetadata`。
