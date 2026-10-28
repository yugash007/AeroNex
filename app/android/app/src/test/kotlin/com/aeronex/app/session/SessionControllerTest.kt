package com.aeronex.app.session

import com.aeronex.app.link.Transport
import com.aeronex.app.link.TransportKind
import com.aeronex.app.safety.SafetySupervisor
import com.aeronex.app.safety.StopPolicy
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.FakeArduCopter
import com.aeronex.app.vehicle.executor.waitFor
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The fake vehicle's end of the link: while [slowMs] > 0 each write takes that long; a [stuck] write waits for close. */
private class Throttled(private val inner: Transport) : Transport by inner {
    @Volatile var slowMs = 0L
    @Volatile var stuck = false
    private val closed = CountDownLatch(1)

    override fun write(bytes: ByteArray): Boolean {
        if (stuck) closed.await(5, TimeUnit.SECONDS) else if (slowMs > 0) Thread.sleep(slowMs)
        return inner.write(bytes)
    }

    override fun close() {
        closed.countDown()
        inner.close()
    }
}

@Suppress("UNCHECKED_CAST")
class SessionControllerTest {
    private val vehicle = FakeArduCopter()
    private val opened = CopyOnWriteArrayList<Throttled>()
    private val released = AtomicInteger()
    private val threadSetups = CopyOnWriteArrayList<String>()
    private val controller = SessionController(
        transports = { TransportLease(Throttled(vehicle.transport()).also { opened += it }) { released.incrementAndGet() } },
        timingFor = { FAST_TIMING },
        linkThreadSetup = { threadSetups += Thread.currentThread().name },
    )
    private val config = SessionConfig(LinkType.UDP, "127.0.0.1", 14550, 0)

    @After fun tearDown() {
        controller.close()
        vehicle.close()
        assertTrue("never a forced disarm", vehicle.commands(400).none { it.param2 != 0f })
    }

    private fun snapshot() = controller.snapshot()
    private fun Map<String, Any?>.obj(key: String) = this[key] as Map<String, Any?>
    private fun Map<String, Any?>.objOrNull(key: String) = this[key] as Map<String, Any?>?

    private fun ready() = snapshot().let { s ->
        s.obj("link")["state"] == "up" && s.obj("vehicle").let { it["connected"] == true && it["homeKnown"] == true && it["positionReady"] == true }
    }

    private fun connect() {
        controller.connect(config)
        waitFor("link up with a ready vehicle") { ready() }
    }

    private fun step(name: String, arguments: Map<String, Any?> = emptyMap()) =
        mapOf("name" to name, "arguments" to arguments, "source" to "rules")
    private fun takeoff(m: Int = 3) = step("takeoff", mapOf("altitude_m" to m))
    private fun move(direction: String, m: Number) = step("move_relative", mapOf("direction" to direction, "distance_m" to m))
    private fun submit(vararg steps: Map<String, Any?>, utterance: String? = null) =
        controller.submitPlan(mapOf("steps" to steps.toList(), "source" to "typed", "utterance" to utterance))

    private fun runPhase() = snapshot().objOrNull("run")?.get("phase")
    private fun fly(vararg steps: Map<String, Any?>, timeoutMs: Long = 10_000): Map<String, Any?> {
        val token = submit(*steps)["token"] as String
        assertEquals(mapOf("accepted" to true), controller.confirm(token))
        waitFor("plan end", timeoutMs) { runPhase() == "FINISHED" || runPhase() == "FAILED" }
        return snapshot().obj("run")
    }

    @Test fun connectReturnsASnapshotAndTheLinkComesUp() {
        assertFalse(controller.active)
        val first = controller.connect(config)
        assertEquals(SessionSchema.VERSION, first["schemaVersion"])
        val link = first.obj("link")
        assertTrue(link["state"] == "connecting" || link["state"] == "up")
        assertEquals("udp", link["transport"])
        assertEquals("wifi", link["profile"])
        assertEquals("no MAVLink signing in this build", true, link["unsigned"])
        assertEquals(false, first.obj("execution")["enabled"])
        assertTrue(controller.active)
        waitFor("ready") { ready() }
        assertEquals(1, opened.size)
        assertEquals("each link thread got its setup", 2, threadSetups.size)
    }

    @Test fun eachConnectGetsANewTransportAndForgetsTheLastVehicle() {
        connect()
        assertTrue(controller.setExecution(true))
        controller.disconnect()
        assertFalse(controller.active)
        val down = snapshot()
        assertEquals("down", down.obj("link")["state"])
        assertEquals(false, down.obj("vehicle")["connected"])
        assertEquals("the store was reset", false, down.obj("vehicle")["homeKnown"])
        assertEquals(1, released.get())
        assertTrue(Thread.getAllStackTraces().keys.none { it.isAlive && it.name.endsWith("[${opened[0].description}]") })
        connect()
        assertEquals(2, opened.size)
        assertNotSame(opened[0], opened[1])
        assertEquals("execution is per connection (S6)", false, snapshot().obj("execution")["enabled"])
    }

