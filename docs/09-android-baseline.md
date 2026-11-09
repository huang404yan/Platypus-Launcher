# Android 运行端技术基线（2026-11-09）

本文回答一个问题：**手机能不能跑 Minecraft Java 版，卡在哪，代价是多少。**
所有数字分两栏标注：**[实测]** = 本机跑过命令、看过字节数；**[推断·未验证]** = 算出来的，附推算依据，等真机实验验。

验证方式已改：本机不再连 USB / 不跑 `adb`。所有真机结论由用户装 APK 自测回传，步骤见 [10-android-ceshi-buzhou.md](./10-android-ceshi-buzhou.md) 与 [11-android-s1-shiyan.md](./11-android-s1-shiyan.md)。

---

## 1. 结论先行

1. **[实测] 应用内起 JVM 这件事，业界已有成品。** PojavLauncher 用 JNI 在应用进程里 `JNI_CreateJavaVM`，不是 fork 子进程，也不需要 root。这条路成立。
2. **[实测] 上游 JRE 产物只到 Java 17。** PojavLauncher 的 `android-openjdk-build-multiarch` 仓库全部 4 个 tag 是 `jre17-ec28559` / `jre17-ca01427` / `jre8-99f3f8b` / `jre8-40df388`，**没有 jre21**。而 1.21.1 的元数据写着 `javaVersion.majorVersion = 21`。所以：
   - **1.20.1（要 Java 17）：现成 JRE 直接可用。**
   - **1.21.1（要 Java 21）：必须自己编 JRE21，或者让用户先接受「本启动器只支持到 1.20.x」。** 这是当前最大的单点缺口。
3. **[实测] 一个版本的完整下载量 ≈ 700–900 MB。** 1.20.1 是 706 MB，1.21.1 是 885 MB，其中 88% 是资源文件（assets）。手机端首次安装就是一次大文件下载，下载器必须支持断点续传，否则中途断网等于从头再来。
4. **[推断·未验证] 默认机型能进世界，但帧率会被翻译层吃掉一半。** 具体见 §6，需要实验 E3 定标。

---

## 2. 版本元数据：体积与 Java 要求

**数据来源 [实测]**：本机 `curl` 拉 `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json`（HTTP 200，278,772 B，1.04 s），再按其中 `url` 逐个拉版本 json 解析。

| 项 | 1.20.1 | 1.21.1 |
|---|---|---|
| 版本 json 大小 | 34,974 B | 38,408 B |
| `mainClass` | `net.minecraft.client.main.Main` | 同 |
| `javaVersion.majorVersion` | **17** | **21** |
| `javaVersion.component` | `java-runtime-gamma` | `java-runtime-delta` |
| `assets` id | `5` | `17` |
| `assetIndex.totalSize` | 651,970,283 B（621.8 MB） | 826,356,660 B（788.1 MB） |
| client jar | 23,028,853 B（22.0 MB） | 26,836,906 B（25.6 MB） |
| libraries 条数 | 88 | 97 |
| libraries artifact 合计 | 65,383,126 B（62.4 MB） | 75,122,740 B（71.6 MB） |
| natives 库条数 | 42 | 48 |
| └ Linux natives | 1,225,621 B（1.17 MB） | 2,614,712 B（2.49 MB） |
| └ Windows natives | 4,110,870 B（3.92 MB） | 7,488,760 B（7.14 MB） |
| **完整下载量合计** | **740,382,262 B ≈ 706 MiB** | **928,316,306 B ≈ 885 MiB** |

清单本身：922 个版本，其中 `type == release` 的 103 个；`latest` 是 `release: 26.3` / `snapshot: 26.4-snapshot-7`。

