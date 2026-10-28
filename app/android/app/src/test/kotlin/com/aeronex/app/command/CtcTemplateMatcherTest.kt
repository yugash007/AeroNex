package com.aeronex.app.command

import com.aeronex.app.command.CtcTemplateMatcher.Candidate
import com.aeronex.app.safety.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 4.8 CTC lexicon matching: slots exact, fixed words within d1 <= 1 and a margin d2 - d1 >= 2. */
@Suppress("UNCHECKED_CAST")
class CtcTemplateMatcherTest {
    private val te = CtcTemplateMatcher("te")

    private fun steps(result: ParseResult) = (result as? ParseResult.Accepted)?.plan?.steps?.map { it.command.toString() }

    private fun one(matcher: CtcTemplateMatcher, text: String) = steps(matcher.match(text))?.single()

    private fun rejected(matcher: CtcTemplateMatcher, text: String) =
        assertTrue("$text: ${matcher.match(text)}", matcher.match(text) is ParseResult.Rejected)

    @Test fun theDecisionRuleIsDOneAtMostOneWithAMarginOfTwo() {
        fun pick(vararg candidates: Candidate) = CtcTemplateMatcher.choose(candidates.toList())
        val takeoff3 = Candidate("takeoff", listOf(3.0), cost = 1, fixedWords = 3)
        assertEquals(takeoff3, pick(takeoff3)) // nothing else in reach
        assertEquals(takeoff3, pick(takeoff3, Candidate("land", emptyList(), 3, 1)))
        assertNull(pick(takeoff3, Candidate("land", emptyList(), 2, 1))) // d2 - d1 = 1
        assertNull(pick(takeoff3, Candidate("takeoff", listOf(4.0), 2, 3))) // another slot value
        assertEquals(takeoff3, pick(takeoff3, takeoff3.copy(cost = 2))) // the same intent and slots
        assertNull(pick(Candidate("move", listOf(2.0, "forward"), 1, 2))) // fewer than 3 fixed words: d1 must be 0
        assertNull(pick(Candidate("takeoff", listOf(3.0), 2, 4))) // d1 > 1
        val land = Candidate("land", emptyList(), 0, 1)
        assertEquals(land, pick(land, Candidate("hold", emptyList(), 2, 1)))
        assertNull(pick(land, Candidate("hold", emptyList(), 1, 1)))
        assertNull(pick(land, land.copy(intent = "stop")))
        assertNull(pick())
    }

    @Test fun slotsMatchExactlyAndAreNeverSnapped() {
        assertEquals("move_relative({direction: forward, distance_m: 5})", one(te, "ఐదు మీటర్లు ముందుకు వెళ్ళు"))
        assertEquals("move_relative({direction: forward, distance_m: 6})", one(te, "ఆరు మీటర్లు ముందుకు వెళ్ళు"))
        assertEquals("move_relative({direction: left, distance_m: 2})", one(te, "ఎడమ వైపుకు రెండు మీటర్లు వెళ్ళు"))
        // An unknown word in the number or direction slot rejects the clause; no nearest number is chosen.
        rejected(te, "ఐదో మీటర్లు ముందుకు వెళ్ళు")
        rejected(te, "ఐదు మీటర్లు ముందుక వెళ్ళు")
        // Two numbers for one slot, and a number where a fixed word belongs.
        rejected(te, "రెండు మూడు మీటర్లు ముందుకు వెళ్ళు")
        rejected(te, "మూడు మీటర్లు రెండు టేకాఫ్")
        // Recognised, then refused: out of range.
        assertEquals("Step 1 (\"పదిహేను మీటర్లు ముందుకు వెళ్ళు\"): Specify a direction and distance >0–10 m.",
            (te.match("పదిహేను మీటర్లు ముందుకు వెళ్ళు") as ParseResult.Rejected).message)
        // 0.4.0 (grammar/README.md "Spoken numbers"): the halves are slot values, read provisionally (were refused).
        assertEquals("move_relative({direction: forward, distance_m: 0.5})", one(te, "అర మీటర్ ముందుకు వెళ్ళు"))
        assertEquals("move_relative({direction: up, distance_m: 2.5})", one(te, "రెండున్నర మీటర్లు పైకి వెళ్ళు"))
        // Digits, ASCII or Telugu, in case a model emits them.
        assertEquals("takeoff({altitude_m: 3})", one(te, "3 మీటర్లు టేకాఫ్"))
        assertEquals("takeoff({altitude_m: 3})", one(te, "౩ మీటర్లు టేకాఫ్"))
    }

