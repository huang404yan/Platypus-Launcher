# 23 · 模组管理与检查更新（P2）

记录日期：2026-11-10。本文只写**已经跑通过的事实**与**还没做到的部分**。

> 编号说明：01–22 已占用，本轮取 23。上一篇 18 只做到「能装、能看」，本篇是「装完之后的事」。

---

## 一、这一轮补的是哪四件事

18 号文档第七节那张取舍表里写着「只装不卸」「不查更新」，用户拿到手的第一反应就是：
**装错了一个模组，只能开资源管理器去删**。开服玩家能接受，普通玩家不会。

| 能力 | 用户动作 | 落到的代码 |
| --- | --- | --- |
| 启用 / 停用 | 勾选框 | `core/.../core/mods/ModFileOps.kt` 的 `setEnabled` |
| 删除 | 行尾「删除」→ 二次确认 → 「删除文件」 | 同文件 `delete` |
| 检查更新 | 「检查更新」按钮 | `core/.../core/mods/ModUpdateChecker.kt` |
| 更新单条 | 「更新到 x.y.z」按钮 | `InstalledModRow` → `ModInstaller.installFile` |

桌面端与安卓端**共用这一整套 core**，界面各写一份；两边行为必须一致，所以下面的规则对两端同时成立。

## 二、启停只改文件名

生态老规矩：**文件名以 `.jar` 结尾才加载**。所以停用 = `x.jar` → `x.jar.disabled`，启用 = 改回来。

认的停用尾巴有三个，**顺序有意义（先长后短）**：

```
.jar.disabled   .jar.bak   .disabled
```

先长后短是因为 `.jar.bak` 若先被 `.disabled` 之类的短尾巴匹配，会剥错位置。`.disabled` 这一条单独特殊处理：它本身就代替了 `.jar`，启用时要补回 `.jar` 才加载得到。

安全约束（都是「无声出事」那一类，所以写死在 core 里）：

- **目标名已存在就抛错，不覆盖**——覆盖会让一个模组无声消失。
- **源文件不在就抛错**，不返回成功。
- **`renameTo` 失败照实抛**（文件被游戏占用时 Windows 会拒绝），界面显示原因，不假装成功。

## 三、检查更新为什么用 sha1

本地 jar **不记录来源**（`mods/` 里就是一个 jar，没有 manifest 说它是从哪个 project 的哪个 version 装的）。所以不能读「我装的是 sodium 0.5.13」去问最新版。

换成反查：算本地文件的 sha1 → POST `/v2/version_files` → Modrinth 告诉你这个文件属于哪个 project / 哪个 version → 再问这个 project 在**当前游戏版本 + 加载器**下的最新版。

不读 jar 里的 `modId` 的原因是 modId 与 slug 不是一回事：Sodium 的 modId 恰好是 `sodium`，但大量模组不是，用 modId 拼 URL 会 404。

```
POST https://api.modrinth.com/v2/version_files
{"hashes":["<sha1a>","<sha1b>"],"algorithm":"sha1"}
```

### 3.1 一个真踩到的 bug：多个哈希被粘成一个

原实现是：

```kotlin
"{\"hashes\":[\"" + batch.joinToString(",") { it.sha1 } + "\"],\"algorithm\":\"sha1\"}"
```

`joinToString(",")` 不带引号，两个哈希会拼成 `"aaa,bbb"`——**一个字符串**，而不是 `"aaa","bbb"`。
单条时恰好正确，所以只装一个模组的 e2e 一直没暴露；装两个之后界面上表现为「每条都查不到更新」，
而且**不报错**。

修法：抽出 `ModUpdateChecker.versionFilesBody(hashes)`，每个哈希各自带引号，外面套 JSON 壳。
它现在有 3 条单测盯着（含一条把请求体再用 kotlinx.serialization 反解回列表），
因为这种错只会静默降级，跑界面试不出来。

### 3.2 四类结论，不糊成一句

| 状态 | 界面文案 | 含义 |
| --- | --- | --- |
| `UP_TO_DATE` | 已是最新 1.8.14-beta.1 | 版本号字符串完全相同 |
| `OUTDATED` | 有新版本 … + 「更新到 …」按钮 | 有别的版本号 |
| `NOT_ON_MODRINTH` | Modrinth 不认识这个文件 | 改过的包 / 别站下的 / 不是模组 |
| `NO_COMPATIBLE_VERSION` | 没有适配当前版本的更新 | 认得出，但没有当前版本+加载器的包 |

版本比较**只做字符串相等**，不解析语义版本。`1.2` 与 `1.2.0` 会被判成「不同」而多提示一次更新——
宁可多提示一次，也不静默漏掉真更新。解析语义版本要处理 `1.8.14-beta.1+1.21.1-neoforge` 这种
MC 生态常见的复合串，写错了就是**悄悄不提示**，代价比多提示高得多。

## 四、加载器必须跟着实例走（第二个真 bug）

