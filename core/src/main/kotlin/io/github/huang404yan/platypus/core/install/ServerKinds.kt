package io.github.huang404yan.platypus.core.install

import io.github.huang404yan.platypus.core.loader.FabricMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 服务端的加载器。
 *
 * 和客户端的 io.github.huang404yan.platypus.core.instance.LoaderKind 分开列：服务端有 Paper
 * 这种客户端根本没有的东西，客户端的 QUILT / OPTIFINE 也不可能出现在服务端目录里。
 * 硬塞进同一个枚举，每个 when 都要为不存在的组合写分支。
 */
enum class ServerKind(val label: String) {
    /** Mojang 版本 json 里的 downloads.server。 */
    VANILLA("原版"),

    /** PaperMC 的 paperclip jar（fill.papermc.io）。 */
    PAPER("Paper"),

    /** Fabric 的 fabric-server-launch jar（meta.fabricmc.net 现成的，不用跑安装器）。 */
    FABRIC("Fabric"),

    /** Forge installer --installServer。 */
    FORGE("Forge"),

    /** NeoForge installer --installServer。 */
    NEOFORGE("NeoForge"),
    ;

    /** 服务端安装要不要跑安装器。这类安装必须有一个能用的 Java，不然装到一半才发现。 */
    val needsInstaller: Boolean
        get() = this == FORGE || this == NEOFORGE
}

/**
 * 一次服务端安装要装什么。
 *
 * [loaderVersion] 对不同加载器含义不同：Paper 是构建号（196）、Fabric 是 loader 版本、
 * Forge / NeoForge 是加载器版本。留空则由安装器自己挑推荐版。
 */
data class ServerLoader(
    val kind: ServerKind = ServerKind.VANILLA,
    val loaderVersion: String? = null,
) {
    companion object {
        val Vanilla: ServerLoader = ServerLoader(ServerKind.VANILLA)
    }
}

/** Paper 的一个构建。 */
data class PaperBuild(
    val id: Int,
    val channel: String,
    val fileName: String,
    val url: String,
    /** Paper 只发 sha256，没有 sha1 —— 下载后按 sha256 自己校验。 */
    val sha256: String,
    val size: Long,
) {
    /** 给人看的：构建 196（STABLE）。 */
    val label: String get() = "构建 " + id + "（" + channel + "）"
}

/**
 * PaperMC 的下载接口。
 *
 * 注意这里的域名是 fill.papermc.io：老文档里的 api.papermc.io/v2 已经整站 410 Gone，
 * api.papermc.io/v3 返回 403 Endpoint Unsupported。2026-11 实测只有 fill 这套能用。
 */
object PaperMeta {

    const val BASE_URL: String = "https://fill.papermc.io/v3"

    const val PROJECT: String = "paper"

    /** GET 这个地址拿到某个游戏版本的所有构建（新的在前）。 */
    fun buildsUrl(gameVersion: String): String =
        BASE_URL + "/projects/" + PROJECT + "/versions/" + gameVersion.trim() + "/builds"

    /**
     * 解析构建列表。
     *
     * 每一项长这样（只留我们用的字段）：
     * {"id":196,"channel":"STABLE","downloads":{
     *    "server:default":{"name":"paper-1.20.1-196.jar",
     *       "checksums":{"sha256":"234a..."},"size":43230582,"url":"https://fill-data..."}}}
     *
     * 只认 server:default：server:mojang 是喂给别的工具的原始产物，不是能直接开的 jar。
     */
    fun parseBuilds(text: String): List<PaperBuild> {
        if (text.isBlank()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        val array = root as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            val downloads = (obj["downloads"] as? JsonObject) ?: return@mapNotNull null
            val server = (downloads["server:default"] as? JsonObject) ?: return@mapNotNull null
            val url = (server["url"] as? JsonPrimitive)?.content.orEmpty()
            if (url.isBlank()) return@mapNotNull null
            val name = (server["name"] as? JsonPrimitive)?.content.orEmpty()
            val sha256 = ((server["checksums"] as? JsonObject)
                ?.get("sha256") as? JsonPrimitive)?.content.orEmpty()
            val size = (server["size"] as? JsonPrimitive)?.longOrNull ?: -1L
            PaperBuild(
                id = id,
                channel = (obj["channel"] as? JsonPrimitive)?.content.orEmpty().ifBlank { "STABLE" },
                fileName = name.ifBlank { "paper-" + id + ".jar" },
                url = url,
                sha256 = sha256,
                size = size,
            )
        }
    }

    /** 最新的一个构建；列表是新的在前，但这里按 id 兜底，免得哪天顺序变了装到旧版。 */
    fun latest(builds: List<PaperBuild>): PaperBuild? = builds.maxByOrNull { it.id }
}

/**
 * Fabric 的服务端。
 *
 * Fabric 不用跑安装器：meta 直接给一个可启动的 server jar（内部就是 launcher + 依赖）。
 * 缺点是这个地址必须带 installer 版本，少一段就是 404：
 * /v2/versions/loader/<游戏>/<loader>/<installer>/server/jar
 */
object FabricServer {

    /** GET 这个地址拿到 installer 版本列表，第一项通常就是最新稳定版。 */
    fun installerListUrl(): String = FabricMeta.BASE_URL + "/v2/versions/installer"

    /** 解析 installer 版本列表，取最新稳定版。解析不出来返回 null（让调用方报「元数据形状变了」）。 */
    fun parseInstallerVersion(text: String): String? {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        val array = root as? JsonArray ?: return null
        val items = array.mapNotNull { it as? JsonObject }
        val stable = items.firstOrNull { (it["stable"] as? JsonPrimitive)?.content == "true" }
        val picked = stable ?: items.firstOrNull() ?: return null
        return (picked["version"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    /** 可直接启动的 Fabric 服务端 jar。 */
    fun serverJarUrl(gameVersion: String, loaderVersion: String, installerVersion: String): String =
        FabricMeta.BASE_URL + "/v2/versions/loader/" + gameVersion.trim() + "/" +
            loaderVersion.trim() + "/" + installerVersion.trim() + "/server/jar"

    /** 缓存用的文件名：换 loader 版本不会互相覆盖。 */
    fun serverJarName(gameVersion: String, loaderVersion: String): String =
        "fabric-server-" + gameVersion.trim() + "-" + loaderVersion.trim() + ".jar"
}
