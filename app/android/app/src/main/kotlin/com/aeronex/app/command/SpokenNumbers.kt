package com.aeronex.app.command

import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.NumeralKind
import java.math.BigDecimal

/**
 * Spoken numbers as digits (grammar/README.md "Spoken numbers"): the idea of Python `word2number`, done natively
 * from [GrammarTables], so the app needs no Python.
 *
 * - "<whole> point <digit words>": "two point five" = 2.5, "twenty three point five" = 23.5, "point five" = 0.5;
 * - "<whole> and a half": "two and a half" = 2.5;
 * - the listed halves: "half a" metre, hi डेढ़ = 1.5, te రెండున్నర = 2.5 ... One of value 0.5 (half, आधा, అర)
 *   counts only right before a metre word, and none ends an "and a half" with no number ("2 meters and a half"
 *   stays as v0.2.0 refused it).
 *
 * Whole numbers stay words unless asked for ([digits] `integers`): the rules read them already, and v0.2.0 messages
 * quote them. Nothing is guessed: a phrase that is not exactly one of these forms is left as it is, and the parser
 * then refuses its scale words ("point", "half") as before. Ranges are the validator's.
 */
object SpokenNumbers {
    /** A text with spoken numbers as digits, and the languages whose tables read them ("डेढ़" → hi). */
    class Digits(val text: String, val codes: Set<String>)

    /** [text] with every spoken decimal and half of [codes] (with [integers], every whole number too) as digits. */
    fun digits(text: String, codes: Collection<String> = GrammarTables.RULE_LANGUAGES, integers: Boolean = false): String =
        read(text, codes, integers).text

    /** [digits], with the languages that read each number: the step parser refuses one read across vocabularies. */
    fun read(text: String, codes: Collection<String> = GrammarTables.RULE_LANGUAGES, integers: Boolean = false): Digits {
        val tables = codes.map(TABLES::getValue)
        val words = words(text)
        val read = linkedSetOf<String>()
        val out = StringBuilder()
        var copied = 0
        var i = 0
        while (i < words.size) {
            val found = tables.firstNotNullOfOrNull { it.read(words, i) }
            if (found == null || (found.whole && !integers)) {
                i += found?.length ?: 1
                continue
            }
            val first = words[i]
            val last = words[i + found.length - 1]
            out.append(text, copied, first.coreStart).append(plain(found.value))
            copied = last.coreEnd
            read += found.code
            i += found.length
        }
        return Digits(out.append(text, copied, text.length).toString(), read)
    }

    /** The value of [phrase] when all of it is one spoken number of [code] (whole numbers included), else null. */
    fun value(phrase: String, code: String): Double? {
        val words = words(phrase)
        val found = TABLES.getValue(code).read(words, 0, needsUnit = false) ?: return null
        return if (found.length == words.size) found.value.toDouble() else null
    }

    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private fun words(text: String) = WORD.findAll(text).map { Word(it.range.first, it.value) }.toList()

    /** One whitespace-separated word; [key] is its lower-case core, [coreStart] until [coreEnd] in the text. */
    private class Word(start: Int, token: String) {
        val coreStart: Int
        val coreEnd: Int
        val key: String

        init {
            var s = 0
            var e = token.length
            while (s < e && !isCore(token[s])) s++
            while (e > s && !isCore(token[e - 1])) e--
            coreStart = start + s
            coreEnd = start + e
            key = Normalizer.clean(token.substring(s, e)).lowercase()
        }

        private fun isCore(c: Char) = Character.isLetterOrDigit(c) || Character.getType(c).let {
            it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
        } || c.code == 0x200C || c.code == 0x200D
    }

    /** A spoken number of [length] words of [code] at the read position; [whole] for a plain whole number. */
    private class Found(val value: BigDecimal, val length: Int, val whole: Boolean, val code: String)

    /** One language's tables, by lower-case cleaned word lists. */
    private class Table(private val code: String) {
        private val lexicon = GrammarTables.lexicon(code)

        /** RULE and EXTRA whole numbers (not the REJECT scale phrases), longest first. */
        private val wholes: List<Pair<List<String>, Long>> = phrases(lexicon.numerals
            .filter { (it.kind == NumeralKind.RULE || it.kind == NumeralKind.EXTRA) && it.value % 1.0 == 0.0 }
            .map { it.phrase to it.value.toLong() } +
            (lexicon.ones + lexicon.tens + lexicon.numbers).filterKeys { it !in lexicon.scaleWords }
                .map { (word, value) -> word to value.toLong() })
        private val digitWords: Map<String, Long> = wholes.filter { (words, value) -> words.size == 1 && value in 0..9 }
            .associate { (words, value) -> words.single() to value }
        private val points: List<List<String>> = lexicon.decimalPoints.map(::split)
        private val halves: List<List<String>> = lexicon.andHalf.map(::split).sortedByDescending { it.size }

