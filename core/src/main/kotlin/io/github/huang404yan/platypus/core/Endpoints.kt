package io.github.huang404yan.platypus.core

/**
 * 全项目对外的服务地址，集中在这一处。
 *
 * ## 为什么要集中
 *
 * 以前这些地址散在二十多个文件里各写各的。做「空壳发布包」时没法一处关掉：改一处漏一处，
 * 漏掉的那处就等于把地址留在包里了。集中之后，shell 一开，所有取值器都从 serverBase 拼地址，
 * 没填就是空串。
 *
 * ## 两种包
 *
 * - 完整版（BuildInfo.SHELL 为 false）：本机调试、自用构建、正式版都走这条。每个取值器都
 *   返回源码里写死的官方地址与国内镜像，行为与以前完全一致。
 * - 空壳版（BuildInfo.SHELL 为 true）：只给对外发布的开源版用。包里不含任何可用地址，
 *   界面布局照旧；用户在「设置 → 下载」填一个镜像根后，所有地址都从它拼。
 *
 * ## 用户填的镜像根怎么理解
 *
 * 约定：这个根替代所有官方主机，路径保持原样。填 https://mirror.example.com，版本清单就取
 * https://mirror.example.com/mc/game/version_manifest_v2.json，资源文件取
 * https://mirror.example.com/<前两位>/<sha1>（BMCLAPI 的布局）。也就是说填的人要自备一个
 * 能代理这些上游站、且路径照抄 BMCLAPI 的镜像。这套约定在设置页那个输入框下面写明了。
 *
 * 微软登录的端点不在这里（见 auth/MicrosoftAuth.kt）：那是身份链，任何版本都走官方地址，
 * 也不允许用户改。
 */
object Endpoints {

    // ── 完整版的真实地址 ──────────────────────────────────────────────────────
    // 全部 private：外部只能拿到下面那些取值器，拿到的已经是「当前这个包该用的」地址。

    private const val REAL_PISTON_META = "https://piston-meta.mojang.com"
    private const val REAL_PISTON_DATA = "https://piston-data.mojang.com"
    private const val REAL_LAUNCHER = "https://launcher.mojang.com"
    private const val REAL_LAUNCHER_META = "https://launchermeta.mojang.com"
    private const val REAL_RESOURCES = "https://resources.download.minecraft.net"
    private const val REAL_MOJANG_LIBRARIES = "https://libraries.minecraft.net"
    private const val REAL_BMCLAPI = "https://bmclapi2.bangbang93.com"
    private const val REAL_FABRIC_META = "https://meta.fabricmc.net"
    private const val REAL_FABRIC_MAVEN = "https://maven.fabricmc.net"
    private const val REAL_QUILT_META = "https://meta.quiltmc.org"
    private const val REAL_QUILT_MAVEN = "https://maven.quiltmc.org"
    private const val REAL_FORGE_MAVEN = "https://maven.minecraftforge.net"
    private const val REAL_FORGE_FILES = "https://files.minecraftforge.net"
    private const val REAL_NEOFORGE_MAVEN = "https://maven.neoforged.net"
    private const val REAL_MODRINTH = "https://api.modrinth.com"
    private const val REAL_CURSEFORGE = "https://api.curseforge.com"
    private const val REAL_AUTHLIB = "https://authlib-injector.yushi.moe"
    private const val REAL_PAPER = "https://fill.papermc.io"
    private const val REAL_GITHUB_API = "https://api.github.com"

    // ── 空壳版 ────────────────────────────────────────────────────────────────

    /** 这个包是不是空壳版。编译期定死，运行期改不了。 */
    val shell: Boolean get() = BuildInfo.SHELL

    /** 用户在设置里填的镜像根，已归一化（无末尾斜杠），没填是空串。只在空壳版有意义。 */
    @Volatile
    var serverBase: String = ""
        private set

    /**
     * 更新用户填的镜像根。界面改完设置调一次，启动时从设置文件读一次。
     *
     * 只认 http/https 且带主机名的完整 URL，其余一律当没填。用户手输的字段不该抛异常，
     * 返回空串就是「填的不算数」，报错由界面自己说。
     */
    fun setServerBase(raw: String): String {
        val cleaned = normalizeBase(raw)
        serverBase = cleaned
        return cleaned
    }

