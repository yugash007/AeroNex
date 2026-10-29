package com.aeronex.app.vehicle

import com.aeronex.app.link.mavlink.HomePosition
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where the autopilot's local frame (LOCAL_POSITION_NED, and HOME_POSITION's x/y/z) has its origin, in degrees × 1e7
 * and metres above sea level: HOME_POSITION's latitude, longitude and altitude less its x, y and z.
 *
 * It moves only when the frame does (a reboot, a new EKF origin), not when home does: ArduCopter sets home where it
 * arms, while the frame, and every position the flight memory keeps in it, stays put (spec 2026-10-28 M1). An
 * autopilot that reports x/y/z as zeros makes it follow home instead, which only forgets more often.
 */
data class FrameOrigin(val latE7: Double, val lonE7: Double, val altM: Double) {
    /** Metres between two origins, on a flat earth as ArduPilot's own Location distances. */
    fun distanceTo(other: FrameOrigin): Double {
        val north = (other.latE7 - latE7) * LATLON_TO_M
        val east = (other.lonE7 - lonE7) * LATLON_TO_M * longitudeScale((latE7 + other.latE7) / 2)
        val down = altM - other.altM
        return sqrt(north * north + east * east + down * down)
    }

    companion object {
        /** Metres per 1e-7 degree of latitude: ArduPilot's LATLON_TO_M. */
        const val LATLON_TO_M = 0.011131884502145034

        /** The origin [home] implies; null when its x/y/z are not finite. */
        fun of(home: HomePosition): FrameOrigin? {
            if (!home.x.isFinite() || !home.y.isFinite() || !home.z.isFinite()) return null
            val lat = home.latitude - home.x / LATLON_TO_M
            return FrameOrigin(lat, home.longitude - home.y / (LATLON_TO_M * longitudeScale(home.latitude.toDouble())),
                home.altitude / 1000.0 + home.z)
        }

        /** ArduPilot's longitude_scale: east metres per 1e-7 degree shrink with the latitude's cosine. */
        private fun longitudeScale(latE7: Double) = max(cos(Math.toRadians(latE7 * 1e-7)), 0.01)
    }
}
