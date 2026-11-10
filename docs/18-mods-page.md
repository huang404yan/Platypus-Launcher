# 18 · 模组页（本地 mods/ 扫描 + Modrinth 搜索安装）

记录日期：2026-11-09。本文只写**已经跑通过的事实**与**还没做到的部分**。

> 编号说明：01–17 已占用，本轮取 18。

---

## 一、为什么是 Modrinth 而不是 CurseForge

| | CurseForge | Modrinth |
| --- | --- | --- |
| API 凭证 | 必须 API key，申请要说明用途 | 匿名 GET，无 token |
| 第三方启动器下载 | 条款禁止绕过其广告/分成页面 | 官方文档明确允许启动器调 API 与 CDN |
| 搜索 | `/v1/mods/search` | `/v2/search`，支持 facets |

结论不用犹豫：**CurseForge 不能接**（不是技术问题，是条款问题），Modrinth 可以直接接。

## 二、两层能力，分开写

### 2.1 本地 `mods/` 扫描（`core/.../core/mods/ModScanner.kt`）

关键事实：**`mods/` 在游戏目录下，不在 `versions/<id>/` 下**。也就是说所有实例共用一份模组目录——这是 Mojang 的目录约定，不是本项目偷懒。想做「每个实例独立模组」，得改成启动时把选定模组软链/复制进一个隔离目录，那是另一件事。

识别规则：

| 文件名 | 处理 |
| --- | --- |
| `*.jar` | 启用，读元数据 |
| `*.jar.disabled` / `*.jar.bak` / `*.disabled` | 列出但标停用，**不读元数据** |
| `.txt` 或点开头的文件 | 静默跳过（`desktop.ini`、`README.txt` 不该刷屏） |
| 其它（`setup.exe` 之类） | 不列，但加一条 warning「跳过 X：不是 .jar」 |

元数据读取顺序（命中即停）：

1. `fabric.mod.json` → `LoaderKind.FABRIC`，取 `id` / `name` / `version`
2. `META-INF/neoforge.mods.toml` → `NEOFORGE`
3. `META-INF/mods.toml` → `FORGE`

TOML **只认第一段 `[[mods]]` 的 `modId` / `displayName` / `version`**，用正则 `(?m)^\s*key\s*=\s*"([^"]*)"` 抓。没有实现完整 TOML 解析器——一个 `mods.toml` 里可能有多段 `[[mods]]`（一个 jar 塞多个模组），也可能有 `[[dependencies]]` 这种嵌套表，真实现要几百行，而我们只需要一个能显示的名字。**读不出来不报错**：`problem` 字段记一句原因，`title` 退回文件名，界面照常显示。

绝不抛异常：`describe()` 里所有 `runCatching`，坏 jar（`ZipException`）、没声明文件、编码不对，全部折成 `problem`。

### 2.2 Modrinth 元数据（`core/.../core/mods/ModrinthMeta.kt`）

实测（2026-11-09）：

```
GET https://api.modrinth.com/v2/search?query=sodium&limit=3&facets=[["project_type:mod"],["categories:fabric"],["versions:1.20.1"]]
→ 200 / 4867 B / total_hits=41

GET https://api.modrinth.com/v2/project/sodium/version?game_versions=["1.20.1"]&loaders=["fabric"]
→ 200 / 27965 B / 13 个版本
```

facets 是**三层数组**：层内 OR、层间 AND。写成一层就退化成 OR，搜 1.20.1 的 fabric 模组会混进 forge 的。这一条是实测踩出来的。

`files[]` 的字段：`file_type` / `filename` / `hashes.id`（就是 sha1）/ `id` / `primary` / `size` / `url`。一个版本可能挂多个文件（`sources.jar`、`dev.jar`），**只取 `primary == true` 的那个**，没有 primary 才取第一个。

版本挑选顺序：`release` → `beta` → 第一个。不按发布日期排——Modrinth 返回的顺序已经是它自己的优先级。

## 三、编排（`core/.../core/mods/ModInstaller.kt`）

三步：`VERSIONS`（查询版本）→ `DOWNLOAD`（下载模组）→ `VERIFY`（校验文件）。

- 文件名**保持原样不改**。有些模组靠文件名区分构建变体（`sodium-fabric-0.5.13+mc1.20.1.jar` 里的 `+mc1.20.1`），改名会让用户对不上官方页面。
- 下载走既有的 `HttpDownloader`，因此**白得三件事**：`.part` 断点续传、sha1 强校验、镜像兜底。
- 下完立刻 `ModScanner.describe(target)` 回读，证明「文件真的在且读得出元数据」，而不是只信 HTTP 200。

### 依赖：如实说没做

