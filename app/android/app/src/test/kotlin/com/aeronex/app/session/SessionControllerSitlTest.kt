package com.aeronex.app.session

import com.aeronex.app.link.TelemetryProfile
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.observability.JsonlTraceWriter
import com.aeronex.app.observability.LatencyStats
import com.aeronex.app.observability.TracePoint
import com.aeronex.app.vehicle.executor.StopAction
import com.aeronex.app.vehicle.executor.waitFor
import java.io.File
import java.io.FileWriter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The session controller against ArduCopter SITL (MAVProxy `udpin` on 127.0.0.1:14550), driven through [SessionApi]
 * exactly as the `aeronex/session` channel drives it: connect → setExecution(true) → submitPlan → confirm. Skipped
 * unless Gradle runs with `-Psitl=1` and the simulator is running headless.
 *
 * Measures spec 5 M2 (move frame → POSITION_TARGET echo, Wi-Fi target p95 ≤ 300 ms) over plans of five short moves,
 * confirmed after an operator's reading pause, and checks a STOP mid-move gets a BRAKE ACK. One plan is confirmed at
 * once and reported but not asserted: its first move shows the stream's start-up delay that the pause hides.
 *
 * The echo stream's 5 Hz runs on simulated time, and within a plan each move frame leaves right after the sample that
 * showed the previous arrival, so its echo is one stream period later: 200 ms at real time. Two bounds (plan
 * deviation row "10.4 M2 in SITL"):
 * - M2 at real time: the wall-clock p95 × the simulator's speed (measured from ArduCopter's 1 Hz heartbeat and from
 *   the echo stream itself) ≤ 300 ms;
 * - a wall bound that does not scale the app's own time: the wall-clock p95 ≤ the median measured stream period
 *   (wall) + 60 ms, so link, RX and supervisor delay cannot hide behind a slow simulator.
 * Both are printed with the wall-clock figures. This is not a raw wall-clock M2 pass on a slow simulator.
 */
@Suppress("UNCHECKED_CAST")
class SessionControllerSitlTest {
    // Lazy: nothing starts when the test is skipped.
    private val controller by lazy { SessionController() }
    private val api by lazy { SessionApi(controller) }

    private fun ok(method: String, arguments: Any? = null): Any? {
        val reply = api.call(method, arguments)
        if (reply !is SessionApi.Reply.Ok) throw AssertionError("$method -> $reply")
        return reply.value
    }

    private fun snapshot() = ok("getSnapshot") as Map<String, Any?>
    private fun Map<String, Any?>.obj(key: String) = this[key] as Map<String, Any?>
    private fun vehicle() = snapshot().obj("vehicle")
    private fun age(key: String) = (vehicle().obj("ages")[key] as Number?)?.toLong() ?: Long.MAX_VALUE

    private fun ready(): Boolean {
        val v = vehicle()
        return snapshot().obj("link")["state"] == "up" && v["connected"] == true && v["positionReady"] == true &&
            v["homeKnown"] == true && v["healthy"] == true && age("globalPosition") < 3_000 && age("localPosition") < 3_000
    }

    private fun onGround() = vehicle().let { it["armed"] == false && it["landedStateName"] == "ON_GROUND" }

    private fun step(name: String, arguments: Map<String, Any?> = emptyMap()) =
        mapOf("name" to name, "arguments" to arguments, "source" to "rules")
    private fun move(direction: String, m: Number) = step("move_relative", mapOf("direction" to direction, "distance_m" to m))

    private fun lastConfirmId() = controller.trace.events().last { it.name == TracePoint.CONFIRM }.id

    /** submitPlan, then the operator reads the card for [pauseMs] and taps CONFIRM; returns the plan's trace id. */
    private fun confirmPlan(steps: List<Map<String, Any?>>, pauseMs: Long): Long {
        val shown = ok("submitPlan", mapOf("steps" to steps, "source" to "tap")) as Map<String, Any?>
        val token = shown["token"] as? String ?: throw AssertionError("not shown for confirmation: $shown")
        if (pauseMs > 0) Thread.sleep(pauseMs)
        assertEquals(mapOf("accepted" to true), ok("confirm", token))
        return lastConfirmId()
    }

    private fun fly(steps: List<Map<String, Any?>>, pauseMs: Long = OPERATOR_PAUSE_MS, timeoutMs: Long = 120_000): Pair<Long, Map<String, Any?>> {
        val id = confirmPlan(steps, pauseMs)
        waitFor("plan end", timeoutMs) { snapshot()["run"].let { it is Map<*, *> && (it["phase"] == "FINISHED" || it["phase"] == "FAILED") } }
        val run = snapshot().obj("run")
        println("SITL session plan ${run["summary"]} -> ${run["phase"]}\n${run["message"]}")
        return id to run
    }

    private fun assertCompleted(run: Map<String, Any?>) {
        assertEquals(run["message"].toString(), "FINISHED", run["phase"])
        assertTrue(run["message"].toString(), (run["outcomes"] as List<Map<String, Any?>>).all { it["outcome"] == "COMPLETED" })
    }