    /** 归一化一个根地址：只认 http/https 且带主机名，去掉末尾斜杠，认不出返回空串。 */
    fun normalizeBase(raw: String): String {
        val trimmed = raw.trim().trim('"').trim()
        val host = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
            else -> return ""
        }
        // 光有 scheme 不算地址；只剩斜杠也不算。
        if (host.trimStart('/').isEmpty()) return ""
        return trimmed.trimEnd('/')
    }

    /**
     * 空壳版没填地址时，这一句给用户看。界面直接拿去显示，不要各写一份。
     */
    const val SHELL_HINT: String =
        "这个包不带下载与搜索服务。去「设置 → 下载」填一个镜像地址，填完这里就有内容了。"

    /**
     * 空壳版里替换主机的名单：命中前缀就把主机换成镜像根，路径原样保留。
     *
     * 顺序要紧：长的放前面，launchermeta 不能先被 launcher 吃掉。
     */
    private val REWRITE_HOSTS = listOf(
        "https://launchermeta.mojang.com",
        "https://piston-meta.mojang.com",
        "https://piston-data.mojang.com",
        "https://launcher.mojang.com",
        "https://resources.download.minecraft.net",
        "https://libraries.minecraft.net",
        "https://files.minecraftforge.net",
        "https://maven.minecraftforge.net",
        "https://maven.neoforged.net",
        "https://maven.fabricmc.net",
        "https://maven.quiltmc.org",
        "https://meta.fabricmc.net",
        "https://meta.quiltmc.org",
        "https://api.modrinth.com",
        "https://api.curseforge.com",
        "https://fill.papermc.io",
        "https://authlib-injector.yushi.moe",
        "https://api.github.com",
    )

    /**
     * 这个地址在当前包里该用什么。
     *
     * 完整版原样返回。空壳版把官方主机换成镜像根、路径保留；认不出的主机原样返回
     * （外置登录的 yggdrasil 地址、用户自己的整合包直链都不该被我们改）。
     * 空壳版没填镜像根时返回空串。
     */
    fun use(url: String): String {
        if (!shell) return url
        return rewriteHost(url, serverBase)
    }

    /**
     * [use] 的替换规则本身。
     *
     * 单独抽出来是因为本机构建恒为完整版（[shell] 为 false），不抽的话这段逻辑
     * 在单测里一行都跑不到 —— 而它恰恰是空壳包唯一的地址出口，必须有测试盯着。
     */
    internal fun rewriteHost(url: String, root: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        // 认不出的主机是用户自己的地址（外置登录、整合包直链），哪一版都不该动它 ——
        // 哪怕还没填镜像根，也不能把它清成空串，那等于把外置登录一并停掉。
        val host = REWRITE_HOSTS.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
            ?: return trimmed
        // 认得出是官方主机、但还没填镜像根：这个包现在没有服务可用。
        if (root.isEmpty()) return ""
        return root + trimmed.substring(host.length)
    }

    /**
     * 在某个根上拼一段路径，根来自上面那些取值器。
     *
     * 根为空（空壳版没填镜像根）时返回空串，调用方据此判断「这个包现在没有服务」。
     * 拼的时候容忍根有没有末尾斜杠——历史代码里两种写法都有。
     */
    fun join(base: String, path: String): String {
        val root = base.trim()
        if (root.isEmpty()) return ""
        return root.trimEnd('/') + "/" + path.trimStart('/')
    }

    /**
     * 把根拼成「带末尾斜杠」的形式，给那些用 base + 相对路径直接相加的老代码用。
     *
     * 根为空（空壳版没填镜像根）时返回空串，拼出来的地址也就是空串。
     */
    fun dir(base: String): String {
        val root = base.trim()
        if (root.isEmpty()) return ""
        return root.trimEnd('/') + "/"
    }

    // ── 各服务的取值器 ────────────────────────────────────────────────────────

    /** Mojang 版本清单与元数据的根。 */
    fun pistonMeta(): String = use(REAL_PISTON_META)

    /** Mojang 的文件 CDN，版本 jar 与不少库的下载地址落在这里。 */
    fun pistonData(): String = use(REAL_PISTON_DATA)

    /** 老的启动器 CDN，少数老版本还在用。 */
    fun launcherCdn(): String = use(REAL_LAUNCHER)

    /** 启动器元数据（Java 运行时清单在这台主机上）。 */
    fun launcherMeta(): String = use(REAL_LAUNCHER_META)

    /** 资源文件（assets objects）的根。 */
    fun resources(): String = use(REAL_RESOURCES)

    /** Mojang 库（libraries）的根，不带末尾斜杠。 */
    fun mojangLibraries(): String = use(REAL_MOJANG_LIBRARIES)

    /** BMCLAPI 的根，不带末尾斜杠。 */
    fun bmclapi(): String = use(REAL_BMCLAPI)

    fun fabricMeta(): String = use(REAL_FABRIC_META)

    fun fabricMaven(): String = use(REAL_FABRIC_MAVEN)

    fun quiltMeta(): String = use(REAL_QUILT_META)

    fun quiltMaven(): String = use(REAL_QUILT_MAVEN)

    fun forgeMaven(): String = use(REAL_FORGE_MAVEN)

    fun forgeFiles(): String = use(REAL_FORGE_FILES)

    fun neoForgeMaven(): String = use(REAL_NEOFORGE_MAVEN)

    fun modrinth(): String = use(REAL_MODRINTH)

    fun curseForge(): String = use(REAL_CURSEFORGE)

    fun authlibInjector(): String = use(REAL_AUTHLIB)

    fun paper(): String = use(REAL_PAPER)

    fun gitHubApi(): String = use(REAL_GITHUB_API)

    /**
     * 这个包现在能不能对外发请求。
     *
     * 完整版一律 true。空壳版只有在用户填了镜像根时才 true —— 没填就说明这个包不带任何
     * 网络服务，界面据此显示 SHELL_HINT，而不是去发一个注定失败的请求。
     */
    fun serviceReady(): Boolean = !shell || serverBase.isNotEmpty()
}
