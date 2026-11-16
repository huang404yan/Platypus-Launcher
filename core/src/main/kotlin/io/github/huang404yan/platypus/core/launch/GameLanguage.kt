package io.github.huang404yan.platypus.core.launch

import java.io.File
import java.util.Locale

/**
 * 进游戏用哪个语言。
 *
 * Minecraft 自己的语言是写在 `options.txt` 里的一行 `lang:zh_cn`，值形如
 * `zh_cn` / `en_us` / `zh_tw`。这一层只做三件事：把设置里存的值认出来、
 * 认不出时按系统语言猜一个、把标签翻成给人看的名字。真正写文件在 [GameOptions]。
 *
 * 为什么不在启动器里另造一套「中文/英文」的枚举：游戏认的是那一串 id，
 * 中间再加一层映射，两边迟早有一边写完另一边读不出来的情况；而且整合包自带的
 * 语言资源包也只认这些 id。
 */
object GameLanguage {

    /** 跟随系统时用的兜底语言：认不出系统语言就给它。 */
    const val FALLBACK: String = "en_us"

    /** 一个可选的语言。 */
    data class Choice(val id: String, val label: String)

    /**
     * 界面上给的那几档。
     *
     * 只列游戏自带的、装了也不会显示成一串方框的语种；排在前面的是简中，
     * 因为这份启动器的使用者绝大多数是中文用户。
     */
    val CHOICES: List<Choice> = listOf(
        Choice("zh_cn", "简体中文"),
        Choice("zh_tw", "繁體中文"),
        Choice("en_us", "English"),
        Choice("ja_jp", "日本語"),
        Choice("ko_kr", "한국어"),
        Choice("ru_ru", "Русский"),
        Choice("fr_fr", "Français"),
        Choice("de_de", "Deutsch"),
        Choice("es_es", "Español"),
        Choice("pt_br", "Português"),
    )

    /** 语言 id 给人看的名字；认不出来就原样返回那一串 id。 */
    fun labelOf(id: String): String =
        CHOICES.firstOrNull { it.id == id }?.label ?: id

    /**
     * 系统语言对应到游戏语言。
     *
     * 只看 `Locale.getDefault().language`（`zh` / `en` / `ja`…），繁体按国家/地区再分一次：
     * 台湾、香港、澳门的 `zh` 归到 `zh_tw`，其余 `zh` 一律 `zh_cn`。
     * 认不出来给 [FALLBACK]，与游戏自己找不到语言时的行为一致。
     */
    fun systemDefault(locale: Locale = Locale.getDefault()): String {
        val language = locale.language.lowercase()
        if (language == "zh") {
            val region = locale.country.uppercase()
            return if (region == "TW" || region == "HK" || region == "MO") "zh_tw" else "zh_cn"
        }
        val exact = CHOICES.firstOrNull { it.id == language + "_" + locale.country.lowercase() }
        if (exact != null) return exact.id
        return CHOICES.firstOrNull { it.id == language + "_" + language }?.id
            ?: CHOICES.firstOrNull { it.id.startsWith(language + "_") }?.id
            ?: FALLBACK
    }

    /**
     * 设置里那一格要真正写进文件的值。
     *
     * 空串 = 跟随系统（见 [systemDefault]）。用户手改过 settings.json 写成 `zh-CN`
     * 这种形状时也兜回来：把大杠换成小杠、大小写压平，认不出来还是走系统默认。
     */
    fun resolve(setting: String, locale: Locale = Locale.getDefault()): String {
        val cleaned = setting.trim().lowercase().replace('-', '_')
        if (cleaned.isEmpty()) return systemDefault(locale)
        return cleaned
    }
}

/**
 * 读写游戏目录下的 `options.txt`，只为把语言那一行摆正。
 *
 * 这个文件是游戏自己维护的：一开头一行 `version:3955`，之后一行一个键值对，
 * 顺序、注释、未知的键都属于游戏。所以这里只做「改一行、别的原样搬过去」，
 * 绝不解析成键值表再整份重写 —— 那样会把游戏新加的、我们不认识的键全丢了。
 *
 * 写盘走「同目录临时文件 + 改名」，中途断电最坏是留个 .tmp，不会留下半份文件。
 */
object GameOptions {

    /** 语言那一行的键名。 */
    const val LANG_KEY: String = "lang"

    /** `options.txt` 的位置：就在 `--gameDir` 那一层。 */
    fun fileOf(gameDir: File): File = File(gameDir, "options.txt")

    /** 读出文件里的语言；文件不在或没有这一行返回 null。 */
    fun readLang(gameDir: File): String? {
        val file = fileOf(gameDir)
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        return langIn(lines)
    }

    /**
     * 把 [GameOptions.fileOf] 里的语言改成 [lang]。
     *
     * 已经有这一行就替换那一行（保留它原来的位置）；没有就追加到末尾；
     * 连文件都没有就新建一份只含这一行的 —— 游戏启动时会把缺的键补上。
     *
     * @return 这次是否真的动了文件；值本来就对时返回 false（不白写一次盘，
     *   也便于测试区分「没动」与「动完还是这个值」）
     */
    fun writeLang(gameDir: File, lang: String): Boolean {
        val wanted = lang.trim()
        if (wanted.isEmpty()) return false
        val file = fileOf(gameDir)
        val existing = runCatching { if (file.isFile) file.readLines() else emptyList() }
            .getOrDefault(emptyList())
        if (langIn(existing) == wanted) return false
        val updated = replaceLang(existing, wanted)
        val dir = file.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val temp = File(dir, "options.txt.platypus-tmp")
        val text = updated.joinToString("\n", postfix = "\n")
        runCatching { temp.writeText(text) }.getOrElse { return false }
        // 改名覆盖：File.renameTo 在 Windows 上目标已存在会失败，先删再改。
        if (file.exists() && !file.delete()) {
            temp.delete()
            return false
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            return false
        }
        return true
    }

    /** 从若干行里挑语言值；键名不区分大小写，冒号后可能带空格。 */
    internal fun langIn(lines: List<String>): String? {
        for (line in lines) {
            val index = line.indexOf(':')
            if (index <= 0) continue
            if (!line.substring(0, index).trim().equals(LANG_KEY, ignoreCase = true)) continue
            val value = line.substring(index + 1).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    /** 替换或追加 `lang:` 行，其余行按原顺序原样保留。 */
    internal fun replaceLang(lines: List<String>, lang: String): List<String> {
        val wanted = LANG_KEY + ":" + lang
        val out = ArrayList<String>(lines.size + 1)
        var replaced = false
        for (line in lines) {
            val index = line.indexOf(':')
            val isLang = index > 0 && line.substring(0, index).trim().equals(LANG_KEY, ignoreCase = true)
            if (!isLang) {
                out += line
                continue
            }
            // 同一行语言重复出现时只留第一条，别把重复行抄出去。
            if (!replaced) {
                out += wanted
                replaced = true
            }
        }
        if (!replaced) out += wanted
        return out
    }
}
