package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadPreference
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.runtime.AndroidJavaCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 图形后端要的一个 .so。
 *
 * [size] 是上游仓库里那个文件的真实字节数（2026-11-21 用 GitHub contents API 逐个读的）。
 * 上游没给 sha256，但长度对不上就一定是坏的 —— 下载器拿它当校验用。
 */
data class AndroidGraphicsFile(
    val name: String,
    val size: Long,
    /** 缺了也能启动的那几个（只给界面/ImGui 用的、只给 Vulkan 时间线用的）。 */
    val optional: Boolean = false,
)

/**
 * 「某个后端 + 某个 ABI」要的一整套文件。
 *
 * 安卓上换渲染后端不像桌面上换一个 jar：能选的只有几种 .so，每种还要配套一整组 LWJGL
 * 原生库（少了 liblwjgl.so，游戏走到创建本地窗口那一步就崩在别处）。所以这里按「套」给，
 * 不让人一个个挑文件放进目录。
 */
data class AndroidGraphicsBundle(
    val renderer: AndroidRenderer,
    val abi: String,
    val files: List<AndroidGraphicsFile>,
) {
    /** 全下一遍要多少字节。 */
    val totalBytes: Long get() = files.sumOf { it.size }

    /** 必需的那几个里缺了哪几个（可选的缺了不算）。 */
    fun missingIn(nativeDir: File): List<AndroidGraphicsFile> =
        files.filter { !it.optional && !File(nativeDir, it.name).isFile }

    /** 自带的那一个 .so 在不在 —— 自检里「渲染库文件」查的就是它。 */
    fun rendererLibIn(nativeDir: File): File = File(nativeDir, renderer.libraryName)

    fun isInstalled(nativeDir: File): Boolean = missingIn(nativeDir).isEmpty()

    /** 一个文件的候选地址：主镜像在前，备用镜像与原生域跟在后面。 */
    fun urlsOf(file: AndroidGraphicsFile): List<String> {
        val raw = AndroidGraphicsCatalog.rawUrlOf(abi, file.name)
        return listOf(
            AndroidJavaCatalog.MIRROR_GH_PROXY + raw,
            AndroidJavaCatalog.MIRROR_GHFAST + raw,
            raw,
        )
    }

    /** 还没落盘的那几个变成下载条目；已经在盘上的不再下一次。 */
    fun downloadItems(nativeDir: File): List<DownloadItem> =
        files.filter { !File(nativeDir, it.name).isFile }.map { file ->
            val urls = urlsOf(file)
            DownloadItem(
                url = urls.first(),
                target = File(nativeDir, file.name).absolutePath,
                size = file.size,
                mirrors = urls.drop(1),
            )
        }

    /** 一句能直接显示的话：「GL4ES（arm64-v8a）共 8 个文件，约 33 MB」。 */
    val summary: String
        get() = renderer.label + "（" + abi + "）共 " + files.size + " 个文件，约 " + megabytes(totalBytes)
}

