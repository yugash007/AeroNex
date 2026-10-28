package com.aeronex.app.memory

import com.aeronex.app.vehicle.Ned
import kotlin.math.sqrt

/** A position the app flew the vehicle to, confirmed by telemetry, in the autopilot's LOCAL_POSITION_NED frame. */
data class Waypoint(val position: Ned, val label: String, val atWallMs: Long)

/** One step of a finished plan: the "previous commands" the operator can review and run again. */
data class CommandRecord(
    val id: Long,
    /** Steps of one plan share it, in step order. */
    val planId: Long,
    val atWallMs: Long,
    val name: String,
    /** Numbers as given (Int or Double), as [com.aeronex.app.command.model.FlightCommand.arguments]. */
    val arguments: Map<String, Any>,
    val description: String,
    /** The plan source on the wire: phone, typed, tap or ai. */
    val source: String,
    /** The step's final status name, e.g. COMPLETED, NOT_SENT, UNKNOWN. */
    val outcome: String,
)

/** An immutable view of the flight memory (spec 2026-10-28 M1). */
data class MemoryState(
    /** HOME_POSITION the trail and spots belong to; null until home is known. */
    val home: Ned? = null,
    /** The current flight's confirmed positions, oldest first: the first is where the takeoff ended. */
    val trail: List<Waypoint> = emptyList(),
    /** Saved spots by number (1–9). */
    val points: Map<Int, Waypoint> = emptyMap(),
    /** Finished steps, oldest first, at most [FlightJournal.HISTORY_LIMIT]. */
    val history: List<CommandRecord> = emptyList(),
    /** The next [CommandRecord.id] and plan id. */
    val nextId: Long = 1,
    /** Why the trail and spots were last forgotten, for the UI; null when nothing was. */
    val notice: String? = null,
)

/**
 * The flight memory (spec 2026-10-28 M1): the trail of positions the app flew the vehicle to in this flight, the
 * spots the operator saved, home, and the commands of finished plans. The supervisor writes it on its thread and
 * anyone may read [state]. Every change goes to [store] (which saves off the caller's thread), so the memory
 * survives an app or service restart.
 *
 * Positions are the autopilot's local NED frame and only mean something while home stays put: when HOME_POSITION
 * moves more than [HOME_TOLERANCE_M] from the remembered home (another vehicle, a reboot, a new EKF origin), the
 * trail and the spots are forgotten, with [HOME_MOVED] as the notice. A restored memory without a home keeps its
 * history only, since its positions cannot be checked.
 */
class FlightJournal(
    private val store: JournalStore = JournalStore.NONE,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    @Volatile var state: MemoryState = restore(store.load())
        private set

    /** Every vehicle snapshot's home (cheap when it has not moved). */
    fun observeHome(home: Ned?) {
        if (home == null) return
        val known = state.home
        if (known != null && known.distanceTo(home) <= HOME_TOLERANCE_M) return
        update { s ->
            val current = s.home
            when {
                current == null -> s.copy(home = home)
                current.distanceTo(home) <= HOME_TOLERANCE_M -> s
                else -> MemoryState(home = home, history = s.history, nextId = s.nextId,
                    notice = if (s.trail.isEmpty() && s.points.isEmpty()) null else HOME_MOVED)
            }
        }
    }

    /** A takeoff ended at [position]: a new flight starts a new trail. */
    fun takeoffCompleted(position: Ned) = update { it.copy(trail = listOf(waypoint(position, TAKEOFF_LABEL)), notice = null) }

    /**
     * A move or a flight-memory flight ended at [position]. A position closer than [MIN_SPACING_M] to the last one
     * adds nothing. The takeoff position always stays first; past [TRAIL_LIMIT] the oldest after it are dropped.
     */
    fun arrived(position: Ned, label: String) = update { s ->
        val last = s.trail.lastOrNull()
        when {
            last == null -> s.copy(trail = listOf(waypoint(position, label)))
            last.position.distanceTo(position) < MIN_SPACING_M -> s
            else -> s.copy(trail = bounded(s.trail + waypoint(position, label)))
        }
    }

    /** go_back or retrace ended: only the first [count] positions of the trail remain (undo). */
    fun keepTrail(count: Int) = update { s -> if (count >= s.trail.size) s else s.copy(trail = s.trail.take(maxOf(count, 0))) }

    /** Saves [position] as spot [point]; an older spot of that number is replaced. */
    fun markPoint(point: Int, position: Ned) = update { s ->
        s.copy(points = s.points + (point to waypoint(position, "Spot $point")))
    }

    /** A finished plan's steps go to the history, as one plan. */
    fun recordPlan(steps: List<RecordedStep>) {
        if (steps.isEmpty()) return
        update { s ->
            val planId = s.nextId
            val at = wallClock()
            val records = steps.mapIndexed { i, step ->
                CommandRecord(planId + i, planId, at, step.name, step.arguments, step.description, step.source, step.outcome)
            }
            s.copy(history = (s.history + records).takeLast(HISTORY_LIMIT), nextId = planId + steps.size)
        }
    }

    /** The steps of the plan [planId] in the history, in order; empty when it is no longer there. */
    fun plan(planId: Long): List<CommandRecord> = state.history.filter { it.planId == planId }

    /** The operator cleared the memory: trail, spots, history and notice; home stays known. */
    fun clear() = update { s -> MemoryState(home = s.home, nextId = s.nextId) }

    private fun waypoint(position: Ned, label: String) = Waypoint(position, label, wallClock())

    private fun bounded(trail: List<Waypoint>) =
        if (trail.size <= TRAIL_LIMIT) trail else listOf(trail.first()) + trail.drop(1).takeLast(TRAIL_LIMIT - 1)

    private inline fun update(change: (MemoryState) -> MemoryState) {
        val saved = synchronized(lock) {
            val before = state
            val after = change(before)
            if (after == before) return
            state = after
            after
        }
        store.save(saved)
    }

    /** One finished step for [recordPlan]. */
    data class RecordedStep(
        val name: String,
        val arguments: Map<String, Any>,
        val description: String,
        val source: String,
        val outcome: String,
    )

    companion object {
        const val HOME_TOLERANCE_M = 1.0
        const val MIN_SPACING_M = 0.5
        const val TRAIL_LIMIT = 64
        const val HISTORY_LIMIT = 60
        const val TAKEOFF_LABEL = "Takeoff"
        const val HOME_MOVED = "Home moved, so the flown path and saved spots were forgotten."

        /** A restored memory keeps positions only with the home they belong to. */
        private fun restore(saved: MemoryState?): MemoryState = when {
            saved == null -> MemoryState()
            saved.home == null -> MemoryState(history = saved.history, nextId = saved.nextId)
            else -> saved
        }
    }
}

/** Straight-line distance in metres. */
internal fun Ned.distanceTo(other: Ned): Double {
    val dx = x - other.x
    val dy = y - other.y
    val dz = z - other.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}
