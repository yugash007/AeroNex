package com.aeronex.app.command

import com.aeronex.app.command.DartText.DANDA
import com.aeronex.app.command.DartText.ELLIPSIS
import com.aeronex.app.command.DartText.END
import com.aeronex.app.command.DartText.START
import com.aeronex.app.command.DartText.WS
import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.GrammarTables.Plan

/**
 * Port of v0.2.0 `CommandSplitter`: a transcript → step clauses, on spoken connectives and punctuation. Every
 * language's connectives apply to every transcript, because speech mixes them.
 */
object CommandSplitter {
    /**
     * Clauses in spoken order: wake words and a leading "first" stripped, empty or courtesy-only clauses dropped, and
     * a lone motion verb joined to the clause after it (alone or last, it stays).
     */
    fun split(transcript: String): List<String> {
        val clauses = mutableListOf<String>()
        // Wake words go first, so "hey, drone, land" leaves no "hey" clause.
        for (part in WAKE_PHRASE.replace(transcript, ",").split(SEPARATOR)) {
            val tokens = DartText.splitWhitespace(DartText.trim(part))
            var start = 0
            var i = 0
            while (i < tokens.size) {
                val length = connectiveAt(tokens, i)
                if (length == 0) {
                    i++
                    continue
                }
                clauses += tokens.subList(start, i).joinToString(" ")
                i += length
                start = i
            }
            clauses += tokens.subList(start, tokens.size).joinToString(" ")
        }
        val spoken = clauses.map(::clean).filter(::hasContent)
        val kept = spoken.filterIndexed { i, clause ->
            !only(Plan.COURTESY, clause) && !(i + 1 < spoken.size && only(Plan.ORDINALS, clause))
        }
        if (kept.isEmpty()) return kept
        val first = FIRST_WORD.replaceFirst(kept.first(), "")
        val ordered = listOfNotNull(first.takeIf(::hasContent)) + kept.drop(1)
        val joined = mutableListOf<String>()
        var verbs = ""
        for ((i, clause) in ordered.withIndex()) {
            if (i + 1 < ordered.size && only(Plan.MOTION_VERBS, clause)) {
                verbs += "$clause "
            } else {
                joined += verbs + clause
                verbs = ""
            }
        }
        return joined
    }

    private fun hasContent(clause: String) = CONTENT.containsMatchIn(clause)

    /** Every word of [clause] is in [vocabulary]. */
    private fun only(vocabulary: Set<String>, clause: String) =
        NON_WORD.split(clause.lowercase()).filter { it.isNotEmpty() }.all { it in vocabulary }

    /**
     * Token count of the connective starting at [i], or 0. An "and" between two numerals ("two and a half", "3 and
     * 5") or before a fraction ("2 meters and a half") is part of the number.
     */
    private fun connectiveAt(tokens: List<String>, i: Int): Int {
        for (connective in Plan.CONNECTIVES) {
            val words = connective.split(' ')
            val end = i + words.size
            if (end > tokens.size || tokens.subList(i, end).joinToString(" ") { word(it) } != connective) continue
            val following = if (end < tokens.size) word(tokens[end]) else ""
            // "फिर से" is "again": a connective that is a context filler before the next word is no connective.
            if (Plan.CONTEXT_FILLERS.any { it.word == words.last() && following in it.before }) continue
            if (connective == NEXT && following == TO) continue // "next to"
            // PROVISIONAL (2026-10-11), needs native-speaker review: before a direction directly followed by a
            // move verb, "और" is "further" ("2 मीटर और आगे जाओ"), not "and".
            if (connective == HINDI_AND && following in HINDI_DIRECTIONS && end + 1 < tokens.size &&
                word(tokens[end + 1]) in HINDI_MOVE_VERBS
            ) return 0
            val next = if (following == ARTICLE) end + 1 else end
            val inNumber = connective == AND && next < tokens.size &&
                (word(tokens[next]) in Plan.SCALE_WORDS || i > 0 && numeral(tokens[i - 1]) && numeral(tokens[next]))
            return if (inNumber) 0 else words.size
        }
        return 0
    }

