package com.aeronex.app.command

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.GrammarTables.Plan

/**
 * The flight-memory commands of the rule languages (spec 2026-10-28; grammar/README.md "Flight memory"): return home,
 * go back, retrace, go to point n and mark point n, read from each rule lexicon's memory templates
 * ([com.aeronex.app.grammar.Lexicon.templates]). A clause is one only when it is exactly a template once courtesy and
 * filler words are set aside on both sides, so "go back 3 m" stays a backward move, "land at home" stays refused and
 * a negated command ("don't go back") never matches: the rules refuse it as before. The point number is a numeral
 * of the template's language or digits; [FlightCommand] refuses one outside 1–9.
 */
object MemoryCommands {
    /** Template intent → tool. */
    private val TOOLS = mapOf(
        "return_home" to FlightCommand.RETURN_HOME, "go_back" to FlightCommand.GO_BACK, "retrace" to FlightCommand.RETRACE,
        "goto_point" to FlightCommand.GOTO_POINT, "mark_point" to FlightCommand.MARK_POINT,
    )
    private const val NUM = "{num}"

    /** Words that never decide a command: "please go home now", "can you retrace the path". */
    private val IGNORED: Set<String> = Plan.COURTESY + Plan.FILLER + Plan.INDIC_FILLER

    /** One spelled-out template: its words in order, null for the point number. */
    private class Pattern(val code: String, val tool: String, val words: List<String?>)

    private val patterns: List<Pattern> by lazy {
        GrammarTables.RULE_LANGUAGES.flatMap { code ->
            val lexicon = GrammarTables.lexicon(code)
            lexicon.templates.filter { it.intent in TOOLS }.flatMap { template ->
                template.tokens.fold(listOf(emptyList<String?>())) { prefixes, token ->
                    val options: List<List<String?>> = when {
                        token == NUM -> listOf(listOf(null))
                        token.startsWith("{") -> lexicon.classes.getValue(token.substring(1, token.length - 1)).map(::significant)
                        else -> listOf(significant(token))
                    }
                    prefixes.flatMap { prefix -> options.map { prefix + it } }
                }.map { Pattern(code, TOOLS.getValue(template.intent), it) }
            }.distinctBy { it.tool to it.words }
        }
    }

    /**
     * The flight-memory command [clause] is, or null when it is none. Throws
     * [com.aeronex.app.command.model.CommandFormatException] for a point number outside 1–9 ("go to point 12").
     */
    fun read(clause: String): FlightCommand? {
        val words = significant(clause)
        if (words.isEmpty()) return null
        for (pattern in patterns) {
            val number = match(pattern, words) ?: continue
            return if (number == NO_NUMBER) FlightCommand.of(pattern.tool)
            else FlightCommand.of(pattern.tool, mapOf(FlightCommand.POINT to number))
        }
        return null
    }

    /** The point number (or [NO_NUMBER]) when [words] are [pattern]'s, else null. */
    private fun match(pattern: Pattern, words: List<String>): Any? {
        val slot = pattern.words.indexOf(null)
        if (slot < 0) return if (pattern.words == words) NO_NUMBER else null
        val before = pattern.words.subList(0, slot)
        val after = pattern.words.subList(slot + 1, pattern.words.size)
        val length = words.size - before.size - after.size
        if (length !in 1..MAX_NUMBER_WORDS) return null
        if (words.subList(0, before.size) != before || words.subList(words.size - after.size, words.size) != after) return null
        val phrase = words.subList(before.size, before.size + length).joinToString(" ")
        val value = Normalizer.number(phrase, pattern.code)?.value ?: return null
        return if (value % 1.0 == 0.0 && value in 0.0..Int.MAX_VALUE.toDouble()) value.toInt() else value
    }

    /** Lower-case words of [text] without courtesy and filler words. */
    private fun significant(text: String): List<String> =
        RuleIntentEngine.tokens(Normalizer.clean(text)).filter { it !in IGNORED }

    /** Marks a template without a point number. */
    private val NO_NUMBER = Any()

    /** "twenty one" is the longest number phrase a point could be said with (and is refused, as any above 9). */
    private const val MAX_NUMBER_WORDS = 3
}
