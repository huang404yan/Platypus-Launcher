package io.github.huang404yan.platypus.core.search

/**
 * 一条「中文叫法 → Modrinth 上真能搜到的关键词」的对照。
 *
 * [en] 一律写 Modrinth 搜索框里能直接出结果的词（slug 或通用英文名），不写项目全名：
 * 用户点一下就是要能搜到东西，搜不到等于没帮上忙。
 */
data class SearchTerm(
    val zh: String,
    val en: String,
    val kind: Kind = Kind.ANY,
) {

    /** 这个中文叫法属于哪一类内容，用来按当前页面过滤。 */
    enum class Kind(val label: String) {
        MOD("模组"),
        SHADER("光影"),
        RESOURCE_PACK("资源包"),
        MODPACK("整合包"),
        ANY("通用"),
    }
}

/**
 * 中文关键词 → 英文搜索词。
 *
 * 为什么需要它：Modrinth 的 `/search?query=` 只做拉丁字母的全文匹配，
 * 传中文过去不会报错，只会安静地返回空列表 —— 用户看到的现象是「搜什么都搜不到」，
 * 而不是「不支持中文」。所以这里做三件事：
 *
 * 1. [looksCjk] 认出「这条查询里一个拉丁字母都没有」，界面据此给提示，不再让用户白等；
 * 2. [translate] 把常见中文叫法换成能搜到东西的英文词，界面给可点的建议；
 * 3. [popular] 在用户还没想好搜什么时，直接给几个当下热门的英文词。
 *
 * 词典只收「中文社区常用的叫法」，不做全文翻译，也不试图覆盖全部：覆盖不到的
 * 情况仍然要退化成「请用英文名」的提示，这一点写在 [modrinthNote] 里。
 */
object SearchKeywords {

    /** 纯中文查询时界面固定显示的说明，三处搜索入口共用同一句，避免各写各的。 */
    const val modrinthNote: String =
        "Modrinth 的搜索只认英文名或 slug，中文关键词发过去只会得到空结果。" +
            "下面这些是常用叫法对应的英文词，点一下就填进搜索框。"

