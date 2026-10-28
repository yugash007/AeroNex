package com.aeronex.app.link

import com.aeronex.app.link.mavlink.CommandLong
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.MavlinkEncoder
import com.aeronex.app.link.mavlink.MavlinkFrame
import com.aeronex.app.link.mavlink.MavlinkParser
import com.aeronex.app.link.mavlink.ParamRequestRead
import com.aeronex.app.link.mavlink.SetPositionTargetLocalNed
import com.aeronex.app.link.mavlink.SetupSigning
import com.aeronex.app.link.mavlink.Signing
import com.aeronex.app.link.mavlink.TimestampStore
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** LinkSession over loopback UDP against a fake ArduCopter that behaves like MAVProxy's udpin output. */
class LinkSessionTest {
    private val cleanup = mutableListOf<AutoCloseable>()

    @After fun tearDown() {
        cleanup.asReversed().forEach { runCatching { it.close() } }
    }

    private fun vehicle(signing: Signing? = null) = UdpVehicle(signing).also { cleanup += it }

    private fun udp(vehicle: UdpVehicle) = UdpTransport("127.0.0.1", vehicle.port, 0, localAddress = "127.0.0.1")

    private fun session(
        transport: Transport,
        listener: LinkListener = RecordingListener(),
        encoder: MavlinkEncoder = MavlinkEncoder(),
        parser: MavlinkParser = MavlinkParser(),
        profile: TelemetryProfile = TelemetryProfile.forKind(transport.kind),
        heartbeatPeriodMs: Long = 1000,
    ) = LinkSession(transport, encoder, parser, profile, listener, heartbeatPeriodMs = heartbeatPeriodMs)
        .also { cleanup += AutoCloseable { it.stop() } }

    /** SET_MESSAGE_INTERVAL requests seen by the vehicle: message id → interval µs (last one wins). */
    private fun intervals(vehicle: FakeVehicle) =
        vehicle.commands(511).associate { it.param1.toInt() to it.param2.toLong() }

    private fun expectedIntervals(profile: TelemetryProfile, streaming: Boolean) =
        profile.rates.mapValues { TelemetryProfile.intervalUs(it.value) } +
            (85 to if (streaming) TelemetryProfile.intervalUs(profile.positionTargetHz) else -1L)

    private fun homeRequests(vehicle: FakeVehicle) = vehicle.commands(512).filter { it.param1 == 242f }

    @Test fun gcsHeartbeatGoesOutEverySecondFromStartEvenWithoutAVehicle() {
        val vehicle = vehicle().apply { heartbeats = false }
        val link = session(udp(vehicle))
        val started = System.nanoTime()
        link.start()
        Thread.sleep(3_400)
        val beats = vehicle.gcsHeartbeats()
        assertEquals("4 heartbeats in 3.4 s", 4, beats.size)
        assertTrue("first heartbeat immediately", (beats[0].atNanos - started) / 1_000_000 < 300)
        beats.zipWithNext { a, b -> (b.atNanos - a.atNanos) / 1_000_000 }.forEach { gap ->
            assertTrue("heartbeat gap $gap ms", gap in 800..1200)
        }
        val heartbeat = beats[0].frame.message as Heartbeat
        assertEquals(6, heartbeat.type) // MAV_TYPE_GCS
        assertEquals(8, heartbeat.autopilot) // MAV_AUTOPILOT_INVALID
        assertEquals(4, heartbeat.systemStatus) // MAV_STATE_ACTIVE
        assertFalse(link.stats.up)
    }

    @Test fun heartbeatIsNeverThrottledByAFullQueue() {
        val vehicle = vehicle().apply { heartbeats = false }
        val link = session(SlowTransport(udp(vehicle), delayMs = 5), heartbeatPeriodMs = 200)
        link.start()
        val flooding = AtomicBoolean(true)
        val flooder = thread {
            while (flooding.get()) link.send(ParamRequestRead(1, 1, "FS_GCS_ENABLE"))
        }
        Thread.sleep(2_000)
        flooding.set(false)
        flooder.join()
        assertTrue("the queue overflowed", link.stats.txDropped > 0)
        val beats = vehicle.gcsHeartbeats()
        assertTrue("${beats.size} heartbeats in 2 s", beats.size >= 9)
        beats.zipWithNext { a, b -> (b.atNanos - a.atNanos) / 1_000_000 }.forEach { gap ->
            assertTrue("heartbeat gap $gap ms under load", gap in 100..320)
        }
    }