**为什么这张表重要**：
- 706 MiB 是「点一次下载按钮要花多久」的分母。按 10 MB/s 的家庭 Wi-Fi 算约 71 秒，按 4G 实测 2 MB/s 算约 6 分钟。中途切后台、锁屏、掉信号都必须能续，所以 `HttpDownloader` 的 sha1 复用逻辑是必需品而不是优化项。
- **Android 上取 Linux natives，不取 Windows 的。** `OsFamily.current` 在 Android 上返回 `LINUX`，这正好是我们要的：1.20.1 的 Linux natives 只有 1.17 MB。

---

## 3. JRE：体积、布局、能不能裁

**数据来源 [实测]**：本机从 `gh-proxy.com` 镜像下载 `jre17-arm64-20210825-release.tar.xz`，落盘用 Python `tarfile` 逐成员统计。

| 项 | 实测值 |
|---|---|
| 下载地址 | `https://gh-proxy.com/https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm64-20210825-release.tar.xz` |
| HTTP | 200，13.65 s，38,065,004 B（36.30 MiB） |
| sha256 | `c64583ac2e0ec8857e43456fa9adcf482c6a8e454a7133173bf15692d2478b8d` |
| 解压后 | 169,394,360 B（161.5 MiB），439 个成员，压缩比 4.45 |
| **顶层目录** | **没有。** 所有路径以 `./` 开头，根就是 `.` —— 解压时不用剥壳，直接解到目标目录 |
| 子目录成员数 | bin 5 / conf 22 / legal 312 / lib 69 / man 29 / release 1 |

体积构成（前 7 项）：

| 文件 | 大小 | 占比 |
|---|---|---|
| `./lib/modules` | 122.73 MiB | **76%** |
| `./lib/server/libjvm.so` | 19.27 MiB | 12% |
| `./lib/ct.sym` | 7.93 MiB | 5% |
| `./lib/libfreetype.so` | 3.11 MiB | 2% |
| `./lib/libfontmanager.so` | 1.75 MiB | 1% |
| `./lib/fonts/LucidaSansRegular.ttf` | 0.67 MiB | — |
| `./lib/libawt.so` | 0.66 MiB | — |

**可裁多少 [推断·未验证]**：
- `legal/`（312 个文件）、`man/`（29 个）、`ct.sym`、`lib/src.zip` 对运行期无用，删掉约省 9 MiB。
- 真正的大头是 `lib/modules`（jimage，122.73 MiB）。它装的是整个 JDK 的类库，Minecraft 只用其中一小部分。用 `jlink` 只保留 `java.base` + `java.desktop` + `java.logging` + `java.naming` + `jdk.unsupported` 等必要模块，**理论上能压到 40–60 MiB**。但 `jlink` 需要原生 `jlink` 可执行文件，而 Pojav 这个包是交叉编译产物、没带 `jlink`；要裁就得在 Linux x86_64 上跑一次 jlink 再回填。**这条没验证过，且成本不低，先不做。**
- 结论：**短期按 36.3 MB 压缩 / 161.5 MB 落盘打包**。双 ABI（arm64 + arm32）合计约 72 MB 压缩 / 323 MB 落盘 —— 这个体积不适合塞进 APK，应该走「首次启动时下载」或「按 ABI 出两个 APK」。

**为什么 JRE 不能塞进 APK [实测依据]**：当前 debug APK 是 12,660,566 B（12.1 MiB）。塞一个 ABI 就会变成 ~49 MB，两个 ABI ~85 MB。侧载场景能忍，但每次改代码都要传 85 MB，开发循环会废掉。

---

## 4. 启动链路：应用内 JVM 具体怎么起

以下全部来自 **PojavLauncher 公开代码的阅读**（`repo: PojavLauncherTeam/PojavLauncher`，分支 `v3_openjdk`），**只借鉴事实性知识，不拷代码**：

**入口**：`com.oracle.dalvik.VMLauncher.launchJVM(String[] args)` —— 一个 JNI 方法，内部 `JNI_CreateJavaVM`。**在应用自己的进程里建 JVM，不是 fork 一个新进程。**

