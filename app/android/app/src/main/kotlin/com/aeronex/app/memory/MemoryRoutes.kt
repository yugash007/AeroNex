package com.aeronex.app.memory

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.vehicle.Ned

/**
 * Where a flight-memory step flies (spec 2026-10-28 M2), resolved from the memory and the vehicle's position at the
 * step's start. [legs] are absolute local NED targets flown in order, each sent once; empty when the vehicle is
 * already there. When the step completes, the trail keeps its first [keep] positions (go back, retrace) or, when
 * [keep] is null, gains the arrival position labelled [label].
 */
data class Route(val legs: List<Ned>, val label: String, val keep: Int? = null)

/**
 * Resolves flight-memory steps. A pure function of the memory and a position, so the validator projects a plan
 * with the same rules the supervisor flies it with.
 *
 * - return home: home's north and east at the current height (a level flight; land is its own step).
 * - go back (undo): the newest trail position at least [ARRIVED_M] away; the positions after it are forgotten, so
 *   saying it again walks further back. After a STOP mid-move that is where the move started.
 * - retrace: every trail position newest first, back to where the takeoff ended: the path already flown, which
 *   was clear of obstacles at its heights.
 * - go to spot n: the saved spot, at its saved height.
 */
object MemoryRoutes {
    /** Closer than this is "already there". */
    const val ARRIVED_M = 0.75

    const val HOME_UNKNOWN = "Home position is unknown. Wait for GPS home before returning."
    const val NOTHING_TO_GO_BACK = "There is no earlier position to go back to in this flight."
    const val NOTHING_TO_RETRACE = "There is no flown path to retrace in this flight."

    fun noSpot(point: Int) = "Spot $point is not saved. Say \"mark spot $point\" there first."

    /** Throws [CommandFormatException] with the operator message when the memory cannot answer. */
    fun resolve(command: FlightCommand, memory: MemoryState, position: Ned): Route = when (command.name) {
        FlightCommand.RETURN_HOME -> {
            val home = memory.home ?: throw CommandFormatException(HOME_UNKNOWN)
            val target = Ned(home.x, home.y, position.z)
            Route(if (target.horizontalDistanceTo(position) < ARRIVED_M) emptyList() else listOf(target), "Home")
        }
        FlightCommand.GO_BACK -> {
            val index = memory.trail.indexOfLast { it.position.distanceTo(position) >= ARRIVED_M }
            if (index < 0) throw CommandFormatException(NOTHING_TO_GO_BACK)
            val target = memory.trail[index]
            Route(listOf(target.position), target.label, keep = index + 1)
        }
        FlightCommand.RETRACE -> {
            var end = memory.trail.size
            while (end > 0 && memory.trail[end - 1].position.distanceTo(position) < ARRIVED_M) end--
            if (end == 0) throw CommandFormatException(NOTHING_TO_RETRACE)
            Route(memory.trail.subList(0, end).asReversed().map { it.position }, memory.trail.first().label, keep = 1)
        }
        FlightCommand.GOTO_POINT -> {
            val point = command.point!!
            val spot = memory.points[point] ?: throw CommandFormatException(noSpot(point))
            Route(if (spot.position.distanceTo(position) < ARRIVED_M) emptyList() else listOf(spot.position), spot.label)
        }
        else -> throw IllegalArgumentException("${command.name} is not a flight-memory flight")
    }

    /**
     * The memory as it would be after [command] ended at [position] along [route]: the validator's projection of a
     * plan's later steps (and the supervisor's journal update, through [FlightJournal]).
     */
    fun after(memory: MemoryState, command: FlightCommand, route: Route?, position: Ned, wallMs: Long = 0): MemoryState =
        when {
            command.name == FlightCommand.MARK_POINT ->
                memory.copy(points = memory.points + (command.point!! to Waypoint(position, "Spot ${command.point}", wallMs)))
            command.name == FlightCommand.TAKEOFF ->
                memory.copy(trail = listOf(Waypoint(position, FlightJournal.TAKEOFF_LABEL, wallMs)))
            route?.keep != null -> memory.copy(trail = memory.trail.take(route.keep))
            command.isMotion -> {
                val last = memory.trail.lastOrNull()
                if (last != null && last.position.distanceTo(position) < FlightJournal.MIN_SPACING_M) memory
                else memory.copy(trail = memory.trail + Waypoint(position, route?.label ?: command.description, wallMs))
            }
            else -> memory
        }
}
