# 33 · 联机页「直接进」：用官方 quickPlay 参数跳过主菜单

2026-11-14。桌面端联机页从「只把地址找出来」推进到「点一下就把游戏拉进那个世界」。

## 一、要解决的问题

联机页原先只能扫局域网广播（`224.0.2.60:4445`）或手填 `host:port`，拿到地址后能做的只有**复制**。
用户还得自己切到启动页、开游戏、等主菜单、点「多人游戏 → 直接连接」、粘贴。四步里有三步是纯手工搬运。

界面里当时写了一段「为什么不在这里点一下就进」，理由是「绕过游戏自己连会丢皮肤和会话」。
这个理由本身没错，但它只否定了**自己实现协议栈**这一条路，没否定**让游戏自己去连**。

## 二、做法：不碰协议栈，只补一个命令行参数

1.20 起，Minecraft 的版本 json 的 `arguments.game` 里有四条 quickPlay 规则：

```json
{"rules": [{"action": "allow", "features": {"is_quick_play_multiplayer": true}}],
 "value": ["--quickPlayMultiplayer", "${quickPlayMultiplayer}"]}
```

另外三条是 `has_quick_plays_support` → `--quickPlayPath`、`is_quick_play_singleplayer` → `--quickPlaySingleplayer`、
`is_quick_play_realms` → `--quickPlayRealms`。这就是**官方的「直接连接」**，游戏内部照旧走正版校验与会话服务，
所以皮肤、会话、模组兼容性全都在。

要点：**只填占位符是不够的**。这条参数被 `rules.features` 挡着，
`is_quick_play_multiplayer` 不开，整段 `--quickPlayMultiplayer <地址>` 根本不会被拼进命令行。
所以改的是两处，必须同时给。

## 三、改了哪几层

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 参数拼装 | `core/src/main/kotlin/io/github/huang404yan/platypus/core/launch/LaunchArguments.kt` | `LaunchContext` 加 `quickPlayMultiplayer: String = ""`；`placeholders()` 改为读它；新增 `QUICK_PLAY_MULTIPLAYER_FEATURE` / `quickPlayFeatures()` / `supportsQuickPlay(version)` |
| 请求对象 | `core/.../launch/Launch.kt` | `LaunchRequest` 加 `quickPlayServer: String = ""` |
| 桌面运行时 | `app-desktop/.../runtime/DesktopGameRuntime.kt` | 地址非空才开特性开关；版本文件不认这条参数时**直接抛错**，不静默忽略 |
| 启动页 | `ui/.../LaunchPage.kt` | 新增「直连服务器」输入框 + 版本能力预检（读实例目录里的版本 json 调 `supportsQuickPlay`） |
| 联机页 | `ui/.../LinkPage.kt` | 每个世界与手填地址旁多一个「直接进」按钮，回调把地址交出去 |
| 导航 | `ui/.../App.kt` | 联机页回调 → 记下地址 → 切到启动页；地址存在 App 层，切页不丢 |

### 为什么地址存在 App 层

切页会把启动页整个销毁（`when (page)` 直接调用各页 Composable，离开组合树）。
地址要是放在启动页的 `remember` 里，用户从联机页点「直接进」、到启动页看一眼、切去别处再回来，
地址就没了，启动变成停在主菜单——和用户的点击意图不符。所以和 `focusKey`、`subSelection` 一样存在 App 层。

### 为什么版本不支持时要抛错

`1.19` 及更早的版本 json 里根本没有这条规则。此时给它拼命令行，参数会被规则过滤掉，
游戏照常停在主菜单。用户点的是「进那个服务器」，结果什么都没发生——比报错更糟：报错至少知道为什么。
所以 `DesktopGameRuntime.buildContext()` 里会先 `supportsQuickPlay(version)`，不支持就抛：

```
这个版本（1.20.1 之类）的版本文件里没有 --quickPlayMultiplayer，不支持直接连接。
1.20 起才有这条参数；换一个 1.20+ 的实例，或者去掉直连地址再启动。
```

启动页也会提前标红（`isError` + 红字提示），不用等点下去才知道。

## 四、单测

`core/src/test/kotlin/io/github/huang404yan/platypus/core/launch/LaunchArgumentsTest.kt` 新增 4 例：

- `填了直连地址并打开特性开关才会有 quickPlayMultiplayer` —— 地址原样落到 `--quickPlayMultiplayer` 后面
- `不打开特性开关时直连参数整段不出现` —— 守住「只填占位符不够」这条
- `1_20 之前的版本文件不认直接连接` —— `supportsQuickPlay` 对 1.20.1 为真、对 1.12.2 与无 arguments 的 json 为假
- `rules 里 feature 为假时直连参数同样不出现` —— feature 显式写 false 也不进

全量：**40 个测试类 / 463 例 / 0 失败**（2026-11-14 13:39 实测，改造前 459 例）。

## 五、还没做到

