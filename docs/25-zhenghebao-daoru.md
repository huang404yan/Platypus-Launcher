# 25 · 整合包导入（.mrpack / MCBBS v2 → 能启动的实例）

记录日期：2026-11-10。本文只写**已经跑通过的事实**与**还没做到的部分**。

> 编号说明：01–24 已占用，本轮取 25。

---

## 一、一句话

下载页底部多了一个「导入整合包」：选一个 `.mrpack` 或 MCBBS v2 的 `.zip`，先只读解析给你看一眼（什么包、哪个游戏版本、带什么加载器、几个文件、多大），确认后一次走完「装原版 → 装加载器 → 下包内文件 → 解 overrides → 登记实例」。

---

## 二、为什么要先解析再装

一个整合包从几十 MB 到 4 GB 不等。如果点一下就直接开下，用户在下到 30% 才发现「这包是 1.19 的、我这台机器没装那个加载器」，只能取消重来。

所以入口是两段式：

| 阶段 | 做什么 | 碰网络吗 | 碰盘吗 |
| --- | --- | --- | --- |
| `ModpackImporter.inspect(archive)` | 打开 zip、认出格式、读出清单、算总体积 | 不 | 只读 |
| `ModpackImporter.import(...)` | 装游戏、下文件、解压、登记 | 是 | 是 |

`inspect` 是纯函数的边界：它只吃一个 `File`，吐一个 `ModpackPreview`，没有副作用，因此能在单测里对着一堆畸形 zip 反复跑。

---

## 三、认得哪两种格式

| 格式 | 识别标记 | 权威字段 | 状态 |
| --- | --- | --- | --- |
| Modrinth（`.mrpack`） | 根目录有 `modrinth.index.json` | `formatVersion` / `files[]` / `dependencies` | 已实现并通过真包验收 |
| MCBBS v2 | 根目录有 `mcbbs.packmeta`（优先于 `manifest.json`） | `addons[]` 里的 `game` / `forge` / `neoforge` / `fabric` | 已实现，**尚未用真包验收** |
| CurseForge（裸 `manifest.json`） | 有 `manifest.json` **且**有 `overrides/` | — | 只识别到「这是 CF 包」，不导入 |
| Prism / HMCL（`mmc-pack.json`） | 有 `mmc-pack.json` | — | 明确拒绝并给出原因 |

两点判定细节，都是踩过才知道的：

1. **裸 `manifest.json` 不能单独作为证据**。普通 mod jar 的 `META-INF/MANIFEST.MF` 不叫这个名字，但很多 zip 里确实带着一个叫 `manifest.json` 的构建清单。要求「同时存在 `overrides/` 目录」才认成 CurseForge，误判率才降得下来。
2. **判定顺序是 `mcbbs.packmeta` > `mmc-pack.json` > `manifest.json`**。这个顺序抄的是 PCL 的 `ModModpack.vb:44-55`——PCL 是四端（PCL / HMCL / Prism / 官方）都验过的实现，它把 packmeta 排在最前是因为有些 MCBBS 包同时带了 CurseForge 风格的 `manifest.json` 当兜底。

---

## 四、`.mrpack` 的关键字段（按官方文档核实）

来源：<https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack>（2026-10-07 更新）。

```json
{
  "formatVersion": 1,
  "game": "minecraft",
  "versionId": "1.0.0",
  "name": "包名",
  "files": [
    {
      "path": "mods/MyMod.jar",
      "hashes": { "sha1": "...", "sha512": "..." },
      "env": { "client": "required", "server": "required" },
      "downloads": ["https://cdn.modrinth.com/..."],
      "fileSize": 123456
    }
  ],
  "dependencies": { "minecraft": "1.20.1", "fabric-loader": "0.20.1" }
}
```

实现里守住的四条：

- **`hashes` 里 sha1 与 sha512 按规范都必有**，但真包里见过只写 sha512 的；本实现只取 sha1 交给下载器校验，缺 sha1 时记一条警告而不是失败。
- **`env.client == "unsupported"` 的文件必须跳过**（那是给人开专用服务器用的）。跳过数会出现在警告里。
- **`dependencies.minecraft` 缺失 → 直接失败**。没有游戏版本就无从「装原版」，这是唯一的硬失败条件。
- **`quilt-loader` 明确拒绝**，与 PCL 一致（PCL 遇到 Quilt 包直接取消安装）。不认识的 dependency key → 落到 `LoaderKind.OTHER` 并记警告，不失败——规范原文说「可能随时新增，应用要能处理未知 key」。

