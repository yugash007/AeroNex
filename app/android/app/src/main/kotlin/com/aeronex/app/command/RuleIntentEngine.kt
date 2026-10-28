package com.aeronex.app.command

import com.aeronex.app.command.DartText.DASHES
import com.aeronex.app.command.DartText.WS
import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.Lexicon

/**
 * Port of v0.2.0 `rule_intent_engine.dart`: one clause and one rule vocabulary (en, hi or te) → one command, or an
 * [IntentException]. Input that does not map to exactly one command is refused rather than guessed: negated or
 * compound utterances, non-metre units, spoken minus signs and number forms the tables would truncate. The tables
 * are the lexicons' rule tables ([Lexicon.ruleVocabulary]); the English ones apply to every language as in v0.2.0.
 */
internal object RuleIntentEngine {
    open class IntentException(message: String) : Exception(message)

    /** The clause names no command at all (v0.2.0 handed only this case to its AI fallback). */
    class NotRecognized(message: String) : IntentException(message)

    /** No rule vocabulary for this language (ta, kn, ml). */
    class Unsupported(message: String) : IntentException(message)

    /** A rule command id (STOP, TAKEOFF, LAND, HOVER or [MOVE]) with a move's canonical direction and distance. */
    data class Intent(val command: String, val direction: String? = null, val distance: Double? = null)

    /** A matched number and the tokens it consumed. */
    class NumberMatch(val value: Double, val consumed: List<String>)

    const val MOVE = "MOVE"
    private const val MAX_DISTANCE_M = 500.0

    /** "hundred" and "one hundred": the one scale word the English tables compose. */
    private const val HUNDRED = "hundred"

    private val en = GrammarTables.lexicon("en")

    fun parse(transcript: String, code: String): Intent {
        if (DartText.trim(transcript).isEmpty()) throw IntentException("empty transcript")
        val vocab = vocabulary(code)
        val words = tokens(transcript)

        val suffixes = vocab.negatorSuffixes + en.negatorSuffixes
        if (containsAny(words, vocab.negators + en.negators) || words.any { word -> suffixes.any { word.endsWith(it) } }) {
            throw IntentException("negated command in: '$transcript'")
        }

        // One hit per distinct command ("hold position" and "hold" are one HOVER).
        val hits = vocab.commands.filter { entry -> entry.phrases.any { removeSubsequence(words, tokens(it)) != null } }
        if (hits.size > 1) throw IntentException("more than one command in: '$transcript'")
        if (hits.isNotEmpty()) {
            val entry = hits.single()
            // Two of verb, direction and distance make a second (MOVE) request; one alone is filler, so "stop
            // moving" stays STOP. Strip the command's own phrases first: "रुक जाओ" contains the verb "जाओ".
            val others = stripAll(words, entry.phrases)
            val moveParts = listOf(
                containsAny(others, vocab.moveVerbs),
                containsAny(others, vocab.directions.values.flatten()),
                others.any { ASCII_DIGIT.containsMatchIn(normalizeDigits(it)) || it in numeralWords(vocab) },
            ).count { it }
            if (moveParts >= 2) throw IntentException("more than one command in: '$transcript'")
            return Intent(entry.command)
        }

        vocab.moveVerbs.firstOrNull { removeSubsequence(words, tokens(it)) != null }
            ?: throw NotRecognized("no motion verb found in: '$transcript'")

        var direction: String? = null
        for ((canonical, forms) in vocab.directions) {
            if (forms.any { removeSubsequence(words, tokens(it)) != null }) {
                if (direction != null) throw IntentException("more than one direction in: '$transcript'")
                direction = canonical
            }
        }
        direction ?: throw IntentException("no direction found in: '$transcript'")

        val number = checkedNumber(transcript, vocab, words)
            ?: throw IntentException("no distance found in: '$transcript'")
        if (!(number.value > 0 && number.value <= MAX_DISTANCE_M)) {
            throw IntentException("distance_m must satisfy 0 < d <= $MAX_DISTANCE_M, got ${number.value}")
        }
        return Intent(MOVE, direction, number.value)
    }

