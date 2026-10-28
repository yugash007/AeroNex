package com.aeronex.app.session

import com.aeronex.app.observability.Trace
import com.aeronex.app.observability.TracePoint
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyBookTest {
    private val now = AtomicLong()
    private val trace = Trace(LongSupplier { now.get() })
    private val book = LatencyBook().also { trace.addSink(it) }

    private fun at(ms: Long, name: String, id: Long, fields: Map<String, Any?> = emptyMap()) {
        now.set(ms * 1_000_000)
        trace.mark(name, id, fields)
    }

    @Test fun everyStageIsReportedInOrderEmptyUntilMeasured() {
        val stages = book.summaries()
        assertEquals(LatencyBook.Stage.entries.map { it.key }, stages.keys.toList())
        assertEquals(listOf("pttUpToDrained", "drainedToFinal", "pttUpToFinal", "finalToPlan", "pttUpToPlan",
            "confirmToFrame", "confirmToTx", "confirmToAck", "confirmToMoveTx", "moveTxToEcho", "confirmToInAir",
            "stopToTx", "stopToAck", "aiGloss", "aiPlan", "aiTools", "aiTotal"), stages.keys.toList())
        assertNull(stages["moveTxToEcho"])
    }

    @Test fun spansPairTheFirstStartWithTheNextEndOfTheSameIdAsTheTraceDoes() {
        at(0, TracePoint.CONFIRM, 1)
        at(4, TracePoint.MOVE_TX, 1)
        at(150, TracePoint.ECHO, 1)
        at(3_000, TracePoint.MOVE_TX, 1) // the plan's second move
        at(3_250, TracePoint.ECHO, 1)
        at(3_260, TracePoint.ECHO, 1) // no open span: ignored
        at(5_000, TracePoint.STOP_TRIGGER, 2)
        at(5_001, TracePoint.STOP_TX, 2)
        at(5_008, TracePoint.STOP_ACK, 2)
        val stages = book.summaries()
        assertEquals(listOf(146.0, 250.0), trace.durationsMs(TracePoint.MOVE_TX, TracePoint.ECHO))
        assertEquals(2, stages["moveTxToEcho"]!!.count)
        assertEquals(146.0, stages["moveTxToEcho"]!!.p50Ms, 1e-9)
        assertEquals(250.0, stages["moveTxToEcho"]!!.p95Ms, 1e-9)
        assertEquals(4.0, stages["confirmToMoveTx"]!!.p50Ms, 1e-9)
        assertEquals(1, stages["confirmToMoveTx"]!!.count)
        assertEquals(8.0, stages["stopToAck"]!!.p95Ms, 1e-9)
        assertEquals(1.0, stages["stopToTx"]!!.p50Ms, 1e-9)
        assertNull(stages["confirmToAck"])
    }

    @Test fun theVoicePathAndTheFirstFrameAfterConfirmAreSpansToo() {
        val voice = VoiceSession.TRACE_ID_BASE + 1
        at(0, VoiceTrace.PTT_UP, voice)
        at(70, VoiceTrace.AUDIO_DRAINED, voice)
        at(80, VoiceTrace.ASR_FINAL, voice)
        at(81, TracePoint.PLAN_VALIDATED, 7) // the supervisor's own id: not this utterance
        at(83, TracePoint.PLAN_VALIDATED, voice)
        at(1_000, TracePoint.CONFIRM, 8)
        at(1_002, TracePoint.MOVE_TX, 8) // a move plan's first frame
        at(1_005, TracePoint.TX, 8)
        val stages = book.summaries()
        assertEquals(70.0, stages["pttUpToDrained"]!!.p50Ms, 1e-9)
        assertEquals(10.0, stages["drainedToFinal"]!!.p50Ms, 1e-9)
        assertEquals(80.0, stages["pttUpToFinal"]!!.p50Ms, 1e-9)
        assertEquals(3.0, stages["finalToPlan"]!!.p50Ms, 1e-9)
        assertEquals(83.0, stages["pttUpToPlan"]!!.p50Ms, 1e-9)
        assertEquals("the first frame of either kind", 2.0, stages["confirmToFrame"]!!.p50Ms, 1e-9)
        assertEquals(1, stages["confirmToFrame"]!!.count)
        assertEquals(5.0, stages["confirmToTx"]!!.p50Ms, 1e-9)
    }

    @Test fun keepsOnlyTheNewestSamplesAndForgetsSpansThatNeverClose() {
        val small = LatencyBook(window = 3, maxOpen = 2)
        trace.addSink(small)
        for (i in 1..5L) {
            at(i * 100, TracePoint.STOP_TRIGGER, i)
            at(i * 100 + i, TracePoint.STOP_TX, i)
        }
        assertEquals(3, small.summaries()["stopToTx"]!!.count)
        assertEquals(5.0, small.summaries()["stopToTx"]!!.maxMs, 1e-9)
        // STOPs that send nothing never close their span; only the newest maxOpen are kept.
        for (i in 10..20L) at(i * 100, TracePoint.STOP_TRIGGER, i)
        at(2_050, TracePoint.STOP_TX, 10) // its span was forgotten
        at(2_060, TracePoint.STOP_TX, 20)
        assertEquals(3, small.summaries()["stopToTx"]!!.count)
        assertEquals(60.0, small.summaries()["stopToTx"]!!.maxMs, 1e-9)
    }
}
