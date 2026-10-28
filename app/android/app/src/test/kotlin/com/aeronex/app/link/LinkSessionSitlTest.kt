package com.aeronex.app.link

import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.HomePosition
import com.aeronex.app.link.mavlink.MavlinkEncoder
import com.aeronex.app.link.mavlink.MavlinkParser
import com.aeronex.app.link.mavlink.SetupSigning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Against ArduCopter SITL through MAVProxy (`--out udpin:…:14550`). Skipped unless AERONEX_SITL=1; start the
 * simulator first (Start-Simulation.ps1 -Headless).
 */
class LinkSessionSitlTest {
    private val listener = RecordingListener()
    private lateinit var link: LinkSession

    @Before fun requireSitl() {
        assumeTrue("set AERONEX_SITL=1 with SITL on 127.0.0.1:14550", System.getenv("AERONEX_SITL") == "1")
        val transport = UdpTransport("127.0.0.1", 14550, 0, localAddress = "127.0.0.1")
        link = LinkSession(transport, MavlinkEncoder(), MavlinkParser(), TelemetryProfile.forKind(transport.kind), listener)
    }

    @After fun tearDown() {
        if (::link.isInitialized) link.stop()
    }

    /** ACKs addressed to us (MAVProxy shares sysid 255 but uses component 230). */
    private fun acks(command: Int) = listener.frames.filter { it.sysId == 1 && it.compId == 1 }
        .map { it.message }.filterIsInstance<CommandAck>().filter { it.command == command && it.targetComponent == 190 }

    @Test fun arduCopterAcceptsTheTelemetryProfileAndSendsHome() {
        link.start()
        waitFor("ArduCopter heartbeat", timeoutMs = 30_000) { listener.states.firstOrNull() == true }
        val expected = TelemetryProfile.WIFI.rates.size + 1 // + POSITION_TARGET_LOCAL_NED off
        waitFor("SET_MESSAGE_INTERVAL ACKs", timeoutMs = 10_000) { acks(511).size >= expected }
        assertEquals("all SET_MESSAGE_INTERVAL accepted: ${acks(511)}", listOf(0), acks(511).map { it.result }.distinct())
        waitFor("REQUEST_MESSAGE ACK", timeoutMs = 10_000) { acks(512).isNotEmpty() }
        assertEquals(0, acks(512).first().result)
        waitFor("HOME_POSITION", timeoutMs = 60_000) { listener.frames.any { it.message is HomePosition } }

        assertTrue(link.setPositionTargetStreaming(true))
        waitFor("POSITION_TARGET on ACK", timeoutMs = 5_000) { acks(511).size >= expected + 1 }
        assertTrue(link.setPositionTargetStreaming(false))
        waitFor("POSITION_TARGET off ACK", timeoutMs = 5_000) { acks(511).size >= expected + 2 }
        assertEquals(listOf(0), acks(511).map { it.result }.distinct())

        assertFalse(link.send(SetupSigning(1, 1, key32, 1L)))

        // Observed rates (MAVProxy's own stream requests may add to them, so they are printed, not asserted).
        val windowStart = listener.frames.size
        Thread.sleep(4_000)
        val rates = listener.frames.drop(windowStart).filter { it.sysId == 1 && it.compId == 1 }
            .groupingBy { it.message.spec.name }.eachCount().mapValues { it.value / 4.0 }.toSortedMap()
        println("SITL received Hz over 4 s: $rates")
        val stats = link.stats
        assertTrue(stats.up)
        assertEquals(0L, stats.badCrc)
        assertEquals(1L, stats.setupSigningRefused)
        println("SITL link stats: $stats")
    }
}
