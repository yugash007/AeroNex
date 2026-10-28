package com.aeronex.app.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spoken discourse fillers ("hmm", "basically", "like", "should") are read past by the rules; corrections are not. */
class DiscourseFillerTest {
    private fun steps(text: String) =
        (PlanParser.parse(text, "en-IN") as? ParseResult.Accepted)?.plan?.steps?.map { it.command.toString() }

    @Test fun fillersAroundACommandAreReadPast() {
        assertEquals(listOf("move_relative({direction: forward, distance_m: 3})"), steps("hmm basically move forward 3 meters"))
        assertEquals(listOf("move_relative({direction: up, distance_m: 2})"), steps("like move up 2 meters"))
        assertEquals(listOf("land({})"), steps("you should land"))
        assertEquals(listOf("move_relative({direction: left, distance_m: 2.5})"), steps("Hm move two point five meters left."))
    }

    @Test fun aSelfCorrectionIsStillRefused() {
        for (said in listOf("go up 2 meters, actually 1 meter", "move forward 3 meters, I mean 4 meters")) {
            assertTrue(said, PlanParser.parse(said, "en-IN") is ParseResult.Rejected)
        }
    }
}
