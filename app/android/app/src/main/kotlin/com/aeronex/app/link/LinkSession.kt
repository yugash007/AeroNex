package com.aeronex.app.link

import com.aeronex.app.link.mavlink.CommandLong
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.HomePosition
import com.aeronex.app.link.mavlink.MavlinkEncoder
import com.aeronex.app.link.mavlink.MavlinkFrame
import com.aeronex.app.link.mavlink.MavlinkMessage
import com.aeronex.app.link.mavlink.MavlinkParser
import com.aeronex.app.link.mavlink.PositionTargetLocalNed
import com.aeronex.app.link.mavlink.RadioStatus
import com.aeronex.app.link.mavlink.SetPositionTargetLocalNed
import com.aeronex.app.link.mavlink.SetupSigning
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.function.LongSupplier
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Called on the link RX thread: return quickly and never block (post to the supervisor instead). An exception is
 * caught and counted in [LinkStats.listenerErrors]; the link keeps running.
 */
interface LinkListener {
    /**
     * Every frame that passed the transport's source filter, CRC and (when signing) signature, from any sender:
     * filter on sysId/compId (e.g. RADIO_STATUS comes from the radio's own sysid).
     */
    fun onFrame(frame: MavlinkFrame)

    /** Up = a HEARTBEAT from the target within the last 3 s. Called on every change, and with false when stopped. */
    fun onLinkState(up: Boolean)
}

/** A snapshot for diagnostics. Elapsed times are null until something has been received. */
data class LinkStats(
    val up: Boolean,
    /** False shows the UNSIGNED banner. */
    val signed: Boolean,
    val rxFrames: Long,
    val rxBytes: Long,
    val txFrames: Long,
    val txBytes: Long,
    /** Frames the transport did not take (not connected, I/O error); never retried. */
    val txErrors: Long,
    /** Messages refused because their TX queue was full. */
    val txDropped: Long,
    /** Queued vehicle commands dropped because a priority (STOP) message was queued after them. */
    val purgedByPriority: Long,
    val lastRxElapsedMs: Long?,
    val lastHeartbeatElapsedMs: Long?,
    val badCrc: Long,
    val badSignature: Long,
    val unsignedRejected: Long,
    val unknownMessages: Long,
    val badFrames: Long,
    val setupSigningRefused: Long,
    val unsafeCommandsRefused: Long,
    val listenerErrors: Long,
)

/**
 * One MAVLink connection to one vehicle: an RX thread (transport → parser → [LinkListener]) and a TX thread
 * (priority lane, normal lane, GCS HEARTBEAT every [heartbeatPeriodMs] that nothing throttles).
 *
 * - The link is up while a HEARTBEAT from [targetSystem]/[targetComponent] arrived within the last 3 s. Each time
 *   it comes up (the first time too) [requestTelemetry] runs, because ArduPilot does not keep requested rates
 *   across reboots and drops them during parameter transfers (spec 4.15).
 * - [sendPriority] (STOP) goes ahead of everything queued and drops queued vehicle commands, so no mode change,
 *   arm, takeoff or move queued before a STOP can go out after it.
 * - Refused whatever the caller asks: SETUP_SIGNING except to the connected FC over a direct USB cable with no
 *   radio on it (spec 4.13); forced arm/disarm, flight termination, motor test and parameter writes (spec 8.3).
 * - Messages are encoded on the TX thread at send time (fresh sequence, CRC, signing timestamp and signature).
 *   Nothing is retried or replayed here: a frame the transport does not take is counted in [LinkStats.txErrors].
 *
 * One-shot: [start] once, [stop] once (idempotent); a new connection gets a new session and transport.
 * [clock] is in nanoseconds (it is a LongSupplier so the RX loop reads it without boxing).
 */