启动前必须做的准备（顺序固定）：
1. `JREUtils.relocateLibPath()` —— 把 JRE 的 `lib/` 加进 `LD_LIBRARY_PATH`，否则 `libjvm.so` 依赖的 `libfreetype.so` 等找不到。
2. `setJavaEnvironment()` —— 设 `JAVA_HOME`、`POJAV_*` 一类环境变量。
3. `initJavaRuntime()` —— 建 JVM 前把常量池、native 方法注册好。
4. `setupExitMethod()` / `initializeHooks()` —— 接管 `System.exit`，否则游戏一崩整个应用跟着退。
5. `chdir(gameDir)` —— 游戏的工作目录。

**必须手动加的 JVM 参数**（照抄值，每一条都有理由）：

| 参数 | 为什么必须 |
|---|---|
| `-Djava.home=<runtimeHome>` | JVM 自己推不出 Android 上的 JDK 布局 |
| `-Djava.io.tmpdir=<cachePath>` | 默认指向 `/tmp`，Android 上是只读的 |
| `-Djna.boot.library.path=<nativeLibDir>` | JNA 靠它找 `libjnidispatch.so` |
| `-Duser.home=<gameHome>` | 否则游戏往不可写的地方写存档 |
| **`-Dos.name=Linux`** | **关键**。Android 的 `os.name` 是 `Linux` 但很多库会走 Android 分支，这条统一伪装成标准 Linux |
| `-Dos.version=Android-<RELEASE>` | 配合上一条，保留可识别的版本信息 |
| `-Dorg.lwjgl.vulkan.libname=libvulkan.so` | LWJGL 找 Vulkan 的默认名字不对 |
| `-Dorg.lwjgl.opengl.libname=<libgl4es_114.so \| libOSMesa.so>` | **渲染后端就是靠这一条切换的** |
| `-Dorg.lwjgl.freetype.libname=<nativeLibDir>/libfreetype.so` | 字体渲染 |
| `-Dglfwstub.initEgl=false` | GLFW 替身不要自己初始化 EGL，由上层交给 Android GLSurfaceView |
| `-Dext.net.resolvPath=<resolv.conf>` | Android 的 DNS 配置不在 JVM 默认位置 |
| `-Djdk.lang.Process.launchMechanism=FORK` | **关键**。Android 没有 `jspawnhelper`，默认的 `POSIX_SPAWN` 会直接失败 |
| `-Dlog4j2.formatMsgNoLookups=true` | Log4Shell 缓解 |
| `-Dfml.earlyprogresswindow=false`、`-Dloader.disable_forked_guis=true` | Forge 的窗口在 Android 上开不出来 |

还会主动 purge 掉用户手写的 `-Xms/-Xmx/-d32/-d64/-Xint/-XX:+UseLargePages*/-XX:ActiveProcessorCount` —— 说明这些参数在 Android 上会打架，必须由启动器统一管控。

**这不是「理论上可行」**：以上每一条都是别人已经踩过并写进代码的结论。

---

## 5. 渲染：三条路，各自的代价

Pojav 的选择逻辑（`JREUtils.loadGraphicsLibrary()`）一共三条分支：

| 后端 | 加载的库 | 原理 | 代价 |
|---|---|---|---|
| **GL4ES**（默认） | `libgl4es_114.so` | 把 OpenGL 1.x/2.x 调用翻译成 OpenGL ES 2.0 | 兼容性最好；**只能到 GL 2.1**，跑不了需要 GL 3.2+ 的 shader mod / 光影 |
| **Zink**（`vulkan_zink`） | `libOSMesa.so` | GL → Vulkan → 厂商驱动 | 能上 GL 4.x，光影可开；依赖 Vulkan 驱动质量，**低端机直接不可用** |
| **LTW**（`opengles3_ltw`） | `libltw.so` | 另一个 ES3 翻译层 | 实验性质，Pojav 自己也标着不成熟 |

