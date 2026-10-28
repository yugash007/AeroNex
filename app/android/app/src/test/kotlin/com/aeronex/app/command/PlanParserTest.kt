package com.aeronex.app.command

import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.safety.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parser pipeline beyond the golden vectors: the grammar fixtures of the rule languages and v2's hardening. */
@Suppress("UNCHECKED_CAST")
class PlanParserTest {
    private fun steps(text: String, language: String) =
        (PlanParser.parse(text, language) as? ParseResult.Accepted)?.plan?.steps?.map { it.command.toString() }

    private fun message(text: String, language: String) = (PlanParser.parse(text, language) as ParseResult.Rejected).message

    @Test fun everyRuleLanguageFixtureParses() {
        for (code in GrammarTables.RULE_LANGUAGES) {
            val fixtures = fixtures(code)
            val language = "$code-IN"
            for (case in (fixtures["positive"] as List<Map<String, Any?>>) + (fixtures["loans"] as List<Map<String, Any?>>)) {
                val text = case["text"] as String
                assertEquals("$code: $text", listOf(tool(case)), steps(text, language))
            }
            for (case in fixtures["plans"] as List<Map<String, Any?>>) {
                val text = case["text"] as String
                assertEquals("$code: $text", (case["steps"] as List<Map<String, Any?>>).map(::tool), steps(text, language))
            }
            for (case in fixtures["negated"] as List<Map<String, Any?>>) {
                assertTrue("$code: ${case["text"]}", PlanParser.parse(case["text"] as String, language) is ParseResult.Rejected)
            }
            for (case in fixtures["unk"] as List<Map<String, Any?>>) {
                val text = case["text"] as String
                if (case["expect"] == "hold") assertEquals("$code: $text", listOf("hold({})"), steps(text, language))
                else assertTrue("$code: $text", PlanParser.parse(text, language) is ParseResult.Rejected)
            }
            for (case in fixtures["out_of_grammar"] as List<Map<String, Any?>>? ?: emptyList()) {
                assertTrue("$code: ${case["text"]}", PlanParser.parse(case["text"] as String, language) is ParseResult.Rejected)
            }
        }
    }

    @Test fun teNegativeImperativesAreRefusedAsNegations() {
        for (word in GrammarTables.lexicon("te").grammarNegators) {
            val text = "రెండు మీటర్లు ముందుకు $word"
            // The fused -వద్దు forms are the inspector's (v0.2.0); the rest no v0.2.0 rule read (its AI got them).
            val expected = if ("వద్దు" in word) TranscriptInspector.NEGATION else "Step 1 (\"$text\"): ${StepParser.NEGATED}"
            assertEquals(word, expected, message(text, "te-IN"))
        }
        // The golden case keeps its v0.2.0 step-level message.
        assertEquals("Step 1 (\"ల్యాండ్ చేయొద్దు\"): ${StepParser.NEGATED}", message("ల్యాండ్ చేయొద్దు", "te-IN"))
    }

    @Test fun aClauseNoRuleReadsRejectsTheWholePlanWithoutAnyFallback() {
        assertEquals(PlanParser.notUnderstood(1, "மூன்று மீட்டர் முன்னே செல்"), message("மூன்று மீட்டர் முன்னே செல்", "ta-IN"))
        assertEquals(PlanParser.notUnderstood(2, "do a flip"), message("take off to 3 m then do a flip", "en-IN"))
        // A rule error after an unread clause still wins, as in v0.2.0.
        assertEquals("Takeoff can only be the first step.", message("do a flip then take off to 3 m", "en-IN"))
    }

    @Test fun textInATemplateOnlyLanguageIsReadByItsTemplates() {
        fun read(text: String, language: String) =
            (PlanParser.parseText(text, language) as? ParseResult.Accepted)?.plan?.steps?.map { it.command.toString() }
        for (code in GrammarTables.LANGUAGES - GrammarTables.RULE_LANGUAGES.toSet()) {
            val fixtures = fixtures(code)
            for (case in (fixtures["positive"] as List<Map<String, Any?>>) + (fixtures["loans"] as List<Map<String, Any?>>)) {
                assertEquals("$code: ${case["text"]}", listOf(tool(case)), read(case["text"] as String, "$code-IN"))
            }
            for (case in fixtures["negated"] as List<Map<String, Any?>>) {
                assertTrue("$code: ${case["text"]}", PlanParser.parseText(case["text"] as String, "$code-IN") is ParseResult.Rejected)
            }
        }
        // The rule languages, and text in another script, are the rule parser's.
        fun outcome(result: ParseResult): Any = when (result) {
            is ParseResult.Accepted -> result.plan.steps.map { it.command.toString() } to result.transcript
            is ParseResult.Rejected -> result.message
        }
        for ((text, language) in listOf("మూడు మీటర్లు టేక్ ఆఫ్" to "te-IN", "मत उड़ो" to "hi", "take off to 3 m" to "kn-IN",
            "மூன்று மீட்டர் முன்னே செல்" to "en-IN")) {
            assertEquals(text, outcome(PlanParser.parse(text, language)), outcome(PlanParser.parseText(text, language)))
        }
        assertEquals(listOf("takeoff({altitude_m: 3})"), read("take off to 3 m", "kn-IN"))
        // Typed signs and unit symbols are refused, never dropped (spec 4.8 rule 2).
        for ((text, language) in listOf("மீட்டர் மேலே போ" to "ta-IN", "ಮೀಟರ್ ಮೇಲೆ ಹೋಗು" to "kn-IN",
            "മീറ്റർ മുകളിലേക്ക് പോകൂ" to "ml-IN")) {
            for ((number, reason) in listOf("-3" to "distances and heights cannot be negative.",
                "- 3" to "distances and heights cannot be negative.", "–3" to "distances and heights cannot be negative.",
                "5'" to "use metres, for example: 3 m.", "5%" to "use metres, for example: 3 m.")) {
                assertEquals("$number $text", ParseResult.Rejected("Step 1 (\"$number $text\"): $reason"),
                    PlanParser.parseText("$number $text", language))
            }
        }
    }

    @Test fun normalisedSpeechParsesLikeTypedText() {
        assertEquals(listOf("takeoff({altitude_m: 3})"), steps("तीन मीटर्स टेक ऑफ करो", "hi-IN"))
        assertEquals(listOf("get_status({})"), steps("स्टेटस", "hi-IN"))
        assertEquals(listOf("move_relative({direction: forward, distance_m: 2})"),
            steps("డ్రోన్" + String(intArrayOf(0x200C), 0, 1) + "ను 2 మీటర్లు ముందుకు పంపు", "te-IN"))
    }

    private fun tool(case: Map<String, Any?>): String {
        val arguments = case["arguments"] as Map<String, Any?>
        return "${case["tool"]}(${arguments.entries.joinToString(", ", "{", "}") { "${it.key}: ${(it.value as? Number)?.let(::number) ?: it.value}" }})"
    }

    private fun number(value: Number): String = value.toDouble().let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }

    private fun fixtures(code: String): Map<String, Any?> {
        val stream = javaClass.getResourceAsStream("/grammar/fixtures_$code.json") ?: error("fixtures_$code.json missing")
        return MiniJson.parse(stream.readBytes().toString(Charsets.UTF_8)) as Map<String, Any?>
    }
}