    /**
     * 词典。
     *
     * 排序无意义（[translate] 会按匹配长度重排）；同一中文叫法可以有多条英文候选，
     * 命中时按出现顺序给出，第一条最可能搜到东西。
     */
    val all: List<SearchTerm> = listOf(
        // —— 优化与性能（问得最多的一类）——
        SearchTerm("钠", "sodium", SearchTerm.Kind.MOD),
        SearchTerm("钠扩展", "sodium-extra", SearchTerm.Kind.MOD),
        SearchTerm("钠附属", "sodium-extra", SearchTerm.Kind.MOD),
        SearchTerm("锂", "lithium", SearchTerm.Kind.MOD),
        SearchTerm("星光", "starlight", SearchTerm.Kind.MOD),
        SearchTerm("磷", "phosphor", SearchTerm.Kind.MOD),
        SearchTerm("实体剔除", "entityculling", SearchTerm.Kind.MOD),
        SearchTerm("实体渲染优化", "entityculling", SearchTerm.Kind.MOD),
        SearchTerm("内存优化", "ferritecore", SearchTerm.Kind.MOD),
        SearchTerm("区块优化", "c2me", SearchTerm.Kind.MOD),
        SearchTerm("网络优化", "krypton", SearchTerm.Kind.MOD),
        SearchTerm("启动优化", "modernfix", SearchTerm.Kind.MOD),
        SearchTerm("动态光源", "dynamic-lights", SearchTerm.Kind.MOD),
        SearchTerm("光影加载器", "iris", SearchTerm.Kind.MOD),
        SearchTerm("铱", "iris", SearchTerm.Kind.MOD),
        SearchTerm("高清修复", "optifine", SearchTerm.Kind.MOD),
        SearchTerm("内嵌副手", "embeddium", SearchTerm.Kind.MOD),
        SearchTerm("遥远地平线", "distant-horizons", SearchTerm.Kind.MOD),
        SearchTerm("内存泄漏修复", "memoryleakfix", SearchTerm.Kind.MOD),
        SearchTerm("经验球合并", "clumps", SearchTerm.Kind.MOD),
        SearchTerm("快速加载", "fastload", SearchTerm.Kind.MOD),
        SearchTerm("加速启动", "lazydfu", SearchTerm.Kind.MOD),
        SearchTerm("区块预生成", "chunky", SearchTerm.Kind.MOD),
        SearchTerm("连接纹理", "continuity", SearchTerm.Kind.MOD),
        SearchTerm("着色器兼容", "indium", SearchTerm.Kind.MOD),
        SearchTerm("钠选项", "reeses-sodium-options", SearchTerm.Kind.MOD),

        // —— 界面与体验 ——
        SearchTerm("小地图", "xaeros-minimap", SearchTerm.Kind.MOD),
        SearchTerm("旅行地图", "journeymap", SearchTerm.Kind.MOD),
        SearchTerm("模组菜单", "modmenu", SearchTerm.Kind.MOD),
        SearchTerm("模组列表", "modmenu", SearchTerm.Kind.MOD),
        SearchTerm("物品管理器", "jei", SearchTerm.Kind.MOD),
        SearchTerm("配方查看", "jei", SearchTerm.Kind.MOD),
        SearchTerm("配方管理", "rei", SearchTerm.Kind.MOD),
        SearchTerm("第一人称模型", "first-person-model", SearchTerm.Kind.MOD),
        SearchTerm("血量显示", "torohealth", SearchTerm.Kind.MOD),
        SearchTerm("护甲显示", "armor-hud", SearchTerm.Kind.MOD),
        SearchTerm("物品信息显示", "jade", SearchTerm.Kind.MOD),
        SearchTerm("提示框", "jade", SearchTerm.Kind.MOD),
        SearchTerm("苹果皮", "appleskin", SearchTerm.Kind.MOD),
        SearchTerm("饥饿显示", "appleskin", SearchTerm.Kind.MOD),
        SearchTerm("苹果皮显示", "appleskin", SearchTerm.Kind.MOD),
        SearchTerm("物品管理器2", "emi", SearchTerm.Kind.MOD),
        SearchTerm("方块信息", "wthit", SearchTerm.Kind.MOD),
        SearchTerm("小地图世界地图", "xaeros-world-map", SearchTerm.Kind.MOD),
        SearchTerm("世界地图", "xaeros-world-map", SearchTerm.Kind.MOD),
        SearchTerm("迷你hud", "minihud", SearchTerm.Kind.MOD),
        SearchTerm("f3 信息", "betterf3", SearchTerm.Kind.MOD),
        SearchTerm("按键冲突", "controlling", SearchTerm.Kind.MOD),
        SearchTerm("鼠标优化", "mouse-tweaks", SearchTerm.Kind.MOD),
        SearchTerm("平滑滚动", "smooth-scroll", SearchTerm.Kind.MOD),

        // —— 内容向大型模组 ——
        SearchTerm("机械动力", "create", SearchTerm.Kind.MOD),
        SearchTerm("应用能源", "ae2", SearchTerm.Kind.MOD),
        SearchTerm("应用能源2", "ae2", SearchTerm.Kind.MOD),
        SearchTerm("植物魔法", "botania", SearchTerm.Kind.MOD),
        SearchTerm("暮色森林", "twilight-forest", SearchTerm.Kind.MOD),
        SearchTerm("冰与火", "ice-and-fire", SearchTerm.Kind.MOD),
        SearchTerm("通用机械", "mekanism", SearchTerm.Kind.MOD),
        SearchTerm("沉浸工程", "immersive-engineering", SearchTerm.Kind.MOD),
        SearchTerm("匠魂", "tinkers-construct", SearchTerm.Kind.MOD),
        SearchTerm("匠魂3", "tinkers-construct", SearchTerm.Kind.MOD),
        SearchTerm("农夫乐事", "farmers-delight", SearchTerm.Kind.MOD),
        SearchTerm("夸克", "quark", SearchTerm.Kind.MOD),
        SearchTerm("更好战斗", "better-combat", SearchTerm.Kind.MOD),
        SearchTerm("史诗战斗", "epic-fight", SearchTerm.Kind.MOD),
        SearchTerm("动作战斗", "epic-fight", SearchTerm.Kind.MOD),
        SearchTerm("链锯剑", "epic-fight", SearchTerm.Kind.MOD),
        SearchTerm("饰品", "curios", SearchTerm.Kind.MOD),
        SearchTerm("饰品栏", "curios", SearchTerm.Kind.MOD),
        SearchTerm("帕秋莉手册", "patchouli", SearchTerm.Kind.MOD),
        SearchTerm("手册", "patchouli", SearchTerm.Kind.MOD),
        SearchTerm("村民繁殖", "guard-villagers", SearchTerm.Kind.MOD),
        SearchTerm("末地扩展", "enderscape", SearchTerm.Kind.MOD),

        // —— 存储与物流 ——
        SearchTerm("精致存储", "refined-storage", SearchTerm.Kind.MOD),
        SearchTerm("存储抽屉", "storagedrawers", SearchTerm.Kind.MOD),
        SearchTerm("抽屉", "storagedrawers", SearchTerm.Kind.MOD),
        SearchTerm("精致背包", "sophisticated-backpacks", SearchTerm.Kind.MOD),
        SearchTerm("背包", "sophisticated-backpacks", SearchTerm.Kind.MOD),
        SearchTerm("精致存储箱", "sophisticated-storage", SearchTerm.Kind.MOD),
        SearchTerm("铁箱子", "iron-chests", SearchTerm.Kind.MOD),
        SearchTerm("功能存储", "functional-storage", SearchTerm.Kind.MOD),
        SearchTerm("终端", "refined-storage", SearchTerm.Kind.MOD),

        // —— 科技与魔法 ——
        SearchTerm("热力膨胀", "thermal-expansion", SearchTerm.Kind.MOD),
        SearchTerm("工业先锋", "industrial-foregoing", SearchTerm.Kind.MOD),
        SearchTerm("末影接口", "enderio", SearchTerm.Kind.MOD),
        SearchTerm("新星魔法", "ars-nouveau", SearchTerm.Kind.MOD),
        SearchTerm("血魔法", "blood-magic", SearchTerm.Kind.MOD),
        SearchTerm("秘术", "occultism", SearchTerm.Kind.MOD),
        SearchTerm("机械动力附属", "createaddition", SearchTerm.Kind.MOD),

        // —— 冒险与生物 ——
        SearchTerm("更多生物", "alexs-mobs", SearchTerm.Kind.MOD),
        SearchTerm("亚历克斯生物", "alexs-mobs", SearchTerm.Kind.MOD),
        SearchTerm("深层世界", "the-undergarden", SearchTerm.Kind.MOD),
        SearchTerm("地形生成", "terralith", SearchTerm.Kind.MOD),
        SearchTerm("起源", "origins", SearchTerm.Kind.MOD),
        SearchTerm("简单剑", "simply-swords", SearchTerm.Kind.MOD),

        // —— 生活质量 ——
        SearchTerm("创世神", "worldedit", SearchTerm.Kind.MOD),
        SearchTerm("世界编辑", "worldedit", SearchTerm.Kind.MOD),
        SearchTerm("投影", "litematica", SearchTerm.Kind.MOD),
        SearchTerm("建筑小工具", "effortless-building-gadgets", SearchTerm.Kind.MOD),
        SearchTerm("轻松建造", "effortless-building", SearchTerm.Kind.MOD),
        SearchTerm("砍树", "fallingtree", SearchTerm.Kind.MOD),
        SearchTerm("一键砍树", "fallingtree", SearchTerm.Kind.MOD),

        // —— 前置与开发 ——
        SearchTerm("前置", "fabric-api", SearchTerm.Kind.MOD),
        SearchTerm("模组前置", "fabric-api", SearchTerm.Kind.MOD),
        SearchTerm("皮肤", "skin-shuffle", SearchTerm.Kind.MOD),
        SearchTerm("宝可梦", "cobblemon", SearchTerm.Kind.MOD),
        SearchTerm("依赖库", "fabric-api", SearchTerm.Kind.MOD),
        SearchTerm("客户端api", "fabric-api", SearchTerm.Kind.MOD),
        SearchTerm("配置库", "cloth-config", SearchTerm.Kind.MOD),

        // —— 光影 ——
        SearchTerm("互补", "complementary-reimagined", SearchTerm.Kind.SHADER),
        SearchTerm("互补光影", "complementary-reimagined", SearchTerm.Kind.SHADER),
        SearchTerm("重想体素", "rethinking-voxels", SearchTerm.Kind.SHADER),
        SearchTerm("体素光影", "rethinking-voxels", SearchTerm.Kind.SHADER),
        SearchTerm("光子", "photon", SearchTerm.Kind.SHADER),
        SearchTerm("光子光影", "photon", SearchTerm.Kind.SHADER),
        SearchTerm("极速光影", "makeup-ultra-fast", SearchTerm.Kind.SHADER),
        SearchTerm("轻量光影", "makeup-ultra-fast", SearchTerm.Kind.SHADER),
        SearchTerm("香草增强", "vanilla-plus", SearchTerm.Kind.SHADER),
        SearchTerm("原版增强", "vanilla-plus", SearchTerm.Kind.SHADER),
        // SEUS 系列在 Modrinth 上没有可检索的工程：seus / seus-shaders / seus-renewed /
        // seus-renewed-shaders 四个都实测过，hits 全空。不放一个点了什么都搜不到的候选。
        SearchTerm("光影", "shaders", SearchTerm.Kind.SHADER),
        SearchTerm("互补重想", "complementary-reimagined", SearchTerm.Kind.SHADER),
        SearchTerm("bsl", "bsl-shaders", SearchTerm.Kind.SHADER),
        SearchTerm("bsl光影", "bsl-shaders", SearchTerm.Kind.SHADER),
        SearchTerm("卡帕光影", "kappa-shader", SearchTerm.Kind.SHADER),
        SearchTerm("sildur光影", "sildurs-vibrant-shaders", SearchTerm.Kind.SHADER),
        SearchTerm("鲜艳光影", "sildurs-vibrant-shaders", SearchTerm.Kind.SHADER),

        // —— 资源包 ——
        SearchTerm("忠实", "faithful", SearchTerm.Kind.RESOURCE_PACK),
        SearchTerm("原味素材", "faithful", SearchTerm.Kind.RESOURCE_PACK),
        SearchTerm("动画材质", "fresh-animations", SearchTerm.Kind.RESOURCE_PACK),
        SearchTerm("动态材质", "fresh-animations", SearchTerm.Kind.RESOURCE_PACK),
        SearchTerm("更好的树叶", "better-leaves", SearchTerm.Kind.RESOURCE_PACK),
        SearchTerm("树叶", "better-leaves", SearchTerm.Kind.RESOURCE_PACK),
        SearchTerm("低配材质", "stay-true", SearchTerm.Kind.RESOURCE_PACK),
        SearchTerm("极简材质", "bare-bones", SearchTerm.Kind.RESOURCE_PACK),
        SearchTerm("十六倍材质", "modern-mizunos", SearchTerm.Kind.RESOURCE_PACK),
        SearchTerm("水野材质", "modern-mizunos", SearchTerm.Kind.RESOURCE_PACK),

        // —— 整合包 ——
        SearchTerm("空岛", "skyblock", SearchTerm.Kind.MODPACK),
        SearchTerm("科技整合包", "tech", SearchTerm.Kind.MODPACK),
        SearchTerm("冒险整合包", "adventure", SearchTerm.Kind.MODPACK),
        SearchTerm("空岛整合包", "skyblock", SearchTerm.Kind.MODPACK),
        SearchTerm("整合包", "modpack", SearchTerm.Kind.MODPACK),
        SearchTerm("rpg整合包", "rpg", SearchTerm.Kind.MODPACK),
        SearchTerm("角色扮演整合包", "rpg", SearchTerm.Kind.MODPACK),
    )

