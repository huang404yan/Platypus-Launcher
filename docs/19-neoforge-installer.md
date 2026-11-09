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
| `https://libraries.minecraft.net/` | `https://bmclapi2.bangbang93.com/libraries/<tail>` | Mojang 库本机可直连，但镜像也通 |
| 其它 | 原样 | — |

**为什么要加落地镜像兜底**：首跑 54/58 成功、4 个反复超时。单独用 `curl -L` 拉这 4 个全部 200 且字节数对；直连 `mirror.nyist.edu.cn` 是 0.19 s，走 `bmclapi2` 是 0.50 s。说明不是超时配置（read 60 s / connect 15 s）不够，而是**302 跳转链在某些并发连接上卡死**。加上 `mirror.nyist.edu.cn` 直连后一次过。

顺带记录两个不能用的地址：`https://bmclapi2.hurtmc.com/maven/...` 与 `https://bmclapi.zhuqiy.top/maven/...` 都是 **404**，别当备选。

### 3.5 缺库必须硬失败，不能放过

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
| 依赖官方 installer 子进程 | 不重写 processor 流水线（同 Forge 的取舍）；代价：要本机有 Java 21，且 installer 自己会去拉 Mojang 清单 |
| 预铺依赖库 | 多一次下载循环，换来 installer 完全不联网下库；代价：`<游戏目录>/libraries` 会更早被填满 |
| 缺库硬失败 | 宁可拦住也不留「看着装好其实崩」的目录 |
| 安装器缓存 | `<游戏目录>/neoforge-installers/`，重装不再下 7 MB |
| 不做 `--installServer` | 只做客户端 |

## 九、还没做到

- **`GameJavaRules.requiredMajor` 对 26.x 这类无 `1.` 前缀的版本会落到 else → Java 8**（正则 `^(1)\.(\d+)…` 不匹配）。实际路径上 `OneClickInstaller` 优先用版本 json 的 `javaVersion.majorVersion`，所以只在版本文档缺该字段时才出错。**未验证会不会真踩到。**
- 安装器升级判断：按 `versionId` 命中就跳过，不比对 installer 自身版本
- NeoForge 的 `--offline` / `--skip-hash-check` 没用上（预铺已覆盖需求）
- 老版本（`20.2.x` 之前）没测过
