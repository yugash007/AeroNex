package com.aeronex.app.command

import com.aeronex.app.command.NbestMargin.Alternative
import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.safety.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Vosk n-best margin (spec 4.8) in score units. The alternative lists are the W1 host measurements
 * (docs/testing/2026-10-12-phase0-engine-eval.json, `vosk.models[].clips[].nbest`): Vosk "confidence" is an
 * utterance score of about 50-180, and the dangerous alternatives sat 0.3-2.8 points below the top.
 */
@Suppress("UNCHECKED_CAST")
class NbestMarginTest {
    private fun decide(language: String, vararg alternatives: Pair<String, Double>, margin: Double = NbestMargin.DEFAULT_MARGIN_SCORE) =
        NbestMargin.decide(alternatives.map { (text, score) -> Alternative(text, score) }, language, margin)

    private fun steps(result: ParseResult) = (result as ParseResult.Accepted).plan.steps.map { it.command.toString() }

    private fun rejected(result: ParseResult, message: String = NbestMargin.UNSURE) {
        assertTrue("$result", result is ParseResult.Rejected)
        assertEquals(message, (result as ParseResult.Rejected).message)
    }

    /** One W1 n-best list: the clip's language and spoken reference, and Vosk's alternatives. */
    private data class Measured(val language: String, val reference: String, val alternatives: List<Alternative>)

    /** Every Vosk n-best list in the W1 eval (en, hi, te; eSpeak clips; both runs). */
    private fun measured(): List<Measured> {
        val eval = MiniJson.parse(repoFile(EVAL).readText(Charsets.UTF_8)) as Map<String, Any?>
        return ((eval["vosk"] as Map<String, Any?>)["models"] as List<Map<String, Any?>>).flatMap { model ->
            (model["clips"] as List<Map<String, Any?>>).flatMap { clip ->
                val lists = (clip["nbest"] as Map<String, Any?>?)?.get("alternatives") as List<List<Map<String, Any?>>>?
                lists.orEmpty().map { list ->
                    Measured(model["lang"] as String, clip["ref"] as String,
                        list.map { Alternative(it["text"] as String, (it["confidence"] as Number).toDouble()) })
                }
            }
        }
    }

    private fun plan(result: ParseResult) = (result as? ParseResult.Accepted)?.let(::steps)

    /** [negated] is [plain] plus negation words of [language] only (the negation dropped). */
    private fun dropsNegation(negated: String, plain: String, language: String): Boolean {
        val negators = GrammarTables.lexicon(language).negators
        fun words(text: String) = text.split(' ').filter { it.isNotEmpty() }
        return TranscriptInspector.negated(negated) && !TranscriptInspector.negated(plain) && words(plain).isNotEmpty() &&
            words(negated).filter { it !in negators } == words(plain)
    }

    @Test fun everyW1ListIsRejectedOrGivesTheSpokenPlan() {
        val lists = measured()
        assertEquals(74, lists.size)
        var accepted = 0
        for ((language, reference, alternatives) in lists) {
            val decided = plan(NbestMargin.decide(alternatives, language)) ?: continue
            accepted++
            assertEquals("$language \"$reference\": $alternatives", plan(PlanParser.parse(reference, language)), decided)
        }
        println("W1 n-best lists: ${lists.size}; accepted with the spoken plan: $accepted; rejected: ${lists.size - accepted}")
        assertTrue(accepted >= 30)
    }

    @Test fun theDefaultMarginCoversEveryDangerousGapW1Measured() {
        // A dangerous alternative reads another plan than the speaker's (another command, number or direction), or
        // is the speaker's words without their negation. Its gap is how far below the top Vosk scored it.
        val found = mutableListOf<Pair<String, Double>>()
        for ((language, reference, alternatives) in measured()) {
            val top = alternatives.maxBy { it.confidence }
            val spoken = plan(PlanParser.parse(reference, language))
            for (other in alternatives.filter { it !== top }) {
                val read = plan(PlanParser.parse(other.text, language))
                val gap = top.confidence - other.confidence
                val name = "$language \"${other.text}\" for \"$reference\""
                if (spoken != null && read != null && read != spoken) {
                    found += name to gap
                    // The speaker's plan on top, the other reading at its measured gap: the margin, not the parser,
                    // rejects it (a margin just below the gap lets the top through).
                    val pair = listOf(Alternative(reference, 100.0), Alternative(other.text, 100.0 - gap))
                    rejected(NbestMargin.decide(pair, language))
                    assertEquals(name, spoken, plan(NbestMargin.decide(pair, language, gap - 0.01)))
                } else if (dropsNegation(reference, other.text, language)) {
                    found += name to gap
                    val pair = listOf(Alternative(other.text, 100.0), Alternative(reference, 100.0 - gap))
                    assertTrue(name, NbestMargin.decide(pair, language) is ParseResult.Rejected)
                }
            }
        }
        println("W1 dangerous alternatives: " + found.joinToString("; ") { (name, gap) -> "$name %.2f below".format(gap) })
        // hi: another direction at 2.76, 9 for 2 at 2.13, land for stop at 0.75 and 1.57; the negation dropped at
        // 1.19 and 1.46 (hi) and 0.28 (te).
        assertEquals(7, found.size)
        assertTrue("$found", found.all { it.second < NbestMargin.DEFAULT_MARGIN_SCORE })
    }

