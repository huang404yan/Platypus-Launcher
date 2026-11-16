package io.github.huang404yan.platypus.core.lan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 陶瓦联机（Terracotta）的协议层：阶段名、房间码、状态解析与所有给用户看的话。
 *
 * 这里一个网络请求、一个进程都不碰（那些在 [TerracottaClient] 与 [TerracottaInstaller]），
 * 所以整个文件是纯函数、可以离线测。文案集中在一处也免得界面和文档各说一套。
 *
 * 集成契约（2026-10-27 实测，与 PCL 自编版一致）：
 *  1. terracotta.exe --hmcl <文件> -> 文件里出现 {"port":N}（实测 0.1 秒）
 *  2. 之后全走 http://127.0.0.1:<port> 的 GET：/state /state/scanning /state/guesting /state/ide /meta /panic
 *  3. 端口随机，必须等文件；本地回环必须禁代理，否则被系统代理劫持成 502
 */
object Terracotta {

    /** 内置的陶瓦版本。升级时三处一起改：这里、[PACKAGE_SHA512]、[downloadUrls]。 */
    const val VERSION: String = "0.4.2"

    /** 官方发行包的 SHA-512（windows-x86_64）。下载后必须核对：这是要执行的文件。 */
    const val PACKAGE_SHA512: String =
        "6a98f524d4f00373696517306af8aa50d01d55ce4eadb27e9e4bc2f882707a0b5f20d5d4c33371d1459dcf5bf144ffed9beb414202d9ccf32b11dbbfcf19d650"

    /** 程序目录名，挂在 <gameRoot>/platypus/ 下（与启动器其它本地数据放一起）。 */
    const val DIR_NAME: String = "terracotta"

    /** 解出来的可执行文件名。发行包里就是这个名字。 */
    const val EXE_NAME: String = "terracotta.exe"

    /** 房间码总长度：U/ + 16 位字符 + 3 个连字符 = 21。 */
    const val CODE_LENGTH: Int = 21

    /** 房间码字符表：34 个，去掉了易混的 I 与 O。 */
    private const val CODE_CHARS: String = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** 一行日志能认出来的「游戏已经关了」之类，留给界面判对端消失用。 */
    const val NOTE_HOST_ENDED: String =
        "房间已结束（游戏可能已关闭或退出了世界）。重新建房会生成新的房间码。"

    /** 游客侧对端消失：不清房间码，同一个码多半还能重连。 */
    const val NOTE_GUEST_ENDED: String =
        "房主已断开（对方可能关掉了游戏或退出了世界）。可以再点一次「加入」重试；若仍失败，请向房主索要新的房间码。"

    /** 陶瓦要约 27 秒才会自己从 host-ok 掉到 exception，这半分钟里界面先给一句软提示。 */
    const val NOTE_HOST_GAME_GONE: String =
        "游戏已关闭，正在等待陶瓦确认（通常 30 秒内）…之后这个房间码会失效。"

    /** 陶瓦程序目录。 */
    fun dirOf(gameRoot: File): File = File(File(gameRoot, "platypus"), DIR_NAME)

    /** 目录里那个可执行文件；没装返回 null。 */
    fun exeOf(dir: File): File? {
        val files = dir.listFiles() ?: return null
        return files
            .filter { it.isFile }
            .filter { it.name.lowercase().startsWith("terracotta") && it.name.lowercase().endsWith(".exe") }
            .sortedBy { it.name.lowercase() }
            .firstOrNull()
    }

    /** 装没装：目录里有 terracotta 开头的 exe 就算装了。 */
    fun isInstalled(dir: File): Boolean = exeOf(dir) != null

    /** 发行包的文件名。 */
    fun packageName(): String = "terracotta-" + VERSION + "-windows-x86_64-pkg.tar.gz"

    /**
     * 下载地址。
     *
     * 以前这里有三个源（cnb.cool / gitee / github），按国内可达性排过序。2026-11-16 起
     * 只留 GitHub 官方一条：那两个是国内转存，各固定住一个旧版本号，而现在的规矩是
     * 「跟着官方最新版走」（见 [TerracottaReleases]），转存不同步就会下到跟校验值对不上的包。
     *
     * [version] 可以传在线查到的版本；不传就用内置的 [VERSION]。
     */
    fun downloadUrls(version: String = VERSION): List<String> = listOf(
        TerracottaReleases.officialUrl("v" + version, TerracottaReleases.assetNameOf(version)),
    )

    // ── 房间码 ─────────────────────────────────────────────────────────────────