    private fun word(token: String) = EDGE_SYMBOLS.replace(token.lowercase(), "")

    private fun numeral(token: String) = word(token).let { NUMERIC.matches(it) || it in Plan.NUMBER_WORDS || it in Plan.SCALE_WORDS }

    // Trailing sentence punctuation only: a leading "-" may be a minus sign.
    private fun clean(clause: String) =
        DartText.trim(TRAILING.replaceFirst(LEADING_DRONE.replaceFirst(DartText.trim(clause), ""), ""))

    private const val AND = "and"
    private const val ARTICLE = "a"
    private const val NEXT = "next"
    private const val TO = "to"
    private const val HINDI_AND = "और"
    private val HINDI_DIRECTIONS = GrammarTables.lexicon("hi").directions.values.flatten().toSet()
    private val HINDI_MOVE_VERBS = GrammarTables.lexicon("hi").moveVerbs.toSet()

    /** "hey drone" anywhere ends the clause before it; a bare "drone" opening a clause is dropped by [clean]. */
    private val WAKE_PHRASE = Regex(
        START + "(?:" + DartText.alternation(Plan.WAKE_GREETINGS) + ")[$WS,:;.!?$ELLIPSIS$DANDA]+(?:" +
            DartText.alternation(Plan.WAKE_NAMES) + ")" + END,
        RegexOption.IGNORE_CASE,
    )

    /**
     * `,` `;`, and `.` `!` `?` `…` `।` before whitespace or the end, or glued between letters ("meters.land"); `/`
     * between words of two or more letters ("meters/land", not "m/s" or "meters/second"). Never between digits:
     * "1,000", "2.5", "2/5", "2. 5" and "2 . 5" stay one number. v0.2.0's `(?<=\p{Nd}\s*\.)` is unbounded, which
     * ICU and java.util.regex refuse, so "a `.` after a digit and spaces, before spaces and a digit" is spelled out.
     */
    private val SEPARATOR = Regex(
        """(?<!\p{Nd})[,;]|[,;](?!\p{Nd})|""" +
            """(?<!\p{Nd})[!?$ELLIPSIS$DANDA](?=[$WS]|\z)|""" +
            """(?<!\p{Nd})\.(?<!\p{Nd}[$WS]{1,1000}\.)(?=[$WS]|\z)|""" +
            """(?<!\p{Nd})\.(?<=\p{Nd}[$WS]{1,1000}\.)(?=[$WS]|\z)(?![$WS]*\p{Nd})|""" +
            """[.!?$ELLIPSIS$DANDA](?=[$WS]*\z|[$WS]+[^$WS\p{Nd}])|""" +
            """(?<=[\p{L}\p{M}])[.!?$ELLIPSIS$DANDA]+(?=[\p{L}\p{M}])|""" +
            """(?<=[\p{L}\p{M}]{2})/+(?=[\p{L}\p{M}]{2})(?!""" + DartText.alternation(Plan.SLASH_UNITS) + ")",
        RegexOption.IGNORE_CASE,
    )

    /** "First" opening the first clause only orders it, so it is dropped there. */
    private val FIRST_WORD = Regex(
        "^(?:" + DartText.alternation(Plan.FIRST_WORDS) + """)(?:[$WS:]+|\z)""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_DRONE = Regex(
        "^(?:" + DartText.alternation(Plan.WAKE_NAMES) + """)(?:[$WS,:.!?;$ELLIPSIS]+|\z)""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING = Regex("""[$WS.!?$ELLIPSIS$DANDA]+\z""")
    private val CONTENT = Regex("""[\p{L}\p{N}]""")
    private val NON_WORD = Regex("""[^\p{L}\p{M}\p{N}]+""")
    private val EDGE_SYMBOLS = Regex("""^[\p{P}\p{S}]+|[\p{P}\p{S}]+\z""")
    private val NUMERIC = Regex("""[\p{Nd}.,]+""")
}