    @Test fun aSecondConnectIsRefusedWhileConnected() {
        connect()
        try {
            controller.connect(config)
            fail("connected twice")
        } catch (e: SessionException) {
            assertEquals(SessionException.ALREADY_CONNECTED, e.code)
        }
        assertEquals(1, opened.size)
        assertEquals("up", snapshot().obj("link")["state"])
    }

    @Test fun aTransportThatCannotOpenFailsTheConnect() {
        val failing = SessionController(transports = {
            TransportLease(object : Transport {
                override val kind = TransportKind.UDP
                override val description = "busy port"
                override fun open() = throw IOException("Address already in use")
                override fun write(bytes: ByteArray) = false
                override fun read(buffer: ByteArray, timeoutMs: Int) = -1
                override fun close() {}
            }) { released.incrementAndGet() }
        })
        try {
            failing.connect(config)
            fail("connected")
        } catch (e: SessionException) {
            assertEquals(SessionException.CONNECT_FAILED, e.code)
            assertEquals("Cannot open the UDP link to 127.0.0.1:14550: Address already in use", e.message)
        } finally {
            failing.close()
        }
        assertEquals(1, released.get())
        assertFalse(failing.active)
    }

    @Test fun dryRunIsTheDefaultAndSendsNothing() {
        connect()
        val run = fly(takeoff(), step("land"))
        assertEquals("FINISHED", run["phase"])
        assertEquals(listOf("NOT_SENT", "NOT_SENT"), (run["outcomes"] as List<Map<String, Any?>>).map { it["outcome"] })
        Thread.sleep(100)
        assertTrue(vehicle.vehicleCommands().isEmpty())
        assertFalse("setExecution needs a link", SessionController().use { it.setExecution(true) })
    }

    @Test fun submitPlanShowsThePlanForConfirmation() {
        connect()
        val result = submit(takeoff(), step("land"), utterance = "take off to 3 m then land")
        val token = result["token"] as String
        assertEquals("1. Take off to 3 m; 2. Land", result["summary"])
        assertTrue(result["expiresInMs"].toString(), (result["expiresInMs"] as Long) in 14_000..15_000)
        val pending = snapshot().obj("pending")
        assertEquals(token, pending["token"])
        assertEquals("typed", pending["source"])
        assertEquals(listOf("Take off to 3 m", "Land"), (pending["steps"] as List<Map<String, Any?>>).map { it["description"] })
        val traced = controller.trace.events().single { it.name == SessionController.PLAN_SUBMITTED }
        assertEquals("take off to 3 m then land", traced.fields["utterance"])
        assertEquals(mapOf("accepted" to false, "error" to SafetySupervisor.TOKEN_MISMATCH), controller.confirm("other"))
        controller.invalidatePending(SessionController.BACKGROUNDED)
        waitFor("dropped when the app goes to the background") { snapshot()["pending"] == null }
        assertEquals(SessionController.BACKGROUNDED, snapshot()["notice"])
        assertEquals(mapOf("accepted" to false, "error" to SafetySupervisor.NO_PLAN), controller.confirm(token))
    }

    @Test fun rejectionsCarryTheOperatorMessage() {
        connect()
        assertEquals(mapOf("error" to "Tool is not allowed."), submit(step("arm")))
        assertEquals(mapOf("error" to "Land can only be the last step."), submit(step("land"), move("left", 1)))
        assertEquals(mapOf("error" to "The drone is on the ground. Start with a takeoff height, for example: " +
            "take off to 3 m and fly forward 3 m."), submit(move("forward", 1)))
        assertEquals(mapOf("error" to SessionSchema.aiStep(1)),
            controller.submitPlan(mapOf("steps" to listOf(move("left", 1) + ("source" to "ai")), "source" to "phone")))
        assertNull(snapshot()["pending"])
    }

    @Test fun statusAndHoldAnswerAtOnce() {
        connect()
        assertEquals(mapOf("immediate" to true, "outcome" to "NOT_SENT", "message" to "NOT SENT — dry run: get_status({})"),
            submit(step("get_status")))
        assertTrue(controller.setExecution(true))
        val status = submit(step("get_status"))
        assertEquals("COMPLETED", status["outcome"])
        assertEquals("COMPLETED — Connected · Disarmed · On ground · Stabilize · Alt 0.0 m · Battery 90% · Position ready",
            status["message"])
    }

