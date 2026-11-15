package io.github.huang404yan.platypus.core.mods

import io.github.huang404yan.platypus.core.modpack.ModpackExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 拿本地文件的 sha1 去问 Modrinth「这个文件是不是公开版本」，问到就把下载地址带回来。
 *
 * 导出整合包用它省体积：认出来的只写地址，认不出来的再打进包里。
 * 这是唯一一处「导出」与「Modrinth」耦合的地方，所以单独一个对象 ——
 * 导出本身（[ModpackExport]）只认一个函数接口，单测里换成假的就能不联网跑。
 *
 * 一条都没命中是正常结果（本地改过的 jar、自己编译的、非 Modrinth 来源），返回空表；
 * 网络或接口出错则抛 IOException，让调用方能分清「Modrinth 不认识这些文件」与
 * 「根本没连上」——后者不该一声不响地退化成「全部打包」。
 */
object ModrinthHostedLookup {

    /** 一次问多少个：与检查更新共用同一个批次大小（100）。 */
    suspend fun lookup(sha1s: List<String>): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val clean = sha1s.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return@withContext emptyMap()
        val out = mutableMapOf<String, List<String>>()
        for (batch in ModUpdateChecker.batches(clean)) {
            val body = ModInstaller.postJson(
                ModrinthMeta.versionFilesUrl(),
                ModUpdateChecker.versionFilesBody(batch),
            )
            out += ModrinthMeta.parseVersionFileUrls(body)
        }
        out
    }

    /** 给导出层用的适配器。 */
    fun asHostedLookup(): ModpackExport.HostedLookup = ModpackExport.HostedLookup { hashes ->
        runCatching { lookup(hashes) }.getOrElse { error ->
            throw IOException(error.message ?: "连不上 Modrinth", error)
        }
    }
}
