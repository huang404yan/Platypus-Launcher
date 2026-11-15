package io.github.huang404yan.platypus.core.modpack

import io.github.huang404yan.platypus.core.instance.Instance
import io.github.huang404yan.platypus.core.instance.InstanceLayouts
import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.mods.CurseForgeFingerprint
import io.github.huang404yan.platypus.core.mods.ModUpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出成整合包时能选的格式。 */
enum class ModpackExportFormat(val label: String, val extension: String) {
    /** MCBBS v2：文件实体全放 overrides/，元数据是 mcbbs.packmeta + manifest.json。 */
    MCBBS("MCBBS v2（.zip）", "zip"),

    /**
     * CurseForge：只写 manifest.json + overrides/。
     *
     * 与 [MCBBS] 的差别只有「少写一份 mcbbs.packmeta」——文件实体照样全部打进包里，
     * 体积与 MCBBS 完全一样。单独列出来是因为 CurseForge App 与一些第三方工具只认
     * 这种形状，把 mcbbs.packmeta 一起塞进去对它们只是多一个陌生文件。
     *
     * 顺序上排在 [MRPACK] 之前：界面直接遍历 entries 出选项，两种「文件全带」的 zip
     * 挨着放比隔一个更说得通。
     */
    CURSE("CurseForge（.zip）", "zip"),

    /** Modrinth：能认出来的文件只留下载地址，认不出的才打进 overrides/。 */
    MRPACK("Modrinth（.mrpack）", "mrpack"),
}

/** 导出时能勾的目录。顺序就是写进压缩包的顺序（只是确定性，不影响正确性）。 */
enum class ModpackExportCategory(val dirName: String, val label: String, val note: String) {
    MODS("mods", "模组", "mods/ 下的 jar 与配置文件"),
    CONFIG("config", "配置", "config/ 整个目录"),
    RESOURCE_PACKS("resourcepacks", "资源包", "resourcepacks/"),
    SHADER_PACKS("shaderpacks", "光影", "shaderpacks/"),
    SAVES("saves", "存档", "saves/（默认不勾：里面有别人的世界）"),
}

/**
 * 一次导出的参数。
 *
 * [categories] 默认不含存档：存档是用户自己的东西，默认打包进整合包再发出去是数据事故，
 * 想要必须自己勾（与 PCL 把存档单独列成一组选项是同一个理由）。
 */
data class ModpackExportOptions(
    val name: String = "",
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    val format: ModpackExportFormat = ModpackExportFormat.MCBBS,
    val categories: Set<ModpackExportCategory> = DEFAULT_CATEGORIES,
) {
    companion object {
        val DEFAULT_CATEGORIES: Set<ModpackExportCategory> = linkedSetOf(
            ModpackExportCategory.MODS,
            ModpackExportCategory.CONFIG,
            ModpackExportCategory.RESOURCE_PACKS,
            ModpackExportCategory.SHADER_PACKS,
        )
    }
}

/** 从实例目录里扫出来、准备打进包里的一个文件。 */
data class ModpackExportEntry(
    /** 相对实例目录的路径，斜杠分隔，例如 mods/sodium.jar。 */
    val path: String,
    val file: File,
    val bytes: Long,
) {
    /** 写进 zip 时套的那一层前缀。 */
    val overridePath: String get() = ModpackMeta.OVERRIDES_DIR + "/" + path
}

/**
 * 一个「Modrinth 认识、因此不必打进包里」的文件。
 *
 * sha512 由我们本地算，不信上游返回的：Modrinth 导入时会核对，值不对等于写出一个坏包。
 */
data class ModpackHostedEntry(
    val path: String,
    val sha1: String,
    val sha512: String,
    val size: Long,
    val urls: List<String>,
)

/**
 * 一个「CurseForge 认识、因此不必打进包里」的文件。
 *
 * 只有一对 ID，没有下载地址 —— CurseForge 的 manifest.json 里就只写这两个数，
 * 由导入方自己按 ID 去下。指纹留着是为了让日志能说清是哪个文件对上的号。
 */
