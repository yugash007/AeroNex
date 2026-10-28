package com.aeronex.app.bench

import com.aeronex.app.command.ParseResult
import com.aeronex.app.command.PlanParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectationTest {
    private fun parsed(text: String): ParseResult = PlanParser.parseText(text, "en").also {
        if (text != "do not take off") assertTrue("$text should parse", it is ParseResult.Accepted)
    }

    private val rejected = ParseResult.Rejected("Say it again.")

    @Test fun commandClipsNeedTheExactCommand() {
        assertEquals(Verdict.EXACT, Expectation.verdict("takeoff_3", false, parsed("take off to 3 m")))
        assertEquals(Verdict.FALSE_ACCEPT, Expectation.verdict("takeoff_3", false, parsed("take off to 5 m")))
        assertEquals(Verdict.EXACT, Expectation.verdict("forward_3", false, parsed("move forward 3 m")))
        assertEquals(Verdict.FALSE_ACCEPT, Expectation.verdict("forward_3", false, parsed("move backward 3 m")))
        assertEquals(Verdict.EXACT, Expectation.verdict("left_2", false, parsed("go left 2 m")))
        assertEquals(Verdict.FALSE_ACCEPT, Expectation.verdict("left_2", false, parsed("go left 3 m")))
        assertEquals(Verdict.EXACT, Expectation.verdict("land", false, parsed("land")))
        assertEquals(Verdict.EXACT, Expectation.verdict("hold", false, parsed("hold position")))
        assertEquals(Verdict.FALSE_ACCEPT, Expectation.verdict("land", false, parsed("hold position")))
        assertEquals(Verdict.REJECTED, Expectation.verdict("land", false, rejected))
        assertEquals("no final result (timeout, overrun) is a safe miss",
            Verdict.REJECTED, Expectation.verdict("land", false, null))
    }

    @Test fun extraStepsAreAFalseAccept() {
        assertEquals(Verdict.FALSE_ACCEPT, Expectation.verdict("takeoff_3", false, parsed("take off to 3 m and land")))
    }

    @Test fun aSpokenStopIsAStopTokenOrAHoldPlan() {
        assertEquals(Verdict.EXACT, Expectation.verdict("stop", true, null))
        assertEquals(Verdict.EXACT, Expectation.verdict("stop", false, parsed("stop")))
        assertEquals(Verdict.FALSE_ACCEPT, Expectation.verdict("stop", false, parsed("land")))
        assertEquals(Verdict.REJECTED, Expectation.verdict("stop", false, rejected))
        assertEquals("a stop token on another command is a false STOP, not an accept",
            Verdict.FALSE_STOP, Expectation.verdict("land", true, null))
    }

    @Test fun negatedClipsMustBeRejected() {
        assertEquals(Verdict.EXACT, Expectation.verdict("neg_takeoff", false, parsed("do not take off")))
        assertEquals(Verdict.FALSE_ACCEPT, Expectation.verdict("neg_takeoff", false, parsed("take off to 3 m")))
        assertEquals(Verdict.FALSE_STOP, Expectation.verdict("neg_takeoff", true, null))
    }

    @Test fun unknownClipsHaveNoVerdict() {
        assertNull(Expectation.verdict("barrel_roll", false, rejected))
        assertNull(Expectation.verdict("takeoff_x", false, rejected))
    }

    @Test fun spotterVerdicts() {
        assertEquals(SpotterVerdict.HIT, Expectation.spotterVerdict("stop", detections = 1))
        assertEquals(SpotterVerdict.MISS, Expectation.spotterVerdict("stop", detections = 0))
        assertEquals(SpotterVerdict.FALSE_FIRE, Expectation.spotterVerdict("land", detections = 2))
        assertEquals(SpotterVerdict.QUIET, Expectation.spotterVerdict("hold", detections = 0))
    }
}
