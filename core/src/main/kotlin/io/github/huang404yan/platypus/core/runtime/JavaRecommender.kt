package io.github.huang404yan.platypus.core.runtime

/**
 * 一次「这个版本该用哪份 Java」的判断结果。
 *
 * 拆出来是因为同一个问题要在四个地方回答：启动页的左栏提示、内容区的 Java 列表、
 * 实例详情页的 Java 卡、以及出错后那句解释。四处各写一遍必然走样 —— 之前就出现过
 * 启动页说「缺 Java 17」而详情页说「没有正好是 17 的运行时」，同一件事两种说法。
 */
enum class JavaVerdict {
    /** 版本文件里没写，也没能从版本号推出来 —— 只能让用户自己挑。 */
    UNKNOWN_NEED,

    /** 本机正好有一份能用的。 */
    READY,

    /** 本机没有，但要么启动器能下，要么至少该去试一下下载。 */
    CAN_DOWNLOAD,

    /** 本机没有，而官方清单里确实也没有这个主版本。 */
    NOT_AVAILABLE,
}

/**
 * 推荐结论。
 *
 * @param requiredMajor 这个版本要的主版本号；读不出来时为 null
 * @param runtime 可以直接拿来启动的那份（[JavaVerdict.READY] 时非空）
 * @param offer 官方清单里对应的那一项（[JavaVerdict.CAN_DOWNLOAD] 且清单已加载时非空）
 * @param installedMajors 本机现有的主版本号，升序去重 —— 界面用它说「你有 8、17、21」
 */
data class JavaRecommendation(
    val requiredMajor: Int? = null,
    val runtime: JavaRuntime? = null,
    val offer: JavaRuntimeOffer? = null,
    val installedMajors: List<Int> = emptyList(),
    val verdict: JavaVerdict = JavaVerdict.UNKNOWN_NEED,
) {
    val ready: Boolean get() = verdict == JavaVerdict.READY
}

/**
 * 按游戏版本挑 Java：**本机已有的优先，没有再看能不能下**。
 *
 * 形参照 HMCL 的「Java 全生命周期」（探测 → 按游戏版本推荐 → 缺就下载 → 校验），
 * 但只做到「推荐」这一段：下载与校验早就在 [JavaRuntimeService.ensureRuntime] 里了，
 * 这一层只负责回答「现在该用哪一份、没有的话下一步干什么」。
 *
 * 两条刻意的规则：
 * - **只认主版本完全相等**（沿用 [findExactMajor]）。1.16.5 的 Forge 在 Java 16+ 上会直接崩，
 *   「差一点也能凑合」在这里是不成立的。
 * - **同为该主版本时托管的那份优先**。系统装的 JDK 会被上游更新、也可能被别的程序卸掉；
 *   启动器自己下的那份删得掉、也不会被牵连。用户仍可在列表里改选。
 */
object JavaRecommender {

    fun recommend(
        requiredMajor: Int?,
        runtimes: List<JavaRuntime>,
        offers: List<JavaRuntimeOffer> = emptyList(),
    ): JavaRecommendation {
        val majors = runtimes.map { it.majorVersion }.filter { it > 0 }.distinct().sorted()
        if (requiredMajor == null || requiredMajor <= 0) {
            return JavaRecommendation(installedMajors = majors, verdict = JavaVerdict.UNKNOWN_NEED)
        }

        val exact = runtimes
            .filter { it.majorVersion == requiredMajor }
            .minByOrNull { if (it.managed) 0 else 1 }
        if (exact != null) {
            return JavaRecommendation(
                requiredMajor = requiredMajor,
                runtime = exact,
                installedMajors = majors,
                verdict = JavaVerdict.READY,
            )
        }

        val offer = offers
            .filter { it.majorVersion == requiredMajor }
            .minByOrNull { if (it.installed) 0 else 1 }
        if (offer != null) {
            // 清单说这份已经装好了，但探测没扫到 —— 托管目录里的东西不会被系统 PATH 带出来。
            // 这时路径就是权威，直接合成一份可用的运行时，省掉一轮「下一遍同样的文件」。
            val installedAt = offer.installedPath
            if (offer.installed && !installedAt.isNullOrBlank()) {
                return JavaRecommendation(
                    requiredMajor = requiredMajor,
                    runtime = JavaRuntime(path = installedAt, majorVersion = requiredMajor, managed = true),
                    offer = offer,
                    installedMajors = majors,
                    verdict = JavaVerdict.READY,
                )
            }
            return JavaRecommendation(
                requiredMajor = requiredMajor,
                offer = offer,
                installedMajors = majors,
                verdict = JavaVerdict.CAN_DOWNLOAD,
            )
        }

        // 清单没加载（启动页与详情页根本不拉清单）时不能断言「官方也没有」，
        // 那是把「我不知道」说成「不存在」。这种情况仍算能下 —— ensureRuntime 自己会去找。
        val verdict = if (offers.isEmpty()) JavaVerdict.CAN_DOWNLOAD else JavaVerdict.NOT_AVAILABLE
        return JavaRecommendation(requiredMajor = requiredMajor, installedMajors = majors, verdict = verdict)
    }

    /**
     * 给界面的一行话。
     *
     * 所有说法都锚在「这个版本要 Java N」上，不写「当前未配置运行时」这种不知道在说什么的句子；
     * 缺的时候一定带上下一步该点哪儿。
     */
    fun describe(recommendation: JavaRecommendation): String {
        val major = recommendation.requiredMajor
        return when (recommendation.verdict) {
            JavaVerdict.UNKNOWN_NEED ->
                "读不出这个版本要哪个 Java。可以手动挑一份，或者先启动一次看看报错。"

            JavaVerdict.READY ->
                "这个版本要 Java " + major + "，本机已经有" +
                    (recommendation.runtime?.let { if (it.managed) "（启动器下载的那份）" else "" } ?: "") + "。"

            JavaVerdict.CAN_DOWNLOAD ->
                "这个版本要 Java " + major + "，本机没有。启动器可以下一份到自己的 java/ 里，" +
                    "不会动系统里现有的 Java。"

            JavaVerdict.NOT_AVAILABLE ->
                "这个版本要 Java " + major + "，本机没有，官方清单里也没有这一版。" +
                    (if (recommendation.installedMajors.isEmpty()) {
                        "可能是断网，也可能官方没为当前平台提供。"
                    } else {
                        "本机现有的是 Java " + recommendation.installedMajors.joinToString("、") + "。"
                    }) + "自己装一份 Java " + major + "，再回来在列表里选。"
        }
    }
}
