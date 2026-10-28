package com.aeronex.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TelemetryProfileTest {
    // Spec 4.15 rate table, message id → Hz. POSITION_TARGET_LOCAL_NED (85) is separate: only while a move is pending.
    @Test fun wifiProfileMatchesTheSpecTable() {
        assertEquals(mapOf(0 to 1.0, 32 to 5.0, 33 to 5.0, 30 to 5.0, 245 to 2.0, 1 to 1.0, 193 to 1.0, 242 to 0.2),
            TelemetryProfile.WIFI.rates)
        assertEquals(5.0, TelemetryProfile.WIFI.positionTargetHz, 0.0)
    }

    @Test fun radioProfileMatchesTheSpecTable() {
        assertEquals(mapOf(0 to 1.0, 32 to 3.0, 33 to 0.5, 30 to 1.0, 245 to 1.0, 1 to 0.5, 193 to 0.5, 242 to 0.2),
            TelemetryProfile.RADIO.rates)
        assertEquals(4.0, TelemetryProfile.RADIO.positionTargetHz, 0.0)
    }

    @Test fun onlyUsbRadiosGetTheRadioProfile() {
        assertSame(TelemetryProfile.WIFI, TelemetryProfile.forKind(TransportKind.UDP))
        assertSame(TelemetryProfile.WIFI, TelemetryProfile.forKind(TransportKind.TCP))
        assertSame(TelemetryProfile.WIFI, TelemetryProfile.forKind(TransportKind.USB_DIRECT_FC))
        assertSame(TelemetryProfile.RADIO, TelemetryProfile.forKind(TransportKind.USB_RADIO))
    }

    @Test fun ratesBecomeSetMessageIntervalMicroseconds() {
        assertEquals(200_000L, TelemetryProfile.intervalUs(5.0))
        assertEquals(333_333L, TelemetryProfile.intervalUs(3.0))
        assertEquals(250_000L, TelemetryProfile.intervalUs(4.0))
        assertEquals(2_000_000L, TelemetryProfile.intervalUs(0.5))
        // 0 µs would mean "default rate" to ArduPilot; -1 disables the message.
        assertEquals(-1L, TelemetryProfile.intervalUs(0.0))
    }

    /** Audit L5: one lost sample of a 0.5 Hz radio stream is not "stale"; Wi-Fi keeps the 3 s floor. */
    @Test fun slowStreamsStayFreshForTwoAndAHalfIntervals() {
        assertEquals(TelemetryProfile.WIFI.rates.keys.associateWith { if (it == 242) 12_500L else 3_000L },
            TelemetryProfile.WIFI.freshnessMs())
        val radio = TelemetryProfile.RADIO.freshnessMs()
        assertEquals(5_000L, radio[33])
        assertEquals(5_000L, radio[1])
        assertEquals(5_000L, radio[193])
        assertEquals(3_000L, radio[0])
        assertEquals(3_000L, radio[32])
    }

    /** Audit L4: home keeps streaming, so one lost HOME_POSITION no longer leaves home unknown for the session. */
    @Test fun homeStreamsSlowlyOnEveryProfile() {
        for (profile in listOf(TelemetryProfile.WIFI, TelemetryProfile.RADIO)) assertEquals(0.2, profile.rates[242]!!, 0.0)
    }

}
