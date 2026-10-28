package com.aeronex.app.session

import com.aeronex.app.observability.LatencyStats
import com.aeronex.app.observability.LatencySummary
import com.aeronex.app.observability.TraceEvent
import com.aeronex.app.observability.TracePoint

/**
 * Rolling per-stage latency for the snapshot's `latency` section (Diagnostics, spec 5), kept as a trace sink so a
 * 10 Hz snapshot does not rescan the trace ring. Spans pair exactly like [com.aeronex.app.observability.Trace.durationsMs]:
 * per trace id, the first `from` point opens a span that the next `to` point closes. Voice spans use the utterance's
 * id ([VoiceSession.TRACE_ID_BASE] and up), the others the supervisor's plan or STOP id. The newest [window] spans per
 * stage are kept, and at most [maxOpen] unclosed spans (a STOP that sends nothing never closes its span).
 */
class LatencyBook(private val window: Int = 256, private val maxOpen: Int = 64) : (TraceEvent) -> Unit {
    /** The stages in snapshot order; [key] is the snapshot name. A span ends at the first of its [to] points. */
    enum class Stage(val key: String, val from: String, vararg val to: String) {
        /** Spec 5 stages 2a + 2b: PTT release → the tail ended and the last frame reached the recognizer. */
        PTT_UP_TO_DRAINED("pttUpToDrained", VoiceTrace.PTT_UP, VoiceTrace.AUDIO_DRAINED),
        /** Stage 3: the final decode. */
        DRAINED_TO_FINAL("drainedToFinal", VoiceTrace.AUDIO_DRAINED, VoiceTrace.ASR_FINAL),
        /** PTT release → final transcript. */
        PTT_UP_TO_FINAL("pttUpToFinal", VoiceTrace.PTT_UP, VoiceTrace.ASR_FINAL),
        /** Stage 4: normalise + parse + validate (the supervisor's answer). */
        FINAL_TO_PLAN("finalToPlan", VoiceTrace.ASR_FINAL, TracePoint.PLAN_VALIDATED),
        /** T0 → validated plan, before the UI frame (stage 5 is measured in Flutter). */
        PTT_UP_TO_PLAN("pttUpToPlan", VoiceTrace.PTT_UP, TracePoint.PLAN_VALIDATED),
        /** Stage 6 / M1: confirm tap → the plan's first frame queued (COMMAND_LONG or the move). */
        CONFIRM_TO_FRAME("confirmToFrame", TracePoint.CONFIRM, TracePoint.TX, TracePoint.MOVE_TX),
        /** Spec 5 stage 6: confirm tap → the plan's first COMMAND_LONG queued. */
        CONFIRM_TO_TX("confirmToTx", TracePoint.CONFIRM, TracePoint.TX),
        /** Confirm → the plan's first COMMAND_ACK (G1 "confirm → ACK"). */
        CONFIRM_TO_ACK("confirmToAck", TracePoint.CONFIRM, TracePoint.ACK),
        /** M1: confirm → the move frame queued. */
        CONFIRM_TO_MOVE_TX("confirmToMoveTx", TracePoint.CONFIRM, TracePoint.MOVE_TX),
        /** M2: move frame queued → its POSITION_TARGET echo seen, for every move. */
        MOVE_TX_TO_ECHO("moveTxToEcho", TracePoint.MOVE_TX, TracePoint.ECHO),
        /** K2: confirm → landed state IN_AIR. */
        CONFIRM_TO_IN_AIR("confirmToInAir", TracePoint.CONFIRM, TracePoint.IN_AIR),
        /** S1 (from the service call, not the input event): STOP → stop frame queued. */
        STOP_TO_TX("stopToTx", TracePoint.STOP_TRIGGER, TracePoint.STOP_TX),
        /** G2: STOP → its COMMAND_ACK. */
        STOP_TO_ACK("stopToAck", TracePoint.STOP_TRIGGER, TracePoint.STOP_ACK),
        /** 0.4.0 AI path: the lexicon gloss (hi/te/ta/kn/ml → English). */
        AI_GLOSS("aiGloss", AiTrace.START, AiTrace.GLOSSED),
        /** The LFM planner, including waiting for the AI thread and a lazy model load. */
        AI_PLAN("aiPlan", AiTrace.GLOSSED, AiTrace.PLANNED),
        /** Every planner step through the rules or a Needle tool call. */
        AI_TOOLS("aiTools", AiTrace.PLANNED, AiTrace.TOOLS),
        /** The utterance → the AI's plan shown or its error (not cancelled requests). */
        AI_TOTAL("aiTotal", AiTrace.START, AiTrace.DONE),
    }

    private val open = Stage.entries.associateWith { LinkedHashMap<Long, Long>() }
    private val spans = Stage.entries.associateWith { ArrayDeque<Double>() }

    init {
        require(window > 0 && maxOpen > 0) { "LatencyBook sizes must be positive" }
    }

    @Synchronized override fun invoke(event: TraceEvent) {
        for (stage in Stage.entries) {
            val starts = open.getValue(stage)
            if (event.name == stage.from) {
                if (event.id !in starts) {
                    if (starts.size >= maxOpen) starts.remove(starts.keys.first())
                    starts[event.id] = event.atNanos
                }
            } else if (event.name in stage.to) {
                val start = starts.remove(event.id) ?: continue
                val kept = spans.getValue(stage)
                if (kept.size >= window) kept.removeFirst()
                kept.addLast((event.atNanos - start) / 1e6)
            }
        }
    }

    /** Every stage by [Stage.key], in order; null until a span was measured. */
    @Synchronized fun summaries(): Map<String, LatencySummary?> =
        Stage.entries.associate { it.key to LatencyStats.summarize(spans.getValue(it)) }
}
