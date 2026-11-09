# Windows 安装包（MSI）构建实记

2026-11-09 实测跑通。本文只写已验证的做法与数字，不写计划。

## 结论

`:app-desktop:packageMsi` 在本机能成功出包，前提是用**「WiX 二进制预置 + `-PskipWix`」**的组合绕过 compose 插件的在线下载。

产物：`app-desktop/build/compose/binaries/main/msi/Platypus Preview-0.8.0.msi`
- 文件头 `d0cf11e0a1b11ae1`（标准 OLE2 复合文档，即真实 MSI）
- **msi 不是可重现构建**：同样的源码连打两次，字节数与 sha256 都会不同（内含时间戳与 CAB 排布），但解包出来的应用内容完全一致。两次实测：

| 出处 | 字节 | sha256 |
|---|---|---|
| 首次（captain 打，已归档，**已换出**） | 64,565,356 | `0c234da25a8f3bb17c1aa635367e5381e4afd6712b51d9c65e7bde1f53b1731b` |
| 二次（自动铺 WiX 后重打） | 64,573,548 | `5f0b16b8c94f5c4fb74f36030fc28aad2dd46ba347f5745126a35cf7011a2891` |
| 三次（验收时，一键装前） | 64,712,812 | `2d3e8fc9d44d3690620464f66ee0b68e89de5929120430e91d291be3a334d3f2` |
| **四次（2026-11-09 14:57，含一键装闭环）** | **64,815,212** | `ed466ab324d693efe4ff9f60a89f3a7521b99287dbc5505c944ea4ce5fe92a1a` |

- 归档副本（**现行 = 上表「四次」**）：`releases/preview/windows/Platypus-Launcher-0.8.0-preview.1-win-x64.msi`，64,815,212 字节。此前那三份都早于「一键装闭环」代码，包内没有 `OneClickInstaller.class` / `MojangJavaRuntimeInstaller.class`，已移到 `dist/_old-releases/win-0909/`。
- 「二次」那份解包后 exe 597,504 字节、sha256 `7f2ba938c55661e4bb98da02f6e85c936d78fded763931d08d5c217ed730a7ed`，与 app-image 里的 exe 是同一个（已实测）。
- 「四次」（现行归档）也做过解包复核：2026-11-09 15:01 `msiexec /a` 解出 `Platypus Preview.exe` 597,504 字节，sha256 同上 `7f2ba938...0a7ed` —— 与 app-image、免安装 zip 三处一致（**已实测**）。
- **校验 msi 请比解包出来的 exe 的 sha256，不要比 msi 文件本身的 sha256**——后者每次都变。

## 为什么需要这个组合

两个机制互相咬合，缺一不可：

1. **compose 桌面插件**在 Windows 上无条件注册 `:downloadWix` 任务，会把 `wix3112rtm` 从 GitHub 下下来解压到 **`<root>/build/wix311`**。没网或网慢就卡死在这里（实测本机 GitHub 直连只有约 120 KB/s）。
2. **jpackage** 打 msi 时找 WiX 的位置，恰好也是 **`<root>/build/wix311`**。

而根 `build.gradle.kts` 的 `skipWix` 分支做了两件事：把 `Wix` 任务禁掉、并保证 `<root>/build/wix311` 里有真的 candle/light（2026-11-09 之前这里只 `mkdirs()` 建空目录，现已改为自动从本机 WiX 源复制）。

目录空着时 jpackage 会报：

```
找不到 WiX 工具。已查找 WiX v3 light.exe 和 candle.exe 或 WiX v4/v5 wix.exe，但都未找到
错误：类型 [msi] 无效或不受支持
```

**所以关键是：这个目录里必须有真的 WiX。** 文件齐了，jpackage 就满意了，全程不需要联网。

## 一次完整的可复现步骤

### 1. 取得 WiX v3（jpackage 只认 v3 的 candle/light）

```
https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip
```

- 41,297,555 字节
- sha256 `6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31`
- 解压得到 71 个条目、16 个 exe：`candle.exe light.exe heat.exe insignia.exe lit.exe lux.exe melt.exe nit.exe pyro.exe retina.exe shine.exe smoke.exe torch.exe ThmViewer.exe dark.exe WixCop.exe`
- 校验：`candle.exe -?` 应打印 `Windows Installer XML Toolset Compiler version 3.14.1.8722`

本机已解压在 `D:\ruanjian\kaifa\wix314\bin`（安装位置，不属于仓库；仓库里不提交任何 WiX 二进制）。

**注意不要用 WiX v4/v5**——它们是单个 `wix.exe`，而 jpackage 25 的检测顺序是先找 v3 的 light/candle。

### 2. 让构建脚本自己铺进 `build/wix311`（2026-11-09 起自动）

根 `build.gradle.kts` 的 `skipWix` 分支会自动把 WiX 复制进 `build/wix311`。源目录按以下顺序找，取第一个命中的：

1. `-PwixDir=<目录>` 项目属性
2. `PLATYPUS_WIX_DIR` 环境变量
3. `PATH` 里第一个含 `candle.exe` 的目录

