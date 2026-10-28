package com.aeronex.app.command

import com.aeronex.app.grammar.GrammarTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0.4.0 natural phrasings through the whole parser (grammar/README.md "Spoken numbers" and "Natural
 * phrasing"): spoken decimals and halves, direction verbs and comparatives, sideways phrasing. Every safety
 * refusal keeps its exact message.
 */
class NaturalPhrasingTest {
    private fun outcome(text: String, language: String = "en-IN"): String = when (val r = PlanParser.parseText(text, language)) {
        is ParseResult.Accepted -> r.plan.steps.joinToString("; ") { it.command.toString() }
        is ParseResult.Rejected -> "ERROR " + r.message
    }

    private fun move(direction: String, distance: Number) = "move_relative({direction: $direction, distance_m: $distance})"

    private fun check(cases: List<Pair<String, String>>, language: String = "en-IN") {
        val wrong = cases.mapNotNull { (text, want) -> outcome(text, language).takeIf { it != want }?.let { "$text\n  want $want\n  got  $it" } }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test fun spokenDecimalsAndHalves() = check(listOf(
        "move three meters forward" to move("forward", 3),
        "Move Three Meters Forward." to move("forward", 3),
        "move two point five meters left" to move("left", 2.5),
        "Move Two Point Five Meters Left." to move("left", 2.5),
        "move forward two and a half meters" to move("forward", 2.5),
        "move forward one and a half meters" to move("forward", 1.5),
        "move forward two and half meters" to move("forward", 2.5),
        "move forward half a meter" to move("forward", 0.5),
        "move forward half a metre" to move("forward", 0.5),
        "move left one point five meters" to move("left", 1.5),
        "move forward point five meters" to move("forward", 0.5),
        "go up nine point nine meters" to move("up", 9.9),
        "take off to two point five meters" to "takeoff({altitude_m: 2.5})",
        "take off to one and a half meters then move forward half a meter and land" to
            "takeoff({altitude_m: 1.5}); ${move("forward", 0.5)}; land({})",
    ))

    @Test fun directionVerbsComparativesAndSideways() = check(listOf(
        "climb two meters" to move("up", 2),
        "Climb two meters!" to move("up", 2),
        "ascend two meters" to move("up", 2),
        "rise two meters" to move("up", 2),
        "go higher two meters" to move("up", 2),
        "fly higher by 2 m" to move("up", 2),
        "climb up 2 meters" to move("up", 2),
        "climb by three meters" to move("up", 3),
        "descend two meters" to move("down", 2),
        "drop two meters" to move("down", 2),
        "lower two meters" to move("down", 2),
        "lower the drone two meters" to move("down", 2),
        "go lower two meters" to move("down", 2),
        "descend down one and a half meters" to move("down", 1.5),
        "advance two meters" to move("forward", 2),
        "ahead two meters" to move("forward", 2),
        "move ahead two meters" to move("forward", 2),
        "reverse two meters" to move("backward", 2),
        "retreat two meters" to move("backward", 2),
        "back up two meters" to move("backward", 2),
        "move 3 meters to the left" to move("left", 3),
        "go towards the right 2 meters" to move("right", 2),
        "go up by 2 meters" to move("up", 2),
        "go up by two meters" to move("up", 2),
        "take off to 3 meters then climb two point five meters and then land" to
            "takeoff({altitude_m: 3}); ${move("up", 2.5)}; land({})",
    ))

    @Test fun rangeNegationSignAndUnitRefusalsKeepTheirMessages() = check(listOf(
        "move forward twenty three meters" to
            "ERROR Step 1 (\"move forward twenty three meters\"): Specify a direction and distance >0–10 m.",
        "climb twenty three meters" to "ERROR Step 1 (\"climb twenty three meters\"): Specify a direction and distance >0–10 m.",
        "move forward ten point five meters" to
            "ERROR Step 1 (\"move forward ten point five meters\"): Specify a direction and distance >0–10 m.",
        "take off to twelve point five meters" to
            "ERROR Step 1 (\"take off to twelve point five meters\"): Specify a takeoff altitude of 1–10 m.",
        "don't climb two meters" to "ERROR ${TranscriptInspector.NEGATION}",
        "do not move forward two point five meters" to "ERROR ${TranscriptInspector.NEGATION}",
        "climb minus two meters" to "ERROR Step 1 (\"climb minus two meters\"): distances and heights cannot be negative.",
        "move forward minus two point five meters" to
            "ERROR Step 1 (\"move forward minus two point five meters\"): distances and heights cannot be negative.",
        "move forward -2.5 meters" to "ERROR Step 1 (\"move forward -2.5 meters\"): distances and heights cannot be negative.",
        "climb two feet" to "ERROR Step 1 (\"climb two feet\"): use metres, for example: 3 m.",
        "move forward two point five centimeters" to
            "ERROR Step 1 (\"move forward two point five centimeters\"): use metres, for example: 3 m.",
        "move forward half" to "ERROR Step 1 (\"move forward half\"): say a plain number, for example: 2.5 m.",
        "go forward 2 meters and a half" to
            "ERROR Step 1 (\"go forward 2 meters and a half\"): say a plain number, for example: 2.5 m.",
        // The inspector reads "down two meters" as "down 2 meters" (v0.2.0).
        "climb down two meters" to "ERROR Step 1 (\"climb down 2 meters\"): say one direction per step.",
        "go back up two meters" to "ERROR Step 1 (\"go back up 2 meters\"): say one direction per step.",
        "climb to 3 meters" to "ERROR Step 1 (\"climb to 3 meters\"): say how far to move, for example: move up 2 m.",
        // No number: the v0.2.0 reading stays (not understood), so the AI path of spec 2026-10-15 may still see it.
        "climb" to "ERROR ${PlanParser.notUnderstood(1, "climb")}",
        "drop the package" to "ERROR ${PlanParser.notUnderstood(1, "drop the package")}",
    ))

    @Test fun aDirectionVerbBesideAnotherDirectionIsRefusedNeverReversed() {
        // The "up" of "back up" belongs to that phrase: "climb back up" names up and backward, so it is refused.
        val oneDirection = "say one direction per step."
        check(listOf(
            "climb back up 2 meters" to "ERROR Step 1 (\"climb back up 2 meters\"): $oneDirection",
            "rise back up two meters" to "ERROR Step 1 (\"rise back up 2 meters\"): $oneDirection",
            "ascend back up 2 m" to "ERROR Step 1 (\"ascend back up 2 m\"): $oneDirection",
            "climb two meters back up" to "ERROR Step 1 (\"climb two meters back up\"): $oneDirection",
            "rise back 2 m" to "ERROR Step 1 (\"rise back 2 m\"): $oneDirection",
            "back up up 2 m" to "ERROR Step 1 (\"back up up 2 m\"): $oneDirection",
            // Two direction verbs or comparatives are two directions too, even when they agree.
            "climb rise 2 m" to "ERROR Step 1 (\"climb rise 2 m\"): $oneDirection",
            "advance ahead 2 m" to "ERROR Step 1 (\"advance ahead 2 m\"): $oneDirection",
            "go higher back up 2 m" to "ERROR Step 1 (\"go higher back up 2 m\"): $oneDirection",
            "go higher lower 2 m" to "ERROR Step 1 (\"go higher lower 2 m\"): $oneDirection",
            // The same direction once more is that direction ("climb up 2 m").
            "reverse back two meters" to move("backward", 2),
            "go higher up 2 m" to move("up", 2),
        ))
        for (text in listOf(
            "climb back up 2 meters", "rise back up two meters", "ascend back up 2 m", "climb two meters back up",
            "climb back up two point five meters", "descend back up 2 m", "climb 2 m back up", "Climb back up, 2 m.",
        )) assertTrue("$text: ${outcome(text)}", outcome(text).startsWith("ERROR"))
    }

    @Test fun aSpokenDecimalOrHalfCountsOnlyInTheVocabularyThatReadsTheStep() {
        // As whole numerals since v0.2.0 ("move तीन meters forward", "two मीटर आगे जाओ" are refused): "डेढ़" in an
        // English step, or "two point five" in a Hindi one, is never read across vocabularies.
        val plainNumber = "say a plain number, for example: 2.5 m."
        check(listOf(
            "move डेढ़ meters forward" to "ERROR Step 1 (\"move डेढ़ meters forward\"): $plainNumber",
            "move forward आधा meter" to "ERROR Step 1 (\"move forward आधा meter\"): $plainNumber",
            "take off to ढाई meters" to "ERROR Step 1 (\"take off to ढाई meters\"): $plainNumber",
            "two point five मीटर आगे जाओ" to "ERROR Step 1 (\"two point five मीटर आगे जाओ\"): $plainNumber",
            "half मीटर आगे जाओ" to "ERROR Step 1 (\"half मीटर आगे जाओ\"): $plainNumber",
        ), "hi-IN")
        check(listOf(
            "move ఒకటిన్నర meters forward" to "ERROR Step 1 (\"move ఒకటిన్నర meters forward\"): $plainNumber",
            "two and a half మీటర్లు ముందుకు వెళ్ళు" to "ERROR Step 1 (\"two and a half మీటర్లు ముందుకు వెళ్ళు\"): $plainNumber",
        ), "te-IN")
        for ((text, language) in listOf("move तीन meters forward" to "hi-IN", "two मीटर आगे जाओ" to "hi-IN")) {
            assertTrue("$text: ${outcome(text, language)}", outcome(text, language).startsWith("ERROR"))
        }
    }

    @Test fun hindiAndTeluguHalvesAreReadProvisionally() {
        check(listOf(
            "डेढ़ मीटर आगे जाओ" to move("forward", 1.5),
            "ढाई मीटर ऊपर जाओ" to move("up", 2.5),
            "आधा मीटर आगे जाओ" to move("forward", 0.5),
            "ढाई मीटर टेकऑफ करो" to "takeoff({altitude_m: 2.5})",
        ), "hi-IN")
        check(listOf(
            "ఒకటిన్నర మీటర్లు ముందుకు వెళ్ళు" to move("forward", 1.5),
            "రెండున్నర మీటర్లు పైకి వెళ్ళు" to move("up", 2.5),
            "సగం మీటర్ ముందుకు వెళ్ళు" to move("forward", 0.5),
            "అర మీటర్ ముందుకు వెళ్ళు" to move("forward", 0.5),
        ), "te-IN")
        for (word in listOf("डेढ़", "ढाई", "आधा")) assertTrue(word, word in GrammarTables.lexicon("hi").provisionalWords)
        for (word in listOf("ఒకటిన్నర", "రెండున్నర", "సగం", "అర")) assertTrue(word, word in GrammarTables.lexicon("te").provisionalWords)
    }

    @Test fun tamilKannadaAndMalayalamHalvesAreReadByTheirTemplates() {
        for (code in listOf("ta", "kn", "ml")) {
            val lexicon = GrammarTables.lexicon(code)
            val template = lexicon.templates.first { it.intent == "move" }.tokens
            val halves = lexicon.numerals.filter { it.value in setOf(0.5, 1.5, 2.5) && it.phrase in lexicon.provisionalWords }
            for (half in halves) {
                val text = template.joinToString(" ") { token ->
                    when (token) {
                        "{num}" -> half.phrase
                        "{dir}" -> lexicon.directions.getValue("forward").first()
                        "{unit}", "{verb}" -> lexicon.classes.getValue(token.trim('{', '}')).first()
                        else -> token
                    }
                }
                val distance: Number = half.value
                assertEquals("$code $text", move("forward", distance), outcome(text, "$code-IN"))
            }
        }
    }
}