    @Test fun priorityMessagesJumpTheQueueAndDropQueuedVehicleCommands() {
        val vehicle = vehicle().apply { heartbeats = false }
        val gated = GatedTransport(udp(vehicle))
        val link = session(gated)
        link.start()
        waitFor("TX blocked on the first heartbeat") { gated.writesStarted.get() == 1 }
        val audit = ParamRequestRead(1, 1, "FS_GCS_ENABLE")
        val guided = CommandLong(1, 1, 176, param1 = 1f, param2 = 4f)
        val move = SetPositionTargetLocalNed(0, 1, 1, 9, 3576, 2f, 0f, 0f)
        val rate = CommandLong(1, 1, 511, param1 = 32f, param2 = 200_000f)
        val home = CommandLong(1, 1, 512, param1 = 242f)
        val brake = CommandLong(1, 1, 176, param1 = 1f, param2 = 17f)
        listOf(audit, guided, move, rate, home).forEach { assertTrue(link.send(it)) }
        assertTrue(link.sendPriority(brake))
        assertTrue(link.sendPriority(brake.copy(confirmation = 1)))
        gated.release()
        waitFor("the queue drained") { vehicle.received.size >= 6 }
        Thread.sleep(200)
        val sent = vehicle.received.map { it.frame.message }
        assertTrue(sent[0] is Heartbeat)
        assertEquals(listOf(brake, brake.copy(confirmation = 1), audit, rate, home), sent.filter { it !is Heartbeat })
        assertEquals(2L, link.stats.purgedByPriority)
    }

    @Test fun telemetryIsRequestedWhenTheLinkComesUpAndAgainAfterItWasDown() {
        val vehicle = vehicle()
        val listener = RecordingListener()
        val link = session(udp(vehicle), listener)
        link.start()
        waitFor("link up") { listener.states == listOf(true) }
        waitFor("profile requested") { intervals(vehicle) == expectedIntervals(TelemetryProfile.WIFI, false) }
        waitFor("home requested") { homeRequests(vehicle).size == 1 }
        assertTrue(vehicle.commands(511).all { it.targetSystem == 1 && it.targetComponent == 1 })

        assertTrue(link.setPositionTargetStreaming(true))
        waitFor("POSITION_TARGET at 5 Hz") { intervals(vehicle)[85] == 200_000L }

        vehicle.heartbeats = false
        waitFor("link down", timeoutMs = 6_000) { listener.states == listOf(true, false) }
        val silentMs = (System.nanoTime() - vehicle.lastHeartbeatSentAt) / 1_000_000
        assertTrue("down after ${silentMs} ms of silence", silentMs in 2_900..3_600)
        assertFalse(link.stats.up)

        vehicle.received.clear()
        vehicle.heartbeats = true
        waitFor("link up again") { listener.states == listOf(true, false, true) }
        waitFor("profile re-requested, move still pending") {
            intervals(vehicle) == expectedIntervals(TelemetryProfile.WIFI, streaming = true)
        }
        waitFor("home re-requested") { homeRequests(vehicle).size == 1 }

        assertTrue(link.setPositionTargetStreaming(false))
        waitFor("POSITION_TARGET off") { intervals(vehicle)[85] == -1L }
    }

    @Test fun theRadioProfileIsUsedWhenGiven() {
        val vehicle = vehicle()
        val listener = RecordingListener()
        session(udp(vehicle), listener, profile = TelemetryProfile.RADIO).start()
        waitFor("radio profile requested") { intervals(vehicle) == expectedIntervals(TelemetryProfile.RADIO, false) }
    }

    @Test fun datagramsFromAnyOtherSourceAreDropped() {
        val vehicle = vehicle().apply { heartbeats = false }
        val listener = RecordingListener()
        val transport = udp(vehicle)
        val link = session(transport, listener)
        link.start()
        waitFor("the vehicle learned our port") { vehicle.peer != null }
        val impostor = DatagramSocket(InetSocketAddress(loopback, 0)).also { cleanup += it }
        val forged = MavlinkEncoder(1, 1).encode(vehicleHeartbeat)
        repeat(5) { impostor.send(DatagramPacket(forged, forged.size, vehicle.peer)) }
        waitFor("impostor datagrams dropped") { transport.droppedDatagrams == 5L }
        Thread.sleep(100)
        assertTrue(listener.frames.isEmpty())
        assertTrue(listener.states.isEmpty())
        assertEquals(0L, link.stats.rxFrames)

        vehicle.heartbeats = true
        waitFor("link up from the real vehicle") { listener.states == listOf(true) }
        assertTrue(listener.frames.all { it.sysId == 1 })
    }