- **不做 NAT 穿透与公网中转**。`--quickPlayMultiplayer` 只是个地址入口，能不能连取决于网络本身是否通（见下一节）。
- **1.19 及更早**的实例只能用复制地址 + 游戏内手动连，界面已说明。
- **没有真实的「两个世界互连」端到端验证**：启动页点击类是人工验证项，本轮只做了编译、单测与静态核对。
  验证方法：在两台同网段机器上，A 开单人世界并「对局域网开放」，B 在联机页点「直接进」，
  游戏应在主菜单之前直接落到加载地形界面；失败时看 `logs/platypus-launch-<版本>.txt` 里有没有 `--quickPlayMultiplayer`。

## 六、本机地址枚举（2026-11-14 补）

上一节说「不在同一局域网就没有能填的地址」，这句只对了一半：**地址是有的，只是用户不知道自己有**。
跨网络能连的两条路——双方都有公网 IPv6，或在路由器上做端口转发——都不需要启动器帮你打洞，
但都需要先把「我这台机器的地址 + 游戏开的端口」拼对。这一步启动器能做，也就做了。

### 新增模块

`core/src/main/kotlin/io/github/huang404yan/platypus/core/lan/LocalAddresses.kt`：

| 类型 | 作用 |
| --- | --- |
| `LocalBinding(host, kind, interfaceName)` | 一个可用地址；`withPort(port)` 拼成游戏能直接粘的写法，IPv6 自动加方括号 |
| `LocalBinding.Kind` | `LAN` / `IPV6` / `OTHER` 三档，界面按这档写「朋友能用在哪」 |
| `LocalAddresses.list()` | 读 `NetworkInterface.getNetworkInterfaces()`，读不到返回空表**不抛** |
| `LocalAddresses.pick(entries)` | 过滤与排序的纯函数，单测打的是它 |

过滤规则：丢掉没启用的网卡、回环（`127.0.0.1`、`::1`）、链路本地（`169.254.`、`fe80::/10` —— 只在同一段链路有效，填进游戏必失败），
并把 IPv6 的 zone id（`fe80::1%wlan0`）去掉。同一地址出现在多张网卡上只留一条。
排序固定为 LAN → IPV6 → OTHER，同类按网卡名与地址，界面顺序不会每次重开都变。

分类只按地址段判断：`10.` / `172.16-31.` / `192.168.` 是 LAN；`100.64/10`（运营商级 NAT）与其它公网 IPv4 归 OTHER；
带冒号且不是 `::1` 的算 IPv6。**分类不代表能连通**——防火墙、路由器 AP 隔离、ISP 有没有给公网 IP 都在这之外，
所以界面文案写的是「用在哪」，不是「一定能连」。

### 不查公网 IP

没做「联网查我的公网 IP」。两条理由：查就得把请求发给第三方服务，为一个可选功能引入外部依赖不划算；
而且查到的公网 IP 在开了 NAT 的情况下本来也连不进来——少了端口转发这一环，显示出来只会误导。

### 界面

桌面 `ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/LinkPage.kt` 与
安卓 `androidApp/src/main/kotlin/io/github/huang404yan/platypus/launcher/preview/LinkPage.kt`
各加一段「让朋友连到你」：填游戏里「对局域网开放」时聊天栏给出的端口（默认 25565、只收数字、校验 1–65535），
下面按地址种类逐条列出「地址:端口」+ 一句能用在什么场合 + 「复制地址」按钮。
桌面端整页从固定高度改成 `verticalScroll`——加上这一段之后内容超过一屏，不滚会把底部说明裁掉。

「为什么不在这里点一下就进」那张卡片原本写着「跨网络需要 NAT 穿透或中转服务器，这一版没做」，
现在改成说清两条真实可行的路（公网 IPv6 / 端口转发），并明确 **UDP 打洞与公网中转服务器仍然不做**——
那需要一台常驻服务器，跟「把地址填对」是两件事。

### 单测

`core/src/test/kotlin/io/github/huang404yan/platypus/core/lan/LocalAddressesTest.kt`，10 例：
只留下启用的非回环地址 / 私网 IPv4 认作局域网 / 公网 IPv4 归到其它类但不丢 / 链路本地地址一律丢掉 /
IPv6 去掉 zone id 后保留 / 同一个地址只留一条 / 排序先局域网再 IPv6 最后其它 / 不是 IP 的字符串丢掉 /
拼端口时 IPv6 加方括号 / 本机至少要能读出一张网卡或不报错。最后一例只验「不抛、结构合法」——
真实网卡内容随机器变，拿它当断言只能测出「这台机器现在有几个网卡」。

全量：**41 个测试类 / 473 例 / 0 失败**（2026-11-14 13:54 实测，加本节前 463 例）。

### 还没做到

- **没有真实的跨网络连通验证**。本机只有一张网卡，两个网络之间能不能连通，得用户自己试。
- **不检测端口有没有真的开着**。填了端口就能复制，不判断游戏是否真在监听——检测要主动连一次，而时机很难拿准（游戏刚开、房主还在加载地形）。
- **IPv6 只做了写法，没做可达性判断**。`[2001:db8::5]:25565` 能不能连，取决于对端有没有 IPv6、路由有没有放行。

