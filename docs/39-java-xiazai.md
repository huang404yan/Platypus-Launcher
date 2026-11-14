# Java 下载：两端各做各的

2026-11-14。给 Windows 端与安卓端加「下载 Java」。两端的形态**故意不一样**：
电脑上 Java 是「本机早就有一堆，缺的那一份可以自动补」，手机上没有 Java 这个概念，
只能由启动器自己下几份常用的。参考对象也不同 —— 桌面看 PCL 与美西螈（Axolotl），
安卓看 FCL（FoldCraftLauncher）。

## 一、为什么两端不能共用一套

桌面端早就有一套 `JavaRuntimeService`：扫注册表与常见安装路径、跑 `java -version` 探主版本、
按 `GameJavaRules` 给某个游戏版本挑精确主版本（**刻意不做高版本顶替**：1.16.5 的 Forge 在
Java 16+ 上直接崩）。缺哪一档时自动去 Mojang 的运行时清单拉一份。

安卓端没有这套东西，也不该有：

- 手机上不存在「已安装的 JDK」这种系统级设施，扫描无从谈起；
- 安卓从 10 起禁止执行共享存储里的代码，JRE 必须解到应用私有目录（`context.filesDir`）；
- 安卓要的是**针对 bionic libc 预编译**的 OpenJDK，Azul / Adoptium 那些 glibc 的包一个都跑不了。

所以两端的「下载 Java」是两件事：桌面是「补一份缺的」，安卓是「存几份常用的」。

## 二、桌面端

### 2.1 一个开关：`ensure` 与 `download`

`JavaRuntimeService` 原来只有 `ensure(major)`，语义是「能用就行」——本机已经有 Java 21，
就一个字节都不下。这对「启动游戏前自动补」是对的，对用户主动点「下载」是错的：
用户点下载，要的是**一份启动器自己管、随时删得掉的运行时**。

所以在接口上加了两个带默认实现的成员（`core/runtime/JavaRuntime.kt`）：

```kotlin
suspend fun catalog(): List<JavaRuntimeOffer> = emptyList()
suspend fun download(major: Int, component: String?, onProgress: (Float) -> Unit = {}): JavaRuntime =
    ensureRuntime(major, component, onProgress)
```

不支持这两个能力的后端（安卓那份没有实现）返回空表、退回 `ensureRuntime` 即可，
不必改所有实现类。

### 2.2 清单：从 Mojang 的运行时索引读

`JavaRuntimeIndex` 早就有了，但原来只按 `component` 去找**一个**。现在新增
`buildRuntimeOffers(index, platform, installedOf)`（`core/runtime/JavaRuntimeManifest.kt`），
把某个平台上能下的全部列成表：

- 只认 `JavaRuntimeComponents.majorOfComponent()` 认得出来的 component，**`minecraft-java-exe` 直接丢掉**
  ——它的 `version.name` 是 `14` / `12`，不是 Java 版本；
- **丢掉 `java-runtime-gamma-snapshot`**：它和 gamma 是同一个 Java 17，两个都列会有两行一样的东西；
- 同一主版本出现多个 component 时按 `rank()` 留最优（gamma 0 < beta 1 < alpha 2 < delta 3 < epsilon 4 < jre-legacy 5）；
- 按主版本升序排，已装的那一行带上路径。

实测（`https://piston-meta.mojang.com/.../all.json`，2026-11-14）：

| 平台 | 能下到的 |
| --- | --- |
| windows-x64 | 8（jre-legacy）· 16（alpha）· 17（gamma）· 21（delta）· 25（epsilon） |
| windows-arm64 | 17 · 21 · 25（**没有 8 与 16**） |
| windows-x86 | 8 · 16 · 17（**没有 21 与 25**） |

`version.name` 有三种写法要能解析成主版本：`21.0.7`（点分）、`16.0.1.9.1`（四段）、
`8u51-cacert462b08`（主版本在 `u` 前面）。`majorOfVersionName` 按
`substringBefore('.').substringBefore('u').substringBefore('+')` 取。

### 2.3 界面：设置 → Java

`ui/JavaTab.kt`（281 行），挂在设置页第二个二级项。两张卡：

