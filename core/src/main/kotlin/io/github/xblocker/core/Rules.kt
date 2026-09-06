package io.github.xblocker.core

import java.util.Locale
import java.util.regex.Pattern

data class Rule(val text: String, val category: String)
data class RejectedRule(val text: String, val reason: String)
data class FilterSettings(
    val enabled: Boolean = true,
    val cloudEnabled: Boolean = true,
    val onlyReplies: Boolean = true,
    val checkNames: Boolean = true,
    val blockPromoted: Boolean = true,
    val disabledCategories: Set<String> = setOf("仇恨用语"),
    val customRules: String = "",
    val whitelist: Set<String> = emptySet(),
)

object RuleParser {
    private val header = Regex("^#(?:\\s*\\[([^]]+)\\]|\\s+(\\S+.*))$")
    fun clean(text: String): String = buildString(text.length) {
        text.codePoints().forEach { cp ->
            // Default-ignorable characters used to evade literal matching; preserve whitespace.
            if (Character.getType(cp) != Character.FORMAT.toInt() && cp !in 0xFE00..0xFE0F &&
                cp !in 0xE0100..0xE01EF && cp !in 0x180B..0x180F &&
                cp !in listOf(0x034F, 0x115F, 0x1160, 0x3164, 0xFFA0)) appendCodePoint(cp)
        }
    }
    fun parse(text: String): List<Rule> {
        var category = "常规屏蔽词"
        return buildList {
            text.lineSequence().forEach { raw ->
                val line = clean(raw).trim().removePrefix("\uFEFF")
                val match = header.matchEntire(line)
                when {
                    line.isBlank() || line == "#" -> Unit
                    match != null -> category = match.groupValues.drop(1).first { it.isNotBlank() }.trim()
                    else -> add(Rule(line, category))
                }
            }
        }.distinct()
    }
    fun handle(value: String): String = value.trim().removePrefix("@").lowercase(Locale.ROOT)
}

data class Tweet(val id: String, val text: String, val name: String = "", val handle: String = "", val isReply: Boolean = false)
data class Match(val reason: String, val rule: String, val category: String)

/** Java Pattern is bounded by a shared character-access budget for each tweet. */
private class Budget(val deadline: Long = System.nanoTime() + 20_000_000L, var remaining: Int = 250_000)
private class BudgetExceeded : RuntimeException(null, null, false, false)
private class BoundedText(private val text: String, private val budget: Budget) : CharSequence {
    override val length: Int get() = text.length
    override fun get(index: Int): Char {
        if (--budget.remaining < 0 || (budget.remaining and 1023 == 0 && System.nanoTime() > budget.deadline)) throw BudgetExceeded()
        return text[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = BoundedText(text.substring(startIndex, endIndex), budget)
    override fun toString(): String = text
}

class RuleEngine(val settings: FilterSettings, cloudText: String) {
    private data class Compiled(val rule: Rule, val literal: String?, val pattern: Pattern?)
    private val compiled = mutableListOf<Compiled>()
    val rejected = mutableListOf<RejectedRule>()
    val categories = RuleParser.parse(cloudText).groupingBy { it.category }.eachCount()
    val count: Int get() = compiled.size
    private val whitelist = settings.whitelist.map(RuleParser::handle).toSet()

    init {
        val cloud = if (settings.cloudEnabled) RuleParser.parse(cloudText).filter { it.category !in settings.disabledCategories } else emptyList()
        val custom = RuleParser.parse(settings.customRules).map { it.copy(category = "自定义") }
        (cloud + custom).distinctBy { it.text }.take(15_000).forEach { rule ->
            try {
                require(rule.text.length <= 1000) { "规则超过 1000 字符" }
                val end = rule.text.lastIndexOf('/')
                if (rule.text.startsWith('/') && end > 0) {
                    val flags = rule.text.substring(end + 1)
                    require(flags.all { it in "imgysu" }) { "不支持的 JavaScript 正则标志" }
                    var options = 0
                    if ('i' in flags) options = options or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                    if ('m' in flags) options = options or Pattern.MULTILINE
                    if ('s' in flags) options = options or Pattern.DOTALL
                    val body = rule.text.substring(1, end)
                        .replace("\\u{", "\\x{")
                    // Unsupported JS Unicode properties are reported, never treated as plain words.
                    compiled += Compiled(rule, null, Pattern.compile(body, options))
                } else compiled += Compiled(rule, rule.text.lowercase(Locale.ROOT), null)
            } catch (e: IllegalArgumentException) {
                rejected += RejectedRule(rule.text, e.message?.lineSequence()?.firstOrNull() ?: "无效正则")
            }
        }
    }

    fun match(tweet: Tweet): Match? {
        if (!settings.enabled || RuleParser.handle(tweet.handle) in whitelist || (settings.onlyReplies && !tweet.isReply)) return null
        val fields = buildList {
            add("正文" to RuleParser.clean(tweet.text).take(32_768))
            if (settings.checkNames) {
                add("昵称" to RuleParser.clean(tweet.name).take(512))
                add("用户名" to tweet.handle.take(128))
            }
        }
        val budget = Budget()
        for ((field, text) in fields) {
            val lower = text.lowercase(Locale.ROOT)
            val bounded = BoundedText(text, budget)
            for (entry in compiled) {
                val matches = if (entry.literal != null) lower.contains(entry.literal) else try {
                    entry.pattern!!.matcher(bounded).find()
                } catch (_: BudgetExceeded) { false } catch (_: StackOverflowError) { false }
                if (matches) return Match(field, entry.rule.text, entry.rule.category)
            }
        }
        return null
    }
}
