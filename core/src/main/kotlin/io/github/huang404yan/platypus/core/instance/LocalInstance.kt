package io.github.huang404yan.platypus.core.instance

import io.github.huang404yan.platypus.core.launch.VersionResolver
import java.io.File

/**
 * 把「目录里已经有的一个版本」直接认养成实例，不联网、不下载。
 *
 * 场景：用户别的启动器已经把这版下好了，只是想换鸭嘴兽来管。这时再走一遍
 * 「下载 → 补 Java → 登记」既浪费时间又可能覆盖掉用户自己的文件，所以这里只做两件事：
 * 判断这一版到底齐不齐（json 能解析、client jar 在且非空），齐了就直接产出一个可登记的
 * [Instance]；不齐就返回 null，让调用方退回真正的安装流程。
 *
 * 抽成 core 里的纯函数而不是写在 Composable 里，是为了这条分支能被单测覆盖 ——
 * 「本地已有就别再下载」这个判断错了，用户会白等几分钟或者被覆盖文件。
 */
object LocalInstance {

    /**
     * @param gameRoot 游戏目录（.minecraft 那一层）
     * @param versionId 版本目录名，也就是 versions/<id>
     * @param name 展示名；空白时用解析出来的版本 id
     * @param memoryMb 会被夹到 [Instance.MIN_MEMORY_MB]–[Instance.MAX_MEMORY_MB]
     * @param javaPath 调用方探测到的 Java；没有就留空，启动页还能补
     * @return 本地版本可用时返回可直接 save 的实例，否则 null
     */
    fun adopt(
        gameRoot: File,
        versionId: String,
        name: String? = null,
        memoryMb: Int = Instance.DEFAULT_MEMORY_MB,
        javaPath: String? = null,
    ): Instance? {
        val id = versionId.trim()
        if (id.isEmpty()) return null

        val resolved = runCatching { VersionResolver.load(gameRoot, id) }.getOrNull() ?: return null
        // 跨模块的 public val 不能智能转换，先取到本地变量再判空。
        val clientJar = resolved.clientJar
        if (clientJar == null || !clientJar.isFile || clientJar.length() <= 0L) return null

        val realId = resolved.id.ifBlank { id }
        val (loader, loaderVersion) = InstanceScanner.detectLoader(realId)
        val displayName = name?.trim()?.takeIf { it.isNotEmpty() } ?: realId
        // 加载器版本的 id 形如 1.20.1-forge-47.2.0，instance.gameVersion 要的是底层原版号。
        val gameVersion = InstanceScanner.gameVersionFromId(realId) ?: realId

        return Instance(
            id = realId,
            name = displayName,
            gameVersion = gameVersion,
            loader = loader,
            loaderVersion = loaderVersion,
            directory = VersionResolver.versionDir(gameRoot, realId).absolutePath,
            javaPath = javaPath?.takeIf { it.isNotBlank() },
            memoryMb = Instance.clampMemoryMb(memoryMb),
            registered = true,
        )
    }
}
