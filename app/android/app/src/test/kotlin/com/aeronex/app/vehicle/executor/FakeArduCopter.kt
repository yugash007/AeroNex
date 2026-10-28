package com.aeronex.app.vehicle.executor

import com.aeronex.app.link.LinkListener
import com.aeronex.app.link.LinkSession
import com.aeronex.app.link.TelemetryProfile
import com.aeronex.app.link.Transport
import com.aeronex.app.link.TransportKind
import com.aeronex.app.link.mavlink.Attitude
import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.CommandLong
import com.aeronex.app.link.mavlink.EkfStatusReport
import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.link.mavlink.GlobalPositionInt
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.HomePosition
import com.aeronex.app.link.mavlink.LocalPositionNed
import com.aeronex.app.link.mavlink.MavlinkEncoder
import com.aeronex.app.link.mavlink.MavlinkFrame
import com.aeronex.app.link.mavlink.MavlinkMessage
import com.aeronex.app.link.mavlink.MavlinkParser
import com.aeronex.app.link.mavlink.PositionTargetLocalNed
import com.aeronex.app.link.mavlink.SetPositionTargetLocalNed
import com.aeronex.app.link.mavlink.StatusText
import com.aeronex.app.link.mavlink.SysStatus
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.VehicleStateStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A message the app sent, as the fake vehicle received it. */
internal class Sent(val atNanos: Long, val sysId: Int, val compId: Int, val message: MavlinkMessage)

/**
 * A scripted stand-in for ArduCopter 4.6 (sysid 1/1) behind an in-memory transport. It follows the real behaviours
 * the executor relies on: DO_SET_MODE ACKs (BRAKE/GUIDED refused without a position estimate when armed, the same
 * mode accepted at once), arming only on the ground, a non-forced disarm refused unless landed, NAV_TAKEOFF only
 * when armed in GUIDED on the ground, BODY_OFFSET_NED targets rotated by yaw, POSITION_TARGET_LOCAL_NED only in
 * GUIDED and only while requested, LAND then auto-disarm. Fast kinematics keep tests short.
 *
 * Every COMMAND_LONG is ACKed to its sender unless a script ([lose], [loseAcks], [refuse]) says otherwise.
 */
internal class FakeArduCopter(private val tickMs: Long = 20) : AutoCloseable {
    private val lock = Any()
    private val encoder = MavlinkEncoder(1, 1)
    private val endpoints = CopyOnWriteArrayList<Endpoint>()
    @Volatile private var running = true

    val received = CopyOnWriteArrayList<Sent>()

    // Vehicle state, guarded by lock.
    var mode = CopterMode.STABILIZE
        get() = synchronized(lock) { field }
        private set
    var armed = false
        get() = synchronized(lock) { field }
        private set
    var landed = VehicleState.LANDED_ON_GROUND
        get() = synchronized(lock) { field }
        private set
    private var n = 0.0
    private var e = 0.0
    private var d = 0.0
    private var vn = 0.0
    private var ve = 0.0
    private var vd = 0.0
    @Volatile var yaw = 0.0
    private var target: DoubleArray? = null
    private var takeoffAltitude: Double? = null
    private var positionTargetRequested = false
    private var landedAtNanos = 0L
    private var tick = 0L