    @Test fun liveFlightAndAStopMidMoveThatBrakes() {
        connect()
        assertTrue(controller.setExecution(true))
        assertEquals("FINISHED", fly(takeoff())["phase"])
        vehicle.speed = 2.0
        val token = submit(move("forward", 8))["token"] as String
        assertEquals(mapOf("accepted" to true), controller.confirm(token))
        waitFor("moving, echo seen") { vehicle.speedNow() > 1.0 && snapshot().obj("latency")["moveTxToEcho"] != null }
        assertTrue(controller.active)
        controller.stop(StopTrigger.BUTTON, heldOneSecond = false)
        waitFor("STOP confirmed") { snapshot().objOrNull("stop")?.get("phase") == "CONFIRMED" }
        val stop = snapshot().obj("stop")
        assertEquals("BRAKE", stop["action"])
        assertEquals("BUTTON", stop["trigger"])
        assertEquals(false, stop["alarm"])
        assertEquals(CopterMode.BRAKE, vehicle.mode)
        val run = snapshot().obj("run")
        assertEquals("FAILED", run["phase"])
        assertEquals("UNKNOWN", (run["outcomes"] as List<Map<String, Any?>>).single()["outcome"])
        val latency = snapshot().obj("latency")
        assertEquals(1, (latency["stopToAck"] as Map<String, Any?>)["n"])
        assertEquals(1, (latency["moveTxToEcho"] as Map<String, Any?>)["n"])
    }

    @Test fun stopWithoutASessionSendsNothingAndSaysUseRc() {
        controller.stop(StopTrigger.VOLUME_DOWN, heldOneSecond = false)
        waitFor("decided") { snapshot()["stop"] != null }
        assertEquals("NOTHING_SENT", snapshot().obj("stop")["phase"])
        assertEquals(StopPolicy.NOT_CONNECTED, snapshot().obj("stop")["message"])
    }

    @Test fun listenersHearEveryPlanAndLinkChange() {
        val calls = AtomicInteger()
        controller.addChangeListener { calls.incrementAndGet() }
        connect()
        val afterConnect = calls.get()
        assertTrue(afterConnect > 0)
        submit(takeoff())
        waitFor("told about the pending plan") { calls.get() > afterConnect }
    }

    @Test fun requestTelemetryAsksTheVehicleAgain() {
        assertFalse(controller.requestTelemetry())
        connect()
        val before = vehicle.commands(511).size
        assertTrue(controller.requestTelemetry())
        waitFor("rates requested again") { vehicle.commands(511).size > before }
    }

    @Test fun disconnectAbortsARunningPlanAsUnknown() {
        connect()
        assertTrue(controller.setExecution(true))
        fly(takeoff())
        vehicle.speed = 1.0
        val token = submit(move("forward", 8))["token"] as String
        controller.confirm(token)
        waitFor("moving") { vehicle.speedNow() > 0.5 }
        controller.disconnect()
        val run = snapshot().obj("run")
        assertEquals("FAILED", run["phase"])
        assertEquals("UNKNOWN — " + SafetySupervisor.DISCONNECTED,
            (run["outcomes"] as List<Map<String, Any?>>).single()["message"])
    }

    /** Live, airborne, and a move plan shown: the POSITION_TARGET echo stream is on (plan deviation row). */
    private fun streamingForAShownMove() {
        connect()
        assertTrue(controller.setExecution(true))
        assertEquals("FINISHED", fly(takeoff())["phase"])
        submit(move("forward", 2))
        waitFor("the echo stream is on") { vehicle.positionTargetIntervals().lastOrNull() == 200_000L }
    }

    @Test fun disconnectSendsTheEchoStreamOffBeforeTheLinkCloses() {
        streamingForAShownMove()
        // A slow link: the off request is still being written when the supervisor has let go of the link.
        opened.single().slowMs = 60
        controller.disconnect()
        assertEquals("POSITION_TARGET off reached the vehicle", -1L, vehicle.positionTargetIntervals().last())
        assertEquals("down", snapshot().obj("link")["state"])
    }

    @Test fun aStuckLinkHoldsDisconnectOnlyForTheBoundedDrain() {
        streamingForAShownMove()
        opened.single().stuck = true
        val started = System.nanoTime()
        controller.disconnect()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited for the drain: $tookMs ms", tookMs >= SessionController.DISCONNECT_DRAIN_MS - 20)
        assertTrue("but no longer than its bound: $tookMs ms", tookMs < SessionController.DISCONNECT_DRAIN_MS + 1_000)
        assertTrue(SessionController.DISCONNECT_DRAIN_MS <= 250)
        assertEquals("down", snapshot().obj("link")["state"])
        assertEquals(1, released.get())
    }
}
