package com.aeronex.app.observability

import java.io.StringWriter
import java.util.function.LongSupplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceTest {
    private var now = 1_000_000_000L
    private val trace = Trace(clock = LongSupplier { now }, capacity = 8)

    @Test fun marksUseTheInjectedClockAndKeepFields() {
        val event = trace.mark(TracePoint.STOP_TRIGGER, id = 3, fields = mapOf("trigger" to "BUTTON"))
        assertEquals(TraceEvent(TracePoint.STOP_TRIGGER, 1_000_000_000L, 3, mapOf("trigger" to "BUTTON")), event)
        now += 5_000_000
        trace.mark(TracePoint.STOP_ACK, id = 3)
        assertEquals(listOf(TracePoint.STOP_TRIGGER, TracePoint.STOP_ACK), trace.events().map { it.name })
        assertEquals(listOf(5.0), trace.durationsMs(TracePoint.STOP_TRIGGER, TracePoint.STOP_ACK))
    }

    @Test fun anExplicitTimestampIsKeptForPointsTakenOnAnotherThread() {
        trace.mark(TracePoint.CONFIRM, id = 1, atNanos = 42)
        assertEquals(42L, trace.events().single().atNanos)
    }

    @Test fun spansPairEachStartWithTheNextEndOfTheSameId() {
        trace.mark(TracePoint.CONFIRM, id = 1)
        now += 2_000_000
        trace.mark(TracePoint.CONFIRM, id = 2) // a second plan, interleaved
        now += 1_000_000
        trace.mark(TracePoint.ACK, id = 1) // 3 ms after confirm 1
        now += 1_000_000
        trace.mark(TracePoint.ACK, id = 1) // later ACKs of the same plan are not a new span
        trace.mark(TracePoint.ACK, id = 2) // 2 ms after confirm 2
        assertEquals(listOf(3.0, 2.0), trace.durationsMs(TracePoint.CONFIRM, TracePoint.ACK))
    }

    @Test fun repeatedStartEndPairsGiveOneSpanEach() {
        for (i in 0 until 2) {
            trace.mark(TracePoint.MOVE_TX, id = 7)
            now += (i + 1) * 1_000_000L
            trace.mark(TracePoint.ECHO, id = 7)
        }
        assertEquals(listOf(1.0, 2.0), trace.durationsMs(TracePoint.MOVE_TX, TracePoint.ECHO))
    }

    @Test fun aFilterKeepsOnlyTheMatchingPoints() {
        trace.mark(TracePoint.CONFIRM, id = 1)
        now += 4_000_000
        trace.mark(TracePoint.ACK, id = 1, fields = mapOf("step" to 2)) // a later step's ACK: not "confirm -> first ACK"
        trace.mark(TracePoint.CONFIRM, id = 2)
        now += 1_000_000
        trace.mark(TracePoint.ACK, id = 2, fields = mapOf("step" to 1))
        val firstStep = { e: TraceEvent -> e.name != TracePoint.ACK || e.fields["step"] == 1 }
        assertEquals(listOf(1.0), trace.durationsMs(TracePoint.CONFIRM, TracePoint.ACK, firstStep))
        assertEquals(1.0, trace.stats(TracePoint.CONFIRM, TracePoint.ACK, firstStep)!!.p50Ms, 0.0)
    }

    @Test fun theRingKeepsTheNewestEvents() {
        for (i in 0 until 10) trace.mark("p$i")
        assertEquals((2 until 10).map { "p$it" }, trace.events().map { it.name })
    }

    @Test fun sinksSeeEveryEventAndAFailingSinkDoesNotBreakTracing() {
        val seen = mutableListOf<String>()
        trace.addSink { throw IllegalStateException("disk full") }
        trace.addSink { seen += it.name }
        trace.mark(TracePoint.LINK_UP)
        assertEquals(listOf(TracePoint.LINK_UP), seen)
        assertEquals(1L, trace.sinkErrors)
    }

    @Test fun statsSummariseASpan() {
        for (ms in listOf(10L, 20L, 30L, 40L)) {
            trace.mark(TracePoint.STOP_TRIGGER, id = ms)
            now += ms * 1_000_000
            trace.mark(TracePoint.STOP_ACK, id = ms)
        }
        val stats = trace.stats(TracePoint.STOP_TRIGGER, TracePoint.STOP_ACK)!!
        assertEquals(4, stats.count)
        assertEquals(20.0, stats.p50Ms, 0.0)
        assertEquals(40.0, stats.p95Ms, 0.0)
        assertNull(trace.stats(TracePoint.CONFIRM, TracePoint.ACK))
    }

    @Test fun jsonlWriterWritesOneEscapedObjectPerLine() {
        val out = StringWriter()
        val writer = JsonlTraceWriter(out)
        trace.addSink(writer)
        trace.mark(TracePoint.STOP_NOTHING_SENT, id = 2, fields = linkedMapOf(
            "reason" to "Pilot mode \"LOITER\", use RC\n", "held" to false, "mode" to 5L, "speed" to Double.NaN,
            "owner" to Kind.EXTERNAL, "none" to null,
        ))
        now += 1
        trace.mark(TracePoint.LINK_DOWN)
        writer.flush()
        val lines = out.toString().split("\n").filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertEquals(
            "{\"t_ns\":1000000000,\"event\":\"stop_nothing_sent\",\"id\":2,\"fields\":{" +
                "\"reason\":\"Pilot mode \\\"LOITER\\\", use RC\\n\",\"held\":false,\"mode\":5,\"speed\":null," +
                "\"owner\":\"EXTERNAL\",\"none\":null}}",
            lines[0],
        )
        assertEquals("{\"t_ns\":1000000001,\"event\":\"link_down\",\"id\":0,\"fields\":{}}", lines[1])
    }

    @Test fun jsonlEscapesControlCharacters() {
        assertTrue(JsonlTraceWriter.line(TraceEvent("x", 0, 0, mapOf("t" to "a\u0001b\tc\\"))).contains("\"a\\u0001b\\tc\\\\\""))
    }

    private enum class Kind { EXTERNAL }
}
