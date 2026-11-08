package io.github.huang404yan.platypus.core.runtime

data class JavaRuntime(
    val path: String,
    val majorVersion: Int,
    val vendor: String? = null,
    val arch: String? = null,
    val managed: Boolean = false,
)

interface JavaRuntimeService {
    suspend fun detect(): List<JavaRuntime>

    /** 缺失时下载；返回可用运行时。 */
    suspend fun ensure(major: Int, onProgress: (Float) -> Unit = {}): JavaRuntime
}

/** 版本字符串 → 所需 Java 主版本。先用粗略规则，后续接版本清单细化。 */
object GameJavaRules {
    fun requiredMajor(gameVersion: String): Int {
        val parts = gameVersion.split('.')
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return 8
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return when {
            minor > 20 -> 21
            minor == 20 && patch >= 5 -> 21
            minor >= 17 -> 17
            else -> 8
        }
    }
}
