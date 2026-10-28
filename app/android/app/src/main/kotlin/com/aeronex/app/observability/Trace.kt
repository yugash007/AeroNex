package com.aeronex.app.observability

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier

/** Trace point names (spec 5). Spans: confirm→tx, confirm→ack, move_tx→echo, stop_trigger→stop_tx/stop_ack. */
object TracePoint {
    const val LINK_UP = "link_up"
    const val LINK_DOWN = "link_down"
    const val PLAN_VALIDATED = "plan_validated"
    const val CONFIRM = "confirm"
    /** A COMMAND_LONG queued (fields: command, confirmation, step). */
    const val TX = "tx"
    /** The BODY_OFFSET_NED move frame queued (fields: direction, distance_m, step). */
    const val MOVE_TX = "move_tx"
    /** A plan step's COMMAND_ACK (fields: command, result, step). */
    const val ACK = "ack"
    const val ECHO = "echo"
    const val MODE_OBSERVED = "mode_observed"
    const val IN_AIR = "in_air"
    const val ARRIVED = "arrived"
    /** A step's final outcome (fields: step, status, message): every COMPLETED, NOT SENT, REJECTED, UNKNOWN, SKIPPED. */
    const val STEP_OUTCOME = "step_outcome"
    const val PLAN_ABORT = "plan_abort"
    /** The plan ended (fields: stage, result). */
    const val PLAN_END = "plan_end"
    const val MODE_OWNER = "mode_owner"
    const val STOP_TRIGGER = "stop_trigger"
    const val STOP_OVERRIDE = "stop_override"
    const val STOP_NOTHING_SENT = "stop_nothing_sent"
    const val STOP_TX = "stop_tx"
    const val STOP_ACK = "stop_ack"
    /** A heartbeat after the STOP's ACK shows its effect (BRAKE, LAND or disarmed). */
    const val STOP_VERIFIED = "stop_verified"
    const val STOP_NOT_CONFIRMED = "stop_not_confirmed"
}

/** One trace point: [atNanos] on the trace clock; [id] ties the points of one plan or one STOP together. */
data class TraceEvent(val name: String, val atNanos: Long, val id: Long = 0, val fields: Map<String, Any?> = emptyMap())

/**
 * Timestamped trace points for incident review and latency stats (spec 5, S11). Thread-safe; keeps the newest
 * [capacity] events and hands each one to the sinks (e.g. [JsonlTraceWriter]) on the marking thread, so a sink must
 * be quick. On Android pass `SystemClock::elapsedRealtimeNanos` as [clock]; the default suits JVM tests.
 */
class Trace(private val clock: LongSupplier = LongSupplier(System::nanoTime), private val capacity: Int = 4096) {
    private val ring = ArrayDeque<TraceEvent>()
    private val sinks = CopyOnWriteArrayList<(TraceEvent) -> Unit>()
    private val errors = AtomicLong()

    init {
        require(capacity > 0) { "Trace capacity must be positive" }
    }

    /** Sink exceptions caught so far; tracing never fails the caller. */
    val sinkErrors: Long get() = errors.get()

    fun now(): Long = clock.asLong

    fun mark(name: String, id: Long = 0, fields: Map<String, Any?> = emptyMap(), atNanos: Long = now()): TraceEvent {
        val event = TraceEvent(name, atNanos, id, fields)
        synchronized(ring) {
            if (ring.size == capacity) ring.removeFirst()
            ring.addLast(event)
        }
        for (sink in sinks) {
            try {
                sink(event)
            } catch (_: Exception) {
                errors.incrementAndGet()
            }
        }
        return event
    }

    fun addSink(sink: (TraceEvent) -> Unit) {
        sinks += sink
    }

    fun removeSink(sink: (TraceEvent) -> Unit) {
        sinks -= sink
    }

    fun events(): List<TraceEvent> = synchronized(ring) { ring.toList() }

    fun clear() = synchronized(ring) { ring.clear() }

    /**
     * Span durations in ms, in order of their end: per [TraceEvent.id], each [from] point opens a span that the next
     * [to] point of the same id closes; a [to] without an open span is ignored. Only points matching [where] count,
     * e.g. only step 1's ACK for "confirm → first ACK".
     */
    fun durationsMs(from: String, to: String, where: (TraceEvent) -> Boolean = { true }): List<Double> {
        val open = HashMap<Long, Long>()
        val spans = ArrayList<Double>()
        for (event in events()) {
            if (!where(event)) continue
            when (event.name) {
                from -> open.putIfAbsent(event.id, event.atNanos)
                to -> open.remove(event.id)?.let { spans += (event.atNanos - it) / 1e6 }
            }
        }
        return spans
    }

    fun stats(from: String, to: String, where: (TraceEvent) -> Boolean = { true }): LatencySummary? =
        LatencyStats.summarize(durationsMs(from, to, where))
}