1. **Java 运行时** —— 官方清单每一档一行（`Java 17 · 17.0.15 · java-runtime-gamma`），
   已装显示路径 + 「已装」，未装显示「下载」。下载中行内变「下载中…」并显示进度条。
   表下面写明会下到 `<游戏目录>/runtime/`。
2. **本机已经找到的 Java** —— `detect()` 的结果，「重新探测」按钮。

排版参考美西螈（Axolotl Launcher）：把 Java 单独列成一张带已装标记的表，而不是混在
「启动参数」里。这部分只用了它的**形态**，它是 Rust + Vue 的桌面应用，一行代码都搬不过来。

三个取舍写在代码注释里：

- **下载不探测本机**：点「下载」就是要在 `<游戏目录>/runtime/` 留一份，哪怕本机已经有同版本；
- **不做高版本顶替**：见上文 Forge 的坑；
- **没有游戏目录就不让下**：`DesktopJavaRuntimeService.download` 直接抛
  「没有配置游戏目录，无法下载托管运行时；请去设置页填一个游戏目录。」

## 三、安卓端

### 3.1 JRE 从哪来

自己编一份能在 bionic 上跑的 OpenJDK 不现实（交叉编译 + 每个 ABI 一份），
所以用 **FCL 仓库里已经按架构预编译好的那份**：`FCL/src/main/jreAssets/app_runtime/java/`
下的 `jre8` / `jre17` / `jre21` / `jre25`，每个版本五个文件。

关键在它的结构 —— **不是一个整包，是两包叠加**：

| 文件 | 内容 | 体积（jre17 实测） |
| --- | --- | --- |
| `universal.tar.xz` | 与架构无关的部分：`lib/modules`（81.7 MB）、`conf/`、`legal/`、`lib/fonts/` | 17,070,632 B / 261 条目 |
| `bin-<abi>.tar.xz` | 该 ABI 的二进制：`bin/java`、`lib/*.so`、**`release`** | 4,640,448 B / 42 条目 |

**只解 universal 会得到一个起不来的 JRE**：`bin/java` 与 `release` 都在 bin 包里。
FCL 自己那句「读 `release` 里的 `JAVA_VERSION` 判版本」也必须等两包都解完才成立。

四个版本的实际体积（`universal` + `bin-arm64`）：

| 版本 | universal | bin-arm64 | 合计 |
| --- | --- | --- | --- |
| jre8 | 9,540,292 | 4,347,796 | 13,888,088 |
| jre17 | 17,070,632 | 4,640,448 | 21,711,080 |
| jre21 | 18,974,588 | 6,847,128 | 25,821,716 |
| jre25 | 32,814,644 | 5,453,168 | 38,267,812 |

`jre25` **没有 `bin-x86`**，所以 32 位 x86 设备上只能看到 8 / 17 / 21 三档。

### 3.2 地址怎么拼：镜像前缀

raw.githubusercontent.com 在国内近乎不可用，实测（拉 4.6 MB 的 `bin-arm64.tar.xz`）：

| 源 | 结果 |
| --- | --- |
| `gh-proxy.com` | **883 KB/s，完整下完** |
| `ghfast.top` | 442 KB/s，完整下完 |
| 裸 raw.githubusercontent.com | 42 秒只收到 64 KB |
| cdn.jsdelivr.net | 6 KB/s |
| fastly.jsdelivr.net | 403 |

所以主地址是 `https://gh-proxy.com/` + 裸 GitHub 地址，备用是 `ghfast.top` + 裸地址，
最后兜底裸地址本身。`AndroidJavaCatalog.mirrorsOf()` 负责这条链。

另外自己做的分块并发实测：把 4,640,448 B 切 16 段并发拉，**4.4 秒拉完（1022 KB/s）**，
单连接只有 165–207 KB/s —— `HttpDownloader` 现成的分块并发 + `.part` 续传直接够用，
不需要接 FluxDown 这类外部下载器（它的 GitHub 加速本质也是换 CDN + 多线程 Range）。

### 3.3 解包：xz 用库，tar 自己解

`org.tukaani:xz`（1.10，168 KB，纯 Java）负责 LZMA2；tar 那一层自己写
（`core/runtime/AndroidJava.kt` 的 `object TarXz`，约 130 行）——引 commons-compress
只为解 tar 不值得，而 tar 的格式总共就那几个偏移。

