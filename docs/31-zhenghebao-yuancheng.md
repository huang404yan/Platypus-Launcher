# 31 · 远程整合包（从 Modrinth 直接搜包并导入）

记录日期：2026-11-14。本文只写**已经跑通过的事实**与**还没做到的部分**。

> 编号说明：01–30 已占用，本轮取 31。导入本身（mrpack 解析、overrides 覆盖顺序、先装游戏再下包内文件）见 `docs/25`。

---

## 一、一句话

下载页的「导入整合包」卡片多了「从 Modrinth 搜」一栏：关键词 + 游戏版本 + 加载器（按钮循环切换），搜到直接点「装这个包」，启动器自己把 `.mrpack` 下到系统临时目录，然后走原来那条本地导入流水线装成实例。

---

## 二、为什么只补「取包」这一段

在补之前，用户得先在浏览器里找到整合包页面、翻到 Files 标签、认出哪个是能导入的 `.mrpack`（同一个项目还会挂 `-sources.jar` 与 `-server.mrpack`），下载到本地，再回启动器点「选择文件…」。四步里只有最后一步是启动器的事。

而 `.mrpack` 到手之后的整段（解析 `modrinth.index.json` → 装游戏本体与加载器 → 下包内文件 → 解 overrides）`ModpackImporter` 早就实现了，且只认本地文件。所以远程世界只多一件事：**把包弄到本地**。

这就是 `ModpackBrowser` 只做 search / versionsOf / download 三件事的原因。下载完返回一个 `File`，调用方把它塞进同一个 `preview`，后面点「开始导入」调的还是原来的 `startImport(archive)`。两条路因此共享同一份进度、错误处理与重试逻辑，不存在「远程一套、本地一套」。

---

## 三、三个口子

### 3.1 search：复用搜索接口，只换 project_type

`ModrinthMeta.searchUrl` 本来就有 `projectType` 参数（光影/资源包那轮加的），整合包只是再传一个常量：

```kotlin
const val PROJECT_TYPE_MODPACK = "modpack"
```

加载器 facet 仍然要带（整合包也分 fabric / forge / quilt / neoforge），走的是同一个 `categories` 面。`loader = VANILLA` 时不加加载器 facet，表示不限。

**空关键词放行**：`query = ""` 时接口照样返回列表（实测 19,337 条），因为「Modrinth 上有什么整合包」本身就是内容，不该逼用户先编一个词。界面上的提示写的是「留空看热门」。

### 3.2 versionsOf：只留 `.mrpack`

同一个项目的版本列表里混着三类文件：

| 文件名 | 是什么 | 能不能导入 |
| --- | --- | --- |
| `Fabulously.Optimized-v15.0.0-beta.3.mrpack` | 真正的整合包 | 能 |
| `...-sources.jar` | 作者附带的源码包 | 不能 |
| `...-server.mrpack` | 服务端用包 | 能装但玩法不对 |

所以 `mrpackOnly()` 只按 `.mrpack` 后缀过滤（大小写不敏感），并且**不额外排除 server 包**——客户端装服务端包会装出个没客户端模组的实例，但从文件名上分辨不总是可靠，硬猜反而误伤。界面上写清「挑最新的一个」，选中的文件会在预览卡片里原样显示文件名，用户自己看得见。

一个 `.mrpack` 都没有时返回空列表，由界面说明「《X》没有适配 <版本> / <加载器> 的 .mrpack」，不抛异常。

### 3.3 download：复用 HttpDownloader，缓存落临时目录

```kotlin
val item = DownloadItem(url = file.url, target = target.absolutePath, sha1 = file.sha1, size = file.size)
val report = HttpDownloader().download(listOf(item)) { p -> ... }
```

- **带 sha1 与 size**：Modrinth 两个字段都给，下载器自己会校验。
- **目标已存在且 sha1 对得上就跳过**：装到一半失败再来一次，不会把几十 MB 重下一遍。
- **断点续传靠下载器自己的 `.part`**（`docs/14`）。

缓存目录是 `java.io.tmpdir` 下的 `platypus-modpacks/packs`。为什么不放游戏目录：`.mrpack` 是原料，装完就该消失，留在 `versions/` 里只会变成用户看不懂的垃圾；放临时目录还能借操作系统的清理策略兜底。目录名固定，所以重装同一个包能命中旧文件。

文件名**不做任何改动**，直接用服务器给的原名（只取最后一段）。因为 `.mrpack` 内部可能按自己的名字认上下文，改名是给自己找麻烦。

---

## 四、界面

