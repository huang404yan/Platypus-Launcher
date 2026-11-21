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
    /**
     * 有多少个条目的体积是未知的（size = -1）。
     *
     * 这些条目没法算进 [totalBytes]，所以 [fraction] 会偏大、永远到不了 1.0 之前就已经
     * 看着「快满了」。界面拿到这个数就该把百分比藏起来，改用「已完成 N/M 个文件」——
     * 显示一个假的 100% 比不显示更糟。
     */
    val unknownSizeFiles: Int = 0,
    /**
     * 最近一次真正发出去的地址。
     *
     * 候选表是「主地址 + 镜像」按下载源偏好排出来的，主地址失败会自动退到下一个。
     * 界面要显示的「现在走哪个源」就该读这个字段，而不是读设置里的偏好 ——
     * 两者不一致的时候（镜像挂了、退到官方源）才是用户最需要知道的那件事。
     */
    val activeUrl: String = "",
    /**
     * 最后一次真正从网络读到字节的时刻（[System.currentTimeMillis]），0 表示还没开始读。
     *
     * 断网之后进度条还会再往前挪一小段，是因为内核缓冲区里剩的那几 KB 会被一次性吐出来。
     * 有了这个时间戳，界面才能如实说「已经 N 秒没收到数据」，而不是让一个还在缓慢上涨的
     * 进度条冒充「还在下载」。
     */
    val lastByteAtMillis: Long = 0L,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    /** 进度是否可信：分母里有没有漏掉的条目。 */
    val fractionReliable: Boolean get() = unknownSizeFiles == 0 && totalBytes > 0
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
