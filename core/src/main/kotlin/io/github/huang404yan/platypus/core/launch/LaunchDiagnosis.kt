package io.github.huang404yan.platypus.core.launch

/**
 * 把退出前后的日志翻译成人能看懂的一句话。
 *
 * 纯字符串匹配，不读盘不起进程，方便单测：Minecraft 退出时真正的原因几乎总在最后几十行日志里，
 * 界面直接展示「退出码 1」没用，展示「缺少 Java 21 需要的模块」才有用。
 */
object LaunchDiagnosis {

    /**
     * 一条归因规则：匹配到任意关键词就用这条解释。顺序即优先级。
     *
     * [skipWhenReachedMenu] 用于「离线启动时必然出现、但不影响游戏运行」的噪声：
     * 1.20+ 用离线账号启动会先打一段 Failed to verify authentication 401 堆栈，随后照常进主菜单；
     * 只要日志里已经出现过进主菜单的信号，这段堆栈就不能算失败原因。
     */
    data class Rule(
        val keywords: List<String>,
        val message: String,
        val skipWhenReachedMenu: Boolean = false,
    )

    val RULES: List<Rule> = listOf(
        Rule(
            listOf("Could not find or load main class", "ClassNotFoundException: net.minecraft"),
            "找不到主类：classpath 不完整，或游戏的 jar/库文件缺失。",
        ),
        Rule(
            listOf("UnsupportedClassVersionError"),
            "Java 版本太低：游戏是用更高版本编译的，请改用对应版本的 Java。",
        ),
        Rule(
            listOf("java.lang.OutOfMemoryError"),
            "内存不足：请在实例设置里调大最大内存。",
        ),
        Rule(
            listOf("Failed to launch", "GradleWorkerMain", "java.lang.NoClassDefFoundError: cpw/mods"),
            "加载器未能启动：Forge/NeoForge 的库文件可能不完整。",
        ),
        Rule(
            listOf("UnsatisfiedLinkError", "no lwjgl in java.library.path", "Failed to locate library"),
            "缺少原生库：natives 没有解压到 -Djava.library.path，或位数不匹配。",
        ),
        Rule(
            listOf("java.lang.IllegalAccessError", "java.lang.reflect.InaccessibleObjectException"),
            "Java 版本不匹配：游戏运行需要的模块开放参数没生效。",
        ),
        Rule(
            listOf("AuthenticationUnavailableException", "Invalid credentials", "authlib"),
            "登录凭据无效：离线启动需要 32 位十六进制的 UUID 且 accessToken 非空（离线时这段 401 堆栈通常不影响进游戏）。",
            skipWhenReachedMenu = true,
        ),
        Rule(
            listOf("Couldn't set pixel format", "Pixel format not accelerated", "EXCEPTION_ACCESS_VIOLATION"),
            "图形驱动异常：显卡驱动过旧或远程桌面环境不支持 OpenGL。",
        ),
    )

    /**
     * 从日志里挑一条最可能的解释。
     *
     * 只看最后 [tailLines] 行：崩溃堆栈在结尾，早期日志里的历史告警不该被当成原因。
     */
    fun diagnose(lines: List<String>, exitCode: Int = 0, tailLines: Int = 200): String? {
        val tail = if (lines.size > tailLines) lines.subList(lines.size - tailLines, lines.size) else lines
        // 「已经进过主菜单」只在整段日志上判断一次：离线 401 堆栈之后游戏照常运行，不算失败。
        val reached = tail.any { it.contains("Setting user:", ignoreCase = true) } || reachedMenu(lines)
        for (rule in RULES) {
            if (rule.skipWhenReachedMenu && reached) continue
            if (tail.any { line -> rule.keywords.any { line.contains(it, ignoreCase = true) } }) {
                return rule.message
            }
        }
        // 进过主菜单后才退出的非零码，绝大多数是「玩家自己关掉的」或点停止按钮：这不是崩溃。
        if (reached && exitCode != 0) {
            return "游戏在进入主菜单后退出（退出码 " + exitCode + "）：通常是手动停止或正常关闭，日志里没有崩溃迹象。"
        }
        // 没命中已知原因：正常退出就不给解释，异常退出给一句兜底，别让界面显示空白。
        return if (exitCode != 0) "游戏以退出码 " + exitCode + " 结束，日志里没有匹配到已知原因。" else null
    }

    /** 日志里是否出现「已经进了主菜单/正在设置用户」这类正常信号。 */
    fun reachedMenu(lines: List<String>): Boolean {
        val joined = lines.joinToString("\n")
        if (joined.contains("Setting user:", ignoreCase = true)) return true
        return joined.contains("Backend library: LWJGL", ignoreCase = true) &&
            joined.contains("Narrator library for x64 successfully loaded", ignoreCase = true)
    }
}
