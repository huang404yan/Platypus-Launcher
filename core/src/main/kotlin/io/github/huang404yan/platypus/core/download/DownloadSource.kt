package io.github.huang404yan.platypus.core.download

/**
 * 下载源偏好：同一个文件往往有官方源与国内镜像两条路，这里决定先走哪条。
 *
 * 只有两个选项，**没有「只走一条」**：那等于把兜底关掉。官方源被墙的时候，
 * 用户选了它就只能看着进度条不动，而镜像本来是他唯一能下成的那条路。
 * 「优先镜像」也不是「只走镜像」——镜像挂了照样退回官方源。两个选项的差别只是顺序。
 *
 * 2026-11-16 把默认值从官方源优先改成镜像优先：国内用官方源基本是等超时，
 * 一个原版装下来大部分时间花在等 30 秒的连接超时上，观感就是「下载很慢」。
 * 镜像内容坏掉有 sha1 校验兜底，判失败会自己退回官方源，所以先走快的那条不亏。
 */
enum class DownloadSource(val id: String, val label: String, val note: String) {
    /** 官方源先试，失败再换镜像。 */
    AUTO(
        "auto",
        "官方源优先",
        "先试官方源，失败才换镜像。国内网络下通常要等到超时才换过去，慢一些但一定拿到官方原件。",
    ),

    /** 镜像先试，失败再退回官方源。默认值。 */
    MIRROR(
        "mirror",
        "优先国内镜像",
        "先试 BMCLAPI 等国内镜像，失败才回官方源。国内网络下明显快，镜像内容坏掉时靠 sha1 校验兜住。",
    ),
    ;

    companion object {
        /** 默认值：镜像优先。理由见这个枚举的 KDoc 与 MIRROR 那条。 */
        val DEFAULT: DownloadSource = MIRROR

        /** 从设置文件里的字符串认出偏好，认不出来退回默认值。 */
        fun of(raw: String): DownloadSource {
            val cleaned = raw.trim().lowercase()
            return entries.firstOrNull { it.id == cleaned } ?: DEFAULT
        }
    }
}

/**
 * 进程级的下载源偏好。
 *
 * 做成全局可变量而不是一路透传：下载偏好不是「这一次下载的参数」，而是「这台机器上
 * 所有下载的默认行为」，用户改一次就该对之后每一次安装生效。把它从界面传到下载器要
 * 穿过十几层调用（安装器、模组安装、整合包导入、Java 下载、服务端安装……），
 * 每一层都加一个参数，漏传一处就是「设置了没用」。
 *
 * 写入时机只有两处：启动时从设置文件读一次、用户在设置页改一次。读它的是每次
 * 新建的 [HttpDownloader]，@Volatile 保证换值之后新起的下载立刻看到新值。
 */
object DownloadPreference {
    @Volatile
    var source: DownloadSource = DownloadSource.DEFAULT
}

/**
 * 按偏好给候选地址排序。
 *
 * 纯函数，不联网不读设置，偏好由调用方显式传入 —— 这样单测可以直接断言顺序。
 */
object DownloadSources {

    /**
     * 哪些主机算「国内镜像」。
     *
     * 认的是主机名而不是完整前缀：同一个镜像有 http/https、有没有末尾斜杠几种写法，
     * 按字符串前缀匹配会漏。
     */
    private val MIRROR_HOSTS = listOf(
        "bmclapi2.bangbang93.com",
        "gh-proxy.com",
        "ghfast.top",
    )

    /** 这个地址是不是国内镜像。 */
    fun isMirror(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (lower.isEmpty()) return false
        return MIRROR_HOSTS.any { host -> hostAt(lower, host) }
    }

    /**
     * [url] 里真的有 [host] 这个主机名吗。
     *
     * 两边都要卡：前面必须是 scheme 或斜杠的开头（否则 `evilbmclapi2.bangbang93.com` 会被认成镜像），
     * 后面必须是路径/端口/查询/片段的分界或结尾（否则 `bmclapi2.bangbang93.com.evil.cn` 才是真的
     * 域名，把文件交给它就是把文件交给别人）。
     */
    private fun hostAt(url: String, host: String): Boolean {
        var from = 0
        while (true) {
            val at = url.indexOf(host, from)
            if (at < 0) return false
            val before = url.substring(0, at)
            val headOk = before.isEmpty() || before.endsWith("://") || before.endsWith("/")
            val afterIndex = at + host.length
            val tailOk = afterIndex >= url.length || url[afterIndex] in TAIL_BREAK
            if (headOk && tailOk) return true
            from = at + 1
        }
    }

    /** 主机名结束后允许紧跟的字符：路径、端口、查询、片段。 */
    private const val TAIL_BREAK = "/:?#"

    /**
     * 排一次下载条目的候选地址：主地址 + 备选镜像，按偏好重排后去重。
     *
     * [url] 为空时只留 [mirrors]（有些条目只有镜像，没有正主）。
     */
    fun order(url: String, mirrors: List<String>, source: DownloadSource): List<String> {
        val candidates = buildList {
            if (url.isNotBlank()) add(url)
            addAll(mirrors.filter { it.isNotBlank() && it != url })
        }.distinct()
        return when (source) {
            DownloadSource.AUTO -> candidates
            // 稳定分区：镜像先行，非镜像保持原来的相对顺序。
            DownloadSource.MIRROR -> candidates.sortedByDescending { isMirror(it) }
        }
    }

    /**
     * 排一串文本接口地址（版本清单、安装器清单、构建列表之类）。
     *
     * 与 [order] 分开是因为这些地址没有「主地址 + mirrors」的结构：调用方直接把全部候选
     * 摊平传进来，顺序写死在代码里（例如 `listOf(MANIFEST_URL, MANIFEST_MIRROR_URL)`）。
     */
    fun orderAll(urls: List<String>, source: DownloadSource): List<String> {
        val cleaned = urls.filter { it.isNotBlank() }.distinct()
        return when (source) {
            DownloadSource.AUTO -> cleaned
            DownloadSource.MIRROR -> cleaned.sortedByDescending { isMirror(it) }
        }
    }
}