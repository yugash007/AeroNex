package com.aeronex.app.mission

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import java.util.Collections

/**
 * A mission (spec 2026-10-28 "Missions"): up to [MAX_STAGES] stages, each a plan the operator saw and checked on its
 * own (at most [FlightPlan.MAX_STEPS] steps), flown in order after one CONFIRM. [plan] is every step in order and keeps
 * the plan rules across the stages (FlightPlan's own messages): a takeoff only first, a land only last, the heights
 * from a takeoff within 1–10 m; a status step is never part of one, since status runs at once. With [checkpoints] the
 * run holds position after each stage until the operator continues ([com.aeronex.app.safety.SafetySupervisor]).
 *
 * The constructor throws [CommandFormatException] with the operator message.
 */
class Mission(stages: List<List<PlanStep>>, val checkpoints: Boolean = false) {
    val stages: List<List<PlanStep>> = Collections.unmodifiableList(stages.map { Collections.unmodifiableList(it.toList()) })

    /** Every step in order: what is validated, confirmed and run. */
    val plan: FlightPlan

    /** The stage (0-based) of each step of [plan]. */
    val stageOfStep: List<Int>

    init {
        if (this.stages.isEmpty()) throw CommandFormatException(EMPTY)
        if (this.stages.size > MAX_STAGES) throw CommandFormatException(TOO_MANY_STAGES)
        this.stages.forEachIndexed { i, steps ->
            if (steps.isEmpty() || steps.size > FlightPlan.MAX_STEPS) throw CommandFormatException(badStage(i))
        }
        val steps = this.stages.flatten()
        if (steps.any { it.command.name == FlightCommand.STATUS }) throw CommandFormatException(NO_STATUS)
        plan = FlightPlan(steps, MAX_STEPS)
        stageOfStep = Collections.unmodifiableList(this.stages.flatMapIndexed { i, stage -> List(stage.size) { i } })
    }

    /** This mission with checkpoints [on] or off. */
    fun withCheckpoints(on: Boolean): Mission = if (on == checkpoints) this else Mission(stages, on)

    /** The step is the first of a stage after the first: where a checkpoint holds. */
    fun startsStage(step: Int) = step in 1 until stageOfStep.size && stageOfStep[step] != stageOfStep[step - 1]

    /** The rest of the mission from [step] (0-based), in the same stages: what "resume" shows. */
    fun from(step: Int): Mission {
        require(step in stageOfStep.indices) { "No step $step in a mission of ${stageOfStep.size}" }
        val first = stageOfStep[step]
        val rest = stages.drop(first).toMutableList()
        rest[0] = rest[0].drop(step - stageOfStep.indexOf(first))
        return Mission(rest, checkpoints)
    }

    companion object {
        const val MAX_STAGES = 5
        const val MAX_STEPS = MAX_STAGES * FlightPlan.MAX_STEPS
        const val EMPTY = "Add a plan to the mission first."
        const val TOO_MANY_STAGES = "A mission holds at most 5 stages."
        const val NO_STATUS = "Ask for status on its own: it is never part of a mission."

        fun badStage(index: Int) = "Stage ${index + 1} needs 1–${FlightPlan.MAX_STEPS} steps."
    }
}
