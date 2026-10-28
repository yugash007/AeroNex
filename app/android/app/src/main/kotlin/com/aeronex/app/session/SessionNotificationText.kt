package com.aeronex.app.session

import com.aeronex.app.safety.StopStatus
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.VehicleState

/** The foreground-service notification's text (pure JVM; [FlightSessionService] shows it). */
object SessionNotificationText {
    const val TITLE = "AeroNex flight session"
    const val STOP_ACTION = "STOP"

    /** One line: an unconfirmed STOP first, else the link, the mode and its owner, and arming. */
    fun line(link: LinkInfo, vehicle: VehicleState, stop: StopStatus?): String {
        if (stop?.alarm == true) return stop.message
        return when (link.state) {
            LinkState.DOWN -> "Not connected"
            LinkState.CONNECTING -> if (link.heartbeatAgeMs == null) "Waiting for the vehicle" else "Link lost, use RC"
            LinkState.UP -> listOfNotNull(
                "Link up",
                vehicle.mode?.let { mode ->
                    val owner = when {
                        vehicle.modeOwner == ModeOwner.APP -> " (app)"
                        vehicle.modeOwnerLabel != null -> " (${vehicle.modeOwnerLabel})"
                        else -> ""
                    }
                    CopterMode.name(mode) + owner
                },
                vehicle.armed?.let { if (it) "Armed" else "Disarmed" },
            ).joinToString(" · ")
        }
    }
}
