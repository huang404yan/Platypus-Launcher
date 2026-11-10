package io.github.huang404yan.platypus.core.launch

import java.io.File

/**
 * 实例目录 `crash-reports/` 里的一份崩溃报告。
 *
 * 游戏崩掉以后玩家最想看的是两样东西：「Description: 渲染画面」这类一句话，和第一个异常。
 * 整份报告动辄几千行堆栈，直接铺到界面上没人读，所以这里只挑这两样，
 * 完整文件仍靠 [file] 让界面按需打开。
 */
data class CrashReport(
    val file: File,
    /** 文件修改时间（毫秒）。 */
    val modifiedAt: Long,
    val bytes: Long,
    /** 「Description: ...」后面那句话；读不到就是空串。 */
    val description: String = "",
    /** 第一个异常行，形如 `java.lang.NullPointerException: Cannot invoke ...`；读不到就是空串。 */
    val exception: String = "",
) {
    val fileName: String get() = file.name
}

/**
 * 读崩溃报告。
 *
 * 为什么不解析文件名里的时间戳：原版 1.13 之后是 `crash-2026-11-10_12.00.00-client.txt`，
 * 但服务端与某些加载器改过格式，而「文件写进来的时间」在两种情况下都等于崩溃时间，
 * 所以用 lastModified 当主键、文件名当次键（同一秒内两份时按名字兜底）。
 */
object CrashReports {

    /** 游戏默认把崩溃报告放在这里的这一层。 */
    const val DIR_NAME = "crash-reports"

    /** 只读头部这么多字节就够找 Description 与第一个异常了，避免把整份几十万行读进内存。 */
    private const val MAX_READ_BYTES = 256 * 1024L

    /**
     * 异常行的样子。
     *
     * 不用 `\$` 匹配内部类：崩溃报告第一行异常一定是顶层类，写上反而让正则难读。
     * `Caused by:` 前缀保留，因为有些报告第一个 `Caused by` 比外层异常更有信息量 —— 但那要等外壳行，
     * 这里顺序上仍然先命中外壳（外层在前），行为符合「第一个异常」。
     */
    private val EXCEPTION_PATTERN = Regex("""^(?:Caused by: )?[a-zA-Z_][\w.]*(?:Exception|Error)\b""")

    private const val DESCRIPTION_PREFIX = "Description:"

    fun dirOf(instanceDir: File): File = File(instanceDir, DIR_NAME)

    /** 最新一份崩溃报告；目录不存在或里面没有 .txt 就返回 null。 */
    fun latest(instanceDir: File): File? {
        val dir = dirOf(instanceDir)
        if (!dir.isDirectory) return null
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".txt", ignoreCase = true) }
            ?: return null
        return files.maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })
    }

    /** 读最新一份；没有报告或读失败都返回 null（读失败不该把启动页搞崩）。 */
    fun read(instanceDir: File, maxBytes: Long = MAX_READ_BYTES): CrashReport? {
        val file = latest(instanceDir) ?: return null
        val text = runCatching { readHead(file, maxBytes) }.getOrNull() ?: return null
        return CrashReport(
            file = file,
            modifiedAt = file.lastModified(),
            bytes = file.length(),
            description = pickDescription(text),
            exception = pickException(text),
        )
    }

    /** 挑出 `Description: xxx` 那一行的后半句。 */
    fun pickDescription(text: String): String =
        text.lineSequence()
            .firstOrNull { it.trimStart().startsWith(DESCRIPTION_PREFIX, ignoreCase = true) }
            ?.trimStart()
            ?.substring(DESCRIPTION_PREFIX.length)
            ?.trim()
            .orEmpty()

    /** 挑出第一个异常行；跳过堆栈的 `at ...` 行。 */
    fun pickException(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { line -> !line.startsWith("at ") && EXCEPTION_PATTERN.containsMatchIn(line) }
            .orEmpty()

    /**
     * 只读前 [maxBytes] 字节左右。
     *
     * 按 UTF-8 读：1.13 之后游戏自己写 UTF-8；万一读到旧版 GBK 报告，头部那两行仍是 ASCII，
     * 挑 Description 与异常名不受影响，所以不做编码探测。
     */
    private fun readHead(file: File, maxBytes: Long): String {
        val builder = StringBuilder()
        var used = 0L
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            while (used < maxBytes) {
                val line = reader.readLine() ?: break
                builder.append(line).append('\n')
                used += line.length + 1L
            }
        }
        return builder.toString()
    }
}
