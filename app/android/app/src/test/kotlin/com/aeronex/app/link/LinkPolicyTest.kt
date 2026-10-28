package com.aeronex.app.link

import com.aeronex.app.link.mavlink.CommandLong
import com.aeronex.app.link.mavlink.MavlinkEncoder
import com.aeronex.app.link.mavlink.MavlinkParser
import com.aeronex.app.link.mavlink.RadioStatus
import com.aeronex.app.link.mavlink.SetupSigning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the link refuses to send, whatever the caller asks (spec 4.13 and 8.3). Uses in-memory transports. */
class LinkPolicyTest {
    private val sessions = mutableListOf<LinkSession>()
    private val fc = MavlinkEncoder(1, 1)
    private val setup = SetupSigning(1, 1, key32, 42L)

    @After fun tearDown() = sessions.forEach { it.stop() }

    private fun start(kind: TransportKind): Pair<PipeTransport, LinkSession> {
        val pipe = PipeTransport(kind)
        val listener = RecordingListener()
        val link = LinkSession(pipe, MavlinkEncoder(), MavlinkParser(), TelemetryProfile.forKind(kind), listener)
        sessions += link
        link.start()
        return pipe to link
    }

    private fun bringUp(pipe: PipeTransport, link: LinkSession) {
        pipe.inject(fc.encode(vehicleHeartbeat))
        waitFor("link up") { link.stats.up }
    }

    private fun setupSigningWritten(pipe: PipeTransport): Boolean {
        Thread.sleep(150)
        return pipe.writtenMessages().any { it is SetupSigning }
    }

    @Test fun setupSigningGoesOverADirectFcCableToTheConnectedFc() {
        val (pipe, link) = start(TransportKind.USB_DIRECT_FC)
        assertFalse("no FC heartbeat seen yet", link.send(setup))
        bringUp(pipe, link)
        assertTrue(link.send(setup))
        waitFor("SETUP_SIGNING written") { pipe.writtenMessages().contains(setup) }
        assertEquals(1L, link.stats.setupSigningRefused)
    }

    @Test fun setupSigningIsNeverBroadcastOrSentToAnotherSystem() {
        val (pipe, link) = start(TransportKind.USB_DIRECT_FC)
        bringUp(pipe, link)
        assertFalse(link.send(SetupSigning(0, 0, key32, 42L)))
        assertFalse(link.send(SetupSigning(2, 1, key32, 42L)))
        assertFalse(setupSigningWritten(pipe))
        assertEquals(2L, link.stats.setupSigningRefused)
    }

    @Test fun aRadioOnTheCableMeansItIsNotADirectFcLink() {
        val (pipe, link) = start(TransportKind.USB_DIRECT_FC)
        bringUp(pipe, link)
        pipe.inject(MavlinkEncoder(51, 68).encode(RadioStatus(200, 190, 100, 40, 38, 0, 0)))
        waitFor("RADIO_STATUS received") { link.stats.rxFrames >= 2 }
        assertFalse(link.send(setup))
        assertFalse(link.sendPriority(setup))
        assertFalse(setupSigningWritten(pipe))
    }

    @Test fun setupSigningIsRefusedOverUsbRadios() {
        val (pipe, link) = start(TransportKind.USB_RADIO)
        bringUp(pipe, link)
        assertFalse(link.send(setup))
        assertFalse(link.sendPriority(setup))
        assertFalse(setupSigningWritten(pipe))
        assertEquals(2L, link.stats.setupSigningRefused)
    }

    @Test fun forcedDisarmFlightTerminationMotorTestAndParameterWritesAreRefused() {
        val (pipe, link) = start(TransportKind.UDP)
        val refused = listOf(
            CommandLong(1, 1, 400, param1 = 0f, param2 = 21196f), // forced disarm
            CommandLong(1, 1, 400, param1 = 1f, param2 = 2989f), // forced arm
            CommandLong(1, 1, 185, param1 = 1f), // flight termination
            CommandLong(1, 1, 209, param1 = 1f), // motor test
            CommandLong(1, 1, 180, param1 = 1f), // DO_SET_PARAMETER
        )
        refused.forEach { assertFalse("$it", link.send(it)); assertFalse("$it", link.sendPriority(it)) }
        assertEquals(10L, link.stats.unsafeCommandsRefused)
        val disarm = CommandLong(1, 1, 400, param1 = 0f) // STOP on the ground: non-forced
        assertTrue(link.sendPriority(disarm))
        waitFor("non-forced disarm written") { pipe.writtenMessages().contains(disarm) }
        assertTrue(pipe.writtenMessages().none { it in refused })
    }
}
