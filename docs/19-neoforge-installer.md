# 19 · NeoForge 安装器闭环（镜像拉元数据 → 预铺依赖库 → 跑官方 installer → 补 client jar → 进主菜单）

记录日期：2026-11-09。本文只写**已经跑通过的事实**与**还没做到的部分**。

> 编号说明：01–18 已占用，本轮取 19。

---

## 一、为什么这条线比 Forge 难三倍

Forge 那条（`docs/17`）只有两个坑：要先写 `launcher_profiles.json`、装完要补 client jar。NeoForge 多出来的坑全部来自**网络**：

| 障碍 | 实测表现 | 绕法 |
| --- | --- | --- |
| 官方 maven 完全不通 | `curl (35) Recv failure: Connection was reset`，`-4` 强制 IPv4 一样；域名解析正常（168.143.171.186），是连接被重置 | 改用 BMCLAPI 镜像 |
| installer 的 `--mirror` 对 client 安装**无效** | 见第二节，字节码级确认 | 自己预铺库 |
| BMCLAPI 自己不存文件 | `bmclapi2.bangbang93.com` 一律 302 到高校镜像（实测 47 条落 `mirror.nyist.edu.cn`） | 拿落地镜像做直连兜底 |

## 二、`--mirror` 为什么救不了 client 安装（字节码级结论）

`java -jar neoforge-21.1.265-installer.jar --help` 里明明有 `--mirror <URL>`，但传了没用。反编译 `dist/_nf/nf.jar`（411 entry）逐条核对：

- `SimpleInstaller.mirror` 是 static URL 字段，`--mirror` 解析后 `putstatic` 进去（字节码 708 行）；
- `Install.getMirror()` 只在「mirror 字段为空 **且** `SimpleInstaller.mirror` 非空」时才 `new Mirror(...)`，否则走 `getMirrorList()`（`install_profile.json` 的 `mirrorList`，**该字段实测为空**）随机挑一个；
- 全 jar 里引用 `getMirror` 的只有 4 个类：`Action`、`ServerInstall`、`Install`、`ui/InstallerPanel`。**`ClientInstall` 一次都没引用**；
- `Action.downloadLibraries` → `DownloadUtils.downloadLibrary` → `monitor.downloader(library.downloads.artifact.url)`，直接拿 json 里写死的 url，没有任何重写；
- 含字符串 `"releases"` 的只有 `FatInstallerAction` 与 `ServerInstall`——印证只有它们做镜像前缀替换。

所以：**client 安装这条路，installer 一定去 `maven.neoforged.net` 下 70 个库**，本机必挂。首跑日志里 13 处 `java.net.SocketTimeoutException: Connect timed out`，每条约 5 秒，栈永远是

```
DownloadUtils.getConnection(DownloadUtils.java:128)
  ← Downloader.download(Downloader.java:120)
  ← DownloadUtils.downloadLibrary(DownloadUtils.java:80)
  ← Action.downloadLibraries(Action.java:86)
  ← ClientInstall.run(ClientInstall.java:113)
  ← SimpleInstaller.main(SimpleInstaller.java:174)
```

## 三、解法：RUN 之前把库「预铺」到 `<游戏目录>/libraries/`

### 3.1 为什么预铺就能让它跳过

`Downloader.download(File f)` 开头是：

```java
if (f.exists() && sha1 != null && Objects.equals(sha1, DownloadUtils.getSha1(f))) {
    message("File … exists. Checksum valid.");
    return true;
}
```

**只发一条日志，不发任何网络请求。** 本轮实测这条路径生效：13822 行安装器日志里 `Checksum valid` 出现 **117 次**，`Downloading library from` **0 次**，`SocketTimeout` **0 次**。

### 3.2 落盘路径怎么算

`DownloadUtils.downloadLibrary` → `File local = library.getName().getLocalPath(dest)`，而 `Artifact.getLocalPath(File dir)` 字节码就是 `new File(dir, path.replace('/', File.separatorChar))`。它用的是 **`Artifact` 的 `path` 字段**（由 maven 坐标 `name` 解析而来），不是 `downloads.artifact.path`。两者实测完全相同，但代码里按 `name` 推更稳，所以 `NeoForgeInstallProfile.mavenPath()` 是这么实现的。

### 3.3 库清单位于哪

