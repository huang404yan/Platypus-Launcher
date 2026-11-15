package io.github.huang404yan.platypus.core.auth

import io.github.huang404yan.platypus.core.BuildInfo

/**
 * 到底用哪个应用 ID。
 *
 * 三条路，优先级从高到低：
 *
 * 1. **构建时注入** —— 打包的人自己注册一个，编译时用 `-Pplatypus.msClientId=` 或
 *    环境变量 [ENV_NAME] 传进来，Gradle 写进 BuildInfo。正式版就是这样：包里带着它，
 *    下载的人什么都不用做，也就没人会因为「没填 ID」而登录不了。
 * 2. **运行时的系统属性 / 环境变量** —— 孵化版不注入，谁要调试登录，自己 export 一个
 *    [ENV_NAME] 就能跑通。PCL 与 PCL-CE 的开源版也是这条：源码里不放 ID，环境变量给。
 * 3. **用户在「设置 → 账号」里填的** —— 孵化版的普通用户走这条。
 *
 * 这个顺序不能反：注入进来的那一份代表「这个包的应用注册」，用户填的代表「这个人自己
 * 注册的应用」。两者同时存在时用注入的，否则正式版用户的登录会打到他填的 ID 上，
 * 而正式版并没有要求他填过。
 *
 * 反过来，**任何一份都不进仓库**：仓库里 [BuildInfo.MS_CLIENT_ID] 恒为空串，构建方在
 * 自己的机器上注入。这与 PCL / PCL-CE / HMCL 的做法一致 —— 公共 ID 随包发出去，
 * 等于所有下载者共用一份别人名下的应用注册。
 */
object ClientIds {

    /** 运行时环境变量名。PCL 用 `PCL_MS_CLIENT_ID`，这里跟着自己的名字走。 */
    const val ENV_NAME: String = "PLATYPUS_MS_CLIENT_ID"

    /** 系统属性名，等价于 [ENV_NAME]，在没法设环境变量的地方用（IDE 运行配置）。 */
    const val PROPERTY_NAME: String = "platypus.msClientId"

    /**
     * 这个包自带的应用 ID；空串表示没带。
     *
     * 编译期注入的优先，其次是运行时给的。每次现读而不是缓存：环境变量在测试里会被改，
     * 缓存一次之后单测之间会互相污染。
     */
    fun builtin(): String {
        val compiled = MicrosoftAuth.normalizeClientId(BuildInfo.MS_CLIENT_ID)
        if (compiled.isNotEmpty()) return compiled
        val fromProperty = System.getProperty(PROPERTY_NAME).orEmpty()
        if (fromProperty.isNotBlank()) return MicrosoftAuth.normalizeClientId(fromProperty)
        return MicrosoftAuth.normalizeClientId(System.getenv(ENV_NAME).orEmpty())
    }

    /** 最终真正拿去发请求的那个 ID：内置优先，没有才用用户填的。 */
    fun resolve(userValue: String): String {
        val fromPackage = builtin()
        if (fromPackage.isNotEmpty()) return fromPackage
        return MicrosoftAuth.normalizeClientId(userValue)
    }

    /** 这个 ID 是哪来的。界面要据此决定那张「自己申请一个」的卡片还画不画。 */
    fun source(userValue: String): Source = when {
        builtin().isNotEmpty() -> Source.BUILTIN
        MicrosoftAuth.normalizeClientId(userValue).isNotEmpty() -> Source.USER
        else -> Source.MISSING
    }

    /** 拿不到任何 ID 时给用户看的一句话；拿得到时返回 null。 */
    fun missingProblem(userValue: String): String? {
        if (source(userValue) != Source.MISSING) return null
        return MicrosoftAuth.clientIdProblem("")
    }

    /** ID 的来源。 */
    enum class Source {
        /** 这个安装包自带的（正式版由构建方注入）。 */
        BUILTIN,

        /** 用户自己在设置里填的（孵化版普通用户）。 */
        USER,

        /** 一份都没有，登录用不了。 */
        MISSING,
    }
}
