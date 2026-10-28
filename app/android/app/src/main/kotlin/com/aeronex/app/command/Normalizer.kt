package com.aeronex.app.command

import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.Lexicon
import com.aeronex.app.grammar.Numeral
import com.aeronex.app.grammar.NumeralKind
import java.text.Normalizer as Unicode

/**
 * The runtime normaliser of spec 4.8, pure JVM. Every table comes from [GrammarTables].
 *
 * - [clean]: Unicode NFC (so a precomposed nukta letter such as U+095E equals letter + nukta), no ZWJ/ZWNJ, no stray
 *   nukta and no orphan combining mark at the start of a word (CTC hypotheses can start with a dependent vowel sign).
 * - [loanwords]: the native-script English command words of every lexicon become their native form (టేక్ ఆఫ్ →
 *   టేకాఫ్, मीटर्स → मीटर); a loan with no native form becomes its Latin spelling (स्टेटस → status). [latin] and
 *   [loans] are the two-way map (డ్రోన్ ↔ drone style).
 * - [number]: the per-language number tables (spec 7.4), digits of every script included.
 * - [languageCode]: "te-IN", "te" or "Telugu" → "te".
 */
object Normalizer {
    /** [clean], then [loanwords]: what the parsers read. */
    fun normalize(text: String): String = loanwords(clean(text))

    fun clean(text: String): String {
        val nfc = Unicode.normalize(text, Unicode.Form.NFC)
        val out = StringBuilder(nfc.length)
        var previous = -1 // the last code point kept, -1 at the start
        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)
            val keep = when {
                cp == ZWNJ || cp == ZWJ -> false
                cp in NUKTAS -> previous >= 0 && Character.getType(previous) == Character.OTHER_LETTER.toInt()
                isMark(cp) -> previous >= 0 && (Character.isLetter(previous) || isMark(previous))
                else -> true
            }
            if (keep) {
                out.appendCodePoint(cp)
                previous = cp
            }
        }
        return Unicode.normalize(out, Unicode.Form.NFC)
    }

    fun loanwords(text: String): String = if (LOANS.isEmpty()) text else LOAN_PATTERN.replace(text) { match ->
        LOANS.getValue(DartText.collapseWhitespace(match.value)).let { it.native ?: it.latin }
    }

    /** The Latin spelling of a native-script loanword (డ్రోన్-style two-way map), or null. */
    fun latin(loan: String): String? = LOANS[clean(loan)]?.latin

    /** [code]'s loanwords for a Latin command word ("take off" → టేక్ ఆఫ్ in te). */
    fun loans(latin: String, code: String): List<String> =
        GrammarTables.lexicon(code).loanwords.filter { it.latin == latin }.map { it.loan }

    /**
     * The number [phrase] names in [code]'s grammar (spec 7.4): a numeral of the lexicon (RULE, EXTRA, LOAN,
     * FRACTION: the halves and en decimals, read since 0.4.0, or REJECT) or digits of any script ("3", "2.5", "౩").
     * Null for anything else: numbers are slots and match exactly, never fuzzily. [SpokenNumbers] composes spoken
     * decimals of any length.
     */
    fun number(phrase: String, code: String): Numeral? {
        val key = DartText.collapseWhitespace(DartText.trim(clean(phrase).lowercase()))
        NUMERALS.getValue(code)[key]?.let { return it }
        val digits = asciiDigits(key)
        return if (DIGITS.matches(digits)) Numeral(key, digits.toDouble(), NumeralKind.RULE) else null
    }

    /** Native digits of every lexicon's script as ASCII digits. */
    fun asciiDigits(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) out.append(DIGIT_ZEROS.firstOrNull { c.code in it until it + 10 }?.let { '0' + (c.code - it) } ?: c)
        return out.toString()
    }

    /** The lexicon code of a selected language ("te-IN", "te", "telugu"), or null when there is none. */
    fun languageCode(language: String): String? {
        val key = DartText.trim(language).lowercase()
        val code = key.substringBefore('-').substringBefore('_')
        if (code in GrammarTables.LANGUAGES) return code
        return GrammarTables.lexicons.values.firstOrNull { it.name.lowercase() == key }?.code
    }

    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    /** Devanagari, Bengali, Gurmukhi, Gujarati, Oriya, Telugu and Kannada nukta. */
    private val NUKTAS = setOf(0x093C, 0x09BC, 0x0A3C, 0x0ABC, 0x0B3C, 0x0C3C, 0x0CBC)
    private val DIGITS = Regex("""[0-9]+(?:\.[0-9]+)?""")
    private val DIGIT_ZEROS = GrammarTables.lexicons.values.map { it.digitZero }.filter { it != '0'.code }.distinct()

    private fun isMark(cp: Int) = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private class Loan(val latin: String, val native: String?)

    /** Every lexicon's loanwords by their cleaned spelling; scripts differ, so no two lexicons share a loan. */
    private val LOANS: Map<String, Loan> = GrammarTables.lexicons.values
        .flatMap { lexicon -> lexicon.loanwords.map { clean(it.loan) to Loan(it.latin, it.native?.let(::clean)) } }
        .toMap()

    /** A whole loanword (a multi-word one with any whitespace between its words), never part of a word. */
    private val LOAN_PATTERN = Regex(
        """(?<![\p{L}\p{M}\p{N}])(?:""" +
            LOANS.keys.sortedByDescending { it.length }.joinToString("|") { loan ->
                loan.split(' ').joinToString("[${DartText.WS}]+") { Regex.escape(it) }
            } + """)(?![\p{L}\p{M}\p{N}])""",
    )

    private val NUMERALS: Map<String, Map<String, Numeral>> = GrammarTables.lexicons.mapValues { (_, lexicon) ->
        numerals(lexicon)
    }

    /** The lexicon's numerals plus the rule tables' words, by cleaned phrase; a listed numeral wins. */
    private fun numerals(lexicon: Lexicon): Map<String, Numeral> {
        val table = LinkedHashMap<String, Numeral>()
        for (numeral in lexicon.numerals) table.putIfAbsent(clean(numeral.phrase), numeral)
        for ((word, value) in lexicon.ones + lexicon.tens + lexicon.numbers) {
            table.putIfAbsent(clean(word), Numeral(word, value.toDouble(), NumeralKind.RULE))
        }
        return table
    }
}
