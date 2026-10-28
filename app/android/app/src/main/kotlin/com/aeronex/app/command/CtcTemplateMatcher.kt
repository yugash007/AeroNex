package com.aeronex.app.command

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.NumeralKind
import kotlin.math.abs
import kotlin.math.min

/**
 * Spec 4.8 lexicon matching for the IndicConformer CTC path. Greedy CTC gives one hypothesis and no n-best, so the
 * margin is defined on [code]'s templates ([com.aeronex.app.grammar.Lexicon.templates]):
 * 1. normalise the hypothesis, refuse any negation, and split it into clauses ([CommandSplitter]);
 * 2. slots match exactly: a number (a numeral of the lexicon, the 0.4.0 halves such as ஒன்றரை included, or digits)
 *    and a direction must be a lexicon entry, with no fuzzy substitution and no nearest-number snapping; an unknown
 *    word in a slot rejects the clause, and a signed number or a unit symbol ("-3", "5%") rejects it with
 *    [StepParser]'s message;
 * 3. fixed words (verbs, units, particles) may be fuzzy: the word-level edit distance over the fixed words is `d1`
 *    for the best template reading and `d2` for the best reading with another intent or slot values;
 * 4. accept only if `d1 <= 1` (`d1 = 0` when the template has fewer than 3 fixed words) and `d2 - d1 >= 2`.
 *
 * The distance counts edits of fixed words only, and there are two: a fixed word is missing, or it is replaced by
 * a near miss of itself (one character edit per four, on fixed words of at least four code points). Every word of
 * the hypothesis must be read as a slot, a fixed word or such a near miss; any other word rejects the clause. So a
 * negative imperative the provisional tables lack ("3 m take off don't"), a scale word ("3 lakh metres") or a
 * multiplier ("twice") is never dropped. A word of the lexicon (another command, a number, a direction, a unit) is never a near miss, so
 * "3 metres up to land" is never read as a takeoff.
 */
class CtcTemplateMatcher(val code: String) {
    /** One template reading of a clause: [slots] in template order (Double, direction or [Refused]). */
    data class Candidate(val intent: String, val slots: List<Any>, val cost: Int, val fixedWords: Int)

    /** A numeral the grammar recognises only to refuse it (a scale word; the halves are read since 0.4.0). */
    data class Refused(val value: Double)

    private sealed interface Item
    private data class Slot(val kind: String) : Item
    private class Fixed(val forms: Set<String>) : Item
    private class Pattern(val intent: String, val items: List<Item>) {
        val fixedWords = items.count { it is Fixed }
    }

    private val lexicon = GrammarTables.lexicon(code)
    private val patterns: List<Pattern> = lexicon.templates.flatMap { template ->
        template.tokens.fold(listOf(emptyList<Item>())) { prefixes, token -> prefixes.flatMap { prefix -> options(token).map { prefix + it } } }
            .map { Pattern(template.intent, it) }
    }
    /** Direction forms and direction loanwords (via their native form) → canonical direction. */
    private val directions: Map<String, String> = buildMap {
        for ((canonical, forms) in lexicon.directions) for (form in forms) put(word(form), canonical)
        for (loan in lexicon.loanwords.filter { it.wordClass == DIR }) {
            val canonical = lexicon.directions.entries.firstOrNull { (_, forms) -> loan.native in forms }?.key ?: continue
            put(word(loan.loan), canonical)
        }
    }
    private val longestSlot = (lexicon.numerals.map { it.phrase } + directions.keys).maxOf { it.split(' ').size }
    private val known: Set<String> = buildSet {
        for (forms in lexicon.classes.values) for (form in forms) addAll(words(form))
        for (template in lexicon.templates) for (token in template.tokens) if (!token.startsWith("{")) add(word(token))
        for (phrase in lexicon.numerals.map { it.phrase } + lexicon.numbers.keys + lexicon.ones.keys + lexicon.tens.keys) addAll(words(phrase))
        for (table in listOf(lexicon.negators, lexicon.grammarNegators, lexicon.otherUnits, lexicon.minusWords, lexicon.scaleWords,
            lexicon.stopLane, lexicon.moveVerbs, lexicon.units, directions.keys.toList())) for (phrase in table) addAll(words(phrase))
        for (command in lexicon.commands) for (phrase in command.phrases) addAll(words(phrase))
    }

