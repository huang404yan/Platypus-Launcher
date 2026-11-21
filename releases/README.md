# 发布产物目录

两条产品线分目录存放，互不覆盖。

| 目录 | 产品 | 版本号规则 |
|---|---|---|
| `stable/` | 鸭嘴兽启动器 / Platypus Launcher | `1.0.0` 起进位 |
| `preview/` | 鸭嘴兽·孵化版 / Platypus Preview | `0.x-preview.N` |

两侧各自再按平台分目录：`android/`、`windows/`、`linux/`。

> **`stable/` 目前应为空。** 2026-11-16 曾按 `1.0.0` 出过一批（msi / zip / apk），
> 试用后判定问题较多、不足以作为第一个正式版，代码已退回 `0.8.0-preview.21` 继续修复，
> 该批产物随之作废，可以删除。正式线自修复完成后重新从 `1.0.0` 起算。

## 两条产品线：正式版、孵化版（开源版）

自 1.0.0 起产物分为两条产品线。**发布前须确认本次发布的是哪一种。** 两条线用同一份代码，
区别只有版本号、是否注入应用 ID、以及孵化版固定用空壳开关构建。

### 一、正式版（`stable/`）

- 由本项目构建并发布，功能完整：下载、加载器安装、模组搜索、联机均已包含。
- 内置微软应用 ID（`-Pplatypus.msClientId=` 或 `PLATYPUS_MS_CLIENT_ID`），下载者无需填写任何内容。
- 首次启动要求登录正版账号，**验证一次后长期有效**（PCL-CE、HMCL 均为同一口径）：
  登录成功后持续记住，之后启动不再询问。允许离线进入游戏等同于为盗版提供明确入口，
  而「该启动器不支持盗版」是此类项目得以立足的前提。
- 源码不随包分发，但与仓库主干为同一份代码，差别仅在于版本号与是否注入凭据。
- 所属产品线仅由版本号判定：含 `-preview` 为孵化版，不含则为正式版（见 [`Brand.kt`](../core/src/main/kotlin/io/github/huang404yan/platypus/core/Brand.kt) 的 `isPreview`）。

### 二、孵化版 / 开源版（`preview/`）

- **对外发布的那一份是空壳**：包内不含任何下载与搜索服务地址（由 `-Pplatypus.shell=true` 构建）。
  版本清单、加载器、模组搜索得先在「设置 → 下载 → 服务地址」填一个镜像根才有内容，填完立刻生效、以后一直用；
  没填之前界面照旧能浏览、本机已有实例照旧能改，下载页与模组搜索是空的 —— 那不是坏了。
- **面向其他构建者**：这条线存在的意义是「能编译者即可发布」。任何人 fork 本仓库并按下列
  命令构建出包，即为其自己的孵化版；自己编一份带服务地址的也允许，那等于替使用它的人承担镜像流量。
- **不由本项目注入应用 ID**：需使用正版登录时请自行申请并填入「设置 → 账号」。
  随包分发的任何 ID 都等于让所有下载者共用他人名下的注册，由此产生的后果超出获取安装包者所能处理的范围。
- 源码完全公开，对可任意修改的代码设置校验并无实际作用：孵化版不强制正版登录，
  首次启动仅弹出一次说明窗口。
- 发布时必须勾选 Pre-release，且不标记 Latest。



### 出包命令

```bash
# 正式版（完整包，注入发行方自身的 client ID）
./gradlew :app-desktop:createDistributable -Pplatypus.msClientId=<你的 ID>

# 孵化版（空壳，不注入 ID：版本号含 -preview，并加 shell 开关）
./gradlew :app-desktop:createDistributable -Pplatypus.shell=true
```

空壳开关写入 `core/build/generated/.../BuildInfo.kt` 的 `SHELL`，运行期不可更改；
所有联网地址均由 [`Endpoints.kt`](../core/src/main/kotlin/io/github/huang404yan/platypus/core/Endpoints.kt) 统一输出，在空壳包中全部指向用户填写的镜像根。
本机调试与单元测试跑在完整包一侧（不带该开关）。

### 禁止放入的内容

**孵化版与空壳包**不得携带私有凭据：任何随安装包分发的 ID，都等于让所有下载者共用
他人名下的应用注册，由此导致的限流、配额收紧或滥用认定，均超出获取安装包者所能处理的范围。
仓库中该项恒为空串，取值顺序见 [`ClientIds.kt`](../core/src/main/kotlin/io/github/huang404yan/platypus/core/auth/ClientIds.kt)。正式版恰好相反：必须
由发行方注入其自行申请的那一份，该 ID 归发行方所有，不随源码分发。

