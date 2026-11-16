# 发布产物目录

两条产品线分开放，互不覆盖。

| 目录 | 产品 | 版本号规则 |
|---|---|---|
| `stable/` | 鸭嘴兽启动器 / Platypus Launcher | `1.0.0` 起进位 |
| `preview/` | 鸭嘴兽·孵化版 / Platypus Preview | `0.x-preview.N` |

两边各自再按平台分：`android/`、`windows/`、`linux/`。

## 两种包：正式版，和孵化版（开源版 = 空壳）

从 0.8.0-preview.20 起，出包只有两种，要求和边界都不一样。**发之前先认清自己要发哪一种。**

### 一、正式版（`stable/`）

- 由本项目构建发布，功能完整：下载、加载器安装、模组搜索、联机，全都带。
- 内置微软应用 ID（`-Pplatypus.msClientId=` 或 `PLATYPUS_MS_CLIENT_ID`），下载的人什么都不用填。
- 首次启动要求登录一个正版账号，**验证一次就够了**（PCL-CE、HMCL 都是这个口径）：
  登录过一次之后一直记着，之后打开不会再问。允许它离线进游戏等于给盗版留一条明面上的入口，
  而「这个启动器不帮你盗版」本来就是这类项目站得住的前提。
- 源码不随包走，但仓库主干与它是同一份代码 —— 差别只有版本号与是否注入凭据。

### 二、孵化版 / 开源版（`preview/`，空壳）

- **是空壳**：包里不带任何下载与搜索服务地址。版本清单、加载器、模组搜索这些要联网的东西，
  都得用户自己去「设置 → 下载 → 服务地址」填一个镜像根才能用。没填之前界面照旧能看、
  本机已有的实例照旧能改，只是下载页与模组搜索是空的。
- **是给其他人制作的**：这条线存在的意义是「谁能编，谁就能发」。任何人 fork 这个仓库、
  按下面的命令打一份包，就是他自己的一份孵化版。所以它不内置别人的应用 ID，也不带别人的镜像。
- 因为源码完全公开，对着能随手改的代码设卡没有意义：孵化版不强制正版登录，
  首次启动只弹一次窗，讲清这个包缺的是服务地址、去哪儿填。
- 发布时必须勾 Pre-release、不标 Latest。

### 出包命令

```bash
# 正式版（完整，注入自己的 client ID）
./gradlew :app-desktop:createDistributable -Pplatypus.msClientId=<你的 ID>

# 孵化版 / 开源版（空壳）
./gradlew :app-desktop:createDistributable -Pplatypus.shell=true
```

空壳开关写进 `core/build/generated/.../BuildInfo.kt` 的 `SHELL`，运行期改不了；
所有联网地址都从 `core/Endpoints.kt` 一个出口出，空壳包里它们全走用户填的镜像根。
不加开关时构建出来的是完整版 —— 本机调试与单测都跑在这一边上。

### 谁不许往里放什么

两种包都不许带私有凭据：任何一份随安装包发出去的 ID，都等于所有下载者共用一份别人名下的
应用注册，被限流、被收紧、被认定滥用，都不是拿到包的人能处理的。仓库里那一份永远是空串，
取值顺序在 `core/auth/ClientIds.kt`。

两边的实现与理由见 `core/Endpoints.kt`（地址的唯一出口）与 `core/edition/`（两条通道的差异）。

## 孵化版不占正式版版本号

`0.x-preview.N` 与 `1.0.0` 是两条独立的号段，**互不占用**：

- 孵化版发到 `0.9-preview.N`、`0.10-preview.N` 都行，正式版照样从 `1.0.0` 开始。
- 正式版一旦发布，版本号只往前走；孵化版继续停留在 `0.x-preview.N`，不会「抢」正式版的号。
- 在 GitHub 上发 Release 时，孵化版**必须勾选 Pre-release**，不要标成 Latest，否则用户会把测试版当正式版下。
- 同一版本号不允许同时出现在 `stable/` 和 `preview/` 下。

## 每个平台最多留 5 个

按文件名里的版本号排序，超出 5 个时删最旧的。删之前确认新包能装上、能启动——**不许先删后测**。

注意空壳包与完整包的**文件名必须能区分**（见下面的命名），否则过几天就分不清哪个是给谁验的。

## 命名

```
Platypus-Launcher-<ver>-android.apk
Platypus-Launcher-<ver>-win-x64.msi
Platypus-Launcher-<ver>-win-x64.zip
Platypus-Launcher-<ver>-linux-x64.AppImage
```

`<ver>` 就是完整版本号，带 preview 后缀，例如 `0.8.0-preview.1`、`1.0.0`。文件名里已经能区分正式版和孵化版，不要再加别的标记。

唯一例外是 v0.8.0-preview.20 起加的**空壳包**：它与完整版同版本号，只能靠文件名区分，
统一加 `-shell` 后缀，例如 `Platypus-Launcher-0.8.0-preview.20-win-x64-shell.zip`。
空壳包默认只出免装 zip（它本来就是给人验「填服务地址前后差多少」的）；要 msi 就同样加 `-shell`。

### Windows 是两种形态，不是二选一

jpackage 在 Windows 上只能产出 **msi 安装包** 或 **app-image 目录**，两者都交付：

| 后缀 | 来源任务 | 用户拿到后怎么做 |
|---|---|---|
| `.msi` | `:app-desktop:packageMsi` | 双击安装，有开始菜单快捷方式，可卸载 |
| `.zip` | `:app-desktop:createDistributable` 后打包 app-image 目录 | 解压到任意位置，双击 `<包名>\<包名>.exe` 直接跑，不写注册表 |

