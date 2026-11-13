# 日志中文乱码：写侧补编码参数 + 读侧按字节挑编码

## 一句话

游戏在中文 Windows 上按 GBK 往 stdout 写，启动器一直按 UTF-8 解，中文当场变成 替换符 U+FFFD、字节就地丢失；现在两头都改——给 JVM 传 `-Dstdout.encoding=UTF-8` 与 `-Dfile.encoding=UTF-8` 让它按 UTF-8 输出，读侧再用 `LogLineReader` 先看字节、不像 UTF-8 才整行退回 GBK 解。

## 为什么光改读侧不够

替换符 `U+FFFD` 是解码器遇到非法字节序列时写下的，**写进去的时候原始字节就没了**。所以「已经出现方块的旧日志」无论怎么重解都救不回来，只能保证以后不再出现。真正必须同时做的两件事：

1. **写侧**：让游戏进程按 UTF-8 输出，从源头消除非法字节。
2. **读侧**：给那些不受启动器控制的输出（老版本、第三方库直接写 GBK、用户自己塞进来的行）留一条兜底路径。

只做第 1 条，遇到不认 `-Dstdout.encoding` 的 JDK 就会原样复发；只做第 2 条，等于每条中文都要走一次「先按 UTF-8 失败再按 GBK 重来」的白工，而且两种编码都合法的字节序列会被解错。

## 两个 -D 都要传

| JDK | 认哪个 | 备注 |
| --- | --- | --- |
| 18 及以上 | `-Dstdout.encoding` | 从 JDK 18 起 `file.encoding` 变成只读，改它不再影响控制台输出 |
| 17 及更早 | `-Dfile.encoding` | 只认这个 |

两个都传才跨版本通吃；不认识的 JDK 只会把它当成一条没人读的系统属性，没有副作用。实现在 `core/src/main/kotlin/io/github/huang404yan/platypus/core/launch/LaunchArguments.kt` 的 `chineseLogArgs()`：

```kotlin
fun chineseLogArgs(): List<String> = listOf("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
```

**放置位置有讲究**：在 `-Xmx` 之后、版本 json 里的 jvm 参数与 `LaunchContext.extraJvmArgs` 之前。JVM 对重复的 `-D` 是后者覆盖前者，这么排既保证老版本 json（没有 jvm 参数）也能带上，又留出让用户自己改回去的口子——`extraJvmArgs` 里写 `-Dfile.encoding=GBK` 就能盖掉。

## 读侧怎么挑编码

`core/src/main/kotlin/io/github/huang404yan/platypus/core/launch/LogLineDecoder.kt`，三级顺序：

1. **严格 UTF-8**：`CodingErrorAction.REPORT`，遇到非法序列抛 `CharacterCodingException`。
2. **严格 GBK**：同上，解不开也抛。
3. **宽松 UTF-8**：不抛异常，最差也只是留替换符——总比把整条日志读断好。

关键点：判定用的是**解码器抛不抛异常**，不是解完之后查有没有 `U+FFFD`。替换符一出现，字节已经丢了，那时再判断「这行可能不是 UTF-8」已经晚了一步。必须先拿到「这行不是 UTF-8」这个事实，再整行换编码重解。

```kotlin
private fun strictDecode(bytes: ByteArray, charset: Charset): String? = runCatching {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()
```

GBK 本身用 `runCatching { Charset.forName("GBK") }` 取，取不到就退化到「UTF-8 → 宽松 UTF-8」两级（有些精简 JRE 不带 GBK 字符集）。

## 为什么自己写一个按行读的类

`LogLineReader(input: InputStream)` 不继承 `BufferedReader`，因为**编码判定必须发生在整行上**：`InputStreamReader` 是按块解码的，一行跨两个 8 KB 缓冲时，前半截可能刚好是合法 UTF-8 前缀，后半截才暴露非法——但那半截已经被交出去了，改不回来。

所以流程是「先攒够一整行的字节 → 交给 `LogLineDecoder.decode` → 得到字符串」。代价是要自己处理：

- `\n` 切行，`\r\n` 去掉 `\r`（游戏在 Windows 上两种混着发）。
- 流结束时最后一行没有换行符也要交出来。
- 空流返回 `null`，不返回空行。
- 单行上限 `MAX_LINE_BYTES = 64 * 1024`：超长行截断但不卡死。曾经见过把一整份 jar 的 base64 打进一行的 mod，不设上限就是无限吃内存。

## 实测证据

真机跑 `1.20.1-Forge_47.4.28`（含一套 47 个模组），在启动器里点「启动」，等进主菜单后点日志区「导出」：

| 指标 | 修复前 | 修复后 |
| --- | --- | --- |
| 界面日志里那条 OpenAL 行 | 26 个 `U+FFFD`（全部集中在这一行） | 完整中文：`OpenAL initialized on device OpenAL Soft on 扬声器 (适用于 I2S 音频的英特尔® 智音技术)` |
| 导出文件 `platypus-20261113-214812.log` | — | 35 137 B / 244 行 / **`U+FFFD` 计数 0** |
| 游戏自己写的 `logs/latest.log` | 按 GBK 写 | 按 UTF-8 写，整文件能被严格 UTF-8 解码器直接解出（`TOTAL FFFD in file: 0`） |
| 进程命令行 | 无编码参数 | 实测含 `-Xmx2048M`、`-Dstdout.encoding=UTF-8`、`-Dfile.encoding=UTF-8` |

游戏进程由 `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` 读 `CommandLine` 确认，不是靠读代码推断。

单测：`LogLineDecoderTest` 11 例、`LaunchArgumentsTest` 补 3 例；全量 `:core:test` = **34 个测试类 / 364 例 / 0 失败 0 错误 0 跳过**。

## 还没做到

- **只在 1.20.1 + Forge 上验过**。其他版本（尤其 1.7.10 这类老版本、以及走 Java 8 的实例）没逐一验——编码参数对所有版本都会传，但「传了之后游戏是否真的按 UTF-8 输出」取决于 JVM 版本，没有全版本矩阵。
- **启动器自己往控制台打的日志仍是乱码**：那是启动器进程的 stdout，跟游戏进程无关，本轮没碰。在 MSYS 终端里看到 `[scan] warning: ????` 属于这一类，不影响界面。
- **GBK 之外的中文编码没覆盖**：BIG5、GB18030 现在一律走宽松 UTF-8 兜底。中文 Windows 上 GB18030 是超集，GBK 能解的子集够用，但真要支持日文/繁体环境还得扩。
- **没有处理 `--encoding` 之类的第三方参数**：某些 mod 会自己重设输出流，绕过 JVM 的默认编码。
