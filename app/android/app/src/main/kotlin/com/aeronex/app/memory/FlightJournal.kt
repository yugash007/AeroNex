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
    /** The mission stage (0-based) of the step; 0 for a plain plan (spec 2026-10-28 "Missions"). */
    val stage: Int = 0,
)

/** An immutable view of the flight memory (spec 2026-10-28 M1). */
data class MemoryState(
    /** HOME_POSITION the trail and points belong to; null until home is known. */
    val home: Ned? = null,
    /** The current flight's confirmed positions, oldest first: the first is where the takeoff ended. */
    val trail: List<Waypoint> = emptyList(),
    /** Saved points by number (1–9). */
    val points: Map<Int, Waypoint> = emptyMap(),
    /** Finished steps, oldest first, at most [FlightJournal.HISTORY_LIMIT]. */
    val history: List<CommandRecord> = emptyList(),
    /** The next [CommandRecord.id] and plan id. */
    val nextId: Long = 1,
    /** Why the trail and points were last forgotten, for the UI; null when nothing was. */
    val notice: String? = null,
)

/**
 * The flight memory (spec 2026-10-28 M1): the trail of positions the app flew the vehicle to in this flight, the
 * points the operator saved, home, and the commands of finished plans. The supervisor writes it on its thread and
 * anyone may read [state]. Every change goes to [store] (which saves off the caller's thread), so the memory
 * survives an app or service restart.
 *
 * Positions are the autopilot's local NED frame and only mean something while home stays put: when HOME_POSITION
 * moves more than [HOME_TOLERANCE_M] from the remembered home (another vehicle, a reboot, a new EKF origin), the
 * trail and the points are forgotten, with [HOME_MOVED] as the notice. A restored memory without a home keeps its
 * history only, since its positions cannot be checked.
 */
class FlightJournal(
    private val store: JournalStore = JournalStore.NONE,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    @Volatile var state: MemoryState = restore(store.load())
        private set

    /** [recordPlan]'s last key and the plan id it got; under [lock]. */
    private var lastKey: Long? = null
    private var lastPlanId = 0L

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

    // The trail and point rules are [MemoryRoutes]': the validator projects plans with the same ones.

    /** A takeoff ended at [position]: a new flight starts a new trail ([MemoryRoutes.takeoff]). */
    fun takeoffCompleted(position: Ned) = update { MemoryRoutes.takeoff(it, position, wallClock()).copy(notice = null) }

    /** A move or a flight-memory flight ended at [position] ([MemoryRoutes.arrived]). */
    fun arrived(position: Ned, label: String) = update { MemoryRoutes.arrived(it, position, label, wallClock()) }

    /** A move or a flight-memory flight starts from [position] ([MemoryRoutes.departing]). */
    fun departing(position: Ned) = update { MemoryRoutes.departing(it, position, wallClock()) }

    /** Go back or retrace ended: only the first [count] positions of the trail remain ([MemoryRoutes.keep]). */
    fun keepTrail(count: Int) = update { MemoryRoutes.keep(it, count) }

    /** Saves [position] as point [point] ([MemoryRoutes.mark]). */
    fun markPoint(point: Int, position: Ned) = update { MemoryRoutes.mark(it, point, position, wallClock()) }

    /**
     * A finished plan's steps go to the history, as one plan. The same plan finishing again under the same [key] (a
     * HOLD whose STOP check ends after its ACK) replaces its records while it is still the newest plan.
     */
    fun recordPlan(steps: List<RecordedStep>, key: Long? = null) {
        if (steps.isEmpty()) return
        update { s ->
            val again = key != null && key == lastKey && s.history.lastOrNull()?.planId == lastPlanId
            val planId = if (again) lastPlanId else s.nextId
            val at = wallClock()
            val records = steps.mapIndexed { i, step ->
                CommandRecord(planId + i, planId, at, step.name, step.arguments, step.description, step.source, step.outcome,
                    step.stage)
            }
            lastKey = key
            lastPlanId = planId
            val kept = if (again) s.history.filter { it.planId != planId } else s.history
            s.copy(history = bounded(kept + records), nextId = maxOf(s.nextId, planId + steps.size))
        }
    }

    /** The steps of the plan [planId] in the history, in order; empty when it is no longer there. */
    fun plan(planId: Long): List<CommandRecord> = state.history.filter { it.planId == planId }

    /** The operator cleared the memory: trail, points, history and notice; home stays known. */
    fun clear() = update { s -> MemoryState(home = s.home, nextId = s.nextId) }

    private inline fun update(change: (MemoryState) -> MemoryState) {
        synchronized(lock) {
            val before = state
            val after = change(before)
            if (after == before) return
            state = after
            // Under the lock, so the store gets the states in the order they were made: an older state saved from
            // another thread never replaces a newer one (a cleared memory would come back after a restart).
            store.save(after)
        }
    }

    /** One finished step for [recordPlan]; [stage] is its mission stage (0 for a plain plan). */
    data class RecordedStep(
        val name: String,
        val arguments: Map<String, Any>,
        val description: String,
        val source: String,
        val outcome: String,
        val stage: Int = 0,
    )

    companion object {
        const val HOME_TOLERANCE_M = 1.0
        const val MIN_SPACING_M = 0.5
        const val TRAIL_LIMIT = 64
        const val HISTORY_LIMIT = 60
        const val TAKEOFF_LABEL = "Takeoff"
        const val HOME_MOVED = "Home moved, so the flown path and saved points were forgotten."

        /**
         * The newest whole plans within [HISTORY_LIMIT] steps: a plan is kept whole or not at all, so "run again"
         * never runs what is left of one. A plan's first record has the plan's id ([recordPlan]); a history saved
         * with its oldest plan cut short loses that plan.
         */
        private fun bounded(history: List<CommandRecord>): List<CommandRecord> {
            var start = 0
            while (start < history.size && (history.size - start > HISTORY_LIMIT || history[start].id != history[start].planId)) {
                val plan = history[start].planId
                while (start < history.size && history[start].planId == plan) start++
            }
            return if (start == 0) history else history.subList(start, history.size).toList()
        }

        /** A restored memory keeps positions only with the home they belong to. */
        private fun restore(saved: MemoryState?): MemoryState = when {
            saved == null -> MemoryState()
            saved.home == null -> MemoryState(history = bounded(saved.history), nextId = saved.nextId)
            else -> saved.copy(history = bounded(saved.history))
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
