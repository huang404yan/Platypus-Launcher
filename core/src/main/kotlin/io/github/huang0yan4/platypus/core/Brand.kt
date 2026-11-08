package io.github.huang0yan4.platypus.core

/**
 * 品牌与法务文案的唯一真源。
 *
 * 界面上所有出现名字、口号、免责声明的地方都从这里取，避免各处写死之后漂移。
 * 免责声明的措辞是模板固定的，改动前先看 docs/04-juece-jilu.md 里的品牌决策。
 */
object Brand {
    const val NAME_ZH: String = "鸭嘴兽启动器"
    const val NAME_EN: String = "Platypus Launcher"

    const val PREVIEW_NAME_ZH: String = "鸭嘴兽·孵化版"
    const val PREVIEW_NAME_EN: String = "Platypus Preview"

    const val SLOGAN: String = "把好东西，缝在一起。"
    const val PREVIEW_SLOGAN: String = "自然界早就缝合好了。"

    const val HOMEPAGE: String = "https://github.com/4huang0yan4/Platypus-Launcher"

    /** 当前构建版本，由 Gradle 在编译期写入 BuildInfo。 */
    val version: String get() = BuildInfo.VERSION

    /** 内核版本号规则：0.x-preview.N 走孵化线，1.0.0 起才是正式版。 */
    val isPreview: Boolean get() = version.contains("-preview")

    const val DISCLAIMER_ZH: String =
        "鸭嘴兽启动器（Platypus Launcher）是一个由第三方开发者制作的非官方 Minecraft 启动器。" +
            "本项目与 Mojang Studios 及 Microsoft 无任何关联，未获得其授权、认可或赞助。" +
            "Minecraft 是 Mojang Studios 的注册商标。请支持购买正版游戏。"

    const val DISCLAIMER_EN: String =
        "Platypus Launcher is an unofficial third-party Minecraft launcher. " +
            "This project is not affiliated with, endorsed by, or sponsored by Mojang Studios or Microsoft. " +
            "Minecraft is a registered trademark of Mojang Studios. " +
            "Please support the official game by purchasing it."

    /** 一句话短版，放主界面底部那种很窄的位置。 */
    const val DISCLAIMER_SHORT: String =
        "非官方 Minecraft 启动器，与 Mojang Studios 及 Microsoft 无关联。"
}