    // Scripts.
    @Volatile var heartbeats = true
    @Volatile var telemetry = true
    @Volatile var echo = true
    /** No position estimate: EKF flags without absolute position; GUIDED/BRAKE refused while armed. */
    @Volatile var gpsDenied = false
    @Volatile var speed = 6.0
    @Volatile var climbRate = 6.0
    @Volatile var landRate = 6.0
    @Volatile var battery = 90
    /** Copies of a command (by id) that are lost on the way: no effect, no ACK. */
    val lose = ConcurrentHashMap<Int, AtomicInteger>()
    /** ACKs (by command id) lost on the way back; the command still takes effect. */
    val loseAcks = ConcurrentHashMap<Int, AtomicInteger>()
    /** Commands (by id) refused with this MAV_RESULT instead of executed. */
    val refuse = ConcurrentHashMap<Int, Int>()
    /** Emit a heartbeat with the new mode before the DO_SET_MODE ACK (the worst ordering the app can see). */
    @Volatile var heartbeatBeforeModeAck = false
    /** Mode numbers whose DO_SET_MODE is refused with FAILED. */
    val refuseModes: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    /** Mode numbers whose DO_SET_MODE is ACCEPTED without any effect (a lying or confused autopilot). */
    val ignoreModes: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    /** Runs after a COMMAND_LONG was executed or refused and before its ACK is sent (not for a lost copy). */
    @Volatile var beforeAck: (CommandLong) -> Unit = {}

    private val physics = thread(name = "fake-arducopter", isDaemon = true) {
        while (running) {
            step(tickMs / 1000.0)
            try { Thread.sleep(tickMs) } catch (_: InterruptedException) { return@thread }
        }
    }

    /** A fresh single-use app-side transport (a reconnect gets a new one). */
    fun transport(): Transport = Endpoint().also { endpoints += it }

    fun position(): DoubleArray = synchronized(lock) { doubleArrayOf(n, e, d) }
    fun speedNow(): Double = synchronized(lock) { sqrt(vn * vn + ve * ve + vd * vd) }
    fun altitude(): Double = synchronized(lock) { -d }

    /**
     * An RC mode switch, another ground station or an autopilot failsafe: the mode changes without the app.
     * [heartbeatNow] reports it at once, as ArduCopter does on a mode change; otherwise the next 10 Hz tick does.
     */
    fun externalMode(newMode: Long, heartbeatNow: Boolean = false) {
        val beat = synchronized(lock) {
            enter(newMode)
            heartbeat()
        }
        if (heartbeatNow) emit(beat)
    }

    fun statusText(severity: Int, text: String) = emit(StatusText(severity, text))

    /** A COMMAND_ACK to the app (sysid 255/190) that answers nothing it just sent, e.g. a late duplicate. */
    fun ack(command: Int, result: Int) = emit(CommandAck(command, result, targetSystem = 255, targetComponent = 190))

    /** Wind pushes the vehicle (and its hold target). */
    fun drift(dn: Double, de: Double) = synchronized(lock) {
        n += dn
        e += de
        target = target?.let { doubleArrayOf(it[0] + dn, it[1] + de, it[2]) }
    }

    /** Puts the vehicle armed and hovering at [altitude] in [hoverMode], as if flown there earlier. */
    fun hover(altitude: Double, hoverMode: Long = CopterMode.GUIDED) = synchronized(lock) {
        armed = true
        landed = VehicleState.LANDED_IN_AIR
        d = -altitude
        enter(hoverMode)
    }

    fun commands(id: Int) = received.map { it.message }.filterIsInstance<CommandLong>().filter { it.command == id }
    fun modeCommands() = commands(MAV_CMD_DO_SET_MODE).map { it.param2.toLong() }
    fun positionTargets() = received.map { it.message }.filterIsInstance<SetPositionTargetLocalNed>()
    /** Requested POSITION_TARGET_LOCAL_NED intervals, in order (µs, -1 = off). */
    fun positionTargetIntervals() = commands(MAV_CMD_SET_MESSAGE_INTERVAL)
        .filter { it.param1.toInt() == PositionTargetLocalNed.messageId }.map { it.param2.toLong() }
    /** Vehicle commands only: no telemetry requests. */
    fun vehicleCommands() = received.map { it.message }.filter {
        it is SetPositionTargetLocalNed || it is CommandLong && it.command != MAV_CMD_SET_MESSAGE_INTERVAL &&
            it.command != MAV_CMD_REQUEST_MESSAGE
    }

    override fun close() {
        running = false
        physics.interrupt()
        physics.join(1_000)
        endpoints.forEach { it.close() }
    }

