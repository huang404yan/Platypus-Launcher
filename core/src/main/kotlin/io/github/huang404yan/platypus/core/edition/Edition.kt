package io.github.huang404yan.platypus.core.edition

import io.github.huang404yan.platypus.core.Brand

/**
 * 这个构建是正式版还是孵化版。
 *
 * 判定只有一条：版本号里带不带 -preview（[Brand.isPreview]）。不另设一个开关，
 * 是因为「发出去的是哪一个」本来就该由版本号唯一决定 —— 多一个布尔字段，
 * 迟早会出现「版本号是 preview 但这个包把自己当正式版」的错配，而那种错配
 * 恰好会绕过首次启动的限制（见 [FirstRun]）。
 *
 * 版本号是编译期常量（Gradle 生成的 BuildInfo），所以 [current] 每个进程只有一种答案，
 * 可以安全地拿它当界面分支的依据。
 */
enum class Edition(val id: String, val label: String) {
    /** 1.0.0 起的正式版：要求正版账号，发布的是编好的应用。 */
    FORMAL("formal", "正式版"),

    /** 0.x-preview.N 的孵化版：也就是开源版，发布出去的源码就是它编出来的。 */
    PREVIEW("preview", "孵化版"),
    ;

    companion object {
        /** 当前构建的通道。 */
        fun current(): Edition = if (Brand.isPreview) PREVIEW else FORMAL

        /** 按 id 认通道，认不出来退回当前构建的通道。 */
        fun of(raw: String): Edition =
            entries.firstOrNull { it.id == raw.trim().lowercase() } ?: current()
    }
}
