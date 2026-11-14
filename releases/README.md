# 发布产物目录

两条产品线分开放，互不覆盖。

| 目录 | 产品 | 版本号规则 |
|---|---|---|
| `stable/` | 鸭嘴兽启动器 / Platypus Launcher | `1.0.0` 起进位 |
| `preview/` | 鸭嘴兽·孵化版 / Platypus Preview | `0.x-preview.N` |

两边各自再按平台分：`android/`、`windows/`、`linux/`。

## 孵化版就是测试版，也就是开源版

这条线**不是**「先发个阉割包探路，完整功能留给正式版」。孵化版的功能集合与正式版是同一份源码，
差别只有三处：版本号带 `-preview.N`、安装包里不带任何人的私有凭据、发布时标 Pre-release。
换言之——

- **功能上它就是完整版**：这一版做了什么，孵化版里就有什么，不存在「这个功能正式版才有」。
- **作为测试版发布**：勾 Pre-release、不标 Latest，用户看到的风险提示与之相符。
- **源码完全公开**：与仓库主干同源，打的 tag 指向同一个 commit。
- **不含私有凭据**：见下面的「不放什么」。安装包发出的那一刻，它必须能被任何人拿去审查、
  自己重新构建，并且不欠任何人一份账号或密钥。

## 孵化版不占正式版版本号

`0.x-preview.N` 与 `1.0.0` 是两条独立的号段，**互不占用**：

- 孵化版发到 `0.9-preview.N`、`0.10-preview.N` 都行，正式版照样从 `1.0.0` 开始。
- 正式版一旦发布，版本号只往前走；孵化版继续停留在 `0.x-preview.N`，不会「抢」正式版的号。
- 在 GitHub 上发 Release 时，孵化版**必须勾选 Pre-release**，不要标成 Latest，否则用户会把测试版当正式版下。
- 同一版本号不允许同时出现在 `stable/` 和 `preview/` 下。

## 每个平台最多留 5 个

按文件名里的版本号排序，超出 5 个时删最旧的。删之前确认新包能装上、能启动——**不许先删后测**。

## 命名

```
Platypus-Launcher-<ver>-android.apk
Platypus-Launcher-<ver>-win-x64.msi
Platypus-Launcher-<ver>-win-x64.zip
Platypus-Launcher-<ver>-linux-x64.AppImage
```

`<ver>` 就是完整版本号，带 preview 后缀，例如 `0.8.0-preview.1`、`1.0.0`。文件名里已经能区分正式版和孵化版，不要再加别的标记。

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

### 测试版不带别人的凭据，也不带任何隐私

孵化版是公开分发的包，下面是硬约束，出包前逐条过一遍：

- **不内置第三方 Client ID**。OAuth 的 `client_id` 不是密钥，但内置一个人家注册的 ID，
  等于让所有下载者共用同一份配额、共享同一个被随时关闭的风险。安装包里
  `MicrosoftEndpoints.DEFAULT_CLIENT_ID` 必须是空串，正版登录由用户自己注册
  （步骤见 `docs/41-zishen-client-id.md`，界面「设置 → 账号」第一张卡里也有一份缩写）。
- **不带任何隐私内容**：真实账号 / UUID / refresh_token、本机路径与用户名、
  诊断页导出的现场、日志文件、`settings.json`、`accounts.json`、`.minecraft` 里的任何东西。
- **不留申请指引的缺口**：Release 说明必须有一段讲「正版登录要先自己填一个应用 ID」，
  并指向 `docs/41-zishen-client-id.md`。指引只写在仓库里不算 —— 用户拿到的是附件。

出包前自检（改完代码到打包之间跑一次）：

```bash
# 排除 src/test：单测里那串 ID 只是「形状合法的样本」，不是真要用的凭据
grep -rn "04b07795\|1950a258" --include=*.kt --exclude-dir=test .   # 应无输出
grep -rn "C:\\Users\\|/home/[a-z]" --include=*.kt --include=*.json .   # 应无输出
grep -n "DEFAULT_CLIENT_ID" core/src/main/kotlin/io/github/huang404yan/platypus/core/auth/MicrosoftAuth.kt
# 最后一条应打印：const val DEFAULT_CLIENT_ID = ""
```