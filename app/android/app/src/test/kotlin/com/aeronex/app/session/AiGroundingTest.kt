package com.aeronex.app.session

import com.aeronex.app.ai.LfmPlanner
import com.aeronex.app.command.model.FlightCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec A4: every number and direction of an AI plan must be in the operator's words (after number normalisation). */
class AiGroundingTest {
    private fun move(direction: String, m: Number) =
        FlightCommand.of(FlightCommand.MOVE, mapOf(FlightCommand.DIRECTION to direction, FlightCommand.DISTANCE to m))
    private fun takeoff(m: Number) = FlightCommand.of(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to m))
    private val land = FlightCommand.of(FlightCommand.LAND)

    private fun check(said: String, vararg commands: FlightCommand) = AiGrounding.check(commands.toList(), said)

    @Test fun numbersAndDirectionsSaidInWordsOrDigitsGround() {
        assertNull(check("could you lift off to three meters and fly two and a half meters ahead", takeoff(3), move("forward", 2.5)))
        assertNull(check("climb 2 m then drift to the left by 1.5", move("up", 2), move("left", 1.5)))
        assertNull(check("go higher by two point five", move("up", 2.5)))
        assertNull(check("take off to 3 m then land", takeoff(3), land))
    }

    @Test fun anInventedNumberOrDirectionIsRefused() {
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, check("move forward a little bit", move("forward", 1)))
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, check("take off", takeoff(3)))
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, check("go 2 meters that way", move("left", 2)))
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, check("go 2 meters forward", move("forward", 2.5)))
    }

    @Test fun eachNumberSaidGroundsOneStep() {
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, check("go forward two meters then right", move("forward", 2), move("right", 2)))
        assertNull(check("go forward two meters then right two meters", move("forward", 2), move("right", 2)))
    }

    @Test fun groundingIsSignAware() {
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, check("move forward minus two meters", move("forward", 2)))
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, check("move forward -2 meters", move("forward", 2)))
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, check("move forward − 2 meters", move("forward", 2)))
    }

    @Test fun theUpOfBackUpIsNoDirectionOfItsOwn() {
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, check("back up 2 meters", move("up", 2)))
        assertNull(check("back up 2 meters", move("backward", 2)))
        // "right" of "right now" is no direction either.
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, check("go 2 meters right now", move("right", 2)))
    }
}