        /**
         * The listed halves (half, "half a", डेढ़, అర ...), longest first. Phrases with a decimal point or an "and a
         * half" form ("point five", "one and a half") are composed by [read] instead, so a word before them counts.
         */
        private val fractions: List<Pair<List<String>, BigDecimal>> = lexicon.numerals
            .filter { it.kind == NumeralKind.FRACTION }
            .map { split(it.phrase) to BigDecimal.valueOf(it.value) }
            .filter { (phrase, _) -> phrase.none { word -> points.any { word in it } || halves.any { word == it.first() } } }
            .sortedByDescending { it.first.size }

        fun read(words: List<Word>, i: Int, needsUnit: Boolean = true): Found? {
            whole(words, i)?.let { (length, value) ->
                decimal(words, i + length)?.let { (digits, fraction) ->
                    return Found(BigDecimal("$value.$fraction"), length + digits, whole = false, code)
                }
                halves.firstOrNull { matches(words, i + length, it) }?.let { half ->
                    return Found(BigDecimal.valueOf(value).add(HALF), length + half.size, whole = false, code)
                }
                return Found(BigDecimal.valueOf(value), length, whole = true, code)
            }
            // "point five" alone, but never "one hundred point five" or "two point fifteen".
            if (i == 0 || !isNumberWord(words[i - 1].key)) {
                decimal(words, i)?.let { (digits, fraction) -> return Found(BigDecimal("0.$fraction"), digits, whole = false, code) }
            }
            for ((phrase, value) in fractions) {
                if (!matches(words, i, phrase) || endsAndHalf(words, i, phrase)) continue
                if (value.compareTo(HALF) == 0 && needsUnit && words.getOrNull(i + phrase.size)?.key !in METRE_WORDS) continue
                return Found(value, phrase.size, whole = false, code)
            }
            return null
        }

        /** [phrase] at [i] ends an "and a half" form without its number ("2 meters and a half"): not read alone. */
        private fun endsAndHalf(words: List<Word>, i: Int, phrase: List<String>) = halves.any { form ->
            (1 until form.size).any { cut ->
                form.subList(cut, form.size) == phrase && i >= cut && matches(words, i - cut, form.subList(0, cut))
            }
        }

        /** The longest whole number at [i]: (words, value). */
        private fun whole(words: List<Word>, i: Int): Pair<Int, Long>? =
            wholes.firstOrNull { (phrase, _) -> matches(words, i, phrase) }?.let { (phrase, value) -> phrase.size to value }

        /** A decimal point at [i] and one or more digit words after it: (words read, the digits). */
        private fun decimal(words: List<Word>, i: Int): Pair<Int, String>? {
            val point = points.firstOrNull { matches(words, i, it) } ?: return null
            var j = i + point.size
            val digits = StringBuilder()
            while (j < words.size) digits.append(digitWords[words[j].key] ?: break).also { j++ }
            return if (digits.isEmpty()) null else j - i to digits.toString()
        }

        private fun isNumberWord(key: String) = key in digitWords || wholes.any { key in it.first } ||
            key in GrammarTables.Plan.SCALE_WORDS || ANY_DIGIT.containsMatchIn(key)

        private fun matches(words: List<Word>, i: Int, phrase: List<String>) =
            i + phrase.size <= words.size && phrase.indices.all { words[i + it].key == phrase[it] }

        private fun phrases(pairs: List<Pair<String, Long>>) =
            pairs.map { (phrase, value) -> split(phrase) to value }.distinctBy { it.first }.sortedByDescending { it.first.size }

        private fun split(phrase: String) = Normalizer.clean(phrase).lowercase().split(' ').filter { it.isNotEmpty() }
    }

    private val HALF = BigDecimal("0.5")
    private val WORD = Regex("[^${DartText.WS}]+")
    private val ANY_DIGIT = Regex("""\p{Nd}""")

    /** Metre words of every lexicon: a bare half counts only before one ("half a meter", "आधा मीटर"). */
    private val METRE_WORDS: Set<String> = GrammarTables.lexicons.values
        .flatMap { it.units + it.unitAbbreviations + it.classes.getValue("unit") }
        .map { Normalizer.clean(it).lowercase() }.toSet()

    private val TABLES: Map<String, Table> = GrammarTables.LANGUAGES.associateWith(::Table)
}