### 4.1 overrides 的三层与顺序

`overrides/` → `server-overrides/` → `client-overrides/`，**按层依次覆盖，client 最后**。

装到客户端时只取 `overrides/` + `client-overrides/`，`server-overrides/` 一律不取（那是给专用服务器的世界与配置）。实现里这个顺序是靠外层循环 `manifest.overrideDirs`、内层遍历 `zip.entries()` 保证的，后一层天然覆盖前一层。

### 4.2 路径安全（这是规范里专门警告的一条）

规范原文要求：导入 zip 时必须确保 `path` 不越出实例目录，不能含 `..`，不能以盘符开头。

实现做了两层：

1. **`ModpackMeta.safeRelativePath()`** 拦三类：以盘符开头（`^[A-Za-z]:`）、绝对路径（`/` 开头）、**某一段整段等于 `..`**。注意是「某一段等于」，不是「字符串里含 `..`」——`..hidden.jar` 是合法文件名，按字面拦会把真模组误杀。
2. 写盘前再比一次 `target.canonicalPath` 是否落在 `instanceDir.canonicalPath` 下。第二层是防「`..` 藏在层名前面」这种绕过：先剥层前缀再判断路径是否合法，顺序反过来就会漏。

顺带拦了 Windows 会静默裁掉的尾点尾空格（`trimEnd('.', ' ')` 为空的段）。

### 4.3 会覆盖用户数据的一律默认跳过

`ModpackOverrides.touchesUserData()` 判的是这些顶层名：

```kotlin
val PROTECTED_TOP_DIRS = setOf(
    "saves", "screenshots", "logs", "crash-reports", "options.txt", "servers.dat",
)
```

覆盖 `mods/` `config/` 是整合包的正常行为，覆盖 `saves/` 就是删档。所以只有当**目标文件已存在**才会跳过，界面上的「允许覆盖已有存档与设置（默认跳过）」勾选框才会放开它。

---

## 五、MCBBS v2 的字段（按 PCL 源码核实）

源码：`gongtong/pcl-src/src/PCL-full/tree/Plain Craft Launcher 2/Modules/Minecraft/ModModpack.vb`（866 行），`InstallPackMCBBS` 从 `:679` 起。

`mcbbs.packmeta` 自带 `PackType=3`，结构是：

```json
{
  "name": "包名",
  "addons": [
    { "id": "game", "version": "1.20.1" },
    { "id": "forge", "version": "47.2.0" }
  ],
  "launchInfo": {
    "javaArgument": ["-XX:+UseG1GC"],
    "launchArgument": ["--demo"]
  }
}
```

三条实现要点：

1. **`addons` 里必须有 `id == "game"`**，否则抛「该整合包未提供游戏版本信息（addons 里缺 game），无法安装」。
2. **`addons.forge` 的 version 是纯 forge 号**（`47.2.0`），不是 `1.20.1-47.2.0`。要拼 maven 路径得配 game 的版本，对应 `ForgeMeta.artifactVersion(gameVersion, forgeVersion) = "$gameVersion-$forgeVersion"`。
3. **MCBBS 包里不含游戏文件**，只声明 addons，游戏本体与加载器都由启动器按版本号现装。所以 `files[]` 恒为空，导入流程里的「下载包内文件」那一步会跳过。

`launchInfo.javaArgument` 写进实例的 `extraJvmArgs`；`launchInfo.launchArgument` 目前**只能记警告**——`Instance` 模型里还没有「额外游戏参数」字段（见第七节）。

---

## 六、导入的真实顺序与原因

```
PARSING          只读解析（inspect）
INSTALLING_GAME  复用 OneClickInstaller：原版文件 → 补 Java → 装加载器 → 登记实例
DOWNLOADING_FILES 下 files[]，按 sha1 校验
EXTRACTING       按层解 overrides
FINISHING        把 extraJvmArgs 合进实例并保存
```

**为什么先装游戏再下包内文件**，两个原因：

1. 包内文件要落在 `<gameRoot>/versions/<id>/mods/...`，而 `<id>` 是 `OneClickInstaller` 在注册那一步才由加载器算出来的（装 Fabric 时会从 `1.20.1` 变成 `1.20.1-fabric-0.20.1`）。先装才能一次拿到确定的路径。
2. 加载器安装（Forge/NeoForge 的官方 installer 是个真 java 进程）本来就需要一个能跑的 java。这一步顺手把 `ensureRuntime` 也走完了。