    /**
     * 这条查询里有没有「一个拉丁字母或数字都没有」。
     *
     * 只判 CJK 不算数：用户可能连着标点一起粘进来（「钠？！」），那也应当当纯中文处理。
     * 反过来「sodium 钠」这种混写不能提示，用户已经写了英文，直接搜就行。
     */
    fun looksCjk(query: String): Boolean {
        var hasCjk = false
        for (ch in query) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' -> return false
                isCjk(ch) -> hasCjk = true
            }
        }
        return hasCjk
    }

    /**
     * 把中文查询翻成英文候选，按「越像越靠前」排。
     *
     * 规则只有三条，不做分词：
     * 1. 整串与某条 [SearchTerm.zh] 相等 → 那条最靠前；
     * 2. 查询里包含了某条中文叫法（「我想装机械动力」）→ 按叫法长度降序（长的更具体）；
     * 3. 某条中文叫法包含了整个查询（用户只打了「机械」）→ 排在最后。
     *
     * [kind] 用于按页面过滤：[SearchTerm.Kind.ANY] 的条目任何页面都收，
     * 其余只在同类型页面出现 —— 在光影页建议 sodium 只会浪费一次点击。
     */
    fun translate(
        query: String,
        kind: SearchTerm.Kind = SearchTerm.Kind.ANY,
        limit: Int = 6,
    ): List<String> {
        val text = normalize(query)
        if (text.isEmpty() || !looksCjk(query)) return emptyList()
        val pool = all.filter { it.kind == SearchTerm.Kind.ANY || kind == SearchTerm.Kind.ANY || it.kind == kind }
        val exact = mutableListOf<SearchTerm>()
        val contains = mutableListOf<SearchTerm>()
        val partial = mutableListOf<SearchTerm>()
        for (term in pool) {
            val zh = normalize(term.zh)
            if (zh.isEmpty()) continue
            when {
                zh == text -> exact += term
                text.contains(zh) -> contains += term
                zh.contains(text) -> partial += term
            }
        }
        return (exact + contains.sortedByDescending { normalize(it.zh).length } + partial)
            .map { it.en }
            .distinct()
            .take(limit.coerceAtLeast(1))
    }

    /**
     * 这一类内容的常用英文词，给「还没想好搜什么」的人一个起点。
     *
     * 只取带明确 [SearchTerm.kind] 的条目：[SearchTerm.Kind.ANY] 是兜底词条（比如「前置」），
     * 当成热门推荐会把 sodium 和 fabric-api 混在一起，反而更难选。
     */
    fun popular(kind: SearchTerm.Kind, limit: Int = 8): List<SearchTerm> {
        if (kind == SearchTerm.Kind.ANY) return emptyList()
        return all.filter { it.kind == kind }.distinctBy { it.en }.take(limit.coerceAtLeast(1))
    }

    private fun normalize(text: String): String =
        text.filter { !it.isWhitespace() && !it.isPunctuation() }.lowercase()

    private fun Char.isPunctuation(): Boolean = this in "，。！？、；：（）【】《》「」『』…—,.!?;:()[]<>\"'\u3001"

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || // 中日韩统一表意文字
            code in 0x3400..0x4DBF || // 扩展 A
            code in 0x3040..0x30FF || // 日文假名
            code in 0xAC00..0xD7AF // 韩文音节
    }
}