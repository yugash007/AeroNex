package com.aeronex.app.session

import com.aeronex.app.link.LinkListener
import com.aeronex.app.link.LinkSession
import com.aeronex.app.link.TelemetryProfile
import com.aeronex.app.link.mavlink.MavlinkEncoder
import com.aeronex.app.link.mavlink.MavlinkFrame
import com.aeronex.app.link.mavlink.MavlinkParser
import com.aeronex.app.vehicle.executor.FakeArduCopter
import com.aeronex.app.vehicle.executor.waitFor
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadSetupTransportTest {
    @Test fun theSetupRunsOnceOnEachLinkThreadBeforeItsFirstIo() {
        val threads = CopyOnWriteArrayList<String>()
        FakeArduCopter().use { vehicle ->
            val transport = ThreadSetupTransport(vehicle.transport()) { threads += Thread.currentThread().name }
            val link = LinkSession(transport, MavlinkEncoder(), MavlinkParser(), TelemetryProfile.WIFI, object : LinkListener {
                override fun onFrame(frame: MavlinkFrame) {}
                override fun onLinkState(up: Boolean) {}
            })
            link.start()
            waitFor("link up") { link.stats.up }
            Thread.sleep(300)
            link.stop()
        }
        assertEquals(threads.toString(), 2, threads.size)
        assertTrue(threads.any { it.startsWith("aeronex-link-rx") })
        assertTrue(threads.any { it.startsWith("aeronex-link-tx") })
    }

    @Test fun aFailingSetupNeverBreaksTheLink() {
        FakeArduCopter().use { vehicle ->
            val transport = ThreadSetupTransport(vehicle.transport()) { throw SecurityException("no priority for you") }
            val link = LinkSession(transport, MavlinkEncoder(), MavlinkParser(), TelemetryProfile.WIFI, object : LinkListener {
                override fun onFrame(frame: MavlinkFrame) {}
                override fun onLinkState(up: Boolean) {}
            })
            link.start()
            waitFor("link up regardless") { link.stats.up }
            link.stop()
        }
    }
}