两侧的实现与设计理由见 [`Endpoints.kt`](../core/src/main/kotlin/io/github/huang404yan/platypus/core/Endpoints.kt)（地址的统一出口）与 [`edition/`](../core/src/main/kotlin/io/github/huang404yan/platypus/core/edition)（两条通道的差异）。

## 孵化版不占正式版版本号

`0.x-preview.N` 与 `1.0.0` 是两条独立的号段，互不占用：

- 孵化版可发至 `0.9-preview.N`、`0.10-preview.N`，正式版仍从 `1.0.0` 起算。
- 正式版发布后版本号只向前推进；孵化版继续停留于 `0.x-preview.N`，不会占用正式版号段。
- 在 GitHub 发布 Release 时，孵化版**必须勾选 Pre-release**，不得标记为 Latest，否则用户易将测试版误当正式版下载。
- 同一版本号不得同时出现在 `stable/` 与 `preview/` 下。

## 每个平台最多保留 5 个

按文件名中的版本号排序，超出 5 个时删除最旧者。删除前须确认新包可安装、可启动：**不得先删后测**。

自己编的带服务地址的孵化版与官方发布的空壳孵化版**文件名必须可区分**（见下节命名规则），否则无法辨别各产物对应的验证对象。

## 命名

```
Platypus-Launcher-<ver>-android.apk
Platypus-Launcher-<ver>-win-x64.msi
Platypus-Launcher-<ver>-win-x64.zip
Platypus-Launcher-<ver>-linux-x64.AppImage
```

`<ver>` 为完整版本号，含 preview 后缀时即为孵化版，例如 `0.8.0-preview.1`、`1.0.0`。文件名本身已可区分正式版与孵化版，无需附加其他标记。

**官方发布的产物一律不带附加后缀**：正式版是完整包、孵化版是空壳，版本号本身就分得开。
自己编一份带服务地址的孵化版（不加 `-Pplatypus.shell=true`）时，文件名加 `-full` 后缀以区分，
例如 `Platypus-Launcher-0.8.0-preview.22-win-x64-full.zip`；不加后缀的按官方口径理解为空壳。

### Windows 为两种形态，并非二选一

jpackage 在 Windows 上只能产出 **msi 安装包** 或 **app-image 目录**，两者均需交付：

| 后缀 | 来源任务 | 获取后的使用方式 |
|---|---|---|
| `.msi` | `:app-desktop:packageMsi` | 双击安装，提供开始菜单快捷方式，可卸载 |
| `.zip` | `:app-desktop:createDistributable` 后打包 app-image 目录 | 解压至任意位置，双击 `<包名>\<包名>.exe` 直接运行，不写入注册表 |

**不存在 `-win-x64.exe` 单文件形态**：jpackage 不产出独立 exe，早期的 `.exe` 命名属于对产物形态的误判，已废弃。zip 中的 exe 必须与同目录的 `app/`、`runtime/` 配合使用，单独复制无法运行。

同一版本可同时存在两种形态（安装版面向普通用户，zip 面向不希望安装的用户），但**合计数量**受下节 5 个上限约束。

### 打 msi 需要本机有 WiX

jpackage 打包 msi 依赖 WiX Toolset 3.x 的 `candle.exe` / `light.exe`。本机 WiX 置于仓库之外（**不安装、不进仓库**），构建时由根 `build.gradle.kts` 复制至 `build/wix311/`：

```
# 三种方式任选其一（按优先级）
gradle.bat -PskipWix -PwixDir=D:\ruanjian\kaifa\wix314 :app-desktop:packageMsi   # 1) 显式参数
set PLATYPUS_WIX_DIR=D:\ruanjian\kaifa\wix314                                    # 2) 环境变量
# 3) candle.exe 在 PATH 里，自动查找
```

`-PwixDir` 可指向 WiX 整包目录或其下的 `bin/`。**必须配合 `-PskipWix`**：该启动参数禁用 downloadWix（不访问 GitHub 下载），再由上述逻辑铺好 `build/wix311`。

`build/` 会被 `clean` 清除，因此 `clean` 之后首次打包 msi 需重新复制一次（约 117 MB，耗时数秒）。仅出 zip（`createDistributable`）时不需要 WiX。

## 产物不进仓库