    /**
     * The explicit metre value in [transcript] ("take off to 3 m"), or null when it has no number with a metre unit.
     * Applies the move distance checks, so a negative value, a non-metre unit or a truncated number form throws;
     * range checks are the caller's.
     */
    fun explicitMetres(transcript: String, code: String): Double? {
        val vocab = vocabulary(code)
        val number = checkedNumber(transcript, vocab, tokens(transcript)) ?: return null
        return if (containsAny(unitWords(transcript), vocab.units + en.units + en.unitAbbreviations)) number.value else null
    }

    /** Whitespace-separated, edge-punctuation-stripped, lower-case tokens; "twenty-three" is two tokens. */
    fun tokens(text: String): List<String> =
        DartText.splitWhitespace(splitHyphens(text).lowercase()).map(::stripEdge).filter { it.isNotEmpty() }

    /** [tokens] without the first contiguous occurrence of [sub]; null if absent. */
    fun removeSubsequence(tokens: List<String>, sub: List<String>): List<String>? {
        for (i in 0..tokens.size - sub.size) {
            if (sub.indices.all { tokens[i + it] == sub[it] }) return tokens.subList(0, i) + tokens.subList(i + sub.size, tokens.size)
        }
        return null
    }

    /** Devanagari and Telugu digits as ASCII (the rule languages' scripts). */
    fun normalizeDigits(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) out.append(RULE_DIGIT_ZEROS.firstOrNull { c.code in it until it + 10 }?.let { '0' + (c.code - it) } ?: c)
        return out.toString()
    }

    private fun vocabulary(code: String): Lexicon =
        GrammarTables.lexicons[code]?.takeIf { it.ruleVocabulary } ?: throw Unsupported("language '$code' is not yet supported")

    /** The words of [vocab]'s number tables, scale words included: all must be consumed by the one matched number. */
    private fun numeralWords(vocab: Lexicon): Set<String> = NUMERAL_WORDS.getValue(vocab.code)

    /** Sign, unit and number-form checks shared by move distances and [explicitMetres]; null when there is no number. */
    private fun checkedNumber(transcript: String, vocab: Lexicon, words: List<String>): NumberMatch? {
        if (containsAny(words, vocab.minusWords + en.minusWords) ||
            NEGATIVE_SIGN.containsMatchIn(normalizeDigits(transcript))
        ) throw IntentException("negative distance in: '$transcript'")
        if (containsAny(unitWords(transcript), vocab.otherUnits + en.otherUnits)) {
            throw IntentException("unsupported distance unit (metres only) in: '$transcript'")
        }
        val number = matchNumber(transcript, vocab) ?: return null
        val leftover = removeSubsequence(words, number.consumed) ?: words
        // "two hundred", "दो सौ": the matched number alone would truncate it.
        if (leftover.any { it in numeralWords(vocab) }) throw IntentException("unsupported number form in: '$transcript'")
        return number
    }

    /** Tokens with digits split off a glued unit ("50cm", "50-cm" → "cm"). */
    private fun unitWords(transcript: String) = tokens(normalizeDigits(transcript)).map { GLUED_NUMBER.replaceFirst(it, "") }

    private fun matchNumber(text: String, vocab: Lexicon): NumberMatch? {
        val normalized = normalizeDigits(text)
        // ".5" would read as 5 and "1½" as 1: never drop part of a number.
        if (BARE_FRACTION.containsMatchIn(normalized) || NUMBER_SYMBOL.containsMatchIn(normalized)) {
            throw IntentException("unsupported number form in: '$text'")
        }
        val digits = DIGIT_NUMBER.findAll(normalized).toList()
        // "1,000", "1e3", "5-10", "10 then 20": never guess which part counts.
        if (digits.size > 1) throw IntentException("unsupported number form in: '$text'")
        digits.firstOrNull()?.let { digit ->
            val raw = digit.value
            val value = if ('.' in raw) raw.toDouble() else raw.toLongOrNull()?.toDouble() ?: raw.toDouble()
            // Consume the original-script text so native digits leave no residue.
            return NumberMatch(value, listOf(text.substring(digit.range)))
        }
        val words = tokens(text)
        if (vocab.ones.isNotEmpty()) {
            for ((i, word) in words.withIndex()) {
                val next = words.getOrNull(i + 1)
                vocab.ones[word]?.let { value ->
                    return if (value == 1 && next == HUNDRED) NumberMatch(100.0, listOf(word, HUNDRED))
                    else NumberMatch(value.toDouble(), listOf(word))
                }
                vocab.tens[word]?.let { value ->
                    val unit = next?.let { vocab.ones[it] }
                    return if (unit != null && unit < 10) NumberMatch((value + unit).toDouble(), listOf(word, next))
                    else NumberMatch(value.toDouble(), listOf(word))
                }
                if (word == HUNDRED) return NumberMatch(100.0, listOf(HUNDRED))
            }
        } else {
            for ((i, word) in words.withIndex()) {
                val value = vocab.numbers[word] ?: continue
                val next = words.getOrNull(i + 1)
                val unit = next?.let { vocab.units19[it] }
                return if (value in vocab.tensValues && unit != null) NumberMatch((value + unit).toDouble(), listOf(word, next))
                else NumberMatch(value.toDouble(), listOf(word))
            }
        }
        return null
    }

    private fun splitHyphens(text: String): String {
        val out = StringBuilder(text.length)
        for ((i, c) in text.withIndex()) {
            val between = c == '-' && i > 0 && i < text.length - 1 && text[i - 1].isAsciiLetter() && text[i + 1].isAsciiLetter()
            out.append(if (between) ' ' else c)
        }
        return out.toString()
    }

    private fun Char.isAsciiLetter() = this in 'A'..'Z' || this in 'a'..'z'

    /** Edge punctuation: ASCII punctuation and symbols, whitespace controls and the Indic and typographic marks. */
    private fun stripEdge(token: String): String {
        var start = 0
        var end = token.length
        while (start < end && isEdge(token[start])) start++
        while (end > start && isEdge(token[end - 1])) end--
        return token.substring(start, end)
    }

    private fun isEdge(c: Char) = c.code in 0x21..0x2F || c.code in 0x3A..0x40 || c.code in 0x5B..0x60 ||
        c.code in 0x7B..0x7E || c.code in EDGE

    /** Space, tab, line feed, carriage return, the dandas, curly quotes, en and em dash, ellipsis. */
    private val EDGE = setOf(0x20, 0x09, 0x0A, 0x0D, 0x964, 0x965, 0x2018, 0x2019, 0x201C, 0x201D, 0x2013, 0x2014, 0x2026)

    /** True when any form occurs as a contiguous run of whole tokens. */
    private fun containsAny(tokens: List<String>, forms: List<String>) =
        forms.any { removeSubsequence(tokens, tokens(it)) != null }

    /** Every occurrence of every phrase removed, longest phrases first. */
    private fun stripAll(tokens: List<String>, phrases: List<String>): List<String> {
        var out = tokens
        for (phrase in phrases.sortedByDescending { tokens(it).size }) {
            val sub = tokens(phrase)
            while (true) out = removeSubsequence(out, sub) ?: break
        }
        return out
    }

    private val NEGATIVE_SIGN = Regex("""(?<![0-9])[$DASHES][$WS]*[0-9]""")
    private val GLUED_NUMBER = Regex("""^[0-9.,]+[$DASHES]?""")
    private val BARE_FRACTION = Regex("""(?<![0-9])[.,][0-9]""")
    private val NUMBER_SYMBOL = Regex("""[\p{No}\p{Nl}]""")
    private val DIGIT_NUMBER = Regex("""-?[0-9]+(?:\.[0-9]+)?""")
    private val ASCII_DIGIT = Regex("[0-9]")
    private val RULE_DIGIT_ZEROS = GrammarTables.RULE_LANGUAGES.map { GrammarTables.lexicon(it).digitZero }.filter { it != '0'.code }
    private val NUMERAL_WORDS: Map<String, Set<String>> = GrammarTables.RULE_LANGUAGES.associateWith { code ->
        GrammarTables.lexicon(code).let { it.ones.keys + it.tens.keys + it.numbers.keys + it.scaleWords }
    }
}
