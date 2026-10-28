package com.aeronex.app.command

import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.grammar.GrammarTables

/**
 * The Vosk n-best margin of spec 4.8, in Vosk's own score units. With `setMaxAlternatives(3)`, each alternative's
 * "confidence" is an utterance score of about 50-180, not 0-1 (W1, docs/testing/2026-10-12-phase0-engine-eval.md
 * section 7), so the spec's Δ = 0.1 is replaced by [DEFAULT_MARGIN_SCORE].
 *
 * Accept only when the top alternative (highest score, whatever the list order) parses to a plan and every other
 * alternative within the margin either parses to the same steps, or fails to parse without contradicting the top's
 * slots. An alternative within the margin that holds a negation, names another number or direction, or parses to
 * other steps rejects the utterance ("say again"): those are the W1 failures (negation dropped, 9 heard for 2).
 *
 * A top the rules reject keeps its own message, but only when no alternative within the margin is negated or names
 * other numbers or directions: a "not understood" top goes on to the AI path (0.4.0), so "take off to three meters
 * then step forward" must not be planned while "don't take off ..." scored just below it (audit S2).
 */
object NbestMargin {
    /**
     * Δ in score points. W1 measured the dangerous alternatives at 0.28 (te negation lost), 0.75-1.57 (hi land for
     * stop), 1.19-1.46 (hi negation dropped), 2.13 (hi 9 for 2) and 2.76 (hi other direction) below the top; 3.0
     * covers all of them (NbestMarginTest reads them from the eval). Tune on the real-speaker corpus (spec 10.2): a
     * wider margin rejects more, never accepts more.
     */
    const val DEFAULT_MARGIN_SCORE = 3.0
    const val UNSURE = "The command was not clear. Say it again."
    const val NOTHING_HEARD = "Nothing was recognised. Say the command again."

    /** One n-best entry: Vosk's `{"text", "confidence"}`. */
    data class Alternative(val text: String, val confidence: Double)

    fun decide(alternatives: List<Alternative>, language: String, marginScore: Double = DEFAULT_MARGIN_SCORE): ParseResult {
        // A score that is not a finite number cannot be ranked or compared: treat it as a disagreement.
        if (alternatives.any { !it.confidence.isFinite() }) return ParseResult.Rejected(UNSURE)
        val ranked = alternatives.sortedByDescending { it.confidence }
        val top = ranked.firstOrNull()?.takeIf { DartText.trim(it.text).isNotEmpty() }
            ?: return ParseResult.Rejected(NOTHING_HEARD)
        val parsed = PlanParser.parse(top.text, language)
        val slots = Slots.of(top.text)
        val best = parsed as? ParseResult.Accepted ?: return if (contradicted(ranked, top, slots, marginScore)) {
            ParseResult.Rejected(UNSURE)
        } else parsed
        for (other in ranked.drop(1)) {
            if (top.confidence - other.confidence > marginScore) break // sorted: the rest are further away
            if (TranscriptInspector.negated(other.text)) return ParseResult.Rejected(UNSURE)
            val agrees = when (val reading = PlanParser.parse(other.text, language)) {
                is ParseResult.Accepted -> sameSteps(reading.plan, best.plan)
                is ParseResult.Rejected -> Slots.of(other.text).agreesWith(slots)
            }
            if (!agrees) return ParseResult.Rejected(UNSURE)
        }
        return best
    }

    /** An alternative within the margin of a rejected top is negated or names other numbers or directions. */
    private fun contradicted(ranked: List<Alternative>, top: Alternative, slots: Slots, marginScore: Double): Boolean {
        for (other in ranked.drop(1)) {
            if (top.confidence - other.confidence > marginScore) break
            if (TranscriptInspector.negated(other.text) || !Slots.of(other.text).agreesWith(slots)) return true
        }
        return false
    }

    private fun sameSteps(a: FlightPlan, b: FlightPlan) = a.steps.size == b.steps.size &&
        a.steps.zip(b.steps).all { (x, y) ->
            x.command.name == y.command.name && x.command.direction == y.command.direction &&
                x.command.altitudeM == y.command.altitudeM && x.command.distanceM == y.command.distanceM
        }

    /** The numbers and canonical directions an alternative names, in order, read token by token. */
    private class Slots(val numbers: List<Double>, val directions: List<String>) {
        /** Nothing named, or the same: a missing slot is no contradiction; another value is. */
        fun agreesWith(top: Slots) = (numbers.isEmpty() || numbers == top.numbers) &&
            (directions.isEmpty() || directions == top.directions)

        companion object {
            fun of(text: String): Slots {
                val tokens = RuleIntentEngine.tokens(Normalizer.normalize(text))
                return Slots(tokens.mapNotNull(::number), tokens.mapNotNull { DIRECTIONS[it] })
            }

            /** A numeral of any lexicon, or digits, glued to a unit or not ("3m"). */
            private fun number(token: String): Double? =
                GrammarTables.LANGUAGES.firstNotNullOfOrNull { Normalizer.number(token, it)?.value }
                    ?: LEADING_DIGITS.find(Normalizer.asciiDigits(token))?.value?.toDouble()

            private val LEADING_DIGITS = Regex("""^[0-9]+(?:\.[0-9]+)?""")

            /** Every one-word direction form of every lexicon (loanwords are normalised to these). */
            private val DIRECTIONS: Map<String, String> = GrammarTables.lexicons.values.flatMap { lexicon ->
                lexicon.directions.flatMap { (canonical, forms) -> forms.map { Normalizer.clean(it).lowercase() to canonical } }
            }.filter { ' ' !in it.first }.toMap()
        }
    }
}