    @Test fun setupSigningIsNeverSentOverUdp() {
        val vehicle = vehicle()
        val listener = RecordingListener()
        val link = session(udp(vehicle), listener)
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

    @Test fun signedLinkRoundTrip() {
        val ours = Signing(key32, 1, TimestampStore.InMemory())
        val vehicle = vehicle(Signing(key32, 0, TimestampStore.InMemory()))
        val listener = RecordingListener()
        val link = session(udp(vehicle), listener, MavlinkEncoder(signing = ours), MavlinkParser(ours))
        link.start()
        waitFor("signed link up") { listener.states == listOf(true) }
        assertTrue(link.stats.signed)
        assertTrue(listener.frames.all { it.signed })
        // The vehicle's parser verifies with the shared key: everything we sent passed and carries our link id.
        waitFor("signed telemetry requests verified") { vehicle.commands(511).isNotEmpty() }
        assertTrue(vehicle.received.all { it.frame.signed && it.frame.signature!!.linkId == 1 })

        // Unsigned and wrong-key frames from the vehicle's address are dropped and counted.
        val before = listener.frames.size
        vehicle.writeRaw(MavlinkEncoder(1, 1).encode(vehicleHeartbeat))
        vehicle.writeRaw(MavlinkEncoder(1, 1, Signing(ByteArray(32), 0, TimestampStore.InMemory())).encode(vehicleHeartbeat))
        waitFor("rejections counted") { link.stats.unsignedRejected == 1L && link.stats.badSignature == 1L }
        assertTrue(listener.frames.drop(before).all { it.signed })
    }

    @Test fun anUnsignedLinkSaysSo() {
        val vehicle = vehicle()
        val link = session(udp(vehicle))
        link.start()
        assertFalse(link.stats.signed)
    }

    @Test fun stopEndsBothThreadsAndSendsNothingMore() {
        val vehicle = vehicle()
        val listener = RecordingListener()
        val transport = udp(vehicle)
        val link = session(transport, listener)
        link.start()
        waitFor("link up") { listener.states == listOf(true) }
        assertEquals(2, linkThreadsAlive(transport.description).size)
        link.stop()
        assertEquals(emptyList<Thread>(), linkThreadsAlive(transport.description))
        assertEquals(listOf(true, false), listener.states)
        assertFalse(link.send(ParamRequestRead(1, 1, "AFTER_STOP")))
        assertFalse(link.sendPriority(CommandLong(1, 1, 176, param1 = 1f, param2 = 17f)))
        assertEquals(-1, transport.read(ByteArray(8), 10))
        val beats = vehicle.gcsHeartbeats().size
        Thread.sleep(1_300)
        assertEquals(beats, vehicle.gcsHeartbeats().size)
        link.stop() // idempotent
        try {
            link.start()
            fail("a stopped session cannot restart")
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun aThrowingListenerDoesNotTakeTheLinkDown() {
        val vehicle = vehicle()
        val states = mutableListOf<Boolean>()
        val listener = object : LinkListener {
            override fun onFrame(frame: MavlinkFrame) = throw IllegalStateException("listener bug")
            override fun onLinkState(up: Boolean) { synchronized(states) { states += up } }
        }
        val link = session(udp(vehicle), listener)
        link.start()
        waitFor("link up") { synchronized(states) { states == listOf(true) } }
        Thread.sleep(500)
        assertTrue(link.stats.listenerErrors >= 2)
        assertTrue(link.stats.rxFrames >= 2)
        assertTrue(link.stats.up)
    }

    @Test fun statsCountTrafficAndAgeOfTheLastFrame() {
        val vehicle = vehicle()
        val listener = RecordingListener()
        val link = session(udp(vehicle), listener)
        assertEquals(null, link.stats.lastRxElapsedMs)
        link.start()
        waitFor("link up") { listener.states == listOf(true) }
        val stats = link.stats
        assertTrue(stats.up)
        assertTrue(stats.rxFrames >= 1 && stats.rxBytes > 0)
        assertTrue(stats.txFrames >= 1 && stats.txBytes > 0)
        assertTrue(stats.lastRxElapsedMs!! in 0..500)
        assertTrue(stats.lastHeartbeatElapsedMs!! in 0..500)
        assertEquals(0L, stats.badCrc)
    }
}