data class ModpackCurseFile(
    val fingerprint: Long,
    val projectId: Int,
    val fileId: Int,
)

/** 导出进度。只给界面用，不参与判断。 */
data class ModpackExportProgress(
    val step: String,
    val done: Int = 0,
    val total: Int = 0,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/** 导出结果。 */
data class ModpackExportResult(
    val archive: File,
    /** 包内总共写了几个文件（含元数据）。 */
    val entries: Int,
    /** 只留下载地址、没打进包的文件数。 */
    val hosted: Int,
    /** 打进 overrides/ 的文件数。 */
    val packaged: Int,
    /** 写进压缩包的原始字节数（压缩前）。 */
    val bytes: Long,
    /** 只留 CurseForge 的 projectID/fileID、没打进包的文件数。 */
    val curseFiles: Int = 0,
    val warnings: List<String> = emptyList(),
)

/** 导出失败，message 是给人看的一句话。 */
class ModpackExportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 把一个实例导出成整合包。
 *
 * 两种格式的差别不是「压缩方式」而是「谁持有文件」：
 * - **MCBBS v2**：文件实体全部进 `overrides/`，元数据只说「要 1.20.1 + Forge 47.2.0」。
 *   导入方按 addons 现装游戏，再把 overrides 原样铺开 —— 因此**不联网也能导**。
 * - **Modrinth**：能认出是哪个公开文件时只写下载地址（[ModpackHostedEntry]），认不出的
 *   才进 overrides。好处是包体积极小、二次分发不碰别人的 jar；代价是必须联网核对。
 *
 * 这里刻意**不**把三种元数据混装：MRPACK 只写 modrinth.index.json，MCBBS 只写
 * mcbbs.packmeta + manifest.json。PCL 的实测注释写得很清楚 —— HMCL 见到
 * modrinth.index.json 就按 Modrinth 包解析，加载器版本对不上就整个导入失败，
 * 真正对的 mcbbs.packmeta 反而读不到。
 */
object ModpackExport {

    const val INDEX_NAME: String = ModpackMeta.MRPACK_INDEX
    const val PACKMETA_NAME: String = ModpackMeta.MCBBS_META
    const val MANIFEST_NAME: String = ModpackMeta.MCBBS_MANIFEST
    const val OVERRIDES_DIR: String = ModpackMeta.OVERRIDES_DIR

    /** 一个实例最多被打包多少个文件：再多就不是整合包，是整盘备份。 */
    const val MAX_FILES: Int = 20_000

    private val PRETTY = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    /** 查一个文件在 Modrinth 上是不是公开版本；返回 sha1 → 可用下载地址。 */
    fun interface HostedLookup {
        suspend fun lookup(sha1s: List<String>): Map<String, List<String>>
    }

    /**
     * 查一个文件在 CurseForge 上是哪个项目的哪个文件；返回指纹 → (projectID, fileID)。
     *
     * 与 [HostedLookup] 分开是因为语义不同：Modrinth 给的是下载地址，CurseForge 给的是两个
     * 整数 ID（manifest.json 的 files 里就只要这两个数）。合成一个接口会让两边都别扭。
     */
    fun interface CurseLookup {
        suspend fun lookup(fingerprints: List<Long>): Map<Long, Pair<Int, Int>>
    }

    /**
     * 扫出这个实例里要导出的文件。纯读盘、不联网，出问题一律返回空表而不是抛异常
     * （目录不存在是很正常的：没装过模组的实例就只有 config/）。
     */
    fun collect(gameDir: File, options: ModpackExportOptions): List<ModpackExportEntry> {
        if (!gameDir.isDirectory) return emptyList()
        val out = mutableListOf<ModpackExportEntry>()
        for (category in ModpackExportCategory.entries) {
            if (category !in options.categories) continue
            val root = File(gameDir, category.dirName)
            if (!root.isDirectory) continue
            val files = runCatching {
                root.walkTopDown()
                    .onEnter { it == root || !it.name.startsWith(".") }
                    .filter { it.isFile && !it.name.startsWith(".") }
                    .toList()
            }.getOrDefault(emptyList())
            for (file in files) {
                if (out.size >= MAX_FILES) return out.sortedBy { it.path.lowercase() }
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val path = ModpackMeta.safeRelativePath(category.dirName + "/" + relative) ?: continue
                val bytes = runCatching { file.length() }.getOrDefault(0L)
                out += ModpackExportEntry(path, file, bytes)
            }
        }
        return out.distinctBy { it.path }.sortedBy { it.path.lowercase() }
    }

    /** 算一个文件的 sha512（十六进制小写）。 */
    fun sha512Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            throw IOException("读不了 " + file.name + "：" + (e.message ?: e::class.simpleName), e)
        }
        return ModUpdateChecker.hex(digest.digest())
    }

    /** 导出成压缩包。会先写临时文件，成功才改名 —— 中途失败不留下半个包。 */
    suspend fun export(
        instance: Instance,
        target: File,
        options: ModpackExportOptions,
        hostedLookup: HostedLookup? = null,
        curseLookup: CurseLookup? = null,
        onProgress: (ModpackExportProgress) -> Unit = {},
    ): ModpackExportResult = withContext(Dispatchers.IO) {
        val resolved = options.copy(name = options.name.trim().ifBlank { instance.name })
        val layout = InstanceLayouts.of(instance)
        val warnings = mutableListOf<String>()

        onProgress(ModpackExportProgress("收集文件"))
        val entries = collect(layout.gameDir, resolved)
        if (entries.isEmpty()) {
            throw ModpackExportException(
                "这个实例的 " + resolved.categories.joinToString("、") { it.label } +
                    " 里没有可导出的文件。换一个实例，或者先装点模组。",
            )
        }

        val hosted = mutableListOf<ModpackHostedEntry>()
        val packaged = mutableListOf<ModpackExportEntry>()
        if (resolved.format == ModpackExportFormat.MRPACK && hostedLookup != null) {
            val hashed = hashEntries(entries, onProgress)
            onProgress(ModpackExportProgress("向 Modrinth 核对", 0, 0))
            val urlsBySha1 = runCatching { hostedLookup.lookup(hashed.map { it.second }) }
                .getOrElse { error ->
                    warnings += "向 Modrinth 核对文件失败（" + (error.message ?: "网络不通") + "），全部文件都打进包里了"
                    emptyMap()
                }
            for ((entry, sha1, sha512) in hashed) {
                val urls = urlsBySha1[sha1].orEmpty().filter { it.startsWith("http", ignoreCase = true) }
                if (urls.isEmpty()) {
                    packaged += entry
                } else {
                    hosted += ModpackHostedEntry(entry.path, sha1, sha512, entry.bytes, sortUrls(urls))
                }
            }
        } else {
            if (resolved.format == ModpackExportFormat.MRPACK) {
                warnings += "没有联网核对，所有文件都打进了 overrides/（包会大一些，但不再依赖下载地址）"
            }
            packaged += entries
        }

        // CurseForge 包的对号：认出来的文件把 projectID/fileID 写进 manifest.json 的 files。
        //
        // 但**文件实体照样打进 overrides/**，不因为对上号就省掉。这是本仓库与标准 CF 导出的
        // 一处刻意分歧，理由在导入侧：本启动器读 MCBBS_V2 包时 files 恒为空、只按 overrides
        // 铺文件（ModpackMeta.parseMcbbs），PCL 的导入器也一样（它自己的注释明说「不会按 files
        // 列表联网下载」）。省掉实体的话，导出的包自己就装不回来 —— 一个只有自己装不上的格式
        // 比没有这个格式更糟。
        //
        // 代价是 files 里的项目在 CurseForge App 上会被先下载一次、再被 overrides 覆盖。
        // 换来的是这个包在任何启动器上都能离线装。warnings 里会如实说这一点。
        val curseFiles = mutableListOf<ModpackCurseFile>()
        if (resolved.format == ModpackExportFormat.CURSE && curseLookup != null) {
            onProgress(ModpackExportProgress("计算文件指纹", 0, packaged.size))
            val byPath = linkedMapOf<String, Long>()
            packaged.forEachIndexed { index, entry ->
                runCatching { CurseForgeFingerprint.of(entry.file) }
                    .onSuccess { byPath[entry.path] = it }
                if (index % 20 == 0) onProgress(ModpackExportProgress("计算文件指纹", index, packaged.size))
            }
            onProgress(ModpackExportProgress("向 CurseForge 核对", 0, 0))
            val ids = runCatching { curseLookup.lookup(byPath.values.toList()) }
                .getOrElse { error ->
                    warnings += "向 CurseForge 核对文件失败（" + (error.message ?: "网络不通") + "），全部文件都打进包里了"
                    emptyMap()
                }
            for ((_, fingerprint) in byPath) {
                val pair = ids[fingerprint] ?: continue
                curseFiles += ModpackCurseFile(fingerprint, pair.first, pair.second)
            }
            if (curseFiles.isNotEmpty()) {
                warnings += "有 " + curseFiles.size + " 个文件能对上 CurseForge 上的项目，清单里写了它们的 ID；" +
                    "文件实体也在包里的 overrides/ 里，所以不联网也装得上（CurseForge App 会多下一次）。"
            }
        }

        val loader = instance.loader
        val loaderVersion = instance.loaderVersion
        if (loader == LoaderKind.QUILT || loader == LoaderKind.OPTIFINE || loader == LoaderKind.OTHER) {
            warnings += "这个实例的加载器是 " + loader.name + "，两种整合包格式都没有它的标准写法：" +
                "导入方只会装原版，模组大概率跑不起来"
        }
        if (loaderVersion.isNullOrBlank() && loader != LoaderKind.VANILLA) {
            warnings += "实例没记加载器版本，" + loader.name + " 这一项没能写进包里，导入方要自己选一个"
        }

        onProgress(ModpackExportProgress("写入压缩包", 0, packaged.size))
        val parent = target.absoluteFile.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        val temp = File(parent ?: File("."), target.name + ".part")
        if (temp.exists()) temp.delete()
        val bytes = writeZip(temp, resolved, instance, hosted, packaged, curseFiles) { done ->
            onProgress(ModpackExportProgress("写入压缩包", done, packaged.size))
        }
        replaceFile(temp, target)
        onProgress(ModpackExportProgress("完成", packaged.size, packaged.size))

        ModpackExportResult(
            archive = target,
            entries = packaged.size + metaFileCount(resolved.format),
            hosted = hosted.size,
            packaged = packaged.size,
            bytes = bytes,
            curseFiles = curseFiles.size,
            warnings = warnings,
        )
    }

    private suspend fun hashEntries(
        entries: List<ModpackExportEntry>,
        onProgress: (ModpackExportProgress) -> Unit,
    ): List<Triple<ModpackExportEntry, String, String>> {
        val out = mutableListOf<Triple<ModpackExportEntry, String, String>>()
        entries.forEachIndexed { index, entry ->
            val sha1 = ModUpdateChecker.sha1Of(entry.file)
            val sha512 = sha512Of(entry.file)
            out += Triple(entry, sha1, sha512)
            if (index % 20 == 0) onProgress(ModpackExportProgress("计算文件哈希", index, entries.size))
        }
        onProgress(ModpackExportProgress("计算文件哈希", entries.size, entries.size))
        return out
    }

    /** 这个格式往包里写几份元数据文件。CurseForge 只写 manifest.json。 */
    fun metaFileCount(format: ModpackExportFormat): Int = when (format) {
        ModpackExportFormat.MRPACK -> 1
        ModpackExportFormat.MCBBS -> 2
        ModpackExportFormat.CURSE -> 1
    }

    /** Modrinth 自己的地址排最后：PCL 的做法，理由是那个地址对国内网络最不好用。 */
    private fun sortUrls(urls: List<String>): List<String> =
        urls.filter { !it.contains("modrinth.com", ignoreCase = true) } +
            urls.filter { it.contains("modrinth.com", ignoreCase = true) }

    private fun replaceFile(temp: File, target: File) {
        if (target.exists() && !target.delete()) {
            throw ModpackExportException("目标文件正被占用，写不进去：" + target.absolutePath)
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun writeZip(
        file: File,
        options: ModpackExportOptions,
        instance: Instance,
        hosted: List<ModpackHostedEntry>,
        packaged: List<ModpackExportEntry>,
        curseFiles: List<ModpackCurseFile>,
        onEntry: (Int) -> Unit,
    ): Long {
        var written = 0L
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            when (options.format) {
                ModpackExportFormat.MRPACK -> {
                    writeText(
                        zip,
                        INDEX_NAME,
                        indexJson(options, instance.gameVersion, instance.loader, instance.loaderVersion, hosted),
                    )
                }
                ModpackExportFormat.MCBBS -> {
                    // 三件元数据共存会让解析方选错格式，所以 MCBBS 模式下不写 modrinth.index.json。
                    writeText(
                        zip,
                        PACKMETA_NAME,
                        packmetaJson(options, instance.gameVersion, instance.loader, instance.loaderVersion),
                    )
                    writeText(
                        zip,
                        MANIFEST_NAME,
                        curseManifestJson(options, instance.gameVersion, instance.loader, instance.loaderVersion),
                    )
                }
                ModpackExportFormat.CURSE -> {
                    // 只写 manifest.json：多一份 mcbbs.packmeta 对 CurseForge 侧是陌生文件，
                    // 而这份 manifest.json 已经把所有信息写全了。
                    writeText(
                        zip,
                        MANIFEST_NAME,
                        curseManifestJson(
                            options,
                            instance.gameVersion,
                            instance.loader,
                            instance.loaderVersion,
                            curseFiles,
                        ),
                    )
                }
            }
            packaged.forEachIndexed { index, entry ->
                zip.putNextEntry(ZipEntry(entry.overridePath))
                entry.file.inputStream().use { input -> written += input.copyTo(zip) }
                zip.closeEntry()
                onEntry(index + 1)
            }
        }
        return written
    }

    private fun writeText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** modrinth.index.json。 */
    fun indexJson(
        options: ModpackExportOptions,
        gameVersion: String,
        loader: LoaderKind,
        loaderVersion: String?,
        hosted: List<ModpackHostedEntry>,
    ): String {
        val root = buildJsonObject {
            put("game", "minecraft")
            put("formatVersion", ModpackMeta.SUPPORTED_MRPACK_FORMAT)
            put("versionId", options.version)
            put("name", options.name)
            put("summary", options.description.ifBlank { options.name + " · " + gameVersion })
            putJsonArray("files") {
                for (entry in hosted) {
                    addJsonObject {
                        put("path", entry.path)
                        putJsonObject("hashes") {
                            put("sha1", entry.sha1)
                            put("sha512", entry.sha512)
                        }
                        putJsonArray("downloads") {
                            for (url in entry.urls) add(JsonPrimitive(url))
                        }
                        put("fileSize", entry.size)
                    }
                }
            }
            putJsonObject("dependencies") {
                put("minecraft", gameVersion)
                val addon = addonOf(loader, loaderVersion)
                if (addon != null) put(addon.dependencyKey, addon.version)
            }
        }
        return PRETTY.encodeToString(JsonObject.serializer(), root)
    }

    /** mcbbs.packmeta。 */
    fun packmetaJson(
        options: ModpackExportOptions,
        gameVersion: String,
        loader: LoaderKind,
        loaderVersion: String?,
    ): String {
        val root = buildJsonObject {
            put("manifestType", "minecraftModpack")
            put("manifestVersion", 1)
            put("name", options.name)
            put("version", options.version)
            put("author", options.author.ifBlank { "鸭嘴兽启动器导出" })
            put("description", options.description)
            put("url", "")
            put("forceUpdate", false)
            put("fileApi", "")
            putJsonArray("origin") {}
            putJsonArray("addons") {
                addJsonObject {
                    put("id", "game")
                    put("version", gameVersion)
                }
                val addon = addonOf(loader, loaderVersion)
                if (addon != null) {
                    addJsonObject {
                        put("id", addon.addonId)
                        put("version", addon.version)
                    }
                }
            }
            putJsonArray("libraries") {}
            putJsonArray("files") {}
            putJsonObject("settings") {
                put("install_mods", true)
                put("install_resourcepack", true)
            }
            putJsonObject("launchInfo") {
                put("minMemory", Instance.DEFAULT_MEMORY_MB)
                putJsonArray("supportJava") {}
                putJsonArray("launchArgument") {}
                putJsonArray("javaArgument") {}
            }
        }
        return PRETTY.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * CurseForge 风格的 manifest.json（MCBBS 包也会带一份，给老启动器兜底）。
     *
     * [curseFiles] 只在导出 CurseForge 包时非空。MCBBS 包一律传空 —— 那份 manifest.json
     * 只是给老启动器认格式用的，里面写 files 会让导入器以为要联网下载，明明能离线装的
     * 包反而装不上（PCL 对 MCBBS 导入器的实测结论）。
     */
    fun curseManifestJson(
        options: ModpackExportOptions,
        gameVersion: String,
        loader: LoaderKind,
        loaderVersion: String?,
        curseFiles: List<ModpackCurseFile> = emptyList(),
    ): String {
        val root = buildJsonObject {
            put("manifestType", "minecraftModpack")
            put("manifestVersion", 1)
            put("name", options.name)
            put("version", options.version)
            put("author", options.author.ifBlank { "鸭嘴兽启动器导出" })
            putJsonObject("minecraft") {
                put("version", gameVersion)
                putJsonArray("modLoaders") {
                    val addon = addonOf(loader, loaderVersion)
                    if (addon != null) {
                        addJsonObject {
                            put("id", addon.curseId + "-" + addon.version)
                            put("primary", true)
                        }
                    }
                }
            }
            putJsonArray("files") {
                for (file in curseFiles) {
                    addJsonObject {
                        put("projectID", file.projectId)
                        put("fileID", file.fileId)
                        put("required", true)
                    }
                }
            }
            put("overrides", OVERRIDES_DIR)
        }
        return PRETTY.encodeToString(JsonObject.serializer(), root)
    }

    private class Addon(val addonId: String, val curseId: String, val dependencyKey: String, val version: String)

    /**
     * 加载器在两种格式里的三种写法。
     *
     * MCBBS 与 CurseForge 都只认短名（game / forge / neoforge / fabric），写
     * net.minecraftforge 这类 patchId 会让导入方完全找不到加载器；Modrinth 的
     * dependencies 用的是 forge / neoforge / fabric-loader。
     */
    private fun addonOf(loader: LoaderKind, version: String?): Addon? {
        val clean = version?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when (loader) {
            LoaderKind.FORGE -> Addon("forge", "forge", "forge", clean)
            LoaderKind.NEOFORGE -> Addon("neoforge", "neoforge", "neoforge", clean)
            LoaderKind.FABRIC -> Addon("fabric", "fabric", "fabric-loader", clean)
            else -> null
        }
    }
}
