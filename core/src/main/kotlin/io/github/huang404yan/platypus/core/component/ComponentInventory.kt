package io.github.huang404yan.platypus.core.component

import io.github.huang404yan.platypus.core.instance.LoaderKind
import io.github.huang404yan.platypus.core.mods.ModScanResult
import io.github.huang404yan.platypus.core.packs.PackKind
import io.github.huang404yan.platypus.core.packs.PackScanResult
import java.io.File

/**
 * 游戏目录里「放进去就生效、拿出来就没了」的那几类东西。
 *
 * [dirName] 是游戏自己认的目录名，写死成常量，不让调用点各写各的。
 */
enum class ComponentKind(val id: String, val label: String, val dirName: String) {
    MOD("mod", "Mod", "mods"),
    RESOURCE_PACK("resourcepack", "资源包", "resourcepacks"),
    SHADER("shader", "光影", "shaderpacks"),
    DATAPACK("datapack", "数据包", "datapacks");

    /** 相对实例目录的位置，例如 `<实例>/mods`。 */
    fun dirOf(instanceDir: File): File = File(instanceDir, dirName)

    companion object {
        fun ofPackKind(kind: PackKind): ComponentKind = when (kind) {
            PackKind.SHADER -> SHADER
            PackKind.RESOURCE_PACK -> RESOURCE_PACK
            PackKind.DATAPACK -> DATAPACK
        }
    }
}

/**
 * 一条已装组件。
 *
 * 模组、资源包、光影、数据包在磁盘上只差一个目录名和实践惯：模组是 `.jar`、其余是
 * `.zip` 或目录。启停、删除、删除确认、停用分组这些操作完全相同，界面上却各写了一遍，
 * 于是同一件事在两页里长得不一样。这个类型就是那份共同形状：谁有额外信息（模组的 modId、
 * 资源包的 pack_format）就塞进 [detail]，界面不必为每一类再分支。
 *
 * [id] 取「父目录 + 去掉停用后缀的基名」。停用会改文件名（`x.jar` ↔ `x.jar.disabled`），
 * 拿文件名或绝对路径当列表的稳定键，一停用整条就被当成另一个条目重新插到别处，
 * 选中态和滚动位置都会跳。
 */
data class Component(
    val id: String,
    val path: String,
    val fileName: String,
    val kind: ComponentKind,
    val title: String,
    val sizeBytes: Long,
    val enabled: Boolean,
    val version: String? = null,
    val loader: LoaderKind? = null,
    val isDirectory: Boolean = false,
    /** 读不出元数据、或者文件看着不对时的说明；正常为 null。 */
    val problem: String? = null,
    /** 界面上顺带写一行的其它事实，例如「pack_format 15」「文件夹」。 */
    val detail: List<String> = emptyList(),
)

/**
 * 一个目录扫下来的结果。
 *
 * [disabled] 单独成列而不是并进 [enabled] 里打标记：停用的通常就几个，又正是回来要找的
 * 那几个，摊进一条长列表就天然沉底了。
 */
data class ComponentInventory(
    val kind: ComponentKind,
    val dir: File,
    val exists: Boolean,
    val enabled: List<Component> = emptyList(),
    val disabled: List<Component> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val totalCount: Int get() = enabled.size + disabled.size

    /** 按「先启用、后停用」拼一列，给不分组的地方用。 */
    val all: List<Component> get() = enabled + disabled
}

/**
 * 把各类扫描结果折成 [ComponentInventory]。
 *
 * 为什么在 core 而不是界面层做：安卓端要同一份数据。折在这里，两端拿到的字段名、
 * 标题怎么取、什么算「问题」就都一致，不会出现桌面说「读不出模组信息」、安卓说
 * 「文件损坏」这类同一件事两种说法的裂口。
 */
object ComponentInventories {

    fun ofMods(result: ModScanResult): ComponentInventory = ComponentInventory(
        kind = ComponentKind.MOD,
        dir = result.dir,
        exists = result.exists,
        enabled = result.mods.map(::ofMod),
        disabled = result.disabled.map(::ofMod),
        warnings = result.warnings,
    )

