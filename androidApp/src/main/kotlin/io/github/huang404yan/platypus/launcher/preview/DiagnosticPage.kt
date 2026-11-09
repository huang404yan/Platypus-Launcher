package io.github.huang404yan.platypus.launcher.preview

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.huang404yan.platypus.core.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断页：把「我这边到底能不能跑」变成用户自己能看见、能复制的文字。
 *
 * 起因是验证方式变了——不再连 USB 跑 adb logcat，而是用户自己装 APK 测，
 * 出问题时唯一能回传的就是界面上的文字。所以这里有两个硬要求：
 *
 *  1. 每条检测都记**原始异常类名 + message**，不许只写「失败」；
 *  2. 整页能一键复制成纯文本，用户直接粘进聊天窗口。
 *
 * 这一页只读：唯一的写操作是外部存储根下 .platypus-diag 里的一次性探针文件，写完立刻删掉。
 */

internal data class DiagnosticRow(val label: String, val value: String, val ok: Boolean? = null)

internal data class DiagnosticSection(val title: String, val rows: List<DiagnosticRow>)

@Composable
internal fun DiagnosticPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sections by remember { mutableStateOf<List<DiagnosticSection>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (running) return
        running = true
        notice = null
        scope.launch {
            sections = Diagnostics.collect(context)
            running = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("诊断", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "这一页就是日志。手机上拿不到 logcat 时，点「复制诊断信息」，把整段文字发回来即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { refresh() }, enabled = !running) {
                Text(if (running) "检测中…" else "重新检测")
            }
            OutlinedButton(
                onClick = { notice = copyToClipboard(context, Diagnostics.toText(sections)) },
                enabled = sections.isNotEmpty(),
            ) {
                Text("复制诊断信息")
            }
        }

        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sections.forEach { section ->
                item { SectionHeader(section.title) }
                items(section.rows) { row -> RowView(row) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun RowView(row: DiagnosticRow) {
    val color = when (row.ok) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(
                row.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(150.dp),
            )
            Text(row.value, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
        }
    }
}

internal fun copyToClipboard(context: Context, text: String): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("Platypus diagnostics", text))
    return "已复制 " + text.length + " 个字符，粘到聊天窗口发回来就行。"
}

internal fun describe(t: Throwable): String {
    val message = t.message
    return t::class.java.name + ": " + (if (message.isNullOrBlank()) "(message 为空)" else message)
}

private fun describeCurrentThread(): String =
    Thread.currentThread().name + " / prio " + Thread.currentThread().priority

internal object Diagnostics {

    private const val MANIFEST_PROBE = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    // 随便一个不存在的资源对象：能拿到 404 就说明 DNS、TLS、HTTP 都通了。
    private const val RESOURCES_PROBE =
        "https://resources.download.minecraft.net/00/0000000000000000000000000000000000000000"

    suspend fun collect(context: Context): List<DiagnosticSection> = withContext(Dispatchers.IO) {
        val sections = mutableListOf<DiagnosticSection>()
        sections += application(context)
        sections += system(context)
        sections += storage(context)
        sections += memory(context)
        sections += network()
        sections += graphics()
        sections += gameDirectories()
        sections.toList()
    }