    private fun emit(message: MavlinkMessage) {
        val bytes = encoder.encode(message)
        for (endpoint in endpoints) endpoint.deliver(bytes)
    }

    private fun handle(frame: MavlinkFrame) {
        received += Sent(System.nanoTime(), frame.sysId, frame.compId, frame.message)
        when (val message = frame.message) {
            is CommandLong -> if (message.targetSystem == 1 && message.targetComponent == 1) command(frame, message)
            is SetPositionTargetLocalNed -> synchronized(lock) {
                if (mode != CopterMode.GUIDED || !armed || landed != VehicleState.LANDED_IN_AIR) return
                if (message.coordinateFrame != FRAME_BODY_OFFSET_NED || message.typeMask != TYPE_MASK_POSITION) return
                val c = cos(yaw)
                val s = sin(yaw)
                target = doubleArrayOf(n + c * message.x - s * message.y, e + s * message.x + c * message.y, d + message.z)
                takeoffAltitude = null
            }
            else -> {}
        }
    }

    private fun command(frame: MavlinkFrame, command: CommandLong) {
        if (lose[command.command]?.let { it.getAndDecrement() > 0 } == true) return
        val result = refuse[command.command] ?: synchronized(lock) { execute(command) }
        if (heartbeatBeforeModeAck && command.command == MAV_CMD_DO_SET_MODE && result == ACCEPTED) {
            emit(synchronized(lock) { heartbeat() })
        }
        beforeAck(command)
        if (loseAcks[command.command]?.let { it.getAndDecrement() > 0 } == true) return
        emit(CommandAck(command.command, result, targetSystem = frame.sysId, targetComponent = frame.compId))
    }

    /** Runs under lock; the MAV_RESULT. */
    private fun execute(command: CommandLong): Int = when (command.command) {
        MAV_CMD_DO_SET_MODE -> {
            val newMode = command.param2.toLong()
            when {
                newMode == mode -> ACCEPTED
                newMode in refuseModes -> FAILED
                newMode in ignoreModes -> ACCEPTED
                armed && gpsDenied && newMode in POSITION_MODES -> {
                    emit(StatusText(4, "Mode change to ${CopterMode.name(newMode)} failed: requires position"))
                    FAILED
                }
                else -> { enter(newMode); ACCEPTED }
            }
        }
        MAV_CMD_COMPONENT_ARM_DISARM -> when {
            command.param1 == 1f -> when {
                armed -> ACCEPTED
                landed == VehicleState.LANDED_ON_GROUND && !gpsDenied -> { armed = true; ACCEPTED }
                else -> FAILED
            }
            !armed -> ACCEPTED
            command.param2 == FORCE_MAGIC || landed == VehicleState.LANDED_ON_GROUND -> { disarm(); ACCEPTED }
            else -> FAILED
        }
        MAV_CMD_NAV_TAKEOFF ->
            if (armed && mode == CopterMode.GUIDED && landed == VehicleState.LANDED_ON_GROUND) {
                takeoffAltitude = command.param7.toDouble()
                landed = VehicleState.LANDED_TAKEOFF
                ACCEPTED
            } else FAILED
        MAV_CMD_SET_MESSAGE_INTERVAL -> {
            if (command.param1.toInt() == PositionTargetLocalNed.messageId) positionTargetRequested = command.param2 > 0
            ACCEPTED
        }
        MAV_CMD_REQUEST_MESSAGE -> {
            if (command.param1.toInt() == HomePosition.messageId) {
                emit(HomePosition(0, 0, 0, 0f, 0f, 0f, listOf(1f, 0f, 0f, 0f), 0f, 0f, 0f))
            }
            ACCEPTED
        }
        else -> UNSUPPORTED
    }