    fun ofPacks(result: PackScanResult): ComponentInventory {
        val kind = ComponentKind.ofPackKind(result.kind)
        return ComponentInventory(
            kind = kind,
            dir = result.dir,
            exists = result.exists,
            enabled = result.packs.map { ofPackTo(kind, it) },
            disabled = result.disabled.map { ofPackTo(kind, it) },
            warnings = result.warnings,
        )
    }

    /** 单条也能折：界面上一行一行渲染时不必先攒成整份结果。 */
    fun ofMod(entry: io.github.huang404yan.platypus.core.mods.ModEntry): Component = Component(
        id = stableId(entry.path, entry.fileName, ComponentKind.MOD),
        path = entry.path,
        fileName = entry.fileName,
        kind = ComponentKind.MOD,
        title = entry.title,
        sizeBytes = entry.sizeBytes,
        enabled = entry.enabled,
        version = entry.version,
        loader = entry.loader,
        problem = entry.problem,
        detail = listOfNotNull(
            entry.modId?.takeIf { it.isNotBlank() && it != entry.fileName },
            entry.version?.let { "版本 " + it },
            if (entry.enabled) null else "停用中（不会加载）",
        ),
    )

    fun ofPack(kind: ComponentKind, entry: io.github.huang404yan.platypus.core.packs.PackEntry): Component =
        ofPackTo(kind, entry)

    private fun ofPackTo(
        kind: ComponentKind,
        entry: io.github.huang404yan.platypus.core.packs.PackEntry,
    ): Component = Component(
        id = stableId(entry.path, entry.fileName, kind),
        path = entry.path,
        fileName = entry.fileName,
        kind = kind,
        title = entry.title,
        sizeBytes = entry.sizeBytes,
        enabled = entry.enabled,
        isDirectory = entry.isDirectory,
        problem = entry.problem,
        detail = listOfNotNull(
            entry.packFormat?.let { "pack_format " + it },
            if (entry.isDirectory) "文件夹" else null,
        ),
    )
}

/**
 * 列表里的稳定键：父目录 + 去掉停用后缀的基名。
 *
 * 停用只改后缀（`.jar` → `.jar.disabled`），基名不动，所以同一个文件在启用与停用两侧
 * 拿到的是同一个 id，列表项不会在切换分组时被当成新条目。
 */
internal fun stableId(path: String, fileName: String, kind: ComponentKind): String {
    val parent = File(path).parentFile?.absolutePath.orEmpty()
    val base = if (kind == ComponentKind.MOD) {
        io.github.huang404yan.platypus.core.mods.ModFileOps.enabledName(fileName)
    } else {
        io.github.huang404yan.platypus.core.packs.PackFileOps.enabledName(fileName)
    }
    return parent + "/" + base
}

/**
 * 组件的启停与删除。
 *
 * 底下还是 [io.github.huang404yan.platypus.core.mods.ModFileOps] 与
 * [io.github.huang404yan.platypus.core.packs.PackFileOps] 两套规则 —— 它们不能并成一套，
 * 因为后缀不同（`.jar` 与 `.zip`），拿模组那套去改 `x.zip` 会变成 `x.zip.jar`。
 * 这里做的是「按类型派发」，让上层不必知道哪一类归哪一套。
 */
object ComponentOps {

    fun setEnabled(component: Component, enabled: Boolean): File = setEnabled(
        kind = component.kind,
        dir = File(component.path).parentFile ?: error("没有上级目录：" + component.path),
        fileName = component.fileName,
        enabled = enabled,
    )

    fun setEnabled(kind: ComponentKind, dir: File, fileName: String, enabled: Boolean): File =
        if (kind == ComponentKind.MOD) {
            io.github.huang404yan.platypus.core.mods.ModFileOps.setEnabled(dir, fileName, enabled)
        } else {
            io.github.huang404yan.platypus.core.packs.PackFileOps.setEnabled(dir, fileName, enabled)
        }

    fun delete(component: Component): Boolean = delete(
        kind = component.kind,
        dir = File(component.path).parentFile ?: error("没有上级目录：" + component.path),
        fileName = component.fileName,
    )

    fun delete(kind: ComponentKind, dir: File, fileName: String): Boolean =
        if (kind == ComponentKind.MOD) {
            io.github.huang404yan.platypus.core.mods.ModFileOps.delete(dir, fileName)
        } else {
            io.github.huang404yan.platypus.core.packs.PackFileOps.delete(dir, fileName)
        }
}
