# 鸭嘴兽·孵化版 0.8.0-preview.10

> 孵化版（Preview）：能用，但功能和接口都还在变。遇到问题请开 issue，附上诊断页导出的信息。
>
> **孵化版 = 测试版 = 开源版。** 它和正式版是同一份源码、同一套功能，差别只有三处：版本号带 `-preview.N`、安装包里不带任何私有凭据、发布时标 Pre-release。

## 这一版比 preview.9 多了什么

### 安装包里不再内置 Client ID，正版登录改成自己申请

preview.9 的包内置了一个从公开文档里抄来的 Azure 应用 ID。它能用，但那是**别人名下的应用注册**：配额算在人家头上、用的人一多就被限流，注册者随时可以把公共客户端流一关让所有依赖它的包当场失效，而且仓库是公开的 —— 内置就等于把别人的 ID 写进版本历史。

这一版把 `MicrosoftEndpoints.DEFAULT_CLIENT_ID` 改成空串，登录前必须先填一个自己注册的 ID：

1. 「设置 → 账号 → 第一步」那张卡里有四步说明，「打开 Azure 门户」按钮直达；
2. 在 Azure 里选「应用注册 → 新注册」，账户类型不能选「仅此组织目录」（设备码走的是 consumers 租户），重定向 URI 留空；
3. 「身份验证」里打开「允许公共客户端流」，否则登录会换回 `unauthorized_client`；
4. 从「概述」复制那串 36 位 ID，粘回界面，点「保存 ID」。

整个流程三分钟，一次填好之后一直用。填的时候边输边校验：不是 36 位带四个横线的形状会被当场拦下，不会发出去换一个看不懂的 `AADSTS700016`。

界面上这一串默认**遮成圆点**，要看明文按住「按住显示」，松手立刻恢复；这个框**不允许复制**
（拦在写剪贴板那一层，Ctrl+C、右键菜单、菜单栏 Copy 一起失效，框下会给一句提示），粘贴照常。
做法照 PCL 自用版：ID 本身不是密钥，
遮的是「共享屏幕或截图时白送一个能定位到谁的应用注册的指纹」。ID 仍然存在设置里，随时可改。

ID 存在 `<游戏目录>/platypus/settings.json` 的 `microsoftClientId` 字段里，**不进安装包、不进仓库**。改完立刻生效不用重启 —— Client ID 是每次登录和刷新时现读设置的。

### 填了一半就点登录会被拦住

输入框里改了 ID 却没点「保存 ID」时，点「用微软账号登录」会提示「这个 ID 还没保存」而不是拿旧 ID 去登录。不拦这一下，用户会以为新 ID 已经生效，然后在微软那边看到用的是旧应用。

## 这一版修了什么

- **`LauncherSettings` 新增 `microsoftClientId` 字段**：老设置文件没有这个字段时退到空串，不会因为缺字段把整个设置读挂。
- **「填回默认值」刻意不清空 Client ID**：那串 ID 是用户从 Azure 门户抄来的，手边没有备份就找不回来。「填回默认值」的语义是「把可重建的设置恢复」，这一项不属于。
- **刷新会话也会带上同一个 ID**：微软把 refresh_token 绑在应用注册上，换 ID 等于换应用，刷新一律 `invalid_grant`。所以 `MicrosoftAccountAuth` 接的是一个 `clientIdProvider: suspend () -> String` 而不是一个构造好的字符串，登录与刷新现读同一个值。
- **「保存 ID」的成功提示挪到了按钮旁边**：原来那句提示走页面底部那条 notice，它在两张卡片之后，点完「保存 ID」视线还停在输入框上，等于没有反馈（这一版出包前实点截图发现）。现在提示紧跟在「撤销改动」右边。

## 已知限制（别当 bug 报）

- **第一次用正版登录要先自己去申请一个 Client ID**，这是这一版刻意的代价。申请步骤见 `docs/41-zishen-client-id.md` 与界面里的说明。
- **真实账号的完整登录没有自动化验证过**。要走完得有人拿着手机去微软页面输码，脚本跑不了。协议链路每一步都有单测（`MicrosoftAuthTest` 29 例），但「一次真的登录成功」没有截图或日志。
- **安卓端没有登录页**：那边还不能启动游戏，登录了也没有消费方。
- **Yggdrasil（外置登录）仍然是空壳**：`core/auth/Account.kt` 里只有接口。
- **令牌彻底失效不会提前提醒**：refresh_token 废掉时，用户是在点「启动」那一刻才知道。
- **同一时刻只有一个「当前账号」**：没有多账号并行会话。
- 服务端不能从界面启动也看不到控制台输出（只能打开目录、自己双击脚本）。
- 安卓端下好的 Java 暂时没有消费方（`runtime-android` 还没开始，下完只能先存着）。
- 安卓端仍未在真机跑过，验证止于「编译通过 + APK 的 dex 里有这些类」。

## 校验

- `gradlew.bat :core:test :ui:jvmTest :ui:compileKotlinJvm :app-desktop:compileKotlin :androidApp:assembleDebug` —— BUILD SUCCESSFUL，0 条 `e:`。
- 单测 **623 例全过 / 53 个测试类**（preview.9 是 616 例）。
- 出包前自检（命令写在 `releases/README.md`「测试版不带别人的凭据」一节，实跑通过）：
  - `grep -rn "04b07795\|1950a258" --include=*.kt --exclude-dir=test .` → 无输出；
  - `grep -rn "C:\\Users\\|/home/[a-z]" --include=*.kt --include=*.json .` → 无输出；
  - `grep -n "DEFAULT_CLIENT_ID" core/src/main/kotlin/io/github/huang404yan/platypus/core/auth/MicrosoftAuth.kt` → `const val DEFAULT_CLIENT_ID = ""`。

## 源码

https://github.com/huang404yan/Platypus-Launcher
