package com.aeronex.app.memory

import com.aeronex.app.vehicle.FrameOrigin
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

/** One step of a saved mission: the tool call, as the history keeps it. */
data class SavedStep(val name: String, val arguments: Map<String, Any>, val description: String)

/** One stage of a saved mission, with the plan source it was built from (wire name: phone, typed, tap, ai). */
data class SavedStage(val source: String, val steps: List<SavedStep>)

/** A mission the operator saved by name (spec 2026-10-28 "Saved missions"): commands only, never positions. */
data class SavedMission(val name: String, val stages: List<SavedStage>, val atWallMs: Long = 0)

/** An immutable view of the flight memory (spec 2026-10-28 M1). */
data class MemoryState(
    /** HOME_POSITION as last seen; null until home is known. It moves where the vehicle arms. */
    val home: Ned? = null,
    /** The local frame [home], [trail] and [points] are in; null until a HOME_POSITION gave it. */
    val origin: FrameOrigin? = null,
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
    /** Saved missions, newest first, at most [FlightJournal.MISSIONS_LIMIT]. Kept by clear and a new frame. */
    val missions: List<SavedMission> = emptyList(),
)

/**
 * The flight memory (spec 2026-10-28 M1): the trail of positions the app flew the vehicle to in this flight, the
 * points the operator saved, home, and the commands of finished plans. The supervisor writes it on its thread and
 * anyone may read [state]. Every change goes to [store] (which saves off the caller's thread), so the memory
 * survives an app or service restart.
 *
 * Positions are in the autopilot's local NED frame and only mean something in that frame. Its [FrameOrigin] comes
 * with every HOME_POSITION: when it moves more than [FRAME_TOLERANCE_M] (another vehicle, a reboot, a new EKF
 * origin), the trail and the points are forgotten, with [FRAME_CHANGED] as the notice. Home itself moving is not a
 * new frame: ArduCopter sets home where it arms, so a second takeoff from elsewhere keeps the points and "return
 * home" flies to the new home. A restored memory without its home and frame keeps its history only, since its
 * positions cannot be checked.
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

    /** Every vehicle snapshot's home and [origin] ([com.aeronex.app.vehicle.VehicleState]); cheap when unchanged. */
    fun observeHome(home: Ned?, origin: FrameOrigin?) {
        if (home == null) return
        if (state.let { it.home == home && it.origin == origin }) return
        update { s ->
            when {
                s.home == null -> s.copy(home = home, origin = origin)
                sameFrame(s, home, origin) -> s.copy(home = home, origin = origin ?: s.origin)
                else -> MemoryState(home = home, origin = origin, history = s.history, nextId = s.nextId,
                    notice = if (s.trail.isEmpty() && s.points.isEmpty()) null else FRAME_CHANGED, missions = s.missions)
            }
        }
    }

    /** Without both origins, only a home that stayed put shows the frame did. */
    private fun sameFrame(s: MemoryState, home: Ned, origin: FrameOrigin?): Boolean {
        val known = s.origin
        return if (known != null && origin != null) known.distanceTo(origin) <= FRAME_TOLERANCE_M
        else s.home!!.distanceTo(home) <= FRAME_TOLERANCE_M
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

    /**
     * The operator cleared the memory: trail, points, history and notice. Home and its frame stay known, and the saved
     * missions stay: they are the operator's own, deleted one by one.
     */
    fun clear() = update { s -> MemoryState(home = s.home, origin = s.origin, nextId = s.nextId, missions = s.missions) }

    /**
     * Saves [mission], replacing one of the same name (any case), as the newest. False when [MISSIONS_LIMIT] others are
     * saved already: one must be deleted first, never dropped unasked.
     */
    fun saveMission(mission: SavedMission): Boolean {
        var saved = false
        update { s ->
            val others = s.missions.filterNot { it.name.equals(mission.name, ignoreCase = true) }
            if (others.size >= MISSIONS_LIMIT) s
            else {
                saved = true
                s.copy(missions = listOf(mission.copy(atWallMs = wallClock())) + others)
            }
        }
        return saved
    }

    /** The saved mission called [name] (any case), if there is one. */
    fun mission(name: String): SavedMission? = state.missions.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun deleteMission(name: String) = update { s ->
        s.copy(missions = s.missions.filterNot { it.name.equals(name.trim(), ignoreCase = true) })
    }

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
        /** Origins (or, without them, homes) closer than this are the same frame. */
        const val FRAME_TOLERANCE_M = 1.0
        const val MIN_SPACING_M = 0.5
        const val TRAIL_LIMIT = 64
        const val HISTORY_LIMIT = 60
        const val MISSIONS_LIMIT = 10
        const val MISSION_NAME_MAX = 32

        /** [raw] as a mission name: trimmed, inner spaces collapsed, 1–[MISSION_NAME_MAX] printable characters. */
        fun missionName(raw: String): String? {
            val name = raw.trim().replace(Regex("\\s+"), " ")
            return name.takeIf { it.length in 1..MISSION_NAME_MAX && it.none(Char::isISOControl) }
        }
        const val TAKEOFF_LABEL = "Takeoff"
        const val FRAME_CHANGED = "The drone's position frame changed (a reboot or another drone), so the flown " +
            "path and saved points were forgotten."

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

        /** A restored memory keeps positions only with the home and frame they belong to. */
        private fun restore(saved: MemoryState?): MemoryState = when {
            saved == null -> MemoryState()
            saved.home == null || saved.origin == null ->
                MemoryState(history = bounded(saved.history), nextId = saved.nextId, missions = saved.missions)
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