`ModpackSection` 里多了一个来源开关：

```kotlin
private enum class ModpackSource { LOCAL, REMOTE }
```

卡片标题右侧两个 `TextButton`：「本地文件」/「从 Modrinth 搜」，当前来源加粗。远程这一栏是：

1. 搜索行：关键词（weight 2f）+ 游戏版本（weight 0.7f）+ 加载器按钮（Fabric → Forge → NeoForge → Quilt → Fabric 循环）+「搜索」。
2. 结果列表：`heightIn(max = 260.dp)` 且 `verticalScroll`，每条显示标题、`作者 · N 次下载`、简介与右侧「装这个包」。
3. 选中之后：`preparingDetail` 逐句推进（「查版本…」→「共 N 个 .mrpack，挑最新的一个…」→「下载 X」→「读取压缩包…」），下载期间显示进度条。
4. 下载完把 `archive` 与 `preview` 写进和本地导入共用的状态，界面下方照常出现预览卡片与「开始导入」。

`DownloadsPage` 把上面填的版本号作为 `initialGameVersion` 传进来当种子，省得用户在两个框里各敲一遍版本。

一个坑：`LoaderKind` 有 `OPTIFINE` 与 `OTHER`，写 `when` 必须带 `else -> "其他"`，否则编译报 `'when' expression must be exhaustive`。

---

## 五、实测证据

全部是 2026-11-14 在本机 app-image（0.8.0-preview.5）上真点的。

| 项 | 实测值 |
| --- | --- |
| 搜 `cobblemon`（1.20.1 + Fabric） | 5 条命中，首条 Cobblemon Official Modpack [Fabric]，CobbledStudios，1144 万次下载 |
| 搜空关键词 | 返回列表（接口侧 19,337 条），界面可用 |
| `versionsOf("fabulously-optimized", null, FABRIC)` | 480 个 mrpack，首个 `Fabulously.Optimized-v15.0.0-beta.3.mrpack` 173,981 B sha1 `59aae710…f513` |
| 单包下载 | `Cobblemon Official Modpack [Fabric] 1.8.4.mrpack` **100,855,720 B**，落 `%TEMP%\platypus-modpacks\packs\` |
| 预览卡片 | 「格式 Modrinth (.mrpack) · 游戏 1.21.1 + Fabric 0.20.1」/「77 个文件要下载，共 209.1 MB」/「覆盖层：overrides → client-overrides」 |
| 导入耗时 | 11:28 → 11:41，约 13 分钟（含 209 MB 游戏文件与 76 MB 按需下载的 Java 运行时，`runtime/java-runtime-delta` 从 0 涨到 376M） |
| 登记 | `instances.json` 新增 `id=1.21.1-fabric-0.20.1`、`name=Cobblemon Official Modpack [Fabric]`、`javaPath=…\java-runtime-delta\bin\java.exe` |
| 磁盘 | 实例目录 27M，`mods/` 下 60 个 jar；`versions/1.21.1/1.21.1.jar` 26,836,906 B；`libraries/` 70 个 jar 64M；`assets/` 3891 文件 798M |
| 单测 | `:core:test` 全量 **39 个测试类 / 436 例** 全过（新增 `ModpackBrowserTest` 9 例） |

`ModpackBrowserTest` 只锁不发请求的那部分：`.mrpack` 过滤（sources/server 包）、后缀大小写、带路径的文件名只取最后一段、非 mrpack 返回 null、缓存目录位置、facet 里 `project_type:modpack` 与 `categories:fabric` 都在、空关键词的 URL、进度分数没有分母时的 0。联网三段靠界面实测与一次性真网 smoke，不进单测，否则 Modrinth 一抖测试就红。

---

## 六、还没做到

- **没有版本挑选**：默认拿 `ModrinthMeta.pickBest` 挑出的最新一个，用户没法在同一页里换版本（要换得去 Modrinth 网页下 .mrpack 再走本地导入）。
- **不显示包内模组清单**：预览卡片只有文件数与总大小，看不出这包里有 Cobblemon 还是别的。
- **下载到 `%TEMP%`**：系统清理临时目录后，重装同一个包要重新下几十到几百 MB。
- **没做断点续传的跨会话恢复**：`.part` 还在就能续，被删了就从头开始。
- **安卓端没有远程搜索**：安卓的整合包页仍只有本地导入（安卓端本轮整体暂停，见 `docs/20`）。
- **没有「装这个包要多少磁盘」的预估**：副本大小 ≈ 包内文件 + overrides，界面上只报要下载多少。
