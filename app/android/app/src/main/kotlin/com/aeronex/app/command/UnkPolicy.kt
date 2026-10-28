package com.aeronex.app.command

import com.aeronex.app.command.DartText.DANDA
import com.aeronex.app.command.DartText.ELLIPSIS
import com.aeronex.app.command.DartText.WS
import com.aeronex.app.grammar.GrammarTables

/**
 * The `[unk]` policy of spec 4.9, for the token Vosk emits for out-of-grammar speech:
 * - any `[unk]` inside a clause or between the clauses of a plan rejects the whole utterance;
 * - a leading or trailing `[unk]` is tolerated only when the rest is exactly a STOP, HOLD or status template of a
 *   lexicon (`[unk] hold position` → HOLD; `[unk] what is the [unk] fly` → rejected).
 */
object UnkPolicy {
    const val SAY_AGAIN = "Part of what you said was not recognised. Say the command again."

    sealed interface Result {
        /** [text] is what to parse: the input unchanged, or the template without its edge `[unk]`. */
        data class Pass(val text: String) : Result
        data class Reject(val message: String) : Result
    }

    fun check(text: String): Result {
        if (!UNK.containsMatchIn(text)) return Result.Pass(text)
        val tokens = DartText.splitWhitespace(DartText.trim(text)).filter { it.isNotEmpty() }
        var start = 0
        var end = tokens.size
        while (start < end && UNK_TOKEN.matches(tokens[start])) start++
        while (end > start && UNK_TOKEN.matches(tokens[end - 1])) end--
        val rest = tokens.subList(start, end).joinToString(" ")
        if (rest.isEmpty() || UNK.containsMatchIn(rest)) return Result.Reject(SAY_AGAIN)
        return if (bare(rest) in exact) Result.Pass(rest) else Result.Reject(SAY_AGAIN)
    }

    private val TOLERATED = setOf("stop", "hold", "status")
    private val CLASS = Regex("""\{([a-z]+)\}""")
    private val UNK = Regex("""\[unk\]""", RegexOption.IGNORE_CASE)
    private val UNK_TOKEN = Regex("""\[unk\][,.;:!?$ELLIPSIS$DANDA]*""", RegexOption.IGNORE_CASE)
    private val EDGES = Regex("""^[\p{P}\p{S}$WS]+|[\p{P}\p{S}$WS]+\z""")

    /** Lower case, normalised, one space between words, no edge punctuation. */
    private fun bare(text: String) = DartText.collapseWhitespace(EDGES.replace(Normalizer.normalize(text).lowercase(), ""))

    /** Every STOP, HOLD and status template instance of every lexicon (non-motion commands only). */
    private val exact: Set<String> = GrammarTables.lexicons.values.flatMap { lexicon ->
        lexicon.templates.filter { it.intent in TOLERATED }.flatMap { template ->
            template.tokens.fold(listOf("")) { prefixes, token ->
                val forms = CLASS.matchEntire(token)?.let { lexicon.classes.getValue(it.groupValues[1]) } ?: listOf(token)
                prefixes.flatMap { prefix -> forms.map { "$prefix $it" } }
            }
        }
    }.map(::bare).toSet()
}