四件事必须做对：

1. **路径穿越要拒绝**，不是静默跳过：
   ```kotlin
   val target = File(root, name).canonicalFile
   if (target != root && !target.path.startsWith(root.path + File.separator)) {
       throw IOException("压缩包里的条目想写到目标目录之外：" + name)
   }
   ```
   静默跳过会解出一个缺文件的 JRE，报错会报在别的地方。
2. **可执行位要设**：按 tar 头里的 mode 取 owner/group/other 执行位，
   `target.setExecutable(true, false)`。`bin/java` 没有 x 权限在安卓上就起不来。
3. **两包要叠进同一个目录**：先都解到 `.tmp-jre<N>-<abi>`，验完 `bin/java` 与 `release` 都在，
   再 `renameTo` 成正式目录。中途失败留下的是 `.tmp-`，不会把半套当成装好。
4. **解完删压缩包**：解出来的比压缩包大好几倍（`lib/modules` 一项就 81 MB），留着只是白占空间。

### 3.4 界面：Java 页

`androidApp/.../JavaPage.kt`。侧栏加了 `JAVA("Java")` 一项，紧跟「设置」。

照 FCL 的思路：**不问「你本机装了哪些 Java」，而是列出启动器能下的几档，每个一行**。
每行显示 `Java 17`、`约 21 MB · arm64-v8a`，已装的显示路径与「删除」（带二次确认），
未装的显示「下载」。进度、取消按钮在列表上方，滚到哪儿都看得见。

设备架构从 `Build.SUPPORTED_ABIS` 挑第一个认得的（`AndroidJavaCatalog.supportedAbi`）；
一个都不认得时直接给一张红卡说明，不留一个空列表。

页面上有两句话不能省：

- 「下好就存在应用私有目录里，删掉应用才会没」—— 与桌面的 `<游戏目录>/runtime/` 不是一回事；
- 「这一版的安卓端还不能进游戏，下好的 Java 先存着」—— **不能承诺「下完就能玩」**，
  `runtime-android`（应用内 JVM + 图形转译）还没开始，目前没有任何消费方。

## 四、致谢与许可

安卓那份 JRE 本体是 OpenJDK（GPL-2.0 with Classpath Exception），打包分发是 FCL 做的。
本项目**不把 JRE 打进 APK**，只在用户点下载时去取 —— 所以 APK 体积没变（13.2 MB），
但来源必须标注：

- `core/Credits.kt` 新增两条：FCL 作为**设计参考**（GPL-3.0，代码未取）、
  安卓 JRE 资产作为**运行时依赖**（OpenJDK GPL-2.0+CE）；
- 根目录 `NOTICE` 新增「== 运行时资产（不进安装包，运行时下载） ==」一节。

## 五、实测

| 项 | 结果 |
| --- | --- |
| `./gradlew :core:test :ui:jvmTest :ui:compileKotlinJvm :app-desktop:compileKotlin :androidApp:assembleDebug` | EXIT=0，BUILD SUCCESSFUL in 55s / 57 tasks，0 条 `e:` |
| 单测 | **51 类 579 例全过**（改造前 50 类 563 例，新增 `AndroidJavaTest` 16 例） |
| Mojang 运行时索引 | windows-x64 五档齐全；arm64 缺 8/16；x86 缺 21/25 |
| FCL 资产实际体积 | 见 3.1 表，jre17 = 21,711,080 B |
| gh-proxy 速率 | 883 KB/s（单连接）/ 1022 KB/s（16 段并发） |

## 六、还没做到

1. **安卓下完的 JRE 没有消费方**。`runtime-android`（应用内 JVM + GL 转译）还没开始，
   页面只能「先存着」。
2. **桌面端下载的运行时没有「删掉」按钮**。文件在 `<游戏目录>/runtime/<component>/`，
   要删得自己去文件管理器；安卓那边有。
3. **FCL 的 `jreAssets` 用了对方的 CDN 前缀**。mirror 优先 `gh-proxy.com`，它哪天挂了，
   只有 `ghfast.top` 与裸 GitHub 兜底，都需要更新常量。
4. **没有做校验**。上游只给了文件体积，没给 sha 值，所以只能比大小。
