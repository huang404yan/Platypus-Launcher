package io.github.huang404yan.platypus.ui

/**
 * 界面模式：完整 / 简洁。
 *
 * 这两档**只改渲染**：简洁模式里收起来的东西仍然存在，切回完整模式原样回来，
 * 盘上的实例、存档、模组一个字节都不动。所以它不需要迁移、不需要备份，
 * 也不该在设置页配一句「改完要重启」—— 点一下就换。
 *
 * 为什么不叫「新手/专家」：这两档不是能力差别，是「一眼想看多少」。
 * 老玩家也常想要简洁模式，第一次装的人也常想一次看全。
 */
enum class UiMode(val id: String, val label: String) {
    FULL("full", "完整"),
    SIMPLE("simple", "简洁"),

    ; // 后面还有 companion object，枚举项末尾这个分号不能省

    companion object {
        /**
         * 没选过时用哪一档。**默认简洁。**
         *
         * 2026-11-21 用户定的口径：默认简洁，理由是性能 —— 简洁模式少画一半二级项、
         * 少建几块折叠区，老机器上第一屏就出来得快。要全的自己去设置里点一下，
         * 或者第一次进主界面时在那张卡上选一次。
         *
         * 换档只改渲染，盘上东西一个字节都不动，所以默认哪一档不会把谁的东西藏丢。
         */
        val DEFAULT: UiMode = SIMPLE

        /**
         * 把设置里存的那个字符串读成模式。
         *
         * 空串（从没选过）与认不出的值都退 [DEFAULT]：认不出的值多半是以后版本写的，
         * 落到默认档至少是用户当初选过的那一档；把设置文件里写坏的一个词当成需求
         * 去猜「他是不是想要完整模式」不值得。
         */
        fun of(raw: String): UiMode =
            entries.firstOrNull { it.id == raw.trim().lowercase() } ?: DEFAULT
    }
}

/**
 * 左列二级项的一组。
 *
 * [label] 为空表示这组平铺、不加小标题；非空时左列会先画一行灰字小标题再画这组。
 * 简洁模式靠它把不常用的项收在「更多类型」下面 —— 收起而不是删掉，功能入口始终在。
 */
data class SubTabGroup(val label: String?, val tabs: List<SubTab>)

/**
 * 两档模式各显示什么、藏什么。
 *
 * **所有简版差异只写在这一个对象里**。页面里不写 `if (mode == SIMPLE)`：
 * 否则每加一个区块就要在十个文件里各判断一次，迟早漏一处。页面只问「这一块该不该出来」，
 * 判断由这里回答。
 *
 * 加第三档时改这里：枚举多一项，这几个函数的 when 补一支。
 */
object UiPolicy {

    /** 设置页里收进「更多设置」的那几项（简洁模式用）。 */
    private val SIMPLE_NESTED_SETTINGS =
        listOf(SETTINGS_TAB_DOWNLOAD, SETTINGS_TAB_SAVES, SETTINGS_TAB_OTHER)

    /** 下载页里收进「更多类型」的那几项（简洁模式用）。 */
    private val SIMPLE_NESTED_DOWNLOADS =
        listOf(DOWNLOAD_TAB_DATAPACKS, DOWNLOAD_TAB_SHADERS, DOWNLOAD_TAB_SERVER)

    /**
     * 左列的二级项分组。
     *
     * 完整模式只有一个无标题的组（与以前一样平铺）；简洁模式按 [isNested] 拆成两段，
     * 第二段带小标题。拆出来的项**仍在同一个列表里**，选中态、刷新按钮、默认项全不变。
     */
    fun subTabGroups(page: Page, mode: UiMode): List<SubTabGroup> {
        val all = PAGE_SUB_TABS[page].orEmpty()
        if (mode == UiMode.FULL || all.isEmpty()) return listOf(SubTabGroup(null, all))
        val nested = nestedIds(page, mode)
        if (nested.isEmpty()) return listOf(SubTabGroup(null, all))
        val (main, more) = all.partition { it.id !in nested }
        // 「更多类型 / 更多设置」用同一个标题：用户不需要分清它在哪个页面，
        // 只需要知道「常用的在上面，其余的在下面」。
        return listOf(SubTabGroup(null, main), SubTabGroup("更多", more))
    }

    /** [subTabGroups] 摊平后的结果：简洁模式必须覆盖完整模式的每一项，一项都不能少。 */
    fun subTabs(page: Page, mode: UiMode): List<SubTab> =
        subTabGroups(page, mode).flatMap { it.tabs }

    /** 这一项在简洁模式下是否要被收进「更多」。 */
    fun isNested(page: Page, mode: UiMode, subId: String): Boolean =
        mode == UiMode.SIMPLE && subId in nestedIds(page, mode)

    private fun nestedIds(page: Page, mode: UiMode): Set<String> = when {
        mode != UiMode.SIMPLE -> emptySet()
        page == Page.DOWNLOAD -> SIMPLE_NESTED_DOWNLOADS.toSet()
        page == Page.SETTINGS -> SIMPLE_NESTED_SETTINGS.toSet()
        else -> emptySet()
    }

    /**
     * 版本设置页显示哪几节。
     *
     * 简洁模式藏「高级」与「导出」：前者是给排查问题的人用的，后者是一次性动作、
     * 放在总览底部一行就够（[showsExportEntry]）。
     */
    fun setupSections(mode: UiMode): List<Pair<String, String>> = when (mode) {
        UiMode.FULL -> SETUP_SECTIONS
        UiMode.SIMPLE -> SETUP_SECTIONS.filter {
            it.first != SETUP_ADV && it.first != SETUP_EXPORT
        }
    }

    /** 简洁模式下「导出」不以独立一节出现，而是总览底部一行按钮。 */
    fun showsExportEntry(mode: UiMode): Boolean = mode == UiMode.SIMPLE

    /**
     * 启动页侧栏「游戏目录与 Java」那块要不要显示。
     *
     * 简洁模式整块藏起来：那是「这一版用哪个 Java、目录在哪」，装好之后基本不动；
     * 真要改，版本设置页里还有一份完整的。
     */
    fun showsGameDirAndJava(mode: UiMode): Boolean = mode == UiMode.FULL

    /** 启动页侧栏「内存」那一行是否展开细节（自动/手动、具体数字）。 */
    fun showsMemoryDetail(mode: UiMode): Boolean = mode == UiMode.FULL

    /** 设置页「界面」里那一块模式选择本身永远显示 —— 否则简洁模式就切不回去了。 */
    fun showsUiModeSwitch(): Boolean = true

    /**
     * 这一档下动效到底该不该关。
     *
     * 简洁档的定位是「性能档」（2026-11-21 用户口径）：它不只少画几个二级项，还要少花时间
     * 在过渡上。所以简洁档下一律按「少动一点」处理，用户勾没勾那个框都一样；完整档则完全
     * 听用户自己的 [reduceMotion]。
     *
     * 反过来做不到：简洁档下勾「少动一点」不会让完整档的动效也关掉 —— 两个开关是不同的
     * 意图，这里只做「简洁档 > 用户开关」的单向覆盖。
     *
     * 放在 UiPolicy 里而不是在 App.kt 里写一句 `||`：这条规则要能单测，
     * 而且以后加第三档时只改这里。
     */
    fun effectiveReduceMotion(mode: UiMode, reduceMotion: Boolean): Boolean =
        reduceMotion || mode == UiMode.SIMPLE
}
