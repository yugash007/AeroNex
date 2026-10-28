package com.aeronex.app.vehicle.executor

import com.aeronex.app.link.LinkSession
import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.CommandLong

/**
 * One COMMAND_LONG and its COMMAND_ACK (MAVLink command protocol): without an ACK it is re-sent after [retryMs]
 * with the same parameters and `confirmation` + 1, up to [maxSends] sends and only within [windowMs] of the first,
 * which also ends a wait that a MAV_RESULT_IN_PROGRESS ACK started.
 * Each send is encoded afresh by the link (sequence, CRC, signature). Not thread-safe: the supervisor thread only.
 */
internal class CommandExchange(
    private val link: LinkSession,
    val command: CommandLong,
    private val retryMs: Long,
    private val maxSends: Int,
    private val windowMs: Long = Long.MAX_VALUE,
    private val priority: Boolean = false,
    /** Called after each send the link queued. */
    private val onSend: (CommandLong) -> Unit = {},
) {
    sealed interface Result {
        data object Waiting : Result
        data class Accepted(val ack: CommandAck) : Result
        /** Any final result other than ACCEPTED. */
        data class Refused(val ack: CommandAck) : Result
        data object TimedOut : Result
    }

    /** Send attempts so far (queued or not). */
    var attempts = 0
        private set
    /** Sends the link queued: "possibly sent". */
    var queued = 0
        private set
    private var firstAtMs = 0L
    private var lastAtMs = 0L
    private var inProgress = false

    /** Sends the first copy; false if the link did not queue it. */
    fun start(nowMs: Long): Boolean {
        firstAtMs = nowMs
        return send(nowMs)
    }

    /** Applies the ACKs that arrived since the last call ([acks], any command), then re-sends or times out. */
    fun update(acks: List<CommandAck>, nowMs: Long): Result {
        for (ack in acks) {
            if (ack.command != command.command) continue
            when (ack.result) {
                MAV_RESULT_ACCEPTED -> return Result.Accepted(ack)
                MAV_RESULT_IN_PROGRESS -> inProgress = true // received: no more re-sends; the final ACK follows
                else -> return Result.Refused(ack)
            }
        }
        // The window bounds IN_PROGRESS too (the STOP lane's 3 s); without one, the plan's step deadline does.
        if (nowMs - firstAtMs >= windowMs) return Result.TimedOut
        if (inProgress) return Result.Waiting
        if (nowMs - lastAtMs < retryMs) return Result.Waiting
        if (attempts >= maxSends) return Result.TimedOut
        send(nowMs)
        return Result.Waiting
    }

    private fun send(nowMs: Long): Boolean {
        val copy = command.copy(confirmation = attempts and 0xFF)
        attempts++
        lastAtMs = nowMs
        val ok = if (priority) link.sendPriority(copy) else link.send(copy)
        if (ok) {
            queued++
            onSend(copy)
        }
        return ok
    }

    companion object {
        const val MAV_RESULT_ACCEPTED = 0
        const val MAV_RESULT_IN_PROGRESS = 5
    }
}
