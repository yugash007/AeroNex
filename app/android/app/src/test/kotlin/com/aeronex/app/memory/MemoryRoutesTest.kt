package com.aeronex.app.memory

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.vehicle.Ned
import org.junit.Assert.*
import org.junit.Test

/** Spec 2026-10-28 M2: where return home, go back, retrace and go to spot fly. */
class MemoryRoutesTest {
    private fun ned(x: Double, y: Double, z: Double = -3.0) = Ned(x, y, z)
    private fun waypoint(p: Ned, label: String = "w") = Waypoint(p, label, 0)
    private val returnHome = FlightCommand.of(FlightCommand.RETURN_HOME)
    private val goBack = FlightCommand.of(FlightCommand.GO_BACK)
    private val retrace = FlightCommand.of(FlightCommand.RETRACE)
    private fun gotoSpot(n: Int) = FlightCommand.of(FlightCommand.GOTO_POINT, mapOf(FlightCommand.POINT to n))

    private val trail = listOf(waypoint(ned(0.0, 0.0), "Takeoff"), waypoint(ned(4.0, 0.0, -5.0), "a"),
        waypoint(ned(4.0, 3.0), "b"))
    private val memory = MemoryState(home = ned(0.2, 0.1, 0.0), trail = trail,
        points = mapOf(2 to waypoint(ned(-2.0, 5.0, -4.0), "Spot 2")))

    private fun refused(message: String, command: FlightCommand, state: MemoryState = memory, at: Ned = ned(4.0, 3.0)) {
        val error = assertThrows(CommandFormatException::class.java) { MemoryRoutes.resolve(command, state, at) }
        assertEquals(message, error.message)
    }

    @Test fun returnHomeFliesLevelToHomesNorthAndEast() {
        val route = MemoryRoutes.resolve(returnHome, memory, ned(4.0, 3.0, -6.0))
        assertEquals(listOf(ned(0.2, 0.1, -6.0)), route.legs)
        assertNull(route.keep)
        assertTrue(MemoryRoutes.resolve(returnHome, memory, ned(0.5, 0.3, -6.0)).legs.isEmpty())
        refused(MemoryRoutes.HOME_UNKNOWN, returnHome, memory.copy(home = null))
    }

    @Test fun goBackUndoesOnePositionAtATime() {
        val first = MemoryRoutes.resolve(goBack, memory, ned(4.0, 3.0))
        assertEquals(listOf(ned(4.0, 0.0, -5.0)), first.legs)
        assertEquals(2, first.keep)
        val shorter = memory.copy(trail = trail.take(2))
        val second = MemoryRoutes.resolve(goBack, shorter, ned(4.0, 0.0, -5.0))
        assertEquals(listOf(ned(0.0, 0.0)), second.legs)
        assertEquals(1, second.keep)
        refused(MemoryRoutes.NOTHING_TO_GO_BACK, goBack, memory.copy(trail = trail.take(1)), ned(0.1, 0.0))
        refused(MemoryRoutes.NOTHING_TO_GO_BACK, goBack, memory.copy(trail = emptyList()))
    }

    @Test fun afterAStopMidMoveGoBackReturnsToWhereTheMoveStarted() {
        // The vehicle braked between b and where it was going: b is the newest position away from it.
        val route = MemoryRoutes.resolve(goBack, memory, ned(4.0, 6.0))
        assertEquals(listOf(ned(4.0, 3.0)), route.legs)
        assertEquals(3, route.keep)
    }

    @Test fun retraceFliesTheTrailNewestFirstBackToTheTakeoffPosition() {
        val route = MemoryRoutes.resolve(retrace, memory, ned(4.0, 3.0))
        assertEquals(listOf(ned(4.0, 0.0, -5.0), ned(0.0, 0.0)), route.legs)
        assertEquals(1, route.keep)
        assertEquals("Takeoff", route.label)
        val interrupted = MemoryRoutes.resolve(retrace, memory, ned(8.0, 3.0))
        assertEquals(listOf(ned(4.0, 3.0), ned(4.0, 0.0, -5.0), ned(0.0, 0.0)), interrupted.legs)
        refused(MemoryRoutes.NOTHING_TO_RETRACE, retrace, memory.copy(trail = trail.take(1)), ned(0.0, 0.2))
    }

    @Test fun goToSpotFliesToTheSavedSpotAtItsHeight() {
        assertEquals(listOf(ned(-2.0, 5.0, -4.0)), MemoryRoutes.resolve(gotoSpot(2), memory, ned(4.0, 3.0)).legs)
        assertTrue(MemoryRoutes.resolve(gotoSpot(2), memory, ned(-2.0, 5.2, -4.0)).legs.isEmpty())
        refused(MemoryRoutes.noSpot(3), gotoSpot(3))
    }

    @Test fun onlyFlightMemoryFlightsAreResolved() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryRoutes.resolve(FlightCommand.of(FlightCommand.LAND), memory, ned(0.0, 0.0))
        }
    }

    @Test fun afterProjectsTheMemoryOfLaterSteps() {
        val back = MemoryRoutes.resolve(goBack, memory, ned(4.0, 3.0))
        assertEquals(2, MemoryRoutes.after(memory, goBack, back, ned(4.0, 0.0, -5.0)).trail.size)
        val home = MemoryRoutes.resolve(returnHome, memory, ned(4.0, 3.0))
        val afterHome = MemoryRoutes.after(memory, returnHome, home, ned(0.2, 0.1, -3.0))
        assertEquals("Home", afterHome.trail.last().label)
        val mark = FlightCommand.of(FlightCommand.MARK_POINT, mapOf(FlightCommand.POINT to 5))
        assertEquals(ned(1.0, 1.0), MemoryRoutes.after(memory, mark, null, ned(1.0, 1.0)).points.getValue(5).position)
        val move = FlightCommand.of(FlightCommand.MOVE, mapOf("direction" to "left", "distance_m" to 2))
        assertEquals("Move left 2 m", MemoryRoutes.after(memory, move, null, ned(4.0, 1.0)).trail.last().label)
        assertEquals(3, MemoryRoutes.after(memory, move, null, ned(4.0, 3.2)).trail.size)
        val takeoff = FlightCommand.of(FlightCommand.TAKEOFF, mapOf("altitude_m" to 3))
        assertEquals(listOf(ned(0.0, 0.0)), MemoryRoutes.after(memory, takeoff, null, ned(0.0, 0.0)).trail.map { it.position })
        assertSame(memory, MemoryRoutes.after(memory, FlightCommand.of(FlightCommand.LAND), null, ned(0.0, 0.0)))
    }
}