    fun match(hypothesis: String): ParseResult {
        val heard = when (val unk = UnkPolicy.check(Normalizer.clean(hypothesis))) {
            is UnkPolicy.Result.Reject -> return ParseResult.Rejected(unk.message)
            is UnkPolicy.Result.Pass -> DartText.trim(unk.text).lowercase()
        }
        if (heard.isEmpty()) return ParseResult.Rejected(NbestMargin.NOTHING_HEARD)
        if (TranscriptInspector.negated(heard)) return ParseResult.Rejected(TranscriptInspector.NEGATION)
        val clauses = CommandSplitter.split(heard)
        if (clauses.isEmpty()) return ParseResult.Rejected(NbestMargin.NOTHING_HEARD)
        if (clauses.size > FlightPlan.MAX_STEPS) return ParseResult.Rejected(PlanParser.TOO_MANY_STEPS)
        val steps = clauses.mapIndexed { i, clause ->
            val command = try {
                command(clause)
            } catch (e: CommandFormatException) {
                return ParseResult.Rejected("Step ${i + 1} (\"$clause\"): ${e.message}")
            } ?: return ParseResult.Rejected(PlanParser.notUnderstood(i + 1, clause))
            PlanStep(command, StepSource.RULES, clause)
        }
        return try {
            ParseResult.Accepted(FlightPlan(steps), heard)
        } catch (e: CommandFormatException) {
            ParseResult.Rejected(e.message ?: PlanParser.SAY_COMMAND)
        }
    }