    @Test fun aScoreThatIsNotAFiniteNumberRejects() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            rejected(decide("en-IN", "move forward 2 meters" to bad, "move forward 9 meters" to 119.0))
            rejected(decide("en-IN", "move forward 2 meters" to 120.0, "move forward 9 meters" to bad))
            rejected(decide("en-IN", "move forward 2 meters" to bad))
        }
    }

    @Test fun alternativesThatAgreeOrFailWithTheSameSlotsKeepTheTopPlan() {
        // en+f3 "go left two meters" and "take off to three meters"; hi "लैंड" with an empty second.
        assertEquals(listOf("move_relative({direction: left, distance_m: 2})"),
            steps(decide("en-IN", "go left two meters" to 156.42, "left two meters" to 156.06)))
        assertEquals(listOf("takeoff({altitude_m: 3})"), steps(decide("en-IN",
            "take off to three meters" to 163.35, "off to three meters" to 162.17, "take off three meters" to 161.72)))
        assertEquals(listOf("land({})"), steps(decide("hi-IN", "लैंड" to 54.98, "" to 54.47)))
        assertEquals(listOf("move_relative({direction: forward, distance_m: 2})"),
            steps(decide("en-IN", "move forward 2 meters" to 120.0, "go forward two meters" to 119.0)))
    }

    @Test fun anotherNumberOrDirectionWithinTheMarginRejects() {
        // hi+f3: "दो मीटर बाएं जाओ" with 9 (नौ) 2.13 points below; hi: the other direction 2.76 below.
        rejected(decide("hi-IN", "दो मीटर बाएं जाओ" to 123.53, "मीटर बाएं जाओ" to 123.39, "नौ मीटर बाएं जाओ" to 121.40))
        rejected(decide("hi-IN", "दो मीटर बाएं जाओ" to 121.01, "मीटर बाएं जाओ" to 119.59, "दो मीटर आगे जाओ" to 118.25))
        // A refused alternative still disagrees on its slot: 15 (out of range) against 5.
        rejected(decide("en-IN", "move forward five meters" to 140.0, "move forward fifteen meters" to 139.0))
        // A different command with no slot that parses: land against hold.
        rejected(decide("en-IN", "hover" to 60.0, "land" to 59.0))
    }

    @Test fun aNegationWithinTheMarginRejectsEvenWhenTheTopDroppedIt() {
        // hi: "टेकऑफ मत करो" vs "टेकऑफ करो" at 1.19, here with the negation in the second alternative.
        rejected(decide("hi-IN", "तीन मीटर टेकऑफ करो" to 98.98, "तीन मीटर टेकऑफ मत करो" to 97.79))
        rejected(decide("en-IN", "take off to three meters" to 150.0, "don't take off to three meters" to 149.0))
        // te negative imperative (no rule reads it): "don't go forward".
        rejected(decide("te-IN", "రెండు మీటర్లు ముందుకు వెళ్ళు" to 100.0, "రెండు మీటర్లు ముందుకు వెళ్ళకు" to 99.0))
    }

    @Test fun alternativesOutsideTheMarginDoNotCountAndTheMarginIsConfigurable() {
        val far = arrayOf("दो मीटर बाएं जाओ" to 123.53, "नौ मीटर बाएं जाओ" to 120.0) // 3.53 below
        assertEquals(listOf("move_relative({direction: left, distance_m: 2})"), steps(decide("hi-IN", *far)))
        rejected(decide("hi-IN", *far, margin = 4.0))
        // "मत" alone at 3.08 below the top "होवर" (hi hold clip) is outside the default margin.
        assertEquals(listOf("hold({})"), steps(decide("hi-IN", "होवर" to 61.0, "" to 59.44, "मत" to 57.92)))
    }

    @Test fun theTopIsTheHighestScoreWhateverTheOrder() {
        rejected(decide("hi-IN", "नौ मीटर बाएं जाओ" to 121.40, "दो मीटर बाएं जाओ" to 123.53))
    }

    @Test fun aTopThatDoesNotParseIsTheAnswer() {
        rejected(decide("hi-IN", "टेकऑफ मत करो" to 98.98, "टेकऑफ करो" to 97.79), TranscriptInspector.NEGATION)
        rejected(decide("te-IN", "" to 82.70, "టేకాఫ్" to 82.42), NbestMargin.NOTHING_HEARD)
        rejected(decide("en-IN"), NbestMargin.NOTHING_HEARD)
        assertTrue(decide("en-IN", "forward three meter" to 147.05) is ParseResult.Rejected) // no verb
    }

    private companion object {
        const val EVAL = "docs/testing/2026-10-12-phase0-engine-eval.json"

        /** [path] from the repository root, found above the working directory (as the golden vectors are). */
        fun repoFile(path: String): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                File(dir, path).takeIf { it.isFile }?.let { return it }
                dir = dir.parentFile
            }
            error("$path not found above ${System.getProperty("user.dir")}")
        }
    }
}