class LinkSession(
    private val transport: Transport,
    private val encoder: MavlinkEncoder,
    private val parser: MavlinkParser,
    val profile: TelemetryProfile,
    private val listener: LinkListener,
    val targetSystem: Int = 1,
    val targetComponent: Int = 1,
    private val clock: LongSupplier = LongSupplier(System::nanoTime),
    private val heartbeatPeriodMs: Long = 1000,
) {
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val drained = lock.newCondition()
    private val priority = ArrayDeque<MavlinkMessage>() // guarded by lock
    private val normal = ArrayDeque<MavlinkMessage>() // guarded by lock
    private var unsent = 0 // queued, or taken by the TX thread and not yet written; guarded by lock
    private var positionTargetStreaming = false // guarded by lock

    private var started = false // guarded by this
    private var stopped = false // guarded by this
    @Volatile private var running = false
    private var threads = emptyList<Thread>()

    // Written by the RX thread only.
    @Volatile private var up = false
    @Volatile private var lastRxAt = NEVER
    @Volatile private var lastHeartbeatAt = NEVER
    @Volatile private var radioStatusSeen = false
    @Volatile private var rxFrames = 0L
    @Volatile private var rxBytes = 0L
    @Volatile private var badCrc = 0L
    @Volatile private var badSignature = 0L
    @Volatile private var unsignedRejected = 0L
    @Volatile private var unknownMessages = 0L
    @Volatile private var badFrames = 0L
    @Volatile private var listenerErrors = 0L

    // Written by the TX thread only.
    @Volatile private var txFrames = 0L
    @Volatile private var txBytes = 0L
    @Volatile private var txErrors = 0L

    private val txDropped = AtomicLong()
    private val purgedByPriority = AtomicLong()
    private val setupSigningRefused = AtomicLong()
    private val unsafeCommandsRefused = AtomicLong()

    private val heartbeat = Heartbeat(type = MAV_TYPE_GCS, autopilot = MAV_AUTOPILOT_INVALID, baseMode = 0,
        customMode = 0, systemStatus = MAV_STATE_ACTIVE)

    init {
        require(heartbeatPeriodMs > 0) { "Heartbeat period must be positive" }
    }

    /** Opens the transport (an IOException propagates) and starts the RX and TX threads. */
    @Synchronized fun start() {
        check(!started && !stopped) { "A LinkSession starts once" }
        started = true
        transport.open()
        running = true
        val name = "[${transport.description}]"
        threads = listOf(
            thread(name = "aeronex-link-rx $name", isDaemon = true) { receiveLoop() },
            thread(name = "aeronex-link-tx $name", isDaemon = true) { transmitLoop() },
        )
    }

    /** Stops both threads (waiting up to 2 s each), drops anything queued and closes the transport. */
    fun stop() {
        val ending = synchronized(this) {
            if (stopped) return
            stopped = true
            running = false
            threads
        }
        lock.withLock {
            priority.clear()
            normal.clear()
            unsent = 0
            wake.signalAll()
            drained.signalAll()
        }
        transport.close()
        for (t in ending) if (t !== Thread.currentThread()) t.join(JOIN_TIMEOUT_MS)
    }

    /**
     * Waits up to [timeoutMs] until everything queued has been handed to the transport (written or failed), e.g. a
     * last SET_MESSAGE_INTERVAL before [stop], which drops the queues. False if something was still unsent.
     */
    fun drain(timeoutMs: Long): Boolean = lock.withLock {
        var leftNs = timeoutMs * 1_000_000
        while (unsent > 0) {
            if (leftNs <= 0) return false
            try {
                leftNs = drained.awaitNanos(leftNs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        true
    }

    /** Queues [message] behind earlier ones; true if queued (not proof that it was sent). */
    fun send(message: MavlinkMessage): Boolean = enqueue(message, urgent = false)

    /** Queues [message] ahead of every normal message and drops queued vehicle commands (the STOP lane). */
    fun sendPriority(message: MavlinkMessage): Boolean = enqueue(message, urgent = true)

    /**
     * Streams POSITION_TARGET_LOCAL_NED at the profile rate while a move is pending, or disables it. The setting
     * survives reconnects. Returns whether the request was queued.
     */
    fun setPositionTargetStreaming(enabled: Boolean): Boolean = lock.withLock {
        positionTargetStreaming = enabled
        send(positionTargetInterval())
    }

    /** Requests every profile rate, the POSITION_TARGET setting and HOME_POSITION. Runs by itself on each link-up. */
    fun requestTelemetry(): Boolean = lock.withLock {
        var queued = true
        for ((messageId, hz) in profile.rates) queued = send(interval(messageId, hz)) and queued
        queued = send(positionTargetInterval()) and queued
        send(CommandLong(targetSystem, targetComponent, MAV_CMD_REQUEST_MESSAGE,
            param1 = HomePosition.messageId.toFloat())) and queued
    }

    val stats: LinkStats
        get() {
            val now = clock.asLong
            return LinkStats(
                up = up, signed = encoder.signed,
                rxFrames = rxFrames, rxBytes = rxBytes, txFrames = txFrames, txBytes = txBytes, txErrors = txErrors,
                txDropped = txDropped.get(), purgedByPriority = purgedByPriority.get(),
                lastRxElapsedMs = elapsedMs(lastRxAt, now), lastHeartbeatElapsedMs = elapsedMs(lastHeartbeatAt, now),
                badCrc = badCrc, badSignature = badSignature, unsignedRejected = unsignedRejected,
                unknownMessages = unknownMessages, badFrames = badFrames,
                setupSigningRefused = setupSigningRefused.get(), unsafeCommandsRefused = unsafeCommandsRefused.get(),
                listenerErrors = listenerErrors,
            )
        }

    private fun enqueue(message: MavlinkMessage, urgent: Boolean): Boolean {
        if (!permitted(message)) return false
        lock.withLock {
            if (!running) return false
            val lane = if (urgent) priority else normal
            if (lane.size >= QUEUE_CAPACITY) {
                txDropped.incrementAndGet()
                return false
            }
            if (urgent) {
                val before = normal.size
                normal.removeAll(::isVehicleCommand)
                purgedByPriority.addAndGet((before - normal.size).toLong())
                unsent -= before - normal.size
            }
            lane.addLast(message)
            unsent++
            wake.signal()
        }
        return true
    }

    private fun permitted(message: MavlinkMessage): Boolean {
        if (message is SetupSigning && !setupSigningAllowed(message)) {
            setupSigningRefused.incrementAndGet()
            return false
        }
        if (message is CommandLong && unsafe(message)) {
            unsafeCommandsRefused.incrementAndGet()
            return false
        }
        return true
    }

    /** Spec 4.13: a direct cable to the FC's own USB port, a HEARTBEAT from that FC, and no radio on the cable. */
    private fun setupSigningAllowed(message: SetupSigning) =
        transport.kind == TransportKind.USB_DIRECT_FC && message.targetSystem == targetSystem && up && !radioStatusSeen

    private fun unsafe(command: CommandLong) = when (command.command) {
        MAV_CMD_COMPONENT_ARM_DISARM -> command.param2 != 0f // 21196 forces arming or disarming
        MAV_CMD_DO_FLIGHTTERMINATION, MAV_CMD_DO_MOTOR_TEST, MAV_CMD_DO_SET_PARAMETER -> true
        else -> false
    }

    private fun isVehicleCommand(message: MavlinkMessage) = message is SetPositionTargetLocalNed ||
        (message is CommandLong && message.command != MAV_CMD_SET_MESSAGE_INTERVAL &&
            message.command != MAV_CMD_REQUEST_MESSAGE)

    private fun interval(messageId: Int, hz: Double) = CommandLong(targetSystem, targetComponent,
        MAV_CMD_SET_MESSAGE_INTERVAL, param1 = messageId.toFloat(), param2 = TelemetryProfile.intervalUs(hz).toFloat())

    private fun positionTargetInterval() = interval(PositionTargetLocalNed.messageId,
        if (positionTargetStreaming) profile.positionTargetHz else 0.0)

    private fun receiveLoop() {
        val buffer = ByteArray(RX_BUFFER_BYTES)
        val datagrams = transport.kind == TransportKind.UDP
        var connection = transport.connections
        try {
            while (running) {
                val count = transport.read(buffer, RX_POLL_MS)
                if (count < 0) break
                val now = clock.asLong
                if (count > 0) {
                    rxBytes += count
                    if (transport.connections != connection) {
                        connection = transport.connections
                        parser.reset() // the old connection's half frame must not swallow the new one's first frames
                    }
                    val frames = parser.parse(buffer, 0, count)
                    if (datagrams) parser.reset() // a partial frame never waits for the next datagram
                    badCrc = parser.badCrc
                    badSignature = parser.badSignature
                    unsignedRejected = parser.unsignedRejected
                    unknownMessages = parser.unknownMessages
                    badFrames = parser.badFrames
                    for (i in frames.indices) receive(frames[i], now)
                }
                if (up && now - lastHeartbeatAt > LINK_TIMEOUT_NS) linkState(false)
            }
        } finally {
            if (up) linkState(false)
        }
    }

    private fun receive(frame: MavlinkFrame, now: Long) {
        rxFrames++
        lastRxAt = now
        val message = frame.message
        if (message is RadioStatus) radioStatusSeen = true
        val fromTarget = message is Heartbeat && frame.sysId == targetSystem && frame.compId == targetComponent
        if (fromTarget) lastHeartbeatAt = now
        try {
            listener.onFrame(frame)
        } catch (_: Exception) {
            listenerErrors++
        }
        if (fromTarget && !up) linkState(true)
    }

    private fun linkState(value: Boolean) {
        up = value
        if (value) requestTelemetry()
        try {
            listener.onLinkState(value)
        } catch (_: Exception) {
            listenerErrors++
        }
    }

    private fun transmitLoop() {
        val periodNs = heartbeatPeriodMs * 1_000_000
        var nextHeartbeat = clock.asLong
        while (running) {
            // One priority message, then the heartbeat if due: STOP jumps the queue, and nothing delays the heartbeat
            // by more than one frame.
            val urgent = lock.withLock { priority.removeFirstOrNull() }
            if (urgent != null) sent(urgent)
            val now = clock.asLong
            if (now - nextHeartbeat >= 0) {
                transmit(heartbeat)
                // Keep the grid; after a stall (a blocked write) restart it instead of sending a burst.
                nextHeartbeat = if (now - nextHeartbeat >= periodNs) now + periodNs else nextHeartbeat + periodNs
                continue
            }
            if (urgent != null) continue
            val next = lock.withLock {
                normal.removeFirstOrNull() ?: run {
                    if (running && priority.isEmpty()) {
                        try {
                            wake.awaitNanos(nextHeartbeat - now)
                        } catch (_: InterruptedException) {
                            // Only stop() ends this thread.
                        }
                    }
                    null
                }
            }
            if (next != null) sent(next)
        }
    }

    /** Transmits a dequeued message, then counts it out for [drain]. */
    private fun sent(message: MavlinkMessage) {
        transmit(message)
        lock.withLock {
            if (unsent > 0) unsent--
            if (unsent == 0) drained.signalAll()
        }
    }

    private fun transmit(message: MavlinkMessage) {
        val bytes = try {
            encoder.encode(message)
        } catch (_: RuntimeException) {
            txErrors++
            return
        }
        if (transport.write(bytes)) {
            txFrames++
            txBytes += bytes.size
        } else {
            txErrors++
        }
    }

    private fun elapsedMs(at: Long, now: Long) = if (at == NEVER) null else (now - at) / 1_000_000

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val LINK_TIMEOUT_NS = 3_000_000_000L
        const val RX_POLL_MS = 100
        const val RX_BUFFER_BYTES = 4096
        const val QUEUE_CAPACITY = 64
        const val JOIN_TIMEOUT_MS = 2_000L

        const val MAV_TYPE_GCS = 6
        const val MAV_AUTOPILOT_INVALID = 8
        const val MAV_STATE_ACTIVE = 4
        const val MAV_CMD_DO_FLIGHTTERMINATION = 185
        const val MAV_CMD_DO_SET_PARAMETER = 180
        const val MAV_CMD_DO_MOTOR_TEST = 209
        const val MAV_CMD_COMPONENT_ARM_DISARM = 400
        const val MAV_CMD_SET_MESSAGE_INTERVAL = 511
        const val MAV_CMD_REQUEST_MESSAGE = 512
    }
}