/** 下载与校验的进度。形状与 [io.github.huang404yan.platypus.core.runtime.AndroidJavaProgress] 一致。 */
data class AndroidGraphicsProgress(
    val step: String,
    val detail: String = "",
    val total: Long = 0L,
    val done: Long = 0L,
) {
    val fraction: Float get() = if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** 装完之后的结果。[badFiles] 非空说明这几个长度对不上（上游换过文件）。 */
data class AndroidGraphicsResult(
    val bundle: AndroidGraphicsBundle,
    val nativeDir: File,
    val downloaded: Int,
    val skipped: Int,
    val badFiles: List<String>,
    val elapsedMs: Long,
) {
    val ok: Boolean get() = badFiles.isEmpty() && bundle.isInstalled(nativeDir)
}

/** 一个字节数说成人话。33,224,000 → 「31.7 MB」。 */
fun megabytes(bytes: Long): String {
    if (bytes <= 0L) return "未知";
    val mb = bytes / 1048576.0
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}


/**
 * 图形后端清单：哪个后端要哪几个 .so、多大、去哪儿取。
 *
 * ## 为什么不随 APK 分发
 *
 * 一套约 20–45 MB、四个 ABI 就是一百多 MB，而一台设备只会用到其中一套。放 APK 里是让
 * 所有人替一个人付体积，所以做成按需下载：要用哪个下哪个，下过一次就不再下。
 *
 * ## 来源
 *
 * PojavLauncher 的 `v3_openjdk` 分支，`app_pojavlauncher/src/main/jniLibs/<abi>/`。
 * 那套 .so 就是安卓上跑 MC 一直在用的那套（GL4ES / Zink 加 LWJGL 原生），我们不重复
 * 编译，也不改它的构建方式，只在本地核对长度后落盘。
 *
 * 直连 raw.githubusercontent 在本机实测会被重置（curl 退出码 35），所以主镜像沿用 JRE
 * 那条链路：gh-proxy.com → ghfast.top → 原生域。
 */
object AndroidGraphicsCatalog {

    /** 上游仓库里 jniLibs 的原始目录前缀（末尾带斜杠）。 */
    const val RAW_BASE =
        "https://raw.githubusercontent.com/PojavLauncherTeam/PojavLauncher/v3_openjdk/app_pojavlauncher/src/main/jniLibs/"

    /** 换哪个后端都会用到的那些：LWJGL 的原生实现、字体、JNA、声音。 */
    private val COMMON: List<String> = listOf(
        "liblwjgl.so",
        "liblwjgl_opengl.so",
        "liblwjgl_stb.so",
        "liblwjgl_tinyfd.so",
        "liblwjgl_nanovg.so",
        "libfreetype.so",
        "libjnidispatch.so",
        "libopenal.so",
    )

    /** 缺了也能启动的：nanovg 只给 ImGui 类界面用，Vulkan 时间线层只给 Zink 用。 */
    private val OPTIONAL: Set<String> = setOf(
        "liblwjgl_nanovg.so",
        "libVkLayer_khronos_timeline_semaphore.so",
    )

    /** 每个后端自己那几个。LTW 上游没有对应的预编译库，只能是空表。 */
    private val RENDERER_FILES: Map<String, List<String>> = mapOf(
        AndroidRenderer.GL4ES.id to listOf("libgl4es_114.so"),
        AndroidRenderer.ZINK.id to listOf("libOSMesa.so", "libVkLayer_khronos_timeline_semaphore.so"),
    )

    /**
     * 每个 ABI 下每个文件的真实字节数。
     *
     * 上游会更新这些 .so，届时长度对不上会让下载报错。真遇到时按报错里的名字去 GitHub 上
     * 读一次新尺寸改这里 —— 这比「放弃校验、装一个坏的上去」好，那种失败会晚到游戏里，
     * 表现成毫无线索的闪退。
     */
    private val SIZES: Map<String, Map<String, Long>> = mapOf(
        "arm64-v8a" to mapOf(
            "libgl4es_114.so" to 2_649_480L,
            "libOSMesa.so" to 13_322_080L,
            "libVkLayer_khronos_timeline_semaphore.so" to 214_936L,
            "libfreetype.so" to 706_136L,
            "libjnidispatch.so" to 168_176L,
            "liblwjgl.so" to 495_456L,
            "liblwjgl_nanovg.so" to 351_376L,
            "liblwjgl_opengl.so" to 360_280L,
            "liblwjgl_stb.so" to 347_408L,
            "liblwjgl_tinyfd.so" to 81_912L,
            "libopenal.so" to 15_914_936L,
        ),
        "armeabi-v7a" to mapOf(
            "libgl4es_114.so" to 1_713_044L,
            "libOSMesa.so" to 12_806_436L,
            "libfreetype.so" to 651_984L,
            "libjnidispatch.so" to 122_296L,
            "liblwjgl.so" to 387_072L,
            "liblwjgl_nanovg.so" to 350_996L,
            "liblwjgl_opengl.so" to 301_760L,
            "liblwjgl_stb.so" to 326_112L,
            "liblwjgl_tinyfd.so" to 88_600L,
            "libopenal.so" to 15_289_544L,
        ),
        "x86_64" to mapOf(
            "libgl4es_114.so" to 2_760_568L,
            "libOSMesa.so" to 14_532_888L,
            "libfreetype.so" to 795_216L,
            "libjnidispatch.so" to 118_584L,
            "liblwjgl.so" to 471_184L,
            "liblwjgl_nanovg.so" to 419_656L,
            "liblwjgl_opengl.so" to 374_504L,
            "liblwjgl_stb.so" to 412_368L,
            "liblwjgl_tinyfd.so" to 83_504L,
            "libopenal.so" to 16_112_824L,
        ),
        "x86" to mapOf(
            "libgl4es_114.so" to 2_808_944L,
            "libfreetype.so" to 765_656L,
            "libjnidispatch.so" to 120_184L,
            "liblwjgl.so" to 419_540L,
            "liblwjgl_nanovg.so" to 432_984L,
            "liblwjgl_opengl.so" to 439_024L,
            "liblwjgl_stb.so" to 426_088L,
            "liblwjgl_tinyfd.so" to 84_636L,
            "libopenal.so" to 17_228_488L,
        ),
    )

    /** 上游编过的那四个 ABI。其它架构（riscv64 之类）一概没有，界面要如实说。 */
    val ABIS: List<String> = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    /** 一个文件在上游的原始地址。 */
    fun rawUrlOf(abi: String, fileName: String): String = RAW_BASE + abi + "/" + fileName

    /** 这个 ABI 有没有对应的预编译库。 */
    fun supports(abi: String): Boolean = SIZES.containsKey(abi.trim())

    /**
     * 某个后端在某个 ABI 上要的一整套。
     *
     * 返回 null 的三种情形都要在界面上说清楚，不能让人以为是「点了没反应」：
     * 这个 ABI 上游没编过；这个后端在这个 ABI 上跑不了（Zink 要 Vulkan，32 位那几套里
     * 连 libOSMesa 都没有）；这个后端上游没有预编译库（LTW）。
     */
    fun bundleOf(renderer: AndroidRenderer, abi: String = ""): AndroidGraphicsBundle? {
        val key = abiMetadataKey(abi)
        if (key.isBlank()) return null
        if (!renderer.supportedOn(key)) return null
        val names = RENDERER_FILES[renderer.id] ?: return null
        val sizes = SIZES[key] ?: return null
        val all = (names + COMMON).distinct()
        val files = all.mapNotNull { name ->
            val size = sizes[name] ?: return@mapNotNull null
            AndroidGraphicsFile(name = name, size = size, optional = name in OPTIONAL)
        }
        // 后端自己那个 .so 必须有尺寸记录，否则这份清单是自相矛盾的。
        if (files.none { it.name == renderer.libraryName }) return null
        return AndroidGraphicsBundle(renderer = renderer, abi = key, files = files)
    }

    /** 某个 ABI 上能选的后端：过滤掉没有预编译库的那些。 */
    fun renderersOn(abi: String): List<AndroidRenderer> =
        AndroidRenderer.supportedOn(abi).filter { bundleOf(it, abi) != null }

    /**
     * 把 Build.SUPPORTED_ABIS 里那几个名字收敛成上游用的那四个。
     *
     * `aarch64`（Linux 惯用名）也要认，因为有些设备的 SUPPORTED_ABIS 会给这个；
     * 认不出来返回空串，调用方据此说「这台机器的架构还没有对应的图形库」。
     */
    private fun abiMetadataKey(abi: String): String {
        val raw = abi.trim().lowercase()
        if (raw.isEmpty()) return ""
        if (SIZES.containsKey(raw)) return raw
        return when (raw) {
            "aarch64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            "amd64" -> "x86_64"
            "x64" -> "x86_64"
            "i686", "x86_32" -> "x86"
            else -> ""
        }
    }
}

/**
 * 按套下载图形后端，落到实例自己的原生库目录。
 *
 * 只做三件事：没下过的下下来、长度对不上的删掉、下完再核一遍必需文件齐不齐。
 * 不碰已经在盘上的文件 —— 那些可能是用户手动放进去的定制库，覆盖掉等于砸人家的东西。
 */
object AndroidGraphicsInstaller {

    suspend fun install(
        nativeDir: File,
        bundle: AndroidGraphicsBundle,
        onProgress: (AndroidGraphicsProgress) -> Unit = {},
    ): AndroidGraphicsResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val total = bundle.totalBytes
        nativeDir.mkdirs()
        if (!nativeDir.isDirectory) {
            throw IOException("建不出原生库目录 " + nativeDir.absolutePath + "，检查一下存储权限")
        }

        val items = bundle.downloadItems(nativeDir)
        if (items.isEmpty()) {
            onProgress(AndroidGraphicsProgress("已就绪", nativeDir.absolutePath, total, total))
            return@withContext AndroidGraphicsResult(
                bundle = bundle,
                nativeDir = nativeDir,
                downloaded = 0,
                skipped = bundle.files.size,
                badFiles = emptyList(),
                elapsedMs = System.currentTimeMillis() - startedAt,
            )
        }

        onProgress(AndroidGraphicsProgress("下载", bundle.summary, total, 0L))
        val downloader = HttpDownloader(parallelism = DownloadPreference.effectiveConnections())
        val report = downloader.download(items) { progress ->
            onProgress(
                AndroidGraphicsProgress(
                    step = "下载",
                    detail = progress.completedFiles.toString() + "/" + progress.totalFiles + " 个文件",
                    total = total,
                    done = progress.completedBytes,
                ),
            )
        }
        if (!report.ok) {
            val first = report.failures.first()
            throw IOException(
                "下载图形后端失败：" + first.reason +
                    "（已经下完的部分留在 " + nativeDir.absolutePath + "，再点一次会接着下）",
            )
        }

        onProgress(AndroidGraphicsProgress("校验", "文件长度", total, total))
        val bad = ArrayList<String>()
        for (file in bundle.files) {
            val target = File(nativeDir, file.name)
            if (!target.isFile) {
                if (!file.optional) bad += file.name
                continue
            }
            if (target.length() != file.size) {
                // 长度不对就删：留着它游戏会崩在它身上，而报错里看不出是它。
                target.delete()
                if (!file.optional) bad += file.name
            }
        }
        onProgress(AndroidGraphicsProgress("完成", nativeDir.absolutePath, total, total))
        AndroidGraphicsResult(
            bundle = bundle,
            nativeDir = nativeDir,
            downloaded = items.size,
            skipped = bundle.files.size - items.size,
            badFiles = bad,
            elapsedMs = System.currentTimeMillis() - startedAt,
        )
    }

    /** 删掉这套后端下过的文件。只删清单里列过的名字，不删目录里别人放的东西。 */
    fun remove(nativeDir: File, bundle: AndroidGraphicsBundle): Int {
        var removed = 0
        for (file in bundle.files) {
            val target = File(nativeDir, file.name)
            if (target.isFile && target.delete()) removed++
        }
        return removed
    }
}
