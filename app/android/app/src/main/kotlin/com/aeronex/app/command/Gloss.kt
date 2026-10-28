package com.aeronex.app.command

import com.aeronex.app.command.DartText.WS
import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.GrammarTables.Plan
import com.aeronex.app.grammar.Lexicon
import com.aeronex.app.grammar.NumeralKind

/**
 * The 0.4.0 lexicon gloss (spec "Translation"): the hi, te, ta, kn and ml command words of an utterance in English, so
 * the LFM planner reads English. Deterministic and dictionary-based, every table from [GrammarTables]; no neural MT.
 *
 * - Spoken numbers of those languages become digits ([SpokenNumbers], native digits too), the Hindi light verb
 *   "कर दो" stays a verb (never the number two), loanwords become their native form ([Normalizer.loanwords]).
 * - Command phrases, directions, move verbs, metre words, direction verbs and comparatives, negators, minus words,
 *   connectives and the altitude words become their English words, longest phrase first; harmless native fillers of
 *   the rule tables ("please", "now", "here", "drone") are dropped.
 * - Latin-script words are kept as they are (English, or a mix such as "take off to 3 m फिर आगे 2 मीटर").
 * - Any other native-script word is never guessed: it is kept in [Result.text] and listed in [Result.unknown], so the
 *   session can refuse to plan from a partial gloss (a word no table has may be a negation, such as Tamil "பண்ணாதே").
 */
object Gloss {
    /** [text]: the utterance with its native command words in English; [unknown]: native words no table reads. */
    class Result(val text: String, val unknown: List<String>) {
        /** Every native-script word was read. */
        val complete: Boolean get() = unknown.isEmpty()
    }

