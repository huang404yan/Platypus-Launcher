package io.github.huang404yan.platypus.core.server

import java.io.File

/**
 * `server.properties` 的一份可编辑视图。
 *
 * 为什么不是「读成 Map 再写回去」：这个文件是**服务端自己写的**，第一次开服之后它会把
 * 几十个键按它自己的顺序重排一遍，里面还可能夹着用户手写的注释、以及被 `#` 注释掉的旧值
 * （很多人用注释来记「上次这个端口不通，换过」）。读成 Map 再写回去，这些都会消失，
 * 用户改一个端口却发现整个文件被重排了 —— 那会让人不敢再点第二次。
 *
 * 所以这里是**按行保留**的：注释行、空行、键的顺序全部原样留着，只有被显式改过的键
 * 才动它那一行；要新增的键追加在末尾。
 *
 * [lines] 里 [PropertyLine.key] 为 null 表示这一行不是键值对（注释或空行）。
 */
data class ServerPropertiesView(val lines: List<PropertyLine>) {

    /** 第一个出现的键的值；没有这个键就返回 null。 */
    fun raw(key: String): String? = lines.firstOrNull { it.key != null && it.key.equals(key, true) }?.value

    /**
     * 按键取字符串值。
     *
     * 文件里没写这个键时返回 [default] —— 原版服务端对缺失的键也用它内置的默认值，
     * 我们跟着退才不会出现「界面显示 max-players=20 但服务端跑的是 20」这种假一致。
     */
    fun string(key: String, default: String = ""): String = raw(key) ?: default

    fun int(key: String, default: Int = 0): Int = raw(key)?.trim()?.toIntOrNull() ?: default

    fun boolean(key: String, default: Boolean = false): Boolean =
        when (raw(key)?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }

    fun contains(key: String): Boolean = raw(key) != null

    /** 改一个键：已有的改第一处，没有的追加到末尾。返回新视图，不改自己。 */
    fun withValue(key: String, value: String): ServerPropertiesView {
        val trimmedKey = key.trim()
        require(trimmedKey.isNotEmpty()) { "键不能为空" }
        val safeValue = ServerProperties.sanitizeValue(trimmedKey, value)
        val index = lines.indexOfFirst { it.key != null && it.key.equals(trimmedKey, true) }
        val next = if (index >= 0) {
            lines.toMutableList().also { list ->
                list[index] = PropertyLine(raw = trimmedKey + "=" + safeValue, key = trimmedKey, value = safeValue)
            }
        } else {
            lines + PropertyLine(raw = trimmedKey + "=" + safeValue, key = trimmedKey, value = safeValue)
        }
        return copy(lines = next)
    }

    /** 批量改；任一键的值不合法时整个不改（不做「改了一半」）。 */
    fun withValues(changes: Map<String, String>): ServerPropertiesView {
        var result = this
        for ((key, value) in changes) result = result.withValue(key, value)
        return result
    }

    /** 写回磁盘的文本。行尾统一 CRLF —— Windows 上的服务端自己写的就是它。 */
    fun toText(): String = lines.joinToString("\r\n") { it.raw } + "\r\n"
}

/** 一行。注释与空行的 [key] / [value] 都是 null。 */
data class PropertyLine(val raw: String, val key: String? = null, val value: String? = null)

/**
 * 服务端配置里我们**敢让用户改**的那些键。
 *
 * 不是全表：原版有几十个键，大部分（`network-compression-threshold`、`sync-chunk-writes`…）
 * 改了只会让服务端变慢或起不来，界面上给一堆看不懂的开关不是补全而是负担。这里的十来个
 * 是「开服的人真的要改」的那一批，其余保持服务端自己的默认值。
 */
enum class PropertyKind { NUMBER, SWITCH, CHOICE, TEXT }

/**
 * 字段分组，只为界面分节用。
 *
 * [BASIC] 是空串：最早那批字段（端口、难度、视距…）都属于它，界面上不加小标题、
 * 顺序也不动 —— 用户已经认熟了那一段，为了加分组把它打散是净损失。
 * 后来补的按主题分组，多一层小标题，免得表单变成一条三十多行的长龙。
 */
