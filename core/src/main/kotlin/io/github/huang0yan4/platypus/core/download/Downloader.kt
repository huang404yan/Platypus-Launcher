package io.github.huang0yan4.platypus.core.download

data class DownloadItem(
    val url: String,
    val target: String,
    val sha1: String? = null,
    val size: Long = -1L,
    val mirrors: List<String> = emptyList(),
)

data class DownloadProgress(
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

data class DownloadFailure(val item: DownloadItem, val reason: String)

data class DownloadReport(
    val succeeded: Int,
    val failures: List<DownloadFailure>,
) {
    val ok: Boolean get() = failures.isEmpty()
}

interface Downloader {
    suspend fun download(
        items: List<DownloadItem>,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadReport
}
