package io.github.huang404yan.platypus.core.runtime

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.HttpDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 安卓上可以下载的 Java 运行时。
 *
 * ## 为什么不在 Mojang 那儿下
 *
 * 桌面端用的是 Mojang 官方运行时（见 [JavaRuntimeManifest]），那是 glibc 的构建，
 * 放安卓上跑不了 —— 安卓是 bionic libc。安卓要有专门编译过的 OpenJDK，
 * 目前只有 FCL（FoldCraftLauncher）在维护这样一份构建，四个大版本各一套，按 ABI 切。
 *
 * 他们的包是两层叠加：[universal.tar.xz] 放与架构无关的部分（lib/modules、
 * conf/、legal/、字体），[bin-<abi>.tar.xz] 只放该 ABI 的二进制
 * （bin/java、lib 下的各个 .so，以及 release）。两个都得解开叠进同一个目录才算一套。
 * 实测 jre17：universal 261 个条目、bin-arm64 42 个条目，release 与 bin/java
 * 都在 bin-<abi> 里，所以不能只下一个包。
 *
 * ## 这一版下完还不能启动游戏
 *
 * 安卓端还没有 JavaRuntimeService 的实现、也没有 runtime-android 模块
 * （要在手机上把 JVM 起来再转译 OpenGL）。所以这里下下来的东西目前只是落在盘上，
 * 等那个模块做出来才能用。界面必须把这句话讲清楚，不能让人以为下完就能进游戏。
 *
 * ## 数据来源
 *
 * 清单里的体积是 2026-11-14 用 GitHub contents API 实测的，和实际下载逐字节相符 ——
 * 所以下载器能靠 size 校验，不必等一个 sha256（上游没给）。
 */
data class AndroidJavaPackage(
    val majorVersion: Int,
    /** 安卓的 ABI 名，例如 arm64-v8a。 */
    val abi: String,
    val universalUrl: String,
    val universalSize: Long,
    val binUrl: String,
    val binSize: Long,
    /** 已装时是 jre 目录的绝对路径，否则 null。 */
    val installedPath: String? = null,
) {
    val label: String get() = "Java " + majorVersion

    /** 两个压缩包加起来多少字节 —— 界面上「大约 40 MB」那个数。 */
    val totalBytes: Long get() = universalSize + binSize

    val installed: Boolean get() = !installedPath.isNullOrBlank()
}

