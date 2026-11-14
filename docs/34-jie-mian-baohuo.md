# 34 · 桌面端下载 / 导入跨页保活

2026-11-14。下载与整合包导入从「页面级协程」搬到「进程级 runner」，切页不再打断正在进行的长任务。

## 一、原先为什么会断

`ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/App.kt` 的导航是一个 `when (page)`：

```kotlin
when (page) {
    Page.DOWNLOAD -> DownloadsPage(initialGameRoot, backend) { page = Page.LAUNCH }
    ...
}
```

分支里直接调用各页 Composable，**页面一旦离开组合树，它里面的 `rememberCoroutineScope()` 就被取消**。
`DownloadsPage` 的 `startInstall()` 和 `ModpackSection` 的 `startImport()` 都 `scope.launch { ... }`，
所以「下载到 80% 手滑点了一下实例页」= 任务被砍断，且界面上留下一个永远在转的半截状态。

安卓端早就没这问题：`androidApp/.../DownloadPage.kt` 里有个 `internal object DownloadJobRunner`，
`private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` 是**进程级**的，
注释写得直白：「页面切走、重组都不会打断它，只有杀进程才停」。

## 二、做法：照安卓那套，把状态也一起搬上去

新建 `ui/src/jvmMain/kotlin/io/github/huang404yan/platypus/ui/InstallRunners.kt`（约 520 行），两个 object：

| runner | 管什么 | 对外 |
| --- | --- | --- |
| `OneClickRunner` | 一键安装（原版 + 加载器 + Java） | `state: StateFlow<OneClickState>`、`start(...)`、`cancel()`、`hint(text)` |
| `ModpackRunner` | 整合包预览 / 导入 / 远程准备 | `state: StateFlow<ImportState>`、`inspect(file)`、`startImport(...)`、`downloadRemote(...)`、`cancelImport()`、`cancelRemote()`、`reset()`、`hint(text)` |

两个 runner 都是：

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val _state = MutableStateFlow(OneClickState())
val state: StateFlow<OneClickState> = _state.asStateFlow()
```

页面侧从「自己持有 `phase` / `progress` / `stepStates` / `message` / `job`」变成只读：

```kotlin
val install = OneClickRunner.state.collectAsState().value
val phase = install.phase
val progress = install.progress
```

搬上去的还有这些**原本散在页面里的类型**：`StepState` / `InstallPhase` / `VISIBLE_STEPS` /
`ImportPhase` / `SpeedMeter` / `formatBytes` / `formatSpeed` / `formatDuration`。
它们现在是 `internal`，页面和 runner 共用一份——不搬的话两处定义必然漂移。

## 三、四个容易踩的点

1. **同名定义必须删干净。** `ModpackSection.kt` 原本有私有 `ImportPhase`，`DownloadsPage.kt` 原本有私有
   `StepState`/`SpeedMeter`/`formatBytes`。不删就是 `Redeclaration`——同包内 `internal` 与 `private` 同名也算冲突。
2. **幂等要显式写。** `start()` / `startImport()` 在 `phase is Running` 时直接 `return`，
   否则连点两下按钮会起两个 job 抢同一个目录。安卓那边也是这么写的（「同一时间只跑一个」）。
3. **速度采样要放在 `update` 外面。** `MutableStateFlow.update` 的 lambda 在竞争时会重跑，
   把 `meter.sample()` 写在里面，一次采样就会被算两遍、间隔变负。
4. **取消语义要分三档。** 一键安装取消 → `phase = Idle` + 一句「已下好的文件不会丢」；
   整合包取消 → `ImportPhase.Canceled(step)`（单独一态，停在 `Running` 会一直转灰条）；
   远程准备取消 → 只清 `preparingSlug`。

## 四、界面行为变化

- 在下载页点「一键安装」，切到实例页再切回来：进度条接着走，步骤勾选状态还在。
- 下载中切页，右下角不再有「任务被取消」的提示——因为没被取消。
- 只有点「取消」按钮、或关掉应用，任务才停。
- 整合包导入同理；`ModpackSection` 的「开始导入」仍有取消按钮，走 `ModpackRunner.cancelImport()`。

## 五、实测

| 项 | 结果 |
| --- | --- |
| `:ui:compileKotlinJvm` | `BUILD SUCCESSFUL`（exit 0） |
| `:app-desktop:compileKotlin` | `BUILD SUCCESSFUL`（exit 0） |
| `:core:test` | 40 个测试类 / 463 例 / 0 失败 |

## 六、还没做到

- **关窗时不等任务。** 应用退出会直接杀进程，正在下的文件留下半截。断点续传能接上（`ResumeSmoke` 覆盖），
  但界面没有「正在下载，确定退出？」这种拦截。
- **切页仍占带宽。** 这是保活的必然代价：用户去了实例页，下载还在跑。没有做「后台限速」。
- **没有单测。** `ui` 是 KMP 且只有 `jvmMain`，没有测试源集；runner 的正确性目前靠编译 + 人工点击验证。
  要补得先给 `ui` 建 `jvmTest`，把 `OneClickInstaller` 换成假实现再断言状态流转。