    /**
     * 归一化：去空白、转大写、I 当 1、O 当 0，再截出从 U/ 开始的 21 位。
     * 抄码的人经常把 0 看成 O、1 看成 I，也会连空格一起复制进来。认不出来返回空串。
     */
    fun normalizeCode(raw: String?): String {
        if (raw == null) return ""
        val cleaned = raw.trim().uppercase().replace(" ", "").replace("I", "1").replace("O", "0")
        val start = cleaned.indexOf("U/")
        if (start < 0) return ""
        val tail = cleaned.substring(start)
        if (tail.length < CODE_LENGTH) return ""
        return tail.substring(0, CODE_LENGTH)
    }

    /**
     * 校验位：16 位按 34 进制取模 7 必须为 0（逐步取模，避免 34^16 溢出）。
     * 这一条能挡住「抄错一个字符」——只查格式的话，错一个字符的码看着完全正常。
     */
    fun checkCode(raw: String): Boolean {
        val code = normalizeCode(raw)
        if (code.length != CODE_LENGTH || !code.startsWith("U/")) return false
        val body = code.substring(2)
        if (body.length != CODE_LENGTH - 2) return false
        var mod = 0
        for (i in body.indices) {
            val ch = body[i]
            if (i == 4 || i == 9 || i == 14) {
                if (ch != '-') return false
                continue
            }
            val value = CODE_CHARS.indexOf(ch)
            if (value < 0) return false
            mod = (mod * 34 + value) % 7
        }
        return mod == 0
    }

    /** 房间码格式不对时的说法。 */
    fun codeFormatProblem(): String =
        "房间码格式不对：应为 U/XXXX-XXXX-XXXX-XXXX，共 21 个字符。"

    /** 格式对但校验位不对时的说法。 */
    fun codeChecksumProblem(): String =
        "房间码校验位不对，可能抄错了一个字符，请核对后重试。"

    // ── 状态解析 ───────────────────────────────────────────────────────────────