object PropertyGroups {
    const val BASIC = ""
    const val PLAYERS = "玩家"
    const val WORLD = "世界生成"
    const val VISIBILITY = "谁能看见、能不能进"
    const val PERFORMANCE = "性能与超时"

    /** 渲染顺序；[BASIC] 永远在最前。 */
    val ORDER = listOf(BASIC, PLAYERS, WORLD, VISIBILITY, PERFORMANCE)
}

data class PropertyField(
    val key: String,
    val label: String,
    val kind: PropertyKind,
    val default: String,
    val note: String = "",
    /** NUMBER 用；闭区间。 */
    val range: IntRange? = null,
    /** CHOICE 用：值 → 界面上的字。 */
    val options: List<Pair<String, String>> = emptyList(),
    /** 界面分节用；见 [PropertyGroups]。 */
    val group: String = PropertyGroups.BASIC,
    /**
     * TEXT 类型是否允许留空。
     *
     * 大多数文本项留空是没有意义的（世界文件夹名叫空串服务端直接起不来），
     * 但 level-seed 恰恰相反：空串的语义是「随机一个种子」，这是最常见的选择。
     */
    val allowEmpty: Boolean = false,
)

object ServerProperties {

    const val FILE_NAME = "server.properties"

    /** 原版默认端口。与 [io.github.huang404yan.platypus.core.install.ServerPorts.DEFAULT_PORT] 同一个值。 */
    const val DEFAULT_PORT = 25565

    fun fileOf(dir: File): File = File(dir, FILE_NAME)

    /** 读文件；不存在或读不动都返回空视图（全空视图 + 默认值 = 原版内置默认，是对的行为）。 */
    fun read(dir: File): ServerPropertiesView {
        val text = runCatching { fileOf(dir).takeIf { it.isFile }?.readText(Charsets.UTF_8) }
            .getOrNull()
            .orEmpty()
        return parse(text)
    }

    fun parse(text: String): ServerPropertiesView {
        if (text.isEmpty()) return ServerPropertiesView(emptyList())
        val lines = text.split("\n").map { it.trimEnd('\r') }.toMutableList()
        // 文件末尾那个换行会 split 出一个空串；它不是「文件里有个空行」，去掉免得每读一次长一行。
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return ServerPropertiesView(lines.map(::parseLine))
    }

