package com.aeronex.app.link

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * MAVLink over TCP, e.g. mavlink-router on port 5760. [open] does no network I/O: the RX thread dials from [read],
 * and dials again after the connection drops ([retryDelayMs] between failed attempts), so a router restart reads as
 * a quiet link rather than a closed one. [read] returns -1 only after [close]. Nothing written while disconnected is
 * kept: [write] returns false and the frame is gone (no replay after a reconnect).
 */
class TcpTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 2_000,
    private val retryDelayMs: Long = 1_000,
) : Transport {
    override val kind = TransportKind.TCP
    override val description = "TCP $host:$port"

    private class Connection(val socket: Socket, val input: InputStream, val output: OutputStream)

    private val lock = Any()
    @Volatile private var opened = false
    @Volatile private var closed = false
    @Volatile private var connection: Connection? = null
    private var dialing: Socket? = null // guarded by lock

    // RX thread only.
    private var socketTimeoutMs = -1
    private var retryAt = 0L

    /** Successful connections so far; changes only on the RX thread, in [read]. */
    @Volatile override var connections = 0L
        private set

    init {
        require(port in 1..65535) { "Port must be 1..65535" }
    }

    override fun open() {
        synchronized(lock) {
            check(!opened && !closed) { "A TcpTransport opens once" }
            opened = true
        }
    }

    override fun write(bytes: ByteArray): Boolean {
        val c = connection ?: return false
        return try {
            c.output.write(bytes)
            true
        } catch (_: IOException) {
            c.socket.close() // the RX thread sees the failure and redials
            false
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed || !opened) return -1
        val c = connection
        if (c == null) {
            dial(timeoutMs)
            return if (closed) -1 else 0
        }
        return try {
            if (timeoutMs != socketTimeoutMs) {
                c.socket.soTimeout = timeoutMs.coerceAtLeast(1)
                socketTimeoutMs = timeoutMs
            }
            val n = c.input.read(buffer, 0, buffer.size)
            if (n >= 0) n else hangUp(c)
        } catch (_: SocketTimeoutException) {
            0
        } catch (_: IOException) {
            hangUp(c)
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            dialing?.close()
            connection?.socket?.close()
        }
    }

    private fun dial(timeoutMs: Int) {
        val waitMs = (retryAt - System.nanoTime()) / 1_000_000
        if (waitMs > 0) {
            pause(minOf(waitMs, timeoutMs.toLong()))
            return
        }
        val socket = Socket()
        synchronized(lock) {
            if (closed) return
            dialing = socket
        }
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            val c = Connection(socket, socket.getInputStream(), socket.getOutputStream())
            synchronized(lock) {
                dialing = null
                if (closed) {
                    socket.close()
                    return
                }
                connection = c
            }
            socketTimeoutMs = -1
            connections++
        } catch (_: IOException) {
            synchronized(lock) { dialing = null }
            socket.close()
            retryAt = System.nanoTime() + retryDelayMs * 1_000_000
        }
    }

    /** Drops [c]; the next read dials again at once (backing off only after a failed dial). */
    private fun hangUp(c: Connection): Int {
        synchronized(lock) { if (connection === c) connection = null }
        c.socket.close()
        return if (closed) -1 else 0
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms.coerceAtLeast(1))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
