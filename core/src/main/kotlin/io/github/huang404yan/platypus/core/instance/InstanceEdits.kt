package io.github.huang404yan.platypus.core.instance

/**
 * 实例设置的「输入框文字 → 可保存的实例」这一步，抽出来放 core。
 *
 * 为什么值得单独一个文件：桌面详情页、安卓详情页都要做同一件事——
 * 把三个输入框的原文（内存、JVM 参数、Java 路径）变成 [Instance]，
 * 其中「内存必须是整数」「夹到 512–32768」「参数一行一个、空行丢掉」三条规则
 * 只要有一端写歪，两端就会在同一个 instances.json 里写出不同的值。
 * 抽成纯函数之后还有第二个好处：它能在 core 里单测，而 Composable 里的
 * 内联 save() 测不了——本轮之前这段逻辑就是测不到的。
 */
sealed interface InstanceEditResult {

    /**
     * 可以保存了。
     *
     * @param instance 合并好输入的实例（registered 恒为 true：写进登记文件就是登记）
     * @param memoryWasClamped 用户填的内存在合法范围外、被改过；界面该说明一句，
     *   否则用户填 999999 看到「已保存」会以为自己填的生效了
     */
    data class Ok(val instance: Instance, val memoryWasClamped: Boolean) : InstanceEditResult

    /** 输入不合法，[message] 是可以直接显示的整句。 */
    data class Invalid(val message: String) : InstanceEditResult
}

object InstanceEdits {

    /** 「内存要填整数，单位 MB。」——三个调用点共用同一句话，免得说法不一致。 */
    const val BAD_MEMORY_MESSAGE = "内存要填整数，单位 MB。"

    /**
     * JVM 参数输入框是「一行一个」。空行与首尾空格丢掉：
     * 用户从别处粘贴常常带空行，原样传给 java 会变成空串参数，启动直接报错。
     */
    fun jvmArgsOf(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 把三个输入框的原文合并进 [instance]。
     *
     * 只改这三项，其余字段（id / directory / loader / createdAt…）原样保留——
     * 详情页只负责改设置，不负责搬家。
     */
    fun fromInput(
        instance: Instance,
        memoryText: String,
        jvmArgsText: String,
        javaPath: String,
    ): InstanceEditResult {
        val parsed = memoryText.trim().toIntOrNull()
            ?: return InstanceEditResult.Invalid(BAD_MEMORY_MESSAGE)
        val clamped = Instance.clampMemoryMb(parsed)
        return InstanceEditResult.Ok(
            instance = instance.copy(
                javaPath = javaPath.trim().ifBlank { null },
                memoryMb = clamped,
                extraJvmArgs = jvmArgsOf(jvmArgsText),
                registered = true,
            ),
            // 只比数值，不比文字：填 " 4096 " 与 "4096" 是一回事，不该提示「被改过」
            memoryWasClamped = clamped != parsed,
        )
    }

    /** 保存成功后的提示句，两端共用。 */
    fun savedMessage(result: InstanceEditResult.Ok): String =
        "已保存：" + result.instance.memoryMb + " MB" +
            if (result.instance.extraJvmArgs.isEmpty()) {
                ""
            } else {
                "，" + result.instance.extraJvmArgs.size + " 个 JVM 参数"
            }
}
