package com.aeronex.app.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 4.9: `[unk]` is tolerated only at the edges of an exact STOP, HOLD or status template. */
class UnkPolicyTest {
    private fun passes(text: String) = (UnkPolicy.check(text) as? UnkPolicy.Result.Pass)?.text

    private fun rejected(text: String) = UnkPolicy.check(text) is UnkPolicy.Result.Reject

    @Test fun textWithoutUnkPassesUnchanged() {
        assertEquals("move forward 3 meters", passes("move forward 3 meters"))
        assertEquals("  land. ", passes("  land. "))
    }

    @Test fun aLeadingOrTrailingUnkIsDroppedBeforeAnExactStopHoldOrStatusTemplate() {
        assertEquals("hold position", passes("[unk] hold position"))
        assertEquals("stop", passes("stop [unk]"))
        assertEquals("hover", passes("[UNK] [unk] hover [unk]"))
        assertEquals("status", passes("[unk] status"))
        assertEquals("हॉवर करो", passes("[unk] हॉवर करो"))
        assertEquals("रुक जाओ", passes("रुक जाओ [unk]"))
        assertEquals("హోవర్ చేయి", passes("[unk] హోవర్ చేయి"))
        assertEquals("ఆపు", passes("ఆపు [unk]"))
        assertEquals("ஹோவர்", passes("[unk] ஹோவர்"))
    }

    @Test fun unkBesideAnythingElseRejectsTheWholeUtterance() {
        for (text in listOf(
            "[unk] what is the [unk] fly", // the spec's golden vector
            "[unk] move forward two meters", // motion
            "move [unk] forward two meters", // inside a motion clause
            "take off to 3 m [unk] land", // between the clauses of a plan
            "hold [unk] position", // inside a hold template
            "land [unk]", // land is not STOP, HOLD or status
            "[unk] hold position please", // not exactly a template
            "[unk] take off to 3 m",
            "[unk]",
            "[unk] [unk]",
            "दो [unk] मीटर आगे जाओ",
        )) assertTrue(text, rejected(text))
        assertEquals(UnkPolicy.SAY_AGAIN, (UnkPolicy.check("[unk] land") as UnkPolicy.Result.Reject).message)
    }

    @Test fun throughThePlanParserAToleratedUnkGivesHoldAndARejectedOneNoPlan() {
        assertEquals(listOf("hold"), steps("[unk] hold position", "en-IN"))
        assertEquals(listOf("hold"), steps("stop [unk]", "en-IN"))
        assertEquals(listOf("hold"), steps("[unk] हॉवर करो", "hi-IN"))
        assertEquals(listOf("hold"), steps("ఆపు [unk]", "te-IN"))
        assertEquals(null, steps("[unk] what is the [unk] fly", "en-IN"))
        assertEquals(null, steps("[unk] move forward two meters", "en-IN"))
    }

    private fun steps(text: String, language: String) =
        (PlanParser.parse(text, language) as? ParseResult.Accepted)?.plan?.steps?.map { it.command.name }
}
