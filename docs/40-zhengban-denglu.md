# 正版登录：设备码、七步链路与只存 refresh_token

2026-11-14。给桌面端加微软正版登录，设置页新增「账号」二级项。

链路本身不长，难的是三件事：**怎么让用户在桌面应用里安全地登录微软账号**、
**中间四个服务各说各话的报错怎么翻译成人话**、**登录态存在哪儿才不会变成账号密码库**。

## 一、为什么是设备码，不是本地回调

桌面端做 MSA 登录有两条路：

1. 起一个本地 HTTP 端口当 redirect_uri，浏览器跳回来时捞 code；
2. 设备码（device code）：服务端给一串八位短码，让用户自己去
   `https://www.microsoft.com/link` 输，应用这边轮询拿结果。

选了第二条，理由都是第一条的实际麻烦：

- 本地端口要过防火墙，Windows 会弹「是否允许」，用户点了拒绝就卡死；
- 端口可能被占，得反复试；
- 某些浏览器不认 `http://127.0.0.1:随机端口` 的 redirect_uri，会拒绝跳转；
- 默认浏览器可能根本不是用户想用的那个。

代价是用户要多抄一次码。UX 上把码做成大号等宽字，旁边写清楚「输完不用点任何东西」，
比让用户在防火墙弹窗里做决定要好。

设备码轮询有两个必须遵守的细节，都在 `core/auth/MicrosoftAuthClient.kt` 里：

- `authorization_pending` 表示「用户还没输完」，要继续等，不能当失败；
- `slow_down` 表示「问得太快了」，要把间隔**再加 5 秒**再问。

把这两个当失败会导致「用户正在输码，界面已经报错了」。

## 二、七步链路

每一步都可能失败，所以每一步的异常文案都写清楚卡在哪：

| # | 端点 | 拿到什么 |
|---|---|---|
| 1 | `login.microsoftonline.com/consumers/oauth2/v2.0/devicecode` | device_code + user_code |
| 2 | 同域 `/oauth2/v2.0/token` | access_token + refresh_token |
| 3 | `user.auth.xboxlive.com/user/authenticate` | Xbox Live 令牌 |
| 4 | `xsts.auth.xboxlive.com/xsts/authorize` | XSTS 令牌 + uhs |
| 5 | `api.minecraftservices.com/authentication/login_with_xbox` | Minecraft 令牌 |
| 6 | `api.minecraftservices.com/entitlements/mcstore` | 有没有买过 |
| 7 | `api.minecraftservices.com/minecraft/profile` | UUID + 玩家名 |

三处容易踩的：

**v1 与 v2 不能混用。** 第 1、2 步必须都在 `login.microsoftonline.com` 的 v2 端点。
PCL 用的 `login.live.com/oauth20_token.srf` 是 v1，v2 签发的 refresh_token 拿去会被拒，
反过来也一样。另外 `login.live.com` 根本没有设备码端点（返回 404）。

**第 3 步的 RpsTicket 要不要补 `d=`。** 规则是「令牌本身已经以 `d=` 开头就不补，否则补上」。
补重了 Xbox 会直接拒。

**第 5 步的 identityToken 格式**是 `XBL3.0 x=<uhs>;<xsts>`，uhs 来自第 4 步的
`DisplayClaims.xui[0].uhs`，xsts 是第 4 步的 Token。少个分号就 400。

## 三、第 6 步不能省

`entitlements/mcstore` 返回的 `items` 为空，说明这个微软账号**没有 Minecraft 的持有记录**
（没买过，或者 Xbox Game Pass 到期了）。这一步必须在拿档案之前查，否则用户会看到
「登录成功」然后启动时进不去，报的还是别的错。

## 四、报错翻译

四个服务的错误格式完全不同，所以 `MicrosoftAuth.kt` 里分了三个翻译函数：

- `describeOAuthError(code, desc)`：`authorization_declined` / `expired_token` /
  `bad_verification_code` / `invalid_grant` / `unauthorized_client` 各有中文；
  认不出来的优先用微软原话（原话往往比笼统的「登录失败」有用）；
- `describeXstsError(xerr)`：四个已知码 —— `2148916227` 账号被封、
  `2148916233` 没注册 Xbox 账号、`2148916235` 所在国家/地区不支持、
  `2148916238` 年龄不足需要家长同意。剩下的把数字带出去，方便用户搜索；
- `describeMcLoginStatus(status, body)`：`ACCOUNT_SUSPENDED` 优先于状态码判断
  （被封号时状态码也可能是 401，光看码会把封号说成登录过期）；
  401/403/429/503 各有文案。

## 五、只存 refresh_token

`<游戏目录>/platypus/accounts.json` 里只有四样东西：UUID、玩家名、refresh_token、时间戳。
**不存 access_token**，理由有两条：

