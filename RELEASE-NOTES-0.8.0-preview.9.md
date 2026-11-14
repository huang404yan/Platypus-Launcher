# 鸭嘴兽·孵化版 0.8.0-preview.9

> 孵化版（Preview）：能用，但功能和接口都还在变。遇到问题请开 issue，附上诊断页导出的信息。

## 这一版比 preview.8 多了什么

### 能登录正版了

设置页新增「账号」二级项（顺序是 启动 / 账号 / Java / 界面 / 其他）。点「用微软账号登录」：

1. 界面给出一个八位短码，大号等宽字；
2. 拿手机或浏览器打开 microsoft.com/link，输这串码；
3. 这边轮询到结果自动继续，**输完不用点任何东西**。

选设备码而不是本地回调端口，是因为后者要过防火墙弹窗、可能撞端口、还可能被判成不安全的 redirect_uri。代价只是多抄一次码。

登录成功后本地只留一枚 refresh_token，存在 `<游戏目录>/platypus/accounts.json`。**那个文件等价于账号密码，别发给别人**（界面 hint 与 docs/40 都写了）。

启动游戏时**每次现换一份会话**：MC 令牌两小时就过期，缓存下来十个里有九个是废的。换不到就停下来把原因说清楚，**不静默退回离线** —— 静默退回会让人以为自己登录着，等进了服务器才发现是个离线号，那时候说不清是谁的问题。

当前是正版账号时，启动页侧栏不再显示「离线用户名」输入框，换成一行「正版账号：X（要换去「设置 → 账号」）」。两个输入同时在场会让人以为填了用户名就用用户名。

### 多账号可以并存

登录多个微软账号后各占一行，随时「用这个 / 移除」。移除只删本地令牌，账号本身没事。选中哪个立刻落盘，不用再滚回去点「保存」。

### 离线模式仍然在，且更明确了

账号页第二张卡写着当前是不是离线模式；正版账号在场时给一个「改用离线模式」按钮。不登录也能玩，离线用户名照旧在「启动」页填。

## 这一版修了什么

- **LauncherSettings 新增 activeAccountUuid 字段**：老设置文件没有这个字段时退到空串（离线模式），不会因为缺字段把整个设置读挂。
- **core/auth/Account.kt 里那套接口仍然没有实现**：这一版的登录没用 AuthService，而是走单独的 AccountAuth。前者留着给以后的 Yggdrasil 用。

## 已知限制（别当 bug 报）

- **真实账号的完整登录没有自动化验证过**。要走完得有人拿着手机去微软页面输码，脚本跑不了。协议链路与每一步的请求体/响应解析都有单测（新增 36 例），但「一次真的登录成功」没有截图或日志。
- **内置的 Client ID 不归本项目所有**：它是公开文档里长期被各类命令行工具引用的公共客户端。微软随时可能收紧；真到不能用那天，改 MicrosoftEndpoints.DEFAULT_CLIENT_ID 一行、发一个新版本。没有做「让用户在设置里填自己的 Client ID」——它必须一路传到刷新会话那一步，多出来的状态比收益大。
- **安卓端没有登录页**：那边还不能启动游戏，登录了也没有消费方。
- **Yggdrasil（外置登录）仍然是空壳**：core/auth/Account.kt 里只有接口。
- **令牌彻底失效不会提前提醒**：refresh_token 废掉时，用户是在点「启动」那一刻才知道。
- **同一时刻只有一个「当前账号」**：没有多账号并行会话。
- 服务端不能从界面启动也看不到控制台输出（只能打开目录、自己双击脚本，见 preview.8 说明）。
- 安卓端下好的 Java 暂时没有消费方（runtime-android 还没开始，下完只能先存着）。
- 安卓端仍未在真机跑过，验证止于「编译通过 + APK 的 dex 里有这些类」。

## 校验

- `gradlew.bat :core:test :ui:jvmTest :ui:compileKotlinJvm :app-desktop:compileKotlin :androidApp:assembleDebug` —— BUILD SUCCESSFUL，0 条 e:。
- 单测 **616 例全过 / 53 个测试类**（其中新增 MicrosoftAuthTest 24 例、AccountStoreTest 12 例）。
- 微软四个域名本机全部可达：login.microsoftonline.com 400 / 0.46 s、login.live.com 400 / 0.69 s、user.auth.xboxlive.com 405 / 0.53 s、api.minecraftservices.com 401 / 0.58 s。
- Client ID 可用性实测（往 devicecode 端点 POST）：Minecraft 官方启动器的 00000000402b5328 与另外三个常见 ID 全部 400 AADSTS700016；Azure CLI 的 04b07795-8ddb-461a-bbee-02f9e1bf7b46 返回 200 带真实短码，用的就是它。

## 源码

https://github.com/huang404yan/Platypus-Launcher