    private fun parseLine(line: String): PropertyLine {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return PropertyLine(raw = line)
        }
        // 原版按第一个 = 切；键里不会有 =，值里可能有（motd 里写 = 很常见）。
        val eq = trimmed.indexOf('=')
        if (eq <= 0) return PropertyLine(raw = line)
        val key = trimmed.substring(0, eq).trim()
        val value = trimmed.substring(eq + 1).trim()
        return PropertyLine(raw = line, key = key, value = value)
    }

    /** 写回磁盘：先写 .tmp 再改名，写坏了不会留下半个 properties。 */
    fun write(dir: File, view: ServerPropertiesView): File {
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("建不了目录：" + dir.absolutePath)
        val target = fileOf(dir)
        val temp = File(dir, FILE_NAME + ".tmp")
        temp.writeText(view.toText(), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            // Windows 上目标存在时 renameTo 会失败，退一步「覆盖写」
            target.writeText(view.toText(), Charsets.UTF_8)
            temp.delete()
        }
        return target
    }

    /**
     * 写值之前的清洗。
     *
     * 两件必须做：① `properties` 是**逐行**格式的，值里一旦有换行，写进去就变成两个键
     * （motd 里换行是最常见的输入）—— 换成空格。② 值首尾的空白会被服务端 `trim()` 掉，
     * 这里先去掉，免得磁盘上的文本与界面上显示的不一致。
     */
    fun sanitizeValue(key: String, value: String): String =
        value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim()

    /** 界面表单里列出来的字段，顺序即显示顺序。 */
    val FIELDS: List<PropertyField> = listOf(
        PropertyField(
            key = "motd",
            label = "服务器描述",
            kind = PropertyKind.TEXT,
            default = "A Minecraft Server",
            note = "别人在多人列表里看到的那一行。不能换行（换行会变成两个键，这里会替换成空格）。",
        ),
        PropertyField(
            key = "server-port",
            label = "端口",
            kind = PropertyKind.NUMBER,
            default = DEFAULT_PORT.toString(),
            range = 1..65535,
            note = "别人连进来用的端口。改完要确认没人占着它。",
        ),
        PropertyField(
            key = "max-players",
            label = "最多几人",
            kind = PropertyKind.NUMBER,
            default = "20",
            range = 1..1000,
        ),
        PropertyField(
            key = "player-idle-timeout",
            label = "挂机多久踢下线（分钟）",
            kind = PropertyKind.NUMBER,
            default = "0",
            range = 0..1440,
            note = "0 表示不踢。开着小服又不想让人长期占位置时有用。",
            group = PropertyGroups.PLAYERS,
        ),
        PropertyField(
            key = "difficulty",
            label = "难度",
            kind = PropertyKind.CHOICE,
            default = "easy",
            options = listOf(
                "peaceful" to "和平",
                "easy" to "简单",
                "normal" to "普通",
                "hard" to "困难",
            ),
        ),
        PropertyField(
            key = "gamemode",
            label = "默认游戏模式",
            kind = PropertyKind.CHOICE,
            default = "survival",
            options = listOf(
                "survival" to "生存",
                "creative" to "创造",
                "adventure" to "冒险",
                "spectator" to "旁观",
            ),
        ),
        PropertyField(
            key = "hardcore",
            label = "极限模式",
            kind = PropertyKind.SWITCH,
            default = "false",
            note = "开了之后死了就是旁观，不能复活。",
        ),
        PropertyField(
            key = "pvp",
            label = "允许玩家互相伤害",
            kind = PropertyKind.SWITCH,
            default = "true",
        ),
        PropertyField(
            key = "online-mode",
            label = "验证正版",
            kind = PropertyKind.SWITCH,
            default = "true",
            note = "关掉之后离线启动器也能进，但谁都能用别人的名字进来。",
        ),
        PropertyField(
            key = "white-list",
            label = "只允许白名单进",
            kind = PropertyKind.SWITCH,
            default = "false",
            note = "开了之后只有下面「白名单」里的人能进。",
        ),
        PropertyField(
            key = "enforce-whitelist",
            label = "白名单外的踢出去",
            kind = PropertyKind.SWITCH,
            default = "false",
            note = "开着服务器时把人从白名单删掉，开了这个他会被立刻踢下线。",
        ),
        PropertyField(
            key = "allow-flight",
            label = "允许飞行",
            kind = PropertyKind.SWITCH,
            default = "false",
            note = "生存模式能飞会被判作弊踢掉；装了飞行类模组才需要开。",
        ),
        PropertyField(
            key = "allow-nether",
            label = "允许下界",
            kind = PropertyKind.SWITCH,
            default = "true",
        ),
        PropertyField(
            key = "enable-command-block",
            label = "启用命令方块",
            kind = PropertyKind.SWITCH,
            default = "false",
        ),
        PropertyField(
            key = "view-distance",
            label = "视距（区块）",
            kind = PropertyKind.NUMBER,
            default = "10",
            range = 3..32,
            note = "调小能明显减轻服务端和玩家两边的负担。",
        ),
        PropertyField(
            key = "simulation-distance",
            label = "模拟距离（区块）",
            kind = PropertyKind.NUMBER,
            default = "10",
            range = 3..32,
            note = "离得太远的区块不再刷怪、不再长作物。",
        ),
        PropertyField(
            key = "spawn-protection",
            label = "出生点保护半径",
            kind = PropertyKind.NUMBER,
            default = "16",
            range = 0..1000,
            note = "这个范围内只有管理员能放方块。0 表示不保护。",
        ),
        PropertyField(
            key = "op-permission-level",
            label = "管理员权限等级",
            kind = PropertyKind.NUMBER,
            default = "4",
            range = 1..4,
            note = "4 是最高，能执行所有命令。",
        ),
        PropertyField(
            key = "level-name",
            label = "世界文件夹名",
            kind = PropertyKind.TEXT,
            default = "world",
            note = "改这里等于换一张地图。名字就是服务端目录下的文件夹名。",
        ),
        PropertyField(
            key = "level-seed",
            label = "世界种子",
            kind = PropertyKind.TEXT,
            default = "",
            allowEmpty = true,
            note = "留空就是随机一个。已经有世界的服务器改这里没有任何效果 —— 种子只在生成时用一次。",
            group = PropertyGroups.WORLD,
        ),
        PropertyField(
            key = "level-type",
            label = "世界类型",
            kind = PropertyKind.CHOICE,
            default = "minecraft:normal",
            options = listOf(
                "minecraft:normal" to "默认",
                "minecraft:flat" to "超平坦",
                "minecraft:large_biomes" to "巨型生物群系",
                "minecraft:amplified" to "放大化",
                "minecraft:single_biome_surface" to "单一生物群系",
            ),
            note = "只在生成新世界时生效。1.19 之前的服务端把它写成 DEFAULT / FLAT 这种大写形式，也认。",
            group = PropertyGroups.WORLD,
        ),
        PropertyField(
            key = "generate-structures",
            label = "生成结构",
            kind = PropertyKind.SWITCH,
            default = "true",
            note = "关掉之后村庄、神庙、要塞都不会生成。已经在跑的世界改它没用。",
            group = PropertyGroups.WORLD,
        ),
        PropertyField(
            key = "max-world-size",
            label = "世界边界半径",
            kind = PropertyKind.NUMBER,
            default = "29999984",
            range = 1..29999984,
            note = "方块数。默认值就是世界边界的理论上限，调小能把世界圈起来。",
            group = PropertyGroups.WORLD,
        ),
        PropertyField(
            key = "enable-status",
            label = "在多人列表里显示在线状态",
            kind = PropertyKind.SWITCH,
            default = "true",
            note = "关掉之后别人的多人列表里这个服务器会显示成「无法连接」，但仍然进得去。",
            group = PropertyGroups.VISIBILITY,
        ),
        PropertyField(
            key = "hide-online-players",
            label = "隐藏在线玩家名单",
            kind = PropertyKind.SWITCH,
            default = "false",
            note = "开了之后查询服务器状态的人看不到有谁在线（只能看到人数）。",
            group = PropertyGroups.VISIBILITY,
        ),
        PropertyField(
            key = "prevent-proxy-connections",
            label = "拒绝通过代理连进来",
            kind = PropertyKind.SWITCH,
            default = "false",
            note = "开了会挡掉一部分用代理的玩家（包括一些加速器）。不是防作弊，别指望它。",
            group = PropertyGroups.VISIBILITY,
        ),
        PropertyField(
            key = "network-compression-threshold",
            label = "压缩阈值（字节）",
            kind = PropertyKind.NUMBER,
            default = "256",
            range = -1..65535,
            note = "小于这个大小的包不压缩。-1 是完全不压缩（吃带宽省 CPU）。局域网内调大或关掉会更快。",
            group = PropertyGroups.PERFORMANCE,
        ),
        PropertyField(
            key = "max-tick-time",
            label = "单次卡顿多久算崩（毫秒）",
            kind = PropertyKind.NUMBER,
            default = "60000",
            range = -1..60000,
            note = "一次 tick 超过这个时间服务端会自己停掉。-1 表示永不因卡顿退出。",
            group = PropertyGroups.PERFORMANCE,
        ),
        PropertyField(
            key = "entity-broadcast-range-percentage",
            label = "实体同步范围（百分比）",
            kind = PropertyKind.NUMBER,
            default = "100",
            range = 10..1000,
            note = "调小能显著减带宽，代价是远处的怪和玩家会突然出现。100 是原版行为。",
            group = PropertyGroups.PERFORMANCE,
        ),
        PropertyField(
            key = "sync-chunk-writes",
            label = "区块写入同步落盘",
            kind = PropertyKind.SWITCH,
            default = "true",
            note = "关掉能快一点，但断电时更容易丢最近的方块改动。宁可慢也别关。",
            group = PropertyGroups.PERFORMANCE,
        ),
    )

    /**
     * 按 [PropertyGroups.ORDER] 分好的字段，界面照着渲染。
     *
     * 认不出的分组追加在末尾而不是丢掉：以后有人加了字段却忘了登记到 ORDER 里，
     * 结果应该是「位置靠后」而不是「这个设置在界面上消失」—— 后者是个很难发现的 bug。
     */
    fun groupedFields(): List<Pair<String, List<PropertyField>>> {
        val byGroup = FIELDS.groupBy { it.group }
        val ordered = PropertyGroups.ORDER.mapNotNull { name ->
            byGroup[name]?.let { name to it }
        }
        val known = PropertyGroups.ORDER.toSet()
        val extra = byGroup.filterKeys { it !in known }.toSortedMap()
        return ordered + extra.toList()
    }

    /** 桌面端 / 安卓端都用的默认视图（空文件 + 内置默认）。 */
    fun defaults(): Map<String, String> = FIELDS.associate { it.key to it.default }

    /** 某字段当前值（缺键时取 [PropertyField.default]）。 */
    fun valueOf(view: ServerPropertiesView, field: PropertyField): String = view.raw(field.key) ?: field.default

    /**
     * 校验一个字段的新值。合法返回 null，不合法返回一句给人看的话。
     *
     * 界面上边输边调它；[ServerPropertiesView.withValue] 不校验（它要能改任意键），
     * 校验放在这一层，是因为「哪些键有什么约束」本身就是界面表单的知识。
     */
    fun problemOf(field: PropertyField, raw: String): String? {
        val value = raw.trim()
        return when (field.kind) {
            PropertyKind.SWITCH -> if (value.equals("true", true) || value.equals("false", true)) {
                null
            } else {
                "只能填 true 或 false。"
            }
            PropertyKind.CHOICE -> {
                val allowed = field.options.map { it.first }
                if (allowed.any { sameChoice(it, value) }) null
                else "只能选 " + allowed.joinToString("、") + " 之一。"
            }
            PropertyKind.NUMBER -> {
                val number = value.toIntOrNull()
                when {
                    number == null -> "要填整数。"
                    field.range != null && number !in field.range ->
                        "要在 " + field.range.first + " 到 " + field.range.last + " 之间。"
                    else -> null
                }
            }
            PropertyKind.TEXT -> when {
                value.isEmpty() && field.key != "motd" && !field.allowEmpty -> "不能为空。"
                field.key == "level-name" && !isSafeWorldName(value) ->
                    "世界文件夹名不能带 / \\\\ : * ? \" < > | 这些字符。"
                else -> null
            }
        }
    }

    /**
     * 比较 CHOICE 的取值时用的规范化形式。
     *
     * 为什么要这一步：原版写 level-type 时会把冒号转义成 `minecraft\\:normal`（java.util.Properties
     * 的老习惯），而人手工填、教程里抄的又普遍是不带反斜杠的 `minecraft:normal`。两种写法
     * 服务端都认，但直接字符串比较会把磁盘上那份判成「不在选项里」，界面上明明选着却报错。
     */
    fun choiceKeyOf(raw: String): String = raw.trim().replace("\\:", ":").replace("\\\\", "\\").lowercase()

    /** 两个值是不是同一个 CHOICE 选项。 */
    fun sameChoice(left: String, right: String): Boolean = choiceKeyOf(left) == choiceKeyOf(right)

    /** 世界文件夹名：服务端会把它当目录名用，所以挡掉路径分隔符与 Windows 非法字符。 */
    fun isSafeWorldName(raw: String): Boolean {
        val name = raw.trim()
        if (name.isEmpty() || name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\')) return false
        val forbidden = charArrayOf(':', '*', '?', '"', '<', '>', '|')
        return name.none { c -> c in forbidden || c.code < 0x20 }
    }

    /** 空文件时给界面一个「这些值是原版默认」的提示用。 */
    fun exists(dir: File): Boolean = fileOf(dir).isFile
}