- MC 令牌只活两小时，缓存下来十个里有九个是过期的；
- 多存一份就多一份泄露面。

refresh_token 会被微软**轮换**：有时响应里带新的，有时不带（旧的还能用）。
带的时候必须存回去 —— 不存的话，旧凭据在微软那边被轮换过一次之后就作废了，
用户下次启动会突然被要求重新登录，而且原因看不出来。
这条逻辑在 `MicrosoftAccountAuth.sessionFor()` 里。

这个文件**等价于账号密码**，KDoc、界面 hint 与 `docs` 都写明了这一点。

## 六、每次启动现换会话

`AccountAuth.sessionFor(account)` 每次都走完整链路第 2 步以后的部分（刷新令牌 → Xbox → XSTS → MC 令牌），
一两秒的事。这样做的代价是每次启动多两个网络往返，换到的是「永不过期的登录态」。

启动页有一条**刻意的取舍**：换不到会话时**停下来报错**，不静默退回离线模式。
理由写在 `LaunchPage.kt` 的注释里 —— 静默退回离线，用户会以为自己登录着，
等进了服务器才发现是个离线号，那时候已经说不清楚是谁的问题了。
报错文案直接指路：「去「设置 → 账号」重新登录，或者在那里改用离线模式」。

## 七、内置的 Client ID 是别人的

设备码流程必须先有一个在 Azure 上注册过的公共客户端。申请第三方 MSA 应用要自己注册，
没有官方发的通用 ID。

实测（2026-11-14，往 devicecode 端点逐个 POST）：

- `00000000402b5328`（Minecraft 官方启动器的 v1 ID）、`00000000441cc96b`、
  `000000004C12AE6F`、`0000000040170455` —— 全部 `400 AADSTS700016`（在租户里找不到）；
- `04b07795-8ddb-461a-bbee-02f9e1bf7b46`（Azure CLI）、
  `1950a258-227b-4e31-a9cf-717495945fc2`（Azure PowerShell）—— 返回 200，带真实短码。

用的是前者。**这个 ID 不归本项目所有**，微软随时可能收紧。真到不能用那天，
改 `MicrosoftEndpoints.DEFAULT_CLIENT_ID` 一行、发一个新版本即可；用户自己注册的
Azure 应用 ID 也能直接填进这一行。

没有做「让用户在设置里填自己的 Client ID」：clientId 必须一路传到刷新会话那一步
（刷新与登录要用同一个 ID），多出来的状态比收益大。

## 八、界面

设置页的二级项从四个变成五个：启动 / 账号 / Java / 界面 / 其他。「账号」紧跟「启动」，
因为它决定「以谁的身份进游戏」，比目录和内存更靠前。

账号页两张卡：

- **正版账号**：登录按钮 → 大号短码 + 网址 + 倒计时 → 已登录列表（每行「用这个 / 移除」）；
- **离线模式**：显示当前是不是离线模式，正版在场时给一个「改用离线模式」按钮。

选中哪个账号**立刻落盘**，不等「保存」按钮 —— 用户点「用这个」的意图就是马上生效，
再让他滚回去点一次保存是没道理的。

启动页侧栏配合改了一处：**当前是正版账号时不再显示「离线用户名」输入框**，
换成一行「正版账号：X（要换去「设置 → 账号」）」。两个输入同时在场会让人以为
填了用户名就用用户名。

## 九、实测

- `./gradlew.bat :core:test :ui:jvmTest` —— 53 个测试类、616 例全过。
  其中新增 `MicrosoftAuthTest`（24 例，纯函数：表单拼装、解析、四种 XSTS 错码、
  状态码文案）与 `AccountStoreTest`（12 例：存读、同账号不覆盖、坏文件当空、
  空令牌挡在写盘前）。
- 四个域名本机全部可达（`login.microsoftonline.com` 400/0.46s、
  `login.live.com` 400/0.69s、`user.auth.xboxlive.com` 405/0.53s、
  `api.minecraftservices.com` 401/0.58s），没有被墙。
- **真实账号的完整登录没有跑过**：需要用户本人拿着手机去微软页面输码，
  自动化跑不了。链路正确性来自协议文档与 PCL 的对照实现，
  各步的请求体/响应解析都有单测，但「一次真的登录成功」没有截图或日志。

## 十、还没做到

- 安卓端没有登录页：那边还不能启动游戏，登录了也没有消费方。
- 没有令牌失效的主动提醒：refresh_token 彻底废掉时，用户是在点「启动」那一刻才知道。
- 没有多账号并行会话：同一时刻只有一个账号是「当前账号」。
- 没有 Yggdrasil（外置登录）：`Account.kt` 里那个实现在这一版还是空壳。