Modrinth 的 `dependencies[]` 里 `dependency_type == "required"` 只给一个 `project_id`（不是 slug，也不是版本号）。要真装前置，得再 `GET /v2/projects/<id>` 拿 slug、再查它的版本列表、再递归——那就成了完整依赖求解器，还要处理环依赖与版本冲突。

本轮的选择：**收下 required 数量、装完如实告诉用户「还差 N 个前置模组，需要自己再装」**。假装解决了比不解决更坏。

## 四、界面

导航栏 `Page` 里插了 `MODS("模组")`，位置在「下载」与「联机」之间。`App.kt` 的 `when` 分发到 `ModsPage`。

页面结构：

```
标题 + 说明
[游戏目录输入框            ] [刷新已装]
[在 Modrinth 搜模组        ] [游戏版本] [加载器：Fabric] [搜索]
已装 N 个（启用 A · 停用 B）
┌── 本地 mods/ ────────┬── Modrinth 搜索结果 ──┐
│ 每行：标题 / 大小     │ 每行：标题 / 下载量   │
│      文件名 · 加载器  │      作者 / 描述      │
│      problem（红字）  │      slug / [装进 mods/] │
└──────────────────────┴───────────────────────┘
``
加载器按钮是**循环切换**（Fabric → Forge → Quilt → NeoForge → Fabric），不是下拉框——只有一个设置项时，下拉框要两次点击，按钮一次。

## 五、实测（`dist/_mods-e2e`，2026-11-09 21:25）

无头验收任务 `:app-desktop:modsE2e`：

```
[21:25:26] MODS E2E start; query=sodium gv=1.20.1 loader=FABRIC
[21:25:28]   [1] sodium / Sodium / 2.5 亿 次下载
[21:25:28]   [2] sodium-extra / Sodium Extra / 1 亿 次下载
[21:25:28]   [3] reeses-sodium-options / Reese's Sodium Options / 8803 万 次下载
[21:25:28] INSTALL sodium
[21:25:29]   下载模组 下载 sodium-fabric-0.5.13+mc1.20.1.jar
[21:25:59]   校验文件 校验 sodium-fabric-0.5.13+mc1.20.1.jar
[21:25:59] INSTALL ED sodium-fabric-0.5.13+mc1.20.1.jar bytes=971552 version=mc1.20.1-0.5.13-fabric sha1Checked=true
[21:25:59] SCAN exists=true enabled=1 disabled=0 warnings=0
[21:25:59]   sodium-fabric-0.5.13+mc1.20.1.jar / id=sodium / name=Sodium / version=0.5.13+mc1.20.1 / loader=FABRIC / enabled=true / problem=-
[21:25:59] RESULT installed=true elapsedMs=33149
```

`sha1Checked=true` 的意思是 `ModScanner.describe` 回读时 `problem == null`（`HttpDownloader` 那边 sha1 校验失败会直接失败、不会到这一步）。

耗时 33 秒里 30 秒在下载——Modrinth 的 CDN 在本机单连接约 32 KB/s 起，971 KB 走了半分钟。真实网速会更快。

## 六、单测

| 文件 | 例数 | 覆盖 |
| --- | --- | --- |
| `core/.../core/mods/ModScannerTest.kt` | 11 | 目录不存在 / 空目录 / 三种元数据格式 / 停用后缀 / 坏 jar / 非 jar 告警 / 排序 |
| `core/.../core/mods/ModrinthMetaTest.kt` | 12 | 真实响应夹具（search 与 version 各一份）/ facets 三层结构 / 编码 / 下载量格式化 / primary 挑选 |

两份都用 `ZipOutputStream` / 字符串夹具造假数据，**不联网**。

## 七、已知取舍

| 取舍 | 理由 / 代价 |
| --- | --- |
| `mods/` 全局共用 | 遵循 Mojang 目录约定；要「每实例独立模组」得另做隔离，见第一节 |
| 不做依赖求解 | 见第三节；界面上如实报缺 |
| 只装不卸 | 删除走文件管理器（用户自己删 jar 最直接）；界面里的「停用」目前只是**显示**后缀，不是按钮 |
| 不支持从 URL 直装 | Modrinth 之外（GitHub Releases、作者网盘）没有统一元数据，本轮不做 |
| 不查更新 | 要记录「从哪个 project/version 装的」，得在 `mods/` 旁边维护一份清单文件，留给下一轮 |

## 八、还没做到

- 模组的启用/停用按钮（现在只有扫描时的识别）
- 更新检查（需要本地清单记录来源）
- 依赖自动安装
- CurseForge（条款不允许）
- 模组冲突提示（同类优化模组互斥，如 sodium 与 optifine）