`install_profile.json` 的 `libraries[]`（21.1.265 实测 **70 条，sha1 与 size 全齐**：host 分布 = `maven.neoforged.net` 51 + `libraries.minecraft.net` 19，合计 26 637 572 B），加上版本 json 的 `libraries[]`（47 条，**全部是 70 条的子集**）。代码里两批 `distinctBy { it.path }` 合并，共 70 条要铺。

### 3.4 镜像规则

| 原 url 前缀 | 换成的地址 | 备注 |
| --- | --- | --- |
| `https://maven.neoforged.net/releases/` | `https://bmclapi2.bangbang93.com/maven/<tail>` | 主用，302 到高校镜像 |
| 同上，作为兜底 | `https://mirror.nyist.edu.cn/bmclapi/<tail>` | **直连，少一跳 302** |
| 同上，第二道兜底 | `https://maven.creeperhost.net/<tail>` | **2026-11-14 新增**，补 BMCLAPI 的缺件，见 3.5 |
| `https://libraries.minecraft.net/` | `https://bmclapi2.bangbang93.com/libraries/<tail>` | Mojang 库本机可直连，但镜像也通 |
| 其它 | 原样 | — |

三者的顺序是「落地镜像直连 → CreeperHost → 官方」：落地镜像最快（0.19 s，国内直连），CreeperHost 只在前者缺件时才被用到，官方地址永远排最后（本机连不上，但换台机器就可能通，不能写死成「只有镜像」）。

**为什么要加落地镜像兜底**：首跑 54/58 成功、4 个反复超时。单独用 `curl -L` 拉这 4 个全部 200 且字节数对；直连 `mirror.nyist.edu.cn` 是 0.19 s，走 `bmclapi2` 是 0.50 s。说明不是超时配置（read 60 s / connect 15 s）不够，而是**302 跳转链在某些并发连接上卡死**。加上 `mirror.nyist.edu.cn` 直连后一次过。

顺带记录两个不能用的地址：`https://bmclapi2.hurtmc.com/maven/...` 与 `https://bmclapi.zhuqiy.top/maven/...` 都是 **404**，别当备选。

### 3.5 落地镜像也会缺件：1.20.4 的 JarJarFileSystems-0.4.0

2026-11-14 拿 `1.20.4 + NeoForge 20.4.253` 做端到端，预铺卡在 74/75 后硬失败：

```
INSTALL FAILED at step LOADER_INSTALL: NeoForge 预铺 NeoForge 依赖库失败：
NeoForge 依赖库有 1 个没下下来，安装器会因此装不全。
失败的库：JarJarFileSystems-0.4.0.jar：下载失败：Connect timed out
```

逐项探测的结果：该 installer 的 83 个库（去重后 path）里 64 个来自 `maven.neoforged.net`，其中**只有 `net/neoforged/JarJarFileSystems/0.4.0/JarJarFileSystems-0.4.0.jar` 在 `bmclapi2` 与 `mirror.nyist.edu.cn` 上都是 404** —— 同一个 group 的 `JarJarSelector/0.4.0`、`JarJarMetadata/0.4.0` 都是 200，连 `JarJarFileSystems/0.4.1` 都是 200，是单文件级别缺件。而本机官方域名 TLS 被重置，于是三个地址全废。

试过并排除的公共 maven（全部 404 或不可用）：`repo1.maven.org`、`maven.aliyun.com`（三个 repo 都试）、`repo.huaweicloud.com`、`mirrors.huaweicloud.com`、`mirrors.tuna.tsinghua.edu.cn`、`mirrors.ustc.edu.cn`、`mirrors.bfsu.edu.cn`、`mirror.sjtu.edu.cn`、`mirrors.cloud.tencent.com`、`maven.minecraftforge.net`、`maven.blamejared.com`、`jitpack.io`、`repo.spring.io/plugins-release`（401）、`mirror.nyist.edu.cn/maven`。

唯一命中全部 64 个的是 **`https://maven.creeperhost.net/<原 releases 后路径>`**（探测脚本 `dist/_probe_ch.py` 输出 `creeperhost ok 64 bad 0`）。它因此成了 `mirrorUrls()` 的第二道兜底，见 3.4 的表。

教训：**「BMCLAPI 有」不等于「每个文件都有」**，镜像缺件只会在大版本线里偶发一两个文件，单测用假数据永远发现不了——这种缺陷只能靠换版本真跑 e2e 撞出来。

### 3.6 缺库必须硬失败，不能放过

installer 对缺库的行为是「下不来就跳过继续」，最后照样打印 `Successfully installed client into launcher.`——留下一个看着装好、一启动就崩的目录。所以 `prepareLibraries` 下完自己校验，有任何一个失败就抛 `IOException`，文案把前三个失败库名与原因列出来。

