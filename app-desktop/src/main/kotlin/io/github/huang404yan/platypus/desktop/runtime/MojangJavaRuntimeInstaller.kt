package io.github.huang404yan.platypus.desktop.runtime

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadProgress
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeComponents
import io.github.huang404yan.platypus.core.runtime.MOJANG_RUNTIME_INDEX_URL
import io.github.huang404yan.platypus.core.runtime.JavaRuntimeEntry
import io.github.huang404yan.platypus.core.runtime.JavaRuntimePlan
import io.github.huang404yan.platypus.core.runtime.javaExecutableInRuntime
import io.github.huang404yan.platypus.core.runtime.parseRuntimeIndex
import io.github.huang404yan.platypus.core.runtime.planRuntimeManifest
import io.github.huang404yan.platypus.core.runtime.runtimeJavaProblem
import io.github.huang404yan.platypus.core.runtime.runtimePlanProblem
import io.github.huang404yan.platypus.core.runtime.runtimePlatform
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从 Mojang 官方 runtime 清单装一个 JRE 到 <gameRoot>/runtime/<component>。
 *
 * 只用官方 raw 文件（manifest 里 `downloads.raw` 就是成品），不解压 lzma——实测 gamma 的
 * 486 个条目里有 file 条目没有 lzma 键，走 lzma 会直接 KeyError；raw 全量约 150 MB。
 *
 * 安装是幂等的：[javaExecutable] 已存在就当作装好（HttpDownloader 也会按 sha1 跳过已下载文件）。
 */
class MojangJavaRuntimeInstaller(
    private val gameRoot: File,
    private val indexUrl: String = MOJANG_RUNTIME_INDEX_URL,
    private val parallelism: Int = 16,
    private val platform: String = runtimePlatform(),
) {

    /** 上次失败的原因，供 UI 直接展示（成功时为 null）。 */
    @Volatile
    var lastFailure: String? = null
        private set

    val runtimeRoot: File get() = File(gameRoot, "runtime")

    /**
     * 确保 [major] 版本的运行时可用，返回 java 可执行文件。
     *
     * @param component 版本 json 里的 javaVersion.component；为空时按主版本号兜底映射
     */
    suspend fun ensure(
        major: Int,
        component: String? = null,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        lastFailure = null
        val resolved = resolveComponent(major, component)
            ?: throw JavaRuntimeInstallException(
                "Mojang 没有提供 Java $major 的官方运行时（只有 8/16/17/21/25）。请手动指定 Java 路径。",
            )
        val runtimeDir = File(runtimeRoot, resolved)
        val existing = javaExecutableInRuntime(runtimeDir, platform)
        if (existing.isFile) {
            onProgress(1f)
            return@withContext existing
        }

        val index = runCatching { parseRuntimeIndex(fetchText(indexUrl)) }
            .getOrElse {
                lastFailure = "拉取运行时索引失败：${it.message}"
                throw JavaRuntimeInstallException(lastFailure!!, it)
            }
        val entry = index.find(platform, resolved)
            ?: throw JavaRuntimeInstallException(
                "官方运行时索引里没有 $platform/$resolved（可能该平台不支持 Java $major）。",
            )

        onProgress(0.02f)
        val manifest = runCatching { fetchText(entry.manifestUrl) }
            .getOrElse {
                lastFailure = "拉取 $resolved 的文件清单失败：${it.message}"
                throw JavaRuntimeInstallException(lastFailure!!, it)
            }
        val plan = planRuntimeManifest(manifest, resolved, runtimeDir, platform)
        runtimePlanProblem(resolved, plan)?.let {
            lastFailure = it
            throw JavaRuntimeInstallException(it)
        }

        plan.directories.forEach { File(it).mkdirs() }
        onProgress(0.05f)
        val report = HttpDownloader(parallelism = parallelism).download(plan.files) { progress ->
            onProgress(scale(progress, plan))
        }
        if (!report.ok) {
            val detail = report.failures.take(3).joinToString("；") { it.item.target.substringAfterLast(File.separator) + " → " + it.reason }
            lastFailure = "$resolved 有 ${report.failures.size} 个文件下载失败：$detail"
            throw JavaRuntimeInstallException(lastFailure!!)
        }

        val java = File(plan.javaExecutable)
        runtimeJavaProblem(java)?.let {
            lastFailure = "$resolved $it"
            throw JavaRuntimeInstallException(lastFailure!!)
        }
        onProgress(1f)
        java
    }

    /** 组件 id：优先用版本 json 里的，其次按主版本兜底。 */
    fun resolveComponent(major: Int, component: String?): String? {
        val trimmed = component?.trim().orEmpty()
        return when {
            trimmed.isNotEmpty() -> trimmed
            else -> JavaRuntimeComponents.componentForMajor(major)
        }
    }

    private fun scale(progress: DownloadProgress, plan: JavaRuntimePlan): Float {
        val done = if (progress.totalBytes > 0) {
            progress.completedBytes.toFloat() / progress.totalBytes
        } else if (progress.totalFiles > 0) {
            progress.completedFiles.toFloat() / progress.totalFiles
        } else {
            0f
        }
        return (0.05f + 0.95f * done).coerceIn(0f, 1f)
    }

    private fun fetchText(url: String): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code：$url")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val USER_AGENT = "PlatypusLauncher/0.1 (+https://github.com/huang404yan/Platypus-Launcher)"
    }
}

/** 自动补 Java 失败时抛这个，message 直接拿去显示给用户。 */
class JavaRuntimeInstallException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