    /** The clause's command, null when no template reading passes [choose]; refused numbers throw. */
    private fun command(clause: String): FlightCommand? {
        // The tokeniser strips edge punctuation, so a sign or unit symbol is refused before it can vanish ("-3" → 3).
        StepParser.rejectSignsAndSymbols(clause)
        val words = RuleIntentEngine.tokens(clause)
        val candidates = patterns.flatMap { pattern ->
            align(pattern, words).map { (slots, cost) -> Candidate(pattern.intent, slots, cost, pattern.fixedWords) }
        }
        val best = choose(candidates) ?: return null
        if (best.slots.any { it is Refused }) throw CommandFormatException(PLAIN_NUMBER)
        val numbers = best.slots.filterIsInstance<Double>().map(::number)
        val direction = best.slots.filterIsInstance<String>().firstOrNull()
        return when (best.intent) {
            "takeoff" -> FlightCommand.of(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to numbers.single()))
            "move" -> FlightCommand.of(FlightCommand.MOVE,
                mapOf(FlightCommand.DIRECTION to direction, FlightCommand.DISTANCE to numbers.single()))
            "land" -> FlightCommand.of(FlightCommand.LAND)
            // A spoken stop means stop and hold (as the rule parser reads it); the voice STOP lane is separate.
            "hold", "stop" -> FlightCommand.of(FlightCommand.HOLD)
            "status" -> FlightCommand.of(FlightCommand.STATUS)
            else -> null
        }
    }

    /** The cheapest cost (at most [MAX_COST]) of each slot reading of [words] under [pattern]. */
    private fun align(pattern: Pattern, words: List<String>): Map<List<Any>, Int> {
        val items = pattern.items
        val memo = HashMap<Int, Map<List<Any>, Int>>()
        fun from(i: Int, w: Int): Map<List<Any>, Int> = memo.getOrPut(i * (words.size + 1) + w) {
            val best = HashMap<List<Any>, Int>()
            fun offer(slot: Any?, rest: Map<List<Any>, Int>, cost: Int) {
                for ((slots, restCost) in rest) {
                    val total = restCost + cost
                    val all = if (slot == null) slots else listOf(slot) + slots
                    if (total <= MAX_COST && total < (best[all] ?: Int.MAX_VALUE)) best[all] = total
                }
            }
            if (i == items.size && w == words.size) best[emptyList()] = 0
            when (val item = items.getOrNull(i)) {
                is Slot -> for (end in w + 1..min(words.size, w + longestSlot)) {
                    slot(item.kind, words.subList(w, end).joinToString(" "))?.let { offer(it, from(i + 1, end), 0) }
                }
                is Fixed -> {
                    offer(null, from(i + 1, w), 1) // the fixed word is missing
                    if (w < words.size) substitution(item, words[w])?.let { offer(null, from(i + 1, w + 1), it) }
                }
                null -> Unit
            }
            best
        }
        return from(0, 0)
    }

    private fun slot(kind: String, phrase: String): Any? = when (kind) {
        NUM -> Normalizer.number(phrase, code)?.let { if (it.kind == NumeralKind.REJECT) Refused(it.value) else it.value }
        DIR -> directions[phrase]
        else -> null
    }

    /**
     * 0 for a form of the fixed word, 1 for a near miss of one, null otherwise (a lexicon word never stands in).
     * Only forms of [MIN_NEAR_MISS] or more code points have near misses: one edit on a shorter word is a 33-50 %
     * change (te లేక is not లే, kn ಏರದು "does not rise" is not ಏರು).
     */
    private fun substitution(item: Fixed, word: String): Int? = when {
        word in item.forms -> 0
        protected(word) -> null
        item.forms.any { form ->
            val length = form.codePointCount(0, form.length)
            length >= MIN_NEAR_MISS && editDistance(word, form) <= length / 4
        } -> 1
        else -> null
    }

    /** Words the lexicon knows, and anything with a digit: never a near miss of another word. */
    private fun protected(word: String) = word in known || DIGIT.containsMatchIn(word)

    /** A template token's readings: a slot, one fixed word (single-word class forms), or a multi-word form spelled out. */
    private fun options(token: String): List<List<Item>> {
        val name = CLASS.matchEntire(token)?.groupValues?.get(1) ?: return listOf(listOf(Fixed(setOf(word(token)))))
        if (name == NUM || name == DIR) return listOf(listOf(Slot(name)))
        val forms = lexicon.classes.getValue(name).map(::word)
        val single = forms.filter { ' ' !in it }
        return (if (single.isEmpty()) emptyList() else listOf(listOf<Item>(Fixed(single.toSet())))) +
            forms.filter { ' ' in it }.map { form -> form.split(' ').map { Fixed(setOf(it)) } }
    }

    private fun word(text: String) = DartText.collapseWhitespace(DartText.trim(Normalizer.clean(text).lowercase()))

    private fun words(text: String) = word(text).split(' ').filter { it.isNotEmpty() }

    companion object {
        /** Readings above this cost cannot change a decision (d1 <= 1 and d2 - d1 >= 2). */
        private const val MAX_COST = 3
        /** The shortest fixed word (in code points) that may be replaced by a near miss of itself. */
        private const val MIN_NEAR_MISS = 4
        private const val NUM = "num"
        private const val DIR = "dir"
        private const val PLAIN_NUMBER = "say a plain number, for example: 2.5 m."
        private val CLASS = Regex("""\{([a-z]+)\}""")
        private val DIGIT = Regex("""\p{Nd}""")

        /**
         * The spec 4.8 decision over every reading: the best (intent, slots) at cost `d1`, the best other at `d2`.
         * Null (say again) unless `d1 <= 1`, `d1 = 0` for a template of fewer than 3 fixed words, and `d2 - d1 >= 2`.
         */
        internal fun choose(candidates: List<Candidate>): Candidate? {
            val readings = candidates.groupBy { it.intent to it.slots }.values
                .map { group -> group.minWith(compareBy<Candidate> { it.cost }.thenByDescending { it.fixedWords }) }
            val best = readings.minWithOrNull(compareBy<Candidate> { it.cost }.thenByDescending { it.fixedWords }) ?: return null
            val d1 = best.cost
            val d2 = readings.filter { it !== best }.minOfOrNull { it.cost } ?: Int.MAX_VALUE
            val allowed = if (best.fixedWords < 3) 0 else 1
            return best.takeIf { d1 <= allowed && d2.toLong() - d1 >= 2 }
        }

        /** Whole numbers as Int, the rest as Double (as the rule parser gives them). */
        private fun number(value: Double): Number = if (value % 1.0 == 0.0 && abs(value) <= Int.MAX_VALUE) value.toInt() else value

        /** Levenshtein distance over code points. */
        private fun editDistance(a: String, b: String): Int {
            val x = a.codePoints().toArray()
            val y = b.codePoints().toArray()
            var previous = IntArray(y.size + 1) { it }
            for (i in 1..x.size) {
                val current = IntArray(y.size + 1)
                current[0] = i
                for (j in 1..y.size) {
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
                }
                previous = current
            }
            return previous[y.size]
        }
    }
}