## 四、NeoForge 版本号 → Minecraft 版本

**这是最容易做错的一步。** maven 的 `<version>` 字段不可靠（`26.3.0.101` 的 pom 写 `26.3-1`、`21.1.265` 写 `1.21.1-20240808.144430`），前缀猜测也有反例。唯一可靠的是 **installer jar 根目录的 `version.json` 里的 `inheritsFrom`**。

实测 10 个样本：

| NeoForge | inheritsFrom |
| --- | --- |
| 20.2.93 | 1.20.2 |
| 20.3.8-beta | 1.20.3 |
| 20.4.253 | 1.20.4 |
| 21.0.167 | 1.21 |
| 21.1.265 | 1.21.1 |
| 21.3.100 | 1.21.3 |
| 21.11.48 | 1.21.11 |
| 26.1.2.126 | 26.1.2 |
| 26.2.0.93 | 26.2 |
| 26.3.0.101 | 26.3 |

规律（`NeoForgeMeta.guessMinecraftVersion` 就按这个写）：去掉最后一段构建号，若剩下的最后一段是 0 也去掉；首段 1..21 补 `1.` 前缀，26 起不补。**前缀只是初筛**，install 时一定会用 `readInheritsFrom` 复核，对不上直接抛「NeoForge X 是给 Minecraft Y 的，不是 Z」。

本轮实跑日志里那行是：`复核目标游戏版本：已确认 21.1.265 对应 Minecraft 1.21.1`。

## 五、跑的流程（`NeoForgeInstaller` 七步）

| 步骤 | `NeoForgeStep` | 干什么 |
| --- | --- | --- |
| 1 | `VERSIONS` | 拉 `maven-metadata.xml`（68838 B、1856 个版本），按 MC 版本过滤 |
| 2 | `INSTALLER` | 下 `neoforge-<v>-installer.jar`（21.1.265 = 6 992 412 B）；已下过且是合法 jar 就复用 |
| 3 | `VERIFY_TARGET` | 读 jar 内 `version.json` 的 `inheritsFrom` 复核 |
| 4 | `PREPARE` | 缺才写 `launcher_profiles.json` |
| 5 | `PREPARE_LIBS` | **预铺 70 个依赖库**（本轮新增，见第三节） |
| 6 | `RUN` | `java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar <installer> --installClient <gameRoot>`，逐行刷日志 |
| 7 | `VERIFY` + `CLIENT_JAR` | 校验 `versions/neoforge-<v>/neoforge-<v>.json`；把 `versions/<原版>/<原版>.jar` 复制成 `neoforge-<v>.jar` |

版本目录里只有两个文件：

```
versions/neoforge-21.1.265/neoforge-21.1.265.json   21 253 B
versions/neoforge-21.1.265/neoforge-21.1.265.jar    26 836 906 B   ← 复制自原版 1.21.1
```

json 关键字段：`id = neoforge-21.1.265`（**不带原版号前缀**，与 Forge 的 `1.20.1-forge-47.4.28` 不同）、`inheritsFrom = 1.21.1`、`mainClass = cpw.mods.bootstraplauncher.BootstrapLauncher`、`arguments.game` 含 `--fml.neoForgeVersion 21.1.265` / `--fml.mcVersion 1.21.1` / `--launchTarget forgeclient`。

## 六、端到端实测（1.21.1 + NeoForge 21.1.265，空目录起步）

```
gradle :app-desktop:neoForgeE2e -PnfDir=dist/_test-mc/_nf1211 -Pgv=1.21.1 -Pe2eTimeoutMs=900000 -PskipOrig=false
```

| 阶段 | 数字 |
| --- | --- |
| 原版 1.21.1 下载 | 3960 个文件 / 100%，`versions/1.21.1/1.21.1.jar` 26 836 906 B、`1.21.1.json` 38 408 B、assets 796 MB、libraries 64 MB |
| Java | 自动下载 `java-runtime-delta`（1.21.1 的 json 要求 Java 21：`javaVersion.majorVersion = 21`） |
| 查 NeoForge 版本 | 挑到 21.1.265（同 MC 版本里最后一个 stable） |
| 预铺依赖库 | 4 个新下 + 66 个已存在校验通过，**约 1.3 秒** |
| installer 自身耗时 | 41 秒（22:49:23.527 → 22:50:04.774 `Successfully installed client into launcher.`） |
| 整段 LOADER_INSTALL | 54 752 ms（`INSTALL OK`） |
| 进主菜单 | `launchMs = 14 871`，`menuReached = true` |
| 总耗时 | `TOTAL ms = 75 197` |