    private fun enter(newMode: Long) {
        mode = newMode
        takeoffAltitude = null
        target = if (newMode == CopterMode.GUIDED) doubleArrayOf(n, e, d) else null
        // ArduCopter 4.6.3 says this (INFO) on every LAND entry; it is not a failsafe.
        if (newMode == CopterMode.LAND) emit(StatusText(6, "PrecLand: Failsafe Measures"))
    }

    /** Runs under lock. */
    private fun heartbeat() = Heartbeat(2, 3, if (armed) 128 or 1 else 1, mode, if (armed) 4 else 3)

    private fun disarm() {
        armed = false
        takeoffAltitude = null
        target = null
    }

    private fun step(dt: Double) {
        val outgoing = ArrayList<MavlinkMessage>()
        synchronized(lock) {
            tick++
            move(dt)
            val every100ms = tick % (100 / tickMs).coerceAtLeast(1) == 0L
            if (heartbeats && every100ms) outgoing += heartbeat()
            if (telemetry) {
                outgoing += LocalPositionNed(0, n.toFloat(), e.toFloat(), d.toFloat(), vn.toFloat(), ve.toFloat(), vd.toFloat())
                outgoing += GlobalPositionInt(0, 0, 0, 0, (-d * 1000).toInt(), 0, 0, 0, 0)
                outgoing += Attitude(0, 0f, 0f, yaw.toFloat(), 0f, 0f, 0f)
                if (every100ms) {
                    outgoing += ExtendedSysState(0, landed)
                    outgoing += SysStatus(0, 1, 1, 0, 0, 0, battery)
                    outgoing += EkfStatusReport(if (gpsDenied) 0x0F else 0x37F, 0f, 0f, 0f, 0f, 0f)
                }
            }
            val t = target
            if (echo && every100ms && positionTargetRequested && mode == CopterMode.GUIDED && t != null) {
                outgoing += PositionTargetLocalNed(0, 1, TYPE_MASK_POSITION, t[0].toFloat(), t[1].toFloat(), t[2].toFloat(),
                    0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
        }
        outgoing.forEach(::emit)
    }

    /** Runs under lock. */
    private fun move(dt: Double) {
        if (!armed) {
            vn = 0.0; ve = 0.0; vd = 0.0
            return
        }
        val climb = takeoffAltitude
        when {
            mode == CopterMode.GUIDED && climb != null -> {
                vn = 0.0; ve = 0.0; vd = -climbRate
                d = maxOf(d - climbRate * dt, -climb)
                if (-d > 0.5) landed = VehicleState.LANDED_IN_AIR
                if (-d >= climb) {
                    vd = 0.0
                    takeoffAltitude = null
                    target = doubleArrayOf(n, e, d)
                }
            }
            mode == CopterMode.GUIDED && target != null && landed == VehicleState.LANDED_IN_AIR -> {
                val t = target!!
                val dn = t[0] - n
                val de = t[1] - e
                val dd = t[2] - d
                val distance = sqrt(dn * dn + de * de + dd * dd)
                val stepLength = speed * dt
                if (distance <= stepLength) {
                    n = t[0]; e = t[1]; d = t[2]
                    vn = 0.0; ve = 0.0; vd = 0.0
                } else {
                    vn = dn / distance * speed; ve = de / distance * speed; vd = dd / distance * speed
                    n += vn * dt; e += ve * dt; d += vd * dt
                }
            }
            mode == CopterMode.LAND -> {
                vn = 0.0; ve = 0.0; vd = landRate
                if (landed != VehicleState.LANDED_ON_GROUND) landed = VehicleState.LANDED_LANDING
                d = minOf(d + landRate * dt, 0.0)
                if (d >= 0.0) {
                    vd = 0.0
                    if (landed != VehicleState.LANDED_ON_GROUND) {
                        landed = VehicleState.LANDED_ON_GROUND
                        landedAtNanos = System.nanoTime()
                    } else if (System.nanoTime() - landedAtNanos > 200_000_000L) {
                        disarm()
                    }
                }
            }
            mode == CopterMode.BRAKE -> {
                vn *= 0.5; ve *= 0.5; vd *= 0.5
                if (sqrt(vn * vn + ve * ve + vd * vd) < 0.05) { vn = 0.0; ve = 0.0; vd = 0.0 }
                n += vn * dt; e += ve * dt; d += vd * dt
            }
            else -> { vn = 0.0; ve = 0.0; vd = 0.0 }
        }
    }

    private inner class Endpoint : Transport {
        override val kind = TransportKind.UDP
        override val description = "fake ArduCopter ${System.identityHashCode(this)}"
        private val parser = MavlinkParser()
        private val incoming = LinkedBlockingQueue<ByteArray>()
        @Volatile private var closed = false

        override fun open() {}

        override fun write(bytes: ByteArray): Boolean {
            if (closed) return false
            val frames = synchronized(parser) {
                parser.parse(bytes).also { parser.reset() }
            }
            frames.forEach(::handle)
            return true
        }

        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            if (closed) return -1
            val bytes = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return if (closed) -1 else 0
            bytes.copyInto(buffer)
            return bytes.size
        }

        override fun close() {
            closed = true
            endpoints -= this
        }

        fun deliver(bytes: ByteArray) {
            if (!closed) incoming.add(bytes)
        }
    }

    companion object {
        const val MAV_CMD_DO_SET_MODE = 176
        const val MAV_CMD_COMPONENT_ARM_DISARM = 400
        const val MAV_CMD_NAV_TAKEOFF = 22
        const val MAV_CMD_SET_MESSAGE_INTERVAL = 511
        const val MAV_CMD_REQUEST_MESSAGE = 512
        const val ACCEPTED = 0
        const val FAILED = 4
        const val UNSUPPORTED = 3
        const val FRAME_BODY_OFFSET_NED = 9
        const val TYPE_MASK_POSITION = 3576
        const val FORCE_MAGIC = 21196f
        val POSITION_MODES = setOf(CopterMode.GUIDED, CopterMode.BRAKE, CopterMode.LOITER, CopterMode.POSHOLD, CopterMode.RTL)
    }
}

/** The app side wired as T7 will wire it: link frames → store; store snapshots → [events]. */
internal class Rig(
    val vehicle: FakeArduCopter = FakeArduCopter(),
    val store: VehicleStateStore = VehicleStateStore(),
) : AutoCloseable {
    val events = VehicleEvents().also { store.addListener(it::onState) }
    val linkStates = CopyOnWriteArrayList<Boolean>()
    var linkListener: (Boolean) -> Unit = {}
    var link = newLink()
        private set

    fun newLink(): LinkSession = LinkSession(vehicle.transport(), MavlinkEncoder(), MavlinkParser(), TelemetryProfile.WIFI,
        object : LinkListener {
            override fun onFrame(frame: MavlinkFrame) = store.accept(frame)
            override fun onLinkState(up: Boolean) {
                linkStates += up
                linkListener(up)
            }
        })

    /** Starts the link and waits until the vehicle is connected with fresh telemetry and home. */
    fun connect(): Rig {
        link.start()
        waitFor("fresh vehicle telemetry") {
            val s = store.snapshot()
            s.connected && s.home != null && s.landedState != null && s.positionReady && s.fresh(LocalPositionNed.messageId)
        }
        return this
    }

    /** Stops the link and starts a new one (a reconnect). */
    fun reconnect(): LinkSession {
        link.stop()
        store.reset()
        link = newLink()
        link.start()
        return link
    }

    override fun close() {
        link.stop()
        vehicle.close()
    }
}

internal fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("Timed out after $timeoutMs ms waiting for $what")
        Thread.sleep(5)
    }
}

/** Short timings for JVM tests (same rules, smaller numbers). */
internal val FAST_TIMING = ExecutorTiming(commandRetryMs = 150, commandTries = 3, echoTimeoutMs = 600,
    stepDeadlineMs = 5_000, stopWindowMs = 1_000)
