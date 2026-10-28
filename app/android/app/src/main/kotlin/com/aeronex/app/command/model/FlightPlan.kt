package com.aeronex.app.command.model

import java.util.Collections

/**
 * Where a step came from. [AI] (0.4.0, spec A3): a step of a plan the in-process AI pipeline built (LFM planner, then
 * the rules or a Needle tool call per step); only the supervisor's AI entry accepts it, never the channel.
 */
enum class StepSource {
    RULES, FIXTURE, AI;

    /** The v0.2.0 / golden-vector spelling, e.g. "rules". */
    val wireName: String get() = name.lowercase()
}

/** One proposal of a plan; [clause] is the spoken or typed clause it came from. */
data class PlanStep(val command: FlightCommand, val source: StepSource, val clause: String = "")

/**
 * An ordered, structurally valid list of proposals, confirmed once as a whole.
 * Port of v0.2.0 `flight_plan.dart`: the constructor throws [CommandFormatException] with the same messages, in
 * the same order (empty, more than 5 steps, status on its own, takeoff first, land last, 1–10 m from the takeoff).
 * A mission's steps (spec 2026-10-28, [com.aeronex.app.mission.Mission]) are one plan of up to [maxSteps].
 */
class FlightPlan(steps: List<PlanStep>, maxSteps: Int = MAX_STEPS) {
    val steps: List<PlanStep> = Collections.unmodifiableList(steps.toList())

    init {
        if (this.steps.isEmpty()) throw CommandFormatException("Say a command first.")
        if (this.steps.size > maxSteps) {
            throw CommandFormatException(if (maxSteps == MAX_STEPS) "Say at most 5 steps at a time." else "A mission holds at most $maxSteps steps.")
        }
        val names = this.steps.map { it.command.name }
        if (names.size > 1 && FlightCommand.STATUS in names) throw CommandFormatException("Ask for status on its own.")
        if (names.lastIndexOf(FlightCommand.TAKEOFF) > 0) {
            throw CommandFormatException("Takeoff can only be the first step.")
        }
        val land = names.indexOf(FlightCommand.LAND)
        if (land >= 0 && land != names.size - 1) throw CommandFormatException("Land can only be the last step.")
        if (names.first() == FlightCommand.TAKEOFF) checkAltitude(this.steps.first().command.altitudeM!!)
    }

    /**
     * Validates the plan against the reported vehicle state, as v0.2.0 `checkVehicle`. On the ground a plan starts with
     * a takeoff, or is status alone, or (flight memory) marks a point alone: the launch point can be saved.
     */
    fun checkVehicle(landed: Boolean, altitude: Double? = null) {
        val first = steps.first().command.name
        val markAlone = first == FlightCommand.MARK_POINT && steps.size == 1
        if (landed && first != FlightCommand.TAKEOFF && first != FlightCommand.STATUS && !markAlone) {
            throw CommandFormatException(
                "The drone is on the ground. Start with a takeoff height, " +
                    "for example: take off to 3 m and fly forward 3 m."
            )
        }
        if (!landed && steps.any { it.command.name == FlightCommand.TAKEOFF }) {
            throw CommandFormatException("The drone is already flying. Remove the takeoff step.")
        }
        if (!landed && altitude != null) checkAltitude(altitude)
    }

    /**
     * Simulates up/down moves from [start]; the executor still re-checks each. A flight-memory flight ends at a
     * remembered height this plan cannot know, so the simulation stops there; the validator projects the rest with the
     * flight memory ([com.aeronex.app.safety.PlanValidator]).
     */
    private fun checkAltitude(start: Double) {
        var altitude = start
        steps.forEachIndexed { i, step ->
            if (step.command.isMemoryMotion) return
            val direction = step.command.direction
            if (direction != "up" && direction != "down") return@forEachIndexed
            val distance = step.command.distanceM!!
            altitude += if (direction == "up") distance else -distance
            // Tolerate float rounding, e.g. 1.4 m down 0.4 m lands on the 1 m floor.
            if (altitude < 1 - 1e-9 || altitude > 10 + 1e-9) {
                throw CommandFormatException("Step ${i + 1} would leave the 1–10 m altitude range.")
            }
        }
    }

    override fun toString() =
        steps.withIndex().joinToString("; ") { (i, step) -> "${i + 1}. ${step.command.description}" }

    companion object {
        const val MAX_STEPS = 5

        fun single(command: FlightCommand, source: StepSource = StepSource.RULES, clause: String = "") =
            FlightPlan(listOf(PlanStep(command, source, clause)))
    }
}
