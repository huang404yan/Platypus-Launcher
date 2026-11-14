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

- **跨网络联机**仍然没有。`--quickPlayMultiplayer` 只是个地址入口，不在同一局域网就没有能填的地址（NAT 穿透/中转是另一套东西）。
- **1.19 及更早**的实例只能用复制地址 + 游戏内手动连，界面已说明。
- **没有真实的「两个世界互连」端到端验证**：启动页点击类是人工验证项，本轮只做了编译、单测与静态核对。
  验证方法：在两台同网段机器上，A 开单人世界并「对局域网开放」，B 在联机页点「直接进」，
  游戏应在主菜单之前直接落到加载地形界面；失败时看 `logs/platypus-launch-<版本>.txt` 里有没有 `--quickPlayMultiplayer`。