    private fun takeoff() {
        waitFor("on the ground, disarmed", 60_000) { onGround() }
        repeat(3) { attempt ->
            waitFor("EKF position, home, telemetry and health", 180_000) { ready() }
            val (_, run) = fly(listOf(step("takeoff", mapOf("altitude_m" to 3))))
            if (run["phase"] == "FINISHED") return
            println("SITL takeoff attempt ${attempt + 1} failed: ${run["message"]}")
            waitFor("disarmed again", 30_000) { vehicle()["armed"] == false }
            Thread.sleep(5_000)
        }
        throw AssertionError("takeoff failed three times")
    }

    private fun echoes(planId: Long) = controller.trace.durationsMs(TracePoint.MOVE_TX, TracePoint.ECHO) { it.id == planId }

    /**
     * The wall-clock interval of ArduCopter's 1 Hz (simulated time) heartbeat over [beats] heartbeats: 1000 ms is real
     * time. The POSITION_TARGET stream's 5 Hz is simulated time too, so a slow simulator stretches every echo wait.
     */
    private fun heartbeatIntervalMs(beats: Int = 8, timeoutMs: Long = 40_000): Double {
        fun count() = controller.vehicle().samples[Heartbeat.messageId] ?: 0
        val start = count()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (count() == start && System.nanoTime() < deadline) Thread.sleep(1)
        val firstCount = count()
        val firstAt = System.nanoTime()
        while (count() < firstCount + beats && System.nanoTime() < deadline) Thread.sleep(1)
        val n = count() - firstCount
        return if (n > 0) (System.nanoTime() - firstAt) / 1e6 / n else Double.NaN
    }

    /** Wall-clock times at which another POSITION_TARGET_LOCAL_NED arrived, polled every millisecond. */
    private inner class StreamSampler : AutoCloseable {
        private val times = CopyOnWriteArrayList<Long>()
        @Volatile private var running = true
        private val poller = thread(name = "sitl-position-target-sampler", isDaemon = true) {
            var last = controller.vehicle().positionTargetCount
            while (running) {
                val now = controller.vehicle().positionTargetCount
                if (now != last) {
                    times += System.nanoTime()
                    last = now
                }
                Thread.sleep(1)
            }
        }

        /** The median gap between samples while the stream runs (gaps over 1 s are the stream being off). */
        fun medianPeriodMs(): Double {
            val gaps = times.zipWithNext { a, b -> (b - a) / 1e6 }.filter { it < 1_000.0 }.sorted()
            return if (gaps.isEmpty()) Double.NaN else gaps[gaps.size / 2]
        }

        override fun close() {
            running = false
            poller.join(1_000)
        }
    }

    private fun moves(last: String) = listOf(move("forward", 2), move("right", 2), move("backward", 2), move("left", 2), move(last, 1))