    fun toText(sections: List<DiagnosticSection>): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val builder = StringBuilder()
        builder.append("鸭嘴兽启动器 · 诊断信息").append('\n')
        builder.append("导出时间：").append(stamp).append('\n')
        builder.append("导出线程：").append(describeCurrentThread()).append('\n')
        builder.append('\n')
        sections.forEach { section ->
            builder.append('[').append(section.title).append(']').append('\n')
            section.rows.forEach { row ->
                builder.append(row.label).append(" = ").append(row.value).append('\n')
            }
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun application(context: Context): DiagnosticSection {
        val rows = mutableListOf<DiagnosticRow>()
        val packageName = context.packageName
        rows += DiagnosticRow("applicationId", packageName)
        val info = runCatching { context.packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        if (info == null) {
            rows += DiagnosticRow("包信息", "PackageManager.getPackageInfo 返回 null", false)
        } else {
            rows += DiagnosticRow("versionName", info.versionName ?: "(空)")
            @Suppress("DEPRECATION")
            val versionCode = info.versionCode
            rows += DiagnosticRow("versionCode", versionCode.toString())
        }
        rows += DiagnosticRow("品牌名", if (Brand.isPreview) Brand.PREVIEW_NAME_ZH else Brand.NAME_ZH)
        rows += DiagnosticRow("Brand.version", Brand.version)
        rows += DiagnosticRow("通道", if (Brand.isPreview) "孵化版" else "正式版")
        rows += DiagnosticRow("数据目录", runCatching { context.filesDir.absolutePath }.getOrElse { describe(it) })
        return DiagnosticSection("应用", rows)
    }

    private fun system(context: Context): DiagnosticSection {
        val rows = mutableListOf<DiagnosticRow>()
        rows += DiagnosticRow("厂商 / 型号", Build.MANUFACTURER + " " + Build.MODEL)
        rows += DiagnosticRow("设备代号", Build.DEVICE + " / " + Build.PRODUCT)
        rows += DiagnosticRow("Android 版本", Build.VERSION.RELEASE + "（SDK " + Build.VERSION.SDK_INT + "）")
        rows += DiagnosticRow("系统构建", Build.DISPLAY)
        rows += DiagnosticRow("内核", runCatching { System.getProperty("os.version") ?: "(空)" }.getOrElse { describe(it) })
        rows += DiagnosticRow("支持的 ABI", Build.SUPPORTED_ABIS.joinToString(", "))
        rows += DiagnosticRow("首选 ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "(空)")
        rows += DiagnosticRow("CPU 核数", Runtime.getRuntime().availableProcessors().toString())
        rows += DiagnosticRow("系统语言", Locale.getDefault().toString())
        val metrics = context.resources.displayMetrics
        rows += DiagnosticRow("屏幕", metrics.widthPixels.toString() + " x " + metrics.heightPixels + " px / " + metrics.densityDpi + " dpi")
        return DiagnosticSection("系统", rows)
    }

    private fun storage(context: Context): DiagnosticSection {
        val rows = mutableListOf<DiagnosticRow>()

        val managerGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            hasFileAccess(context)
        }
        rows += DiagnosticRow("所有文件访问权限", if (managerGranted) "已授予" else "未授予", managerGranted)
        rows += DiagnosticRow("hasFileAccess()", hasFileAccess(context).toString(), hasFileAccess(context))

        val base = Environment.getExternalStorageDirectory()
        rows += DiagnosticRow("外部存储根", base.absolutePath)
        rows += DiagnosticRow("根目录存在", if (base.isDirectory) "是" else "否", base.isDirectory)
        rows += DiagnosticRow("根目录可读", if (base.canRead()) "是" else "否", base.canRead())
        rows += DiagnosticRow("根目录可写", if (base.canWrite()) "是" else "否", base.canWrite())

        val listing = runCatching { base.list() }.getOrNull()
        if (listing == null) {
            rows += DiagnosticRow("顶层条目数", "list() 返回 null：被拒绝或目录不可读", false)
        } else {
            rows += DiagnosticRow("顶层条目数", listing.size.toString(), true)
            rows += DiagnosticRow("顶层前 10 项", listing.take(10).joinToString(", ").ifBlank { "(空)" })
        }

        rows += DiagnosticRow(
            "可用空间",
            runCatching { fmtBytes(base.usableSpace) + " / 共 " + fmtBytes(base.totalSpace) }
                .getOrElse { describe(it) },
        )

        // 写入探针：真正验证 MANAGE_EXTERNAL_STORAGE 是否生效，而不是只看开关。
        val probeDir = File(base, ".platypus-diag")
        try {
            probeDir.mkdirs()
            val probeFile = File(probeDir, "probe.txt")
            probeFile.writeText("ok")
            val readBack = probeFile.readText()
            if (readBack == "ok") {
                rows += DiagnosticRow("写入探针", "成功（建目录 + 写 + 读回一致 + 已清理）", true)
            } else {
                rows += DiagnosticRow("写入探针", "读回内容不一致：" + readBack, false)
            }
        } catch (t: Throwable) {
            rows += DiagnosticRow("写入探针", describe(t), false)
        } finally {
            runCatching { File(probeDir, "probe.txt").delete() }
            runCatching { probeDir.delete() }
        }
        return DiagnosticSection("存储", rows)
    }

    private fun memory(context: Context): DiagnosticSection {
        val rows = mutableListOf<DiagnosticRow>()
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            rows += DiagnosticRow("系统总内存", fmtBytes(info.totalMem))
            rows += DiagnosticRow("系统可用内存", fmtBytes(info.availMem))
            rows += DiagnosticRow("低内存状态", if (info.lowMemory) "是（系统认为内存紧张）" else "否", !info.lowMemory)
            rows += DiagnosticRow("低内存阈值", fmtBytes(info.threshold))
        } catch (t: Throwable) {
            rows += DiagnosticRow("系统内存", describe(t), false)
        }

        val runtime = Runtime.getRuntime()
        rows += DiagnosticRow("应用堆上限", fmtBytes(runtime.maxMemory()))
        rows += DiagnosticRow("应用堆已分配", fmtBytes(runtime.totalMemory()))
        rows += DiagnosticRow("应用堆空闲", fmtBytes(runtime.freeMemory()))
        val bigHeap = runtime.maxMemory() >= 1024L * 1024 * 1024
        rows += DiagnosticRow("堆上限是否 >= 1 GB", if (bigHeap) "是" else "否", bigHeap)
        return DiagnosticSection("内存", rows)
    }

    private fun network(): DiagnosticSection {
        val rows = mutableListOf<DiagnosticRow>()
        rows += httpProbe("版本清单", MANIFEST_PROBE, "期望 200")
        rows += httpProbe("资源 CDN", RESOURCES_PROBE, "期望 403/404，能回状态码就算通")
        return DiagnosticSection("网络（每次都实测，失败会带异常类名）", rows)
    }

    private fun httpProbe(label: String, url: String, expectation: String): DiagnosticRow {
        val started = System.nanoTime()
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PlatypusLauncher/0.1 (diagnostics)")
            }
            val code = connection.responseCode
            var bytes = 0L
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            if (stream != null) {
                stream.use {
                    val buffer = ByteArray(8192)
                    var rounds = 0
                    while (rounds < 64) {
                        val read = it.read(buffer)
                        if (read <= 0) break
                        bytes += read
                        rounds++
                    }
                }
            }
            connection.disconnect()
            val elapsed = (System.nanoTime() - started) / 1_000_000
            DiagnosticRow(
                label,
                url + "\nHTTP " + code + " · " + elapsed + " ms · 读 " + bytes + " B · " + expectation,
                code in 200..299 || code in 400..499,
            )
        } catch (t: Throwable) {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            DiagnosticRow(label, url + "\n" + describe(t) + "（" + elapsed + " ms 后放弃）", false)
        }
    }

    private fun graphics(): DiagnosticSection {
        val rows = mutableListOf<DiagnosticRow>()
        var display: EGLDisplay? = null
        var surface: EGLSurface? = null
        var glContext: EGLContext? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                rows += DiagnosticRow("eglGetDisplay", "返回 EGL_NO_DISPLAY", false)
                return DiagnosticSection("图形（EGL / GLES）", rows)
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                rows += DiagnosticRow(
                    "eglInitialize",
                    "失败，eglGetError = 0x" + Integer.toHexString(EGL14.eglGetError()),
                    false,
                )
                return DiagnosticSection("图形（EGL / GLES）", rows)
            }
            rows += DiagnosticRow("EGL 版本", version[0].toString() + "." + version[1])

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val chosen = EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0)
            val config = configs[0]
            if (!chosen || count[0] == 0 || config == null) {
                rows += DiagnosticRow("eglChooseConfig", "没有可用的 ES2 + Pbuffer 配置", false)
                return DiagnosticSection("图形（EGL / GLES）", rows)
            }

            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE),
                0,
            )
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                rows += DiagnosticRow("eglCreatePbufferSurface", "失败，eglGetError = 0x" + Integer.toHexString(EGL14.eglGetError()), false)
                return DiagnosticSection("图形（EGL / GLES）", rows)
            }

            glContext = createContext(display, config, 3) ?: createContext(display, config, 2)
            if (glContext == null || glContext == EGL14.EGL_NO_CONTEXT) {
                rows += DiagnosticRow("eglCreateContext", "ES3 与 ES2 都建不出来，eglGetError = 0x" + Integer.toHexString(EGL14.eglGetError()), false)
                return DiagnosticSection("图形（EGL / GLES）", rows)
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, glContext)) {
                rows += DiagnosticRow("eglMakeCurrent", "失败，eglGetError = 0x" + Integer.toHexString(EGL14.eglGetError()), false)
                return DiagnosticSection("图形（EGL / GLES）", rows)
            }

            rows += DiagnosticRow("GL_RENDERER", nullSafe(GLES20.glGetString(GLES20.GL_RENDERER)))
            rows += DiagnosticRow("GL_VENDOR", nullSafe(GLES20.glGetString(GLES20.GL_VENDOR)))
            rows += DiagnosticRow("GL_VERSION", nullSafe(GLES20.glGetString(GLES20.GL_VERSION)))
            rows += DiagnosticRow("GL_SHADING_LANGUAGE_VERSION", nullSafe(GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION)))

            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
            val interesting = listOf(
                "GL_OES_EGL_image",
                "GL_EXT_texture_filter_anisotropic",
                "GL_OES_vertex_array_object",
                "GL_EXT_buffer_storage",
                "GL_EXT_color_buffer_float",
                "GL_KHR_debug",
                "GL_EXT_disjoint_timer_query",
            )
            val present = interesting.filter { extensions.contains(it) }
            rows += DiagnosticRow("关键扩展命中", if (present.isEmpty()) "（一条都没命中）" else present.joinToString(", "))
            rows += DiagnosticRow("扩展总数", extensions.split(' ').count { it.isNotBlank() }.toString())
        } catch (t: Throwable) {
            rows += DiagnosticRow("图形探测异常", describe(t), false)
        } finally {
            runCatching {
                if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != null && surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (glContext != null && glContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, glContext)
                    EGL14.eglTerminate(display)
                }
            }
        }
        return DiagnosticSection("图形（EGL / GLES）", rows)
    }

    private fun createContext(display: EGLDisplay, config: EGLConfig, clientVersion: Int): EGLContext? {
        val attribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, EGL14.EGL_NONE)
        return EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attribs, 0)
    }

    private fun nullSafe(value: String?): String = if (value.isNullOrBlank()) "(null)" else value

    private fun gameDirectories(): DiagnosticSection {
        val rows = mutableListOf<DiagnosticRow>()
        val base = Environment.getExternalStorageDirectory().absolutePath
        val candidates = listOf(
            base + "/.minecraft",
            base + "/minecraft",
            base + "/games/PojavLauncher/.minecraft",
            base + "/Android/data/net.kdt.pojavlaunch/files/.minecraft",
            base + "/.platypus",
        )
        candidates.forEach { path ->
            val file = File(path)
            val versions = File(file, "versions")
            val count = if (versions.isDirectory) versions.list()?.size ?: 0 else 0
            rows += DiagnosticRow(
                path.removePrefix(base),
                if (file.isDirectory) "存在，versions 下 " + count + " 项" else "不存在",
                file.isDirectory,
            )
        }
        rows += DiagnosticRow("默认游戏目录", defaultGameRoot())
        return DiagnosticSection("游戏目录", rows)
    }
}

internal fun fmtBytes(bytes: Long): String {
    if (bytes < 1024) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.size - 1) {
        value /= 1024
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}