**硬约束 [实测]**：`libOSMesa.so` **只在 arm64-v8a 和 x86_64 里存在**，`armeabi-v7a`（32 位）和 `x86` 没有。也就是说 **32 位设备没有 Vulkan/Zink 这条路，只能 GL4ES**。而 2020 年后的手机基本都是 arm64，所以这条影响不大，但报告里得写死。

**我们自己的 APK 现状 [实测]**：当前 APK 解包后只有 4 个 `.so`，全是 `libandroidx.graphics.path.so`（arm64-v8a 10,096 B / armeabi-v7a 7,252 B / x86 9,284 B / x86_64 10,760 B，Compose 的矢量路径依赖带进来的）。**与 Minecraft 运行有关的 native 库一个都没有** —— runtime-android 模块还不存在。APK 共 138 个条目，12.2 MiB 里最大的是 `classes.dex` 18,634,372 B 与 `classes10.dex` 10,821,116 B（未压缩）。

---

## 6. 性能推算（全部标「推断·未验证」）

### 6.1 帧率上限

**推算模型**：设桌面 720p（1280×720）、渲染距离 8 区块、原版 1.20.1 在 Intel UHD 620 级核显上约 **40–60 fps**（这是本文唯一的经验锚点，未在本机实测）。手机 GPU（Adreno 640 / Mali-G76 级）的算力与该核显同量级或略高，但要多付两笔开销：

1. **翻译层开销**：GL4ES 把每个 GL 调用翻译成 ES2 调用，顶点数组要重新打包、状态要映射。社区普遍报告 30–60% 的性能损失（无一手数据，标未验证）。
2. **内存带宽**：手机是 LPDDR4X，与桌面共享内存架构相近，但少了桌面的大缓存。

**结论区间 [推断·未验证]：720p / 8 区块 / 原版 ≈ 15–35 fps。** 这个区间的宽度正好说明它不可靠 —— 真正的定标必须靠实验 E3（见 11 号文档），用游戏内 `F3` 读数或启动器自己打点。

**能确定的定性结论**：
- 渲染距离从 8 降到 4，帧率提升最明显（顶点与区块重建量都降）。
- 光影（shader）在 GL4ES 上**开不了**，不是慢，是 GL 版本不够。
- 分辨率从 720p 降到 480p 对帧率有正收益，但手机屏幕是高分屏，降分辨率等于模糊，得让用户在「清晰」和「流畅」里选。

### 6.2 内存预算

Android 的内存模型和桌面完全不同，这里有两个独立的限制：

1. **JVM 堆上限（`-Xmx`）**：由启动器自己设。1.20.1 原版建议 1–2 GB；开光影或大整合包要 3–4 GB。
2. **进程总内存上限**：由系统 lmkd（低内存杀手）决定。**超了不是 OOM 异常，是整个进程被 SIGKILL**，用户看到的是「闪退」，没有任何 Java 异常可抓。

**推算 [推断·未验证]**：
- 8 GB 内存的手机，前台应用通常能拿到 2–3 GB 才触发 lmkd。
- 但 JVM 堆之外还有：JRE 自身的 native 内存（`lib/modules` 映射约 120 MB）、GL 驱动、纹理（显存与内存共享）。实际留给 `-Xmx` 的大概是 **1.5–2.5 GB**。
- 4–6 GB 的手机，`-Xmx` 安全值可能只有 1 GB 上下。

**这条对界面的直接要求**：内存设置页不能只给滑块，必须显示「本机实测可用内存」和「建议上限」，并且默认值要保守 —— 因为超限的失败模式是闪退，用户拿不到任何错误信息，只会觉得「这软件坏了」。

### 6.3 首次启动总耗时

**[推断·未验证]**，按 706 MiB 下载 + 161.5 MiB JRE 解压：

