package com.aeronex.app.safety

import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.mission.Mission
import com.aeronex.app.vehicle.executor.StopAction

/**
 * Where a plan came from: the plan card's source chip (spec 4.10). [AI] (0.4.0, spec A3) only through
 * [SafetySupervisor.submitAi], from the in-process AI pipeline; [MISSION] (spec 2026-10-28) only for the mission the
 * supervisor built from its stages; [fromChannel] are the sources a client may name.
 */
enum class PlanSource(val label: String) {
    PHONE("Phone"), TYPED("Typed"), TAP("Tap"), AI("AI"), MISSION("Mission");

    companion object {
        /** The sources the `aeronex/session` channel accepts: never [AI] or [MISSION]. */
        val fromChannel: List<PlanSource> = entries - AI - MISSION
    }
}

/** The STOP triggers (spec 4.12) plus HOLD and switching execution off, which act as STOP. */
enum class StopTrigger {
    BUTTON, VOLUME_DOWN, NOTIFICATION, VOICE, PARTIAL_TOKEN, CANCEL, HOLD_COMMAND, EXECUTION_OFF;

    /** Only a 1 s hold of the on-screen button overrides an EXTERNAL mode owner. */
    val canOverride: Boolean get() = this == BUTTON
}

/** v0.2.0 step states. */
enum class StepStatus { PENDING, RUNNING, COMPLETED, NOT_SENT, REJECTED, UNKNOWN, SKIPPED }

/** A step's state and its operator-facing text (the v0.2.0 strings). */
data class StepOutcome(val step: PlanStep, val status: StepStatus, val message: String? = null) {
    companion object {
        fun dryRun(step: PlanStep) = StepOutcome(step, StepStatus.NOT_SENT, "NOT SENT — dry run: ${step.command}")
        /** [detail]: get_status's vehicle status line (v0.2.0 "COMPLETED — Connected · …"). */
        fun completed(step: PlanStep, detail: String? = null) = StepOutcome(step, StepStatus.COMPLETED,
            "COMPLETED — " + (detail ?: "confirmed by simulator telemetry: ${step.command.name}"))
        /** HOLD on its own is the STOP action, confirmed by its ACK (spec 4.14). */
        fun completedByAck(step: PlanStep) =
            StepOutcome(step, StepStatus.COMPLETED, "COMPLETED — confirmed by ACK: ${step.command.name}")
        fun rejected(step: PlanStep, reason: String) = StepOutcome(step, StepStatus.REJECTED, "REJECTED — nothing sent: $reason")
        fun unknown(step: PlanStep, reason: String) = StepOutcome(step, StepStatus.UNKNOWN, "UNKNOWN — $reason")
        fun skipped(step: PlanStep) = StepOutcome(step, StepStatus.SKIPPED, "SKIPPED — not sent")
    }
}

/**
 * A validated plan shown for confirmation: [token] works once, until [expiresAtMs] (supervisor clock). A [mission]'s
 * plan is all its steps ([Mission.plan]).
 */
data class PendingPlan(
    val token: String,
    val plan: FlightPlan,
    val source: PlanSource,
    val expiresAtMs: Long,
    val mission: Mission? = null,
)

/** One stage of the mission being built: a plan as it was said or tapped, and where it came from. */
data class DraftStage(val plan: FlightPlan, val source: PlanSource)

/** A running mission holding between stages until [SafetySupervisor.continueMission] with [token]. */
data class Checkpoint(val token: String, val stage: Int, val stageCount: Int)

/**
 * The last mission ended early at step [fromStep] (0-based): [SafetySupervisor.resumeMission] continues it. When it
 * ended [atCheckpoint], that step never started: nothing was interrupted, so there is nothing to skip.
 */
data class Resume(val fromStep: Int, val stepCount: Int, val atCheckpoint: Boolean = false)

/** Missions (spec 2026-10-28 "Missions"): the one being built, the one holding at a checkpoint, the one to resume. */
data class MissionStatus(
    /** New plans become stages of [stages] instead of waiting for CONFIRM (STOP, HOLD and status still run at once). */
    val building: Boolean = false,
    val stages: List<DraftStage> = emptyList(),
    /** The mission holds after each stage until the operator continues. */
    val checkpoints: Boolean = false,
    /** The stage of each step of the shown or running mission ([SupervisorState.plan]); null for a plain plan. */
    val stageOfStep: List<Int>? = null,
    val checkpoint: Checkpoint? = null,
    val resume: Resume? = null,
)

enum class PlanStage { IDLE, AWAITING_CONFIRMATION, EXECUTING, FINISHED, FAILED }

enum class StopPhase { NOTHING_SENT, SENDING, CONFIRMED, NOT_CONFIRMED }

/** The last STOP: what was sent ([action], null when nothing) and the banner [message]. */
data class StopStatus(
    /** Increases with every STOP decision (also its trace id), so a repeated identical STOP is still new. */
    val id: Long,
    val trigger: StopTrigger,
    val phase: StopPhase,
    val action: StopAction?,
    val message: String,
    /** "STOP NOT CONFIRMED" or a refused disarm: sound the alarm. */
    val alarm: Boolean,
    /** Sent over an EXTERNAL mode owner by the 1 s button hold (logged). */
    val override: Boolean,
)

/** An immutable snapshot for the UI and the service channel. */
data class SupervisorState(
    /** A link is attached. */
    val linked: Boolean = false,
    /** Live execution; false is dry run (the default, per connection, never saved). */
    val execution: Boolean = false,
    val stage: PlanStage = PlanStage.IDLE,
    val pending: PendingPlan? = null,
    /** The plan shown, running or last run. */
    val plan: FlightPlan? = null,
    val source: PlanSource? = null,
    val outcomes: List<StepOutcome> = emptyList(),
    /** The v0.2.0 summary of [outcomes]. */
    val result: String? = null,
    val stop: StopStatus? = null,
    /** Why a pending plan went away, and similar one-line notices. */
    val notice: String? = null,
    val mission: MissionStatus = MissionStatus(),
)

sealed interface SubmitResult {
    /** Shown for confirmation. */
    data class AwaitingConfirmation(val pending: PendingPlan) : SubmitResult

    /** STOP, HOLD and status run at once (S4); the outcome may still be running. */
    data class Immediate(val outcome: StepOutcome) : SubmitResult

    /** While a mission is built, a plan becomes its next stage ([stages] of them now) instead of waiting for CONFIRM. */
    data class AddedToMission(val stages: Int) : SubmitResult

    data class Rejected(val message: String) : SubmitResult
}

sealed interface ConfirmResult {
    data object Started : ConfirmResult
    data class Rejected(val message: String) : ConfirmResult
}
