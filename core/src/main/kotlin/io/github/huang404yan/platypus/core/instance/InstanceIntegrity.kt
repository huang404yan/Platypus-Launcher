package io.github.huang404yan.platypus.core.instance

import io.github.huang404yan.platypus.core.download.DownloadItem
import io.github.huang404yan.platypus.core.download.DownloadProgress
import io.github.huang404yan.platypus.core.download.DownloadReport
import io.github.huang404yan.platypus.core.download.HttpDownloader
import io.github.huang404yan.platypus.core.launch.VersionResolver
import io.github.huang404yan.platypus.core.version.DownloadPlanner
import io.github.huang404yan.platypus.core.version.parseAssetIndex
import java.io.File
import java.security.MessageDigest

/** 清单里一个文件的本地状态。 */
enum class IntegrityState {
    /** 在，而且对得上。 */
    OK,

    /** 清单里有、本地没有。 */
    MISSING,

    /** 在，但大小或 sha1 跟清单对不上（下载断了、被别的启动器改过、磁盘出错）。 */
    BROKEN,
}

/**
 * 清单里的一条与它在本机的样子。
 *
 * [item] 原样留着，是给「补齐」用的：交给 [HttpDownloader] 之后，它会自己按 sha1 判断要不要重下，
 * 所以这里不需要先把坏文件删掉。
 */
data class IntegrityFile(
    val item: DownloadItem,
    val state: IntegrityState,
    /** 本地实际字节数，缺失时为 0。 */
    val actualBytes: Long = 0L,
) {
    val target: File get() = File(item.target)
}

/**
 * 一次完整性检查的结果。
 *
 * 「应当是哪些文件」全部来自版本 json（含 inheritsFrom 合并后的结果），不是扫一遍目录去猜：
 * 少了什么只有对照清单才知道，多出来的文件（自己塞的模组、日志）一律不管。
 */
data class IntegrityReport(
    val versionId: String = "",
    /** 版本 json 里声明的全部文件，按「client jar → 资源索引 → 库 → 资源对象」排列。 */
    val files: List<IntegrityFile> = emptyList(),
    /** 解析或检查过程中的问题（缺父版本、资源索引缺失之类），原样给界面显示。 */
    val warnings: List<String> = emptyList(),
    /** 这次检查有没有真的算过 sha1。false 表示只比了大小，坏文件可能没被发现。 */
    val hashesChecked: Boolean = false,
) {
    val totalFiles: Int get() = files.size
    val okFiles: Int get() = files.count { it.state == IntegrityState.OK }
    val missing: List<IntegrityFile> get() = files.filter { it.state == IntegrityState.MISSING }
    val broken: List<IntegrityFile> get() = files.filter { it.state == IntegrityState.BROKEN }

    /** 需要补的那些文件本身（缺的在前，坏的在后）。 */
    val problems: List<IntegrityFile> get() = missing + broken

    /** 需要补的文件数。 */
    val problemFiles: Int get() = problems.size

    /** 要补多少字节（缺失的按清单体量算，损坏的整份重下）。 */
    val missingBytes: Long get() = (missing + broken).sumOf { if (it.item.size > 0) it.item.size else 0L }

    val healthy: Boolean get() = problemFiles == 0

    /** 一句话总结，界面直接显示，不用自己拼。 */
    val summary: String
        get() = when {
            totalFiles == 0 -> "没有可对照的清单"
            healthy -> "对照清单的 " + totalFiles + " 个文件都在"
            else -> "对照清单的 " + totalFiles + " 个文件里，" + okFiles + " 个没问题，" +
                missing.size + " 个缺了，" + broken.size + " 个坏了"
        }
}

/**
 * 按版本清单检查一个实例的文件完整性，并把缺的补回来。
 *
 * 为什么需要它：启动失败最常见的原因不是 Java 不对，而是 client jar 没下完、libraries 少一个、
 * assets/objects 里某个文件是 0 字节。那些情况启动器只会抛一句「缺库」或者干脆闪退，
 * 用户既不知道是哪一层出的问题，也没有「重下一遍」以外的办法——而重下一遍要几十分钟。
 * 这里做的是「只补对不上的那几个文件」。
 *
 * 清单来源是 [DownloadPlanner]（纯计算）：先 [VersionResolver.load] 把 inheritsFrom 链合并成一个
 * 版本，再让 planner 生成应有的文件表。所以加载器版本（Forge/NeoForge/Fabric）也能校验——
 * 它们的库一部分来自父版本，合并之后自然都在表里。
 *
 * 不联网的部分（[inspect]）与联网的部分（[repair]）分开放，单测只测前者。
 */
object InstanceIntegrity {

