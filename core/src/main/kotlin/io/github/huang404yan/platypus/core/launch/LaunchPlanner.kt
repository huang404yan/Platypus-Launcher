package io.github.huang404yan.platypus.core.launch

import io.github.huang404yan.platypus.core.Brand
import io.github.huang404yan.platypus.core.auth.Session
import io.github.huang404yan.platypus.core.instance.InstanceLayout
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.version.OsFamily
import io.github.huang404yan.platypus.core.version.VersionJson
import java.io.File

/**
 * 一次启动要准备的全部东西：解析好的版本、拼好的上下文、拼好的命令行、要解压的 natives。
 *
 * 这个类存在的理由只有一条：**「怎么把一次启动翻译成命令行」这件事与平台无关**。
 * 桌面端翻译完交给 `ProcessBuilder`，安卓以后翻译完交给 JNI 里的 `JVM_CreateJavaVM`，
 * 两边前面的步骤一模一样（读 json、合继承链、挑当前平台的库、拼参数）。
 * 所以把它放 core，而不是留在桌面那个实现里复制一份。
 */
data class LaunchPlan(
    val layout: InstanceLayout,
    val resolved: ResolvedVersion,
    val context: LaunchContext,
    val command: LaunchCommand,
    /** 老版本要解压的 natives jar；1.20.1 这类新版本是空列表。 */
    val nativeArchives: List<NativeArchive> = emptyList(),
    /** 解析过程中值得让用户看见的话（缺父版本、直连降级之类）。 */
    val notes: List<String> = emptyList(),
) {
    /** 客户端 jar：classpath 里那一份，找不到时为 null。 */
    val clientJar: File? get() = resolved.clientJar
}

/**
 * 把 [LaunchRequest] 展开成 [LaunchPlan]。纯读盘、不联网、不起进程。
 *
 * 单独拆出来是为了能测：启动失败有一半原因出在「这一步算出来的东西不对」，
 * 而验算一张命令行完全不需要真起一个游戏。
 */
object LaunchPlanner {

    /**
     * @param request 要启动的实例、选好的 Java、登录会话
     * @param launcherName 写进 `--versionType` 之外的启动器标识
     * @param launcherVersion 同上
     * @param os 目标平台；桌面用 [OsFamily.current]，安卓以后传 LINUX（规则里安卓吃的是 Linux 分支）
     * @param arch 目标架构；同上
     */
    fun plan(
        request: LaunchRequest,
        launcherName: String = Brand.NAME_EN,
        launcherVersion: String = Brand.version,
        os: OsFamily = OsFamily.current(),
        arch: String = OsFamily.currentArch(),
    ): LaunchPlan {
        val layout = InstanceLayouts.of(request.instance)
        // 版本 json 永远在 <gameRoot>/versions/<id>/ 下；layout.gameDir 是实例自己的游戏目录
        // （隔离后等于版本目录），不能拿它当 gameRoot 传进来，否则会去找 <实例目录>/versions/<id>。
        val versionId = layout.versionDir.name
        val resolved = VersionResolver.load(layout.gameRoot, versionId)
            ?: throw IllegalStateException("版本文件不存在：" + VersionResolver.jsonFile(layout.gameRoot, versionId).path)
        val notes = mutableListOf<String>()
        notes += resolved.warnings

        val version = resolved.version
        val context = buildContext(request, layout, version, resolved.clientJar, launcherName, launcherVersion, os, arch, notes)
        // 降级要说一声：用户点的是「直接进」，命令行里却没有 quickPlay，不解释会以为没生效。
        if (LaunchArguments.directConnectMode(version, request.quickPlayServer) == DirectConnectMode.SERVER_ARG) {
            notes += degradeNote(version.id)
        }
        return LaunchPlan(
            layout = layout,
            resolved = resolved,
            context = context,
            command = LaunchArguments.build(version, context),
            nativeArchives = LaunchArguments.nativeArchives(version, context),
            notes = notes,
        )
    }

    /** 「这个版本没有 quickPlay，改用 --server/--port 直连」。两端用同一句话。 */
    fun degradeNote(versionId: String): String =
        "这个版本（" + versionId + "）的版本文件里没有 --quickPlayMultiplayer，改用 --server/--port 直连。"

    /**
     * 把实际要执行的命令行写到 `logs/platypus-launch-<版本>.txt`。
     *
     * 只读落盘，失败就返回 null —— 写不进去不该让启动失败。accessToken 一律替换成 ***：
     * 这份文件会被用户随手发到群里。
     */
    fun dumpCommandLine(
        gameDir: File,
        versionName: String,
        arguments: List<String>,
        session: Session,
    ): File? = runCatching {
        val dir = File(gameDir, "logs").apply { mkdirs() }
        val file = File(dir, "platypus-launch-" + versionName + ".txt")
        val token = session.accessToken
        val masked = if (token.isNotBlank()) arguments.map { it.replace(token, "***") } else arguments
        file.writeText(masked.joinToString(System.lineSeparator()) + System.lineSeparator(), Charsets.UTF_8)
        file
    }.getOrNull()

    private fun buildContext(
        request: LaunchRequest,
        layout: InstanceLayout,
        version: VersionJson,
        resolvedJar: File?,
        launcherName: String,
        launcherVersion: String,
        os: OsFamily,
        arch: String,
        notes: MutableList<String>,
    ): LaunchContext {
        // 「直接连接」：1.20+ 走版本 json 里的 quickPlay（要同时打开 feature 开关），
        // 1.19 及更早没有那条规则，由 LaunchArguments 改拼 --server/--port，这里不用区别对待。
        val address = request.quickPlayServer.trim()
        val direct = LaunchArguments.directConnectMode(version, address)
        val placeholder = LaunchContext(
            gameDir = layout.gameDir,
            assetsDir = layout.assetsDir,
            librariesDir = layout.librariesDir,
            nativesDir = layout.nativesDir,
            classpath = emptyList(),
            session = request.session,
            versionName = layout.versionDir.name,
            memoryMb = request.instance.memoryMb,
            features = if (direct == DirectConnectMode.QUICK_PLAY) LaunchArguments.quickPlayFeatures() else emptyMap(),
            quickPlayMultiplayer = address,
            os = os,
            arch = arch,
            launcherName = launcherName,
            launcherVersion = launcherVersion,
            extraJvmArgs = request.instance.extraJvmArgs + request.jvmArgs,
            extraGameArgs = request.gameArgs,
        )
        val classpath = buildList {
            addAll(LaunchArguments.classpathLibraries(version, placeholder))
            (resolvedJar ?: clientJar(version, layout))?.let { add(it) }
        }
        return placeholder.copy(classpath = classpath)
    }

    /**
     * 客户端 jar：先看 json 里的 downloads.client.path，再退回 versions/<id>/<id>.jar。
     *
     * Forge 的继承型 json 自己不写 client，需要用父版本的 id 去找 —— 这里按「继承链最底层的 id」兜底。
     */
    private fun clientJar(version: VersionJson, layout: InstanceLayout): File? {
        val declared = version.downloads?.client?.path
        if (!declared.isNullOrBlank()) {
            val file = File(layout.librariesDir, declared)
            if (file.isFile) return file
        }
        // 合并后 id 是子版本，client jar 却常常在父版本目录下，逐个试。
        val candidates = layout.gameRoot.resolve("versions").listFiles()
            ?.filter { it.isDirectory }
            ?.map { File(it, it.name + ".jar") }
            ?.filter { it.isFile }
            ?.sortedBy { it.path }
            .orEmpty()
        return candidates.firstOrNull { it.parentFile?.name == version.id }
            ?: candidates.firstOrNull()
    }
}
