package com.aeronex.app.command

import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.GrammarTables.Plan
import com.aeronex.app.grammar.Lexicon

/**
 * The 0.4.0 natural phrasings of one clause, rewritten into words the v0.2.0 rules read (grammar/README.md "Spoken
 * numbers" and "Natural phrasing"). [StepParser] reads the result; error messages still quote the clause as said.
 *
 * - Spoken decimals and halves become digits ([SpokenNumbers]): "two point five" → 2.5.
 * - Only in a clause that names a number: a direction verb with no move verb is a move in its direction ("climb 2 m"
 *   → "move up 2 m"; "climb up 2 m" → "move up 2 m"), and a comparative after a move verb is its direction ("go
 *   higher 2 m" → "go up 2 m"). Without a number the clause keeps its v0.2.0 reading ("drop the package" stays not
 *   understood). With a move verb a direction verb is left alone, so "go back up 2 m" is still two directions.
 * - A clause with two such phrases, or one beside another direction, is a [Reading.conflict] and is not rewritten:
 *   "climb back up 2 m" names up and backward (the "up" of "back up" is that phrase's), so it is never one of them.
 */
object NaturalPhrasing {
    /**
     * [text] for the rules; [numbers], the rule languages whose tables read its spoken decimals and halves; and
     * [conflict] when its direction verbs or comparatives cannot name one direction, which [StepParser] refuses.
     */
    class Reading(val text: String, val numbers: Set<String>, val conflict: Boolean)

    fun read(clause: String): Reading {
        val spoken = SpokenNumbers.read(clause)
        val text = spoken.text
        val asSaid = Reading(text, spoken.codes, conflict = false)
        val words = WORD.findAll(text).toList()
        val keys = words.map { RuleIntentEngine.tokens(it.value).joinToString(" ") }
        if (keys.none { it.isNotEmpty() && (ANY_DIGIT.containsMatchIn(it) || it in Plan.NUMBER_WORDS) }) return asSaid
        val verb = keys.any { it in MOVE_VERBS }
        val found = phrasesIn(keys, if (verb) COMPARATIVES else DIRECTION_VERBS)
        if (found.isEmpty()) return asSaid
        // Directions named outside every phrase: the "up" of "back up" is no second up.
        val inside = found.flatMap { it.first }.toSet()
        val named = keys.filterIndexed { i, _ -> i !in inside }.mapNotNull(DIRECTION_OF::get).toSet()
        val (span, direction) = found.first()
        if (found.size > 1 || named.any { it != direction }) return Reading(text, spoken.codes, conflict = true)
        val replacement = listOfNotNull(MOVE.takeUnless { verb }, CANONICAL.getValue(direction).takeUnless { direction in named })
        val start = words[span.first].range.first
        val end = words[span.last].range.last + 1
        // An empty replacement ("go higher up 2 m"): drop the phrase and one space beside it.
        val rest = if (replacement.isEmpty() && end < text.length && text[end] == ' ') end + 1 else end
        return Reading(text.substring(0, start) + replacement.joinToString(" ") + text.substring(rest), spoken.codes, conflict = false)
    }

    /** The [table] phrases in [keys], longest first at each word, as (word indices, direction). */
    private fun phrasesIn(keys: List<String>, table: List<Pair<List<String>, String>>): List<Pair<IntRange, String>> {
        val found = mutableListOf<Pair<IntRange, String>>()
        var i = 0
        while (i < keys.size) {
            val match = table.firstOrNull { (phrase, _) -> matches(keys, i, phrase) }
            if (match == null) {
                i++
                continue
            }
            val (phrase, direction) = match
            found += (i until i + phrase.size) to direction
            i += phrase.size
        }
        return found
    }

    private fun matches(keys: List<String>, i: Int, phrase: List<String>) =
        i + phrase.size <= keys.size && phrase.indices.all { keys[i + it] == phrase[it] }

    private val WORD = Regex("[^${DartText.WS}]+")
    private val ANY_DIGIT = Regex("""\p{Nd}""")

    /** Move verbs of the rule languages: with one, a direction verb is left alone and a comparative is read. */
    private val MOVE_VERBS: Set<String> = GrammarTables.RULE_LANGUAGES.flatMap { GrammarTables.lexicon(it).moveVerbs }.toSet()

    private val en = GrammarTables.lexicon("en")

    /** The verb a direction verb becomes, and each direction's canonical word ("move", "up"). */
    private val MOVE = en.moveVerbs.first()
    private val CANONICAL: Map<String, String> = en.directions.mapValues { it.value.first() }

    /** Direction forms of the rule languages by canonical direction: "climb up" names up once. */
    private val DIRECTION_OF: Map<String, String> = GrammarTables.RULE_LANGUAGES.flatMap { code ->
        GrammarTables.lexicon(code).directions.flatMap { (direction, forms) -> forms.map { it.lowercase() to direction } }
    }.toMap()

    private fun phrases(table: (Lexicon) -> Map<String, List<String>>) =
        GrammarTables.RULE_LANGUAGES.flatMap { code ->
            table(GrammarTables.lexicon(code)).flatMap { (direction, forms) -> forms.map { it.lowercase().split(' ') to direction } }
        }.sortedByDescending { it.first.size }

    private val DIRECTION_VERBS = phrases { it.directionVerbs }
    private val COMPARATIVES = phrases { it.comparatives }
}