传的目录既可以是 WiX 整包目录（`D:\ruanjian\kaifa\wix314`），也可以是它的 `bin`（`...\wix314\bin`）——脚本会自己判断往里再走一层 `bin`。复制目标固定是 `<root>/build/wix311`，`bin` 里的内容**直接铺平**（不会多套一层 `bin/`）。已经铺好的话会打印「复用已有」，不重复复制那 117 MB。

```
-PskipWix -PwixDir=D:\ruanjian\kaifa\wix314
```

实测（先把 `build/wix311` 移走模拟 clean）：`BUILD SUCCESSFUL in 38s`，日志里两行

```
[wix] 从 D:\ruanjian\kaifa\wix314\bin 复制 WiX 到 ...\platypus\build\wix311
[wix] 复制完成：candle.exe=true
```

只想手工铺一次也可以用回老办法：`cp -r /d/ruanjian/kaifa/wix314/bin/* <repo>/build/wix311/`。

> **实测范围说明**：`-PwixDir` 已实测（上面 38s 那次）。`PLATYPUS_WIX_DIR` 与 `PATH` 两条分支走同一段代码但**未单独跑通**（执行器连续报 `EXECUTOR_UNAVAILABLE`），属「按代码推断可用」，不是实测结论。

### 2b. 手工铺（老办法，仍可用）

```bash
cp -r /d/ruanjian/kaifa/wix314/bin/* /d/ruanjian/zhinengti/gongzuokongjian/platypus/build/wix311/
```

### 3. 构建

```
MSYS_NO_PATHCONV=1 cmd.exe /c "D:\ruanjian\kaifa\qidongqigongju\gradle-9.9.0\bin\gradle.bat --no-daemon -PskipWix :app-desktop:packageMsi"
```

实测 `BUILD SUCCESSFUL in 32s`（20 actionable tasks: 3 executed, 17 up-to-date）。

**必须带 `-PskipWix`**。不带的话 `:downloadWix` 会真的去 GitHub 下载 wix3112rtm 并覆盖 `build/wix311`，慢到基本等于失败（实测卡住，日志停在 `> Task :downloadWix`）。

## 验证 MSI 真的能用

不要只看文件存在。用管理安装解包，能验出 MSI 表结构是否完好：

```
msiexec /a "Platypus Preview-0.8.0.msi" /qn TARGETDIR=<临时目录>
```

**必须用 `/qn`，不要用 `/qb`**：`/qb` 是带进度条的基础 UI，会一直等界面交互，非交互调用会挂住（实测一次挂了 6 分钟以上无输出，只能 `Stop-Process` 掉 msiexec）。

实测 EXIT=0，解出：

```
Platypus Preview/
  Platypus Preview.exe   597,504 字节
  app/
  runtime/
```

exe 字节数与 `createDistributable` 产出的 app-image 一致（597,504），说明两条通道打的是同一份应用。

## 已知限制

- **版本号**：jpackage 不接受 `-preview` 后缀，`app-desktop/build.gradle.kts` 里用 `packageVersion = fullVersion.substringBefore("-")` 裁成 `0.8.0`。所以 msi 里的 ProductVersion 是 `0.8.0`，文件名才是 `0.8.0-preview.1`。升级 UUID（`upgradeUuid`）在 preview/stable 两条通道各一个，互不覆盖。
- **`build/wix311` 会被 `clean` 清掉，但已不用手工重做第 2 步**：根 `build.gradle.kts` 的 `skipWix` 分支现在会自动把 WiX 铺进 `build/wix311`（见上文第 2 步）。只有找不到任何 WiX 源时才会留空目录、由 jpackage 报「找不到 WiX 工具」——那条报错现在**等于「本机没有 WiX 源」**，不再是「忘了手动复制」。
- **没有代码签名**。安装时 Windows SmartScreen 会拦；分发前要自行决定是否签名，本机没有证书。
- Linux 侧 `packageDeb`/`AppImage` 未在本次验证（本机是 Windows）。

## 与 app-image 的分工

两条产物都留着，用途不同：

| 产物 | 路径 | 用途 |
|---|---|---|
| app-image zip | `releases/preview/windows/Platypus-Launcher-0.8.0-preview.1-win-x64.zip`（**64,078,265 字节**，2026-11-09 15:06 换新） | 免安装、解压即用；用户双击 exe |
| msi | `releases/preview/windows/Platypus-Launcher-0.8.0-preview.1-win-x64.msi`（**64,815,212 字节**，2026-11-09 15:06 换新） | 正式安装、进「应用和功能」、可静默升级 |

两份都含「一键装闭环」代码（`OneClickInstaller.class`、`MojangJavaRuntimeInstaller.class` 已在包内，zip 用 `zipfile` 嵌套读 jar 证实）。归档 sha256：zip `284c1841c63c7c03695303d67f3b90aa218da3fc3b55655c70f96b881d301226`、msi `ed466ab324d693efe4ff9f60a89f3a7521b99287dbc5505c944ea4ce5fe92a1a`。