现象：选中 NeoForge 21.1.265 的实例，模组页的加载器按钮还写着「加载器：Fabric」。
于是「检查更新」按 fabric 去问，Modrinth 老老实实回了 fabric 版的最新包，界面把 fabric 包当成
neoforge 的新版本——**装下去必然崩**。

修法是记住「加载器是跟着哪个实例对齐的」：

```kotlin
var loaderInstanceKey by remember { mutableStateOf("") }
// rescanMods() 里，拿到 target 之后：
if (loaderInstanceKey != instanceKey(target)) {
    loaderInstanceKey = instanceKey(target)
    loaderOf(target.loader)?.let { loader = it }
}
```

用 `loaderInstanceKey` 而不是每次 rescan 都无条件覆盖，是因为用户可能手动轮换过加载器（想搜别的加载器的包），
刷一下列表不该把选择改回去。原版实例 `loaderOf` 返回 null，不动当前选择。

切换实例时把 `loaderInstanceKey` 清空，下次扫描重新对齐。

## 五、更新单条：旧文件停用而不是删除

```
1. 按新版本的文件名下载进 mods/
2. 新文件名 == 旧文件名 → 什么都不做（同一个包）
3. 否则把旧文件改名成 x.jar.disabled
```

为什么要第 3 步而不是直接删：新版有问题时用户还能改回来。为什么要第 2 步的相等判断：
同一个包重装时目标名已存在，`setEnabled` 会按第二节的安全约束抛错——但那不是错误，是对上号了。

## 六、真机验收

### 6.1 桌面端（`app-desktop/build/compose/binaries/main/app/Platypus Preview`，1700x1000）

夹具：`dist/_test-mc/_nf1211/versions/neoforge-21.1.265/mods/` 放 sodium 与 iris 两个 neoforge 包
（`dist/_modfix.py` 从 Modrinth 下，1 292 934 B / 2 540 723 B）。

| 步骤 | 结果 |
| --- | --- |
| 修复前点「检查更新」 | 「查了 2 个，可更新 2 个。」两条都显示 `…-fabric` 的新版号 |
| 修复后点「检查更新」 | 「查了 2 个，可更新 0 个。」两条都是「已是最新 …-neoforge」 |
| 点第 2 条勾选框 | 标题变「已装 2 个（启用 1 · 停用 1）」，提示行 `已停用 sodium-…jar.disabled`；磁盘 `ls` 确认真的改名 |
| 点 Iris「删除」 | 弹出「删除这个模组文件？…不进回收站」，按钮「取消」/「删除文件」 |
| 点「删除文件」 | 磁盘上 Iris 的 jar 消失，只剩 Sodium 的 `.jar.disabled` |

截图：`dist/_shots/p2-f-check3.png`（修复后「可更新 0 个」）、`p2-h-disabled.png`（停用）、
`p2-n-delconfirm.png`（二次确认）、`p2-o-deleted.png`（删完）。

**删按钮一开始点不出来不是 bug**：我按截图目测的坐标打偏了 30 px，是点击落在卡片空白处。
最后靠逐像素找主色系文字框（紫色文字在 (1558,790)-(1598,808)，中心 1578,799）才点准。
这一条记在这里是因为它浪费了两轮排查——**界面点了没反应，先怀疑坐标再怀疑代码**。

### 6.2 安卓端

编译通过（`:androidApp:compileDebugKotlin` EXIT=0），**未真机验证**——本机 `adb devices` 为空，
没有连接设备。安卓端的行为与桌面端共用同一份 core，但界面布局是重写的（手机窄屏），
所以仍然要真机点一次才算数，见第八节。

## 七、单测

| 文件 | 例数 | 覆盖 |
| --- | --- | --- |
| `core/.../core/mods/ModFileOpsTest.kt` | 12 | 启停后缀识别 / 改名的往返 / 目标已存在拒覆盖 / 源不存在报错 / `.disabled` 补回 `.jar` / 删除成功与不存在 |
| `core/.../core/mods/ModUpdateCheckerTest.kt` | 15 | 请求体每哈希各自带引号 / 单哈希不粘连 / JSON 可反解 / 分批 / 版本比较 / 四类状态映射 |
| `app-desktop/.../ModUpdateE2e.kt` | 1 个任务 | 联网：真模组 sha1 → version_files → 断言状态 |

全量（`core/build/test-results/test/*.xml` 统计）：`classes=29 tests=299 failures=0 errors=0 skipped=0`。

## 八、还没做到

- **安卓端真机验证**（本机无设备）
- **依赖自动安装**：装模组时会报「还差 N 个前置模组」，桌面端有「一次装齐」按钮，安卓端只有提示文字
- **批量更新**：一条一条点；Modrinth 的 version_files 一次能问 100 个，批量更新不难，但失败回滚的界面还没设计
- **版本比较的语义化**：现在只做字符串相等，见 3.2 的取舍
- **模组冲突提示**（同类优化互斥，如 sodium 与 optifine）
- **CurseForge**：条款不允许，见 18 号文档第一节