    fun english(text: String): Result {
        val normalized = LIGHT_VERB.replace(Normalizer.normalize(text)) { Plan.LIGHT_VERB_REPLACEMENT }
        val numbered = Normalizer.asciiDigits(SpokenNumbers.digits(normalized, NATIVE, integers = true))
        val tokens = DartText.splitWhitespace(DartText.trim(numbered)).filter { it.isNotEmpty() }.map(::Token)
        val out = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val phrase = if (token.key.isEmpty()) null else PHRASES.firstOrNull { (words, _) -> matches(tokens, i, words) }
            val length = phrase?.first?.size ?: 1
            val english = when {
                token.key.isEmpty() -> ""
                phrase != null -> phrase.second
                else -> word(token.core, unknown)
            }
            val punctuation = token.lead to tokens[i + length - 1].trail
            // A dropped filler or a lone mark keeps its punctuation on the word before ("जाओ करो," → "move,").
            if (english.isEmpty() && out.isNotEmpty()) out[out.lastIndex] += punctuation.first + punctuation.second
            else if (english.isNotEmpty() || punctuation.first.isNotEmpty() || punctuation.second.isNotEmpty()) {
                out += punctuation.first + english + punctuation.second
            }
            i += length
        }
        return Result(DartText.collapseWhitespace(out.joinToString(" ").replace(DANDA, ".")), unknown)
    }

    /** One word no phrase matched: digits with a glued unit ("3मीटर"), Latin as it is, else unknown. */
    private fun word(core: String, unknown: MutableList<String>): String {
        val digits = LEADING_DIGITS.find(core)?.value
        if (digits != null && digits.length < core.length) {
            val rest = core.substring(digits.length)
            WORDS[key(rest)]?.let { return "$digits $it" }
        }
        if (!native(core)) return core
        unknown += core
        return core
    }

    /** A letter of a script other than Latin (Devanagari, Telugu, Tamil, Kannada, Malayalam ...). */
    private fun native(text: String) = text.codePoints().anyMatch {
        Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
    }

    private fun matches(tokens: List<Token>, i: Int, words: List<String>) =
        i + words.size <= tokens.size && words.indices.all { tokens[i + it].key == words[it] } &&
            // A phrase ends at its last word's punctuation: "जाओ, फिर" is two phrases.
            (0 until words.size - 1).all { tokens[i + it].trail.isEmpty() && tokens[i + it + 1].lead.isEmpty() }

    /** A whitespace-separated token: [core] between its leading and trailing punctuation; [key] for the tables. */
    private class Token(raw: String) {
        val lead: String
        val core: String
        val trail: String
        val key: String

        init {
            var s = 0
            var e = raw.length
            while (s < e && !isCore(raw[s])) s++
            while (e > s && !isCore(raw[e - 1])) e--
            lead = raw.substring(0, s)
            core = raw.substring(s, e)
            trail = raw.substring(e)
            key = key(core)
        }

        private fun isCore(c: Char) = Character.isLetterOrDigit(c) || Character.getType(c).let {
            it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
        } || c.code == 0x200C || c.code == 0x200D
    }

    private fun key(phrase: String) = Normalizer.clean(phrase).lowercase()

    private fun split(phrase: String) = key(phrase).split(' ').filter { it.isNotEmpty() }

    /** The glossed languages: every lexicon but English. */
    private val NATIVE: List<String> = GrammarTables.LANGUAGES - "en"

    private val en = GrammarTables.lexicon("en")
    private const val MOVE = "move"
    private const val METERS = "meters"
    private const val THEN = "then"
    private const val NOT = "not"

    /** Native-script phrase → English ("" drops a harmless filler), longest first (built once). */
    private val PHRASES: List<Pair<List<String>, String>> by lazy {
        TABLE.entries.map { it.key to it.value }.sortedByDescending { it.first.size }
    }

    /** Single native words → English, for a word with a glued number ("3मीटर"). */
    private val WORDS: Map<String, String> by lazy { TABLE.filterKeys { it.size == 1 }.mapKeys { it.key.single() } }

    private val TABLE: Map<List<String>, String> by lazy { table() }

    private fun table(): Map<List<String>, String> {
        val table = LinkedHashMap<List<String>, String>()
        fun put(phrase: String, english: String) {
            val words = split(phrase)
            // Latin phrases are English already; the first table to name a phrase wins.
            if (words.isNotEmpty() && words.any(::native)) table.putIfAbsent(words, english)
        }
        for (code in NATIVE) {
            val lexicon = GrammarTables.lexicon(code)
            for (command in lexicon.commands) command.phrases.forEach { put(it, COMMANDS.getValue(command.command)) }
            for ((word, english) in CLASSES) lexicon.classes[word].orEmpty().forEach { put(it, english) }
            lexicon.directions.forEach { (direction, forms) -> forms.forEach { put(it, direction) } }
            directionWords(lexicon).forEach { (form, english) -> put(form, english) }
            (lexicon.moveVerbs + lexicon.classes["verb"].orEmpty()).forEach { put(it, MOVE) }
            (lexicon.units + lexicon.unitAbbreviations + lexicon.classes["unit"].orEmpty()).forEach { put(it, METERS) }
            (lexicon.negators + lexicon.grammarNegators).forEach { put(it, NOT) }
            lexicon.minusWords.forEach { put(it, en.minusWords.first()) }
            lexicon.numerals.filter { it.kind == NumeralKind.LOAN || it.kind == NumeralKind.RULE || it.kind == NumeralKind.EXTRA }
                .filter { it.value % 1.0 == 0.0 }
                .forEach { put(it.phrase, it.value.toLong().toString()) }
        }
        Plan.CONNECTIVES.forEach { put(it, THEN) }
        Plan.FIRST_WORDS.forEach { put(it, "first") }
        Plan.MOTION_VERBS.forEach { put(it, MOVE) }
        Plan.STRAIGHT_WORDS.forEach { put(it, "straight") }
        Plan.MORE_WORDS.forEach { put(it, "more") }
        HEIGHT_WORDS.forEach { (word, english) -> put(word, english) }
        // Fillers the rule tables already let any step carry; anything else stays unknown.
        (Plan.INDIC_FILLER + Plan.COURTESY + Plan.CONTEXT_FILLERS.map { it.word } + Plan.SIMPLE_WORDS +
            Plan.LAND_WORDS + Plan.TAKEOFF_WORDS + Plan.INDIC_MOVE_WORDS - Plan.SEQUENCE_WORDS).forEach { put(it, "") }
        return table
    }

    /** Direction verbs and comparatives as the English word of the same direction ("climb", "higher"). */
    private fun directionWords(lexicon: Lexicon): List<Pair<String, String>> =
        lexicon.directionVerbs.flatMap { (direction, forms) ->
            val english = en.directionVerbs[direction]?.firstOrNull() ?: "$MOVE $direction"
            forms.map { it to english }
        } + lexicon.comparatives.flatMap { (direction, forms) ->
            val english = en.comparatives[direction]?.firstOrNull() ?: direction
            forms.map { it to english }
        }

    private val COMMANDS = mapOf("STOP" to "stop", "TAKEOFF" to "take off", "LAND" to "land", "HOVER" to "hover")
    private val CLASSES = listOf("takeoff" to "take off", "land" to "land", "hold" to "hold", "stop" to "stop",
        "status" to "status")

    /** The rule tables' altitude words with their English sense; "तक" and "వరకు" name a height ("5 मीटर तक"). */
    private val HEIGHT_WORDS: List<Pair<String, String>> = Plan.ALTITUDE_WORDS.map { word ->
        word to when {
            word == "तक" || word == "వరకు" -> "to"
            word == "पर" -> "at"
            else -> "height"
        }
    }

    /** The light verbs "कर दो", "करवा दो" … mean "करो"; that "दो" is not two (as [StepParser] reads them). */
    private val LIGHT_VERB = Regex(
        """(?<![\p{L}\p{M}])(?:""" + DartText.alternation(Plan.LIGHT_VERB_STEMS) + ")[$WS]+(?:" +
            DartText.alternation(Plan.LIGHT_VERB_AUXILIARIES) + """)(?![\p{L}\p{M}])""",
    )

    private val LEADING_DIGITS = Regex("""^[0-9]+(?:\.[0-9]+)?""")
    private val DANDA = Regex("""[\x{964}\x{965}]""")
}
