package io.github.huang404yan.platypus.core.download

data class DownloadItem(
    val url: String,
    val target: String,
    val sha1: String? = null,
    val size: Long = -1L,
    val mirrors: List<String> = emptyList(),
)

/**
 * 进度快照。
 *
 * [resumedFiles] / [resumedBytes] 是「其中有多少是接着上次没下完的部分」——断点续传真的生效时
 * 这两个数大于 0，界面可以据此告诉用户「不是从头再来」，而不必让用户凭下载速度猜。
 * 它们已经包含在 [completedFiles] / [completedBytes] 里，是子集，不是额外累加。
 */
data class DownloadProgress(
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val resumedFiles: Int = 0,
    val resumedBytes: Long = 0L,
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
