package com.aeronex.app.command

import com.aeronex.app.command.DartText.ELLIPSIS
import com.aeronex.app.command.DartText.END
import com.aeronex.app.command.DartText.START
import com.aeronex.app.command.DartText.WORD
import com.aeronex.app.command.DartText.WS
import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.GrammarTables.Plan
import com.aeronex.app.grammar.GrammarTables.Transcript

/**
 * Port of v0.2.0 `CommandTranscript.inspect`: a conservative wording cleanup before the plan parser. It never turns
 * a transcript into an action; [Review.problem] sends it back to the operator instead.
 */
object TranscriptInspector {
    /** [normalized] is what the plan parser reads; with a [problem] nothing is parsed. */
    data class Review(val normalized: String, val problem: String? = null, val suggestion: String? = null)

    const val EMPTY = "Review or type the command first."
    const val FOREIGN_SCRIPT =
        "This result uses Arabic script while Telugu is selected. Record again or edit the transcript."
    const val MISHEARING =
        "This may be a mishearing. Apply the suggested correction only if it matches what you said."
    const val NEGATION =
        "This command contains a negation. Cancel it, or state the single action you want to preview."
    const val NEEDS_HEIGHT = "To what height? Add the altitude, for example: take off to 3 meters."

    fun inspect(text: String, language: String): Review {
        var value = DartText.collapseWhitespace(DartText.trim(text).lowercase())
        if (value.isEmpty()) return Review("", EMPTY)
        // Preserve an unexpected script for explicit review, never translate it into an action.
        for ((code, script) in FOREIGN) {
            if (language.lowercase().startsWith(code) && script.containsMatchIn(value)) {
                return Review(value, FOREIGN_SCRIPT)
            }
        }
        for ((heard, meant) in MISHEARINGS) {
            if (heard.matches(value)) return Review(value, MISHEARING, meant)
        }
        // The wake word may end the utterance ("Hey drone."): that is empty, not a command.
        value = WAKE.replaceFirst(value, "")
        value = TAKE_OFF.replace(value, "take off")
        for ((spelling, word) in SPELLINGS) value = spelling.replace(value) { word }
        value = SPOKEN_METRES.replace(value) { m ->
            "${m.groupValues[1]}${Transcript.NUMBERS.getValue(m.groupValues[2])} ${m.groupValues[3]}"
        }
        value = DartText.trim(TRAILING.replace(value, ""))
        if (value.isEmpty()) return Review("", EMPTY)
        if (value in Transcript.HOLD_ALIASES) value = "hold"
        if (negatedWording(value)) return Review(value, NEGATION)
        if (value in Transcript.BARE_TAKEOFF) return Review(value, NEEDS_HEIGHT)
        return Review(value)
    }

    /**
     * Any negation of any lexicon in [text]: the inspector's own, every lexicon's negators, negator suffixes and
     * grammar negators (te negative imperatives such as వెళ్ళకు), and the plan-level ones. The n-best margin and the
     * CTC matcher refuse on it; [inspect] keeps the v0.2.0 set, so its messages stay those of the golden vectors.
     */
    fun negated(text: String): Boolean {
        val value = Normalizer.clean(text).lowercase()
        if (negatedWording(value)) return true
        val tokens = RuleIntentEngine.tokens(value)
        val words = WORDS.split(value).filter { it.isNotEmpty() }
        if (words.any { it in Plan.NEGATION_WORDS }) return true
        return GrammarTables.lexicons.values.any { lexicon ->
            tokens.any { token -> token in lexicon.negators || lexicon.negatorSuffixes.any { token.endsWith(it) } } ||
                lexicon.grammarNegators.any { RuleIntentEngine.removeSubsequence(tokens, RuleIntentEngine.tokens(it)) != null }
        }
    }

    /** The v0.2.0 negation test on an inspected (lower-case) [value]. */
    private fun negatedWording(value: String) =
        ENGLISH_NEGATION.containsMatchIn(value) || FUSED.containsMatchIn(value) ||
            LETTERS_ONLY.split(value).any { it in Transcript.NEGATORS }

    private val FOREIGN: Map<String, Regex> = Transcript.FOREIGN_SCRIPTS.mapValues { (_, ranges) ->
        Regex(ranges.joinToString("", "[", "]") { "\\x{%X}-\\x{%X}".format(it.first, it.last) })
    }
    private val MISHEARINGS: Map<Regex, String> = Transcript.MISHEARINGS.entries.associate { (heard, meant) ->
        Regex("^" + Regex.escape(heard) + """[.!?]*\z""") to meant
    }
    private val WAKE = Regex(
        "^(?:(?:" + DartText.alternation(Plan.WAKE_GREETINGS) + ")[$WS,]+)?(?:" +
            DartText.alternation(Plan.WAKE_NAMES) + """)(?:[$WS,:.!?;$ELLIPSIS]+|\z)""",
    )
    private val TAKE_OFF = Regex("""${START}take[$WS\-\x{2010}\x{2011}\x{2013}]*off$END""")
    private val SPELLINGS: List<Pair<Regex, String>> = Transcript.SPELLING.map { (spelling, word) ->
        Regex(START + Regex.escape(spelling) + END) to word
    }
    private val SPOKEN_METRES = Regex(
        "(^|$START(?:" + DartText.alternation(Transcript.NUMBER_PRECEDERS) + ")[$WS]+)(" +
            DartText.alternation(Transcript.NUMBERS.keys) + ")[$WS]+(meters?)$END",
    )
    private val TRAILING = Regex("""[$WS.,!?;:$ELLIPSIS]+\z""")
    private val ENGLISH_NEGATION = Regex(
        START + "(?:" + DartText.alternation(Transcript.NEGATION_WORDS) + "|$WORD*n[" +
            Transcript.NEGATION_APOSTROPHES.joinToString("") + "]t)" + END,
    )

    /** Longer Indic negators may be fused onto the verb (Malayalam അരുത് → -രുത്). */
    private val FUSED = Regex(DartText.alternation(Transcript.FUSED_NEGATORS))

    /** Whole words only: मत or ना also occur inside ordinary words. */
    private val LETTERS_ONLY = Regex("""[^\p{L}\p{M}\x{200C}\x{200D}]+""")
    private val WORDS = Regex("""[^\p{L}\p{M}\p{N}]+""")
}