**没有 `-win-x64.exe` 这个单文件**：jpackage 不产出独立 exe，早期的 `.exe` 命名是对产物形态的误判，已废弃。zip 里那个 exe 必须和同目录的 `app/`、`runtime/` 一起用，单独拷走跑不起来。

两种形态里同一个版本可以同时存在（安装版给普通用户，zip 给不想装东西的人），但**合计**受下面的 5 个上限约束。

### 打 msi 需要本机有 WiX

jpackage 打 msi 依赖 WiX Toolset 3.x 的 `candle.exe` / `light.exe`。本机的 WiX 放在仓库外面（**不安装、不进仓库**），构建时由根 `build.gradle.kts` 复制进 `build/wix311/`：

```
# 三种方式任选其一（按优先级）
gradle.bat -PskipWix -PwixDir=D:\ruanjian\kaifa\wix314 :app-desktop:packageMsi   # 1) 显式参数
set PLATYPUS_WIX_DIR=D:\ruanjian\kaifa\wix314                                    # 2) 环境变量
# 3) candle.exe 在 PATH 里，自动跟着找
```

`-PwixDir` 指到 WiX 整包目录或它下面的 `bin/` 都可以。**必须配合 `-PskipWix`**：启动参数本身会禁用 downloadWix（不去 GitHub 下载），然后由上面的逻辑铺好 `build/wix311`。

`build/` 会被 `clean` 清掉，所以 `clean` 之后第一次打包 msi 会重新复制一次（约 117 MB，几秒）。只出 zip（`createDistributable`）时完全不需要 WiX。

## 产物不进仓库

这个目录（除本文件）已在 `.gitignore` 里，**只是本机的暂存区**。对外分发走 GitHub Releases 的附件：仓库历史只放源码与构建必需的文件，二进制一旦提交就删不掉，只会把仓库撑大（当前 5 个 Android 包已 60 MB，桌面安装包一个就 60 MB 上下，一年下来轻松过 GB）。

出包流程：本地构建 → 放进对应的 `<通道>/<平台>/` → 发 Release 时上传该文件 → 旧的本地包按上面的 5 个上限清理。

## 不放什么

- 签名私钥、`keystore`、`*.jks` —— 永远不进仓库
- 构建中间产物（`build/`、`.gradle/`）—— 已在 `.gitignore`
- 调试版 —— 只放对外可安装的包

### 空壳包不带别人的凭据，也不带任何隐私

孵化版（空壳）是公开分发、由其他人自己构建的包，下面是硬约束，出包前逐条过一遍：

- **不内置第三方 Client ID**。OAuth 的 `client_id` 不是密钥，但内置一个人家注册的 ID，
  等于让所有下载者共用同一份配额、共享同一个被随时关闭的风险。孵化版出包时既不能传
  `-Pplatypus.msClientId` 也不能设 `PLATYPUS_MS_CLIENT_ID`，`BuildInfo.MS_CLIENT_ID` 与
  `MicrosoftEndpoints.DEFAULT_CLIENT_ID` 都必须是空串，正版登录由用户自己注册
  （步骤在界面「设置 → 账号」第一张卡里，取值顺序见 `core/auth/ClientIds.kt`）。
  **正式版是另一回事**：由构建方在自己机器上注册并注入，那份 ID 属于发行方，不随源码走。
  三条路的优先级见 `core/auth/ClientIds.kt`。
- **空壳包还要确认真的没有服务地址**：`-Pplatypus.shell=true` 出包后，
  `BuildInfo.kt` 里 `SHELL` 必须是 `true`，并且包里不要残留任何内置镜像根。
- **不带任何隐私内容**：真实账号 / UUID / refresh_token、本机路径与用户名、
  诊断页导出的现场、日志文件、`settings.json`、`accounts.json`、`.minecraft` 里的任何东西。
- **不留申请指引的缺口**：Release 说明必须有一段讲「正版登录要先自己填一个应用 ID」，
  并写清去哪儿填（界面「设置 → 账号」第一张卡）。指引只写在仓库里不算 —— 用户拿到的是附件。

出包前自检（改完代码到打包之间跑一次）：

```bash
# 排除 src/test：单测里那串 ID 只是「形状合法的样本」，不是真要用的凭据
grep -rn "04b07795\|1950a258" --include=*.kt --exclude-dir=test .   # 应无输出
grep -rn "C:\\Users\\|/home/[a-z]" --include=*.kt --include=*.json .   # 应无输出
grep -n "DEFAULT_CLIENT_ID" core/src/main/kotlin/io/github/huang404yan/platypus/core/auth/MicrosoftAuth.kt
# 第四条应打印：const val DEFAULT_CLIENT_ID = ""
```

孵化版还要确认构建产物里那份是空的（前一条只查了源码里的常量，注入的值走的是另一条路）：

```bash
# 出完包在构建目录里查一眼，内容应是 const val MS_CLIENT_ID: String = ""
grep -n "MS_CLIENT_ID" core/build/generated/platypus/kotlin/io/github/huang404yan/platypus/core/BuildInfo.kt
# 环境变量别留在 shell 里（Windows 的 set 与 bash 的 env 都查一下）
env | grep PLATYPUS_MS_CLIENT_ID    # 应无输出
# 空壳包：这一行应打印 true
grep -n "SHELL" core/build/generated/platypus/kotlin/io/github/huang404yan/platypus/core/BuildInfo.kt
```

IDE 或命令行里为了调试设过 `-Pplatypus.msClientId=` / `PLATYPUS_MS_CLIENT_ID` 的话，
出包前必须清掉，否则那个开发用的 ID 会静默进到孵化版包里。