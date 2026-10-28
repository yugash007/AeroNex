package com.aeronex.app.safety

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.link.LinkListener
import com.aeronex.app.link.LinkSession
import com.aeronex.app.link.TelemetryProfile
import com.aeronex.app.link.UdpTransport
import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.CommandLong
import com.aeronex.app.link.mavlink.GlobalPositionInt
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.LocalPositionNed
import com.aeronex.app.link.mavlink.MavlinkCrc
import com.aeronex.app.link.mavlink.MavlinkEncoder
import com.aeronex.app.link.mavlink.MavlinkFrame
import com.aeronex.app.link.mavlink.MavlinkParser
import com.aeronex.app.link.mavlink.ParamRequestRead
import com.aeronex.app.link.mavlink.ParamValue
import com.aeronex.app.link.mavlink.PositionTargetLocalNed
import com.aeronex.app.observability.JsonlTraceWriter
import com.aeronex.app.observability.LatencyStats
import com.aeronex.app.observability.Trace
import com.aeronex.app.observability.TraceEvent
import com.aeronex.app.observability.TracePoint
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.VehicleStateStore
import com.aeronex.app.vehicle.executor.ExecutorTiming
import com.aeronex.app.vehicle.executor.StopAction
import com.aeronex.app.vehicle.executor.waitFor
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.hypot
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * The supervisor, executor and STOP lane against the real ArduCopter 4.6 SITL (Gazebo, MAVProxy `udpin` on
 * 127.0.0.1:14550, this link bound to local 14551). Skipped unless Gradle runs with `-Psitl=1`; start the simulator
 * first (Start-Simulation.ps1 -Headless). A second MAVLink client (sysid 254) on SITL's SERIAL1 TCP port 5762 plays
 * the RC pilot (mode switches) and the test's parameter tool (PARAM_SET is not something the app can send).
 *
 * The tests run in name order and share one flight; every parameter changed is restored in [stopAll]. They wait for
 * ArduCopter's pre-arm health (SYS_STATUS) as well as the EKF, so they also pass on a freshly started simulator.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SupervisorSitlTest {
    companion object {
        private val enabled = System.getProperty("aeronex.sitl") == "1"
        private val params = ConcurrentLinkedQueue<ParamValue>()
        private val restore = LinkedHashMap<String, Float>()
        private val latencyStopIds = mutableListOf<Long>()
        /** Every POSITION_TARGET_LOCAL_NED received (test-only trace point), to see where the echo time goes. */
        private const val PT_RX = "sitl_pt_rx"
        private lateinit var store: VehicleStateStore
        private lateinit var trace: Trace
        private lateinit var supervisor: SafetySupervisor
        private lateinit var link: LinkSession
        private lateinit var side: SideChannel
        private var traceFile: FileWriter? = null

        @BeforeClass @JvmStatic fun startAll() {
            if (!enabled) return
            store = VehicleStateStore()
            trace = Trace()
            File(System.getProperty("java.io.tmpdir"), "aeronex-sitl-trace.jsonl").let { file ->
                traceFile = FileWriter(file).also { trace.addSink(JsonlTraceWriter(it)) }
                println("SITL trace JSONL: $file")
            }
            supervisor = SafetySupervisor(store, PlanValidator(), trace)
            val transport = UdpTransport("127.0.0.1", 14550, 14551, localAddress = "127.0.0.1")
            link = LinkSession(transport, MavlinkEncoder(), MavlinkParser(), TelemetryProfile.WIFI, object : LinkListener {
                override fun onFrame(frame: MavlinkFrame) {
                    store.accept(frame)
                    when (val message = frame.message) {
                        is ParamValue -> params.add(message)
                        is PositionTargetLocalNed -> if (frame.sysId == 1) trace.mark(PT_RX, fields = mapOf(
                            "x" to message.x, "y" to message.y, "z" to message.z))
                        else -> {}
                    }
                }
                override fun onLinkState(up: Boolean) = supervisor.onLinkState(up)
            })
            link.start()
            supervisor.attach(link, ExecutorTiming.WIFI).get(5, TimeUnit.SECONDS)
            supervisor.setExecution(true).get(5, TimeUnit.SECONDS)
            side = SideChannel()
            waitFor("SITL heartbeat", 60_000) { store.snapshot().connected }
            waitReady()
            println("SITL heartbeat interval at start: ${heartbeatIntervalMs()} ms (1000 = real time; above ~3000 the link flaps)")
        }

        /** The mean wall-clock interval of ArduCopter's 1 Hz heartbeat over a few seconds: the simulator's speed. */
        private fun heartbeatIntervalMs(windowMs: Long = 6_000): Long {
            val first = store.snapshot().samples[Heartbeat.messageId] ?: 0
            Thread.sleep(windowMs)
            val beats = (store.snapshot().samples[Heartbeat.messageId] ?: 0) - first
            return if (beats > 0) windowMs / beats else -1
        }

        @AfterClass @JvmStatic fun stopAll() {
            if (!enabled) return
            try {
                for ((name, value) in restore.entries.reversed()) setParam(name, value)
                if (restore.isNotEmpty()) waitReady()
                if (store.snapshot().armed == true) {
                    side.command(setMode(CopterMode.LAND))
                    waitFor("landed and disarmed", 90_000) { store.snapshot().armed == false }
                }
            } finally {
                printSummary()
                supervisor.close()
                link.stop()
                side.close()
                traceFile?.close()
            }
        }

        private fun printSummary() {
            val stops = trace.events().filter { it.id in latencyStopIds }
            val stopAck = durations(stops, TracePoint.STOP_TRIGGER, TracePoint.STOP_ACK)
            val stopTx = durations(stops, TracePoint.STOP_TRIGGER, TracePoint.STOP_TX)
            val stopSeen = durations(stops, TracePoint.STOP_TRIGGER, TracePoint.STOP_VERIFIED)
            println("SITL STOP button -> stop frame queued (10 trials): ${LatencyStats.summarize(stopTx)}")
            println("SITL STOP button -> BRAKE ACK (10 trials): ${LatencyStats.summarize(stopAck)}  all=$stopAck")
            println("SITL STOP button -> heartbeat shows BRAKE (10 trials): ${LatencyStats.summarize(stopSeen)}")
            // Only plans whose step 1 starts with a COMMAND_LONG (takeoff, land, a move that sets GUIDED first).
            val firstStep = { e: TraceEvent -> e.name == TracePoint.CONFIRM || e.fields["step"] == 1 }
            println("SITL confirm -> first TX (step 1 COMMAND_LONG): ${trace.stats(TracePoint.CONFIRM, TracePoint.TX, firstStep)}")
            println("SITL confirm -> first ACK (step 1 COMMAND_LONG): ${trace.stats(TracePoint.CONFIRM, TracePoint.ACK, firstStep)}")
            println("SITL confirm -> move frame queued (step 1 moves): ${trace.stats(TracePoint.CONFIRM, TracePoint.MOVE_TX, firstStep)}")
            println("SITL confirm -> echo (step 1 moves): ${trace.stats(TracePoint.CONFIRM, TracePoint.ECHO, firstStep)}")
            println("SITL move frame -> echo (all moves): ${trace.stats(TracePoint.MOVE_TX, TracePoint.ECHO)}")
            println("SITL confirm -> in_air (takeoffs): ${trace.stats(TracePoint.CONFIRM, TracePoint.IN_AIR)}")
            printEchoDiagnostics()
            println("SITL heartbeat interval at the end: ${heartbeatIntervalMs()} ms")
            println("SITL link stats: ${link.stats}")
        }

        /** Per move: when the first POSITION_TARGET_LOCAL_NED came after the frame, and whether it was the echo. */
        private fun printEchoDiagnostics() {
            val events = trace.events()
            val firstRx = ArrayList<Double>()
            var firstWasEcho = 0
            for ((i, move) in events.withIndex()) {
                if (move.name != TracePoint.MOVE_TX) continue
                val rx = events.drop(i + 1).firstOrNull { it.name == PT_RX } ?: continue
                val echo = events.drop(i + 1).firstOrNull { it.name == TracePoint.ECHO && it.id == move.id }
                firstRx += (rx.atNanos - move.atNanos) / 1e6
                if (echo != null && events.drop(i + 1).takeWhile { it !== echo }.none { it.name == PT_RX && it !== rx }) firstWasEcho++
            }
            println("SITL move frame -> first POSITION_TARGET received: ${LatencyStats.summarize(firstRx)}; " +
                "the first one was already the echo in $firstWasEcho of ${firstRx.size}")
        }

        private fun durations(events: List<com.aeronex.app.observability.TraceEvent>, from: String, to: String) =
            events.groupBy { it.id }.values.mapNotNull { group ->
                val start = group.firstOrNull { it.name == from } ?: return@mapNotNull null
                val end = group.firstOrNull { it.name == to && it.atNanos >= start.atNanos } ?: return@mapNotNull null
                (end.atNanos - start.atNanos) / 1e6
            }

        /** EKF position, home, fresh telemetry and SYS_STATUS health (which includes ArduPilot's pre-arm checks). */
        private fun ready(s: VehicleState) = s.connected && s.positionReady && s.home != null && s.healthy == true &&
            s.fresh(LocalPositionNed.messageId) && s.fresh(GlobalPositionInt.messageId) && s.landedState != null

        private fun waitReady() = waitFor("EKF position, home, telemetry and pre-arm health", 180_000) { ready(store.snapshot()) }

        private fun setMode(mode: Long) = CommandLong(1, 1, 176, param1 = 1f, param2 = mode.toFloat())

        /** Reads a parameter over the app link (PARAM_REQUEST_READ is allowed; PARAM_VALUE is decoded). */
        private fun readParam(name: String): Float {
            params.clear()
            link.send(ParamRequestRead(1, 1, name))
            var value: Float? = null
            waitFor("PARAM_VALUE $name", 10_000) {
                value = params.firstOrNull { it.paramId == name }?.paramValue
                value != null
            }
            return value!!
        }

        /** PARAM_SET through the side channel; confirmed by the PARAM_VALUE the autopilot broadcasts. */
        private fun setParam(name: String, value: Float) {
            params.clear()
            side.paramSet(name, value)
            waitFor("PARAM_VALUE $name=$value", 10_000) { params.any { it.paramId == name && it.paramValue == value } }
        }

        /** Sets [name] for this run and remembers the original for [stopAll]. */
        private fun override(name: String, value: Float) {
            if (name !in restore) restore[name] = readParam(name)
            setParam(name, value)
        }
    }

    @Before fun requireSitl() {
        assumeTrue("run with -Psitl=1 and ArduCopter SITL on 127.0.0.1:14550", enabled)
    }

    private fun now() = System.nanoTime() / 1_000_000
    private fun plan(vararg commands: FlightCommand) = FlightPlan(commands.map { PlanStep(it, StepSource.RULES) })
    private fun takeoffCommand(m: Int = 3) = FlightCommand.of(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to m))
    private fun move(direction: String, m: Number) =
        FlightCommand.of(FlightCommand.MOVE, mapOf(FlightCommand.DIRECTION to direction, FlightCommand.DISTANCE to m))
    private val land = FlightCommand.of(FlightCommand.LAND)

    /** Why a run may fail for the environment, not the app: SITL heartbeat gaps over 3 s drop the link. */
    private fun stallNote(): String {
        val downs = trace.events().count { it.name == TracePoint.LINK_DOWN }
        return if (downs == 0) "" else " [the link went down $downs time(s): SITL heartbeat gaps over 3 s; after such a gap " +
            "the app treats the mode as EXTERNAL by design (spec 4.12), so plans are refused. Run SITL near real time.]"
    }

    private fun confirmPlan(plan: FlightPlan) {
        val submitted = supervisor.submit(plan, PlanSource.TYPED).get(5, TimeUnit.SECONDS)
        val pending = (submitted as? SubmitResult.AwaitingConfirmation)?.pending
            ?: throw AssertionError("$plan was not accepted for confirmation: $submitted${stallNote()}")
        assertEquals(ConfirmResult.Started, supervisor.confirm(pending.token).get(5, TimeUnit.SECONDS))
    }

    private fun fly(plan: FlightPlan, timeoutMs: Long = 90_000): SupervisorState {
        confirmPlan(plan)
        waitFor("$plan to end", timeoutMs) { supervisor.state.stage.let { it == PlanStage.FINISHED || it == PlanStage.FAILED } }
        val state = supervisor.state
        println("SITL plan $plan -> ${state.stage}\n${state.result}")
        return state
    }

    private fun assertCompleted(state: SupervisorState) {
        assertEquals(state.result, PlanStage.FINISHED, state.stage)
        assertTrue(state.result, state.outcomes.all { it.status == StepStatus.COMPLETED })
    }

    /** Takes off to [altitude] from the ground, once the vehicle is ready (a failed attempt is retried, and logged). */
    private fun takeoff(altitude: Int = 3) {
        waitFor("on the ground, disarmed", 60_000) { store.snapshot().let { it.armed == false && it.landedState == VehicleState.LANDED_ON_GROUND } }
        repeat(3) { attempt ->
            waitReady()
            val state = fly(plan(takeoffCommand(altitude)))
            if (state.stage == PlanStage.FINISHED) return
            println("SITL takeoff attempt ${attempt + 1} failed: ${state.result}")
            waitFor("disarmed again", 30_000) { store.snapshot().armed == false }
            Thread.sleep(5_000)
        }
        throw AssertionError("takeoff failed three times")
    }

    private fun landHere() {
        assertCompleted(fly(plan(land)))
        waitFor("disarmed", 30_000) { store.snapshot().armed == false }
    }

    /** Starts a move plan and waits until its echo is seen and the vehicle is moving. */
    private fun startMove(direction: String, distance: Int, minSpeed: Double = 1.0) {
        confirmPlan(plan(move(direction, distance)))
        waitFor("moving", 20_000) {
            val s = store.snapshot()
            supervisor.state.stage == PlanStage.EXECUTING && (s.speed ?: 0.0) > minSpeed
        }
    }

    /**
     * Triggers a STOP (or Cancel) and waits for that STOP's own decision, never an earlier one; a STOP that was sent
     * is also waited out until a heartbeat shows it or it turns NOT CONFIRMED (the lane's check), and the returned
     * status is the final one.
     */
    private fun stopAndWait(trigger: StopTrigger = StopTrigger.BUTTON, held: Boolean = false, timeoutMs: Long = 8_000): StopStatus {
        val previous = supervisor.state.stop?.id
        if (trigger == StopTrigger.CANCEL) supervisor.cancel() else supervisor.stop(trigger, held)
        waitFor("STOP decided", timeoutMs) {
            supervisor.state.stop?.let { it.id != previous && it.phase != StopPhase.SENDING } == true
        }
        return settled(supervisor.state.stop!!, timeoutMs)
    }

    private fun settled(stop: StopStatus, timeoutMs: Long = 8_000): StopStatus {
        if (stop.phase == StopPhase.NOTHING_SENT) return stop
        waitFor("STOP ${stop.id} checked by heartbeat", timeoutMs) {
            trace.events().any { it.id == stop.id && (it.name == TracePoint.STOP_VERIFIED || it.name == TracePoint.STOP_NOT_CONFIRMED) }
        }
        return supervisor.state.stop!!.takeIf { it.id == stop.id } ?: stop
    }

    private fun verified(stop: StopStatus) = trace.events().any { it.id == stop.id && it.name == TracePoint.STOP_VERIFIED }

    private fun waitStill(timeoutMs: Long = 15_000) =
        waitFor("speed < 0.3 m/s", timeoutMs) { (store.snapshot().speed ?: 9.0) < 0.3 }

    private fun newAck(command: Int, after: Long): CommandAck? =
        store.snapshot().acks[command]?.takeIf { it.atMs >= after }?.message

    @Test fun t01_connectsWithEkfPositionAndHome() {
        val s = store.snapshot()
        assertTrue(ready(s))
        println("SITL ready: mode=${s.mode?.let(CopterMode::name)} owner=${s.modeOwner} home=${s.home} ekf=0x${s.ekfFlags?.toString(16)}")
    }

    @Test fun t02_takeoffMoveForwardLand() {
        takeoff()
        assertCompleted(fly(plan(move("forward", 2), land)))
        waitFor("disarmed", 30_000) { store.snapshot().armed == false }
    }

    @Test fun t03_stopWhileArmedOnTheGroundDisarmsNonForced() {
        waitFor("on the ground, disarmed", 60_000) { store.snapshot().let { it.armed == false && it.landedState == VehicleState.LANDED_ON_GROUND } }
        waitReady() // pre-arm health included: ArduCopter refuses to arm before it
        val sent = now()
        link.send(setMode(CopterMode.GUIDED))
        waitFor("GUIDED ACK", 5_000) { newAck(176, sent)?.result == 0 }
        store.noteCommandedMode(CopterMode.GUIDED) // as the app's own accepted DO_SET_MODE
        val armSent = now()
        link.send(CommandLong(1, 1, 400, param1 = 1f))
        waitFor("ARM ACK", 5_000) { newAck(400, armSent) != null }
        assertEquals("ARM accepted", 0, newAck(400, armSent)!!.result)
        waitFor("armed, app-owned", 10_000) { store.snapshot().let { it.armed == true && it.modeOwner == ModeOwner.APP } }
        val stop = stopAndWait()
        assertEquals(StopPhase.CONFIRMED, stop.phase)
        assertEquals(StopAction.DISARM, stop.action)
        assertTrue(stop.trigger == StopTrigger.BUTTON && !stop.alarm)
        assertTrue("a heartbeat showed it disarmed", verified(stop))
        waitFor("disarmed", 5_000) { store.snapshot().armed == false }
    }

    @Test fun t03b_stopDuringTheArmRoundTripDisarmsAndNoTakeoffFollows() {
        waitFor("on the ground, disarmed", 60_000) { store.snapshot().let { it.armed == false && it.landedState == VehicleState.LANDED_ON_GROUND } }
        waitReady()
        val before = trace.events().size
        val previous = supervisor.state.stop?.id
        val purged = link.stats.purgedByPriority
        val fired = AtomicBoolean(false)
        // The STOP button, pressed the moment the ARM is queued: the supervisor handles it before the ARM's ACK.
        val sink: (TraceEvent) -> Unit = { e ->
            if (e.name == TracePoint.TX && e.fields["command"] == 400 && fired.compareAndSet(false, true)) supervisor.stop(StopTrigger.BUTTON)
        }
        trace.addSink(sink)
        try {
            confirmPlan(plan(takeoffCommand()))
            waitFor("STOP decided", 10_000) { supervisor.state.stop?.let { it.id != previous && it.phase != StopPhase.SENDING } == true }
        } finally {
            trace.removeSink(sink)
        }
        val stop = settled(supervisor.state.stop!!)
        assertEquals(StopAction.DISARM, stop.action)
        assertEquals(StopPhase.CONFIRMED, stop.phase)
        assertTrue("a heartbeat showed it disarmed", verified(stop))
        Thread.sleep(3_000)
        assertEquals(false, store.snapshot().armed)
        val events = trace.events().drop(before)
        assertTrue("no NAV_TAKEOFF after the STOP", events.none { it.name == TracePoint.TX && it.fields["command"] == 22 })
        assertEquals(StepStatus.UNKNOWN, supervisor.state.outcomes.single().status)
        val ackMs = durations(events.filter { it.id == stop.id }, TracePoint.STOP_TRIGGER, TracePoint.STOP_ACK)
        println("SITL STOP in the ARM round trip: DISARM ACK after $ackMs ms, " +
            "queued ARM purged=${link.stats.purgedByPriority - purged}, plan ${supervisor.state.result}")
    }

    @Test fun t04_aNonForcedDisarmInTheAirIsRefused() {
        takeoff()
        val sent = now()
        link.send(CommandLong(1, 1, 400, param1 = 0f, param2 = 0f))
        waitFor("disarm ACK", 5_000) { newAck(400, sent) != null }
        assertEquals("ArduCopter refuses a non-forced GCS disarm while flying", 4, newAck(400, sent)!!.result)
        assertEquals(true, store.snapshot().armed)
    }

    @Test fun t05_stopMidMoveBrakesAndTheVehicleStops() {
        startMove("forward", 8, minSpeed = 1.5)
        val stop = stopAndWait()
        assertEquals(StopPhase.CONFIRMED, stop.phase)
        assertEquals(StopAction.BRAKE, stop.action)
        assertTrue("a heartbeat showed BRAKE", verified(stop))
        assertEquals(StepStatus.UNKNOWN, supervisor.state.outcomes.single().status)
        val stopped = now()
        waitStill(10_000)
        println("SITL BRAKE: speed < 0.3 m/s ${now() - stopped} ms after the ACK was seen")
        assertEquals(CopterMode.BRAKE, store.snapshot().mode)
    }

    @Test fun t06_cancelDuringAMoveIsStop() {
        startMove("backward", 8, minSpeed = 1.5)
        val stop = stopAndWait(StopTrigger.CANCEL)
        assertEquals(StopTrigger.CANCEL, stop.trigger)
        assertEquals(StopAction.BRAKE, stop.action)
        assertTrue("Cancel never disconnects", supervisor.state.linked && link.stats.up)
        waitStill(10_000)
    }

    @Test fun t07_stopButtonToBrakeAckLatencyOverTenTrials() {
        repeat(10) { trial ->
            waitStill()
            startMove(if (trial % 2 == 0) "left" else "right", 3, minSpeed = 0.5)
            val stop = stopAndWait()
            assertEquals(StopAction.BRAKE, stop.action)
            assertEquals(StopPhase.CONFIRMED, stop.phase)
            assertTrue("a heartbeat showed BRAKE", verified(stop))
            latencyStopIds += stop.id
        }
        waitStill()
    }

    @Test fun t08_anExternalModeChangeAbortsThePlanAsUnknown() {
        startMove("forward", 8, minSpeed = 1.0)
        side.command(setMode(CopterMode.LOITER)) // the pilot's mode switch, from another MAVLink system
        waitFor("plan aborted", 10_000) { supervisor.state.stage == PlanStage.FAILED }
        assertEquals("UNKNOWN — Flight mode changed outside this app; vehicle outcome unknown.",
            supervisor.state.outcomes.single().message)
        val s = store.snapshot()
        assertEquals(ModeOwner.EXTERNAL, s.modeOwner)
        assertEquals(CopterMode.LOITER, s.mode)
        assertEquals(StopPhase.NOTHING_SENT, stopAndWait().phase)
        assertEquals("Pilot mode LOITER, use RC", supervisor.state.stop!!.message)
        val stop = stopAndWait(held = true)
        assertTrue(stop.override)
        assertEquals(StopAction.BRAKE, stop.action)
        waitFor("APP owns BRAKE", 5_000) { store.snapshot().modeOwner == ModeOwner.APP }
        waitStill()
    }

    @Test fun t09_repeatedForwardTenIsRejectedAtTheHomeDistanceLimit() {
        var rejected: String? = null
        for (i in 0 until 6) {
            val result = supervisor.submit(plan(move("forward", 10)), PlanSource.TYPED).get(5, TimeUnit.SECONDS)
            if (result is SubmitResult.Rejected) {
                rejected = result.message
                break
            }
            val token = (result as SubmitResult.AwaitingConfirmation).pending.token
            assertEquals(ConfirmResult.Started, supervisor.confirm(token).get(5, TimeUnit.SECONDS))
            waitFor("move ${i + 1}", 60_000) { supervisor.state.stage != PlanStage.EXECUTING }
            assertCompleted(supervisor.state)
        }
        val s = store.snapshot()
        val position = s.position!!
        val home = s.home!!
        val fromHome = hypot(position.x - home.x, position.y - home.y)
        println("SITL home distance: rejected \"$rejected\" at %.1f m from home".format(fromHome))
        assertEquals("Step 1 would go more than 30 m from home.${stallNote()}", rejected + stallNote())
        assertTrue("never beyond the limit: $fromHome m", fromHome <= 31.0)
    }

    @Test fun t10_gpsDeniedBrakeIsRefusedAndLandIsTheFallback() {
        val before = trace.events().size
        landHere()
        // This SITL runs the simulator's own AHRS (AHRS_EKF_TYPE 10), which never loses position; use EKF3.
        override("AHRS_EKF_TYPE", 3f)
        waitReady()
        takeoff()
        override("FS_EKF_THRESH", 0f) // keep the EKF and dead-reckoning failsafes from taking the mode first
        override("FS_DR_ENABLE", 0f)
        override("SIM_GPS_DISABLE", 1f)
        waitFor("EKF loses its position", 60_000) { !store.snapshot().positionReady }
        Thread.sleep(1_000)
        if (store.snapshot().modeOwner != ModeOwner.APP && trace.events().drop(before).any { it.name == TracePoint.LINK_DOWN }) {
            throw AssertionError("SITL stalled: a heartbeat gap over 3 s made the link go down, so the app (correctly) " +
                "treats GUIDED as EXTERNAL and STOP would send nothing. Heartbeat interval now ${heartbeatIntervalMs()} ms; " +
                "rerun with the simulator near real time (free host RAM).")
        }
        assertEquals("the app still owns GUIDED", ModeOwner.APP, store.snapshot().modeOwner)
        val sent = trace.events().size
        val stop = stopAndWait()
        assertEquals(StopPhase.CONFIRMED, stop.phase)
        assertEquals(StopAction.LAND, stop.action)
        assertTrue("a heartbeat showed LAND", verified(stop))
        val acks = trace.events().drop(sent).filter { it.name == TracePoint.STOP_ACK }.map { it.fields["action"] to it.fields["result"] }
        assertEquals(listOf<Pair<Any?, Any?>>(StopAction.BRAKE to 4, StopAction.LAND to 0), acks)
        waitFor("landed and disarmed", 90_000) { store.snapshot().armed == false }
        setParam("SIM_GPS_DISABLE", restore.getValue("SIM_GPS_DISABLE"))
    }
}