游戏日志里的证据行：

```
NeoForge mod loading, version 21.1.265, for MC 1.21.1
Found mod file "neoforge-21.1.265-universal.jar" [locator: PathBasedLocator[name=neoforge, …]]
Launching target 'forgeclient' with arguments [--version, neoforge-21.1.265, --gameDir, …]
Reloading ResourceManager: vanilla, mod_resources, mod/neoforge
```

装完 `dist/_test-mc/_nf1211/libraries` 下共 **131 个 jar**（70 个 Neoforge/Mojang 依赖 + 原版 1.21.1 自己的库 + processor 中间产物 `client-*.jar`）。

### 6.1 同一轮补测：1.21.1 + 21.1.266（2026-11-14）

```
gradle :app-desktop:neoForgeE2e -PnfDir=dist/_neoforge-e2e -Pgv=1.21.1 -PneoforgeVersion=21.1.266 -PskipWix
```

| 阶段 | 数字 |
| --- | --- |
| 原版 1.21.1 下载 | 3962 个文件 / 0 失败 |
| Java | 自动下载 `java-runtime-delta` 21（Microsoft，`managed=true`） |
| 预铺依赖库 | 58 条，全部一次过 |
| installer 自身耗时 | 59 007 ms，`Successfully installed client into launcher.` |
| 整段 LOADER_INSTALL | 620 208 ms → `INSTALL OK`（含首次下 assets） |
| 进主菜单 | `launchMs = 10 742`，`menuReached = true` |
| 总耗时 | `TOTAL ms = 636 469` |

产物 `versions/neoforge-21.1.266/`：json 21 253 B（`inheritsFrom = 1.21.1`、`mainClass = cpw.mods.bootstraplauncher.BootstrapLauncher`）、`neoforge-21.1.266.jar` 26 836 906 B、`libraries/` 131 个 jar，整目录 1.1 GB（assets 798 MB / libraries 152 MB / runtime 96 MB / versions 56 MB）。

### 6.2 跨版本补测：1.20.4 + 20.4.253（2026-11-14，就是 3.5 那个缺件）

```
gradle :app-desktop:neoForgeE2e -PnfDir=dist/_neoforge-e2e-1204 -Pgv=1.20.4 -PneoforgeVersion=20.4.253 -PskipWix
```

第一次跑挂在预铺（`JarJarFileSystems-0.4.0`，见 3.5）。加上 CreeperHost 兜底后重跑一次通过：

```
LOADER_INSTALL 复核目标游戏版本：已确认 20.4.253 对应 Minecraft 1.20.4
LOADER_INSTALL 预铺 NeoForge 依赖库：预铺依赖库 1 个（82 个已存在） 0%
LOADER_INSTALL 预铺依赖库 1/1 100%                      ← 只补那一个缺件，3.3 秒
安装器：File …\JarJarFileSystems-0.4.0.jar exists. Checksum valid.   ← installer 直接跳过
INSTALL OK in 46471 ms; javaInstalled=false
vanilla downloaded=3856 failures=0
neoforge installer=neoforge-20.4.253-installer.jar exit=0 ms=40988
RESULT menuReached=true launchMs=13552
```

补下来的 `JarJarFileSystems-0.4.0.jar` = 31 835 B、sha1 `ef7e5716525bbe50c784a362f9393457a33e6daf`，与 installer 的 `version.json` 里声明的 sha1/size **完全一致**——说明 CreeperHost 给的是原版文件，不是被替换过的。游戏日志里 `NeoForge mod loading, version 20.4.253, for MC 1.20.4 with MCP 20240627.114801` 出现即证明加载器真的起来了。

### 6.3 幂等复跑：同一个目录再跑一次（2026-11-14 12:51）

```
gradle :app-desktop:neoForgeE2e -PnfDir=dist/_neoforge-e2e -PskipWix    ← 不传 -PneoforgeVersion，走默认
```

