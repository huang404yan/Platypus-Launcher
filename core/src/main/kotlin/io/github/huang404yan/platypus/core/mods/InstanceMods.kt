package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import java.io.File
import java.io.IOException

/**
 * 一个实例的 mods/ 现状，以及「隔离之前」还留在游戏根目录下的那批模组。
 *
 * P1 之前 `--gameDir` 指向游戏根目录，所以 mods/ 只有一份、所有实例共用。
 * 隔离之后每个实例只看得到 `<gameRoot>/versions/<id>/mods`，游戏根目录下那份
 * 老 mods/ 就变成了「谁都看不见」的孤儿目录 —— 不主动提示，用户会以为模组丢了。
 *
 * @param instanceDir 实例自己的目录（`<gameRoot>/versions/<id>`）
 * @param modsDir 这个实例的 mods/ 目录，等于 `instanceDir/mods`
 * @param scan 实例 mods/ 的扫描结果
 * @param legacyModsDir 隔离前的共享 mods/（`<gameRoot>/mods`）
 * @param legacyScan 共享 mods/ 的扫描结果；与 [modsDir] 是同一个目录时为 null
 */
data class InstanceModsSnapshot(
    val instanceDir: File,
    val modsDir: File,
    val scan: ModScanResult,
    val legacyModsDir: File,
    val legacyScan: ModScanResult? = null,
) {
    /** 游戏根目录下还有隔离前留下的模组，值得提示一句。 */
    val hasLegacy: Boolean get() = (legacyScan?.totalCount ?: 0) > 0

    val legacyCount: Int get() = legacyScan?.totalCount ?: 0
}

/**
 * 把游戏根目录下那份老 mods/ 搬进某个实例的结果。
 *
 * [copied] 与 [skipped] 都是文件名；[failed] 是「文件名 → 原因」。
 * 走复制而不是移动：老目录里可能还有别的实例要的东西，搬走一个实例就等于替别人做了决定。
 */
data class LegacyAdoptResult(
    val targetDir: File,
    val copied: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failed: Map<String, String> = emptyMap(),
) {
    val total: Int get() = copied.size + skipped.size + failed.size
    val ok: Boolean get() = failed.isEmpty()
}

/**
 * 实例目录 ↔ mods/ 的映射，以及把共享 mods/ 搬进实例。
 *
 * 为什么不把这些塞进 [ModScanner]：ModScanner 的职责只是「给我一个目录，我告诉你里面有什么」，
 * 它不该知道实例这个概念。谁来当那个目录，是这一层的事。
 */
object InstanceMods {

    /** 实例自己的 mods/ 目录：`<instance.directory>/mods`。 */
    fun modsDirOf(instance: Instance): File = modsDirOf(File(instance.directory.trim().trim('"')))

    fun modsDirOf(instanceDir: File): File = ModScanner.modsDir(instanceDir)

    /**
     * 隔离前所有实例共用的那个 mods/：游戏根目录下的 `mods`。
     *
     * 实例目录本身不是 `versions/<id>` 形状时，[InstanceLayouts.gameRootOf] 会退回实例目录自身，
     * 这时「共享目录」就是实例自己的目录，调用方要按 [InstanceModsSnapshot.legacyScan] 为 null 处理。
     */
    fun legacyModsDir(instance: Instance): File {
        val instanceDir = File(instance.directory.trim().trim('"'))
        return ModScanner.modsDir(InstanceLayouts.gameRootOf(instanceDir))
    }

    /** 扫一个实例的 mods/，顺带看看共享 mods/ 里还剩什么。只读，不建目录。 */
    fun scan(instance: Instance): InstanceModsSnapshot {
        val instanceDir = File(instance.directory.trim().trim('"')).absoluteFile
        val modsDir = modsDirOf(instanceDir)
        val scan = ModScanner.scan(instanceDir)
        val legacyDir = legacyModsDir(instance)
        // 非标准布局下 gameRoot 退回实例目录，此时 legacyDir 与 modsDir 是同一个目录，
        // 再扫一遍只会得到同一份清单，还容易让界面提示「有 N 个旧模组要搬」而搬到自己身上。
        val legacy = if (sameDir(legacyDir, modsDir)) null else ModScanner.scan(legacyDir.parentFile ?: legacyDir)
        return InstanceModsSnapshot(
            instanceDir = instanceDir,
            modsDir = modsDir,
            scan = scan,
            legacyModsDir = legacyDir,
            legacyScan = legacy,
        )
    }

    /**
     * 把共享 mods/ 里的模组复制进实例的 mods/。
     *
     * 默认不覆盖同名文件（[overwrite] = false）：同名但内容不同时记进 [LegacyAdoptResult.skipped]，
     * 由用户自己决定留哪个 —— 悄悄覆盖会让人查不出「为什么我的模组版本变了」。
     */
    fun adoptLegacy(instance: Instance, overwrite: Boolean = false): LegacyAdoptResult {
        val snapshot = scan(instance)
        val legacy = snapshot.legacyScan
            ?: throw IOException("这个实例的 mods/ 与共享 mods/ 是同一个目录，不需要搬。")
        if (!legacy.exists) throw IOException("共享目录里没有 mods/：" + legacy.dir.absolutePath)
        val target = snapshot.modsDir
        if (!target.isDirectory && !target.mkdirs()) {
            throw IOException("建不了实例的 mods 目录：" + target.absolutePath)
        }
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()
        for (entry in legacy.mods + legacy.disabled) {
            val source = File(entry.path)
            val dest = File(target, source.name)
            if (dest.exists() && !overwrite) {
                skipped += source.name
                continue
            }
            try {
                source.copyTo(dest, overwrite = true)
                copied += source.name
            } catch (e: Exception) {
                failed[source.name] = e.message ?: e::class.simpleName ?: "复制失败"
            }
        }
        return LegacyAdoptResult(targetDir = target, copied = copied, skipped = skipped, failed = failed)
    }

    private fun sameDir(a: File, b: File): Boolean =
        runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(a.absolutePath == b.absolutePath)
}