/**
 * A second MAVLink client (sysid 254) on SITL's SERIAL1 TCP port: another ground station or the pilot. Sends
 * COMMAND_LONG and hand-encoded PARAM_SET (the app's codec deliberately has no PARAM_SET); drains what SITL sends.
 */
private class SideChannel(port: Int = 5762) : AutoCloseable {
    private val socket = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", port), 5_000)
        tcpNoDelay = true
    }
    private val encoder = MavlinkEncoder(254, 190)
    private var sequence = 0
    private val reader = thread(name = "sitl-side-channel", isDaemon = true) {
        val buffer = ByteArray(4096)
        try {
            val input = socket.getInputStream()
            while (input.read(buffer) >= 0) { /* drained */ }
        } catch (_: IOException) {
        }
    }

    @Synchronized fun command(command: CommandLong) = socket.getOutputStream().write(encoder.encode(command))

    /** PARAM_SET (id 23, CRC_EXTRA 168) as REAL32. */
    @Synchronized fun paramSet(name: String, value: Float) {
        val payload = ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(value).put(1).put(1).put(name.toByteArray(Charsets.US_ASCII).copyOf(16)).put(9).array()
        val frame = ByteArray(10 + payload.size + 2)
        frame[0] = 0xFD.toByte()
        frame[1] = payload.size.toByte()
        frame[4] = (sequence++ and 0xFF).toByte()
        frame[5] = 254.toByte()
        frame[6] = 190.toByte()
        frame[7] = 23
        payload.copyInto(frame, 10)
        val crc = MavlinkCrc.frame(frame, 1, 9 + payload.size, 168)
        frame[10 + payload.size] = crc.toByte()
        frame[11 + payload.size] = (crc shr 8).toByte()
        socket.getOutputStream().write(frame)
    }

    override fun close() {
        socket.close()
        reader.join(1_000)
    }
}