    /**
     * 检查一个实例，全程只读。
     *
     * @param verifyHashes true 时逐个算 sha1（慢，几 GB 的 assets 要几十秒），
     *   false 时只比大小（快，但同样大小的坏文件发现不了）——界面给两个按钮，
     *   想快就用后者，准备启动前再用前者。
     * @param onProgress 已检查完的文件数 / 总数，给界面显示「查了 1200/3000」。
     */
    fun inspect(
        instance: Instance,
        verifyHashes: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): IntegrityReport {
        val versionDir = File(instance.directory.trim().trim('"')).absoluteFile
        val gameRoot = InstanceLayouts.gameRootOf(versionDir)
        val versionId = versionDir.name

        val resolved = runCatching { VersionResolver.load(gameRoot, versionId) }.getOrNull()
            ?: return IntegrityReport(
                versionId = versionId,
                warnings = listOf(
                    "找不到版本 json：versions/" + versionId + "/" + versionId + ".json 不存在或读不了，" +
                        "没有清单就没法对照。",
                ),
            )

        val warnings = resolved.warnings.toMutableList()
        val items = ArrayList<DownloadItem>()

        // ① client jar。resolved.clientJar 会沿继承链往上找（Forge 的版本目录里常常没有自己的 jar，
        //    用的是父版本那个），找到了就校验那个真实文件；找不到才按「这个版本该有」生成一条缺失。
        DownloadPlanner.clientJar(resolved.version, gameRoot)?.let { base ->
            val existing = resolved.clientJar
            items += if (existing != null) base.copy(target = existing.path) else base
        }

        // ② 资源索引与资源对象。索引本身也是清单里的一项；它不在就没法列出 objects 里的文件。
        val indexItem = DownloadPlanner.assetIndex(resolved.version, gameRoot)
        val indexFile = indexItem?.let { File(it.target) }
        if (indexItem != null) items += indexItem
        if (indexFile != null && indexFile.isFile) {
            val index = runCatching { parseAssetIndex(indexFile.readText()) }.getOrNull()
            if (index == null) {
                warnings += "资源索引读不了：" + indexFile.absolutePath + "（内容不是合法的 asset index）"
            } else {
                items += DownloadPlanner.assets(index, gameRoot)
            }
        } else if (indexItem != null) {
            warnings += "资源索引缺失，这一次没法检查 assets/objects 里的资源文件；补上索引后再查一次就能一并校验。"
        }

        // ③ 库文件。rules 按当前平台筛过了，别的系统的库不会算进来（否则永远显示缺一堆）。
        items += DownloadPlanner.libraries(resolved.version, gameRoot)

        val total = items.size
        if (total == 0) {
            return IntegrityReport(
                versionId = resolved.id.ifBlank { versionId },
                warnings = warnings + "版本 json 里没有任何文件条目，没有什么可校验的。",
                hashesChecked = verifyHashes,
            )
        }

        val files = ArrayList<IntegrityFile>(total)
        var done = 0
        for (item in items) {
            files += checkOne(item, verifyHashes)
            done++
            if (done == total || done % 64 == 0) onProgress(done, total)
        }
        return IntegrityReport(
            versionId = resolved.id.ifBlank { versionId },
            files = files,
            warnings = warnings,
            hashesChecked = verifyHashes,
        )
    }

    /**
     * 把报告里对不上的文件补回来。
     *
     * 直接复用 [HttpDownloader]：它按 sha1 判断目标要不要重下，也就是说坏文件会被整份替换，
     * 不缺的文件跳过一次网络请求。断点续传、镜像回退、单文件分段都在它里面，这里不重复实现。
     */
    suspend fun repair(
        report: IntegrityReport,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadReport {
        val items = report.files.filter { it.state != IntegrityState.OK }.map { it.item }
        if (items.isEmpty()) return DownloadReport(succeeded = 0, failures = emptyList())
        return HttpDownloader().download(items, onProgress)
    }

    /** 判断单个文件。大小先用（省一次读盘），对得上才去算 sha1。 */
    private fun checkOne(item: DownloadItem, verifyHashes: Boolean): IntegrityFile {
        val file = File(item.target)
        if (!file.isFile) return IntegrityFile(item, IntegrityState.MISSING)
        val length = file.length()
        if (item.size >= 0 && length != item.size) {
            return IntegrityFile(item, IntegrityState.BROKEN, actualBytes = length)
        }
        val expected = item.sha1
        if (verifyHashes && !expected.isNullOrBlank()) {
            val actual = runCatching { sha1Of(file) }.getOrNull()
            if (actual == null || !actual.equals(expected, ignoreCase = true)) {
                return IntegrityFile(item, IntegrityState.BROKEN, actualBytes = length)
            }
        }
        return IntegrityFile(item, IntegrityState.OK, actualBytes = length)
    }

    private fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
