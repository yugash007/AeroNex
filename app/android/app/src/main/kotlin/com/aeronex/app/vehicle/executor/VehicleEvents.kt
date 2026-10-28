package com.aeronex.app.vehicle.executor

import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.StatusText
import com.aeronex.app.vehicle.Received
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.VehicleStateStore
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The vehicle events the supervisor must not miss between two of its turns: every COMMAND_ACK addressed to this GCS
 * and every STATUSTEXT, in arrival order. A [VehicleState] keeps only the last of each, so register [onState] with
 * [VehicleStateStore.addListener]: the store calls it after every accepted frame, serially, under its lock, and it
 * only queues. Drain from one thread (the supervisor's).
 */
class VehicleEvents {
    private var lastAcks: Map<Int, Received<CommandAck>> = emptyMap()
    private var lastText: Received<StatusText>? = null
    private val acks = ConcurrentLinkedQueue<Received<CommandAck>>()
    private val texts = ConcurrentLinkedQueue<StatusText>()
    /** The heartbeat count at arrival of each ACK of the last drain (supervisor thread). */
    private var drained = IdentityHashMap<CommandAck, Long>()

    fun onState(state: VehicleState) {
        if (state.acks !== lastAcks) {
            for ((command, received) in state.acks) if (lastAcks[command] !== received) acks.add(received)
            lastAcks = state.acks
        }
        val text = state.lastStatusText
        if (text != null && text !== lastText) texts.add(text.message)
        lastText = text
    }

    fun drainAcks(): List<CommandAck> {
        val received = drain(acks)
        drained = IdentityHashMap<CommandAck, Long>(received.size).apply { for (r in received) put(r.message, r.heartbeats) }
        return received.map { it.message }
    }

    /**
     * The vehicle heartbeat count when [ack] (of the last [drainAcks]) arrived, or null for another ACK. A heartbeat
     * counted after it was sent after the ACK, even when both are handled in one supervisor turn.
     */
    fun heartbeatsAtArrival(ack: CommandAck): Long? = drained[ack]

    fun drainTexts(): List<StatusText> = drain(texts)

    private fun <T> drain(queue: ConcurrentLinkedQueue<T>): List<T> {
        if (queue.isEmpty()) return emptyList()
        val out = ArrayList<T>()
        while (true) out += queue.poll() ?: break
        return out
    }

    companion object {
        private val breach = Regex("\\bbreach", RegexOption.IGNORE_CASE)

        /**
         * A STATUSTEXT that aborts a running plan (spec 4.14 "fence or failsafe STATUSTEXT"): severity WARNING or
         * worse, or a fence breach. ArduCopter 4.6.3 sends every failsafe ("Radio Failsafe", "EKF Failsafe: …",
         * battery low, "Dead Reckoning started") at WARNING or worse and a breach as NOTICE "<fence> breached".
         * Deliberately narrower than [com.aeronex.app.vehicle.ModeOwnership.isFailsafeText]: normal
         * flights send INFO texts with those words, e.g. "EKF3 IMU0 MAG0 in-flight yaw alignment complete" after
         * takeoff and "PrecLand: Failsafe Measures" on every LAND, which must not abort a plan.
         */
        fun abortsPlan(text: StatusText) = text.severity <= SEVERITY_WARNING || breach.containsMatchIn(text.text)

        private const val SEVERITY_WARNING = 4
    }
}