    @Test fun takeoffMovesStopAndLandThroughTheSessionController() {
        assumeTrue("run with -Psitl=1 and ArduCopter SITL on 127.0.0.1:14550", System.getProperty("aeronex.sitl") == "1")
        val traceFile = File(System.getProperty("java.io.tmpdir"), "aeronex-session-sitl-trace.jsonl")
        val writer = FileWriter(traceFile)
        controller.trace.addSink(JsonlTraceWriter(writer))
        try {
            ok("connect", mapOf("transport" to "udp", "host" to "127.0.0.1", "remotePort" to 14550, "localPort" to 0,
                "radioLink" to false))
            waitFor("SITL heartbeat", 60_000) { vehicle()["connected"] == true }
            val intervalAtStart = heartbeatIntervalMs()
            println("SITL heartbeat interval at start: $intervalAtStart ms (1000 = real time)")
            assertEquals(mapOf("enabled" to true), ok("setExecution", true))
            takeoff()

            // Diagnostic: confirmed the instant it is shown, so the echo stream starts with the first move frame.
            val (atOnceId, atOnce) = fly(moves("forward"), pauseMs = 0)
            assertCompleted(atOnce)
            val atOnceEchoes = echoes(atOnceId)

            // As an operator confirms: the stream runs from the moment the plan is shown.
            val measured = ArrayList<Double>()
            val firstMoves = ArrayList<Double>()
            val sampler = StreamSampler()
            try {
                for (k in 0 until PLANS) {
                    val (id, run) = fly(moves(if (k % 2 == 0) "backward" else "forward"))
                    assertCompleted(run)
                    val spans = echoes(id)
                    assertEquals("one echo per move", 5, spans.size)
                    firstMoves += spans.first()
                    measured += spans
                }
            } finally {
                sampler.close()
            }
            val streamPeriodMs = sampler.medianPeriodMs()
            val summary = LatencyStats.summarize(measured)!!
            println("SITL M2 move frame -> echo, plan confirmed at once (not asserted): $atOnceEchoes")
            println("SITL M2 move frame -> echo, first move of each confirmed-after-a-pause plan: $firstMoves")
            println("SITL M2 move frame -> echo (asserted, n=${summary.count}): p50=${summary.p50Ms} ms p95=${summary.p95Ms} ms " +
                "max=${summary.maxMs} ms; all=$measured")
            println("SITL snapshot latency: ${snapshot()["latency"]}")

            // STOP from the on-screen button while a move is under way.
            val stopsBefore = controller.trace.events().count { it.name == TracePoint.STOP_TRIGGER }
            confirmPlan(listOf(move("forward", 8)), OPERATOR_PAUSE_MS)
            waitFor("moving", 30_000) { ((vehicle()["speed"] as Double?) ?: 0.0) > 1.0 }
            assertEquals(null, ok("stop", mapOf("heldOneSecond" to false)))
            waitFor("STOP decided", 10_000) { (snapshot()["stop"] as Map<String, Any?>?)?.get("phase").let { it == "CONFIRMED" || it == "NOT_CONFIRMED" } }
            val stop = snapshot().obj("stop")
            assertEquals(stop.toString(), "CONFIRMED", stop["phase"])
            assertEquals("BRAKE", stop["action"])
            val stopId = controller.trace.events().filter { it.name == TracePoint.STOP_TRIGGER }.drop(stopsBefore).single().id
            val acks = controller.trace.events().filter { it.id == stopId && it.name == TracePoint.STOP_ACK }
            assertEquals(listOf<Pair<Any?, Any?>>(StopAction.BRAKE to 0), acks.map { it.fields["action"] to it.fields["result"] })
            val stopToAck = controller.trace.durationsMs(TracePoint.STOP_TRIGGER, TracePoint.STOP_ACK) { it.id == stopId }.single()
            println("SITL STOP (controller, mid-move) -> BRAKE ACK: $stopToAck ms")
            waitFor("still", 20_000) { ((vehicle()["speed"] as Double?) ?: 9.0) < 0.3 }

            val (_, landed) = fly(listOf(step("land")))
            assertCompleted(landed)
            waitFor("disarmed", 60_000) { vehicle()["armed"] == false }
            val intervalAtEnd = heartbeatIntervalMs()
            println("SITL heartbeat interval at the end: $intervalAtEnd ms")
            println("SITL link: ${snapshot()["link"]}")
            // Spec M2 assumes a real-time autopilot. Scale the wall-clock p95 by the simulator's speed, from the 1 Hz
            // heartbeat and from the 5 Hz echo stream itself, taking the fastest estimate (the smallest correction);
            // at real time this is the wall-clock value.
            val streamSpeed = 1_000.0 / TelemetryProfile.WIFI.positionTargetHz / streamPeriodMs
            val heartbeatSpeed = 1_000.0 / minOf(intervalAtStart, intervalAtEnd)
            val speed = minOf(1.0, maxOf(heartbeatSpeed, streamSpeed))
            val realTimeP95 = summary.p95Ms * speed
            println("SITL echo stream period (wall, median while on): %.1f ms -> speed %.3f; heartbeat speed %.3f"
                .format(streamPeriodMs, streamSpeed, heartbeatSpeed))
            println("SITL M2 p95 at real time: %.1f ms (wall %.1f ms x simulator speed %.3f); p50 at real time %.1f ms"
                .format(realTimeP95, summary.p95Ms, speed, summary.p50Ms * speed))
            val wallBoundMs = streamPeriodMs + WALL_MARGIN_MS
            println("SITL M2 wall bound: p95 %.1f ms (wall) <= median stream period %.1f ms + %.0f ms = %.1f ms"
                .format(summary.p95Ms, streamPeriodMs, WALL_MARGIN_MS, wallBoundMs))
            assertTrue("simulator speed unknown", speed.isFinite() && speed > 0.3)
            assertTrue("M2 p95 %.1f ms at real time (wall %.1f ms, speed %.3f) > 300 ms over n=${summary.count}: $measured"
                .format(realTimeP95, summary.p95Ms, speed), realTimeP95 <= 300.0)
            assertTrue("stream period unknown", streamPeriodMs.isFinite())
            assertTrue("M2 wall p95 %.1f ms > median stream period %.1f ms + %.0f ms over n=${summary.count}: $measured"
                .format(summary.p95Ms, streamPeriodMs, WALL_MARGIN_MS), summary.p95Ms <= wallBoundMs)
        } finally {
            if (vehicle()["armed"] == true) {
                api.call("submitPlan", mapOf("steps" to listOf(step("land")), "source" to "tap"))
                    .let { (it as? SessionApi.Reply.Ok)?.value as? Map<String, Any?> }
                    ?.get("token")?.let { api.call("confirm", it) }
                waitFor("landed and disarmed after a failure", 120_000) { vehicle()["armed"] == false }
            }
            controller.close()
            writer.close()
            println("SITL session trace JSONL: $traceFile")
        }
    }

    private companion object {
        /** Reading the plan card and tapping CONFIRM; human confirmation time is not in the latency budget (spec 5). */
        const val OPERATOR_PAUSE_MS = 1_000L
        const val PLANS = 4
        /** App-side allowance over one wall-clock stream period: link TX/RX, the supervisor turn, scheduling. */
        const val WALL_MARGIN_MS = 60.0
    }
}