/** 下载 + 解包时给界面报的进度。 */
data class AndroidJavaProgress(
    val step: String,
    val detail: String = "",
    val total: Long = 0L,
    val done: Long = 0L,
) {
    val fraction: Float get() = if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 安卓 JRE 的清单与地址。
 *
 * 上游是 FCL 仓库里的 FCL/src/main/jreAssets/app_runtime/java/<jre>/。
 * 直连 raw.githubusercontent 在本机只有 2 KB/s（实测 42 秒收到 64 KB），
 * 所以默认走 [MIRROR_GH_PROXY]，[MIRROR_GHFAST] 当备用。
 */
object AndroidJavaCatalog {

    /** GitHub 原生域，实测极慢，只作为最后兜底。 */
    const val RAW_BASE =
        "https://raw.githubusercontent.com/FCL-Team/FoldCraftLauncher/main/FCL/src/main/jreAssets/app_runtime/java/"

    /** 实测 883 KB/s，16 段并发能到 1022 KB/s。 */
    const val MIRROR_GH_PROXY = "https://gh-proxy.com/"

    /** 实测 442 KB/s，备用。 */
    const val MIRROR_GHFAST = "https://ghfast.top/"

    /** 安卓的四种 ABI，顺序就是 Build.SUPPORTED_ABIS 常见的排列。 */
    val ABIS: List<String> = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /** ABI 名 → FCL 包名里的那段后缀。 */
    private val ABI_SUFFIX: Map<String, String> = mapOf(
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "arm",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )

    /**
     * 每一版的体积表：大版本 → (universal 字节, { ABi 后缀 → bin 字节 })。
     *
     * 2026-11-14 用 GitHub contents API 逐个读到的 size。jre25 没有 x86 的构建 ——
     * 上游就没发，不是我们漏了。
     */
    private val SIZES: Map<Int, Pair<Long, Map<String, Long>>> = mapOf(
        8 to (9_540_292L to mapOf("arm" to 3_375_000L, "arm64" to 4_347_796L, "x86" to 3_893_576L, "x86_64" to 6_182_832L)),
        17 to (17_070_632L to mapOf("arm" to 5_558_048L, "arm64" to 4_640_448L, "x86" to 4_986_636L, "x86_64" to 6_088_424L)),
        21 to (18_974_588L to mapOf("arm" to 5_710_624L, "arm64" to 6_847_128L, "x86" to 6_892_112L, "x86_64" to 7_847_856L)),
        25 to (32_814_644L to mapOf("arm" to 4_216_116L, "arm64" to 5_453_168L, "x86_64" to 6_519_636L)),
    )

    /** 有构建的大版本，从小到大。 */
    val MAJORS: List<Int> get() = SIZES.keys.sorted()

    /** ABI 对应的包名后缀；不认识的 ABI 返回 null。 */
    fun abiSuffix(abi: String): String? = ABI_SUFFIX[abi.trim()]

    /**
     * 从设备的 ABI 列表里挑一个我们支持的。
     *
     * 安卓的 Build.SUPPORTED_ABIS 是「优先」顺序，第一个能认的就是该机器的主 ABI。
     * 一个都不认识（比如未来的 riscv64）返回 null，界面据此说明「这台机器的架构还没人编过 Java」。
     */
    fun supportedAbi(supported: List<String>): String? =
        supported.map { it.trim() }.firstOrNull { ABI_SUFFIX.containsKey(it) }

    /** 某个 ABI 上能下的所有版本，从小到大。不认识的 ABI 给空表。 */
    fun packages(
        abi: String,
        installedOf: (Int) -> String? = { null },
    ): List<AndroidJavaPackage> {
        val suffix = abiSuffix(abi) ?: return emptyList()
        return SIZES.entries.sortedBy { it.key }.mapNotNull { (major, entry) ->
            val binSize = entry.second[suffix] ?: return@mapNotNull null
            val rawBase = RAW_BASE + "jre" + major + "/"
            AndroidJavaPackage(
                majorVersion = major,
                abi = abi.trim(),
                universalUrl = MIRROR_GH_PROXY + rawBase + "universal.tar.xz",
                universalSize = entry.first,
                binUrl = MIRROR_GH_PROXY + rawBase + "bin-" + suffix + ".tar.xz",
                binSize = binSize,
                installedPath = installedOf(major),
            )
        }
    }

    /** 同一条地址的备用镜像：主镜像（[MIRROR_GH_PROXY]）失败后挨个再试。 */
    fun mirrorsOf(mainUrl: String): List<String> {
        val raw = mainUrl.removePrefix(MIRROR_GH_PROXY)
        if (raw == mainUrl) return emptyList()
        return listOf(MIRROR_GHFAST + raw, raw)
    }
}

/**
 * 把 tar.xz 解开、把两份叠进同一个目录。
 *
 * 单独拿出来是因为它是这块唯一容易出错的地方：路径穿越、可执行位、512 字节补位。
 * 解包器自己写（没有引 commons-compress）：tar 的头就那几段固定偏移，
 * 引一个 2 MB 的库只为读 6 个字段不划算。xz 解压本身交给 org.tukaani:xz。
 */
object TarXz {

    /** 解一个 .tar.xz 到 destDir，条目名里的相对路径原样保留。 */
    fun extract(archive: File, destDir: File) {
        FileInputStream(archive).use { file ->
            XZInputStream(file).use { xz -> extractTar(xz, destDir) }
        }
    }

    /** 解一个已经解压好的 tar 流。测试直接喂合成的 tar，不必真的压一份 xz。 */
    fun extractTar(input: InputStream, destDir: File) {
        val root = destDir.canonicalFile
        root.mkdirs()
        val header = ByteArray(BLOCK)
        // GNU tar 的长文件名/长链接：先出现一条 typeflag 为 L/K 的条目，内容才是真名字。
        var pendingName: String? = null
        var pendingLink: String? = null
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break
            val type = header[TYPE_OFFSET].toInt().toChar()
            val size = octal(header, SIZE_OFFSET, 12)
            when (type) {
                'L' -> {
                    pendingName = readBody(input, size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    skipPadding(input, size)
                    continue
                }
                'K' -> {
                    pendingLink = readBody(input, size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    skipPadding(input, size)
                    continue
                }
            }
            val name = pendingName ?: fullName(header)
            pendingName = null
            val linkName = pendingLink ?: cString(header, LINK_OFFSET, 100)
            pendingLink = null
            if (name.isBlank()) {
                skipPadding(input, size)
                continue
            }
            val target = File(root, name).canonicalFile
            // 路径穿越：条目想写到目标目录之外就直接拒绝，不静默跳过 ——
            // 静默跳过会解出一个缺文件的 JRE，报错时报在别的地方。
            if (target != root && !target.path.startsWith(root.path + File.separator)) {
                throw IOException("压缩包里的条目想写到目标目录之外：" + name)
            }
            when (type) {
                '5' -> {
                    target.mkdirs()
                    skipPadding(input, size)
                }
                '2', '1' -> {
                    // 符号链接/硬链接：安卓的 JRE 包里没有，遇到就尽力而为，失败不致命。
                    target.parentFile?.mkdirs()
                    runCatching {
                        if (target.exists()) target.delete()
                        if (type == '2') {
                            java.nio.file.Files.createSymbolicLink(target.toPath(), File(linkName).toPath())
                        } else {
                            java.nio.file.Files.createLink(target.toPath(), File(root, linkName).toPath())
                        }
                    }
                    skipPadding(input, size)
                }
                else -> {
                    target.parentFile?.mkdirs()
                    val body = readBody(input, size)
                    target.writeBytes(body)
                    // 可执行位必须留住：bin/java 没有 x 权限，安卓上就起不来。
                    val mode = octal(header, MODE_OFFSET, 8)
                    if (mode and 0b001_000_000L != 0L || mode and 0b000_100_000L != 0L || mode and 0b000_000_100L != 0L) {
                        target.setExecutable(true, false)
                    }
                    skipPadding(input, size)
                }
            }
        }
    }

    private const val BLOCK = 512
    private const val MODE_OFFSET = 100
    private const val SIZE_OFFSET = 124
    private const val TYPE_OFFSET = 156
    private const val LINK_OFFSET = 157
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155

    private fun fullName(header: ByteArray): String {
        val name = cString(header, 0, 100)
        val magic = cString(header, 257, 6)
        if (!magic.startsWith("ustar")) return name
        val prefix = cString(header, PREFIX_OFFSET, PREFIX_LENGTH)
        return if (prefix.isEmpty()) name else prefix + "/" + name
    }

    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = minOf(offset + length, bytes.size)
        while (end < limit && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    /** tar 的数值字段是八进制 ASCII，可能以 NUL 或空格结尾，也可能有前导空格。 */
    private fun octal(bytes: ByteArray, offset: Int, length: Int): Long {
        val text = String(bytes, offset, length, Charsets.US_ASCII).trim().trimEnd('\u0000').trim()
        if (text.isEmpty()) return 0L
        return text.toLongOrNull(8) ?: 0L
    }

    private fun readBody(input: InputStream, size: Long): ByteArray {
        if (size <= 0L) return ByteArray(0)
        if (size > MAX_ENTRY_BYTES) throw IOException("压缩包里有条目标称 " + size + " 字节，超过上限")
        val out = ByteArray(size.toInt())
        var read = 0
        while (read < out.size) {
            val n = input.read(out, read, out.size - read)
            if (n < 0) throw IOException("压缩包在半路结束了：还差 " + (out.size - read) + " 字节")
            read += n
        }
        return out
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((BLOCK - (size % BLOCK).toInt()) % BLOCK).toLong()
        if (pad > 0L) skip(input, pad)
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                if (input.read() < 0) return
                left -= 1
            } else {
                left -= n
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return read > 0
            read += n
        }
        return true
    }

    /** 单个条目上限 512 MB：lib/modules 也就 81 MB，再大基本是坏的。 */
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
}

/**
 * 下载 + 解包一套安卓 JRE。
 *
 * 产物目录是 <目标根>/jre<大版本>-<abi>/，里面直接就是 bin/、lib/、conf/。
 * 解包先落在 .tmp- 前缀的临时目录，全部成功才改名成正式目录 ——
 * 中途断了不会留下一个「看着装了、其实缺 libjvm.so」的目录。
 */
object AndroidJavaInstaller {

    /** 已装判据：bin/java、release 都在。两个都是 bin-<abi> 包里的，缺一个就是没装完。 */
    fun isInstalled(javaHome: File): Boolean =
        File(javaHome, "bin/java").isFile && File(javaHome, "release").isFile

    fun homeOf(root: File, offer: AndroidJavaPackage): File =
        File(root, "jre" + offer.majorVersion + "-" + offer.abi)

    suspend fun install(
        targetRoot: File,
        offer: AndroidJavaPackage,
        onProgress: (AndroidJavaProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val home = homeOf(targetRoot, offer)
        if (isInstalled(home)) {
            onProgress(AndroidJavaProgress("已装", home.absolutePath, 1L, 1L))
            return@withContext home
        }
        targetRoot.mkdirs()
        val cache = File(targetRoot, ".cache")
        cache.mkdirs()
        val universal = File(cache, "jre" + offer.majorVersion + "-" + offer.abi + "-universal.tar.xz")
        val bin = File(cache, "jre" + offer.majorVersion + "-" + offer.abi + "-bin.tar.xz")

        val total = offer.totalBytes
        onProgress(AndroidJavaProgress("下载", offer.label + "（" + offer.abi + "）", total, 0L))
        val downloader = HttpDownloader(parallelism = 2)
        val report = downloader.download(
            listOf(
                DownloadItem(
                    url = offer.universalUrl,
                    target = universal.absolutePath,
                    size = offer.universalSize,
                    mirrors = AndroidJavaCatalog.mirrorsOf(offer.universalUrl),
                ),
                DownloadItem(
                    url = offer.binUrl,
                    target = bin.absolutePath,
                    size = offer.binSize,
                    mirrors = AndroidJavaCatalog.mirrorsOf(offer.binUrl),
                ),
            ),
        ) { progress ->
            onProgress(
                AndroidJavaProgress(
                    step = "下载",
                    detail = progress.completedFiles.toString() + "/" + progress.totalFiles + " 个包",
                    total = total,
                    done = progress.completedBytes,
                ),
            )
        }
        if (!report.ok) {
            val first = report.failures.first()
            throw IOException(
                "下载 " + offer.label + " 失败：" + first.reason +
                    "（已经下完的部分留在 " + cache.absolutePath + "，再点一次会接着下）",
            )
        }

        val work = File(targetRoot, ".tmp-jre" + offer.majorVersion + "-" + offer.abi)
        work.deleteRecursively()
        work.mkdirs()
        try {
            onProgress(AndroidJavaProgress("解包", "通用部分（lib/modules、conf）", total, total))
            TarXz.extract(universal, work)
            onProgress(AndroidJavaProgress("解包", "本机架构的二进制（bin、lib）", total, total))
            TarXz.extract(bin, work)
            onProgress(AndroidJavaProgress("校验", "bin/java 与 release", total, total))
            if (!isInstalled(work)) {
                throw IOException(
                    "解包完了但 " + work.absolutePath + " 里没有 bin/java 或 release，" +
                        "压缩包可能不完整；把 " + cache.absolutePath + " 删掉重下一次。",
                )
            }
            home.deleteRecursively()
            if (!work.renameTo(home)) {
                // 同一个卷上 rename 正常不会失败；失败就退化成复制，慢但能成。
                work.copyRecursively(home, overwrite = true)
                work.deleteRecursively()
            }
        } finally {
            work.deleteRecursively()
        }
        // 压缩包留着没意义：解出来的比它大好几倍，留着只是白占空间。
        universal.delete()
        bin.delete()
        onProgress(AndroidJavaProgress("完成", home.absolutePath, total, total))
        home
    }

    /** 删掉一套已装的 JRE。只删我们自己造的目录，不碰别人放的东西。 */
    fun remove(targetRoot: File, offer: AndroidJavaPackage): Boolean {
        val home = homeOf(targetRoot, offer)
        if (!home.exists()) return false
        return home.deleteRecursively()
    }
}
