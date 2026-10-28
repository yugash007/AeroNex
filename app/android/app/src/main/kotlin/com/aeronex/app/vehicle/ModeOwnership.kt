package com.aeronex.app.vehicle

import kotlin.math.abs

/**
 * Tracks who owns the flight mode (spec 4.12). APP only while the current HEARTBEAT mode is the mode of the
 * app's last ACCEPTED DO_SET_MODE and every heartbeat since that command showed it; EXTERNAL otherwise,
 * including the mode found at connect. An EXTERNAL change is labelled [ExternalCause.AUTOPILOT_FAILSAFE] when a
 * failsafe STATUSTEXT ([isFailsafeText]) arrived within [FAILSAFE_WINDOW_MS] before or after it.
 *
 * A heartbeat gap (the link stalled for 3 s or more, below ArduCopter's GCS failsafe) makes the mode EXTERNAL too,
 * since nothing proves who chose it. When the first heartbeat after the gap still shows the app's own mode,
 * [resumedAppMode] is true (labelled [ExternalCause.LINK_GAP]) until another mode shows or the app sets one: a STOP
 * may then send without the 1 s override hold, while new plans stay blocked (audit L2).
 *
 * Not thread-safe; [VehicleStateStore] serialises calls. Times are on one monotonic clock.
 */
class ModeOwnership {
    /** The last heartbeat mode. */
    private var mode: Long? = null
    /** The mode of the last ACCEPTED DO_SET_MODE since connect. */
    private var commanded: Long? = null
    /** An uncommanded mode (or the mode at connect) was seen since [commanded] was accepted. */
    private var uncommanded = true
    private var changedAtMs: Long? = null
    private var changeIsFailsafe = false
    private var failsafeTextAtMs: Long? = null
    /** The app's own mode, seen again by the first heartbeat after a gap. */
    private var resumed: Long? = null

    val owner: ModeOwner
        get() = if (!uncommanded && mode != null && mode == commanded) ModeOwner.APP else ModeOwner.EXTERNAL

    /** EXTERNAL only because of a heartbeat gap: the vehicle still shows the mode the app set before it. */
    val resumedAppMode: Boolean
        get() = owner == ModeOwner.EXTERNAL && resumed != null && mode == resumed

    /** Null while APP or while no mode is known. */
    val cause: ExternalCause?
        get() = when {
            owner == ModeOwner.APP || mode == null -> null
            resumedAppMode -> ExternalCause.LINK_GAP
            changeIsFailsafe -> ExternalCause.AUTOPILOT_FAILSAFE
            else -> ExternalCause.PILOT_MODE
        }

    /**
     * The first vehicle heartbeat of a connection, or the first after a heartbeat gap: its mode was not chosen by
     * the app (as far as anyone can prove).
     */
    fun connected(mode: Long, nowMs: Long) {
        val appMode = commanded?.takeIf { !uncommanded && this.mode == it }
        this.mode = mode
        commanded = null
        uncommanded = true
        resumed = appMode?.takeIf { it == mode }
        changed(nowMs)
    }

    fun heartbeat(mode: Long, nowMs: Long) {
        val previous = this.mode
        this.mode = mode
        if (resumed != null && mode != resumed) resumed = null
        if (mode == commanded && !uncommanded) return
        // Newly external, or a further change while external: label this change on its own.
        if (!uncommanded || mode != previous) {
            uncommanded = true
            changed(nowMs)
        }
    }

    /** The app received an ACCEPTED COMMAND_ACK for its DO_SET_MODE to [mode]. */
    fun noteCommandedMode(mode: Long) {
        commanded = mode
        uncommanded = false
        resumed = null
    }

    fun statusText(severity: Int, text: String, nowMs: Long) {
        if (!isFailsafeText(severity, text)) return
        failsafeTextAtMs = nowMs
        val changedAt = changedAtMs
        if (owner == ModeOwner.EXTERNAL && changedAt != null && abs(nowMs - changedAt) <= FAILSAFE_WINDOW_MS) {
            changeIsFailsafe = true
        }
    }

    fun reset() {
        mode = null
        commanded = null
        uncommanded = true
        changedAtMs = null
        changeIsFailsafe = false
        failsafeTextAtMs = null
        resumed = null
    }

    private fun changed(nowMs: Long) {
        changedAtMs = nowMs
        changeIsFailsafe = failsafeTextAtMs.let { it != null && abs(nowMs - it) <= FAILSAFE_WINDOW_MS }
    }

    companion object {
        const val FAILSAFE_WINDOW_MS = 2000L
        /** MAV_SEVERITY_WARNING; lower numbers are more severe. */
        const val SEVERITY_WARNING = 4
        private val failsafeWords = Regex("failsafe|fence|battery|ekf", RegexOption.IGNORE_CASE)

        /** A STATUSTEXT that may explain an autopilot mode change: severity WARNING or worse, or a failsafe word. */
        fun isFailsafeText(severity: Int, text: String) =
            severity <= SEVERITY_WARNING || failsafeWords.containsMatchIn(text)
    }
}