本目录（除本文件外）已在 `.gitignore` 中，**仅作为本机暂存区**。对外分发通过 GitHub Releases 附件进行：仓库历史只保留源码与构建必需的文件，二进制文件一旦提交便无法从历史中移除，只会持续增大仓库体积（当前 5 个 Android 包约 60 MB，单个桌面安装包约 60 MB，一年累计可轻易超过 1 GB）。

出包流程：本地构建 → 放入对应的 `<通道>/<平台>/` → 发布 Release 时上传该文件 → 旧本地包按上述 5 个上限清理。

## 不放什么

- 签名私钥、`keystore`、`*.jks`：永不进仓库
- 构建中间产物（`build/`、`.gradle/`）：已在 `.gitignore` 中
- 调试版：仅放对外可安装的包

### 公开分发的包不携带他人凭据，也不携带任何隐私内容

孵化版与空壳包均为公开分发、由他人自行构建的包，以下为硬性约束，出包前须逐条核对：

- **不内置第三方 Client ID**。OAuth 的 `client_id` 并非密钥，但内置他人注册的 ID
  等于让所有下载者共用同一份配额，并共担该 ID 随时被关闭的风险。孵化版出包时既不得传入
  `-Pplatypus.msClientId`，也不得设置 `PLATYPUS_MS_CLIENT_ID`，`BuildInfo.MS_CLIENT_ID` 与
  `MicrosoftEndpoints.DEFAULT_CLIENT_ID` 均须为空串，正版登录由用户自行注册
  （步骤见界面「设置 → 账号」第一张卡片，取值顺序见 [`ClientIds.kt`](../core/src/main/kotlin/io/github/huang404yan/platypus/core/auth/ClientIds.kt)）。
  **正式版另作处理**：由构建方在本机注册并注入，该 ID 归发行方所有，不随源码分发。
  正式版未注入 ID 时程序不会无法启动（首启弹窗可关闭，正文已写明缺少 ID），但微软登录不可用，
  因此发布正式版前必须确认 ID 确已注入。三条取值路径的优先级见 [`ClientIds.kt`](../core/src/main/kotlin/io/github/huang404yan/platypus/core/auth/ClientIds.kt)。
- **孵化版还须确认不含服务地址**：以 `-Pplatypus.shell=true` 出包后，
  `BuildInfo.kt` 中的 `SHELL` 必须为 `true`，且包内不得残留任何内置镜像根。
  正式版相反，`SHELL` 应为 `false`（完整包，自带服务地址）。
- **不携带任何隐私内容**：真实账号、UUID、refresh_token、本机路径与用户名、
  诊断页导出的现场信息、日志文件、`settings.json`、`accounts.json` 以及 `.minecraft` 中的任何内容。
- **不得缺少申请指引**：Release 说明中必须包含一段说明「正版登录需先自行填写应用 ID」，
  并写明填写位置（界面「设置 → 账号」第一张卡片）。仅写在仓库中不算数，用户获取到的是附件。

出包前自检（自代码修改完成到打包之间执行一次）：

```bash
# 排除 src/test：单元测试中的 ID 仅为「格式合法的样本」，并非实际使用的凭据
grep -rn "04b07795\|1950a258" --include=*.kt --exclude-dir=test .   # 应无输出
grep -rn "C:\\Users\\|/home/[a-z]" --include=*.kt --include=*.json .   # 应无输出
grep -n "DEFAULT_CLIENT_ID" core/src/main/kotlin/io/github/huang404yan/platypus/core/auth/MicrosoftAuth.kt
# 第四条应输出：const val DEFAULT_CLIENT_ID = ""
```

孵化版与空壳包还须确认构建产物中该常量为空（上一条仅检查源码常量，注入值经由另一条路径）：

```bash
# 出包后在构建目录中核对，内容应为 const val MS_CLIENT_ID: String = ""
grep -n "MS_CLIENT_ID" core/build/generated/platypus/kotlin/io/github/huang404yan/platypus/core/BuildInfo.kt
# 确认环境变量未残留于 shell（Windows 的 set 与 bash 的 env 均需检查）
env | grep PLATYPUS_MS_CLIENT_ID    # 应无输出
# 空壳包：此行应输出 true
grep -n "SHELL" core/build/generated/platypus/kotlin/io/github/huang404yan/platypus/core/BuildInfo.kt
```

若曾为调试在 IDE 或命令行中设置过 `-Pplatypus.msClientId=` 或 `PLATYPUS_MS_CLIENT_ID`，
出包前必须清除，否则该开发用 ID 会静默进入孵化版包。
