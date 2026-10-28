package com.aeronex.app.session

import com.aeronex.app.link.TcpTransport
import com.aeronex.app.link.TelemetryProfile
import com.aeronex.app.link.UdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionConfigTest {
    private fun rejected(arguments: Any?): SessionException = try {
        SessionConfig.fromMap(arguments)
        fail("accepted $arguments")
        throw AssertionError()
    } catch (e: SessionException) {
        e
    }

    @Test fun readsTheDartMap() {
        val udp = SessionConfig.fromMap(mapOf("transport" to "udp", "host" to " 192.168.4.1 ", "remotePort" to 14550,
            "localPort" to 14551, "radioLink" to false))
        assertEquals(SessionConfig(LinkType.UDP, "192.168.4.1", 14550, 14551, radioLink = false), udp)
        val tcp = SessionConfig.fromMap(mapOf("transport" to "tcp", "host" to "10.0.2.2", "remotePort" to 5760L))
        assertEquals(SessionConfig(LinkType.TCP, "10.0.2.2", 5760, 0, radioLink = false), tcp)
    }

    @Test fun theRadioSettingPicksTheRadioProfile() {
        val base = SessionConfig(LinkType.UDP, "10.0.0.1", 14550)
        assertEquals(TelemetryProfile.WIFI, base.profile)
        assertEquals(TelemetryProfile.RADIO, base.copy(radioLink = true).profile)
        assertEquals(TelemetryProfile.WIFI, base.copy(type = LinkType.TCP).profile)
        assertEquals(TelemetryProfile.RADIO, base.copy(type = LinkType.TCP, radioLink = true).profile)
    }

    @Test fun badSettingsAreRejectedWithAnOperatorMessage() {
        val good = mapOf("transport" to "udp", "host" to "127.0.0.1", "remotePort" to 14550, "localPort" to 0)
        assertEquals(SessionConfig.MISSING, rejected(null).message)
        assertEquals(SessionException.BAD_ARGS, rejected(null).code)
        assertEquals(SessionConfig.BAD_TRANSPORT, rejected(good + ("transport" to "serial")).message)
        assertEquals(SessionConfig.BAD_HOST, rejected(good + ("host" to "  ")).message)
        assertEquals(SessionConfig.BAD_HOST, rejected(good - "host").message)
        assertEquals(SessionConfig.BAD_REMOTE_PORT, rejected(good + ("remotePort" to 0)).message)
        assertEquals(SessionConfig.BAD_REMOTE_PORT, rejected(good + ("remotePort" to 70_000)).message)
        assertEquals(SessionConfig.BAD_REMOTE_PORT, rejected(good + ("remotePort" to 14550.5)).message)
        assertEquals(SessionConfig.BAD_LOCAL_PORT, rejected(good + ("localPort" to -1)).message)
        assertEquals(SessionConfig.BAD_RADIO_LINK, rejected(good + ("radioLink" to "yes")).message)
    }

    @Test fun plainTransportsFollowTheType() {
        val udp = SessionConfig.plainTransport(SessionConfig(LinkType.UDP, "127.0.0.1", 14550, 0))
        assertTrue(udp.transport is UdpTransport)
        assertEquals("UDP 127.0.0.1:14550 local *:0", udp.transport.description)
        val tcp = SessionConfig.plainTransport(SessionConfig(LinkType.TCP, "127.0.0.1", 5760))
        assertTrue(tcp.transport is TcpTransport)
    }
}
