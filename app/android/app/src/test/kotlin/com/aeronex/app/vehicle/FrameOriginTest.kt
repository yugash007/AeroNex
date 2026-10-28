package com.aeronex.app.vehicle

import com.aeronex.app.link.mavlink.HomePosition
import kotlin.math.cos
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 2026-10-28 M1: the frame's origin, which moves with the frame and not with home. */
class FrameOriginTest {
    private val originLat = -353632621.0
    private val originLon = 1491652374.0

    /** HOME_POSITION for a home [n]/[e]/[d] metres from the origin, as ArduPilot reports it. */
    private fun home(n: Float, e: Float, d: Float, lat0: Double = originLat, lon0: Double = originLon): HomePosition {
        val lat = lat0 + n / FrameOrigin.LATLON_TO_M
        val lon = lon0 + e / (FrameOrigin.LATLON_TO_M * cos(Math.toRadians(lat * 1e-7)))
        return HomePosition(lat.roundToInt(), lon.roundToInt(), (584_000 - d * 1000).roundToInt(), n, e, d,
            listOf(1f, 0f, 0f, 0f), 0f, 0f, 0f)
    }

    @Test fun homeMovingInTheFrameKeepsItsOrigin() {
        val atOrigin = FrameOrigin.of(home(0f, 0f, 0f))!!
        assertEquals(originLat, atOrigin.latE7, 0.5)
        assertEquals(originLon, atOrigin.lonE7, 0.5)
        assertEquals(584.0, atOrigin.altM, 1e-9)
        // Armed again 25 m away and 2 m higher: home moved, the frame did not.
        val rearmed = FrameOrigin.of(home(18f, -17.5f, -2f))!!
        assertTrue("${atOrigin.distanceTo(rearmed)} m", atOrigin.distanceTo(rearmed) < 0.02)
    }

    @Test fun aNewFrameMovesTheOriginEvenWithHomeAtTheSameLocalPosition() {
        // A reboot 40 m north: home is at the new origin, (0, 0, 0) in both frames.
        val before = FrameOrigin.of(home(0f, 0f, 0f))!!
        val after = FrameOrigin.of(home(0f, 0f, 0f, lat0 = originLat + 40 / FrameOrigin.LATLON_TO_M))!!
        assertEquals(40.0, before.distanceTo(after), 0.02)
    }

    @Test fun noOriginWithoutAFiniteLocalHome() {
        assertNull(FrameOrigin.of(HomePosition(0, 0, 0, Float.NaN, 0f, 0f, listOf(1f, 0f, 0f, 0f), 0f, 0f, 0f)))
    }
}
