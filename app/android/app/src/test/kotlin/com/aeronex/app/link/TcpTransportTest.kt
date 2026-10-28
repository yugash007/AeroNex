package com.aeronex.app.link

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpTransportTest {
    private val server = ServerSocket(0, 4, loopback)
    private val clients = LinkedBlockingQueue<Socket>()
    private val acceptor = thread(isDaemon = true) {
        while (true) clients.add(try { server.accept() } catch (_: Exception) { break })
    }
    private val transport = TcpTransport("127.0.0.1", server.localPort, retryDelayMs = 100)

    @After fun tearDown() {
        transport.close()
        server.close()
        clients.forEach { it.close() }
        acceptor.join(1_000)
    }

    /** Reads until something arrives or [timeoutMs] passes; the transport connects lazily from read(). */
    private fun readSome(buffer: ByteArray, timeoutMs: Long = 3_000): Int {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val n = transport.read(buffer, 50)
            if (n != 0) return n
        }
        return 0
    }

    @Test fun connectsFromTheReadLoopAndCarriesBytesBothWays() {
        transport.open()
        assertEquals(TransportKind.TCP, transport.kind)
        assertFalse("not connected yet", transport.write(byteArrayOf(1)))
        val buffer = ByteArray(64)
        assertEquals(0, transport.read(buffer, 50)) // connects
        val vehicle = clients.poll(3, TimeUnit.SECONDS)
        assertNotNull(vehicle)
        vehicle!!.getOutputStream().write(byteArrayOf(4, 5))
        assertEquals(2, readSome(buffer))
        assertArrayEquals(byteArrayOf(4, 5), buffer.copyOf(2))
        assertTrue(transport.write(byteArrayOf(7, 7, 7)))
        val got = ByteArray(3)
        vehicle.getInputStream().read(got)
        assertArrayEquals(byteArrayOf(7, 7, 7), got)
    }

    @Test fun reconnectsAfterThePeerDropsAndOnlyReturnsMinusOneAfterClose() {
        transport.open()
        val buffer = ByteArray(64)
        transport.read(buffer, 50)
        val first = clients.poll(3, TimeUnit.SECONDS)!!
        first.close()
        // The drop reads as "nothing yet", never as closed, and the transport dials again.
        val second = run {
            val deadline = System.nanoTime() + 3_000_000_000L
            var socket: Socket? = null
            while (socket == null && System.nanoTime() < deadline) {
                assertEquals(0, transport.read(buffer, 50))
                socket = clients.poll()
            }
            socket
        }
        assertNotNull("reconnected", second)
        second!!.getOutputStream().write(byteArrayOf(3))
        assertEquals(1, readSome(buffer))
        assertEquals(2L, transport.connects)
        transport.close()
        assertEquals(-1, transport.read(buffer, 50))
        assertFalse(transport.write(byteArrayOf(1)))
    }

    @Test fun closeUnblocksAPendingRead() {
        transport.open()
        val buffer = ByteArray(64)
        transport.read(buffer, 50)
        clients.poll(3, TimeUnit.SECONDS)!!
        val result = AtomicInteger(Int.MIN_VALUE)
        val reader = thread { result.set(transport.read(buffer, 10_000)) }
        Thread.sleep(150)
        transport.close()
        reader.join(2_000)
        assertFalse(reader.isAlive)
        assertEquals(-1, result.get())
    }

    @Test fun anUnreachablePeerReadsAsNothingUntilClosed() {
        val port = ServerSocket(0, 1, loopback).use { it.localPort } // closed: connection refused
        val dead = TcpTransport("127.0.0.1", port, retryDelayMs = 100)
        dead.open()
        try {
            repeat(5) { assertEquals(0, dead.read(ByteArray(8), 50)) }
            assertFalse(dead.write(byteArrayOf(1)))
        } finally {
            dead.close()
        }
        assertEquals(-1, dead.read(ByteArray(8), 50))
    }
}
