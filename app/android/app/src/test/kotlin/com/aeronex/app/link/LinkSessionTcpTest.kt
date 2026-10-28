package com.aeronex.app.link

import com.aeronex.app.link.mavlink.MavlinkEncoder
import com.aeronex.app.link.mavlink.MavlinkParser
import com.aeronex.app.link.mavlink.ParamRequestRead
import com.aeronex.app.link.mavlink.SetupSigning
import com.aeronex.app.link.mavlink.StatusText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LinkSession over loopback TCP against a fake ArduCopter that behaves like mavlink-router's TCP server. */
class LinkSessionTcpTest {
    private val vehicle = TcpVehicle()
    private val listener = RecordingListener()
    private val transport = TcpTransport("127.0.0.1", vehicle.port, retryDelayMs = 200)
    private val link = LinkSession(transport, MavlinkEncoder(), MavlinkParser(), TelemetryProfile.forKind(transport.kind),
        listener)

    @After fun tearDown() {
        link.stop()
        vehicle.close()
    }

    private fun profileRequests() = vehicle.commands(511).associate { it.param1.toInt() to it.param2.toLong() }

    private val wifi = TelemetryProfile.WIFI.rates.mapValues { TelemetryProfile.intervalUs(it.value) } + (85 to -1L)

    @Test fun linkComesUpRequestsTelemetryAndRecoversAfterTheConnectionDrops() {
        link.start()
        waitFor("link up") { listener.states == listOf(true) }
        waitFor("profile requested") { profileRequests() == wifi }
        waitFor("home requested") { vehicle.commands(512).any { it.param1 == 242f } }
        // The t=0 heartbeat precedes the lazy dial; the next one (t=1 s) is the first to arrive.
        waitFor("GCS heartbeat over TCP", timeoutMs = 2_000) { vehicle.gcsHeartbeats().isNotEmpty() }

        vehicle.heartbeats = false
        vehicle.dropConnection()
        waitFor("link down", timeoutMs = 6_000) { listener.states == listOf(true, false) }
        waitFor("redialled") { vehicle.accepted.get() == 2 }

        vehicle.received.clear()
        vehicle.heartbeats = true
        waitFor("link up again") { listener.states == listOf(true, false, true) }
        waitFor("profile re-requested on the new connection") { profileRequests() == wifi }
        waitFor("home re-requested") { vehicle.commands(512).any { it.param1 == 242f } }
        assertEquals(2L, transport.connections)
    }

    @Test fun aNewConnectionDropsTheHalfFrameTheOldOneEndedWith() {
        // Audit L9: kept, the old connection's 30 bytes would wait for the 63-byte frame they began, take the new
        // connection's first HEARTBEATs into it and fail its CRC.
        vehicle.heartbeats = false
        link.start()
        val half = MavlinkEncoder(1, 1).encode(StatusText(4, "x".repeat(50))).copyOf(30)
        waitFor("half a frame sent") { vehicle.writeRaw(half) }
        waitFor("half a frame read") { link.stats.rxBytes == 30L }
        vehicle.dropConnection()
        waitFor("redialled") { vehicle.accepted.get() == 2 }

        vehicle.heartbeats = true
        waitFor("link up on the new connection") { listener.states == listOf(true) }
        assertEquals(0L, link.stats.badCrc)
        assertEquals(0L, link.stats.unknownMessages)
        assertEquals(2L, transport.connections)
    }

    @Test fun setupSigningIsNeverSentOverTcp() {
        link.start()
        waitFor("link up") { listener.states == listOf(true) }
        val setup = SetupSigning(1, 1, key32, 1L)
        assertFalse(link.send(setup))
        assertFalse(link.sendPriority(setup))
        assertEquals(2L, link.stats.setupSigningRefused)
        val marker = ParamRequestRead(1, 1, "MARKER")
        assertTrue(link.send(marker))
        waitFor("marker arrived") { vehicle.received.any { it.frame.message == marker } }
        assertFalse(256 in vehicle.messageIds())
    }

    @Test fun stopEndsBothThreads() {
        link.start()
        waitFor("link up") { listener.states == listOf(true) }
        link.stop()
        assertEquals(emptyList<Thread>(), linkThreadsAlive(transport.description))
        assertEquals(listOf(true, false), listener.states)
    }
}