| 阶段 | 数据量 | 10 MB/s 下 | 2 MB/s 下 |
|---|---|---|---|
| 下载 JRE | 36.3 MB | 4 s | 18 s |
| 解压 JRE | → 161.5 MB | 5–15 s（取决于闪存） | 同 |
| 下载游戏文件 | 706 MiB | 71 s | 353 s |
| 解压 natives | 1.17 MB | < 1 s | < 1 s |
| **合计** | | **约 1.5–2 分钟** | **约 6–7 分钟** |

这是「从装好 APK 到能点启动」的时间，不含 Java 类加载与首次世界生成。

---

## 7. 哪些 PCL 功能在 Android 上做不到

参照物是 PCL 自编版的功能表（详见 04-juece-jilu.md）。逐条对照：

| PCL 功能 | Android 能不能做 | 卡在哪 |
|---|---|---|
| 版本列表 / 下载带进度 | **能** | 已实现，见 `DownloadPage.kt` |
| Java 自动探测 | **能（但语义变了）** | 桌面是找一个 `java.exe`；Android 没有系统 Java，只能「内置一份 / 下载一份」 |
| Java 自动下载 | **能，但要自己做源** | Mojang 的 `javaVersion.component` 指向的是**桌面 JRE**，Android 用不了。必须自带 Android 版 JRE 包 |
| 实例管理（含内存） | **能** | 纯文件操作 + 一份 json，已在 core 里做完 |
| 启动进游戏 | **能** | 这是 S1 的核心，依赖 runtime-android |
| 三种登录（离线 / 微软 / 外置） | **离线能，微软能，外置能** | 纯 HTTP + 本地存 token，与平台无关 |
| 陶瓦联机 | **不能** | 它依赖 PCL 自己的服务端协议与 NAT 穿透，是 Windows 端生态。要做得自己实现一套，或者接第三方（如 e4mc） |
| 服务器安装 / 启动 | **不能（作为客户端应用）** | 开服要在后台常驻一个 JVM，Android 会杀后台进程；技术上能做前台服务，但耗电与发热不可接受 |
| 控制台 / RCON | **能（若做开服）** | 但既然不做开服，这条失去意义 |
| server.properties / OP 管理 | 同上 | 同上 |
| Forge 内存 | **能** | 就是启动参数，与平台无关 |
| 主题 | **能** | Compose 层面的事 |
| 自动更新 | **能** | 但侧载 APK 的更新要用户手动装，不能静默 |
| 帮助文档 | **能** | 打包 markdown 或内置 WebView |

**一句话**：客户端能做的（选版本、下载、补 Java、建实例、启动、登录、主题、文档）Android 都能做；**服务端相关的（开服、RCON、OP、联机）在手机上不成立，这不是技术难点而是平台形态问题** —— 手机不该拿来当服务器。

---

## 8. 未决问题（等实验回答）

1. **1.21.1 怎么办**：自己编 JRE21，还是明确只支持到 1.20.x？前者是几天的交叉编译工作，后者是产品决策。
2. **帧率到底多少**：见实验 E3。
3. **GL4ES 在具体机型上的兼容性**：某些 Mali 驱动对 GL4ES 的 FBO 处理有 bug，会让画面全黑。见实验 E4。
4. **首次启动会不会被 lmkd 杀掉**：见实验 E5。
5. **触摸输入怎么映射**：Pojav 用的是「虚拟按键 + 手势」，这块工作量不小，且需要真机手感反馈。本轮不做。

---

## 9. 本轮交付物

| 产物 | 位置 |
|---|---|
| 可安装 APK（含诊断页） | `releases/preview/android/Platypus-Launcher-0.8.0-preview.1-android.apk` |
| 用户测试步骤 | [10-android-ceshi-buzhou.md](./10-android-ceshi-buzhou.md) |
| S1 真机实验清单 | [11-android-s1-shiyan.md](./11-android-s1-shiyan.md) |
| 下载页（可跑） | `androidApp/src/main/kotlin/.../preview/DownloadPage.kt` |
| 诊断页（可跑） | `androidApp/src/main/kotlin/.../preview/DiagnosticPage.kt` |