**进度条为什么不用 `InstallStep`**：`InstallStep` 是下载页一键装的步骤枚举，界面里 `VISIBLE_STEPS` 把它写死成六格。整合包多出「解析包」「下包内文件」「解 overrides」三段，塞进同一个枚举会让下载页凭空多出三格永远不会亮的灯。所以单独一个 `ModpackStep`（六个值），界面上独立一块。

---

## 七、没做到的

| 缺项 | 现状 | 影响 |
| --- | --- | --- |
| `extraGameArgs` | 只记警告，不生效 | MCBBS 包里要求额外游戏参数的少数包装出来会缺参数 |
| CurseForge 包 | 只识别不导入 | 用户拿到 CF 包会被明确拒绝并看到原因 |
| Prism / HMCL（`mmc-pack.json`） | 明确拒绝 | 同上 |
| 资源包 / 光影包 / 数据包 | 不在 `files[]` 的覆盖范围之外也没有单独入口 | 走 P4（下载页扩类） |
| 整合包导出 | 未实现 | 走 P5，与 P3 共用格式代码 |
| 安卓端入口 | 未接 | 安卓端还没有下载页级别的安装器 |

---

## 八、怎么验的

### 8.1 单测

`ModpackMetaTest`（23 例）+ `ModpackOverridesTest`（9 例），覆盖：格式识别优先级、mrpack 字段缺失、`env.client == "unsupported"` 跳过、quilt 拒绝、未知 dependency、`safeRelativePath` 的六类恶意路径、overrides 层序、用户数据保护判定。

### 8.2 无头端到端

`gradle :app-desktop:modpackE2e -PmodpackDir=dist/_modpack-e2e`（`app-desktop/src/test/kotlin/.../ModpackE2e.kt`），退出码 0 才算过：

1. 现场按规范造一个真 `.mrpack`（两个真模组 + 两层 overrides + 一个只该给服务器的文件）；
2. `inspect` 并断言格式 / 游戏版本 / 文件数 / 总体积；
3. `import` 走完整流程；
4. 校验产物：两个模组都落地且各自 sha1 对得上、`client-overrides` 真的覆盖了 `overrides`、`server-overrides` 没被装进来、实例已登记；
5. 用日常启动链路拉起来，等主菜单。

包内模组指向 Modrinth 上的 AppleSkin 1.20.1 fabric 版（sha1 `f859bb13...`，1,097,240 B）与它的依赖 fabric-api（sha1 `3e9cdd3e...`，2,137,232 B），url / sha1 / size 都取自 API。所以下载器那条「按 sha1 校验并落盘」、以及「一个包多个文件一次下齐」的路径都是真跑过的。

**实测数字（2026-11-10）**：

```
INSPECT kind=MRPACK game=1.20.1 loader=FABRIC loaderVersion=0.20.1 files=2 bytes=3234472
IMPORT OK in 7808 ms
  instance id=1.20.1-fabric-0.20.1 downloadedFiles=2 bytes=3234472 overrideFiles=2
  java=<游戏目录>/runtime/java-runtime-gamma/bin/java.exe major=17 javaInstalled=true
PASS × 10（两个模组各三项 + 包内文件数 + overrides + 层序 + server-overrides + 实例登记）
MENU REACHED +10165 ms
RESULT menuReached=true launchMs=10165
```

注意 `javaInstalled=true`：这条验收故意用 `DesktopJavaRuntimeService(scanGlobalLaunchers = false)`，本机没有任何可扫到的 JDK，所以走的是「自动下 Java 运行时」那条分支——也就是说整合包导入顺手把「机器上没有合适 Java」这个最常见的首次体验坑也覆盖了。

### 8.3 第一次跑失败过，原因是验收包本身缺依赖

首跑进到启动阶段就退出（`exited code=1`），游戏日志里是：

```
有不兼容的模组！ (Incompatible mods found!)
模组 'AppleSkin' (appleskin) 2.5.2+mc1.20.1 需要 fabric-api 的 任意版本，但没有安装它！
```

**这不是导入器的缺陷**——真实整合包的 `files[]` 里会把依赖一起列出来，是我的验收包只放了一个模组。修法是把 fabric-api 也写进 `files[]`，顺带把「多文件包」这条路径也覆盖了（原来的断言 `fileCount == 1` 改成 `== 2`）。这件事值得记一笔，因为它说明「装完能启动」这类验收必须真启动，光比对文件落地是发现不了依赖缺失的。
