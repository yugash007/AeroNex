package com.aeronex.app.vehicle.executor

import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.VehicleStateStore

/**
 * The app's mode changes and arming, shared by plan steps and the STOP lane of one connection. The store's
 * [VehicleState.modeOwner] lags the app's own commands both ways, so [ownsMode] also counts:
 * - a DO_SET_MODE in flight ([requested], no ACK yet, or a STOP's ACK no heartbeat has confirmed yet) whose mode a
 *   heartbeat already shows: the supervisor handles the ACK on its own thread, so the heartbeat that follows it can
 *   reach the store first;
 * - an ACCEPTED mode ([accepted]) that no heartbeat shows yet (up to a heartbeat period).
 * [armed] likewise counts an ARM the app queued ([armRequested]) until it is refused, an ARM that got no ACK at all
 * ([armUnanswered]) or an ACCEPTED one until a later heartbeat shows the vehicle's state: a STOP in any of these
 * windows must still disarm (spec 4.12 spool-up row). Cancelling the takeoff does not clear it, since an ARM already
 * on the wire still arms the vehicle; a disarm a heartbeat confirmed does.
 * Supervisor thread only.
 */
class ModeWatch(private val store: VehicleStateStore) {
    private var appMode: Long? = null
    private var heartbeatsAtAck = 0L
    /** Possibly armed by the app's ARM until a heartbeat newer than this count shows the vehicle's state. */
    private var heartbeatsAtArm: Long? = null
    private var armInFlight = false
    private var requestedMode: Long? = null

    /** A DO_SET_MODE to [mode] was queued and is not [accepted] yet (a STOP's: until a heartbeat shows it). */
    fun requested(mode: Long) {
        requestedMode = mode
    }

    /** The request was refused, timed out, abandoned or not confirmed. */
    fun withdraw() {
        requestedMode = null
    }

    /**
     * The app's DO_SET_MODE to [mode] was ACCEPTED (spec 4.12 mode ownership): a plan step's on its ACK, a STOP's only
     * once a heartbeat shows the mode (a COMMAND_ACK names only the command, so the STOP's could be another's).
     */
    fun accepted(mode: Long, heartbeatsAtArrival: Long? = null) {
        store.noteCommandedMode(mode)
        requestedMode = null
        appMode = mode
        // From the ACK's arrival when known: a heartbeat that came after the ACK but in the same supervisor turn must
        // end the wait, or a pilot's mode change right after the app's could pass for the app's own (audit L7).
        heartbeatsAtAck = heartbeatsAtArrival ?: heartbeats(store.snapshot())
    }

    /** An ARM was queued and has no answer yet: it may arm the vehicle at any moment. */
    fun armRequested() {
        armInFlight = true
    }

    /** The ARM was refused: ArduCopter accepts an ARM when already armed, so the vehicle is not armed. */
    fun armRefused() {
        armInFlight = false
        heartbeatsAtArm = null
    }

    /** No ACK came for any send of the ARM: only the ACKs may have been lost, so it counts until a later heartbeat. */
    fun armUnanswered() {
        armInFlight = false
        heartbeatsAtArm = heartbeats(store.snapshot())
    }

    /** Call on an ACCEPTED COMMAND_ACK for the app's ARM. */
    fun armAccepted() {
        armInFlight = false
        heartbeatsAtArm = heartbeats(store.snapshot())
    }

    /** The app's (non-forced) disarm was ACCEPTED and a heartbeat showed it. */
    fun disarmAccepted() {
        armInFlight = false
        heartbeatsAtArm = null
    }

    /** Armed by heartbeat, or possibly armed by the app's ARM that no later heartbeat has spoken about yet. */
    fun armed(state: VehicleState) = state.armed == true || state.connected &&
        (armInFlight || heartbeatsAtArm.let { it != null && heartbeats(state) <= it })

    /** No heartbeat has arrived since the last accepted mode change. */
    fun awaitingHeartbeat(state: VehicleState) = appMode != null && heartbeats(state) <= heartbeatsAtAck

    fun ownsMode(state: VehicleState) = state.connected &&
        (state.modeOwner == ModeOwner.APP || awaitingHeartbeat(state) || requestedMode != null && state.mode == requestedMode)

    /** The mode in force: the accepted one until a heartbeat shows the vehicle's. */
    fun mode(state: VehicleState): Long? = if (awaitingHeartbeat(state)) appMode else state.mode

    /**
     * The modes a heartbeat may show next without anyone but the app having chosen them: the current one, an
     * accepted one no heartbeat shows yet, and one requested without an ACK. A STOP lane compares against these.
     */
    fun modesInPlay(state: VehicleState): Set<Long> = setOfNotNull(state.mode, mode(state), requestedMode)

    /** A new connection: nothing is owned. */
    fun reset() {
        appMode = null
        heartbeatsAtAck = 0
        heartbeatsAtArm = null
        armInFlight = false
        requestedMode = null
    }

    companion object {
        fun heartbeats(state: VehicleState) = state.samples[Heartbeat.messageId] ?: 0
    }
}