    /**
     * 解析 /state 的响应。字段名固定是 state / room / url / difficulty / type。
     * 解析不动返回一个 ok = false 的空状态，不抛 —— 轮询每秒一次，抛一次就刷一屏日志。
     */
    fun parseState(text: String): TerracottaState {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return TerracottaState()
        return TerracottaState(
            ok = true,
            stage = root["state"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            room = root["room"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            url = root["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            difficulty = root["difficulty"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            exceptionType = root["type"]?.jsonPrimitive?.intOrNull ?: TerracottaState.UNKNOWN_TYPE,
        )
    }

    /** 从端口文件里取端口。文件内容形如 {"port":12345}，取不到返回 null。 */
    fun parsePort(text: String): Int? {
        val match = PORT_PATTERN.find(text) ?: return null
        val port = match.groupValues[1].toIntOrNull() ?: return null
        return port.takeIf { it in 1..65535 }
    }

    private val PORT_PATTERN = Regex("\"port\"\\s*:\\s*(\\d+)")

    // ── 文案 ───────────────────────────────────────────────────────────────────

    /** 阶段名 -> 一句话。认不出的阶段原样显示（陶瓦升级后可能加新阶段）。 */
    fun stageText(stage: String): String = when (stage) {
        TerracottaStages.WAITING -> "空闲"
        TerracottaStages.HOST_SCANNING -> "正在等待游戏开启局域网"
        TerracottaStages.HOST_STARTING -> "正在创建房间"
        TerracottaStages.HOST_OK -> "房间已就绪"
        TerracottaStages.GUEST_CONNECTING -> "正在连接房间"
        TerracottaStages.GUEST_STARTING -> "正在打洞连接"
        TerracottaStages.GUEST_OK -> "已连上"
        TerracottaStages.EXCEPTION -> "失败"
        "" -> "未运行"
        else -> stage
    }

    /** 打洞难度 -> 一句人话。 */
    fun difficultyText(difficulty: String): String = when (difficulty) {
        "EASIEST" -> "网络极佳"
        "SIMPLE" -> "网络良好"
        "MEDIUM" -> "网络一般，可能要多等几秒"
        "TOUGH" -> "网络较差，可能连不上"
        else -> "正在评估网络"
    }

    /** exception.type 的 0-5 对照，全部来自陶瓦源码的 ExceptionType 枚举顺序。 */
    fun exceptionText(type: Int): String = when (type) {
        0 -> "连不上房主（对方可能已关房或离线，也可能是网络被限制）。"
        1 -> "房主主动断开了连接。"
        2 -> "本机的组网组件崩了，重试一次；反复失败就换个联机方式。"
        3 -> "房主那边的组网组件崩了，让对方重开房间。"
        4 -> "房主那边的游戏已关闭（退出世界或退了游戏）。"
        5 -> "房间码或版本对不上，确认双方都用同一个版本。"
        else -> "未知错误，可看陶瓦的运行日志。"
    }

    /**
     * 把一份状态转成一句话。轮询时每秒调一次，所以必须纯、必须快。
     *
     * [networkAvailable] 只在 host-ok 时用到：本机断开网络时房间还是建好的，
     * 但外人连不上，继续说「已就绪」会误导。
     */
    fun statusText(state: TerracottaState, networkAvailable: Boolean = true): String {
        if (!state.ok) return "状态：陶瓦未运行（点「创建房间」或「加入房间」时会自动启动）。"
        return when (state.stage) {
            TerracottaStages.WAITING -> "状态：空闲，可以创建或加入房间。"
            TerracottaStages.HOST_SCANNING ->
                "状态：正在等待游戏开启局域网 —— 请切到游戏按 Esc →「对局域网开放」→「开始」。"
            TerracottaStages.HOST_STARTING -> "状态：正在创建房间…（房间码 " + state.room + "）"
            TerracottaStages.HOST_OK -> {
                val base = "房间已就绪，把房间码发给朋友：" + state.room
                if (networkAvailable) base
                else base + "\n（注意：当前网络不可用。本机房间已建好，但其他人现在可能连不上。）"
            }
            TerracottaStages.GUEST_CONNECTING -> "状态：正在连接房间 " + state.room + "…"
            TerracottaStages.GUEST_STARTING ->
                "状态：正在打洞连接…（" + difficultyText(state.difficulty) + "）"
            TerracottaStages.GUEST_OK -> {
                if (state.url.isBlank()) {
                    "已连上！进游戏「多人游戏」双击「陶瓦联机大厅」即可。"
                } else {
                    "已连上！进游戏「多人游戏」双击「陶瓦联机大厅」。\n" +
                        "连不上时改用「直接连接」填：" + state.url
                }
            }
            TerracottaStages.EXCEPTION -> {
                // 守护进程刚被拉起时会先报一个空异常（没有房间码也没有地址），
                // 那不是失败，只是还没开始。真正的失败一定挂着码或地址。
                if (state.room.isBlank() && state.url.isBlank()) {
                    "状态：陶瓦已启动，还没有房间 —— 点「创建房间」或「加入房间」开始。"
                } else {
                    "状态：失败 —— " + exceptionText(state.exceptionType)
                }
            }
            else -> "状态：" + state.stage
        }
    }

    /** 还没装陶瓦时的说法。 */
    fun notInstalledText(): String =
        "状态：陶瓦未安装。点「安装 / 更新陶瓦」自动下载（约 8 MB）。"
}

/** 陶瓦的阶段名。全部小写带连字符，与守护进程的 /state 里 state 字段一致。 */
object TerracottaStages {
    const val WAITING: String = "waiting"
    const val HOST_SCANNING: String = "host-scanning"
    const val HOST_STARTING: String = "host-starting"
    const val HOST_OK: String = "host-ok"
    const val GUEST_CONNECTING: String = "guest-connecting"
    const val GUEST_STARTING: String = "guest-starting"
    const val GUEST_OK: String = "guest-ok"
    const val EXCEPTION: String = "exception"

    /** 房主态：直连地址是游客本机的回环地址，房主拿到也发不出去。 */
    fun isHost(stage: String): Boolean = stage.startsWith("host-")

    /** 真正建成了房 / 真连上了，才算拿到了可以发给别人的东西。 */
    fun isReady(stage: String): Boolean = stage == HOST_OK || stage == GUEST_OK
}

/** 一次状态轮询的结果。ok = false 表示连不上守护进程（没启动或已退出）。 */
data class TerracottaState(
    val ok: Boolean = false,
    val stage: String = "",
    val room: String = "",
    val url: String = "",
    val difficulty: String = "",
    val exceptionType: Int = UNKNOWN_TYPE,
) {
    /** 当前是不是房主态。 */
    val isHost: Boolean get() = TerracottaStages.isHost(stage)

    /** 有没有拿到过房间码或直连地址 —— 用来区分「空异常」与「真失败」。 */
    val hasHandshake: Boolean get() = room.isNotBlank() || url.isNotBlank()

    companion object {
        /** 没给 type 字段时用它。陶瓦源码里 0-5 是有效值。 */
        const val UNKNOWN_TYPE: Int = -1
    }
}