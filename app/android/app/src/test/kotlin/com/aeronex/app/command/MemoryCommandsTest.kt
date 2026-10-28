package com.aeronex.app.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 2026-10-28 (grammar/README.md "Flight memory"): the memory phrases through the whole text parser. */
class MemoryCommandsTest {
    private fun steps(text: String, language: String = "en-IN") =
        (PlanParser.parse(text, language) as? ParseResult.Accepted)?.plan?.steps?.map { it.command.toString() }

    private fun message(text: String, language: String = "en-IN") = (PlanParser.parse(text, language) as ParseResult.Rejected).message

    @Test fun theEnglishPhrasesReadAsTheirTools() {
        assertEquals(listOf("return_home({})"), steps("return home"))
        assertEquals(listOf("return_home({})"), steps("Come back home, please."))
        assertEquals(listOf("return_home({})"), steps("hey drone, can you go home now"))
        assertEquals(listOf("go_back({})"), steps("go back"))
        assertEquals(listOf("go_back({})"), steps("undo the last move"))
        assertEquals(listOf("retrace({})"), steps("retrace the path"))
        assertEquals(listOf("retrace({})"), steps("fly back the same way"))
        assertEquals(listOf("goto_point({point: 3})"), steps("go to point three"))
        assertEquals(listOf("goto_point({point: 3})"), steps("fly to waypoint 3"))
        assertEquals(listOf("goto_point({point: 7})"), steps("go back to position number seven"))
        assertEquals(listOf("mark_point({point: 2})"), steps("mark point two"))
        assertEquals(listOf("mark_point({point: 2})"), steps("save this position as 2"))
        assertEquals(listOf("mark_point({point: 4})"), steps("mark this as point four"))
    }

    @Test fun theyJoinPlansLikeAnyStep() {
        assertEquals(listOf("move_relative({direction: forward, distance_m: 3})", "mark_point({point: 1})",
            "move_relative({direction: right, distance_m: 2})", "goto_point({point: 1})", "land({})"),
            steps("move forward 3 meters then mark point one then move right 2 meters then go to point one and land"))
        assertEquals(listOf("return_home({})", "land({})"), steps("come back home and land"))
    }

    @Test fun anythingMoreIsTheRulesAsBefore() {
        // "go back 3 m" is still a backward move; "back" alone with a number never becomes undo.
        assertEquals(listOf("move_relative({direction: backward, distance_m: 3})"), steps("go back 3 meters"))
        // v0.2.0 golden: "land at home" names no memory command.
        assertEquals("Step 1 (\"land at home\"): \"at\" is not understood; say for example: land.", message("land at home"))
        // A negated memory command is refused, never read.
        for (text in listOf("don't go back", "do not return home", "never retrace the path", "don't mark point two")) {
            assertTrue(text, PlanParser.parse(text, "en-IN") is ParseResult.Rejected)
        }
        // "spot" is not a form (it sounds like "stop"): typed, the rules refuse it.
        assertTrue(PlanParser.parse("go to spot 2", "en-IN") is ParseResult.Rejected)
    }

    @Test fun aPointOutsideOneToNineIsRefusedWithItsMessage() {
        assertEquals("Step 1 (\"go to point twelve\"): Specify a point number 1–9.", message("go to point twelve"))
        assertEquals("Step 1 (\"mark point 0\"): Specify a point number 1–9.", message("mark point 0"))
        assertEquals("Step 1 (\"go to point two point five\"): Specify a point number 1–9.", message("go to point two point five"))
    }

    @Test fun hindiAndTeluguPutThePointNumberBeforeTheVerb() {
        assertEquals(listOf("return_home({})"), steps("घर वापस जाओ", "hi-IN"))
        assertEquals(listOf("goto_point({point: 2})"), steps("पॉइंट दो पर जाओ", "hi-IN"))
        assertEquals(listOf("mark_point({point: 3})"), steps("पॉइंट 3 मार्क कर दो", "hi-IN"))
        assertEquals(listOf("go_back({})"), steps("पिछली जगह पर जाओ", "hi-IN"))
        assertEquals(listOf("return_home({})"), steps("ఇంటికి తిరిగి రా", "te-IN"))
        assertEquals(listOf("goto_point({point: 2})"), steps("పాయింట్ రెండు దగ్గరకు వెళ్ళు", "te-IN"))
        assertEquals(listOf("mark_point({point: 4})"), steps("పాయింట్ నాలుగు మార్క్ చేయి", "te-IN"))
        assertEquals(listOf("retrace({})"), steps("అదే దారిలో తిరిగి రా", "te-IN"))
        // Negated: refused.
        assertTrue(PlanParser.parse("घर वापस मत जाओ", "hi-IN") is ParseResult.Rejected)
        assertTrue(PlanParser.parse("ఇంటికి తిరిగి రావద్దు", "te-IN") is ParseResult.Rejected)
    }

    @Test fun theTeluguCtcPathReadsTheSameTemplates() {
        val te = CtcTemplateMatcher("te")
        fun one(text: String) = (te.match(text) as ParseResult.Accepted).plan.steps.single().command.toString()
        assertEquals("return_home({})", one("ఇంటికి తిరిగి రా"))
        assertEquals("goto_point({point: 2})", one("పాయింట్ రెండు దగ్గరకు వెళ్ళు"))
        assertEquals("mark_point({point: 5})", one("పాయింట్ ఐదు మార్క్ చేయి"))
        assertTrue(te.match("పాయింట్ పదకొండు దగ్గరకు వెళ్ళు") is ParseResult.Rejected)
    }
}
