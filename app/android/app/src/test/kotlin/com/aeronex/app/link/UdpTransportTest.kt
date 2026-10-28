package com.aeronex.app.link

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UdpTransportTest {
    private val peer = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 2_000 }
    private val rogue = DatagramSocket(InetSocketAddress(loopback, 0))
    private val transport = UdpTransport("127.0.0.1", peer.localPort, 0, localAddress = "127.0.0.1")

    @After fun tearDown() {
        transport.close()
        peer.close()
        rogue.close()
    }

    /** The transport's local address, learned the way MAVProxy learns it: from its first datagram. */
    private fun appAddress(): InetSocketAddress {
        assertTrue(transport.write(byteArrayOf(1)))
        val packet = DatagramPacket(ByteArray(16), 16)
        peer.receive(packet)
        return packet.socketAddress as InetSocketAddress
    }

    @Test fun writesReachThePeerAndThePeersDatagramsAreRead() {
        transport.open()
        val app = appAddress()
        peer.send(DatagramPacket(byteArrayOf(9, 8, 7), 3, app))
        val buffer = ByteArray(64)
        assertEquals(3, transport.read(buffer, 1_000))
        assertArrayEquals(byteArrayOf(9, 8, 7), buffer.copyOf(3))
        assertEquals(TransportKind.UDP, transport.kind)
    }

    @Test fun datagramsFromAnyOtherPortAreDroppedAndCounted() {
        transport.open()
        val app = appAddress()
        rogue.send(DatagramPacket(byteArrayOf(6, 6, 6), 3, app))
        val buffer = ByteArray(64)
        val deadline = System.nanoTime() + 1_000_000_000L
        while (transport.droppedDatagrams == 0L && System.nanoTime() < deadline) assertEquals(0, transport.read(buffer, 50))
        assertEquals(1L, transport.droppedDatagrams)
        peer.send(DatagramPacket(byteArrayOf(5), 1, app))
        var n = 0
        while (n == 0 && System.nanoTime() < deadline + 1_000_000_000L) n = transport.read(buffer, 50)
        assertEquals(1, n)
        assertEquals(5.toByte(), buffer[0])
    }

    @Test fun readTimesOutWithZeroAndReturnsMinusOneOnceClosed() {
        transport.open()
        val started = System.nanoTime()
        assertEquals(0, transport.read(ByteArray(64), 100))
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 80)
        transport.close()
        assertEquals(-1, transport.read(ByteArray(64), 100))
        assertFalse(transport.write(byteArrayOf(1)))
    }

    @Test fun closeUnblocksAPendingRead() {
        transport.open()
        val result = AtomicInteger(Int.MIN_VALUE)
        val reader = thread { result.set(transport.read(ByteArray(64), 10_000)) }
        Thread.sleep(150)
        transport.close()
        reader.join(2_000)
        assertFalse(reader.isAlive)
        assertEquals(-1, result.get())
    }

    @Test fun theBindCallbackGetsTheSocketBeforeAnyTraffic() {
        val seen = mutableListOf<DatagramSocket>()
        val bound = UdpTransport("127.0.0.1", peer.localPort, 0, bind = { seen += it }, localAddress = "127.0.0.1")
        try {
            bound.open()
            assertEquals(1, seen.size)
            assertTrue(bound.write(byteArrayOf(1)))
        } finally {
            bound.close()
        }
    }

    @Test fun openFailsWhenTheLocalPortIsTaken() {
        val taken = UdpTransport("127.0.0.1", 14550, rogue.localPort, localAddress = "127.0.0.1")
        try {
            taken.open()
            fail("expected the bind to fail")
        } catch (_: IOException) {
        } finally {
            taken.close()
        }
    }

    @Test fun nothingWorksBeforeOpen() {
        assertFalse(transport.write(byteArrayOf(1)))
        assertEquals(-1, transport.read(ByteArray(8), 10))
    }
}