    @Test fun fixedWordsMayBeOneEditOffOnlyInTemplatesOfThreeOrMoreFixedWords() {
        // {num} {unit} వరకు {takeoff}: three fixed words, so one near-miss (టేకాఫ్ → టేకాప్) is tolerated.
        assertEquals("takeoff({altitude_m: 3})", one(te, "మూడు మీటర్లు వరకు టేకాప్"))
        // A missing fixed word is one edit too: the unit here, the second word of టేక్ ఆఫ్ there.
        assertEquals("takeoff({altitude_m: 3})", one(te, "మూడు వరకు టేకాఫ్"))
        assertEquals("takeoff({altitude_m: 3})", one(te, "మూడు మీటర్లు టేక్"))
        // {num} {unit} {dir} {verb}: two fixed words, so the verb must be exact.
        rejected(te, "రెండు మీటర్లు ముందుకు వెళ్ళూ")
        // A word of another command never stands in for a fixed word, and a far-off word is no near miss.
        rejected(te, "మూడు మీటర్లు వరకు ల్యాండ్")
        rejected(te, "మూడు మీటర్లు వరకు ఫోటో")
        // Two edits are too many.
        rejected(te, "మూడు మీటర్లు వరక టేకాప్")
    }

    @Test fun aWordTheLexiconDoesNotKnowIsNeverSkipped() {
        // Spec 4.8/4.9: only fixed words are edited (dropped, or replaced by a near miss). Any other word rejects the
        // clause, wherever it stands: a negative imperative the provisional tables lack ("don't take off"), a scale
        // word ("3 lakh metres" is not 3 m), a multiplier ("twice"), another unit or a politeness word.
        val unknown = mapOf(
            "ta" to listOf(
                "மூன்று மீட்டர் டேக் ஆஃப் பண்ணாதே", "மூன்று மீட்டர் டேக் ஆஃப் செய்யாதே", "மூன்று லட்சம் மீட்டர் டேக் ஆஃப்",
                "மூன்று கோடி மீட்டர் டேக் ஆஃப்", "மூன்று மீட்டர் டேக் ஆஃப் வினாடி", "தயவுசெய்து மூன்று மீட்டர் டேக் ஆஃப்",
            ),
            "ml" to listOf(
                "മൂന്ന് മീറ്റർ ടേക്ക് ഓഫ് ചെയ്യണ്ട", "മൂന്ന് മീറ്റർ ടേക്ക് ഓഫ് ചെയ്യേണ്ട", "മൂന്ന് ലക്ഷം മീറ്റർ ടേക്ക് ഓഫ്",
            ),
            "kn" to listOf("ಮೂರು ಲಕ್ಷ ಮೀಟರ್ ಟೇಕ್ ಆಫ್"),
            "te" to listOf(
                "మూడు మీటర్లు టేక్ ఆఫ్ చేయమాకు", "మూడు మీటర్లు వరకు టేకాఫ్ చేయమాకు", "మూడు లక్షల మీటర్లు వరకు టేకాఫ్",
                "మూడు లక్షల మీటర్లు టేక్ ఆఫ్", "మూడు మీటర్లు టేక్ ఆఫ్ రెండుసార్లు", "ఐదు మీటర్లు ముందుకు ఇంకా వెళ్ళు",
                "మూడు మీటర్లు టేక్ ఆఫ్ ఆఫ్",
            ),
        )
        for ((code, texts) in unknown) {
            val matcher = CtcTemplateMatcher(code)
            for (text in texts) {
                val result = matcher.match(text)
                assertEquals("$code: $text", ParseResult.Rejected(PlanParser.notUnderstood(1, text)), result)
            }
        }
        // The same commands without the unknown word still match.
        assertEquals("takeoff({altitude_m: 3})", one(CtcTemplateMatcher("ta"), "மூன்று மீட்டர் டேக் ஆஃப்"))
        assertEquals("takeoff({altitude_m: 3})", one(CtcTemplateMatcher("ml"), "മൂന്ന് മീറ്റർ ടേക്ക് ഓഫ്"))
        assertEquals("takeoff({altitude_m: 3})", one(CtcTemplateMatcher("kn"), "ಮೂರು ಮೀಟರ್ ಟೇಕ್ ಆಫ್"))
        assertEquals("takeoff({altitude_m: 3})", one(te, "మూడు మీటర్లు టేక్ ఆఫ్"))
    }

    @Test fun anUnknownWordAnywhereInAnyFixtureRejects() {
        // Each lexicon lacks its lakh word: inserted at every position of every positive fixture, it must reject
        // (in place of the unit too: it is no near miss of one).
        val lakh = mapOf("te" to "లక్షల", "ta" to "லட்சம்", "kn" to "ಲಕ್ಷ", "ml" to "ലക്ഷം")
        var checked = 0
        for ((code, word) in lakh) {
            val matcher = CtcTemplateMatcher(code)
            val fixtures = fixtures(code)
            for (case in (fixtures["positive"] as List<Map<String, Any?>>) + (fixtures["loans"] as List<Map<String, Any?>>)) {
                val words = (case["text"] as String).split(' ')
                for (at in 0..words.size) {
                    rejected(matcher, (words.take(at) + word + words.drop(at)).joinToString(" "))
                    checked++
                }
            }
        }
        rejected(te, "మూడు లక్షల టేక్ ఆఫ్")
        rejected(CtcTemplateMatcher("ta"), "மூன்று லட்சம் டேக் ஆஃப்")
        assertTrue("$checked", checked > 300)
    }