| 阶段 | 数字 | 说明 |
| --- | --- | --- |
| 原版 1.21.1 | `downloaded=3962 failures=0` | 文件都在，全部被 sha1 跳过 |
| installer | `exit=0 ms=581`、`bytes=0` | 581 ms 是因为 `Install` 发现目标已装好直接返回；`bytes=0` 表示没重新下 installer jar |
| 整段 | `INSTALL OK in 6268 ms; javaInstalled=false` | 无 Java 下载（已就位） |
| 进主菜单 | `launchMs = 12930`、`menuReached = true`、`exitCode = 1` | 正常进菜单后自己退出（1.21.1 的既定行为） |
| 总耗时 | `TOTAL ms = 24523` | 对比 6.1 的 636 469 ms |

这条不是新功能，是验「重跑不会把已装环境搞坏」：第二次跑完全不碰网络下载，581 ms 走完 installer，游戏仍能进主菜单。**注意 `-PnfDir` 是必填**，漏了会报 `缺少 -PnfDir=<游戏目录>（建议 dist/_neoforge-e2e）` 且 BUILD FAILED——第一次跑就踩了这个。

**6.1 与 6.2 两条线同时说明**：1.20.4 与 1.21.1 生成的 instance json 形状一致（都是 `mainClass = cpw.mods.bootstraplauncher.BootstrapLauncher`、四个 `--fml.*` 参数：`neoForgeVersion` / `fmlVersion` / `mcVersion` / `neoFormVersion`），所以安装器代码一条路走两个大版本，不需要按版本分支。真正的差异只在库清单内容上——20.4.253 的 188 条 profile 库 / 83 个去重 path 比 21.1.266 更大一些。

## 七、界面

下载页在「顺带装 Forge」下面多一行「顺带装 NeoForge」：

- 三个勾选框（Fabric / Forge / NeoForge）**互斥**，勾一个自动清另两个；
- 版本号框为空时状态写「先选一个原版版本」，不发请求；
- 输入停顿 400 ms 后拉 `maven-metadata.xml`（68 KB，比 Fabric 的 loader 列表大，比原版清单小得多）；
- 默认落在该 MC 版本里**最后一个 stable**，展开可换；
- 状态行写「NeoForge <版本>（官方安装器 + 预铺依赖库，约 2 分钟）」；
- 按钮文案随之变成「安装 NeoForge」。

## 八、已知取舍

| 取舍 | 理由 / 代价 |
| --- | --- |
| 元数据走 BMCLAPI 镜像 | 官方 `maven.neoforged.net` 在本机被重置，直连必挂；代价是镜像的可用性不受我们控制 |
| 依赖库三道兜底 | 落地镜像（快）→ CreeperHost（全）→ 官方（换环境保底）；代价是每个库最多试三次，缺件时最后那次官方地址要等一个 connect timeout（实测约 29 秒才落到第三道） |
| 依赖官方 installer 子进程 | 不重写 processor 流水线（同 Forge 的取舍）；代价：要本机有 Java 21，且 installer 自己会去拉 Mojang 清单 |
| 预铺依赖库 | 多一次下载循环，换来 installer 完全不联网下库；代价：`<游戏目录>/libraries` 会更早被填满 |
| 缺库硬失败 | 宁可拦住也不留「看着装好其实崩」的目录 |
| 安装器缓存 | `<游戏目录>/neoforge-installers/`，重装不再下 7 MB |
| 不做 `--installServer` | 只做客户端 |

## 九、还没做到

- ~~**`GameJavaRules.requiredMajor` 对 26.x 这类无 `1.` 前缀的版本会落到 else → Java 8**~~ —— **2026-11-14 已修**：新增年份式分支（`YearVersionRegex = ^(\d{2})\.\d`），26.1 起返回 25。同日把周号快照也从「一律 21」改成分段（`13w16a–21w18a`→8、`21w19a–22w02a`→16、`22w03a–24w13a`→17、`24w14a–25w46a`→21、`26w14a` 起→25），边界取自官方 744 个快照的 `javaVersion.majorVersion` 实测跃迁点，复算只差 `1.18-pre1` 一个。依据见 docs/05 的 `GameJavaRules` 条目。
- 安装器升级判断：按 `versionId` 命中就跳过，不比对 installer 自身版本
- NeoForge 的 `--offline` / `--skip-hash-check` 没用上（预铺已覆盖需求）
- ~~老版本（`20.2.x` 之前）没测过~~ —— **2026-11-14 已补测 `1.20.4 + 20.4.253`**（见 6.2），20.2.x 及更早的线仍未测
- 镜像缺件只能撞出来，没有自动「逐个文件核对镜像清单」的预检；目前是失败后人工查（`dist/_probe_mirror.py` / `dist/_probe_ch.py`），没进产品代码