    @Test fun aSignedNumberOrAUnitSymbolRejectsAsTheRuleParserDoes() {
        // "-3" is never read as 3, nor "5'" or "5%" as 5 m (spec 4.8 rule 2): the same step messages as StepParser.
        val negative = "distances and heights cannot be negative."
        val unit = "use metres, for example: 3 m."
        val commands = mapOf(
            "te" to "మీటర్లు టేక్ ఆఫ్", "ta" to "மீட்டர் மேலே போ", "kn" to "ಮೀಟರ್ ಮೇಲೆ ಹೋಗು", "ml" to "മീറ്റർ മുകളിലേക്ക് പോകൂ",
        )
        for ((code, command) in commands) {
            val matcher = CtcTemplateMatcher(code)
            assertTrue("$code: $command", matcher.match("3 $command") is ParseResult.Accepted)
            for ((number, reason) in listOf("-3" to negative, "- 3" to negative, "–3" to negative, "−3" to negative,
                "5'" to unit, "5%" to unit, "5 %" to unit, "5°" to unit)) {
                val text = "$number $command"
                assertEquals("$code: $text", ParseResult.Rejected("Step 1 (\"$text\"): $reason"), matcher.match(text))
            }
        }
        // The rule parser gives the same message for the same te text.
        assertEquals(PlanParser.parse("-3 మీటర్లు టేకాఫ్", "te-IN"), te.match("-3 మీటర్లు టేకాఫ్"))
    }

    @Test fun aFixedWordOfFewerThanFourCodePointsHasNoNearMiss() {
        // One edit on a 2-3 code-point word is a 33-50 % change: లేక is not లే, ಏರದು ("does not rise") is not ಏರು.
        rejected(te, "మూడు మీటర్లు పైకి లేక")
        assertEquals("takeoff({altitude_m: 3})", one(te, "మూడు మీటర్లు పైకి లే"))
        val kn = CtcTemplateMatcher("kn")
        rejected(kn, "3 ಮೀಟರ್ ಮೇಲಕ್ಕೆ ಏರದು")
        rejected(kn, "ಮೂರು ಮೀಟರ್ ಮೇಲಕ್ಕೆ ಏರದು")
        assertEquals("takeoff({altitude_m: 3})", one(kn, "ಮೂರು ಮೀಟರ್ ಮೇಲಕ್ಕೆ ಏರು"))
    }

    @Test fun negationsRejectInEveryCtcLanguage() {
        for (code in listOf("te", "ta", "kn", "ml")) {
            val matcher = CtcTemplateMatcher(code)
            for (case in fixtures(code)["negated"] as List<Map<String, Any?>>) rejected(matcher, case["text"] as String)
        }
    }

    @Test fun everyCtcFixtureCommandAndPlanMatches() {
        for (code in listOf("te", "ta", "kn", "ml")) {
            val matcher = CtcTemplateMatcher(code)
            val fixtures = fixtures(code)
            for (case in (fixtures["positive"] as List<Map<String, Any?>>) + (fixtures["loans"] as List<Map<String, Any?>>)) {
                val text = case["text"] as String
                val want = tool(case["tool"] as String, case["arguments"] as Map<String, Any?>)
                assertEquals("$code: $text", listOf(want), steps(matcher.match(text)))
            }
            for (case in fixtures["plans"] as List<Map<String, Any?>>) {
                val text = case["text"] as String
                val want = (case["steps"] as List<Map<String, Any?>>).map { tool(it["tool"] as String, it["arguments"] as Map<String, Any?>) }
                assertEquals("$code: $text", want, steps(matcher.match(text)))
            }
            for (case in fixtures["unk"] as List<Map<String, Any?>>) {
                val text = case["text"] as String
                if (case["expect"] == "hold") assertEquals("$code: $text", listOf("hold({})"), steps(matcher.match(text)))
                else rejected(matcher, text)
            }
        }
    }

    @Test fun cleanupOfRecognizerOutput() {
        // CTC hypotheses can start with an orphan vowel sign; joiners and their absence read the same.
        assertEquals("land({})", one(te, "ాల్యాండ్"))
        val kn = CtcTemplateMatcher("kn")
        assertEquals("move_relative({direction: forward, distance_m: 2})", one(kn, "ಎರಡು ಮೀಟರ್‌ಗಳು ಮುಂದೆ ಹೋಗು"))
        assertEquals("move_relative({direction: forward, distance_m: 2})", one(kn, "ಎರಡು ಮೀಟರ್ಗಳು ಮುಂದೆ ಹೋಗು"))
        assertTrue(te.match("") is ParseResult.Rejected)
        rejected(te, "ఈ కూడా యొక్క") // out of grammar
    }

    private fun tool(name: String, arguments: Map<String, Any?>) =
        "$name(${arguments.entries.joinToString(", ", "{", "}") { "${it.key}: ${(it.value as? Number)?.let(::number) ?: it.value}" }})"

    private fun number(value: Number): String = value.toDouble().let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }

    private fun fixtures(code: String): Map<String, Any?> {
        val stream = javaClass.getResourceAsStream("/grammar/fixtures_$code.json") ?: error("fixtures_$code.json missing")
        return MiniJson.parse(stream.readBytes().toString(Charsets.UTF_8)) as Map<String, Any?>
    }
}
